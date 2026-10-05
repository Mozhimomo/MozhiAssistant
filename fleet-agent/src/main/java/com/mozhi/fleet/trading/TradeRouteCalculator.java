package com.mozhi.fleet.trading;

import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import java.util.*;
import java.util.concurrent.CancellationException;
import static com.mozhi.fleet.trading.TradeSnapshot.*;
import static com.mozhi.fleet.trading.TradeHopPlanner.*;

/** 独立实现供需粗排、按需报价、双站闭环种子和限时分支搜索；可在主线程分帧推进。 */
public final class TradeRouteCalculator {
    public record Metrics(int candidates, int starts, int rankedSources, int cheapPairs, int edges,
                          int pairs, int quotes, int expanded, int pruned,
                          long graphMs, long searchMs, long totalMs) {}
    public record Result(Plan plan, double expectedTradeProfit, double estimatedDays, double fuel,
                         double supplies, int markets, boolean searchLimited, String explanation, Metrics metrics) {}
    private record Pair(Market from, Market to) {}
    private record Source(Market market, int limit) {}
    private record Hint(int buyCap, double space, int excess, int deficit, boolean sellable) {}
    private record Edge(Market to, double profit, double days) { double score() { return profit / Math.max(.05, days); } }
    private record Route(Market start, Market at, Set<String> visited, List<Hop> hops, Resources resources,
                         double positionDays, double loopDays, double profit, double fuel, double supplies) {
        double days() { return positionDays + loopDays; }
        double score() { return profit / Math.max(.05, loopDays + .5 * positionDays); }
    }
    private record Node(Route route, double bound) {}
    private record Seed(Route route, Market to, double score) {}
    private static final int MAX_GRAPH = 200, MAX_OPEN = 4000, START_OUT = 48, NEXT_OUT = 16, START_BUDGET = 960, LOOP_SEEDS = 800;
    private static final class BudgetEnd extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
    private final TradeSnapshot snapshot;
    private final TradeRouteOptions options;
    private final TradeQuotes.Cached prices;
    private final TradeHopPlanner hops;
    private final long budgetNanos;
    private final Map<String, Travel> travels = new HashMap<>();
    private final Map<String, Map<String, Hint>> hints = new HashMap<>();
    private final Map<String, Double> cheapScores = new HashMap<>();
    private final Map<String, List<Edge>> graph = new HashMap<>();
    private final Deque<Pair> pairs = new ArrayDeque<>();
    private final Deque<Source> sources = new ArrayDeque<>();
    private final Set<String> selectedPairs = new HashSet<>();
    private final Deque<Seed> seeds = new ArrayDeque<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::bound).reversed()
            .thenComparing(n -> n.route.at.id()));
    private List<Market> starts = List.of(), candidates = List.of();
    private long started, graphFinished, deadline, graphDeadline, pausedAt;
    private int phase, quotedPairs, expanded, pruned, rankedSources;
    private double maxEdgeProfit, minEdgeDays = Double.MAX_VALUE;
    private volatile boolean cancelled;
    private boolean limited;
    private Route best;
    private Result result;

    public TradeRouteCalculator(TradeSnapshot snapshot, TradeRouteOptions options) { this(snapshot, options, TradeQuotes.tables(), 2000); }
    public TradeRouteCalculator(TradeSnapshot snapshot, TradeRouteOptions options, long budgetMs) { this(snapshot, options, TradeQuotes.tables(), budgetMs); }
    public TradeRouteCalculator(TradeSnapshot snapshot, TradeRouteOptions options, TradeQuotes source, long budgetMs) {
        this.snapshot = Objects.requireNonNull(snapshot); this.options = Objects.requireNonNull(options);
        if (budgetMs < 1) throw new IllegalArgumentException("计算时间必须大于零");
        budgetNanos = Math.min(budgetMs, 10_000) * 1_000_000L;
        prices = new TradeQuotes.Cached(Objects.requireNonNull(source));
        hops = new TradeHopPlanner(snapshot, options, prices, this::checkpoint);
    }
    public Result calculate() { while (!advance(Long.MAX_VALUE)) { } return result; }
    public boolean advance(long sliceNanos) {
        if (result != null) return true;
        checkCancelled();
        if (pausedAt != 0) {
            long paused = System.nanoTime() - pausedAt;
            started += paused; deadline += paused; graphDeadline += paused;
            if (graphFinished != 0) graphFinished += paused;
            pausedAt = 0;
        }
        if (started == 0) {
            started = System.nanoTime(); deadline = started + budgetNanos; graphDeadline = started + budgetNanos * 55 / 100;
        }
        long sliceStart = System.nanoTime();
        do {
            try {
                if (phase == 0) { initialize(); phase = 1; }
                else if (phase == 1) {
                    if (pairs.isEmpty() && sources.isEmpty() || System.nanoTime() >= graphDeadline) {
                        if (!pairs.isEmpty() || !sources.isEmpty()) limited = true;
                        beginSearch();
                    } else if (pairs.isEmpty()) {
                        Source source = sources.removeFirst();
                        enqueue(source.market, source.limit);
                    } else quotePair(pairs.removeFirst());
                } else if (phase == 2) {
                    checkpoint();
                    if (!seeds.isEmpty()) {
                        Seed seed = seeds.removeFirst();
                        Route out = extend(seed.route, seed.to, false);
                        if (out != null) consider(out);
                    } else phase = 3;
                } else {
                    checkpoint();
                    if (open.isEmpty()) { finish(); break; }
                    expand(open.remove());
                }
            } catch (BudgetEnd exhausted) {
                limited = true;
                if (phase <= 1 && System.nanoTime() < deadline) beginSearch(); else finish();
            }
        } while (result == null && System.nanoTime() - sliceStart < sliceNanos);
        return result != null;
    }
    public Result result() { if (result == null) throw new IllegalStateException("跑商计算尚未结束"); return result; }
    public void cancel() { cancelled = true; }
    public void pause() { if (started != 0 && result == null && pausedAt == 0) pausedAt = System.nanoTime(); }
    public String progress() {
        return phase <= 1 ? "正在精算候选路线：已计算 " + quotedPairs + " 对市场、" + prices.calls() + " 个整批报价"
                : "正在搜索多站路线：已展开 " + expanded + " 个分支";
    }
    private void initialize() {
        starts = snapshot.markets().stream().filter(m -> travel(null, m).lightYears() <= options.maxStartDistanceLy() + .001)
                .sorted(Comparator.comparingDouble((Market m) -> travel(null, m).days()).thenComparing(Market::id)).toList();
        if (starts.isEmpty()) return;
        for (Market market : snapshot.markets()) {
            checkpoint();
            Map<String, Hint> goods = new LinkedHashMap<>();
            for (Quote quote : market.quotes()) {
                if (quote.hold() == Hold.PERSONNEL || quote.black() && !options.allowBlackMarket()
                        || !options.commodityIds().isEmpty() && !options.commodityIds().contains(quote.commodityId())) continue;
                Hint row = new Hint(quote.canBuy() ? quote.buyCap() : 0, quote.space(), quote.excess(), quote.deficit(), quote.canSell());
                goods.merge(quote.commodityId(), row, (a, b) -> new Hint(Math.max(a.buyCap, b.buyCap), a.space,
                        Math.max(a.excess, b.excess), Math.max(a.deficit, b.deficit), a.sellable || b.sellable));
            }
            hints.put(market.id(), goods);
        }
        Map<String, Double> scores = new HashMap<>();
        // 全部市场都能装入候选图时，不需要为了筛选再遍历一遍所有市场对。
        if (snapshot.markets().size() > MAX_GRAPH) for (Market start : starts) for (Market to : snapshot.markets()) {
            checkpoint();
            if (!start.id().equals(to.id())) scores.merge(to.id(), cheap(start, to), Math::max);
        }
        LinkedHashMap<String, Market> selected = new LinkedHashMap<>();
        starts.forEach(m -> selected.put(m.id(), m));
        for (Market market : snapshot.markets().stream().sorted(Comparator.<Market>comparingDouble(m -> scores.getOrDefault(m.id(), 0d))
                .reversed().thenComparing(Market::id)).toList()) {
            if (selected.size() >= MAX_GRAPH) break;
            selected.putIfAbsent(market.id(), market);
        }
        candidates = List.copyOf(selected.values());
        int startLimit = Math.min(START_OUT, Math.max(8, START_BUDGET / starts.size()));
        // 每排好一个来源市场就先精算其出边，不能等所有来源都排序完才开始报价。
        for (Market start : starts) sources.addLast(new Source(start, startLimit));
        for (Market from : candidates) sources.addLast(new Source(from, NEXT_OUT));
    }
    private void enqueue(Market from, int limit) {
        for (Market to : candidates) { checkpoint(); if (!from.id().equals(to.id())) cheap(from, to); }
        List<Market> ranked = candidates.stream().filter(to -> !to.id().equals(from.id()))
                .sorted(Comparator.<Market>comparingDouble(to -> cheapScores.get(key(from, to))).reversed().thenComparing(Market::id)).toList();
        rankedSources++;
        int count = 0;
        for (Market to : ranked) {
            checkpoint();
            if (!selectedPairs.add(key(from, to))) continue;
            pairs.addLast(new Pair(from, to));
            if (++count >= limit) break;
        }
    }
    private double cheap(Market from, Market to) {
        return cheapScores.computeIfAbsent(key(from, to), ignored -> cheapUncached(from, to));
    }
    private double cheapUncached(Market from, Market to) {
        double weight = 0;
        Map<String, Hint> destination = hints.getOrDefault(to.id(), Map.of());
        for (var entry : hints.getOrDefault(from.id(), Map.of()).entrySet()) {
            Hint buy = entry.getValue(), sell = destination.get(entry.getKey());
            if (sell == null || !sell.sellable || buy.buyCap <= 0) continue;
            int factor = buy.excess > 0 && sell.deficit > 0 ? 4 : buy.excess > 0 || sell.deficit > 0 ? 2 : 1;
            weight += buy.buyCap * (double) factor / buy.space;
        }
        return weight / Math.max(.05, travel(from, to).days());
    }
    private void quotePair(Pair pair) {
        checkpoint(); quotedPairs++;
        var load = hops.estimate(pair.from, pair.to);
        if (load.profit() <= 0) return;
        double days = travel(pair.from, pair.to).days();
        graph.computeIfAbsent(pair.from.id(), ignored -> new ArrayList<>()).add(new Edge(pair.to, load.profit(), days));
        maxEdgeProfit = Math.max(maxEdgeProfit, load.profit()); minEdgeDays = Math.min(minEdgeDays, Math.max(.05, days));
    }
    private void beginSearch() {
        phase = 2; graphFinished = System.nanoTime(); pairs.clear(); sources.clear();
        graph.values().forEach(edges -> edges.sort(Comparator.comparingDouble(Edge::score).reversed().thenComparing(e -> e.to.id())));
        List<Seed> loops = new ArrayList<>();
        for (Market start : starts) {
            checkCancelled();
            Travel trip = travel(null, start);
            Resources resources = hops.position(trip);
            if (resources == null || trip.days() > options.maxDays() || start.closedDays() > trip.days() + .01) continue;
            Route at = new Route(start, start, Set.of(start.id()), List.of(), resources, trip.days(), 0, 0, trip.fuel(), trip.supplies());
            open.add(new Node(at, bound(at)));
            if (options.closedLoop()) for (Edge edge : graph.getOrDefault(start.id(), List.of())) {
                double back = graph.getOrDefault(edge.to.id(), List.of()).stream().filter(e -> e.to.id().equals(start.id())).mapToDouble(Edge::profit).max().orElse(0);
                loops.add(new Seed(at, edge.to, (edge.profit + back) / Math.max(.05, edge.days + travel(edge.to, start).days() + .5 * trip.days())));
            }
        }
        loops.sort(Comparator.comparingDouble(Seed::score).reversed());
        seeds.addAll(loops.subList(0, Math.min(LOOP_SEEDS, loops.size())));
    }
    private void expand(Node node) {
        Route at = node.route;
        // 先检查已完成的前缀，避免启发式界值剪掉一个已经可执行的好方案。
        consider(at);
        if (best != null && node.bound + .01 < best.score()) { pruned++; return; }
        expanded++;
        if (at.visited.size() >= options.maxStops()) return;
        for (Edge edge : graph.getOrDefault(at.at.id(), List.of())) {
            checkpoint();
            if (at.visited.contains(edge.to.id())) continue;
            Route next = extend(at, edge.to, false);
            if (next == null) continue;
            consider(next);
            double bound = bound(next);
            if (best != null && bound + .01 < best.score()) pruned++; else open.add(new Node(next, bound));
        }
        if (open.size() > MAX_OPEN) {
            List<Node> kept = new ArrayList<>(open); kept.sort(open.comparator());
            pruned += kept.size() - MAX_OPEN; limited = true;
            open.clear(); open.addAll(kept.subList(0, MAX_OPEN));
        }
    }
    private Route extend(Route route, Market destination, boolean closing) {
        Travel trip = travel(route.at, destination);
        double arrival = route.days() + trip.days();
        if (arrival > options.maxDays() || destination.closedDays() > arrival + .01) return null;
        Hop hop = hops.plan(route.at, destination, route.resources, closing);
        if (hop == null) return null;
        List<Hop> path = new ArrayList<>(route.hops); path.add(hop);
        Set<String> visited = new HashSet<>(route.visited); visited.add(destination.id());
        return new Route(route.start, destination, Set.copyOf(visited), List.copyOf(path), hop.after(),
                route.positionDays, route.loopDays + trip.days(), route.profit + hop.load().profit(),
                route.fuel + trip.fuel(), route.supplies + trip.supplies());
    }
    private void consider(Route route) {
        if (route.hops.isEmpty()) return;
        Route complete = options.closedLoop() ? extend(route, route.start, true) : route;
        if (complete == null || complete.profit <= 0 || complete.profit < options.minProfit()) return;
        if (best == null) { best = complete; return; }
        double band = Math.max(.01, .0001 * Math.max(best.score(), complete.score()));
        if (complete.score() > best.score() + band || Math.abs(complete.score() - best.score()) <= band && complete.profit > best.profit) best = complete;
    }
    private double bound(Route route) {
        int remaining = Math.max(0, options.maxStops() - route.visited.size());
        double minDays = minEdgeDays == Double.MAX_VALUE ? .05 : minEdgeDays;
        int count = Math.min(remaining, (int) Math.floor(Math.max(0, options.maxDays() - route.days()) / minDays));
        double bestBound = route.score();
        for (int k = 1; k <= count + (options.closedLoop() ? 1 : 0); k++)
            bestBound = Math.max(bestBound, (route.profit + k * maxEdgeProfit) / Math.max(.05, route.loopDays + k * minDays + .5 * route.positionDays));
        return bestBound;
    }
    private Travel travel(Market from, Market to) {
        return travels.computeIfAbsent(key(from, to), ignored -> snapshot.travel(from == null ? snapshot.fleet().position() : from.position(), to.position()));
    }
    private static String key(Market from, Market to) { return (from == null ? "@fleet" : from.id()) + ">" + to.id(); }
    private void checkCancelled() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("跑商计算已取消"); }
    private void checkpoint() {
        checkCancelled();
        if (System.nanoTime() >= (phase <= 1 ? graphDeadline : deadline)) throw new BudgetEnd();
    }
    private void finish() {
        long now = System.nanoTime();
        if (graphFinished == 0) graphFinished = now;
        int edgeCount = graph.values().stream().mapToInt(List::size).sum();
        Metrics metrics = new Metrics(candidates.size(), starts.size(), rankedSources, cheapScores.size(), edgeCount,
                quotedPairs, prices.calls(), expanded, pruned,
                (graphFinished - started) / 1_000_000, (now - graphFinished) / 1_000_000, (now - started) / 1_000_000);
        if (best == null) {
            String explanation = limited ? "计算预算耗尽，本次搜索未得到可执行路线；不能据此判断不存在盈利路线或资金、货舱不足。"
                    : "在已精算的候选中未找到满足当前规划约束的盈利路线。";
            explanation += "起点 " + starts.size() + " 个，精算 " + quotedPairs + " 对市场、" + prices.calls()
                    + " 个报价、发现 " + edgeCount + " 条盈利候选航段。";
            if (limited && prices.calls() == 0) explanation += "尚未读取任何价格，应先检查计算阶段耗时。";
            result = new Result(null, 0, 0, 0, 0, snapshot.markets().size(), limited, explanation, metrics);
            return;
        }
        List<Step> steps = new ArrayList<>();
        steps.add(TradePlanSteps.move(best.start));
        for (Hop hop : best.hops) steps.add(TradePlanSteps.prepare(hop, options));
        String explanation = String.format(Locale.ROOT,
                "跑商路线：%d 段，预计贸易差价 %.0f 星币 / %.1f 天；燃料 %.1f、补给 %.1f。到站重算采购单。含关税，未扣航行消耗和后勤采购成本；有界启发式结果。精算 %d 对市场、%d 个报价，耗时 %d 毫秒。%s",
                best.hops.size(), best.profit, best.days(), best.fuel, best.supplies, quotedPairs, prices.calls(), metrics.totalMs(),
                limited ? "已达到计算预算。" : "");
        result = new Result(Plan.create("按计算结果执行跑商", steps), best.profit, best.days(), best.fuel,
                best.supplies, snapshot.markets().size(), limited, explanation, metrics);
    }
}
