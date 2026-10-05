package com.mozhi.fleet.trading;

import java.util.*;
import static com.mozhi.fleet.trading.TradeSnapshot.*;

/** 独立实现数量采样、单位空间收益排序和贪心装载；先黑市、后开放市场，最后装燃料。 */
public final class TradeCargoPacker {
    public record Lot(Quote buy, Quote sell, int quantity, double cost, double revenue) {
        public double profit() { return revenue - cost; }
    }
    public record Load(List<Lot> lots, double cost, double profit, double cargo, double fuel) {
        public Load { lots = List.copyOf(lots); }
        public static Load empty() { return new Load(List.of(), 0, 0, 0, 0); }
    }
    private record Ranked(Quote buy, Quote sell, double density) {}
    private final TradeQuotes prices;
    private final TradeRouteOptions options;
    private final Runnable checkpoint;
    public TradeCargoPacker(TradeQuotes prices, TradeRouteOptions options, Runnable checkpoint) {
        this.prices = prices; this.options = options; this.checkpoint = checkpoint;
    }
    public Load pack(Market from, Market to, double cargo, double cash, double tank, Map<String, Integer> consumed) {
        if (from.id().equals(to.id()) || cash <= 0) return Load.empty();
        List<Lot> lots = new ArrayList<>();
        Map<String, Integer> left = new HashMap<>();
        for (Quote q : from.quotes()) left.put(key(q), Math.max(0, q.buyCap() - consumed.getOrDefault(key(q), 0)));
        double originalCash = cash, originalCargo = cargo, originalTank = tank;
        boolean destBlack = options.allowBlackMarket() && to.quotes().stream().anyMatch(q -> q.black() && q.canSell());
        // 黑买优先黑卖；开放市场买入先尝试黑卖，再用剩余资源尝试开放市场卖出。
        boolean[][] channels = {{true, destBlack}, {false, true}, {false, false}};
        for (Hold hold : List.of(Hold.CARGO, Hold.FUEL)) for (boolean[] pair : channels) {
            checkpoint.run();
            if (pair[0] && !options.allowBlackMarket() || pair[1] && !destBlack) continue;
            double room = hold == Hold.FUEL ? tank : cargo;
            List<Ranked> ranked = new ArrayList<>();
            for (Quote buy : from.quotes()) {
                if (buy.hold() != hold || buy.black() != pair[0] || !buy.canBuy() || !tradeAllowed(buy.commodityId())) continue;
                Quote sell = to.quotes().stream().filter(q -> q.commodityId().equals(buy.commodityId()) && q.black() == pair[1] && q.canSell()).findFirst().orElse(null);
                if (sell == null) continue;
                int cap = Math.min(left.getOrDefault(key(buy), 0), fit(room, buy.space()));
                int affordable = affordable(from, buy, cap, cash);
                Lot best = best(from, to, buy, sell, affordable, buy.econUnit(), true);
                if (best != null) ranked.add(new Ranked(buy, sell, best.profit() / (best.quantity * buy.space())));
            }
            ranked.sort(Comparator.comparingDouble(Ranked::density).reversed().thenComparing(r -> r.buy.commodityId()));
            for (Ranked row : ranked) {
                checkpoint.run();
                room = hold == Hold.FUEL ? tank : cargo;
                int cap = Math.min(left.getOrDefault(key(row.buy), 0), fit(room, row.buy.space()));
                int affordable = affordable(from, row.buy, cap, cash);
                Lot lot = best(from, to, row.buy, row.sell, affordable, 1, false);
                if (lot == null || lot.cost > cash) continue;
                lots.add(lot); cash -= lot.cost;
                if (hold == Hold.FUEL) tank -= lot.quantity; else cargo -= lot.quantity * lot.buy.space();
                left.merge(key(lot.buy), -lot.quantity, Integer::sum);
            }
        }
        return new Load(lots, originalCash - cash, lots.stream().mapToDouble(Lot::profit).sum(), originalCargo - cargo, originalTank - tank);
    }
    public int affordable(Market market, Quote buy, int cap, double cash) {
        checkpoint.run();
        final int limit = Math.min(1_000_000, cap);
        cap = limit;
        if (cap < 1 || cash <= 0) return 0;
        // 离线离散报价只能使用已有样本，不允许插值制造价格。
        if (!buy.buys().isEmpty()) return buy.buys().entrySet().stream()
                .filter(e -> e.getKey() <= limit && e.getValue() >= 0 && e.getValue() <= cash)
                .mapToInt(Map.Entry::getKey).max().orElse(0);
        double one = prices.quote(market, buy, 1, true);
        if (one < 0 || one > cash) return 0;
        double full = prices.quote(market, buy, cap, true);
        if (full >= 0 && full <= cash) return cap;
        int low = 1, high = cap;
        while (low < high) {
            checkpoint.run();
            int mid = low + (high - low + 1) / 2;
            double price = prices.quote(market, buy, mid, true);
            if (price >= 0 && price <= cash) low = mid; else high = mid - 1;
        }
        return low;
    }
    private Lot best(Market from, Market to, Quote buy, Quote sell, int cap, double unit, boolean density) {
        Lot best = null;
        Collection<Integer> quantities = buy.buys().isEmpty() ? TradeSnapshot.quantities(cap, unit) : new TreeSet<>(buy.buys().keySet());
        for (int quantity : quantities) {
            checkpoint.run();
            if (quantity <= 0 || quantity > cap) continue;
            double cost = prices.quote(from, buy, quantity, true), revenue = prices.quote(to, sell, quantity, false);
            if (cost < 0 || revenue <= cost) continue;
            Lot candidate = new Lot(buy, sell, quantity, cost, revenue);
            double score = density ? candidate.profit() / (quantity * buy.space()) : candidate.profit();
            double previous = best == null ? -1 : density ? best.profit() / (best.quantity * buy.space()) : best.profit();
            if (score > previous || score == previous && best != null && candidate.profit() > best.profit()) best = candidate;
        }
        return best;
    }
    private boolean tradeAllowed(String id) { return options.commodityIds().isEmpty() || options.commodityIds().contains(id); }
    private static int fit(double room, double space) { return (int) Math.max(0, Math.min(1_000_000, Math.floor(room / space))); }
    public static String key(Quote q) { return q.submarketId() + ":" + q.commodityId(); }
}
