package com.mozhi.fleet.trading;

import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import java.util.*;
import java.util.concurrent.CancellationException;
import static com.mozhi.fleet.trading.TradeSnapshot.*;

/** 独立实现：整批报价、有限货物组合搜索、多站束搜索。无需游戏 API 或 LLM。 */
public final class TradeRouteCalculator {
    public record Result(Plan plan, double expectedTradeProfit, double estimatedDays, double fuel,
                         double supplies, int markets, boolean searchLimited, String explanation) {}
    private record Lot(Quote buy, Quote sell, int quantity, double cost, double revenue) {
        double profit() { return revenue - cost; }
    }
    private record Load(List<Lot> lots, double cost, double profit, double cargo, double fuel, double personnel) {
        static Load empty() { return new Load(List.of(), 0, 0, 0, 0, 0); }
        Load plus(Lot lot) {
            var copy = new ArrayList<>(lots); copy.add(lot);
            double used = lot.quantity * lot.buy.space();
            return new Load(List.copyOf(copy), cost + lot.cost, profit + lot.profit(),
                    cargo + (lot.buy.hold() == Hold.CARGO ? used : 0), fuel + (lot.buy.hold() == Hold.FUEL ? used : 0),
                    personnel + (lot.buy.hold() == Hold.PERSONNEL ? used : 0));
        }
    }
    private record Hop(Market from, Market to, Load load) {}
    private record Route(Market start, Market at, Set<String> visited, List<Hop> hops,
                         double cash, double profit, double days, double fuel, double supplies) {
        double score() { return profit / days; }
    }
    private static final int ROUTE_WIDTH = 48, LOAD_WIDTH = 32;
    private final TradeSnapshot snapshot;
    private final TradeRouteOptions options;
    private final long deadline;
    private int evaluations;
    private boolean limited;

