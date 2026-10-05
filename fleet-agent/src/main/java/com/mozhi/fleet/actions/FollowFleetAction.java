package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.mozhi.fleet.game.GameWorld;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 沿用返航的逐帧追赶方式，靠近后等待目标移动，不合并资产或结束步骤。 */
public final class FollowFleetAction implements Action {
    @Tool(name = "FOLLOW_FLEET", value = "持续跟随指定舰队。使用返航相同的 GO_TO_LOCATION 追赶，靠近后原地等待，目标离开后继续追赶；不使用原生跟随任务，不合并资产。持续返回 RUNNING，必须是计划最后一步，直到玩家停止或改变指令；目标消失时报错。")
    public ExecutionResult follow(Step step, ActionContext context,
            @P(name = "targetFleetId", value = "目标舰队的准确 ID；player 代表玩家舰队，不能跟随自身") String targetFleetId) {
        var target = "player".equals(targetFleetId) ? context.player() : GameWorld.find(context.sector(), targetFleetId);
        if (target == null || target.isExpired() || target.getContainingLocation() == null || target.isEmpty())
            throw new IllegalStateException("跟随目标舰队已消失或不可用：" + targetFleetId);
        if (target == context.fleet()) throw new IllegalArgumentException("分舰队不能跟随自身");
        if (target.getBattle() != null || target.isInHyperspaceTransition()) {
            ActionSupport.hold(context);
            return ActionSupport.result(step, WAITING, "等待目标舰队结束战斗或跃迁后继续跟随");
        }
        if (!ActionSupport.near(context.fleet(), target)) {
            ActionSupport.assign(context.fleet(), FleetAssignment.GO_TO_LOCATION, target, "跟随 " + target.getName());
            return ActionSupport.result(step, RUNNING, "正在追赶 " + target.getName());
        }
        // 与返航使用相同的距离判定；此处只等待，保留双方独立舰队及全部资产。
        ActionSupport.assign(context.fleet(), FleetAssignment.HOLD, context.fleet(), "在目标附近保持跟随");
        return ActionSupport.result(step, RUNNING, "已靠近 " + target.getName() + "，持续跟随中");
    }

    @Override public void stop(ActionContext context) { ActionSupport.hold(context); }
}
