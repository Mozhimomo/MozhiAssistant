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
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.FleetResources;
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
        generatedPlanInsertion();
        generatedPlanGuards();
        decisionDefersReplanning();
        resupplyAndPeriodicReview();
        resourceChecksAfterExecution();
        impossibleCapacityAndInvalidRecovery();
        persistResupply();
        completionReviews();
        returnPermissions();
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
            check(f.agent.view().status() == Agent.Status.REVIEWING, "Successful steps await goal review");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
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
            Step inserted = work("calculated-trade");
            f.action.generated = step -> label(step).equals("first") ? Plan.create("计算结果", List.of(inserted)) : null;
            f.agent.advance(0, false);
            check(f.agent.view().currentStep() == 1, "Execution can finish a step while planner is waiting");
            release.countDown();
            until(f, () -> f.calls.get() == 3 && !f.agent.view().planning());
            check(!f.action.executed.contains("stale-repeated-trade") && f.agent.view().currentStep() == 1,
                    "Discard plans generated before execution progress changed");
            check(f.agent.view().plan().steps().size() == 3 && f.agent.view().plan().steps().get(1).equals(inserted),
                    "A stale model response cannot overwrite the inserted decision plan");
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
            until(restored, () -> restored.agent.view().status() == Agent.Status.COMPLETED);
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

    private static Step work(String label) { return Step.create("WORK", Map.of("label", label), label, "完成"); }

    private static void generatedPlanInsertion() throws Exception {
        Step calc = work("calculate"), tail = work("original-tail");
        Step buy = work("buy"), sell = work("sell");
        Plan child = Plan.create("路线", List.of(buy, sell));
        Agent.State saved;
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Direct plan needs no model"); })) {
            f.action.result = step -> SUCCEEDED;
            f.action.generated = step -> step.equals(calc) ? child : null;
            f.agent.start(Plan.create("跑商后回归", List.of(calc, tail)));
            f.agent.advance(0, false);
            check(f.agent.view().plan().steps().equals(List.of(calc, buy, sell, tail)), "Insert generated plan before original suffix");
            check(f.agent.view().currentStep() == 1 && f.action.executed.equals(List.of("calculate")), "Do not execute inserted action in the same frame");
            check(f.history.snapshot().recentResults().get(0).generatedPlan().equals(child), "Planner history includes decision plan");
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
            for (int i = 0; i < 3; i++) f.agent.advance(0, false);
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.action.executed.equals(List.of("calculate", "buy", "sell", "original-tail")), "Exact expansion order");
            check(f.agent.view().status() == Agent.Status.COMPLETED, "Expanded route and original suffix both complete");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Restore needs no model"); })) {
            f.action.result = step -> SUCCEEDED;
            f.agent.restore(saved);
            for (int i = 0; i < 3; i++) f.agent.advance(0, false);
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.action.executed.equals(List.of("buy", "sell", "original-tail")), "Load neither recalculates nor reinserts consumed decision");
            check(f.agent.view().plan().steps().size() == 4 && f.agent.view().status() == Agent.Status.COMPLETED, "Restored expansion finishes once");
        }
    }

    private static void generatedPlanGuards() {
        for (String invalid : List.of("collision", "unknown", "parameters", "return")) {
            try (Fixture f = new Fixture(call -> decision("KEEP"))) {
                Step calc = work("calculate"), tail = work("tail");
                Step bad = switch (invalid) {
                    case "collision" -> tail;
                    case "unknown" -> Step.create("UNKNOWN", Map.of(), "坏动作", "无");
                    case "parameters" -> Step.create("WORK", Map.of(), "缺参", "无");
                    default -> Step.create("RETURN", Map.of(), "过早回归", "无");
                };
                f.action.result = step -> SUCCEEDED;
                f.action.generated = step -> Plan.create("invalid", List.of(bad));
                f.agent.start(Plan.create("test", List.of(calc, tail)));
                f.agent.advance(0, false);
                check(f.agent.view().status() == Agent.Status.BLOCKED && f.action.executed.size() == 1, "Reject invalid child before executing it: " + invalid);
                check(f.agent.view().plan().steps().equals(List.of(calc, tail)), "Invalid expansion leaves original plan intact");
            }
        }
    }

    private static void decisionDefersReplanning() throws Exception {
        try (Fixture f = new Fixture(call -> decision("KEEP"))) {
            Step calc = Step.create("CALCULATE_TRADE_ROUTE", Map.of("label", "calculate"), "计算", "路线");
            Step child = resourceMove();
            f.action.generated = step -> Plan.create("route", List.of(child));
            f.agent.start(Plan.create("test", List.of(calc)));
            f.agent.advance(15, false);
            check(f.calls.get() == 0 && f.agent.view().currentStep() == 0, "Timer cannot replace a pending decision");
            f.action.result = step -> SUCCEEDED;
            f.agent.advance(0, false);
            check(f.agent.view().currentStep() == 1 && f.agent.view().plan().steps().size() == 2, "Append even when calculation was last step");
            until(f, () -> f.calls.get() == 1);
            check(f.history.snapshot().recentResults().get(0).generatedPlan() != null, "Deferred replan receives calculation result");
        }
    }

    private static Step resourceBuy(String id, int quantity) {
        return Step.create("BUY", Map.of("marketId", "market", "submarketId", "black_market", "itemType", "COMMODITY", "itemId", id, "quantity", quantity), "补购 " + id, "补足");
    }
    private static Step resourceMove() { return Step.create("MOVE_TO", Map.of("destinationId", "market"), "前往采购地", "抵达"); }
    private static String draft(Step... steps) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Step step : steps) rows.add(Map.of("reuseStepId", "", "action", step.action(), "parametersJson", JSON.writeValueAsString(step.parameters()),
                    "description", step.description(), "expectedOutcome", step.expectedOutcome()));
            return JSON.writeValueAsString(Map.of("decision", "REPLACE", "reason", "先补购后继续原目标", "steps", rows));
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void resupplyAndPeriodicReview() throws Exception {
        try (Fixture f = new Fixture(call -> call == 0 ? draft(resourceMove(), resourceBuy("fuel", 86), resourceBuy("supplies", 30), resourceBuy("crew", 5), work("original-task")) : decision("KEEP"))) {
            f.agent.start(Plan.create("原始目标", List.of(work("original-task"))));
            f.agent.advance(14, false);
            f.resources = new FleetResources(14, 100, 1, 29, 1, 4, 5);
            int workBefore = f.action.executed.size();
            f.agent.advance(1, false);
            until(f, () -> !f.resupplyExecuted.isEmpty());
            check(f.action.executed.size() == workBefore && f.agent.snapshot().replenishing(), "Periodic resource check stops business and starts resupply");
            check(f.lastRequest.contains("resources") && f.lastRequest.contains("minimumCrew") && f.lastRequest.contains("purchases"), "Planner receives structured resource snapshot and deficits");
            f.agent.advance(15, false);
            until(f, () -> f.calls.get() == 2 && !f.agent.view().planning());
            for (int i = 0; i < 30; i++) f.agent.advance(0, false);
            check(f.calls.get() == 2 && f.bought.isEmpty(), "Same low resources do not repeatedly replan or reset travel");
            f.moveStatus = SUCCEEDED;
            f.action.result = step -> SUCCEEDED;
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.bought.equals(List.of("fuel", "supplies", "crew")), "Buy each required resource once");
            check(f.agent.view().goal().equals("原始目标") && f.action.executed.get(f.action.executed.size() - 1).equals("original-task"), "Resume original task after resource recovery");
        }
    }
    private static void resourceChecksAfterExecution() throws Exception {
        try (Fixture f = new Fixture(call -> draft(resourceBuy("supplies", 30), work("remaining")))) {
            f.action.result = step -> { if (label(step).equals("consume")) f.resources = new FleetResources(100, 100, 1, 29, 1, 10, 5); return SUCCEEDED; };
            f.agent.start(Plan.create("执行后检查", List.of(work("consume"), work("remaining"))));
            f.agent.advance(0, false);
            check(f.agent.view().status() == Agent.Status.PLANNING && f.agent.view().currentStep() == 1, "Successful step triggers resupply before remaining work");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.action.executed.equals(List.of("consume", "remaining")) && f.bought.equals(List.of("supplies")), "Record success then replenish without replaying work");
        }
        try (Fixture f = new Fixture(call -> draft(resourceMove(), resourceBuy("fuel", 86), work("resume")))) {
            Step calc = Step.create("CALCULATE_TRADE_ROUTE", Map.of("label", "calculate"), "计算", "路线");
            f.agent.start(Plan.create("计算中资源变少", List.of(calc)));
            f.agent.advance(0, false);
            f.resources = new FleetResources(14, 100, 1, 100, 1, 10, 5);
            f.agent.advance(0, false);
            until(f, () -> !f.resupplyExecuted.isEmpty());
            check(f.calls.get() == 1 && f.action.executed.equals(List.of("calculate")), "Resource replan interrupts calculator deferral");
        }
    }
    private static void impossibleCapacityAndInvalidRecovery() throws Exception {
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Tank capacity requires player intervention, no fleet model call"); })) {
            f.resources = new FleetResources(14, 14, 1, 100, 1, 10, 5);
            f.agent.start(Plan.create("出发", List.of(work("must-not-run"))));
            f.agent.advance(15, true);
            check(f.agent.view().status() == Agent.Status.EXECUTING, "Pause does not run resource checks");
            f.agent.advance(15, false);
            check(f.agent.view().status() == Agent.Status.BLOCKED && f.agent.view().reason().contains("油船") && f.action.executed.isEmpty(), "Insufficient full-tank range blocks before executing");
        }
        for (String output : List.of(decision("KEEP"), decision("GOAL_REACHED"), draft(work("skip")), draft(resourceBuy("fuel", 1)), draft(resourceBuy("fuel", 10)))) {
            try (Fixture f = new Fixture(call -> output)) {
                f.resources = new FleetResources(5, 100, 1, 100, 1, 10, 5);
                f.agent.start("原始任务");
                until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
                check(f.calls.get() == 1 && f.bought.isEmpty() && f.action.executed.isEmpty(), "Reject recovery without enough resource purchases");
            }
        }
    }
    private static void persistResupply() throws Exception {
        Agent.State saved;
        FleetResources low = new FleetResources(14, 100, 1, 100, 1, 10, 5);
        try (Fixture f = new Fixture(call -> draft(resourceMove(), resourceBuy("fuel", 86), work("finish")))) {
            f.resources = low; f.agent.start("保存补购进度");
            until(f, () -> !f.resupplyExecuted.isEmpty());
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
            check(saved.replenishing() && saved.recoveryTargets().equals(Map.of("fuel", 100d)), "Persist recovery phase and its target");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Do not restart an already accepted recovery route"); })) {
            f.resources = low; f.agent.restore(saved); f.agent.advance(0, false);
            check(f.calls.get() == 0 && f.resupplyExecuted.equals(List.of("MOVE_TO")), "Load continues resupply while still below threshold");
            f.moveStatus = SUCCEEDED; f.action.result = step -> SUCCEEDED;
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.bought.equals(List.of("fuel")), "Restored purchase executes exactly once");
        }
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(saved);
        legacy.remove("replenishing");
        legacy.remove("recoveryTargets");
        check(!JSON.treeToValue(legacy, Agent.State.class).replenishing(), "Pre-resource-check saves remain readable");
    }

    private static void completionReviews() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Agent.State saved;
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Direct task needs only final review"); })) {
            f.reviewOutput = call -> { entered.countDown(); await(release); return decision("GOAL_REACHED"); };
            f.action.result = step -> SUCCEEDED;
            f.agent.start(Plan.create("确认真正完成", List.of(work("first"))));
            f.agent.advance(0, false); await(entered);
            check(f.agent.view().status() == Agent.Status.REVIEWING && f.agent.view().planning(), "Explicit asynchronous review phase");
            for (int i = 0; i < 10; i++) f.agent.advance(30, false);
            check(f.reviews.get() == 1 && f.action.executed.equals(List.of("first")), "One review, no periodic duplicate and no repeated action");
            check(f.lastReviewRequest.contains("确认真正完成") && f.lastReviewRequest.contains("SUCCEEDED"), "Review includes original goal and actual execution history");
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
            release.countDown();
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
        } finally { release.countDown(); }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Restore review must not recreate actions"); })) {
            f.agent.restore(saved);
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.reviews.get() == 1 && f.action.executed.isEmpty(), "Load recreates only pending review");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Review returns remaining plan directly"); })) {
            f.reviewOutput = call -> call == 0 ? replace("missing-work") : decision("GOAL_REACHED");
            f.action.result = step -> SUCCEEDED;
            f.agent.start(Plan.create("还有遗漏", List.of(work("first"))));
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.reviews.get() == 2 && f.action.executed.equals(List.of("first", "missing-work")), "Unmet goal replans remaining work, then reviews again");
        }
        for (String verdict : List.of("BLOCKED", "KEEP", "ERROR")) {
            try (Fixture f = new Fixture(call -> replace("must-not-run"))) {
                f.reviewOutput = call -> { if (verdict.equals("ERROR")) throw new IllegalStateException("review offline"); return decision(verdict); };
                f.action.result = step -> SUCCEEDED;
                f.agent.start(Plan.create("需要验收", List.of(work("done"))));
                until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
                check(f.agent.view().reason().contains("玩家检查") && f.reviews.get() == 1, "Uncertain/invalid/unavailable review asks player to check: " + verdict);
                f.agent.advance(100, false);
                check(f.calls.get() == 0 && f.action.executed.equals(List.of("done")), "No automatic loop after uncertain review");
            }
        }
    }

    private static void returnPermissions() throws Exception {
        Step home = Step.create("RETURN", Map.of(), "擅自返航", "合并");
        try (Fixture f = new Fixture(call -> draft(home))) {
            f.agent.start("跑商到舰队拥有100万再回来", true);
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.returns == 0, "Even explicit conditional authorization never exposes RETURN to Planner");
        }
        try (Fixture f = new Fixture(call -> replace("trade"))) {
            f.action.result = step -> SUCCEEDED;
            f.agent.start("完成跑商任务");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            f.agent.advance(100, false);
            check(f.returns == 0 && !f.agent.returnAfterCompletion(), "Default permission finishes in place, never returns");
        }
        Agent.State saved;
        try (Fixture f = new Fixture(call -> replace("first-route"))) {
            f.action.result = step -> SUCCEEDED;
            f.reviewOutput = call -> call == 0 ? replace("second-route") : decision("GOAL_REACHED");
            f.agent.start("跑商到舰队拥有100万再回来", true);
            until(f, () -> f.reviews.get() == 2 && f.agent.returning());
            check(f.returns == 0 && f.action.executed.equals(List.of("first-route", "second-route")),
                    "Unmet goal runs another route; authorized return is installed only after successful review");
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("No planning during authorized return"); })) {
            f.returnStatus = SUCCEEDED; f.agent.restore(saved); f.agent.advance(30, false);
            check(f.returns == 1 && f.agent.view().status() == Agent.Status.COMPLETED && f.reviews.get() == 0,
                    "Saved authorized return merges once without reviewing removed fleet");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("Explicit recall needs no planner"); })) {
            f.returnStatus = SUCCEEDED; f.agent.recall(); f.agent.advance(30, false);
            check(f.returns == 1 && f.agent.view().status() == Agent.Status.COMPLETED && f.reviews.get() == 0,
                    "Explicit recall executes directly and completes on merge");
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
        Function<Step, Plan> generated = step -> null;
        final List<String> executed = new ArrayList<>();
        int stops;
        @Override public ActionSpec spec() {
            return new ActionSpec("WORK", "测试动作", List.of(new ActionSpec.Parameter("label", ActionSpec.Type.STRING, true, "动作标签")));
        }
        @Override public ExecutionResult execute(Step step, ActionContext context) {
            executed.add(label(step));
            var status = result.apply(step);
            return new ExecutionResult(step, status, "实际执行 " + label(step), status == SUCCEEDED ? generated.apply(step) : null);
        }
        @Override public void stop(ActionContext context) { stops++; }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger reviews = new AtomicInteger();
        IntFunction<String> reviewOutput = call -> decision("GOAL_REACHED");
        volatile String lastReviewRequest = "";
        final TestAction action = new TestAction();
        final ExecutionHistory history = new ExecutionHistory();
        final Agent agent;
        FleetResources resources = new FleetResources(100, 100, 1, 100, 1, 10, 5);
        final List<String> resupplyExecuted = new ArrayList<>(), bought = new ArrayList<>();
        ExecutionResult.Status moveStatus = RUNNING;
        ExecutionResult.Status returnStatus = RUNNING;
        int returns;
        volatile String lastRequest = "";
        Fixture(IntFunction<String> output) {
            Thread owner = Thread.currentThread();
            LocationAPI location = proxy(LocationAPI.class, (m, a) -> null);
            CampaignFleetAPI player = proxy(CampaignFleetAPI.class, (m, a) -> null);
            CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (m, a) -> m.equals("getContainingLocation") ? location : null);
            SectorAPI sector = proxy(SectorAPI.class, (m, a) -> m.equals("getPlayerFleet") ? player : null);
            ActionContext context = new ActionContext(sector, fleet, proxy(SettingsAPI.class, (m, a) -> null), proxy(FactoryAPI.class, (m, a) -> null));
            Action calc = new Action() {
                public ActionSpec spec() { return new ActionSpec("CALCULATE_TRADE_ROUTE", "计算", action.spec().parameters()); }
                public ExecutionResult execute(Step step, ActionContext c) { return action.execute(step, c); }
            };
            Action returning = new Action() {
                public ActionSpec spec() { return new ActionSpec("RETURN", "回归", List.of()); }
                public ExecutionResult execute(Step step, ActionContext c) { returns++; return new ExecutionResult(step, returnStatus, "返回结果"); }
            };
            Action resourceMove = new Action() {
                public ActionSpec spec() { return new com.mozhi.fleet.actions.MoveToAction().spec(); }
                public ExecutionResult execute(Step step, ActionContext c) {
                    resupplyExecuted.add("MOVE_TO"); return new ExecutionResult(step, moveStatus, "补购航行");
                }
            };
            Action resourceBuy = new Action() {
                public ActionSpec spec() { return new com.mozhi.fleet.actions.BuyAction().spec(); }
                public ExecutionResult execute(Step step, ActionContext c) {
                    String id = (String) step.parameters().get("itemId");
                    double quantity = ((Number) step.parameters().get("quantity")).doubleValue();
                    bought.add(id); resupplyExecuted.add("BUY");
                    resources = new FleetResources(resources.fuel() + (id.equals("fuel") ? quantity : 0), resources.fuelCapacity(), resources.fuelPerLightYear(),
                            resources.supplies() + (id.equals("supplies") ? quantity : 0), resources.suppliesPerDay(),
                            resources.crew() + (id.equals("crew") ? quantity : 0), resources.minimumCrew());
                    return new ExecutionResult(step, SUCCEEDED, "资源已购入");
                }
            };
            Executor executor = new Executor(context, history, List.of(action, calc, returning, resourceMove, resourceBuy));
            ChatModel model = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    check(Thread.currentThread() != owner, "Agent must not call the model on the game thread");
                    lastRequest = request.messages().toString();
                    boolean review = lastRequest.contains("\"completionReview\":true");
                    if (review) lastReviewRequest = lastRequest;
                    return ChatResponse.builder().aiMessage(AiMessage.from(review ? reviewOutput.apply(reviews.getAndIncrement()) : output.apply(calls.getAndIncrement()))).build();
                }
            };
            Planner planner = new Planner(LlmClient.of(model, null, 30));
            agent = new Agent(planner, executor, () -> {
                check(Thread.currentThread() == owner, "Snapshot collection must stay on the game thread");
                return "当前世界状态";
            }, () -> resources, 15);
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
