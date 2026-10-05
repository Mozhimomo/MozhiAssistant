package com.mozhi.fleet.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.fleet.model.Plan;
import java.util.*;

/** 模型专用投影；不改变完整存档、执行账本或接纳结果时的校验依据。 */
public final class PlanningPrompt {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PlanningPrompt() {}

    /** 周期检查不发送动作契约、市场清单和整段历史，仅发送当前决策所需的状态。 */
    public static String periodic(PlanningRequest request) throws java.io.IOException {
        var full = JSON.readTree(encode(request));
        var data = JSON.createObjectNode();
        data.set("goal", full.get("goal"));
        data.set("currentPlan", full.get("currentPlan"));
        data.set("resources", full.get("resources"));
        var history = full.path("executionHistory").path("recentResults");
        if (!history.isEmpty()) data.set("lastResult", history.get(history.size() - 1));
        var world = full.path("worldState");
        if (world.isObject()) {
            var state = data.putObject("worldState");
            for (String key : List.of("controlledFleet", "controlledFleetState", "tradeSummary", "returning", "returnAfterCompletion", "followTargets"))
                if (world.has(key)) state.set(key, world.get(key));
        } else data.set("worldState", world);
        return JSON.writeValueAsString(data);
    }

    public static String encode(PlanningRequest request) {
        return encode(request, null);
    }

    /** 重试仍使用同一真实快照，不提供截断正文，不把生成失败伪装成执行失败。 */
    public static String recovery(PlanningRequest request, String advisoryDecision) {
        return encode(request, advisoryDecision) + "\n上一次结构化回答被输出上限截断，这是生成失败，不代表游戏动作失败。"
                + "请重新返回完整且简短的决策 JSON；reason 用一句话，只列必要的剩余步骤，不复述历史或市场清单。"
                + "保持原目标、授权、金额和剩余出售义务；可用的原计划优先 KEEP。需要跑商时使用计算工具，不展开猜测的多轮买卖。";
    }

    public static String encode(PlanningRequest request, String advisoryDecision) {
        var data = new LinkedHashMap<String, Object>();
        // 静态动作契约在任何任务 ID、版本及动态状态之前。
        data.put("tools", request.actions());
        data.put("goal", request.goal());
        var done = request.executionHistory().completedStepIds();
        var remaining = request.currentPlan() == null ? List.of() : request.currentPlan().steps().stream()
                .filter(step -> !done.contains(step.id())).toList();
        data.put("currentPlan", remaining.isEmpty() ? null : Map.of("steps", remaining));
        var history = new LinkedHashMap<String, Object>();
        history.put("completedStepCount", done.size());
        history.put("recentResults", request.executionHistory().recentResults().stream().map(result -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("step", Map.of("id", result.step().id(), "tool", result.step().tool(), "arguments", result.step().arguments()));
            row.put("status", result.status()); row.put("result", result.result());
            if (result.tradeReceipt() != null) row.put("tradeReceipt", result.tradeReceipt());
            if (result.generatedPlan() != null) row.put("generatedStepCount", result.generatedPlan().steps().size());
            return row;
        }).toList());
        data.put("executionHistory", history);
        Object world;
        try { world = JSON.readTree(request.worldState()); }
        catch (Exception textOnly) { world = request.worldState(); }
        data.put("worldState", world); // JSON 对象直接嵌入，避免二次转义。
        data.put("resources", request.resources());
        if (advisoryDecision != null) data.put("lightModelAssessment", advisoryDecision);
        data.put("completionReview", request.completionReview());
        data.put("trigger", request.trigger());
        data.put("taskId", request.taskId());
        data.put("revision", request.revision());
        try { return JSON.writeValueAsString(canonical(JSON.valueToTree(data), true)); }
        catch (Exception error) { throw new IllegalStateException("无法编码规划快照", error); }
    }

    /** 保留顶层的缓存顺序，嵌套对象按键排序；数组保留业务顺序。 */
    private static JsonNode canonical(JsonNode node, boolean root) {
        if (node.isObject()) {
            var result = JSON.createObjectNode();
            List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add);
            if (!root) Collections.sort(keys);
            for (String key : keys) result.set(key, canonical(node.get(key), false));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode(); node.forEach(value -> result.add(canonical(value, false))); return result;
        }
        return node;
    }
}
