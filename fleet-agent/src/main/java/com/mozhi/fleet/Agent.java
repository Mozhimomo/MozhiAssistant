package com.mozhi.fleet;

import com.mozhi.fleet.execution.Executor;
import com.mozhi.fleet.execution.Monitor;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.planning.Planner;
import com.mozhi.fleet.planning.PlanningRequest;
import com.mozhi.fleet.planning.PlanningResult;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * 游戏主线程上的 Agent 循环：管理任务、规划请求、计时和执行进度。
 * 将 Executor 的反馈交给 Monitor 检查，再按结论推进；每轮最多执行一个步骤。
 */
public final class Agent implements AutoCloseable {
    public enum Status { IDLE, PLANNING, EXECUTING, COMPLETED, BLOCKED, CANCELLED }
    public record View(String taskId, String goal, Status status, Plan plan, int currentStep,
                       ExecutionResult lastResult, boolean planning, String reason) {}
    public record State(View view, long revision, double elapsedSeconds, boolean executable,
                        String queuedReason, Executor.State execution) {}

    private final Thread owner = Thread.currentThread();
    private final Planner planner;
    private final Executor executor;
    private final Monitor monitor = new Monitor();
    private final Supplier<String> observations;
    private final double intervalSeconds;
    private String taskId = "", goal = "", reason = "", queuedReason = "";
    private Status status = Status.IDLE;
    private Plan plan;
    private int currentStep;
    private long revision;
    private double elapsedSeconds;
    private boolean executable, closed;
    private ExecutionResult lastResult;
    private Future<PlanningResult> pending;
    private PlanningRequest submitted;

    public Agent(Planner planner, Executor executor, Supplier<String> observations) {
        this(planner, executor, observations, 15);
    }

    public Agent(Planner planner, Executor executor, Supplier<String> observations, double intervalSeconds) {
        this.planner = Objects.requireNonNull(planner);
        this.executor = Objects.requireNonNull(executor);
        this.observations = Objects.requireNonNull(observations);
        if (!Double.isFinite(intervalSeconds) || intervalSeconds <= 0) throw new IllegalArgumentException("规划间隔必须为正秒数");
        this.intervalSeconds = intervalSeconds;
    }

    /** 新目标替换旧任务，立即提交初始规划。观察回调只在调用线程采集世界状态。 */
    public void start(String goal) {
        begin(goal);
        queuedReason = "新任务";
        submitIfNeeded();
    }

