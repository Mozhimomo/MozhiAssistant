package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.ArrayList;
import java.util.List;

/** 在一次主线程调用内转移原始资产，并在异常时按逆序恢复和核对。 */
public final class AssetTransaction {
    private final List<Runnable> undo = new ArrayList<>();

    public void onRollback(Runnable action) { undo.add(action); }

    public void moveItems(CargoAPI source, CargoAPI target, CargoAPI.CargoItemType type, Object data, float quantity) {
        if (source == target) throw new IllegalStateException("资产源和目标不能相同");
        float from = CargoAmounts.quantity(source, type, data), to = CargoAmounts.quantity(target, type, data);
        if (!Float.isFinite(quantity) || quantity <= 0 || !Float.isFinite(from) || !Float.isFinite(to)
                || from < quantity || to < 0 || !Float.isFinite(to + quantity)) throw new IllegalArgumentException("实际库存或转移数量无效");
        if (Math.abs((double) from - (from - quantity) - quantity) > 0.01
                || Math.abs((double) (to + quantity) - to - quantity) > 0.01) throw new IllegalArgumentException("库存数值精度不足，无法准确转移指定数量");
        onRollback(() -> restoreQuantity(source, type, data, from));
        onRollback(() -> restoreQuantity(target, type, data, to));
        source.removeItems(type, data, quantity);
        target.addItems(type, data, quantity);
        quantityEquals(source, type, data, from - quantity);
        quantityEquals(target, type, data, to + quantity);
    }

    public void credits(CargoAPI cargo, float amount) {
        float before = cargo.getCredits().get();
        if (!Float.isFinite(before) || before < 0 || !Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("信用点余额无效");
        onRollback(() -> setCredits(cargo, before));
        setCredits(cargo, amount);
    }

    public void moveShip(FleetDataAPI source, FleetDataAPI target, FleetMemberAPI ship) {
        if (source == target || !source.getMembersListCopy().contains(ship)
                || target.getMembersListCopy().stream().anyMatch(other -> other.getId().equals(ship.getId()))) {
            throw new IllegalStateException("舰船所属舰队不符或目标已有同 ID 舰船");
        }
        boolean mothballed = ship.isMothballed();
        var captain = ship.getCaptain();
        onRollback(() -> {
            ship.getRepairTracker().setMothballed(mothballed);
            ship.setCaptain(captain);
            if (ship.isMothballed() != mothballed || ship.getCaptain() != captain) throw new IllegalStateException("无法恢复舰船封存状态或舰长");
        });
        onRollback(() -> {
            if (!source.getMembersListCopy().contains(ship)) source.addFleetMember(ship);
            if (!source.getMembersListCopy().contains(ship)) throw new IllegalStateException("无法恢复原舰船归属");
        });
        onRollback(() -> {
            if (target.getMembersListCopy().contains(ship)) target.removeFleetMember(ship);
            if (target.getMembersListCopy().contains(ship)) throw new IllegalStateException("无法撤销舰船转入");
        });
        source.removeFleetMember(ship);
        target.addFleetMember(ship);
        ship.setCaptain(captain);
        ship.getRepairTracker().setMothballed(mothballed);
        if (source.getMembersListCopy().contains(ship) || !target.getMembersListCopy().contains(ship)) {
            throw new IllegalStateException("舰船转移未完成");
        }
        if (ship.isMothballed() != mothballed || ship.getCaptain() != captain) throw new IllegalStateException("舰船状态转移未完成");
    }

    public void moveOfficer(FleetDataAPI source, FleetDataAPI target, OfficerDataAPI officer) {
        if (source == target || target.getOfficerData(officer.getPerson()) != null) throw new IllegalStateException("军官已在目标舰队");
        onRollback(() -> {
            if (source.getOfficerData(officer.getPerson()) == null) source.addOfficer(officer);
            if (source.getOfficerData(officer.getPerson()) == null) throw new IllegalStateException("无法恢复军官");
        });
        onRollback(() -> {
            target.removeOfficer(officer.getPerson());
            if (target.getOfficerData(officer.getPerson()) != null) throw new IllegalStateException("无法撤销军官转入");
        });
        source.removeOfficer(officer.getPerson());
        target.addOfficer(officer);
        if (source.getOfficerData(officer.getPerson()) != null || target.getOfficerData(officer.getPerson()) == null) {
            throw new IllegalStateException("军官转移未完成");
        }
    }

    public RuntimeException rollback(RuntimeException failure) {
        boolean uncertain = false;
        for (int i = undo.size() - 1; i >= 0; i--) {
            try { undo.get(i).run(); }
            catch (RuntimeException error) { uncertain = true; failure.addSuppressed(error); }
        }
        if (uncertain) return new UncertainActionException("资产恢复失败，需要核实实际货物、舰船和资金", failure);
        return new IllegalStateException("动作失败，本次资产变更已恢复：" + failure.getMessage(), failure);
    }

    private static void setCredits(CargoAPI cargo, float amount) {
        cargo.getCredits().set(amount);
        if (Float.compare(cargo.getCredits().get(), amount) != 0) throw new IllegalStateException("信用点结算未完成");
    }

    private static void restoreQuantity(CargoAPI cargo, CargoAPI.CargoItemType type, Object data, float desired) {
        float current = CargoAmounts.quantity(cargo, type, data);
        if (!Float.isFinite(current)) throw new IllegalStateException("无法读取实际库存");
        float delta = desired - current;
        if (delta > 0) cargo.addItems(type, data, delta);
        if (delta < 0) cargo.removeItems(type, data, -delta);
        quantityEquals(cargo, type, data, desired);
    }

    private static void quantityEquals(CargoAPI cargo, CargoAPI.CargoItemType type, Object data, float expected) {
        float actual = CargoAmounts.quantity(cargo, type, data);
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.01f)
            throw new IllegalStateException("货物数量不符：" + type + "/" + data + "，预期 " + expected + "，实际 " + actual);
    }
}
