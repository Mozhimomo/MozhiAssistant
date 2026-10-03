package com.mozhi.assistant.bootstrap;

import java.util.List;
import java.util.Map;

/** 主线程采集的异常快照；后台通知生成不再读取游戏对象。 */
record FleetIntervention(String taskId, String planId, String goal, String step, String reason) {
    static FleetIntervention from(Map<String, Object> data) {
        Map<?, ?> state = map(data.get("state")), mission = map(state.get("mission")), plan = map(state.get("plan"));
        String mode = text(state.get("mode"));
        String status = first(text(mission.get("status")), text(plan.get("status")), mode);
        if (!mode.equals("LOST") && !status.equals("BLOCKED") && !status.equals("FAILED")) return null;
        String step = "";
        if (plan.get("steps") instanceof List<?> steps) {
            int current = plan.get("currentStep") instanceof Number number ? number.intValue() : -1;
            if (current >= 0 && current < steps.size()) step = text(map(steps.get(current)).get("description"));
        }
        return new FleetIntervention(text(mission.get("id")), text(plan.get("id")),
                first(text(mission.get("originalGoal")), text(plan.get("goal")), text(state.get("order")), "舰队任务"), step,
                first(text(state.get("reason")), text(mission.get("reviewReason")), mode.equals("LOST") ? "舰队已失联或移除" : "任务无法继续，需要玩家确认"));
    }

    String snapshot() {
        return "目标：" + goal + "\n受阻步骤：" + (step.isBlank() ? "未确定" : step) + "\n异常原因：" + reason;
    }
    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String first(String... values) { for (String value : values) if (!value.isBlank()) return value; return ""; }
}