    /** 参数明确的直接指令（例如召回）无需先调用模型。 */
    public void start(Plan initial) {
        Objects.requireNonNull(initial);
        for (var step : initial.steps()) {
            executor.actionSpecs().stream().filter(spec -> spec.name().equals(step.action())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知动作：" + step.action())).validate(step.parameters());
        }
        for (int i = 0; i < initial.steps().size() - 1; i++)
            if (initial.steps().get(i).action().equals("RETURN")) throw new IllegalArgumentException("RETURN 必须在末尾");
        begin(initial.goal());
        plan = initial; executable = true; status = Status.EXECUTING;
        reason = "直接指令已就绪";
    }

    private void begin(String goal) {
        requireOwner();
        if (closed) throw new IllegalStateException("Agent 已关闭");
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("目标不能为空");
        cancelPending();
        executor.beginTask();
        taskId = UUID.randomUUID().toString();
        this.goal = goal;
        plan = null; currentStep = 0; revision = 0; elapsedSeconds = 0;
        lastResult = null; executable = false;
        status = Status.PLANNING;
        queuedReason = "";
    }

    public State snapshot() {
        requireOwner();
        return new State(view(), revision, elapsedSeconds, executable, queuedReason, executor.snapshot());
    }

    /** 不保存 Future；恢复执行下标和终态，未完成的后台规划使用新世界快照重新提交。 */
    public void restore(State saved) {
        requireOwner();
        if (closed || status != Status.IDLE || !taskId.isEmpty()) throw new IllegalStateException("只能向新 Agent 恢复存档");
        View state = Objects.requireNonNull(saved.view());
        Objects.requireNonNull(state.status());
        Objects.requireNonNull(state.taskId());
        Objects.requireNonNull(state.goal());
        Objects.requireNonNull(state.reason());
        Objects.requireNonNull(saved.queuedReason());
        if (saved.revision() < 0 || !Double.isFinite(saved.elapsedSeconds()) || saved.elapsedSeconds() < 0
                || state.currentStep() < 0 || (state.plan() == null ? state.currentStep() != 0 : state.currentStep() > state.plan().steps().size())
                || (saved.executable() && (state.plan() == null || state.currentStep() == state.plan().steps().size()))
                || (state.status() != Status.IDLE && (state.taskId().isBlank() || state.goal().isBlank())))
            throw new IllegalArgumentException("Agent 存档进度无效");
        executor.restore(saved.execution());
        taskId = state.taskId(); goal = state.goal(); status = state.status(); plan = state.plan();
        currentStep = state.currentStep(); lastResult = state.lastResult(); reason = state.reason();
        revision = saved.revision(); elapsedSeconds = saved.elapsedSeconds(); executable = saved.executable();
        queuedReason = saved.queuedReason();
        if (executor.isBlocked()) { block(executor.blockedReason()); return; }
        if (active() && (state.planning() || !executable)) queuedReason = "读档后使用最新状态重新规划剩余工作";
    }

    public void suspend(String reason) { requireOwner(); block(reason); }

    /** realSeconds 为未加速的实际时间增量；游戏暂停时不计时、不执行也不接纳新计划。 */
    public void advance(double realSeconds, boolean paused) {
        requireOwner();
        if (!Double.isFinite(realSeconds) || realSeconds < 0) throw new IllegalArgumentException("时间增量必须为非负秒数");
        if (closed || paused || !active()) return;
        elapsedSeconds = Math.min(intervalSeconds, elapsedSeconds + realSeconds);
        consume();
        if (!active()) return;
        if (executable) {
            try {
                lastResult = executor.execute(plan, currentStep);
                reason = lastResult.result();
                switch (monitor.check(lastResult)) {
                    case CONTINUE -> status = Status.EXECUTING;
                    case ADVANCE -> {
                        revision++;
                        currentStep++;
                        if (currentStep == plan.steps().size()) {
                            finish(Status.COMPLETED, "计划步骤已全部执行成功");
                            return;
                        }
                    }
                    case REPLAN -> {
                        revision++;
                        executable = false;
                        if (executor.isBlocked()) { block(executor.blockedReason()); return; }
                        status = Status.PLANNING;
                        queuedReason = "步骤失败：" + lastResult.result();
                    }
                }
            } catch (RuntimeException error) {
                block("执行无法继续：" + message(error));
                return;
            }
        }
        if (elapsedSeconds >= intervalSeconds && queuedReason.isEmpty()) queuedReason = "定期重新规划";
        submitIfNeeded();
    }

    /** 可由外部的简单事件检查触发；忙碌时只保留一次待规划请求。 */
    public void requestReplan(String reason) {
        requireOwner();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("触发原因不能为空");
        if (active()) queuedReason = reason;
    }

    public View view() {
        requireOwner();
        return new View(taskId, goal, status, plan, currentStep, lastResult, pending != null, reason);
    }

    public void cancel() { requireOwner(); finish(Status.CANCELLED, "任务已取消"); }

    @Override public void close() {
        requireOwner();
        if (closed) return;
        try { cancel(); }
        finally { closed = true; planner.close(); }
    }

    private void submitIfNeeded() {
        if (queuedReason.isEmpty() || pending != null || !active()) return;
        String trigger = queuedReason;
        queuedReason = "";
        elapsedSeconds = 0;
        try {
            PlanningRequest request = new PlanningRequest(taskId, revision, goal, observations.get(),
                    executor.historySnapshot(), trigger, executor.actionSpecs(), plan);
            pending = planner.plan(request);
            submitted = request;
            if (!executable) status = Status.PLANNING;
            reason = trigger;
        } catch (RuntimeException error) { planningFailed(error); }
    }

    private void consume() {
        if (pending == null || !pending.isDone()) return;
        Future<PlanningResult> future = pending;
        PlanningRequest request = submitted;
        pending = null; submitted = null;
        // 步骤完成/失败是关键进度变化；普通航行位置变化不会让规划结果反复失效。
        if (!taskId.equals(request.taskId()) || revision != request.revision()) {
            queuedReason = "执行进度已变化，使用最新结果重新规划";
            return;
        }
        try {
            PlanningResult result = future.get(); // 已经 isDone，不阻塞主线程。
            if (!taskId.equals(result.taskId()) || revision != result.revision()) {
                queuedReason = "规划结果版本不符，重新规划";
                return;
            }
            reason = result.reason();
            switch (result.decision()) {
                case REPLACE -> {
                    Plan next = result.plan();
                    if (lastResult != null && lastResult.status() == ExecutionResult.Status.FAILED
                            && next.steps().stream().anyMatch(step -> step.id().equals(lastResult.step().id()))) {
                        block("新计划复用了已失败的步骤，需要使用新的步骤 ID");
                        return;
                    }
                    boolean continuing = executable && plan.steps().get(currentStep).equals(next.steps().get(0));
                    if (!continuing) executor.stop();
                    plan = next; currentStep = 0; executable = true; status = Status.EXECUTING;
                }
                case KEEP -> {
                    if (!executable) { block("当前步骤已失败，不能保留原计划"); return; }
                    status = Status.EXECUTING;
                }
                case GOAL_REACHED -> finish(Status.COMPLETED, result.reason());
                case BLOCKED -> block(result.reason());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            planningFailed(error);
        } catch (ExecutionException | RuntimeException error) {
            planningFailed(error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error);
        }
    }

    private void planningFailed(Throwable error) {
        // 有可用计划就继续执行，下一周期再规划；没有可用计划时才暂停等待新指令。
        queuedReason = ""; elapsedSeconds = 0;
        if (executable) reason = "规划失败，继续当前计划：" + message(error);
        else block("规划失败：" + message(error));
    }

    private boolean active() { return status == Status.PLANNING || status == Status.EXECUTING; }
    private void block(String message) { finish(Status.BLOCKED, message); }

    private void finish(Status finalStatus, String message) {
        cancelPending();
        queuedReason = ""; executable = false;
        status = finalStatus; reason = message;
        try { executor.stop(); }
        catch (RuntimeException error) { status = Status.BLOCKED; reason = "停止动作失败：" + message(error); }
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(true);
        planner.cancel();
        pending = null; submitted = null;
    }

    private static String message(Throwable error) { return Objects.toString(error.getMessage(), error.getClass().getSimpleName()); }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Agent 必须在创建它的游戏主线程调用");
    }
}
