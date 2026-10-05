package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import java.util.LinkedHashSet;
import com.fs.starfarer.api.campaign.CargoAPI;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 驶向玩家，双方结束战斗/跃迁且距离足够近后实际合并全部资产。 */
public final class ReturnToPlayerAction implements Action {
    @dev.langchain4j.agent.tool.Tool(name = "RETURN", value = "返回玩家舰队，靠近且双方结束战斗或跃迁后合并剩余资产并移除分舰队。必须是计划最后一步，由 Agent 按玩家授权调用。")
    public ExecutionResult returnToPlayer(Step step, ActionContext context) {
        var fleet = context.fleet();
        var player = context.player();
        if (player.isExpired() || player.getContainingLocation() == null) throw new IllegalStateException("玩家舰队不可用");
        if (player.getBattle() != null || player.isInHyperspaceTransition()) {
            return ActionSupport.result(step, WAITING, "等待玩家结束战斗或跃迁后回归");
        }
        if (!ActionSupport.near(fleet, player)) {
            ActionSupport.assign(fleet, FleetAssignment.GO_TO_LOCATION, player, "回归玩家舰队");
            return ActionSupport.result(step, RUNNING, "正在返回玩家，尚未合并");
        }
        var source = fleet.getCargo();
        var destination = player.getCargo();
        float credits = source.getCredits().get();
        var location = fleet.getContainingLocation();
        var playerFlagship = player.getFlagship();
        var officers = fleet.getFleetData().getOfficersCopy();
        for (var ship : fleet.getFleetData().getMembersListCopy()) moveShip(fleet.getFleetData(), player.getFleetData(), ship);
        var storedShips = source.getMothballedShips();
        if (storedShips != null && storedShips != fleet.getFleetData()) {
            for (var ship : storedShips.getMembersListCopy()) moveShip(storedShips, player.getFleetData(), ship);
        }
        for (var officer : officers) {
            fleet.getFleetData().removeOfficer(officer.getPerson());
            player.getFleetData().addOfficer(officer);
        }
        record Item(CargoAPI.CargoItemType type, Object data) {}
        var items = new LinkedHashSet<Item>();
        for (var stack : source.getStacksCopy()) items.add(new Item(stack.getType(), stack.getData()));
        var commodities = context.settings().getAllCommoditySpecs();
        if (commodities != null) for (var commodity : commodities)
            items.add(new Item(CargoAPI.CargoItemType.RESOURCES, commodity.getId()));
        // 直接合并（包含商品小数余额），不做数量复核或事务回滚。
        for (var item : items) {
            float quantity = CargoAmounts.quantity(source, item.type(), item.data());
            if (quantity > 0) {
                source.removeItems(item.type(), item.data(), quantity);
                destination.addItems(item.type(), item.data(), quantity);
            }
        }
        source.getCredits().set(0);
        destination.getCredits().add(credits);
        player.forceSync();
        if (playerFlagship != null) player.getFleetData().setFlagship(playerFlagship);
        fleet.clearAssignments();
        location.removeEntity(fleet);
        fleet.setExpired(true);
        return ActionSupport.result(step, SUCCEEDED, "已回归玩家，实际合并剩余舰船、军官、货物和 " + credits + " 星币");
    }

    private static void moveShip(com.fs.starfarer.api.campaign.FleetDataAPI source,
                                 com.fs.starfarer.api.campaign.FleetDataAPI destination,
                                 com.fs.starfarer.api.fleet.FleetMemberAPI ship) {
        var captain = ship.getCaptain();
        boolean mothballed = ship.isMothballed();
        source.removeFleetMember(ship);
        destination.addFleetMember(ship);
        ship.setCaptain(captain);
        ship.getRepairTracker().setMothballed(mothballed);
    }

    @Override public void stop(ActionContext context) { ActionSupport.hold(context); }
}
