package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.util.Misc;
import java.util.ArrayList;
import java.util.Locale;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/** 一次性原生跟随测试，不经过舰队 agent、计划或任务包装器。 */
public final class MozhiFleetFollowCommand implements BaseCommand {
    private static final float DEFAULT_RADIUS = 3000f;

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign() || context.isInCombat()
                || Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            Console.showMessage("请在载入存档后的战役地图中使用 MozhiFleetFollow。");
            return CommandResult.WRONG_CONTEXT;
        }
        float radius = DEFAULT_RADIUS;
        String argument = args == null ? "" : args.trim();
        if (!argument.isEmpty()) {
            try { radius = Float.parseFloat(argument); }
            catch (NumberFormatException error) { return CommandResult.BAD_SYNTAX; }
            if (!Float.isFinite(radius) || radius <= 0) {
                Console.showMessage("半径必须是大于 0 的有限数值，单位为战役地图距离。");
                return CommandResult.BAD_SYNTAX;
            }
        }
        CampaignFleetAPI target = Global.getSector().getPlayerFleet();
        FleetAssignment assignment = FleetAssignment.FOLLOW;
        String text = "跟随玩家（原生指令测试）";
        int assigned = 0, skipped = 0, failed = 0;
        for (CampaignFleetAPI fleet : new ArrayList<>(target.getContainingLocation().getFleets())) {
            if (fleet == target || fleet.isPlayerFleet() || fleet.isExpired() || fleet.isEmpty()) continue;
            float distance = Misc.getDistance(fleet, target);
            if (distance > radius) continue;
            if (fleet.isStationMode() || fleet.getBattle() != null || fleet.isInHyperspaceTransition()) {
                skipped++;
                Console.showMessage("跳过 " + fleet.getName() + " [" + fleet.getId() + "]：空间站、战斗中或跃迁中。");
                continue;
            }
            try {
                fleet.clearAssignments();
                fleet.addAssignment(assignment, target, 100000f, text);
                assigned++;
                var current = fleet.getAI() == null ? null : fleet.getAI().getCurrentAssignment();
                String actual = current == null ? "未读取到当前任务"
                        : current.getAssignment() + " / target=" + (current.getTarget() == target ? "玩家" : "非玩家");
                Console.showMessage(String.format(Locale.ROOT,"%s [%s] 距离 %.0f：%s",
                        fleet.getName(),fleet.getId(),distance,actual));
            } catch (RuntimeException error) {
                failed++;
                Global.getLogger(MozhiFleetFollowCommand.class).error("Native follow command failed for " + fleet.getId(),error);
                Console.showMessage(fleet.getName() + "：下达失败，" + error.getClass().getSimpleName() + "，请查看日志。");
            }
        }
        Console.showMessage(String.format(Locale.ROOT,"[MozhiFleetFollow] 半径 %.0f：已下达 %d，跳过 %d，失败 %d。",
                radius,assigned,skipped,failed));
        Console.showMessage("关闭控制台并解除暂停后观察。此指令只下达一次；舰队原生 AI、其他脚本或现有墨汁计划仍可能覆盖任务。");
        return failed > 0 && assigned == 0 ? CommandResult.ERROR : CommandResult.SUCCESS;
    }
}
