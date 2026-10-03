package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.util.*;

/** 主线程内转移原始成员与货物，不复制舰船或生成资金。 */
public final class FleetTransfer {
    private FleetTransfer() {}
    public static Map<String,Object> preview(List<String> queries) {
        CampaignFleetAPI player=FleetWorld.player();
        var ships=FleetWorld.selectShips(player,queries);
        validateShips(player,ships);
        double crew=ships.stream().mapToDouble(FleetMemberAPI::getMinCrew).sum();
        double supplies=ships.stream().mapToDouble(m->m.getHullSpec().getSuppliesPerMonth()).sum();
        double fuel=ships.stream().mapToDouble(FleetMemberAPI::getFuelCapacity).sum()*0.8;
        return Map.of("ships",ships.stream().map(m->Map.of("id",m.getId(),"name",m.getShipName())).toList(),
                "suggestedCrew",(int)Math.ceil(crew),"suggestedSupplies",(int)Math.ceil(supplies),
                "suggestedFuel",(int)Math.ceil(fuel),"playerCredits",player.getCargo().getCredits().get(),
                "note","仅预览，未转移；请明确划拨资金及资源数量，实际出发会重新检查容量和玩家剩余船员");
    }
    public static CampaignFleetAPI depart(List<String> queries,float credits,float supplies,float fuel,int crew) {
        checkAmount(credits); checkAmount(supplies); checkAmount(fuel);
        if(crew<0) throw new IllegalArgumentException("船员不可为负数");
        CampaignFleetAPI player=FleetWorld.player();
        if(player.getBattle()!=null || player.isInHyperspaceTransition()) throw new IllegalStateException("当前不能分舰队");
        var ships=FleetWorld.selectShips(player,queries);
        validateShips(player,ships);
        CargoAPI source=player.getCargo();
        if(source.getCredits().get()<credits || source.getSupplies()<supplies || source.getFuel()<fuel || source.getCrew()<crew)
            throw new IllegalArgumentException("玩家资金、补给、燃料或船员不足");
        float remainingCrew=(float)player.getFleetData().getMembersListCopy().stream()
                .filter(m->!ships.contains(m) && !m.isMothballed()).mapToDouble(FleetMemberAPI::getMinCrew).sum();
        if(source.getCrew()-crew<remainingCrew) throw new IllegalArgumentException("划拨后玩家舰队船员不足");
        CampaignFleetAPI fleet=FleetFactoryV3.createEmptyFleet(Factions.PLAYER, FleetTypes.MERC_SCOUT,null);
        fleet.setName("墨汁远征舰队");
        fleet.setNoFactionInName(true);
        fleet.setNoAutoDespawn(true);
        fleet.setAIMode(false);
        fleet.setCommander(Global.getFactory().createPerson());
        fleet.getCommander().setFaction(Factions.PLAYER);
        fleet.getCommander().getName().setFirst("墨汁");
        fleet.getCommander().setPortraitSprite("graphics/portraits/SOD_portrait_mozhi.png");
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_BUSY,true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED,true);
        fleet.getMemoryWithoutUpdate().set("$mozhiExpedition",true);
        var moved=new ArrayList<FleetMemberAPI>();
        var officers=new ArrayList<com.fs.starfarer.api.characters.OfficerDataAPI>();
        try {
            for(var ship:ships) {
                var officer=player.getFleetData().getOfficerData(ship.getCaptain());
                if(officer!=null && !officers.contains(officer)) {
                    officers.add(officer); player.getFleetData().removeOfficer(officer.getPerson());
                    fleet.getFleetData().addOfficer(officer);
                }
                player.getFleetData().removeFleetMember(ship);
                fleet.getFleetData().addFleetMember(ship); moved.add(ship);
            }
            fleet.forceSync(); player.forceSync();
            if(crew<fleet.getFleetData().getMinCrew() || crew>fleet.getCargo().getMaxPersonnel()
                    || supplies>fleet.getCargo().getMaxCapacity() || fuel>fleet.getCargo().getMaxFuel())
                throw new IllegalArgumentException("划拨资源超出分舰队容量，或船员不足；未出发");
            player.getContainingLocation().addEntity(fleet);
            fleet.setLocation(player.getLocation().x+150,player.getLocation().y+150);
        } catch(RuntimeException error) {
            for(var ship:moved) { fleet.getFleetData().removeFleetMember(ship); player.getFleetData().addFleetMember(ship); }
            for(var officer:officers) { fleet.getFleetData().removeOfficer(officer.getPerson()); player.getFleetData().addOfficer(officer); }
            if(fleet.getContainingLocation()!=null) fleet.getContainingLocation().removeEntity(fleet);
            player.forceSync();
            throw error;
        }
        source.getCredits().subtract(credits); fleet.getCargo().getCredits().add(credits);
        source.removeSupplies(supplies); fleet.getCargo().addSupplies(supplies);
        source.removeFuel(fuel); fleet.getCargo().addFuel(fuel);
        source.removeCrew(crew); fleet.getCargo().addCrew(crew);
        fleet.forceSync(); player.forceSync();
        FleetWorld.idle(fleet);
        return fleet;
    }
    private static void validateShips(CampaignFleetAPI player,List<FleetMemberAPI> ships) {
        if(ships.size()>=player.getFleetData().getMembersListCopy().size()) throw new IllegalArgumentException("玩家必须至少保留一艘舰船");
        for(var ship:ships) {
            if(ship.isFlagship() || ship.getCaptain()==player.getCommander()) throw new IllegalArgumentException("不能派出玩家旗舰，请先更换旗舰");
            if(ship.isMothballed() || ship.isFighterWing() || ship.isStation()) throw new IllegalArgumentException("只能派出可航行的非封存舰船");
        }
    }
    public static void merge(CampaignFleetAPI fleet) {
        CampaignFleetAPI player=FleetWorld.player();
        if(!FleetWorld.near(fleet,player) || fleet.getBattle()!=null || player.getBattle()!=null
                || fleet.isInHyperspaceTransition() || player.isInHyperspaceTransition())
            throw new IllegalStateException("须靠近玩家、结束战斗与跃迁后合并");
        for(var ship:fleet.getFleetData().getMembersListCopy()) {
            fleet.getFleetData().removeFleetMember(ship); player.getFleetData().addFleetMember(ship);
        }
        for(var officer:fleet.getFleetData().getOfficersCopy()) {
            fleet.getFleetData().removeOfficer(officer.getPerson()); player.getFleetData().addOfficer(officer);
        }
        CargoAPI from=fleet.getCargo(),to=player.getCargo();
        // addAll 的信用点行为不作为假设；显式转移，避免重复入账。
        float credits=from.getCredits().get(); from.getCredits().set(0);
        to.addAll(from,true); from.clear(); to.getCredits().add(credits);
        player.forceSync();
        fleet.clearAssignments();
        if(fleet.getContainingLocation()!=null) fleet.getContainingLocation().removeEntity(fleet);
        fleet.setExpired(true);
    }
    public static void checkAmount(float value) {
        if(!Float.isFinite(value) || value<0 || value>1_000_000_000) throw new IllegalArgumentException("资源金额无效");
    }
}