    public TradeRouteCalculator(TradeSnapshot snapshot, TradeRouteOptions options) {
        this(snapshot, options, 2000);
    }
    public TradeRouteCalculator(TradeSnapshot snapshot, TradeRouteOptions options, long budgetMs) {
        this.snapshot = Objects.requireNonNull(snapshot); this.options = Objects.requireNonNull(options);
        if (budgetMs < 1) throw new IllegalArgumentException("计算时间必须大于零");
        deadline = System.nanoTime() + Math.min(budgetMs, 10_000) * 1_000_000L;
    }
    public Result calculate() {
        List<Route> frontier = new ArrayList<>();
        Fleet fleet = snapshot.fleet();
        for (Market market : snapshot.markets()) {
            Travel trip = snapshot.travel(fleet.position(), market.position());
            if (trip.lightYears() > options.maxStartDistanceLy() && !fleet.position().locationId().equals(market.position().locationId())) continue;
            if (feasible(trip.days(), trip.fuel(), trip.supplies()))
                frontier.add(new Route(market, market, Set.of(market.id()), List.of(), fleet.credits(), 0, trip.days(), trip.fuel(), trip.supplies()));
        }
        frontier.sort(Comparator.comparingDouble(Route::days).thenComparing(route -> route.at.id()));
        Route best = null;
        for (int stop = 1; stop < options.maxStops() && !frontier.isEmpty() && !expired(); stop++) {
            List<Route> next = new ArrayList<>();
            for (Route route : frontier) {
                for (Market target : snapshot.markets()) {
                    if (expired()) break;
                    if (route.visited.contains(target.id())) continue;
                    Route candidate = extend(route, target, false);
                    if (candidate == null) continue;
                    next.add(candidate);
                    Route complete = options.closedLoop() ? extend(candidate, candidate.start, true) : candidate;
                    if (complete != null && complete.profit >= options.minProfit() && complete.profit > 0 && better(complete, best)) best = complete;
                    // Bound memory as well as work on very large modded sectors.
                    if (next.size() > ROUTE_WIDTH * 4) trim(next);
                }
                if (expired()) break;
            }
            trim(next); frontier = next;
        }
        if (best == null) return new Result(null, 0, 0, 0, 0, snapshot.markets().size(), limited,
                "在当前资金、库存、货舱、燃料、补给和路线约束内未找到可盈利路线" + (limited ? "（搜索预算已用尽，可缩小范围后重算）" : ""));
        List<Step> steps = new ArrayList<>();
        move(steps, best.start);
        for (Hop hop : best.hops) {
            for (Lot lot : hop.load.lots) trade(steps, "BUY", hop.from, lot.buy, lot.quantity);
            move(steps, hop.to);
            for (Lot lot : hop.load.lots) trade(steps, "SELL", hop.to, lot.sell, lot.quantity);
        }
        String summary = String.format(Locale.ROOT,
                "跑商路线：%d 次航段，预计贸易利润 %.0f 信用点 / %.1f 天（%.0f/天），燃料 %.1f、补给 %.1f；已计关税，利润未扣航行消耗价值。%s%s",
                best.hops.size(), best.profit, best.days, best.score(), best.fuel, best.supplies,
                limited ? "有界搜索结果，不保证全局最优。" : "数量为离散整批报价搜索，不保证全局最优。",
                snapshot.skipped() > 0 ? "跳过 " + snapshot.skipped() + " 个无法读取或交易的市场/交易区。" : "");
        return new Result(Plan.create("按计算结果执行跑商", steps), best.profit, best.days, best.fuel, best.supplies,
                snapshot.markets().size(), limited, summary);
    }
    private Route extend(Route route, Market destination, boolean closing) {
        Travel trip = snapshot.travel(route.at.position(), destination.position());
        double days = route.days + trip.days(), fuel = route.fuel + trip.fuel(), supplies = route.supplies + trip.supplies();
        if (!feasible(days, fuel, supplies)) return null;
        double money = Math.min(options.maxSpend(), route.cash - options.reserveCredits());
        Load load = pack(route.at, destination, money);
        if (load.lots.isEmpty() && !closing) return null;
        List<Hop> hops = new ArrayList<>(route.hops); hops.add(new Hop(route.at, destination, load));
        Set<String> visited = new HashSet<>(route.visited); visited.add(destination.id());
        return new Route(route.start, destination, Set.copyOf(visited), List.copyOf(hops), route.cash + load.profit,
                route.profit + load.profit, days, fuel, supplies);
    }
    private Load pack(Market source, Market destination, double money) {
        if (money <= 0 || expired()) return Load.empty();
        evaluations++;
        Map<String, Map<Integer, Lot>> choices = new TreeMap<>();
        for (Quote buy : source.quotes()) for (Quote sell : destination.quotes()) {
            if (expired()) return Load.empty();
            if (!buy.commodityId().equals(sell.commodityId())) continue;
            for (var price : buy.buys().entrySet()) {
                double cost = price.getValue(), revenue = sell.sells().getOrDefault(price.getKey(), -1d);
                if (!Double.isFinite(cost) || !Double.isFinite(revenue) || cost < 0 || cost > money || revenue <= cost) continue;
                Lot lot = new Lot(buy, sell, price.getKey(), cost, revenue);
                choices.computeIfAbsent(buy.commodityId(), key -> new TreeMap<>()).merge(price.getKey(), lot,
                        (a, b) -> b.profit() > a.profit() ? b : a);
            }
        }
        List<Load> loads = new ArrayList<>(List.of(Load.empty()));
        for (var variants : choices.values()) {
            if (expired()) break;
            List<Load> candidates = new ArrayList<>(loads); // Also consider not carrying this commodity.
            for (Load old : loads) for (Lot lot : variants.values()) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("跑商计算已取消");
                Load added = old.plus(lot);
                if (added.cost <= money && added.cargo <= snapshot.fleet().cargoRoom()
                        && added.fuel <= snapshot.fleet().fuelRoom() && added.personnel <= snapshot.fleet().personnelRoom()) candidates.add(added);
            }
            candidates.sort(Comparator.comparingDouble(Load::profit).reversed().thenComparingDouble(Load::cost));
            // Keep several low-cost and high-profit alternatives for later commodities.
            LinkedHashSet<Load> kept = new LinkedHashSet<>(candidates.subList(0, Math.min(LOAD_WIDTH / 2, candidates.size())));
            candidates.sort(Comparator.comparingDouble(load -> -load.profit / Math.max(1, load.cost)));
            for (Load candidate : candidates) { if (kept.size() >= LOAD_WIDTH) break; kept.add(candidate); }
            kept.add(Load.empty()); loads = new ArrayList<>(kept);
        }
        return loads.stream().max(Comparator.comparingDouble(Load::profit).thenComparingDouble(load -> -load.cost)).orElse(Load.empty());
    }
    private boolean feasible(double days, double fuel, double supplies) {
        return days <= options.maxDays() && fuel + options.reserveFuel() <= snapshot.fleet().fuel()
                && supplies + options.reserveSupplies() <= snapshot.fleet().supplies();
    }
    private boolean expired() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("跑商计算已取消");
        if (System.nanoTime() >= deadline || evaluations >= 20_000) limited = true;
        return limited;
    }
    private static boolean better(Route a, Route b) { return b == null || a.score() > b.score() || a.score() == b.score() && a.profit > b.profit; }
    private static void trim(List<Route> routes) {
        routes.sort(Comparator.comparingDouble(Route::score).reversed().thenComparing(route -> route.at.id()));
        if (routes.size() > ROUTE_WIDTH) routes.subList(ROUTE_WIDTH, routes.size()).clear();
    }
    private static void move(List<Step> steps, Market at) {
        steps.add(Step.create("MOVE_TO", Map.of("destinationId", at.destinationId()), "前往 " + at.name(), "实际入轨后交易"));
    }
    private static void trade(List<Step> steps, String action, Market at, Quote quote, int quantity) {
        steps.add(Step.create(action, Map.of("marketId", at.id(), "submarketId", quote.submarketId(),
                "itemType", "COMMODITY", "itemId", quote.commodityId(), "quantity", quantity),
                (action.equals("BUY") ? "购买 " : "出售 ") + quantity + " × " + quote.name(), "按实时库存和价格成交"));
    }
}
