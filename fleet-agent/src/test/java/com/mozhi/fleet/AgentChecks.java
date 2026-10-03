package com.mozhi.fleet;

import com.mozhi.fleet.execution.Executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.mozhi.fleet.actions.Action;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.actions.UncertainActionException;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;
import com.mozhi.fleet.planning.ExecutionHistory;
import com.mozhi.fleet.planning.Planner;
import com.mozhi.llm.LlmClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 真正连接 Agent、Planner、Executor，使用本地假模型与动作验证协调流程。 */
public final class AgentChecks {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        sequentialCompletion();
        periodicAndPause();
        failureReplans();
        staleProgress();
        newTaskCancelsOldReply();
        planningFailureAndBlocked();
        persistedProgressAndBlocked();
        System.out.println("Agent checks passed");
    }

    private static void sequentialCompletion() throws Exception {
        try (Fixture f = new Fixture(call -> replace("first", "second"))) {
            f.action.result = step -> SUCCEEDED;
            f.agent.start("完成两步");
            until(f, () -> f.action.executed.size() == 1);
            check(f.agent.view().currentStep() == 1 && f.agent.view().status() == Agent.Status.EXECUTING,
                    "Advance one step without an extra review request");
            f.agent.advance(0, false);
            check(f.agent.view().status() == Agent.Status.COMPLETED && f.action.executed.size() == 2, "Finish after all steps succeed");
            f.agent.advance(100, false);
            check(f.calls.get() == 1 && f.action.executed.size() == 2, "Completed task neither replans nor executes again");
            check(f.history.snapshot().completedStepIds().size() == 2, "Actual results flow into shared history");
        }
    }

    private static void periodicAndPause() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(call -> {
            if (call == 0) return replace("ongoing");
            if (call == 1) { entered.countDown(); await(release); }
            return decision("KEEP");
        })) {
            f.agent.start("持续执行");
            until(f, () -> f.agent.view().plan() != null);
            String planId = f.agent.view().plan().id();
            f.agent.advance(14, false);
            check(f.calls.get() == 1, "Do not replan before the interval");
            int beforePause = f.action.executed.size();
            f.agent.advance(100, true);
            check(f.action.executed.size() == beforePause && f.calls.get() == 1, "Paused time and execution are ignored");
            f.agent.advance(1, false);
            await(entered);
            int beforePending = f.action.executed.size();
            f.agent.advance(15, false);
            f.agent.advance(15, false);
            check(f.calls.get() == 2 && f.action.executed.size() == beforePending + 2, "Keep executing while one replan is pending");
            release.countDown();
            until(f, () -> f.calls.get() == 3 && !f.agent.view().planning());
            check(f.agent.view().plan().id().equals(planId) && f.action.stops == 0,
                    "KEEP preserves the current action; overdue timers coalesce into one follow-up");
        } finally { release.countDown(); }
    }

    private static void failureReplans() throws Exception {
        try (Fixture f = new Fixture(call -> call == 0 ? replace("bad") : replace("recovered"))) {
            f.action.result = step -> label(step).equals("bad") ? FAILED : SUCCEEDED;
            f.agent.start("处理失败");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.calls.get() == 2 && f.action.executed.equals(List.of("bad", "recovered")), "Failure triggers one new plan, not repeated execution");
            check(f.history.snapshot().recentResults().get(0).status() == FAILED, "Replan retains failed-step feedback");
        }
        try (Fixture f = new Fixture(call -> call == 0 ? replace("bad") : decision("KEEP"))) {
            f.action.result = step -> FAILED;
            f.agent.start("失败后不能原样重试");
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.calls.get() == 2 && f.action.executed.size() == 1, "Do not loop on KEEP for a failed step");
        }
    }

    private static void staleProgress() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(call -> {
            if (call == 0) return replace("first", "remaining");
            if (call == 1) { entered.countDown(); await(release); return replace("stale-repeated-trade"); }
            return decision("KEEP");
        })) {
            f.agent.start("执行期间重新规划");
            until(f, () -> f.agent.view().plan() != null);
            f.agent.advance(15, false);
            await(entered);
            f.action.result = step -> label(step).equals("first") ? SUCCEEDED : RUNNING;
            f.agent.advance(0, false);
            check(f.agent.view().currentStep() == 1, "Execution can finish a step while planner is waiting");
            release.countDown();
            until(f, () -> f.calls.get() == 3 && !f.agent.view().planning());
            check(!f.action.executed.contains("stale-repeated-trade") && f.agent.view().currentStep() == 1,
                    "Discard plans generated before execution progress changed");
        } finally { release.countDown(); }
    }

    private static void newTaskCancelsOldReply() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(call -> {
            if (call == 0) {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { continue; }
                    check(done, "Late reply test timed out");
                }
                return replace("old-task");
            }
            return replace("new-task");
        })) {
            f.action.result = step -> SUCCEEDED;
            f.agent.start("旧任务");
            await(entered);
            String oldId = f.agent.view().taskId();
            f.agent.start("新任务");
            release.countDown();
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(!f.agent.view().taskId().equals(oldId) && f.action.executed.equals(List.of("new-task")), "Old replies cannot replace the new goal");
            f.agent.start("再次开始");
            check(f.history.snapshot().recentResults().isEmpty(), "New task clears the old history");
            f.agent.cancel();
            f.agent.advance(100, false);
            check(f.agent.view().status() == Agent.Status.CANCELLED, "Cancelled tasks stay stopped");
        } finally { release.countDown(); }
    }

    private static void planningFailureAndBlocked() throws Exception {
        try (Fixture f = new Fixture(call -> { if (call == 0) return replace("ongoing"); throw new IllegalStateException("offline error"); })) {
            f.agent.start("保留可用计划");
            until(f, () -> f.agent.view().plan() != null);
            f.agent.advance(15, false);
            until(f, () -> !f.agent.view().planning());
            check(f.agent.view().status() == Agent.Status.EXECUTING, "Periodic model failure does not stop a usable plan");
            int calls = f.calls.get();
            f.agent.advance(0, false);
            check(f.calls.get() == calls, "Do not retry failed model calls every frame");
        }
        for (String result : List.of("BLOCKED", "GOAL_REACHED")) {
            try (Fixture f = new Fixture(call -> decision(result))) {
                f.agent.start("直接规划结论");
                until(f, () -> !f.agent.view().planning());
                check(f.action.executed.isEmpty(), "No action needed for terminal planning decisions");
                check(f.agent.view().status() == (result.equals("BLOCKED") ? Agent.Status.BLOCKED : Agent.Status.COMPLETED), "Accept planner's terminal assessment");
            }
        }
        try (Fixture f = new Fixture(call -> replace("uncertain"))) {
            f.action.result = step -> { throw new UncertainActionException("asset uncertainty", null); };
            f.agent.start("处理已有阻塞");
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.calls.get() == 1, "Executor asset block is not bypassed by automatic replanning");
        }
    }

    private static void persistedProgressAndBlocked() throws Exception {
        Agent.State saved;
        try (Fixture original = new Fixture(call -> replace("first", "remaining"))) {
            original.action.result = step -> SUCCEEDED;
            original.agent.start("保存步骤进度");
            until(original, () -> original.agent.view().currentStep() == 1);
            saved = JSON.readValue(JSON.writeValueAsString(original.agent.snapshot()), Agent.State.class);
        }
        try (Fixture restored = new Fixture(call -> { throw new AssertionError("No new plan needed to resume"); })) {
            restored.agent.restore(saved);
            restored.action.result = step -> SUCCEEDED;
            restored.agent.advance(0, false);
            check(restored.action.executed.equals(List.of("remaining")), "Restored task skips the completed action");
            check(restored.agent.view().status() == Agent.Status.COMPLETED && restored.history.snapshot().completedStepIds().size() == 2,
                    "Restore retains historical completion identities");
        }
        try (Fixture original = new Fixture(call -> replace("uncertain"))) {
            original.action.result = step -> { throw new UncertainActionException("asset uncertainty", null); };
            original.agent.start("阻塞存档");
            until(original, () -> original.agent.view().status() == Agent.Status.BLOCKED);
            saved = JSON.readValue(JSON.writeValueAsString(original.agent.snapshot()), Agent.State.class);
        }
        try (Fixture restored = new Fixture(call -> replace("must-not-run"))) {
            restored.agent.restore(saved);
            restored.agent.advance(100, false);
            check(restored.calls.get() == 0 && restored.agent.view().status() == Agent.Status.BLOCKED, "Load preserves asset blocking");
            try { restored.agent.start("新任务"); throw new AssertionError("Must retain uncertainty block"); }
            catch (IllegalStateException expected) { }
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture original = new Fixture(call -> { entered.countDown(); await(release); return replace("old"); })) {
            original.agent.start("规划期间存档");
            await(entered);
            saved = JSON.readValue(JSON.writeValueAsString(original.agent.snapshot()), Agent.State.class);
        } finally { release.countDown(); }
        try (Fixture restored = new Fixture(call -> replace("fresh"))) {
            restored.agent.restore(saved);
            restored.action.result = step -> SUCCEEDED;
            until(restored, () -> restored.agent.view().status() == Agent.Status.COMPLETED);
            check(restored.calls.get() == 1 && restored.action.executed.equals(List.of("fresh")), "A pending request is recreated after load");
        }
    }

    private static String replace(String... labels) {
        try {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (String label : labels) steps.add(Map.of("reuseStepId", "", "action", "WORK",
                    "parametersJson", JSON.writeValueAsString(Map.of("label", label)), "description", label, "expectedOutcome", "完成 " + label));
            return JSON.writeValueAsString(Map.of("decision", "REPLACE", "reason", "执行目标", "steps", steps));
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static String decision(String decision) { return "{\"decision\":\"" + decision + "\",\"reason\":\"基于当前状态\",\"steps\":[]}"; }
    private static String label(Step step) { return (String) step.parameters().get("label"); }

    private static void until(Fixture f, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < deadline, "Agent condition timed out: " + f.agent.view());
            f.agent.advance(0, false);
            Thread.sleep(2);
        }
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class TestAction implements Action {
        Function<Step, ExecutionResult.Status> result = step -> RUNNING;
        final List<String> executed = new ArrayList<>();
        int stops;
        @Override public ActionSpec spec() {
            return new ActionSpec("WORK", "测试动作", List.of(new ActionSpec.Parameter("label", ActionSpec.Type.STRING, true, "动作标签")));
        }
        @Override public ExecutionResult execute(Step step, ActionContext context) {
            executed.add(label(step));
            return new ExecutionResult(step, result.apply(step), "实际执行 " + label(step));
        }
        @Override public void stop(ActionContext context) { stops++; }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final TestAction action = new TestAction();
        final ExecutionHistory history = new ExecutionHistory();
        final Agent agent;
        Fixture(IntFunction<String> output) {
            Thread owner = Thread.currentThread();
            LocationAPI location = proxy(LocationAPI.class, (m, a) -> null);
            CampaignFleetAPI player = proxy(CampaignFleetAPI.class, (m, a) -> null);
            CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (m, a) -> m.equals("getContainingLocation") ? location : null);
            SectorAPI sector = proxy(SectorAPI.class, (m, a) -> m.equals("getPlayerFleet") ? player : null);
            ActionContext context = new ActionContext(sector, fleet, proxy(SettingsAPI.class, (m, a) -> null), proxy(FactoryAPI.class, (m, a) -> null));
            Executor executor = new Executor(context, history, List.of(action));
            ChatModel model = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    check(Thread.currentThread() != owner, "Agent must not call the model on the game thread");
                    return ChatResponse.builder().aiMessage(AiMessage.from(output.apply(calls.getAndIncrement()))).build();
                }
            };
            Planner planner = new Planner(LlmClient.of(model, null, 30));
            agent = new Agent(planner, executor, () -> {
                check(Thread.currentThread() == owner, "Snapshot collection must stay on the game thread");
                return "当前世界状态";
            });
        }
        @Override public void close() { agent.close(); }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> instance == args[0]; case "hashCode" -> System.identityHashCode(instance); default -> type.getSimpleName();
            };
            Object result = handler.apply(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive() || method.getReturnType() == void.class) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == long.class) return 0L;
            return 0;
        }));
    }
}
