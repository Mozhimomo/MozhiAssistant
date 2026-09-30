package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import static com.mozhi.assistant.runtime.tools.SpecText.*;

/** 当前战役的已知地点，不缓存星球、市场和玩家位置。所有方法在游戏主线程调用。 */
final class CampaignPlaces {
    private CampaignPlaces() { }

    static void requireCampaign() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            throw new IllegalStateException("需要进入战役并拥有玩家舰队后使用。");
        }
    }

    /** 购买查询使用全部当前市场，不套用星图的已知/可见性过滤。 */
    static List<MarketAPI> allMarkets(List<String> warnings) {
        var result = new ArrayList<MarketAPI>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<MarketAPI, Boolean>());
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market != null && seen.add(market)) result.add(market);
        }
        // 一些模组市场只绑定实体，没有加入经济系统。
        for (var location : Global.getSector().getAllLocations()) {
            try {
                for (var entity : location.getAllEntities()) {
                    try {
                        MarketAPI market = entity.getMarket();
                        if (market != null && seen.add(market)) result.add(market);
                    } catch (RuntimeException exception) {
                        warnings.add("实体市场读取失败：" + entity.getId());
                    }
                }
            } catch (RuntimeException exception) {
                warnings.add("位置市场枚举不完整：" + location.getName());
            }
        }
        return result;
    }

    static List<SectorEntityToken> marketEntities(MarketAPI market) {
        var result = new java.util.LinkedHashSet<SectorEntityToken>();
        if (market.getPrimaryEntity() != null) result.add(market.getPrimaryEntity());
        if (market.getConnectedEntities() != null) result.addAll(market.getConnectedEntities());
        result.remove(null);
        return new ArrayList<>(result);
    }

    static SectorEntityToken marketTarget(MarketAPI market) {
        return marketEntities(market).stream().filter(e -> e.isAlive() && !e.isExpired()).findFirst().orElse(null);
    }

    static List<SectorEntityToken> marketDestinations(String query) {
        var ids = new LinkedHashMap<String, SectorEntityToken>();
        var names = new LinkedHashMap<String, SectorEntityToken>();
        List<String> warnings = new ArrayList<>();
        for (MarketAPI market : allMarkets(warnings)) {
            for (var entity : marketEntities(market)) {
                if (same(query, entity.getId()) || (entity == marketTarget(market) && same(query, market.getId()))) ids.put(entity.getId(), entity);
                if (same(query, entity.getName()) || (entity == marketTarget(market) && same(query, market.getName()))) names.put(entity.getId(), entity);
            }
        }
        if (!warnings.isEmpty()) throw new IllegalStateException("市场目录读取不完整，无法唯一确认目的地：" + warnings);
        return new ArrayList<>((ids.isEmpty() ? names : ids).values());
    }

    static List<MarketAPI> markets() {
        return Global.getSector().getEconomy().getMarketsCopy().stream()
                .filter(m -> !m.isHidden() && !m.isPlanetConditionMarketOnly())
                .filter(m -> available(m.getPrimaryEntity())).toList();
    }

    private static boolean available(SectorEntityToken entity) {
        return entity != null && entity.isAlive() && !entity.isExpired() && !entity.isDiscoverable();
    }

    static List<PlanetAPI> planets() {
        List<PlanetAPI> result = new ArrayList<>();
        for (var system : Global.getSector().getStarSystems()) {
            for (PlanetAPI planet : system.getPlanets()) {
                if (planet.isStar() || !available(planet)) continue;
                MarketAPI market = planet.getMarket();
                boolean inhabited = market != null && !market.isHidden() && !market.isPlanetConditionMarketOnly();
                boolean surveyed = market != null && market.getSurveyLevel() != MarketAPI.SurveyLevel.NONE;
                if (inhabited || system.isEnteredByPlayer() || surveyed) result.add(planet);
            }
        }
        return result;
    }

    static List<SectorEntityToken> destinations(String query) {
        LinkedHashMap<String, SectorEntityToken> ids = new LinkedHashMap<>();
        LinkedHashMap<String, SectorEntityToken> names = new LinkedHashMap<>();
        for (MarketAPI market : markets()) {
            SectorEntityToken target = market.getPrimaryEntity();
            if (same(query, market.getId()) || same(query, target.getId())) ids.put(target.getId(), target);
            if (same(query, market.getName()) || same(query, target.getName())) names.put(target.getId(), target);
        }
        for (PlanetAPI planet : planets()) {
            if (same(query, planet.getId())) ids.put(planet.getId(), planet);
            if (same(query, planet.getName())) names.put(planet.getId(), planet);
        }
        return new ArrayList<>((ids.isEmpty() ? names : ids).values());
    }

    static String search(List<String> queries) {
        StringBuilder out = new StringBuilder();
        for (String query : queries) {
            line(out, "查询", query);
            List<SectorEntityToken> matches = destinations(query);
            if (matches.isEmpty()) out.append("未找到已知星球或市场；请使用完整名称或 ID。\n");
            for (SectorEntityToken entity : matches) out.append(details(entity)).append('\n');
        }
        return out.toString();
    }

    static String listPlanets(String systemQuery, int limit) {
        var sector = Global.getSector();
        var matches = sector.getStarSystems().stream().filter(system ->
                systemQuery == null || systemQuery.isBlank()
                        ? system == sector.getPlayerFleet().getStarSystem()
                        : same(systemQuery, system.getId()) || same(systemQuery, system.getName())
                            || same(systemQuery, system.getNameWithNoType())).toList();
        if (matches.size() != 1) {
            return "请指定唯一的星系名称或 ID。匹配星系：" + matches.stream()
                    .map(s -> s.getName() + " [" + s.getId() + "]").toList();
        }
        var candidates = planets().stream().filter(p -> p.getStarSystem() == matches.get(0))
                .sorted(Comparator.comparingDouble(p -> distance(sector.getPlayerFleet(), p))).toList();
        StringBuilder out = new StringBuilder("本次只列出星系「" + matches.get(0).getName()
                + "」的星球，不是购买地点搜索；查哪里买舰船或物品请调用 findBuyingLocations 搜索全星区。\n"
                + "已知星球数：" + candidates.size() + "；显示上限：" + limit + "\n");
        candidates.stream().limit(limit).forEach(p -> out.append(details(p)).append('\n'));
        return out.toString();
    }

    static String details(SectorEntityToken entity) {
        StringBuilder out = new StringBuilder();
        line(out, "地点", label(entity));
        line(out, "类别", entity instanceof PlanetAPI ? "星球" : "市场/空间站");
        line(out, "所在星系/位置", entity.getContainingLocation().getName());
        line(out, "距玩家的星际直线距离/光年", distance(Global.getSector().getPlayerFleet(), entity));
        if (entity.getContainingLocation() == Global.getSector().getPlayerFleet().getContainingLocation()) {
            line(out, "本地直线距离/游戏单位", Misc.getDistance(Global.getSector().getPlayerFleet(), entity));
        }
        if (entity instanceof PlanetAPI planet) line(out, "星球类型", planet.getTypeNameWithWorld());
        MarketAPI market = entity.getMarket();
        if (market == null || market.isHidden()) return out.toString();
        line(out, "市场 ID", market.getId());
        line(out, "调查程度", market.getSurveyLevel());
        boolean inhabited = !market.isPlanetConditionMarketOnly();
        if (inhabited || market.getSurveyLevel() == MarketAPI.SurveyLevel.FULL) {
            line(out, "危险度/%", market.getHazardValue() * 100);
        }
        for (var condition : market.getConditions()) {
            if (inhabited || condition.isSurveyed() || !condition.requiresSurveying()) {
                line(out, "环境/资源", condition.getName() + " [" + condition.getId() + "]");
            }
        }
        if (inhabited) {
            line(out, "市场规模", market.getSize());
            line(out, "所属势力", market.getFaction().getDisplayName());
            line(out, "对玩家敌对", market.getFaction().isHostileTo(Global.getSector().getPlayerFaction()));
            line(out, "稳定度", market.getStabilityValue());
            line(out, "基础关税/%", market.getTariff().getModifiedValue() * 100);
            for (var industry : market.getIndustries()) line(out, "产业", industry.getCurrentName());
            for (var submarket : market.getSubmarketsCopy()) {
                if (!submarket.getPlugin().isHidden()) line(out, "交易区", submarket.getNameOneLine() + " [" + submarket.getSpecId() + "]");
            }
        }
        return out.toString();
    }

    static double distance(SectorEntityToken from, SectorEntityToken to) {
        if (from == null || to == null) return Double.POSITIVE_INFINITY;
        return Misc.getDistanceLY(from.getLocationInHyperspace(), to.getLocationInHyperspace());
    }

    static String label(SectorEntityToken entity) {
        return entity.getName() + " [" + entity.getId() + "]";
    }

    static boolean same(String a, String b) {
        return a != null && b != null && !a.isBlank() && normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
