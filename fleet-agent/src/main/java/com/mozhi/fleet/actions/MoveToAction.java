package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;
import java.util.List;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

public final class MoveToAction implements Action {
    private static final ActionSpec SPEC = new ActionSpec("MOVE_TO",
            "移动到实体、市场或星系中心，并在实际进入目标环绕轨道后成功；只下达移动/环绕指令不算完成。",
            List.of(ActionSupport.parameter("destinationId", ActionSpec.Type.STRING, true, "观察中存在的实体、市场或星系 ID")));

    @Override public ActionSpec spec() { return SPEC; }

    @Override public ExecutionResult execute(Step step, ActionContext context) {
        var target = ActionSupport.destination(context, ActionSupport.text(step, "destinationId"));
        if (target == context.fleet()) throw new IllegalArgumentException("不能以自身为移动目标");
        if (target.getContainingLocation() == null) throw new IllegalStateException("目的地已离开星区");
        if (ActionSupport.orbiting(context.fleet(), target)) return ActionSupport.result(step, SUCCEEDED, "已抵达并环绕 " + target.getName());
        if (ActionSupport.near(context.fleet(), target)) {
            ActionSupport.assign(context.fleet(), FleetAssignment.ORBIT_PASSIVE, target, "环绕 " + target.getName());
            return ActionSupport.result(step, RUNNING, "已靠近 " + target.getName() + "，等待实际进入环绕轨道");
        }
        ActionSupport.assign(context.fleet(), FleetAssignment.GO_TO_LOCATION, target, "前往 " + target.getName());
        return ActionSupport.result(step, RUNNING, "正在前往 " + target.getName());
    }

    @Override public void stop(ActionContext context) { ActionSupport.hold(context); }
}
