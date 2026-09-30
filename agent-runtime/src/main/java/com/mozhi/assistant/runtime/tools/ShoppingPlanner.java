package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mozhi.assistant.runtime.tools.SpecLookup.Category.*;
import static com.mozhi.assistant.runtime.tools.SpecText.*;

/** 每次通过实时库存读取器搜索全部市场，再基于数量、权限与距离安排购买站点。 */
final class ShoppingPlanner {
    record Item(SpecLookup.Category category, String id, String name, Set<String> hullIds) {
        String label() { return name + " [" + category + ":" + id + "]"; }
    }
    record Offer(Item item, MarketAPI market, String shop, String instance, double quantity, float tariff, String restriction) {
        boolean canBuy() { return restriction.isEmpty(); }
    }
    record Scan(List<Offer> offers, List<String> warnings, String coverage) { }
    record Purchase(Offer offer, double quantity) { }
    record Stop(MarketAPI market, List<Purchase> purchases, double distanceLY) { }
    record Plan(List<Stop> stops, Map<Item, Double> missing, List<String> warnings, String coverage) { }

    private ShoppingPlanner() { }

    static Map<Item, Double> requests(List<String> names, List<Integer> quantities) {
        if (names == null || names.isEmpty() || names.size() > 20) {
            throw new IllegalArgumentException("请提供 1 至 20 个购买名称或 ID。");
        }
        boolean defaults = quantities == null || quantities.isEmpty();
        if (!defaults && quantities.size() != names.size()) {
            throw new IllegalArgumentException("数量列表必须与物品列表一一对应；空列表表示每项 1 个。");
        }
        Map<Item, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isBlank()) throw new IllegalArgumentException("购买名称不能留空。");
            Integer count = defaults ? 1 : quantities.get(i);
            if (count == null || count < 1 || count > 1000000) throw new IllegalArgumentException("数量须为 1 至 1000000 的整数。");
            var matches = SpecLookup.resolve(name);
            boolean hullNames = !matches.isEmpty() && matches.stream().allMatch(m -> m.category() == HULL);
            if (matches.isEmpty() || (matches.size() != 1 && !hullNames)) {
                throw new IllegalArgumentException("购买对象「" + name + "」" + (matches.isEmpty() ? "未找到。" : "不唯一："
                        + matches.stream().map(m -> m.category().label + " / " + m.name() + " [" + m.category() + ":" + m.id() + "]").toList())
                        + "请指定唯一的规格 ID；跨类别同 ID 时使用类别代码:ID，例如 COMMODITY:fuel。");
            }
            var match = matches.get(0);
            if (match.category() == SHIP_SYSTEM) {
                throw new IllegalArgumentException("战术系统通常是舰型自带能力，不能作为独立货物购买：" + name);
            }
            Set<String> hullIds = new LinkedHashSet<>();
            if (hullNames) {
                for (var candidate : matches) hullIds.add(candidate.id());
                // 基础舰型包含 API 声明的 D 型子舰型；指定变体 ID 时保持精确匹配。
                for (var spec : Global.getSettings().getAllShipHullSpecs()) {
                    if (spec.isDHull() && (hullIds.contains(spec.getDParentHullId())
                            || hullIds.contains(spec.getBaseHullId()))) hullIds.add(spec.getHullId());
                }
            }
            Item item = new Item(match.category(), match.id(), hullNames ? name : match.name(), Set.copyOf(hullIds));
            Item equivalent = null;
            for (Item existing : result.keySet()) {
                if (existing.category != item.category) continue;
                if ((item.category == HULL && existing.hullIds.equals(item.hullIds))
                        || (item.category != HULL && existing.id.equals(item.id))) {
                    equivalent = existing;
                    break;
                }
                if (item.category == HULL && existing.hullIds.stream().anyMatch(item.hullIds::contains)) {
                    throw new IllegalArgumentException("舰型清单存在重叠型号，请合并查询或指定不重叠的变体 ID，避免同一艘船重复分配。");
                }
            }
            result.merge(equivalent == null ? item : equivalent, count.doubleValue(), Double::sum);
        }
        return result;
    }

    static Scan scan(Set<Item> wanted, boolean includeBlackMarket) {
        return new MarketStockReader(wanted, includeBlackMarket).scan();
    }

    static String buyingLocations(Map<Item, Double> request, Scan scan, int limit) {
        StringBuilder out = new StringBuilder(scan.coverage).append(stockNotice());
        var player = Global.getSector().getPlayerFleet();
        for (var need : request.entrySet()) {
            line(out, "购买目标", need.getKey().label());
            line(out, "期望数量", need.getValue());
            if (!need.getKey().hullIds.isEmpty()) line(out, "本次匹配舰型 ID（含关联 D 型）", need.getKey().hullIds);
            var offers = scan.offers.stream().filter(o -> o.item.equals(need.getKey()))
                    .sorted(Comparator.comparingDouble((Offer o) -> CampaignPlaces.distance(player, CampaignPlaces.marketTarget(o.market)))
                            .thenComparingDouble(o -> localDistance(player, CampaignPlaces.marketTarget(o.market)))).toList();
            line(out, "全范围匹配库存条目", offers.size());
            line(out, "其中当前可购买条目", offers.stream().filter(Offer::canBuy).count());
            line(out, "匹配市场数", offers.stream().map(o -> o.market.getId()).distinct().count());
            line(out, "本次展示条目数", Math.min(limit, offers.size()));
            if (offers.size() > limit) {
                out.append("按距离展示最近 ").append(limit).append(" 条，另有 ")
                        .append(offers.size() - limit).append(" 条未展示；搜索本身已覆盖上述全范围。\n");
            }
            for (Offer offer : offers.stream().limit(limit).toList()) {
                appendOffer(out, offer, offer.quantity);
                line(out, "距玩家/光年", CampaignPlaces.distance(player, CampaignPlaces.marketTarget(offer.market)));
            }
            if (offers.isEmpty()) out.append("本次读取的全部库存中没有匹配项；如有读取失败，不能断言整个星区无货。\n");
        }
        appendWarnings(out, scan.warnings);
        return out.toString();
    }

    static Plan plan(Map<Item, Double> request, Scan scan) {
        Map<Item, Double> remaining = new LinkedHashMap<>(request);
        Map<String, List<Offer>> byMarket = new LinkedHashMap<>();
        for (Offer offer : scan.offers) {
            if (offer.canBuy()) byMarket.computeIfAbsent(offer.market.getId(), ignored -> new ArrayList<>()).add(offer);
        }
        List<Stop> stops = new ArrayList<>();
        SectorEntityToken from = Global.getSector().getPlayerFleet();
        while (!remaining.isEmpty()) {
            final SectorEntityToken origin = from;
            var next = byMarket.values().stream().filter(offers -> offers.stream().anyMatch(o -> remaining.containsKey(o.item)))
                    .min(Comparator.comparingDouble((List<Offer> offers) -> CampaignPlaces.distance(origin, CampaignPlaces.marketTarget(offers.get(0).market)))
                            .thenComparingDouble(offers -> localDistance(origin, CampaignPlaces.marketTarget(offers.get(0).market))));
            if (next.isEmpty()) break;
            List<Offer> offers = next.get();
            MarketAPI market = offers.get(0).market;
            List<Purchase> purchases = new ArrayList<>();
            for (Offer offer : offers) {
                double needed = remaining.getOrDefault(offer.item, 0d);
                double taken = Math.min(needed, Math.floor(offer.quantity));
                if (taken <= 0) continue;
                purchases.add(new Purchase(offer, taken));
                double rest = needed - taken;
                if (rest <= 0) remaining.remove(offer.item); else remaining.put(offer.item, rest);
            }
            byMarket.remove(market.getId());
            if (!purchases.isEmpty()) {
                stops.add(new Stop(market, List.copyOf(purchases), CampaignPlaces.distance(from, CampaignPlaces.marketTarget(market))));
                from = CampaignPlaces.marketTarget(market);
            }
        }
        return new Plan(List.copyOf(stops), remaining, scan.warnings, scan.coverage);
    }

    static double localDistance(SectorEntityToken from, SectorEntityToken to) {
        return from != null && to != null && from.getContainingLocation() == to.getContainingLocation()
                ? Misc.getDistance(from, to) : Double.MAX_VALUE;
    }

    static String describe(Plan plan) {
        StringBuilder out = new StringBuilder(plan.coverage).append(stockNotice());
        out.append("路线采用逐站选择最近可补足物品的市场；不是全局最短路线或最低价格方案。\n");
        double distance = 0;
        for (int i = 0; i < plan.stops.size(); i++) {
            Stop stop = plan.stops.get(i);
            distance += stop.distanceLY;
            line(out, "第 " + (i + 1) + " 站", stop.market.getName() + " [市场 ID=" + stop.market.getId() + "]");
            line(out, "本段星际直线距离/光年", stop.distanceLY);
            for (Purchase purchase : stop.purchases) appendOffer(out, purchase.offer, purchase.quantity);
        }
        line(out, "总星际直线距离/光年", distance);
        out.append("距离不包含星系内航程、跳跃与绕行；尚未估算燃料、货舱和资金是否足够。\n");
        for (var missing : plan.missing.entrySet()) line(out, "未能凑齐 " + missing.getKey().label(), missing.getValue());
        appendWarnings(out, plan.warnings);
        return out.toString();
    }

    private static void appendOffer(StringBuilder out, Offer offer, double quantity) {
        line(out, "物品", offer.item.label());
        line(out, "库存实例", offer.instance);
        line(out, "数量", quantity);
        line(out, "市场", offer.market.getName() + " [" + offer.market.getId() + "]");
        line(out, "星系/位置", offer.market.getContainingLocation() == null ? "未绑定位置" : offer.market.getContainingLocation().getName());
        line(out, "商店", offer.shop);
        line(out, "关税/%", Float.isNaN(offer.tariff) ? "未知" : offer.tariff * 100);
        line(out, "购买状态", offer.canBuy() ? "当前权限检查通过" : offer.restriction);
    }

    private static String stockNotice() {
        return "本次在游戏主线程实时遍历市场及仓库；商店先执行游戏常规库存更新，再读取 cargo 和封存舰船。未使用历史库存或助手缓存。\n"
                + "所有匹配项均参与结果：敌对、隐藏、黑市、仓储及权限不明的货物也保留，并标注购买状态；只有权限通过的项用于自动购买路线。\n";
    }

    private static void appendWarnings(StringBuilder out, List<String> warnings) {
        Set<String> unique = new LinkedHashSet<>(warnings);
        if (unique.isEmpty()) return;
        line(out, "扫描提示条数", unique.size());
        unique.stream().limit(15).forEach(w -> out.append(w).append('\n'));
        if (unique.size() > 15) out.append("其余同类提示省略；查询覆盖不完整。\n");
    }
}
