package com.mozhi.fleet.execution;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.mozhi.fleet.game.FleetDestinations;
import com.mozhi.fleet.game.FleetTransfer;
import com.mozhi.fleet.game.FleetWorld;
import com.mozhi.fleet.model.FleetPlan;
import com.mozhi.fleet.model.FleetPlanStep;
import com.mozhi.fleet.model.FleetState;
import com.mozhi.fleet.trade.FleetTrading;
import java.util.Locale;

/** Execute：导航、交易、跟随和返回合并。所有游戏状态操作发生在主线程。 */
public final class FleetPlanExecutor {
    public void advance(CampaignFleetAPI fleet, FleetState state, double days) {
        FleetPlan plan = state.plan;
        if (plan == null || !plan.active()) {
            FleetWorld.passive(fleet);
            FleetWorld.holdPosition(fleet); return;
        }
        FleetPlanStep step = plan.current();
        if (step == null) { plan.status = FleetPlan.Status.COMPLETED; return; }
        CampaignFleetAPI player = FleetWorld.player();
        boolean needsPlayer = step.action==FleetPlanStep.Action.FOLLOW_PLAYER || step.action==FleetPlanStep.Action.RETURN;
        if (fleet.getBattle() != null || fleet.isInHyperspaceTransition()
                || (needsPlayer && (player.getBattle()!=null || player.isInHyperspaceTransition()))) {
            plan.status = FleetPlan.Status.PAUSED; plan.feedback = "等待战斗或跃迁结束";
            state.reason = plan.feedback; return;
        }
        FleetWorld.passive(fleet);
        plan.status = FleetPlan.Status.RUNNING; plan.feedback = "";
        if (step.status == FleetPlanStep.Status.PENDING) {
            step.status = FleetPlanStep.Status.RUNNING; step.startedDay = state.elapsedDays;
            state.note("开始步骤 " + (plan.currentStep + 1) + "/" + plan.steps.size() + "：" + step.description);
        }
        state.mode = step.action.name(); state.reason = step.description;
        switch (step.action) {
            case MOVE_TO -> moveTo(fleet,state,step);
            case BUY, SELL -> trade(fleet,state,step);
            case FOLLOW_PLAYER -> {
                FleetWorld.follow(fleet);
                if (FleetWorld.following(fleet, player)) step.progressDays += days;
                if (step.durationDays > 0) {
                    step.result = String.format(Locale.ROOT, "跟随进度 %.2f / %.2f 游戏日", step.progressDays, step.durationDays);
                    if (step.progressDays >= step.durationDays) complete(state, "已完成指定时长的跟随");
                } else step.result = FleetWorld.following(fleet, player) ? "持续跟随玩家，等待新命令" : "正在追赶玩家";
            }
            case RETURN -> {
                if (player.getBattle() != null) {
                    plan.status = FleetPlan.Status.PAUSED; plan.feedback = "等待玩家结束战斗后合并";
                    state.reason = plan.feedback; return;
                }
                if (!FleetWorld.near(fleet, player)) {
                    FleetWorld.returnToPlayer(fleet);
                    step.result = "正在返回玩家，尚未合并";
                    return;
                }
                FleetTransfer.merge(fleet);
                complete(state, "已实际合并剩余舰船、军官、货物和信用点");
                state.fleetId = ""; state.mode = "MERGED";
            }
        }
    }
    private void moveTo(CampaignFleetAPI fleet, FleetState state, FleetPlanStep step) {
        var destination=FleetDestinations.forStep(step);
        var target=destination.entity();
        boolean sameLocation=fleet.getContainingLocation()==target.getContainingLocation();
        if (FleetWorld.isOrbiting(fleet,target)) {
            complete(state,"已抵达 "+step.destination+" 并进入环绕轨道"); return;
        }
        if (!sameLocation || (!step.orbitStarted && !FleetWorld.near(fleet,target))) {
            step.orbitStarted=false;
            FleetWorld.goTo(fleet,target); step.result="正在前往 "+target.getName(); return;
        }
        FleetWorld.orbit(fleet,target);
        // 下达环绕任务不等于已入轨；后续 tick 读取原生 OrbitAPI 确认。
        step.orbitStarted=true; step.result="已靠近目的地，等待进入环绕轨道";
    }
    private void trade(CampaignFleetAPI fleet, FleetState state, FleetPlanStep step) {
        var destination=FleetDestinations.forStep(step);
        FleetWorld.requireOrbit(fleet,destination.entity());
        // BUY/SELL 不下达任何移动或环绕任务，只进行本地交易。
        complete(state,FleetTrading.execute(fleet,destination.market(),step));
    }
    private static void complete(FleetState state, String result) {
        state.plan.completeStep(result, state.elapsedDays);
        state.note("完成步骤：" + result);
        if (!state.plan.active()) {
            state.plannerStatus = "计划已完成";
            var action=state.plan.steps.get(state.plan.steps.size()-1).action;
            state.mode = action==FleetPlanStep.Action.MOVE_TO || action==FleetPlanStep.Action.BUY || action==FleetPlanStep.Action.SELL ? "ORBIT" : "IDLE";
            state.reason = result;
        }
    }
}
