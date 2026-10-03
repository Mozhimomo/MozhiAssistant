package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.Global;
import com.mozhi.fleet.model.FleetPlanStep;
import java.text.Normalizer;
import java.util.*;

/** 主线程解析全星区目的地；只固定实体 ID，始终重新取得实际游戏对象。 */
public final class FleetDestinations {
    public record Destination(SectorEntityToken entity, MarketAPI market, boolean system) {}
    private FleetDestinations() {}

    static List<MarketAPI> markets() {
        Set<MarketAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MarketAPI> result = new ArrayList<>();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy())
            if (market != null && seen.add(market)) result.add(market);
        for (var location : Global.getSector().getAllLocations())
            for (var entity : location.getAllEntities()) {
                MarketAPI market = entity.getMarket();
                if (market != null && seen.add(market)) result.add(market);
            }
        return result;
    }

    public static Destination resolve(String query, boolean marketRequired) {
        Map<String,Destination> ids = new LinkedHashMap<>(), names = new LinkedHashMap<>();
        for (MarketAPI market : markets()) {
            if (marketRequired && market.isPlanetConditionMarketOnly()) continue;
            SectorEntityToken entity = target(market);
            if (entity == null) continue;
            Destination destination = new Destination(entity, market, false);
            match(ids, names, query, market.getId(), market.getName(), destination);
            match(ids, names, query, entity.getId(), entity.getName(), destination);
            if (market.getConnectedEntities() != null)
                for (var connected : market.getConnectedEntities())
                    match(ids, names, query, connected.getId(), connected.getName(), destination);
        }
        if (!marketRequired) for (var system : Global.getSector().getStarSystems()) {
            SectorEntityToken center = system.getCenter() == null ? system.getStar() : system.getCenter();
            if (center != null) {
                Destination destination = new Destination(center, null, true);
                match(ids, names, query, system.getId(), system.getName(), destination);
                if (same(query, system.getBaseName())) names.put(center.getId(), destination);
            }
            for (var planet : system.getPlanets()) {
                if (planet.isStar()) continue;
                MarketAPI market=planet.getMarket();
                SectorEntityToken entity=market!=null && !market.isPlanetConditionMarketOnly()?target(market):planet;
                match(ids, names, query, planet.getId(), planet.getName(), new Destination(entity==null?planet:entity, market, false));
            }
        }
        var candidates = new ArrayList<>((ids.isEmpty() ? names : ids).values());
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(candidates.isEmpty()
                    ? "未找到" + (marketRequired ? "可交易市场" : "星球、星系或市场") + "：" + query
                    : "目的地重名，请使用 ID：" + candidates.stream().map(d -> d.entity().getName()+" ["+d.entity().getId()+"]").toList());
        }
        return candidates.get(0);
    }

    public static Destination forStep(FleetPlanStep step) {
        boolean trade = step.action == FleetPlanStep.Action.BUY || step.action == FleetPlanStep.Action.SELL;
        if (step.targetId == null || step.targetId.isBlank()) {
            Destination result = resolve(step.destination, trade);
            step.targetId = result.entity().getId();
            step.marketId = result.market() == null ? "" : result.market().getId();
            step.systemDestination = result.system();
            return result;
        }
        SectorEntityToken entity = Global.getSector().getEntityById(step.targetId);
        if (entity == null || entity.isExpired()) throw new IllegalStateException("目的地已不存在：" + step.targetId);
        MarketAPI market = entity.getMarket();
        if (trade && (market == null || !Objects.equals(market.getId(), step.marketId))) {
            market = markets().stream().filter(m -> Objects.equals(m.getId(), step.marketId)).findFirst().orElse(null);
        }
        if (trade && (market == null || market.isPlanetConditionMarketOnly()))
            throw new IllegalStateException("目的地市场已不存在：" + step.marketId);
        return new Destination(entity, market, step.systemDestination);
    }

    private static SectorEntityToken target(MarketAPI market) {
        var primary = market.getPrimaryEntity();
        if (primary != null && !primary.isExpired()) return primary;
        if (market.getConnectedEntities() != null)
            for (var entity : market.getConnectedEntities()) if (!entity.isExpired()) return entity;
        return null;
    }
    private static void match(Map<String,Destination> ids, Map<String,Destination> names,
                              String query, String id, String name, Destination destination) {
        if (destination.entity().isExpired()) return;
        if (same(query, id)) ids.put(destination.entity().getId(), destination);
        if (same(query, name)) names.put(destination.entity().getId(), destination);
    }
    public static boolean same(String a, String b) { return b != null && normalize(a).equals(normalize(b)); }
    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
