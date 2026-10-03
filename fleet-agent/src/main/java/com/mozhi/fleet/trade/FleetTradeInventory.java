package com.mozhi.fleet.trade;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.mozhi.fleet.game.FleetDestinations;
import java.util.*;

/** 仅在交易执行当次读取货物及舰船，保留特殊物品实例数据与舰船原始对象。 */
final class FleetTradeInventory {
    record Entry(SubmarketAPI shop, CargoAPI cargo, FleetDataAPI ships, CargoStackAPI stack,
                 FleetMemberAPI ship, String key, List<String> ids, List<String> names, float available) {
        String label() { return ship != null ? ship.getShipName()+" / "+ship.getHullSpec().getHullName() : stack.getDisplayName(); }
    }
    private FleetTradeInventory() {}

    static List<Entry> read(SubmarketAPI shop, CargoAPI cargo, FleetDataAPI ships) {
        List<Entry> entries = new ArrayList<>();
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            if (stack.getSize() <= 0) continue;
            String id, kind, name = stack.getDisplayName();
            List<String> aliases = new ArrayList<>();
            if (stack.isCommodityStack()) { kind="commodity"; id=stack.getCommodityId(); }
            else if (stack.isWeaponStack()) { kind="weapon"; id=stack.getWeaponSpecIfWeapon().getWeaponId(); }
            else if (stack.isFighterWingStack()) { kind="fighter"; id=stack.getFighterWingSpecIfWing().getId(); }
            else if (stack.isModSpecStack()) { kind="hullmod"; id=stack.getHullModSpecIfHullMod().getId(); }
            else if (stack.isSpecialStack()) {
                kind="special";
                var data=stack.getSpecialDataIfSpecial();
                id=data.getId()+":"+Objects.toString(data.getData(),"");
                aliases.add(data.getId());
            } else continue;
            String key=kind+":"+id;
            aliases.add(id); aliases.add(key);
            entries.add(new Entry(shop,cargo,null,stack,null,key,aliases,List.of(name),stack.getSize()));
        }
        if (ships != null) for (FleetMemberAPI ship : ships.getMembersListCopy()) {
            if (ship.isFighterWing() || ship.isStation()) continue;
            var hull=ship.getHullSpec();
            List<String> ids=new ArrayList<>(List.of(ship.getId(),hull.getHullId(),"ship:"+hull.getHullId()));
            if(hull.getBaseHullId()!=null) ids.add(hull.getBaseHullId());
            List<String> names=new ArrayList<>(List.of(ship.getShipName(),hull.getHullName()));
            if(hull.getHullNameWithDashClass()!=null) names.add(hull.getHullNameWithDashClass());
            entries.add(new Entry(shop,cargo,ships,null,ship,"ship:"+Objects.toString(hull.getBaseHullId(),hull.getHullId()),ids,names,1));
        }
        return entries;
    }

    static List<Entry> matching(List<Entry> entries, String query) {
        List<Entry> byId=entries.stream().filter(e -> e.ids().stream().anyMatch(id -> FleetDestinations.same(query,id))).toList();
        List<Entry> matches=byId.isEmpty()
                ? entries.stream().filter(e -> e.names().stream().anyMatch(name -> FleetDestinations.same(query,name))).toList() : byId;
        Set<String> keys=new LinkedHashSet<>();
        for (Entry match:matches) keys.add(match.key());
        if(keys.size()>1) throw new IllegalArgumentException("物品查询有歧义，请使用具体 ID（特殊物品带实例数据）："+keys);
        if(matches.isEmpty()) throw new IllegalArgumentException("当前实际库存中未找到："+query);
        return matches;
    }
}
