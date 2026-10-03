package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.util.*;

/** 原生舰队访问及移动，不包含发展、市场或交战策略。 */
public final class FleetWorld {
    private FleetWorld() {}
    public static CampaignFleetAPI player() { return Global.getSector().getPlayerFleet(); }
    public static CampaignFleetAPI findFleet(String id) {
        if (id == null || id.isBlank()) return null;
        for (LocationAPI location : Global.getSector().getAllLocations())
            for (CampaignFleetAPI fleet : location.getFleets())
                if (id.equals(fleet.getId())) return fleet;
        return null;
    }
    public static boolean near(SectorEntityToken a, SectorEntityToken b) {
        return a.getContainingLocation() == b.getContainingLocation()
                && distance(a,b) < Math.max(350, a.getRadius() + b.getRadius() + 150);
    }
    public static boolean following(CampaignFleetAPI fleet, CampaignFleetAPI player) {
        return fleet.getContainingLocation() == player.getContainingLocation()
                && distance(fleet,player) < Math.max(1200, fleet.getRadius()+player.getRadius()+300);
    }
    public static double distance(SectorEntityToken a, SectorEntityToken b) {
        return Math.hypot(a.getLocation().x-b.getLocation().x,a.getLocation().y-b.getLocation().y);
    }
    static List<FleetMemberAPI> selectShips(CampaignFleetAPI fleet, List<String> queries) {
        if (queries == null || queries.isEmpty()) throw new IllegalArgumentException("必须明确指定舰船名称或实例 ID");
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        Set<FleetMemberAPI> chosen = new LinkedHashSet<>();
        for (String query : queries) {
            var ids = members.stream().filter(m -> m.getId().equals(query)).toList();
            var matches = ids.isEmpty() ? members.stream().filter(m -> m.getShipName().equalsIgnoreCase(query)).toList() : ids;
            if (matches.size() != 1) throw new IllegalArgumentException("舰船不存在或重名，请使用实例 ID：" + query);
            if (!chosen.add(matches.get(0))) throw new IllegalArgumentException("重复指定同一艘舰船");
        }
        return new ArrayList<>(chosen);
    }
    public static void idle(CampaignFleetAPI fleet) {
        assign(fleet, FleetAssignment.HOLD, fleet, "等待墨汁指令");
    }
    public static void follow(CampaignFleetAPI fleet) {
        assign(fleet, FleetAssignment.GO_TO_LOCATION, player(), "跟随玩家舰队");
    }
    public static void returnToPlayer(CampaignFleetAPI fleet) {
        assign(fleet, FleetAssignment.GO_TO_LOCATION, player(), "返回玩家并合并舰队");
    }
    public static void goTo(CampaignFleetAPI fleet, SectorEntityToken target) {
        assign(fleet, FleetAssignment.GO_TO_LOCATION, target, "前往 " + target.getName());
    }
    public static void orbit(CampaignFleetAPI fleet, SectorEntityToken target) {
        assign(fleet, FleetAssignment.ORBIT_PASSIVE, target, "环绕 " + target.getName());
    }
    public static boolean isOrbiting(CampaignFleetAPI fleet, SectorEntityToken target) {
        var current=fleet.getAI()==null?null:fleet.getAI().getCurrentAssignment();
        return fleet.getContainingLocation()==target.getContainingLocation()
                && fleet.getOrbit()!=null && fleet.getOrbit().getFocus()==target
                && current!=null && current.getAssignment()==FleetAssignment.ORBIT_PASSIVE && current.getTarget()==target;
    }
    public static void requireOrbit(CampaignFleetAPI fleet, SectorEntityToken target) {
        if (!isOrbiting(fleet,target))
            throw new IllegalStateException("尚未环绕交易市场 "+target.getName()+"；必须先完成该目的地的 MOVE_TO 步骤");
    }
    /** 等待新计划或读档时保留已有轨道，避免清空交易前置条件。 */
    public static void holdPosition(CampaignFleetAPI fleet) {
        var orbit=fleet.getOrbit();
        if (orbit!=null && orbit.getFocus()!=null) orbit(fleet,orbit.getFocus());
        else idle(fleet);
    }
    private static void assign(CampaignFleetAPI fleet, FleetAssignment assignment, SectorEntityToken target, String text) {
        var current = fleet.getAI() == null ? null : fleet.getAI().getCurrentAssignment();
        if (current != null && current.getAssignment() == assignment && current.getTarget() == target) return;
        fleet.clearAssignments();
        fleet.addAssignment(assignment, target, 100000f, text);
    }
    public static void passive(CampaignFleetAPI fleet) {
        fleet.setNoEngaging(2f);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
        // FOLLOW 也使用战术目标；清空它会关闭 followMode，并可能清除跃迁路径。
        // 这里只限制主动交战，移动目标由原生任务 AI 管理。
    }
    public static Map<String,Object> describe(CampaignFleetAPI fleet) {
        var cargo = fleet.getCargo();
        Map<String,Object> data = new LinkedHashMap<>();
        float daily = fleet.getLogistics().getTotalSuppliesPerDay();
        data.put("credits", cargo.getCredits().get());
        data.put("supplies", cargo.getSupplies());
        data.put("supplyDays", daily > 0 ? cargo.getSupplies()/daily : 0);
        data.put("fuel", cargo.getFuel()); data.put("fuelCapacity", cargo.getMaxFuel());
        data.put("crew", cargo.getCrew()); data.put("requiredCrew", fleet.getFleetData().getMinCrew());
        data.put("readiness", fleet.getFleetData().getMembersListCopy().stream()
                .filter(m -> !m.isMothballed()).mapToDouble(m -> m.getRepairTracker().getCR()).average().orElse(0));
        return data;
    }
}
