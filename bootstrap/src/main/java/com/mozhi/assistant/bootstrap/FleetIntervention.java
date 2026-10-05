package com.mozhi.assistant.bootstrap;

import java.util.List;
import java.util.Map;

/** 主线程采集的异常快照；后台通知生成不再读取游戏对象。 */
record FleetIntervention(String taskId, String planId, String goal, String step, String reason, boolean completed,
                         String stepStatus, String stepResult) {
    static FleetIntervention from(Map<String, Object> data) {
        Map<?, ?> state = map(data.get("state")), mission = map(state.get("mission")), plan = map(state.get("plan"));
        String mode = text(state.get("mode"));
        String status = first(text(mission.get("status")), text(plan.get("status")), mode);
        boolean completed = status.equals("COMPLETED") && Boolean.TRUE.equals(state.get("awaitingReturnConfirmation"));
        if (!completed && !mode.equals("LOST") && !status.equals("BLOCKED") && !status.equals("FAILED")) return null;
        String step = "", stepStatus = "", stepResult = "";
        if (plan.get("steps") instanceof List<?> steps) {
            int current = plan.get("currentStep") instanceof Number number ? number.intValue() : -1;
            if (current >= 0 && current < steps.size()) {
                var row = map(steps.get(current));
                step = text(row.get("description")); stepStatus = text(row.get("status")); stepResult = text(row.get("result"));
            }
        }
        return new FleetIntervention(text(mission.get("id")), text(plan.get("id")),
                first(text(mission.get("originalGoal")), text(plan.get("goal")), text(state.get("order")), "舰队任务"), step,
                first(text(state.get("reason")), text(mission.get("reviewReason")), mode.equals("LOST") ? "舰队已失联或移除" : "任务无法继续，需要玩家确认"), completed, stepStatus, stepResult);
    }

    String snapshot() {
        if (completed) return "事件：任务完成，等待玩家决定是否返航\n目标：" + goal + "\n验收结论：" + reason
                + "\n分舰队尚未返航。请主动告知舰长任务已完成，并询问是否返航；等待玩家答复。";
        return "目标：" + goal + "\n暂停时当前步骤：" + (step.isBlank() ? "未确定" : step)
                + "\n已记录动作状态：" + (stepStatus.isBlank() ? "未知" : stepStatus)
                + "\n已记录动作结果：" + (stepResult.isBlank() ? "未提供" : stepResult) + "\n异常原因：" + reason
                + "\n规划失败与动作执行失败是两件事，当前步骤名称不能作为成交或失败证明。"
                + "快照未提供当前资金或完整交易账本，不能推断资金未变、此前零交易或任务尚未开始。";
    }
    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String first(String... values) { for (String value : values) if (!value.isBlank()) return value; return ""; }
}
