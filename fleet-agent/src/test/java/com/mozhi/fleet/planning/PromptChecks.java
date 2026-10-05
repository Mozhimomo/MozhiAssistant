package com.mozhi.fleet.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.fleet.model.*;
import java.util.*;

/** 请求体边界、缓存前缀及本地完整账本与模型摘要的一致性。 */
public final class PromptChecks {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var results = new ArrayList<ExecutionResult>();
        var history = new ExecutionHistory();
        for (int i = 0; i < 1000; i++) {
            Step step = new Step("trade-" + i, i % 2 == 0 ? "BUY" : "SELL",
                    Map.of("itemType", "COMMODITY", "itemId", "supplies", "quantity", 2), "购买或出售", "预期库存改变");
            var result = new ExecutionResult(step, ExecutionResult.Status.SUCCEEDED, "已结算", null,
                    i % 2 == 0 ? new TradeReceipt(100, 0, 100) : new TradeReceipt(0, 150, 150));
            results.add(result); history.record(result);
        }
        var totals = TradeSummary.of(results);
        check(totals.get("creditsSpent").equals(50000d) && totals.get("creditsReceived").equals(75000d), "汇总超出最近历史窗口的所有交易");
        var unknown = new ExecutionResult(Step.create("BUY", Map.of(), "旧交易", "未知"), ExecutionResult.Status.SUCCEEDED, "旧记录");
        results.add(unknown);
        check(TradeSummary.of(results).get("unknownAmountTrades").equals(1), "缺少回执不代表零成本交易");
        Step remaining = Step.create("MOVE_TO", Map.of("destinationId", "market"), "航行", "抵达");
        var all = new ArrayList<Step>(); results.stream().limit(1000).forEach(result -> all.add(result.step())); all.add(remaining);
        var plan = Plan.create("持有100万", all);
        var request = new PlanningRequest("task", 1000, plan.goal(), json.writeValueAsString(Map.of("tradeSummary", totals, "credits", 1050000)),
                history.snapshot(), "定期重新规划", List.of(new ActionSpec("MOVE_TO", "导航", List.of())), plan);
        String compact = PlanningPrompt.encode(request);
        var tree = json.readTree(compact);
        check(tree.path("currentPlan").path("steps").size() == 1 && tree.path("currentPlan").path("steps").get(0).path("id").asText().equals(remaining.id()), "仅将未完成步骤发送给模型");
        check(tree.path("executionHistory").path("recentResults").size() == 20 && !tree.path("executionHistory").has("completedStepIds"), "保留 20 条结果，不发送无限增长的已完成 ID");
        check(tree.path("worldState").isObject() && tree.path("worldState").path("credits").asInt() == 1050000, "投影保留实际目标余额，不重复转义字符串");
        check(plan.steps().size() == 1001 && history.snapshot().completedStepIds().size() == 1000, "完整校验状态与持久化状态保持不变");
        var changed = new PlanningRequest("different-task", 1001, plan.goal(), "{\"credits\":1060000}", history.snapshot(), "新任务", request.actions(), plan);
        String second = PlanningPrompt.encode(changed);
        check(compact.substring(0, compact.indexOf("\"worldState\"")).equals(second.substring(0, second.indexOf("\"worldState\""))), "世界、任务和版本变化不影响前置稳定内容");
        check(compact.startsWith("{\"tools\":") && compact.indexOf("\"taskId\"") > compact.indexOf("\"worldState\""), "静态动作在前，易变身份在后");
        var left = new PlanningRequest("same", 1, plan.goal(), "{\"b\":2,\"a\":{\"y\":1,\"x\":0}}", history.snapshot(), "检查", request.actions(), plan);
        var right = new PlanningRequest("same", 1, plan.goal(), "{\"a\":{\"x\":0,\"y\":1},\"b\":2}", history.snapshot(), "检查", request.actions(), plan);
        check(PlanningPrompt.encode(left).equals(PlanningPrompt.encode(right)), "映射插入顺序不影响提示词前缀");
        check(compact.length() < json.writeValueAsString(request).length() / 4, "长时间运行的请求投影显著缩小");
        var calc = Step.create("CALCULATE_TRADE_ROUTE", Map.of(), "计算", "计划");
        var generatedHistory = new ExecutionHistory();
        generatedHistory.record(new ExecutionResult(calc, ExecutionResult.Status.SUCCEEDED, "路线已生成", plan));
        var generatedRequest = new PlanningRequest("calc", 0, plan.goal(), "{}", generatedHistory.snapshot(), "计算结束", List.of(), null);
        String generated = PlanningPrompt.encode(generatedRequest);
        check(!generated.contains("generatedPlan") && generated.contains("generatedStepCount"), "历史中不重复保存完整生成路线");
        System.out.println("提示词与账本检查通过；长时间运行的模拟请求字符数：" + json.writeValueAsString(request).length() + " -> " + compact.length());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
