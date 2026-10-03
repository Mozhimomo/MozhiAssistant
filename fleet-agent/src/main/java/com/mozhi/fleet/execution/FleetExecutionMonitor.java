package com.mozhi.fleet.execution;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.mozhi.fleet.game.FleetDestinations;
import com.mozhi.fleet.game.FleetWorld;
import com.mozhi.fleet.model.FleetPlanStep;
import com.mozhi.fleet.model.FleetState;
import java.util.Objects;

/** 主线程轻量检查：任务目标被覆盖或长期没有推进，不判断经营策略。 */
public final class FleetExecutionMonitor {
    private String stepId="", locationId="";
    private double bestDistance=Double.POSITIVE_INFINITY,lastProgressDay,lastFollowDays;
    public void reset() {stepId="";}

    public String deviation(CampaignFleetAPI fleet,FleetState state,double stallDays) {
        FleetPlanStep step=state.plan==null?null:state.plan.current();
        if(step==null || step.status!=FleetPlanStep.Status.RUNNING) return null;
        if(fleet.getBattle()!=null || fleet.isInHyperspaceTransition()) {
            lastProgressDay=state.elapsedDays;
            return null;
        }
        if(step.action==FleetPlanStep.Action.BUY || step.action==FleetPlanStep.Action.SELL) return null;
        SectorEntityToken target;
        if(step.action==FleetPlanStep.Action.MOVE_TO) target=FleetDestinations.forStep(step).entity();
        else {
            CampaignFleetAPI player=FleetWorld.player();
            if(player.getBattle()!=null || player.isInHyperspaceTransition()) {lastProgressDay=state.elapsedDays; return null;}
            target=player;
        }
        var assignment=fleet.getAI()==null?null:fleet.getAI().getCurrentAssignment();
        if(assignment!=null && (assignment.getTarget()!=target
                || (assignment.getAssignment()!=FleetAssignment.GO_TO_LOCATION
                && assignment.getAssignment()!=FleetAssignment.FOLLOW && assignment.getAssignment()!=FleetAssignment.ORBIT_PASSIVE)))
            return "原生任务已偏离当前步骤：实际任务 "+assignment.getAssignment()+"，预期目标 "+target.getName();
        boolean local=fleet.getContainingLocation()==target.getContainingLocation();
        double distance=local?FleetWorld.distance(fleet,target)
                :Math.hypot(fleet.getLocationInHyperspace().x-target.getLocationInHyperspace().x,
                           fleet.getLocationInHyperspace().y-target.getLocationInHyperspace().y);
        String location=fleet.getContainingLocation().getId()+":"+local;
        if(!Objects.equals(stepId,step.id) || !locationId.equals(location)) {
            stepId=step.id; locationId=location; bestDistance=distance;
            lastProgressDay=state.elapsedDays; lastFollowDays=step.progressDays;
        }
        boolean following=step.action==FleetPlanStep.Action.FOLLOW_PLAYER && FleetWorld.following(fleet,FleetWorld.player());
        if(distance<bestDistance-25 || step.progressDays>lastFollowDays || following || FleetWorld.isOrbiting(fleet,target)) {
            bestDistance=distance; lastFollowDays=step.progressDays; lastProgressDay=state.elapsedDays;
        }
        return state.elapsedDays-lastProgressDay>=stallDays
                ? "当前步骤已连续 "+stallDays+" 个游戏日没有接近目标、完成跟随或进入轨道" : null;
    }
}
