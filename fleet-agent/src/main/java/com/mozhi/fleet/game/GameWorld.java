package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.FleetResources;
import com.mozhi.fleet.model.ResourceCheck;
import com.mozhi.fleet.execution.Monitor;
import com.mozhi.fleet.actions.ActionContext;
import com.fs.starfarer.api.Global;
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

    /** 对话命令接受名称，进入计划后固定为 ID，避免重名舰队或名称变化导致换目标。 */
    public static CampaignFleetAPI followTarget(SectorAPI sector, String query) {
        if (query.equals("player") || query.equals("玩家") || query.equals("玩家舰队")) {
            var player = sector.getPlayerFleet();
            if (player == null || player.isExpired()) throw new IllegalArgumentException("玩家舰队不可用");
            return player;
        }
        var exact = find(sector, query);
        if (exact != null && !exact.isExpired()) return exact;
        var matches = new LinkedHashSet<CampaignFleetAPI>();
        for (var location : sector.getAllLocations()) for (var fleet : location.getFleets())
            if (!fleet.isExpired() && query.equalsIgnoreCase(fleet.getName())) matches.add(fleet);
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "未找到目标舰队：" + query
                : "目标舰队重名，请指定 ID：" + matches.stream().map(fleet -> fleet.getName() + " [" + fleet.getId() + "]").toList());
        return matches.iterator().next();
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

    public static FleetResources resources(CampaignFleetAPI fleet) {
        var cargo = fleet.getCargo(); var logistics = fleet.getLogistics();
        return new FleetResources(cargo.getFuel(), cargo.getMaxFuel(), logistics.getFuelCostPerLightYear(),
                cargo.getSupplies(), logistics.getTotalSuppliesPerDay(), cargo.getCrew(), fleet.getFleetData().getMinCrew());
    }

    public static Map<String, Object> describe(CampaignFleetAPI fleet) {
        var cargo = fleet.getCargo();
        var data = new LinkedHashMap<String, Object>();
        float daily = fleet.getLogistics().getTotalSuppliesPerDay();
        data.put("credits", cargo.getCredits().get()); data.put("supplies", cargo.getSupplies());
        data.put("supplyDays", daily > 0 ? cargo.getSupplies() / daily : 0);
        data.put("fuel", cargo.getFuel()); data.put("fuelCapacity", cargo.getMaxFuel());
        float fuelPerLy = fleet.getLogistics().getFuelCostPerLightYear();
        data.put("fuelPerLightYear", fuelPerLy); data.put("suppliesPerDay", daily);
        data.put("fuelRangeLy", fuelPerLy > 0 ? cargo.getFuel() / fuelPerLy : null);
        data.put("cargoSpaceLeft", cargo.getSpaceLeft()); data.put("crewSpaceLeft", cargo.getFreeCrewSpace());
        data.put("crew", cargo.getCrew()); data.put("requiredCrew", fleet.getFleetData().getMinCrew());
        data.put("readiness", fleet.getFleetData().getMembersListCopy().stream()
                .filter(ship -> !ship.isMothballed()).mapToDouble(ship -> ship.getRepairTracker().getCR()).average().orElse(0));
        return data;
    }

    public static Map<String, Object> item(CargoStackAPI stack) {
        var data = new LinkedHashMap<String, Object>();
        data.put("name", stack.getDisplayName()); data.put("quantity", stack.getSize());
        data.put("wholeQuantity", Float.isFinite(stack.getSize()) ? (long) Math.max(0, Math.floor(stack.getSize())) : 0);
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
        return observations(sector, fleet, goal, plan, new Monitor().checkResources(resources(fleet)));
    }

    public static Map<String, Object> observations(SectorAPI sector, CampaignFleetAPI fleet, String goal, Plan plan, ResourceCheck resources) {
        if (resources.status() == ResourceCheck.Status.READY && "回归玩家舰队".equals(goal) && plan != null && plan.steps().size() == 1 && "RETURN".equals(plan.steps().get(0).action()))
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
            boolean relevant = query.contains(market.getId().toLowerCase(Locale.ROOT))
                    || query.contains(market.getName().toLowerCase(Locale.ROOT)) || query.contains(entity.getId().toLowerCase(Locale.ROOT))
                    || mentioned(query, entity.getName())
                    || fleet.getOrbit() != null && fleet.getOrbit().getFocus() == entity;
            if (!relevant) continue;
            var row = new LinkedHashMap<String, Object>();
            row.put("marketId", market.getId()); row.put("name", market.getName()); row.put("destinationId", entity.getId());
            row.put("location", entity.getContainingLocation().getName()); row.put("locationId", entity.getContainingLocation().getId());
            final String inventoryQuery = query;
            List<Map<String, Object>> shops = new ArrayList<>();
            for (var shop : market.getSubmarketsCopy()) {
                if (shop.getPlugin() == null || shop.getPlugin().isHidden() || shop.getPlugin().isFreeTransfer()) continue;
                var shopData = new LinkedHashMap<String, Object>();
                shopData.put("submarketId", shop.getSpecId()); shopData.put("name", shop.getNameOneLine());
                if (relevant) {
                    try {
                        shop.getPlugin().updateCargoPrePlayerInteraction();
                        var goods = shop.getCargo().getStacksCopy().stream().filter(stack -> stack.getSize() > 0).map(GameWorld::item)
                                .filter(item -> mentioned(inventoryQuery, item.get("itemId")) || mentioned(inventoryQuery, item.get("name")))
                                .sorted(Comparator.comparing(GameWorld::itemSortKey)).toList();
                        shopData.put("items", goods);
                        var ships = shop.getCargo().getMothballedShips();
                        if (ships != null) shopData.put("ships", ships.getMembersListCopy().stream()
                                .filter(ship -> mentioned(inventoryQuery, ship.getId()) || mentioned(inventoryQuery, ship.getShipName()) || mentioned(inventoryQuery, ship.getHullId())
                                        || ship.getHullSpec() != null && mentioned(inventoryQuery, ship.getHullSpec().getHullName()))
                                .sorted(Comparator.comparing(ship -> ship.getId())).map(ship -> Map.of(
                                "itemType", "SHIP", "itemId", ship.getId(), "name", ship.getShipName(), "hull", ship.getHullId(),
                                "hullName", ship.getHullSpec() == null ? "" : ship.getHullSpec().getHullName())).toList());
                    } catch (RuntimeException error) { shopData.put("inventoryError", Objects.toString(error.getMessage(), "库存读取失败")); }
                }
                shops.add(shopData);
            }
            shops.sort(Comparator.comparing(shop -> shop.get("submarketId").toString()));
            row.put("submarkets", shops); catalog.add(row);
        }
        catalog.sort(Comparator.comparing(market -> market.get("marketId").toString()));
        destinations.sort(Comparator.comparing(destination -> destination.get("destinationId").toString()));
        final String destinationQuery = query;
        var player = sector.getPlayerFleet();
        var controlled = fleet(fleet);
        controlled.put("cargo", fleet.getCargo().getStacksCopy().stream().filter(stack -> stack.getSize() > 0).map(GameWorld::item)
                .filter(item -> "COMMODITY".equals(item.get("itemType")) || mentioned(destinationQuery, item.get("itemId")) || mentioned(destinationQuery, item.get("name")))
                .sorted(Comparator.comparing(GameWorld::itemSortKey)).toList());
        controlled.put("cargoScope", "全部经济商品及目标相关物品，其他装备未列出；不代表没有持有");
        List<Map<String, Object>> followTargets = new ArrayList<>();
        if (query.contains("跟随") || query.contains("follow") || plan != null && plan.steps().stream().anyMatch(step -> step.tool().equals("FOLLOW_FLEET"))) {
            for (var location : sector.getAllLocations()) for (var target : location.getFleets()) {
                if (target == fleet || target.isExpired() || target.getContainingLocation() == null) continue;
                if (target != player && !mentioned(query, target.getId()) && !mentioned(query, target.getName())) continue;
                followTargets.add(Map.of("targetFleetId", target == player ? "player" : target.getId(), "name", target.getName(),
                        "location", target.getContainingLocation().getName(), "x", target.getLocation().x, "y", target.getLocation().y,
                        "inBattle", target.getBattle() != null, "inTransition", target.isInHyperspaceTransition()));
            }
        }
        return Map.of("controlledFleet", controlled, "playerLocation", Map.of("locationId", player.getContainingLocation().getId(), "x", player.getLocation().x, "y", player.getLocation().y), "markets", catalog, "matchingDestinations", destinations,
                "followTargets", followTargets,
                "resourceMarkets", resources.status() == ResourceCheck.Status.ADVISORY
                        ? ResourceMarkets.collect(new ActionContext(sector, fleet, Global.getSettings(), Global.getFactory()), resources) : Map.of(),
                "systems", sector.getStarSystems().stream().filter(system -> mentioned(destinationQuery, system.getId()) || mentioned(destinationQuery, system.getName()))
                        .sorted(Comparator.comparing(system -> system.getId())).map(system -> Map.of("destinationId", system.getId(), "name", system.getName())).toList(),
                "inventoryScope", "仅目标、剩余计划及已环绕市场；库存仅目标或剩余计划提及的商品/舰船。省略不代表无货；自主跑商全市场搜索由 CALCULATE_TRADE_ROUTE 完成，资源采购看 resourceMarkets");
    }

    private static boolean mentioned(String query, Object value) {
        return value != null && !value.toString().isBlank() && query.contains(value.toString().toLowerCase(Locale.ROOT));
    }
    private static String itemSortKey(Map<String, Object> item) {
        return item.get("itemType") + ":" + item.get("itemId") + ":" + item.getOrDefault("itemData", "");
    }
}
