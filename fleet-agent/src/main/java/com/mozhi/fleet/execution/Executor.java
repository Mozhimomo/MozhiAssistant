package com.mozhi.fleet.execution;

import com.mozhi.fleet.actions.*;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.FleetResources;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;
import com.mozhi.fleet.planning.ExecutionHistory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 主线程执行当前步骤，无 LLM；步骤选择与计划推进由 Agent 负责。 */
public final class Executor {
    public record State(ExecutionHistory.Snapshot history, List<ExecutionResult> terminal,
                        Step active, String blockedReason) {}
    private final Thread owner = Thread.currentThread();
    private final ActionContext context;
    private final ExecutionHistory history;
    private final com.mozhi.fleet.tools.FleetToolRegistry tools;
    private final Map<String, ExecutionResult> terminal = new LinkedHashMap<>();
    private Step active;
    private String blockedReason = "";

    public Executor(ActionContext context, ExecutionHistory history) {
        this(context, history, List.of(new BuyAction(), new SellAction(), new MoveToAction(), new FollowFleetAction(), new ReturnToPlayerAction(), new CalculateTradeRouteAction(), new PrepareTradeHopAction(), new TransferToPlayerAction()));
    }

    public Executor(ActionContext context, ExecutionHistory history, List<Action> actions) {
        this.context = Objects.requireNonNull(context);
        this.history = Objects.requireNonNull(history);
        tools = new com.mozhi.fleet.tools.FleetToolRegistry(actions);
    }

    /** 与实际执行使用同一份动作契约，直接传给 PlanningRequest。 */
    public List<ActionSpec> actionSpecs() { return tools.specifications(); }

    public void validatePlan(Plan plan) {
        for (int i = 0; i < plan.steps().size(); i++) {
            Step step = plan.steps().get(i);
            tools.validate(step);
            if (step.action().equals("RETURN") && i != plan.steps().size() - 1) throw new IllegalArgumentException("RETURN 必须在末尾");
            if (step.tool().equals("FOLLOW_FLEET") && i != plan.steps().size() - 1) throw new IllegalArgumentException("持续跟随必须在计划末尾；后续行动应由新指令替换");
        }
    }

    public void validateGeneratedPlan(Plan plan) {
        requireOwner();
        validatePlan(plan);
        var completed = history.snapshot().completedStepIds();
        for (Step step : plan.steps()) if (terminal.containsKey(step.id()) || completed.contains(step.id()))
            throw new IllegalArgumentException("决策结果不能重复已执行的步骤：" + step.id());
    }

    public void cancelBackground() { tools.cancelBackground(); }
    public boolean backgroundStopped() { return tools.backgroundStopped(); }
    public void pause() { requireOwner(); if (active != null) tools.pause(active.tool()); }

    public ExecutionHistory.Snapshot historySnapshot() { requireOwner(); return history.snapshot(); }
    public List<ExecutionResult> tradeResults() {
        requireOwner();
        return terminal.values().stream().filter(result -> result.tradeReceipt() != null).toList();
    }
    public Map<String, Object> tradeSummary() {
        requireOwner();
        return com.mozhi.fleet.model.TradeSummary.of(List.copyOf(terminal.values()));
    }

    public FleetResources resources() {
        requireOwner();
        return com.mozhi.fleet.game.GameWorld.resources(context.fleet());
    }

    public State snapshot() {
        requireOwner();
        return new State(history.snapshot(), List.copyOf(terminal.values()), active, blockedReason);
    }

    /** 仅向新执行器恢复存档；保留失败终态和资产阻塞，不能借读档重放交易。 */
    public void restore(State state) {
        requireOwner();
        if (active != null || !terminal.isEmpty() || isBlocked() || !history.snapshot().recentResults().isEmpty())
            throw new IllegalStateException("只能向新执行器恢复存档");
        Objects.requireNonNull(state.history());
        Objects.requireNonNull(state.blockedReason());
        Map<String, ExecutionResult> restored = new LinkedHashMap<>();
        for (ExecutionResult result : state.terminal()) {
            if (result.status() != SUCCEEDED && result.status() != FAILED)
                throw new IllegalArgumentException("存档终态无效");
            if (restored.putIfAbsent(result.step().id(), result) != null)
                throw new IllegalArgumentException("存档终态重复");
        }
        if (state.active() != null && restored.containsKey(state.active().id()))
            throw new IllegalArgumentException("存档当前步骤已结束");
        history.restore(state.history());
        terminal.putAll(restored);
        active = state.active();
        blockedReason = state.blockedReason();
    }

