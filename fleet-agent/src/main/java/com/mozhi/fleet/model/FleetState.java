package com.mozhi.fleet.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** 状态仅保存指挥数据，实际舰船、军官和货物由原生存档保存。 */
public final class FleetState {
    public int version = 2;
    public String fleetId = "";
    public String mode = "IDLE";
    public String order = "";
    public String reason = "";
    public String plannerStatus = "";
    public long revision;
    public double elapsedDays;
    public FleetPlan plan;
    public FleetMission mission;
    public List<String> log = new ArrayList<>();

    public static FleetState restore(ObjectMapper json, String saved) throws Exception {
        JsonNode data = json.readTree(saved);
        int version = data.path("version").asInt();
        FleetState state;
        if (version == 1) {
            // 先读取基础字段，避免旧行动枚举导致整个存档无法加载。
            state = new FleetState();
            state.fleetId = data.path("fleetId").asText("");
            state.elapsedDays = data.path("elapsedDays").asDouble();
            state.revision = data.path("revision").asLong() + 1;
            if (!state.fleetId.isEmpty()) {
                state.plan = data.path("mode").asText().equals("RETURN") ? FleetPlan.recall() : FleetPlan.follow(0, false);
                state.plan.prepare(state.revision, state.elapsedDays);
                state.order = state.plan.goal; state.mode = state.plan.current().action.name();
                state.note("旧版发展计划已移除，保留原舰队及资产，切换为" + state.order);
            }
        } else if (version == 2) state = json.treeToValue(data, FleetState.class);
        else throw new IllegalStateException("不支持的舰队存档版本，未覆盖");
        if (state.fleetId == null || state.log == null || !Double.isFinite(state.elapsedDays) || state.elapsedDays < 0)
            throw new IllegalStateException("舰队存档内容无效");
        if (state.plan != null) {
            state.plan.validate();
            if (state.plan.status == null || state.plan.currentStep < 0 || state.plan.currentStep > state.plan.steps.size())
                throw new IllegalStateException("计划进度无效");
            for (FleetPlanStep step : state.plan.steps)
                if (step.status == null || !Double.isFinite(step.progressDays) || step.progressDays < 0)
                    throw new IllegalStateException("步骤进度无效");
        }
        if(state.mission==null && state.plan!=null) {
            state.mission=FleetMission.start(state.order==null || state.order.isBlank()?state.plan.goal:state.order);
            state.mission.approve(state.plan); state.mission.capture(state.plan);
            state.mission.status=state.plan.status==FleetPlan.Status.COMPLETED?FleetMission.Status.COMPLETED:
                    state.plan.active()?FleetMission.Status.REVIEWING:FleetMission.Status.BLOCKED;
        }
        if(state.mission!=null && (state.mission.originalGoal==null || state.mission.status==null
                || state.mission.objectives==null || state.mission.receipts==null || state.mission.replanCount<0))
            throw new IllegalStateException("舰队子任务记录无效");
        return state;
    }
    public void note(String text) {
        log.add(String.format(java.util.Locale.ROOT, "%.1f日：%s", elapsedDays, text));
        while (log.size() > 30) log.remove(0);
    }
}
