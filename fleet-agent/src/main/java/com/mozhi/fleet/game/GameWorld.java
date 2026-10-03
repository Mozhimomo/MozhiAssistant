package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.mozhi.fleet.model.Plan;
import java.util.*;

/** 游戏对象只在主线程读取；提供给 Planner 的内容全部是文本可序列化数据。 */
public final class GameWorld {
    private GameWorld() {}

    public static CampaignFleetAPI find(SectorAPI sector, String id) {
        if (id == null || id.isBlank()) return null;
        for (var location : sector.getAllLocations())
            for (var fleet : location.getFleets()) if (id.equals(fleet.getId())) return fleet;
        return null;
    }

    public static void passive(CampaignFleetAPI fleet) {
        fleet.setAIMode(false);
        fleet.setNoEngaging(2f);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_BUSY, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
    }

    public static void hold(CampaignFleetAPI fleet) {
        if (fleet.isExpired() || fleet.getBattle() != null || fleet.isInHyperspaceTransition()) return;
        var focus = fleet.getOrbit() == null ? null : fleet.getOrbit().getFocus();
        FleetAssignment assignment = focus == null ? FleetAssignment.HOLD : FleetAssignment.ORBIT_PASSIVE;
        var target = focus == null ? fleet : focus;
        var current = fleet.getAI() == null ? null : fleet.getAI().getCurrentAssignment();
        if (current != null && current.getAssignment() == assignment && current.getTarget() == target) return;
        fleet.clearAssignments();
        fleet.addAssignment(assignment, target, 100000f, "等待墨汁指令");
    }

    public static Set<MarketAPI> markets(SectorAPI sector) {
        Set<MarketAPI> markets = new LinkedHashSet<>(sector.getEconomy().getMarketsCopy());
        for (var location : sector.getAllLocations()) for (var entity : location.getAllEntities())
            if (entity.getMarket() != null) markets.add(entity.getMarket());
        markets.removeIf(MarketAPI::isPlanetConditionMarketOnly);
        return markets;
    }

    public static SectorEntityToken destination(SectorAPI sector, String query) {
        var exact = sector.getEntityById(query);
        if (exact != null && !exact.isExpired()) return exact;
        var ids = new LinkedHashSet<SectorEntityToken>();
        var names = new LinkedHashSet<SectorEntityToken>();
        for (var market : markets(sector)) {
            var entity = market.getPrimaryEntity();
            if (entity == null || entity.isExpired()) continue;
            if (query.equals(market.getId())) ids.add(entity);
            if (query.equalsIgnoreCase(market.getName())) names.add(entity);
        }
        for (var system : sector.getStarSystems()) {
            var center = system.getCenter() == null ? system.getStar() : system.getCenter();
            if (center == null) continue;
            if (query.equals(system.getId())) ids.add(center);
            if (query.equalsIgnoreCase(system.getName())) names.add(center);
        }
        for (var location : sector.getAllLocations()) for (var entity : location.getAllEntities())
            if (!entity.isExpired() && query.equalsIgnoreCase(entity.getName())) names.add(entity);
        var matches = ids.isEmpty() ? names : ids;
        if (matches.size() != 1) throw new IllegalArgumentException("目的地不存在或重名，请使用 ID：" + query);
        return matches.iterator().next();
    }

    public static Map<String, Object> describe(CampaignFleetAPI fleet) {
        var cargo = fleet.getCargo();
        var data = new LinkedHashMap<String, Object>();
        float daily = fleet.getLogistics().getTotalSuppliesPerDay();
        data.put("credits", cargo.getCredits().get()); data.put("supplies", cargo.getSupplies());
        data.put("supplyDays", daily > 0 ? cargo.getSupplies() / daily : 0);
        data.put("fuel", cargo.getFuel()); data.put("fuelCapacity", cargo.getMaxFuel());
        data.put("crew", cargo.getCrew()); data.put("requiredCrew", fleet.getFleetData().getMinCrew());
        data.put("readiness", fleet.getFleetData().getMembersListCopy().stream()
                .filter(ship -> !ship.isMothballed()).mapToDouble(ship -> ship.getRepairTracker().getCR()).average().orElse(0));
        return data;
    }

    public static Map<String, Object> item(CargoStackAPI stack) {
        var data = new LinkedHashMap<String, Object>();
        data.put("name", stack.getDisplayName()); data.put("quantity", stack.getSize());
        if (stack.isCommodityStack()) { data.put("itemType", "COMMODITY"); data.put("itemId", stack.getCommodityId()); }
        else if (stack.isWeaponStack()) { data.put("itemType", "WEAPON"); data.put("itemId", stack.getWeaponSpecIfWeapon().getWeaponId()); }
        else if (stack.isFighterWingStack()) { data.put("itemType", "FIGHTER"); data.put("itemId", stack.getFighterWingSpecIfWing().getId()); }
        else if (stack.isModSpecStack()) { data.put("itemType", "HULLMOD"); data.put("itemId", stack.getHullModSpecIfHullMod().getId()); }
        else if (stack.isSpecialStack()) {
            data.put("itemType", "SPECIAL"); data.put("itemId", stack.getSpecialDataIfSpecial().getId());
            data.put("itemData", Objects.toString(stack.getSpecialDataIfSpecial().getData(), ""));
        }
        return data;
    }

