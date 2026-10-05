package com.mozhi.fleet;

import com.mozhi.fleet.execution.Executor;
import com.mozhi.fleet.execution.Monitor;

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
        failureInterruptsPeriodicCheck();
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
        continuousFollow();
        System.out.println("智能体检查通过");
    }

    private static void sequentialCompletion() throws Exception {
        try (Fixture f = new Fixture(call -> replace("first", "second"))) {
            f.action.result = step -> SUCCEEDED;
            f.agent.start("完成两步");
            until(f, () -> f.action.executed.size() == 1);
            check(f.agent.view().currentStep() == 1 && f.agent.view().status() == Agent.Status.EXECUTING,
                    "推进一步时不增加额外验收请求");
            f.agent.advance(0, false);
            check(f.agent.view().status() == Agent.Status.REVIEWING, "全部步骤成功后等待目标验收");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.agent.view().status() == Agent.Status.COMPLETED && f.action.executed.size() == 2, "所有步骤成功后结束");
            f.agent.advance(100, false);
            check(f.calls.get() == 1 && f.action.executed.size() == 2, "已完成任务不再规划或执行");
            check(f.history.snapshot().completedStepIds().size() == 2, "实际结果写入共享历史");
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
            check(f.calls.get() == 1, "间隔未到时不重规划");
            int beforePause = f.action.executed.size();
            f.agent.advance(100, true);
            check(f.action.executed.size() == beforePause && f.calls.get() == 1, "暂停期间不计时也不执行");
            f.agent.advance(1, false);
            await(entered);
            int beforePending = f.action.executed.size();
            f.agent.advance(15, false);
            f.agent.advance(15, false);
            check(f.calls.get() == 2 && f.action.executed.size() == beforePending + 2, "有一个待处理重规划时继续执行");
            release.countDown();
            until(f, () -> f.calls.get() == 3 && !f.agent.view().planning());
            check(f.agent.view().plan().id().equals(planId) && f.action.stops == 0,
                    "KEEP 保留当前动作；逾期定时请求合并为一次后续检查");
        } finally { release.countDown(); }
    }

    private static void failureReplans() throws Exception {
        try (Fixture f = new Fixture(call -> call == 0 ? replace("bad") : replace("recovered"))) {
            f.action.result = step -> label(step).equals("bad") ? FAILED : SUCCEEDED;
            f.agent.start("处理失败");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.calls.get() == 2 && f.action.executed.equals(List.of("bad", "recovered")), "失败触发一个新计划，不重复执行");
            check(f.history.snapshot().recentResults().get(0).status() == FAILED, "重规划保留失败步骤反馈");
        }
        try (Fixture f = new Fixture(call -> call == 0 ? replace("bad") : decision("KEEP"))) {
            f.action.result = step -> FAILED;
            f.agent.start("失败后不能原样重试");
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.calls.get() == 2 && f.action.executed.size() == 1, "失败步骤不在 KEEP 上循环");
        }
    }

    private static void failureInterruptsPeriodicCheck() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        try (Fixture f = new Fixture(call -> call == 0 ? replace("purchase") : replace("recovered"))) {
            f.checkOutput = call -> {
                entered.countDown();
                try { await(release); }
                catch (CancellationException expected) { interrupted.countDown(); throw expected; }
                return "{\"decision\":\"KEEP\",\"reason\":\"旧快照可继续\"}";
            };
            f.agent.start("缺货后直接重规划");
            until(f, () -> f.agent.view().plan() != null);
            f.agent.advance(15, false); await(entered);
            f.action.result = step -> label(step).equals("purchase") ? FAILED : RUNNING;
            f.agent.advance(0, false);
            await(interrupted);
            until(f, () -> f.action.executed.contains("recovered"));
            check(f.calls.get() == 2 && f.lightCalls.get() == 1, "失败取消旧周期检查并直接交主规划器，不能再次走 KEEP");
            check(f.lastRequest.contains("步骤失败") && f.lastRequest.contains("FAILED"), "新请求保留失败原因及历史");
            f.action.result = step -> SUCCEEDED;
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
        } finally { release.countDown(); }
    }

    private static void continuousFollow() throws Exception {
        try (Fixture f = new Fixture(call -> decision("GOAL_REACHED"))) {
            Step follow = Step.create("FOLLOW_FLEET", Map.of("targetFleetId", "player"), "持续跟随玩家", "持续保持距离");
            f.agent.start(Plan.create("持续跟随直到玩家停止", List.of(follow)));
            f.agent.advance(15, false);
            until(f, () -> f.calls.get() == 1 && !f.agent.view().planning());
            check(f.agent.view().status() == Agent.Status.EXECUTING && f.reviews.get() == 0
                    && !f.history.snapshot().completedStepIds().contains(follow.id()), "不能把持续跟随误判为完成并触发验收");
            f.agent.cancel();
            check(f.agent.view().status() == Agent.Status.CANCELLED, "玩家可明确停止跟随");
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
            check(f.agent.view().currentStep() == 1, "规划器等待时，执行器仍可完成步骤");
            release.countDown();
            until(f, () -> f.calls.get() == 3 && !f.agent.view().planning());
            check(!f.action.executed.contains("stale-repeated-trade") && f.agent.view().currentStep() == 1,
                    "丢弃执行进度变化前生成的计划");
            check(f.agent.view().plan().steps().size() == 3 && f.agent.view().plan().steps().get(1).equals(inserted),
                    "过期模型响应不能覆盖已插入的决策计划");
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
                    check(done, "迟到回复测试超时");
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
            check(!f.agent.view().taskId().equals(oldId) && f.action.executed.equals(List.of("new-task")), "旧回复不能替换新目标");
            f.agent.start("再次开始");
            check(f.history.snapshot().recentResults().isEmpty(), "新任务清除旧历史");
            f.agent.cancel();
            f.agent.advance(100, false);
            check(f.agent.view().status() == Agent.Status.CANCELLED, "已取消任务保持停止");
        } finally { release.countDown(); }
    }

    private static void planningFailureAndBlocked() throws Exception {
        try (Fixture f = new Fixture(call -> { if (call == 0) return replace("ongoing"); throw new IllegalStateException("离线模拟错误"); })) {
            f.agent.start("保留可用计划");
            until(f, () -> f.agent.view().plan() != null);
            f.agent.advance(15, false);
            until(f, () -> !f.agent.view().planning());
            check(f.agent.view().status() == Agent.Status.EXECUTING, "周期模型调用失败不停止可用计划");
            int calls = f.calls.get();
            f.agent.advance(0, false);
            check(f.calls.get() == calls, "模型调用失败后不逐帧重试");
        }
        for (String result : List.of("BLOCKED", "GOAL_REACHED")) {
            try (Fixture f = new Fixture(call -> decision(result))) {
                f.agent.start("直接规划结论");
                until(f, () -> !f.agent.view().planning());
                check(f.action.executed.isEmpty(), "终止类规划决策不需要执行动作");
                check(f.agent.view().status() == (result.equals("BLOCKED") ? Agent.Status.BLOCKED : Agent.Status.COMPLETED), "接受规划器的终态评估");
            }
        }
        try (Fixture f = new Fixture(call -> replace("uncertain"))) {
            f.action.result = step -> { throw new UncertainActionException("资产状态无法确认", null); };
            f.agent.start("处理已有阻塞");
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.calls.get() == 1, "自动重规划不能绕过执行器的资产阻塞");
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
        try (Fixture restored = new Fixture(call -> { throw new AssertionError("继续执行不需要新计划"); })) {
            restored.agent.restore(saved);
            restored.action.result = step -> SUCCEEDED;
            restored.agent.advance(0, false);
            until(restored, () -> restored.agent.view().status() == Agent.Status.COMPLETED);
            check(restored.action.executed.equals(List.of("remaining")), "恢复任务时跳过已完成动作");
            check(restored.agent.view().status() == Agent.Status.COMPLETED && restored.history.snapshot().completedStepIds().size() == 2,
                    "恢复时保留历史完成步骤身份");
        }
        try (Fixture original = new Fixture(call -> replace("uncertain"))) {
            original.action.result = step -> { throw new UncertainActionException("资产状态无法确认", null); };
            original.agent.start("阻塞存档");
            until(original, () -> original.agent.view().status() == Agent.Status.BLOCKED);
            saved = JSON.readValue(JSON.writeValueAsString(original.agent.snapshot()), Agent.State.class);
        }
        try (Fixture restored = new Fixture(call -> replace("must-not-run"))) {
            restored.agent.restore(saved);
            restored.agent.advance(100, false);
            check(restored.calls.get() == 0 && restored.agent.view().status() == Agent.Status.BLOCKED, "读档保留资产阻塞");
            try { restored.agent.start("新任务"); throw new AssertionError("必须保留资产无法确认的阻塞"); }
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
            check(restored.calls.get() == 1 && restored.action.executed.equals(List.of("fresh")), "读档后重新创建待处理请求");
        }
    }

    private static Step work(String label) { return Step.create("WORK", Map.of("label", label), label, "完成"); }

    private static void generatedPlanInsertion() throws Exception {
        Step calc = work("calculate"), tail = work("original-tail");
        Step buy = work("buy"), sell = work("sell");
        Plan child = Plan.create("路线", List.of(buy, sell));
        Agent.State saved;
        try (Fixture f = new Fixture(call -> { throw new AssertionError("直接计划不需要模型"); })) {
            f.action.result = step -> SUCCEEDED;
            f.action.generated = step -> step.equals(calc) ? child : null;
            f.agent.start(Plan.create("跑商后回归", List.of(calc, tail)));
            f.agent.advance(0, false);
            check(f.agent.view().plan().steps().equals(List.of(calc, buy, sell, tail)), "将生成计划插入原后续步骤之前");
            check(f.agent.view().currentStep() == 1 && f.action.executed.equals(List.of("calculate")), "同一帧不执行刚插入的动作");
            check(f.history.snapshot().recentResults().get(0).generatedPlan().equals(child), "规划器历史包含决策生成的计划");
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
            for (int i = 0; i < 3; i++) f.agent.advance(0, false);
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.action.executed.equals(List.of("calculate", "buy", "sell", "original-tail")), "展开顺序准确");
            check(f.agent.view().status() == Agent.Status.COMPLETED, "展开的路线与原后续步骤均完成");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("恢复不需要模型"); })) {
            f.action.result = step -> SUCCEEDED;
            f.agent.restore(saved);
            for (int i = 0; i < 3; i++) f.agent.advance(0, false);
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.action.executed.equals(List.of("buy", "sell", "original-tail")), "读档不重新计算或重复插入已消费的决策结果");
            check(f.agent.view().plan().steps().size() == 4 && f.agent.view().status() == Agent.Status.COMPLETED, "恢复后的展开计划只完成一次");
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
                check(f.agent.view().status() == Agent.Status.BLOCKED && f.action.executed.size() == 1, "在执行前拒绝无效子计划：" + invalid);
                check(f.agent.view().plan().steps().equals(List.of(calc, tail)), "无效展开不改变原始计划");
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
            check(f.calls.get() == 0 && f.agent.view().currentStep() == 0, "定时器不能替换待完成的决策动作");
            f.action.result = step -> SUCCEEDED;
            f.agent.advance(0, false);
            check(f.agent.view().currentStep() == 1 && f.agent.view().plan().steps().size() == 2, "计算位于最后一步时也能追加计划");
            until(f, () -> f.calls.get() == 1);
            check(f.history.snapshot().recentResults().get(0).generatedPlan() != null, "延后的重规划接收计算结果");
        }
    }

    private static Step resourceBuy(String id, int quantity) {
        return Step.create("BUY", Map.of("marketId", "market", "submarketId", "black_market", "itemType", "COMMODITY", "itemId", id, "quantity", quantity), "补购 " + id, "补足");
    }
    private static Step resourceMove() { return Step.create("MOVE_TO", Map.of("destinationId", "market"), "前往采购地", "抵达"); }
    private static String draft(Step... steps) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Step step : steps) rows.add(Map.of("reuseStepId", "", "tool", step.action(), "argumentsJson", JSON.writeValueAsString(step.parameters()),
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
            check(f.action.executed.size() > workBefore && f.lightCalls.get() >= 1, "资源建议期间继续业务，直到轻量模型要求升级且新计划准备完成");
            check(f.lastRequest.contains("resources") && f.lastRequest.contains("minimumCrew") && f.lastRequest.contains("issues") && !f.lastRequest.contains("\"purchases\""), "规划器接收资源建议快照，不附加固定数量");
            f.agent.advance(15, false);
            until(f, () -> f.calls.get() == 2 && !f.agent.view().planning());
            for (int i = 0; i < 30; i++) f.agent.advance(0, false);
            check(f.calls.get() == 2 && f.bought.isEmpty(), "持续资源短缺不反复重规划或重置航行");
            f.moveStatus = SUCCEEDED;
            f.action.result = step -> SUCCEEDED;
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.bought.equals(List.of("fuel", "supplies", "crew")), "每项需要的资源只购买一次");
            check(f.agent.view().goal().equals("原始目标") && f.action.executed.get(f.action.executed.size() - 1).equals("original-task"), "资源补充后继续原始任务");
        }
    }
    private static void resourceChecksAfterExecution() throws Exception {
        Step resumedTrade = Step.create("CALCULATE_TRADE_ROUTE", Map.of("label", "resume-trade"), "补员后重新计算跑商", "路线");
        try (Fixture f = new Fixture(call -> draft(resourceMove(), resourceBuy("crew", 631), resumedTrade))) {
            f.resources = new FleetResources(100, 100, 1, 100, 1, 179, 800);
            f.checkOutput = call -> call == 0
                    ? "{\"decision\":\"KEEP\",\"reason\":\"暂时无可达采购渠道，下一周期复查\"}"
                    : "{\"decision\":\"REPLAN\",\"reason\":\"补员渠道已恢复，先补员再跑商\"}";
            f.agent.start(Plan.create("跑商到目标资金", List.of(Step.create("CALCULATE_TRADE_ROUTE", Map.of("label", "old-trade"), "旧计算", "路线"))));
            until(f, () -> f.lightCalls.get() == 1 && !f.agent.view().planning());
            check(f.calls.get() == 0, "极端情况下允许轻量模型暂缓补充");
            f.moveStatus = SUCCEEDED;
            f.action.result = step -> {
                if (label(step).equals("resume-trade"))
                    check(f.resources.crew() == 810 && f.bought.equals(List.of("crew")), "补员成功后才执行新的跑商计算");
                return RUNNING;
            };
            f.agent.advance(15, false);
            until(f, () -> f.action.executed.contains("resume-trade"));
            check(f.lightCalls.get() == 2 && f.calls.get() == 1, "持续缺员的周期复查不被旧跑商计算推迟");
        }
        try (Fixture f = new Fixture(call -> draft(resourceBuy("supplies", 30), work("remaining")))) {
            f.action.result = step -> { if (label(step).equals("consume")) { f.resources = new FleetResources(100, 100, 1, 29, 1, 10, 5); return SUCCEEDED; } return RUNNING; };
            f.agent.start(Plan.create("执行后检查", List.of(work("consume"), work("remaining"))));
            f.agent.advance(0, false);
            check(f.agent.view().status() == Agent.Status.EXECUTING && f.agent.view().currentStep() == 1, "成功步骤将建议排队，不暂停剩余工作");
            until(f, () -> !f.bought.isEmpty());
            f.action.result = step -> SUCCEEDED;
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(java.util.Collections.frequency(f.action.executed, "consume") == 1 && f.bought.equals(List.of("supplies")), "先记录成功，再按需补充资源，不重放已完成工作");
        }
        try (Fixture f = new Fixture(call -> draft(resourceMove(), resourceBuy("fuel", 86), work("resume")))) {
            Step calc = Step.create("CALCULATE_TRADE_ROUTE", Map.of("label", "calculate"), "计算", "路线");
            f.agent.start(Plan.create("计算中资源变少", List.of(calc)));
            f.agent.advance(0, false);
            f.resources = new FleetResources(14, 100, 1, 100, 1, 10, 5);
            f.agent.advance(0, false);
            until(f, () -> !f.resupplyExecuted.isEmpty());
            check(f.calls.get() == 1 && f.lightCalls.get() == 1 && !f.action.executed.isEmpty(), "计算期间资源建议交给轻量模型，只有升级判断才替换计划");
        }
    }
    private static void impossibleCapacityAndInvalidRecovery() throws Exception {
        try (Fixture f = new Fixture(call -> draft(resourceBuy("crew", 20)))) {
            f.checkOutput = call -> "{\"decision\":\"KEEP\",\"reason\":\"新任务按玩家数量即可\"}";
            f.resources = new FleetResources(100, 100, 1, 100, 1, 50, 100);
            f.agent.start("只买 20 人");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.lightCalls.get() == 1 && f.calls.get() == 1 && f.resources.crew() == 70,
                    "初始建议交给轻量模型；KEEP 后仍允许按指定购买量规划初始目标");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("保留已授权返航，不重规划业务"); })) {
            f.checkOutput = call -> "{\"decision\":\"KEEP\",\"reason\":\"短途返航可以继续\"}";
            f.resources = new FleetResources(10, 12, 1, 20, 1, 4, 5);
            f.agent.recall();
            until(f, () -> f.lightCalls.get() == 1 && !f.agent.view().planning());
            check(f.agent.returning() && f.agent.view().status() == Agent.Status.EXECUTING && f.calls.get() == 0
                    && !f.agent.view().reason().contains("规划失败"), "建议判断为 KEEP 时可保留已授权的 RETURN");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("建议判断为 KEEP 时不得调用主规划器"); })) {
            f.checkOutput = call -> "{\"decision\":\"KEEP\",\"reason\":\"这是建议，当前短途任务可继续\"}";
            f.resources = new FleetResources(14, 14, 1, 20, 1, 4, 5);
            f.agent.start(Plan.create("短途任务", List.of(work("continue"))));
            f.agent.advance(15, true);
            check(f.lightCalls.get() == 0 && f.action.executed.isEmpty(), "暂停时不提交建议检查");
            f.agent.advance(0, false);
            until(f, () -> f.lightCalls.get() == 1 && !f.agent.view().planning());
            for (int i = 0; i < 30; i++) f.agent.advance(0, false);
            check(f.calls.get() == 0 && f.lightCalls.get() == 1 && f.agent.view().status() == Agent.Status.EXECUTING && f.action.executed.size() > 1,
                    "轻量模型选择 KEEP 时，燃料、补给、船员和油箱容量不足都不强制停止或采购");
            check(f.lastCheckRequest.contains("ADVISORY") && f.lastCheckRequest.contains("fuelCapacity"), "轻量模型可见资源建议");
            f.agent.advance(15, false);
            until(f, () -> f.lightCalls.get() == 2 && !f.agent.view().planning());
            check(f.calls.get() == 0, "持续短缺的周期复查仍由轻量模型执行");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("建议不应升级"); })) {
            f.checkOutput = call -> "{\"decision\":\"KEEP\",\"reason\":\"无需调整\"}";
            f.resources = new FleetResources(10, 100, 1, 100, 1, 10, 5);
            f.agent.start(Plan.create("继续航行", List.of(work("continue"))));
            until(f, () -> f.lightCalls.get() == 1 && !f.agent.view().planning());
            f.resources = new FleetResources(10, 100, 1, 100, 1, 4, 5);
            until(f, () -> f.lightCalls.get() == 2 && !f.agent.view().planning());
            check(f.agent.view().status() == Agent.Status.EXECUTING, "新资源问题排队触发一次新的轻量检查，不停止当前动作");
        }
        try (Fixture f = new Fixture(call -> decision("KEEP"))) {
            f.resources = new FleetResources(5, 100, 1, 100, 1, 10, 5);
            f.agent.start("原始任务");
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.calls.get() == 1 && f.bought.isEmpty(), "KEEP 仍要求当前存在可执行计划");
        }
        // 采购数量与顺序由规划器决定，包括少量补充前的前置步骤。
        for (String id : List.of("crew", "fuel", "supplies")) {
            try (Fixture f = new Fixture(call -> draft(work("prerequisite"), resourceBuy(id, 20), work("suffix")))) {
                f.resources = switch (id) {
                    case "crew" -> new FleetResources(100, 100, 1, 100, 1, 80, 100);
                    case "fuel" -> new FleetResources(10, 100, 1, 100, 1, 10, 5);
                    default -> new FleetResources(100, 100, 1, 10, 1, 10, 5);
                };
                f.action.result = step -> SUCCEEDED;
                f.agent.start("只采购 20 " + id);
                until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
                check(f.bought.equals(List.of(id)) && Monitor.amount(f.resources, id) == (id.equals("crew") ? 100 : 30), "精确执行 20 的数量，不强制默认资源目标：" + id);
                check(f.calls.get() == 1 && f.reviews.get() == 1 && f.action.executed.equals(List.of("prerequisite", "suffix")), "接受规划器顺序并完成原始目标，不额外补充：" + id);
            }
        }
        try (Fixture f = new Fixture(call -> draft(resourceBuy("crew", 20), work("suffix")))) {
            f.resources = new FleetResources(100, 100, 1, 100, 1, 50, 100);
            f.action.result = step -> SUCCEEDED;
            f.agent.start("只买 20 人");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.resources.crew() == 70 && f.calls.get() == 1 && f.reviews.get() == 1, "已知剩余短缺不否决已接受计划或其目标验收");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("直接购买不需要在执行前重规划"); })) {
            f.resources = new FleetResources(100, 100, 1, 100, 1, 80, 100);
            f.agent.start(Plan.create("补至最低人数", List.of(resourceBuy("crew", 20))));
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.resources.crew() == 100 && f.bought.equals(List.of("crew")), "直接计划精确执行明确指定的购买数量");
        }
    }
    private static void persistResupply() throws Exception {
        Agent.State saved;
        FleetResources low = new FleetResources(14, 100, 1, 100, 1, 10, 5);
        try (Fixture f = new Fixture(call -> draft(resourceMove(), resourceBuy("fuel", 86), work("finish")))) {
            f.resources = low; f.agent.start("保存补购进度");
            until(f, () -> !f.resupplyExecuted.isEmpty());
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
            check(!saved.replenishing() && saved.recoveryTargets().isEmpty(), "存档不包含强制补充阶段或采购目标");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("不重新启动已接受的补充路线"); })) {
            f.checkOutput = call -> "{\"decision\":\"KEEP\",\"reason\":\"采购已在计划中\"}";
            f.resources = low; f.agent.restore(saved); f.agent.advance(0, false);
            check(f.calls.get() == 0 && f.resupplyExecuted.equals(List.of("MOVE_TO")), "读档后即使仍低于阈值，也继续补充资源");
            f.moveStatus = SUCCEEDED; f.action.result = step -> SUCCEEDED;
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.bought.equals(List.of("fuel")), "恢复后的购买只执行一次");
        }
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(saved);
        legacy.remove("replenishing");
        legacy.remove("recoveryTargets");
        check(!JSON.treeToValue(legacy, Agent.State.class).replenishing(), "引入资源检查前的存档仍可读取");
    }

    private static void completionReviews() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Agent.State saved;
        try (Fixture f = new Fixture(call -> { throw new AssertionError("直接任务只需最终验收"); })) {
            f.reviewOutput = call -> { entered.countDown(); await(release); return decision("GOAL_REACHED"); };
            f.action.result = step -> SUCCEEDED;
            f.agent.start(Plan.create("确认真正完成", List.of(work("first"))));
            f.agent.advance(0, false); await(entered);
            check(f.agent.view().status() == Agent.Status.REVIEWING && f.agent.view().planning(), "使用明确的异步验收阶段");
            for (int i = 0; i < 10; i++) f.agent.advance(30, false);
            check(f.reviews.get() == 1 && f.action.executed.equals(List.of("first")), "只验收一次，不重复周期请求或动作");
            check(f.lastReviewRequest.contains("确认真正完成") && f.lastReviewRequest.contains("SUCCEEDED"), "验收包含原始目标与真实执行历史");
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
            release.countDown();
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
        } finally { release.countDown(); }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("恢复验收时不得重建动作"); })) {
            f.agent.restore(saved);
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.reviews.get() == 1 && f.action.executed.isEmpty(), "读档只重建待处理验收");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("验收直接返回剩余计划"); })) {
            f.reviewOutput = call -> call == 0 ? replace("missing-work") : decision("GOAL_REACHED");
            f.action.result = step -> SUCCEEDED;
            f.agent.start(Plan.create("还有遗漏", List.of(work("first"))));
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            check(f.reviews.get() == 2 && f.action.executed.equals(List.of("first", "missing-work")), "目标未达成时规划剩余工作，随后再次验收");
        }
        for (String verdict : List.of("BLOCKED", "KEEP", "ERROR")) {
            try (Fixture f = new Fixture(call -> replace("must-not-run"))) {
                f.reviewOutput = call -> { if (verdict.equals("ERROR")) throw new IllegalStateException("离线验收错误"); return decision(verdict); };
                f.action.result = step -> SUCCEEDED;
                f.agent.start(Plan.create("需要验收", List.of(work("done"))));
                until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
                check(f.agent.view().reason().contains("玩家检查") && f.reviews.get() == 1, "验收不确定、无效或不可用时请玩家检查：" + verdict);
                f.agent.advance(100, false);
                check(f.calls.get() == 0 && f.action.executed.equals(List.of("done")), "验收无法确定后不自动循环");
            }
        }
    }

    private static void returnPermissions() throws Exception {
        Step home = Step.create("RETURN", Map.of(), "擅自返航", "合并");
        try (Fixture f = new Fixture(call -> draft(home))) {
            f.agent.start("跑商到舰队拥有100万再回来", true);
            until(f, () -> f.agent.view().status() == Agent.Status.BLOCKED);
            check(f.returns == 0, "即使有明确的条件返航授权，也不向规划器公开 RETURN");
        }
        try (Fixture f = new Fixture(call -> replace("trade"))) {
            f.action.result = step -> SUCCEEDED;
            f.agent.start("完成跑商任务");
            until(f, () -> f.agent.view().status() == Agent.Status.COMPLETED);
            f.agent.advance(100, false);
            check(f.returns == 0 && !f.agent.returnAfterCompletion(), "默认权限为原地结束，不返航");
        }
        Agent.State saved;
        try (Fixture f = new Fixture(call -> replace("first-route"))) {
            f.action.result = step -> SUCCEEDED;
            f.reviewOutput = call -> call == 0 ? replace("second-route") : decision("GOAL_REACHED");
            f.agent.start("跑商到舰队拥有100万再回来", true);
            until(f, () -> f.reviews.get() == 2 && f.agent.returning());
            check(f.returns == 0 && f.action.executed.equals(List.of("first-route", "second-route")),
                    "目标未达成则继续下一条路线；仅在验收成功后安排已授权返航");
            saved = JSON.readValue(JSON.writeValueAsString(f.agent.snapshot()), Agent.State.class);
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("授权返航期间不规划"); })) {
            f.returnStatus = SUCCEEDED; f.agent.restore(saved); f.agent.advance(30, false);
            check(f.returns == 1 && f.agent.view().status() == Agent.Status.COMPLETED && f.reviews.get() == 0,
                    "已保存的授权返航只合并一次，不验收已移除舰队");
        }
        try (Fixture f = new Fixture(call -> { throw new AssertionError("明确召回不需要规划器"); })) {
            f.returnStatus = SUCCEEDED; f.agent.recall(); f.agent.advance(30, false);
            check(f.returns == 1 && f.agent.view().status() == Agent.Status.COMPLETED && f.reviews.get() == 0,
                    "明确召回直接执行，合并后完成");
        }
    }

    private static String replace(String... labels) {
        try {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (String label : labels) steps.add(Map.of("reuseStepId", "", "tool", "WORK",
                    "argumentsJson", JSON.writeValueAsString(Map.of("label", label)), "description", label, "expectedOutcome", "完成 " + label));
            return JSON.writeValueAsString(Map.of("decision", "REPLACE", "reason", "执行目标", "steps", steps));
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static String decision(String decision) { return "{\"decision\":\"" + decision + "\",\"reason\":\"基于当前状态\",\"steps\":[]}"; }
    private static String label(Step step) { return (String) step.parameters().get("label"); }

    private static void until(Fixture f, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < deadline, "智能体条件等待超时：" + f.agent.view());
            f.agent.advance(0, false);
            Thread.sleep(2);
        }
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "等待同步信号超时"); }
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
        final AtomicInteger lightCalls = new AtomicInteger();
        volatile String lastCheckRequest = "";
        IntFunction<String> checkOutput = call -> "{\"decision\":\"REPLAN\",\"reason\":\"检查后交主规划器\"}";
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
            Action following = new Action() {
                public ActionSpec spec() { return new com.mozhi.fleet.actions.FollowFleetAction().spec(); }
                public ExecutionResult execute(Step step, ActionContext c) { return new ExecutionResult(step, RUNNING, "已靠近目标，持续跟随"); }
            };
            Executor executor = new Executor(context, history, List.of(action, calc, returning, resourceMove, resourceBuy, following));
            ChatModel model = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    check(Thread.currentThread() != owner, "智能体不得在游戏线程调用模型");
                    lastRequest = request.messages().toString();
                    boolean review = lastRequest.contains("\"completionReview\":true");
                    if (review) lastReviewRequest = lastRequest;
                    return ChatResponse.builder().aiMessage(AiMessage.from(review ? reviewOutput.apply(reviews.getAndIncrement()) : output.apply(calls.getAndIncrement()))).build();
                }
            };
            ChatModel checkModel = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    lastCheckRequest = request.messages().toString();
                    return ChatResponse.builder().aiMessage(AiMessage.from(checkOutput.apply(lightCalls.getAndIncrement()))).build();
                }
            };
            Planner planner = new Planner(LlmClient.of(model, null, 30), LlmClient.of(checkModel, null, 30));
            agent = new Agent(planner, executor, () -> {
                check(Thread.currentThread() == owner, "快照采集必须在游戏线程执行");
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
