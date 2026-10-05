package com.mozhi.assistant.bootstrap.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 仅提取目标、步骤和状态，技术诊断保留在桥接查询里。 */
record FleetPresentation(String taskId, String planId, String goal, String status, String detail, List<Step> steps) {
    record Step(String title, String status) {}
    static FleetPresentation from(Map<String, Object> data) {
        var state = map(data.get("state")); var mission = map(state.get("mission")); var plan = map(state.get("plan"));
        String mode = text(state.get("mode"));
        String status = first(text(mission.get("status")), text(plan.get("status")), mode, "IDLE");
        if (mode.equals("LOST") || data.containsKey("error")) status = "BLOCKED";
        if (mode.equals("MERGED") && text(mission.get("status")).isBlank() && text(plan.get("status")).isBlank()) status = "COMPLETED";
        String goal = first(text(mission.get("originalGoal")), text(plan.get("goal")), text(state.get("order")), "尚未设置目标");
        List<Step> steps = new ArrayList<>();
        String failedResult = "";
        for (Object raw : list(plan.get("steps"))) {
            var step = map(raw);
            steps.add(new Step(first(text(step.get("description")), action(text(step.get("action")))), text(step.get("status"))));
            if (text(step.get("status")).equals("FAILED")) failedResult = text(step.get("result"));
        }
        String detail = switch (status) {
            case "BLOCKED", "FAILED" -> compact(first(text(data.get("error")), text(state.get("reason")), text(mission.get("reviewReason")), "请重新下达任务"));
            case "COMPLETED" -> mode.equals("MERGED") ? "已回归，舰队资产已合并" : "目标已完成，等待你决定是否返航";
            case "REVIEWING" -> "计划已执行完，正在确认目标是否达成";
            case "PLANNING", "REPLANNING" -> steps.stream().anyMatch(step -> step.status().equals("FAILED"))
                    ? compact(first(failedResult, "步骤执行失败，正在重新规划")) : "正在安排接下来的行动";
            case "CANCELLED" -> "任务已取消，等待新指令";
            case "IDLE" -> "与墨汁交流，下达舰队任务";
            default -> text(state.get("plannerStatus")).isBlank() ? "按计划执行中" : "继续执行，同时更新计划";
        };
        return new FleetPresentation(text(mission.get("id")), text(plan.get("id")), goal, status, detail, List.copyOf(steps));
    }
    int completed() { return (int) steps.stream().filter(step -> step.status().equals("COMPLETED") || step.status().equals("SUCCEEDED")).count(); }
    boolean attention() {
        if (status.equals("BLOCKED") || status.equals("FAILED")) return true;
        if (status.equals("COMPLETED") || status.equals("CANCELLED") || status.equals("IDLE")) return false;
        return steps.stream().anyMatch(step -> step.status().equals("FAILED"));
    }
    boolean success() { return status.equals("COMPLETED"); }
    String eventKey() { return first(taskId, planId, goal) + ":" + status; }
    String label() {
        return attention() && (status.equals("PLANNING") || status.equals("REPLANNING")) ? "异常 · 重规划中" : label(status);
    }
    static String label(String status) { return switch (status) {
        case "COMPLETED", "SUCCEEDED" -> "已完成"; case "BLOCKED", "FAILED" -> "需要处理";
        case "REVIEWING" -> "验收中";
        case "PLANNING", "REPLANNING" -> "规划中"; case "EXECUTING", "RUNNING" -> "执行中";
        case "WAITING", "PAUSED" -> "等待中"; case "CANCELLED" -> "已取消";
        case "PENDING", "READY" -> "待执行"; default -> "等待指令";
    }; }
    private static String compact(String value) {
        if (value.contains("LENGTH") || value.contains("输出上限")) return "规划回复超出长度，请重新下达任务";
        if (value.contains("初始化失败")) return "舰队控制器未就绪，请重新载入游戏";
        String line = value.replaceAll("\\s+", " ").trim();
        return line.length() > 64 ? line.substring(0, 63) + "…" : line;
    }
    private static String action(String name) { return switch (name) { case "MOVE_TO" -> "前往目的地"; case "BUY" -> "购买货物"; case "SELL" -> "出售货物"; case "RETURN" -> "返回玩家舰队"; case "FOLLOW_FLEET" -> "持续跟随目标舰队"; case "TRANSFER_TO_PLAYER" -> "向玩家转账"; case "CALCULATE_TRADE_ROUTE" -> "计算跑商路线"; case "PREPARE_TRADE_HOP" -> "到站重算采购单"; default -> "执行步骤"; }; }
    private static String first(String... values) { for (String value : values) if (!value.isBlank()) return value; return ""; }
    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