    public static Map<String, Object> fleet(CampaignFleetAPI fleet) {
        var result = new LinkedHashMap<String, Object>();
        result.put("fleetId", fleet.getId()); result.put("location", fleet.getContainingLocation().getName());
        result.put("locationId", fleet.getContainingLocation().getId());
        result.put("x", fleet.getLocation().x); result.put("y", fleet.getLocation().y);
        result.put("logistics", describe(fleet));
        result.put("ships", fleet.getFleetData().getMembersListCopy().stream().map(ship -> Map.of(
                "id", ship.getId(), "name", ship.getShipName(), "hull", ship.getHullId(), "itemType", "SHIP", "itemId", ship.getId())).toList());
        result.put("cargo", fleet.getCargo().getStacksCopy().stream().filter(stack -> stack.getSize() > 0).map(GameWorld::item).toList());
        var assignment = fleet.getAI() == null ? null : fleet.getAI().getCurrentAssignment();
        result.put("assignment", assignment == null ? "NONE" : assignment.getAssignment().name());
        result.put("assignmentTarget", assignment == null || assignment.getTarget() == null ? "" : assignment.getTarget().getId());
        result.put("orbitTarget", fleet.getOrbit() == null || fleet.getOrbit().getFocus() == null ? "" : fleet.getOrbit().getFocus().getId());
        return result;
    }

    public static Map<String, Object> observations(SectorAPI sector, CampaignFleetAPI fleet, String goal, Plan plan) {
        if ("回归玩家舰队".equals(goal) && plan != null && plan.steps().size() == 1 && "RETURN".equals(plan.steps().get(0).action()))
            return Map.of("controlledFleet", fleet(fleet), "playerFleet", fleet(sector.getPlayerFleet()));
        String query = goal.toLowerCase(Locale.ROOT);
        if (plan != null) query += " " + plan.steps().stream().map(step -> step.parameters().toString()).toList();
        List<Map<String, Object>> destinations = new ArrayList<>();
        for (var location : sector.getAllLocations()) for (var entity : location.getAllEntities()) {
            if (entity.isExpired() || entity.getId() == null || entity.getName() == null || entity.getName().isBlank()) continue;
            if (query.contains(entity.getId().toLowerCase(Locale.ROOT)) || query.contains(entity.getName().toLowerCase(Locale.ROOT)))
                destinations.add(Map.of("destinationId", entity.getId(), "name", entity.getName(), "location", location.getName()));
        }
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (var market : markets(sector)) {
            var entity = market.getPrimaryEntity();
            if (entity == null || entity.isExpired()) continue;
            var row = new LinkedHashMap<String, Object>();
            row.put("marketId", market.getId()); row.put("name", market.getName()); row.put("destinationId", entity.getId());
            row.put("location", entity.getContainingLocation().getName()); row.put("locationId", entity.getContainingLocation().getId());
            boolean relevant = query.contains(market.getId().toLowerCase(Locale.ROOT))
                    || query.contains(market.getName().toLowerCase(Locale.ROOT)) || query.contains(entity.getId().toLowerCase(Locale.ROOT))
                    || fleet.getOrbit() != null && fleet.getOrbit().getFocus() == entity;
            List<Map<String, Object>> shops = new ArrayList<>();
            for (var shop : market.getSubmarketsCopy()) {
                if (shop.getPlugin() == null || shop.getPlugin().isHidden() || shop.getPlugin().isFreeTransfer()) continue;
                var shopData = new LinkedHashMap<String, Object>();
                shopData.put("submarketId", shop.getSpecId()); shopData.put("name", shop.getNameOneLine());
                if (relevant) {
                    try {
                        shop.getPlugin().updateCargoPrePlayerInteraction();
                        var goods = shop.getCargo().getStacksCopy().stream().filter(stack -> stack.getSize() > 0).map(GameWorld::item).toList();
                        shopData.put("items", goods);
                        var ships = shop.getCargo().getMothballedShips();
                        if (ships != null) shopData.put("ships", ships.getMembersListCopy().stream().map(ship -> Map.of(
                                "itemType", "SHIP", "itemId", ship.getId(), "name", ship.getShipName(), "hull", ship.getHullId())).toList());
                    } catch (RuntimeException error) { shopData.put("inventoryError", Objects.toString(error.getMessage(), "库存读取失败")); }
                }
                shops.add(shopData);
            }
            row.put("submarkets", shops); catalog.add(row);
        }
        return Map.of("controlledFleet", fleet(fleet), "playerFleet", fleet(sector.getPlayerFleet()), "markets", catalog, "matchingDestinations", destinations,
                "systems", sector.getStarSystems().stream().map(system -> Map.of("destinationId", system.getId(), "name", system.getName())).toList(),
                "inventoryScope", "指定目标市场、当前计划中的市场和已环绕市场包含库存；其他市场仅列 ID，缺少信息时不要编造");
    }
}
