package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.mozhi.fleet.actions.AssetTransaction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 主线程派遣：仅转移用户明确选择的原始舰船、军官和资源。 */
public final class FleetDeployment {
    private FleetDeployment() {}

    private static List<FleetMemberAPI> select(CampaignFleetAPI player, List<String> queries) {
        if (queries == null || queries.isEmpty() || queries.size() > 30) throw new IllegalArgumentException("需指定 1 至 30 艘舰船");
        var members = player.getFleetData().getMembersListCopy();
        var selected = new LinkedHashSet<FleetMemberAPI>();
        for (String query : queries) {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("舰船名称或 ID 不能为空");
            var matches = members.stream().filter(ship -> query.equals(ship.getId())).toList();
            if (matches.isEmpty()) matches = members.stream().filter(ship -> query.equalsIgnoreCase(ship.getShipName())).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("舰船不存在或重名，请使用实例 ID：" + query);
            var ship = matches.get(0);
            if (!selected.add(ship)) throw new IllegalArgumentException("重复选择舰船");
            if (ship == player.getFlagship() || ship.isFlagship() || ship.getCaptain() == player.getCommander())
                throw new IllegalArgumentException("不能派出玩家旗舰");
            if (ship.isMothballed() || ship.isFighterWing() || ship.isStation()) throw new IllegalArgumentException("只能派出可航行舰船");
        }
        if (selected.size() >= members.size()) throw new IllegalArgumentException("玩家必须保留至少一艘舰船");
        return List.copyOf(selected);
    }

    public static Map<String, Object> preview(CampaignFleetAPI player, List<String> queries) {
        var ships = select(player, queries);
        return Map.of("ships", ships.stream().map(ship -> Map.of("id", ship.getId(), "name", ship.getShipName())).toList(),
                "suggestedCrew", (int) Math.ceil(ships.stream().mapToDouble(FleetMemberAPI::getMinCrew).sum()),
                "suggestedSupplies", (int) Math.ceil(ships.stream().mapToDouble(ship -> ship.getHullSpec().getSuppliesPerMonth()).sum()),
                "suggestedFuel", (int) Math.ceil(ships.stream().mapToDouble(FleetMemberAPI::getFuelCapacity).sum() * .8),
                "playerCredits", player.getCargo().getCredits().get(), "note", "仅预览；派遣须明确指定每项资源数量");
    }

    /** target 必须是尚未加入地图的空舰队。异常时连同货物和资金一起回滚。 */
    public static void depart(CampaignFleetAPI player, CampaignFleetAPI target, List<String> queries,
                              float credits, float supplies, float fuel, int crew) {
        for (float amount : new float[]{credits, supplies, fuel, crew})
            if (!Float.isFinite(amount) || amount < 0 || amount > 1_000_000_000) throw new IllegalArgumentException("划拨数量无效");
        if (player.getBattle() != null || player.isInHyperspaceTransition() || player.getContainingLocation() == null)
            throw new IllegalStateException("战斗或跃迁期间不能派遣");
        var ships = select(player, queries);
        var source = player.getCargo();
        var cargo = target.getCargo();
        if (player == target || target.getContainingLocation() != null || !target.getFleetData().getMembersListCopy().isEmpty()
                || !cargo.getStacksCopy().isEmpty() || cargo.getCredits().get() != 0)
            throw new IllegalArgumentException("目标必须是未入场的空舰队");
        if (source.getCredits().get() < credits || source.getSupplies() < supplies || source.getFuel() < fuel || source.getCrew() < crew)
            throw new IllegalArgumentException("玩家资源不足");
        double remainingCrew = player.getFleetData().getMembersListCopy().stream()
                .filter(ship -> !ships.contains(ship) && !ship.isMothballed()).mapToDouble(FleetMemberAPI::getMinCrew).sum();
        if (source.getCrew() - crew < remainingCrew) throw new IllegalArgumentException("划拨后玩家船员不足");
        var location = player.getContainingLocation();
        var flagship = player.getFlagship();
        var transaction = new AssetTransaction();
        try {
            transaction.onRollback(() -> { player.forceSync(); target.forceSync(); });
            transaction.onRollback(() -> { if (flagship != null) player.getFleetData().setFlagship(flagship); });
            for (var ship : ships) {
                var officer = player.getFleetData().getOfficerData(ship.getCaptain());
                if (officer != null) transaction.moveOfficer(player.getFleetData(), target.getFleetData(), officer);
                transaction.moveShip(player.getFleetData(), target.getFleetData(), ship);
            }
            target.forceSync();
            if (crew < target.getFleetData().getMinCrew() || crew > cargo.getMaxPersonnel()
                    || supplies > cargo.getMaxCapacity() || fuel > cargo.getMaxFuel())
                throw new IllegalArgumentException("分舰队资源超出容量或船员不足");
            transfer(transaction, source, cargo, "supplies", supplies);
            transfer(transaction, source, cargo, "fuel", fuel);
            transfer(transaction, source, cargo, "crew", crew);
            transaction.credits(source, source.getCredits().get() - credits);
            transaction.credits(cargo, credits);
            transaction.onRollback(() -> {
                if (target.getContainingLocation() != null) target.getContainingLocation().removeEntity(target);
                if (target.getContainingLocation() != null) throw new IllegalStateException("无法撤销舰队入场");
            });
            location.addEntity(target);
            target.setLocation(player.getLocation().x + 150, player.getLocation().y + 150);
            GameWorld.hold(target);
            target.forceSync(); player.forceSync();
        } catch (RuntimeException failure) { throw transaction.rollback(failure); }
    }

    private static void transfer(AssetTransaction tx, CargoAPI from, CargoAPI to, String id, float amount) {
        if (amount > 0) tx.moveItems(from, to, CargoAPI.CargoItemType.RESOURCES, id, amount);
    }
}
