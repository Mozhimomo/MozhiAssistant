package com.mozhi.fleet.game;

import java.util.*;

/** 对话用投影；UI、存档及完整交易账本保留原始信息。 */
public final class FleetQueries {
    private FleetQueries() {}

    public static Map<String, Object> summary(Map<String, Object> full, Map<String, Object> trades) {
        var result = select(full, "fleetId", "location", "locationId", "logistics", "assignment", "assignmentTarget", "orbitTarget");
        result.put("shipCount", list(full.get("ships")).size());
        result.put("cargoStackCount", list(full.get("cargo")).size());
        var state = map(full.get("state"));
        var compact = select(state, "fleetId", "mode", "reason", "mission", "returnAfterCompletion", "returning", "awaitingReturnConfirmation", "plannerStatus");
        var plan = map(state.get("plan"));
        if (!plan.isEmpty()) {
            var steps = list(plan.get("steps"));
            int current = plan.get("currentStep") instanceof Number n ? n.intValue() : 0;
            int start = Math.min(Math.max(0, current), steps.size());
            var progress = select(plan, "id", "currentStep", "status");
            progress.put("totalSteps", steps.size()); progress.put("remainingSteps", steps.size() - start);
            progress.put("nextSteps", steps.subList(start, Math.min(start + 3, steps.size())).stream()
                    .map(step -> select(map(step), "action", "description", "status", "result")).toList());
            compact.put("plan", progress);
        }
        compact.put("tradeSummary", trades);
        compact.put("details", "交易明细通过 getMozhiTradeReceipts 按 mission.id 分页查询；cargoStackCount 非货物数量，现金净流入不等于净利润。");
        result.put("state", compact);
        return result;
    }

    public static Map<String, Object> receipts(String taskId, String expectedTask, List<Map<String, Object>> receipts, int offset, int limit) {
        if (taskId.isBlank() || !taskId.equals(expectedTask)) throw new IllegalArgumentException("任务已变化或不存在，请重新查询舰队状态并使用当前 mission.id");
        if (offset < 0 || offset > receipts.size() || limit < 1 || limit > 50) throw new IllegalArgumentException("offset 必须在账本范围内，limit 为 1–50");
        int end = Math.min(receipts.size(), offset + limit);
        return Map.of("taskId", taskId, "offset", offset, "total", receipts.size(), "nextOffset", end < receipts.size() ? end : -1,
                "hasMore", end < receipts.size(), "receipts", List.copyOf(receipts.subList(offset, end)),
                "scope", "本任务已成交且有金额回执的记录，按 stepId 去重；账本仍可能追加，下一页须沿用 taskId；缺失金额见状态汇总 unknownAmountTrades。");
    }

    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> m ? m : Map.of(); }
    private static List<?> list(Object value) { return value instanceof List<?> l ? l : List.of(); }
    private static Map<String, Object> select(Map<?, ?> source, String... keys) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : keys) if (source.containsKey(key)) result.put(key, source.get(key));
        return result;
    }
}
