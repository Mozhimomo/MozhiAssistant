package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.List;

import static com.mozhi.assistant.runtime.tools.ShipDetails.captain;
import static com.mozhi.assistant.runtime.tools.ShipDetails.number;

/** 由工具在主线程调用；每次读取 Global，不缓存舰队状态。 */
final class PlayerFleetDetails {
    private PlayerFleetDetails() { }

    static String read() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return "当前没有已载入的玩家舰队。";
        }
        List<FleetMemberAPI> members = Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        StringBuilder out = new StringBuilder("【当前玩家舰队详情】\n");
        long ships = members.stream().filter(member -> !member.isFighterWing()).count();
        out.append("舰队：").append(fleet.getName())
                .append("；舰船：").append(ships).append("；独立战机联队：").append(members.size() - ships)
                .append("；舰队点数 FP：").append(fleet.getFleetPoints()).append('\n');
        out.append("位置：").append(fleet.getContainingLocation() == null ? "未知" : fleet.getContainingLocation().getName())
                .append("；指挥官：").append(captain(fleet.getCommander())).append('\n');
        CargoAPI cargo = fleet.getCargo();
        if (cargo != null) {
            out.append("星币：").append(number(cargo.getCredits().get()))
                    .append("；补给：").append(number(cargo.getSupplies()))
                    .append("；燃料：").append(number(cargo.getFuel())).append('/').append(number(cargo.getMaxFuel()))
                    .append("；船员：").append(cargo.getCrew()).append("；陆战队员：").append(cargo.getMarines())
                    .append("；货舱占用：").append(number(cargo.getSpaceUsed())).append('/').append(number(cargo.getMaxCapacity())).append('\n');
        }
        for (int i = 0; i < members.size(); i++) {
            out.append("\n【").append(i + 1).append("】").append(ShipDetails.read(members.get(i)));
        }
        return out.toString();
    }
}