    /** 开始新任务；资产状态不确定时不能通过新任务绕过阻塞。 */
    public void beginTask() {
        requireOwner();
        if (isBlocked()) throw new IllegalStateException(blockedReason);
        stop();
        terminal.clear();
        history.clear();
    }

    public ExecutionResult execute(Plan plan, int stepIndex) {
        requireOwner();
        Objects.requireNonNull(plan, "计划");
        if (stepIndex < 0 || stepIndex >= plan.steps().size()) throw new IllegalArgumentException("步骤下标越界");
        if (plan.steps().get(stepIndex).action().equals("RETURN") && stepIndex != plan.steps().size() - 1) {
            return publish(new ExecutionResult(plan.steps().get(stepIndex), FAILED, "RETURN 会移除分舰队，必须是计划最后一步"));
        }
        if (plan.steps().get(stepIndex).tool().equals("FOLLOW_FLEET") && stepIndex != plan.steps().size() - 1)
            return publish(new ExecutionResult(plan.steps().get(stepIndex), FAILED, "持续跟随必须是计划最后一步"));
        return execute(plan.steps().get(stepIndex));
    }

    public ExecutionResult execute(Step step) {
        requireOwner();
        Objects.requireNonNull(step, "步骤");
        ExecutionResult previous = terminal.get(step.id());
        if ((previous != null && !previous.step().equals(step)) || (active != null && active.id().equals(step.id()) && !active.equals(step))) {
            throw new IllegalArgumentException("同一步骤 ID 不能改变定义");
        }
        try {
            if (active != null && !active.id().equals(step.id())) stop();
            if (!blockedReason.isEmpty()) return publish(new ExecutionResult(step, FAILED, blockedReason));
            if (previous != null) return publish(previous);
            if (history.snapshot().completedStepIds().contains(step.id())) {
                return publish(new ExecutionResult(step, FAILED, "此步骤已在历史中完成，禁止再次执行；请由 Agent 核对进度"));
            }
            tools.validate(step);
            if (context.fleet() == context.player()) throw new IllegalStateException("不能将玩家舰队作为分舰队执行动作");
            if (context.fleet().isExpired() || context.fleet().getContainingLocation() == null) throw new IllegalStateException("受控舰队已不存在");
            active = step;
            if (context.sector().isPaused()) { tools.pause(step.tool()); return publish(new ExecutionResult(step, WAITING, "游戏暂停，等待恢复")); }
            if (context.fleet().getBattle() != null || context.fleet().isInHyperspaceTransition()) {
                return publish(new ExecutionResult(step, WAITING, "等待受控舰队结束战斗或跃迁"));
            }
            if (context.fleet().isAIMode()) context.fleet().setAIMode(false);
            ExecutionResult result = tools.execute(step, context);
            if (!result.step().equals(step)) throw new IllegalStateException("动作返回了其他步骤的结果");
            return publish(result);
        } catch (UncertainActionException error) {
            blockedReason = "资产变更无法确认，已停止后续动作：" + error.getMessage();
            return publish(new ExecutionResult(step, FAILED, blockedReason));
        } catch (RuntimeException error) {
            return publish(new ExecutionResult(step, FAILED, Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
        }
    }

    /** 停止原生移动任务，保留已有轨道；未结束的步骤之后仍可续接。 */
    public void stop() {
        requireOwner();
        if (active != null) {
            tools.stop(active.tool(), context);
            active = null;
        }
    }

    public boolean isBlocked() { requireOwner(); return !blockedReason.isEmpty(); }
    public String blockedReason() { requireOwner(); return blockedReason; }

    private ExecutionResult publish(ExecutionResult result) {
        if (result.status() == FAILED && active != null) {
            try { stop(); }
            catch (RuntimeException error) {
                blockedReason = (blockedReason.isEmpty() ? "" : blockedReason + "；") + "停止当前动作失败：" + error.getMessage();
                result = new ExecutionResult(result.step(), FAILED, blockedReason);
            }
        }
        if (result.status() == SUCCEEDED || result.status() == FAILED) {
            if (!terminal.containsKey(result.step().id()) && result.status() == FAILED
                    && (result.step().action().equals("BUY") || result.step().action().equals("SELL"))) {
                org.apache.log4j.Logger.getLogger(Executor.class).warn("交易失败：步骤 " + result.step().id()
                        + "，动作 " + result.step().action() + "，参数 " + result.step().parameters() + "，结果 " + result.result());
            }
            terminal.put(result.step().id(), result);
            active = null;
        }
        history.record(result);
        return result;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Executor 必须在创建它的游戏主线程调用");
    }
}
