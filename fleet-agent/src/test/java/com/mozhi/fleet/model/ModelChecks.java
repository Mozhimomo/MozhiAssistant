package com.mozhi.fleet.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 无游戏、无网络检查：验证跨线程数据边界和计划序列化。 */
public final class ModelChecks {
    public static void main(String[] args) throws Exception {
        immutableParameters();
        orderedStepsAndIdentity();
        invalidDefinitions();
        jsonRoundTrip();
        generatedPlans();
        System.out.println("Plan / Step checks passed");
    }

    private static void immutableParameters() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("quantity", 100);
        List<Object> items = new ArrayList<>();
        items.add(item);
        items.add(null);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("items", items);
        Step step = Step.create("BUY", parameters, "采购补给", "持有指定数量的补给");

        item.put("quantity", 999);
        items.clear();
        parameters.clear();
        List<?> saved = (List<?>) step.parameters().get("items");
        check(saved.size() == 2 && saved.get(1) == null, "Preserve nested values and JSON null");
        check(((Map<?, ?>) saved.get(0)).get("quantity").equals(100), "Snapshot nested parameters");
        rejects(UnsupportedOperationException.class, () -> step.parameters().put("new", true));
        rejects(UnsupportedOperationException.class, saved::clear);
        rejects(UnsupportedOperationException.class, () -> ((Map<?, ?>) saved.get(0)).clear());
    }

    private static void orderedStepsAndIdentity() {
        Step travel = Step.create("MOVE_TO", Map.of("destinationId", "jangala"), "前往市场", "抵达目标市场");
        Step buy = Step.create("BUY", Map.of("item", "supplies", "quantity", 100), "购买补给", "补给增加 100");
        List<Step> source = new ArrayList<>(List.of(travel, buy));
        Plan original = Plan.create("采购补给", source);
        source.clear();
        check(original.steps().equals(List.of(travel, buy)), "Preserve step order and isolate source list");
        rejects(UnsupportedOperationException.class, () -> original.steps().clear());

        Plan remaining = Plan.create(original.goal(), List.of(buy));
        check(!original.id().equals(remaining.id()), "New plan has a new identity");
        check(remaining.steps().get(0).id().equals(buy.id()), "Replan can retain unchanged step identity");
        check(original.steps().size() == 2, "Replacement does not mutate the original plan");
    }

    private static void invalidDefinitions() {
        Step step = Step.create("WAIT", Map.of(), "等待", "等待结束");
        rejects(IllegalArgumentException.class, () -> new Plan("", "目标", List.of(step)));
        rejects(IllegalArgumentException.class, () -> Plan.create(" ", List.of(step)));
        rejects(IllegalArgumentException.class, () -> Plan.create("目标", List.of()));
        rejects(NullPointerException.class, () -> Plan.create("目标", null));
        List<Step> nullStep = new ArrayList<>();
        nullStep.add(null);
        rejects(NullPointerException.class, () -> Plan.create("目标", nullStep));
        Step duplicateId = new Step(step.id(), "MOVE_TO", Map.of(), "不同动作", "不同结果");
        rejects(IllegalArgumentException.class, () -> Plan.create("目标", List.of(step, duplicateId)));
        rejects(IllegalArgumentException.class, () -> Step.create(" ", Map.of(), "说明", "结果"));
        rejects(IllegalArgumentException.class, () -> Step.create("WAIT", Map.of(), "", "结果"));
        rejects(IllegalArgumentException.class, () -> Step.create("WAIT", Map.of(), "说明", null));
        rejects(NullPointerException.class, () -> Step.create("WAIT", null, "说明", "结果"));
        rejects(IllegalArgumentException.class, () -> Step.create("WAIT", Map.of("seconds", Double.NaN), "说明", "结果"));
        rejects(IllegalArgumentException.class, () -> Step.create("WAIT", Map.of("seconds", Float.POSITIVE_INFINITY), "说明", "结果"));
        rejects(IllegalArgumentException.class, () -> Step.create("WAIT", Map.of("entity", new Object()), "说明", "结果"));
        rejects(IllegalArgumentException.class, () -> Step.create("WAIT", Map.of("nested", Map.of(1, "value")), "说明", "结果"));
    }

    private static void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String data = """
                {"id":"plan-1","goal":"采购补给","steps":[
                  {"id":"step-1","action":"BUY","parameters":{
                    "destinationId":"jangala","quantity":100,"maxPrice":150.5,
                    "allowPartial":false,"items":["supplies",null],"options":{"market":null}
                  },"description":"买入补给","expectedOutcome":"持有 100 个补给"}
                ]}
                """;
        Plan decoded = mapper.readValue(data, Plan.class);
        Plan restored = mapper.readValue(mapper.writeValueAsString(decoded), Plan.class);
        check(decoded.equals(restored), "JSON round-trip preserves plan definition and IDs");
        rejects(UnsupportedOperationException.class, () -> restored.steps().clear());
        rejects(UnsupportedOperationException.class, () -> restored.steps().get(0).parameters().clear());
        String invalid = data.replace("\"action\":\"BUY\"", "\"action\":\"\"");
        rejects(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(invalid, Plan.class));
    }

    private static void generatedPlans() throws Exception {
        Step calc = Step.create("CALCULATE_TRADE_ROUTE", Map.of(), "计算", "路线就绪");
        Step tail = Step.create("RETURN", Map.of(), "回归", "合并");
        Step trade = Step.create("BUY", Map.of(), "购买", "完成交易");
        Plan original = Plan.create("跑商后回归", List.of(calc, tail));
        Plan child = Plan.create("计算路线", List.of(trade));
        Plan expanded = original.insertAfter(0, child);
        check(expanded.steps().equals(List.of(calc, trade, tail)), "Insert immediately after decision, before original suffix");
        check(expanded.goal().equals(original.goal()) && !expanded.id().equals(original.id()), "Keep goal and renew plan identity");
        check(original.steps().equals(List.of(calc, tail)), "Original snapshot remains immutable");
        rejects(IllegalArgumentException.class, () -> original.insertAfter(0, Plan.create("collision", List.of(tail))));
        rejects(IllegalArgumentException.class, () -> original.insertAfter(-1, child));
        rejects(IllegalArgumentException.class, () -> original.insertAfter(2, child));
        ObjectMapper json = new ObjectMapper();
        ExecutionResult result = new ExecutionResult(calc, ExecutionResult.Status.SUCCEEDED, "计算成功", child);
        check(json.readValue(json.writeValueAsString(result), ExecutionResult.class).equals(result), "Generated plan survives JSON round trip");
        var legacy = json.valueToTree(result); ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("generatedPlan");
        check(json.treeToValue(legacy, ExecutionResult.class).generatedPlan() == null, "Old saves without generatedPlan remain readable");
        for (var status : List.of(ExecutionResult.Status.RUNNING, ExecutionResult.Status.WAITING, ExecutionResult.Status.FAILED))
            rejects(IllegalArgumentException.class, () -> new ExecutionResult(calc, status, "invalid", child));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> expected, CheckedAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Expected " + expected.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
