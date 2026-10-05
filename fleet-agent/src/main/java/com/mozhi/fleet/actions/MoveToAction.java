package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

public final class MoveToAction implements Action {
    @dev.langchain4j.agent.tool.Tool(name = "MOVE_TO", value = "移动到实体、市场或星系中心，实际进入目标环绕轨道后成功；只下达移动指令不算完成。")
    public ExecutionResult moveTo(Step step, ActionContext context, @dev.langchain4j.agent.tool.P(name = "destinationId", value = "观察中存在的实体、市场或星系 ID") String destinationId) {
        var target = ActionSupport.destination(context, destinationId);
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
