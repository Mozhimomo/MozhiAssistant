package com.mozhi.fleet.game;

import java.util.*;

public final class FleetQueryChecks {
    public static void main(String[] args) {
        List<Map<String, Object>> ledger = new ArrayList<>();
        for (int i = 0; i < 123; i++) ledger.add(Map.of("stepId", "trade-" + i, "creditsSpent", i, "creditsReceived", 0));
        List<Object> rows = new ArrayList<>();
        int offset = 0;
        do {
            var page = FleetQueries.receipts("task", "task", ledger, offset, 20);
            rows.addAll((List<?>) page.get("receipts"));
            offset = (int) page.get("nextOffset");
        } while (offset >= 0);
        require(rows.equals(ledger), "所有分页保留精确且有序的账本，不遗漏也不重复");
        for (int limit : new int[]{0, 51}) {
            try { FleetQueries.receipts("task", "task", ledger, 0, limit); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { }
        }
        try { FleetQueries.receipts("new-task", "task", ledger, 0, 20); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        var steps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) steps.add(Map.of("action", "BUY", "description", "step-" + i, "status", "PENDING"));
        var full = Map.<String, Object>of("ships", List.of(), "cargo", List.of(), "state", Map.of("plan", Map.of("currentStep", 50, "steps", steps), "tradeReceipts", ledger));
        var compact = FleetQueries.summary(full, Map.of("creditsSpent", 123));
        var state = (Map<?, ?>) compact.get("state"); var plan = (Map<?, ?>) state.get("plan");
        require(!state.containsKey("tradeReceipts") && ((List<?>) plan.get("nextSteps")).size() == 3 && plan.get("remainingSteps").equals(50), "摘要大小有界，不删除本地完整数据");
        require(ledger.size() == 123 && steps.size() == 100, "投影不修改源数据");
        System.out.println("状态摘要与账本分页检查通过");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
