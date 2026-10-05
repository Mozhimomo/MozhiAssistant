package com.mozhi.fleet.trading;

import com.mozhi.fleet.actions.*;
import com.mozhi.fleet.model.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static com.mozhi.fleet.trading.TradeSnapshot.*;

/** 验证算法结果、报价数量、预算、到站计划与资源会计，不访问网络或真实资产。 */
public final class TradeRouteChecks {
    private static final Point ORIGIN = new Point("system", false, 0, 0, 0, 0, 0);
    private static final TradeRouteOptions DEFAULT = TradeRouteOptions.from(Map.of());
    public static void main(String[] args) throws Exception {
        nonlinearPrices();
        channelsAndCapacity();
        routesAndClosure();
        logisticsAndPlanInsertion();
        constraintsAndCancellation();
        boundedSearch();
        manyStartingMarkets();
        System.out.println("跑商算法与到站采购单检查通过");
    }
    private static Fleet fleet(double credits, double cargo, double fuelRoom) {
        return new Fleet(ORIGIN, credits, cargo, fuelRoom, 0, 100, 100, 1, 1, 2, 1000);
    }
    private static Quote row(String id, boolean black, int cap, boolean sell) {
        return new Quote(id, id, id.equals("fuel") ? Hold.FUEL : Hold.CARGO, 1, black ? "black_market" : "open_market",
                Map.of(), Map.of(), cap, 1, cap > 0 ? 10 : 0, sell ? 10 : 0, cap > 0, sell);
    }
    private static Market market(String id, double x, Quote... rows) {
        return new Market(id, id, "entity-" + id, new Point("system", false, x, 0, 0, 0, 0), List.of(rows));
    }
    private static TradeRouteCalculator.Result run(Fleet fleet, TradeRouteOptions options, TradeQuotes quotes, Market... markets) {
        return new TradeRouteCalculator(new TradeSnapshot(fleet, List.of(markets), 0), options, quotes, 2000).calculate();
    }
    private static void nonlinearPrices() {
        Market a = market("a", 0, row("ore", false, 20, false));
        Market b = market("b", 1000, row("ore", false, 0, true));
        TradeQuotes prices = (m, q, n, buy) -> buy ? n <= 10 ? n : 10 + (n - 10) * 6 : n * 4;
        var pack = new TradeCargoPacker(new TradeQuotes.Cached(prices), DEFAULT, () -> {}).pack(a, b, 100, 100, 0, Map.of());
        check(pack.profit() == 30 && pack.lots().get(0).quantity() == 10, "按非线性整批利润选择十件，不追求最大数量");
        var result = run(fleet(100, 100, 0), DEFAULT, prices, a, b);
        check(result.expectedTradeProfit() == 30, "路线收益使用整批报价");
        check(result.plan().steps().stream().map(Step::action).toList().equals(List.of("MOVE_TO", "PREPARE_TRADE_HOP")), "路线保留到站重算步骤");
        var prepared = result.plan().steps().get(1);
        check(prepared.parameters().get("marketId").equals("a") && prepared.parameters().get("destinationMarketId").equals("b"), "保留确定的贸易行程");
        check(run(fleet(100, 100, 0), TradeRouteOptions.from(Map.of("minProfit", 31)), prices, a, b).plan() == null, "遵守最低利润");
        TradeQuotes loss = (m, q, n, buy) -> n * (buy ? 10 : 9);
        check(run(fleet(100, 100, 0), DEFAULT, loss, a, b).plan() == null, "不生成亏损路线");
    }
    private static void channelsAndCapacity() {
        Market a = market("a", 0, row("ore", true, 3, false), row("ore", false, 20, false), row("fuel", false, 20, false));
        Market b = market("b", 1000, row("ore", true, 0, true), row("ore", false, 0, true), row("fuel", false, 0, true));
        TradeQuotes prices = (m, q, n, buy) -> n * (buy ? q.black() ? 1 : 2 : q.black() ? 5 : 4);
        var packer = new TradeCargoPacker(new TradeQuotes.Cached(prices), DEFAULT, () -> {});
        var load = packer.pack(a, b, 10, 100, 4, Map.of());
        check(load.cargo() == 10 && load.fuel() == 4, "燃料使用油箱，货物使用货舱");
        check(load.lots().get(0).buy().black() && load.lots().get(0).quantity() == 3, "先使用黑市库存");
        check(load.lots().stream().anyMatch(l -> !l.buy().black() && l.buy().commodityId().equals("ore") && l.quantity() == 7), "开放市场填充余量");
        var noBlack = new TradeCargoPacker(prices, TradeRouteOptions.from(Map.of("allowBlackMarket", false)), () -> {}).pack(a, b, 10, 100, 0, Map.of());
        check(noBlack.lots().stream().noneMatch(l -> l.buy().black() || l.sell().black()), "关闭黑市同时影响买卖两端");
        var poor = packer.pack(a, b, 10, 5, 0, Map.of());
        check(poor.cost() <= 5 && poor.cargo() <= 10, "多渠道总采购不超过预算和空间");
        check(packer.affordable(a, row("ore", false, 100, false), 100, 37) == 18, "使用二分查找资金能承担的数量");
        var cache = new TradeQuotes.Cached(prices);
        cache.quote(a, a.quotes().get(0), 3, true); cache.quote(a, a.quotes().get(0), 3, true);
        check(cache.calls() == 1, "本次计算重复整批报价只查询一次");
    }
    private static void routesAndClosure() {
        Market a = market("a", 0, row("ore", false, 10, false));
        Market b = new Market("b", "b", "entity-b", new Point("second", false, 0, 0, 2, 0, 0), List.of(row("ore", false, 0, true), row("food", false, 10, false)));
        Market c = new Market("c", "c", "entity-c", new Point("third", false, 0, 0, 4, 0, 0), List.of(row("food", false, 0, true)));
        TradeQuotes prices = (m, q, n, buy) -> n * (buy ? 1 : q.commodityId().equals("food") ? 20 : 2);
        var result = run(fleet(100, 10, 0), TradeRouteOptions.from(Map.of("maxStartDistanceLy", 0)), prices, a, b, c);
        check(result.plan().steps().stream().filter(s -> s.action().equals("PREPARE_TRADE_HOP")).count() == 2, "资金滚动进入下一站高利润交易");
        var loop = run(fleet(100, 10, 0), TradeRouteOptions.from(Map.of("closedLoop", true, "maxStartDistanceLy", 0)), prices, a, b, c);
        var last = loop.plan().steps().get(loop.plan().steps().size() - 1);
        check(last.parameters().get("destinationMarketId").equals("a"), "闭环可空载回第一个市场");
        check(loop.estimatedDays() > result.estimatedDays(), "闭环计入返程时间");
        check(loop.plan().steps().stream().noneMatch(s -> s.action().equals("RETURN")), "闭环不获得返航合并权限");
    }
    private static void logisticsAndPlanInsertion() {
        Fleet fleet = new Fleet(ORIGIN, 1000, 30, 15, 0, 5, 2, 1, 1, 2, 1000);
        Market a = market("a", 0, row("ore", false, 30, false), row("fuel", true, 30, false), row("supplies", true, 30, false));
        Market b = new Market("b", "b", "entity-b", new Point("other", false, 0, 0, 10, 0, 0),
                List.of(row("ore", false, 0, true)));
        var options = TradeRouteOptions.from(Map.of("reserveFuel", 2, "reserveSupplies", 1, "commodityIds", List.of("ore")));
        TradeQuotes prices = (m, q, n, buy) -> n * (buy ? 1 : 5);
        var snapshot = new TradeSnapshot(fleet, List.of(a, b), 0);
        var planner = new TradeHopPlanner(snapshot, options, new TradeQuotes.Cached(prices), () -> {});
        var hop = planner.plan(a, b, planner.initial(), false);
        check(hop != null && hop.purchases().size() == 2, "缺少航行物资时生成具体补油补给采购");
        check(hop.after().fuel() == 2 && hop.after().supplies() == 1, "采购考虑路上消耗和本次指定保留量");
        check(hop.purchases().stream().filter(p -> p.quote().commodityId().equals("fuel")).findFirst().orElseThrow().quantity() == 7, "补油数量由当前缺口计算");
        var plan = TradePlanSteps.executable(hop);
        check(plan.steps().stream().filter(s -> s.action().equals("BUY")).count() == 3, "后勤与贸易采购均写进 Plan");
        check(hop.after().cash() == fleet.credits() - hop.purchases().stream().mapToDouble(TradeHopPlanner.Purchase::cost).sum() + hop.load().profit(), "采购扣款与贸易现金流分别入账");
        Step calc = Step.create("CALCULATE_TRADE_ROUTE", Map.of(), "计算", "路线");
        Step tail = Step.create("MOVE_TO", Map.of("destinationId", "original-tail"), "后续工作", "完成");
        Step prepare = TradePlanSteps.prepare(hop, options);
        Plan parent = Plan.create("原任务", List.of(calc, prepare, tail));
        Plan expanded = parent.insertAfter(1, plan);
        check(expanded.goal().equals("原任务") && expanded.steps().get(expanded.steps().size() - 1).equals(tail), "采购单插入决策步骤后且保留原后续步骤");
        var specs = List.of(new BuyAction().spec(), new SellAction().spec(), new MoveToAction().spec(), new PrepareTradeHopAction().spec());
        for (Step step : expanded.steps().subList(1, expanded.steps().size()))
            specs.stream().filter(s -> s.name().equals(step.action())).findFirst().orElseThrow().validate(step.parameters());
        var empty = new TradeHopPlanner(snapshot, options, (m, q, n, buy) -> n, () -> {}).plan(a, b, planner.initial(), true);
        check(empty != null && empty.load().lots().isEmpty(), "行情变化后允许空载，不使用旧买单");
        check(TradePlanSteps.executable(empty).steps().stream().noneMatch(s -> s.action().equals("SELL")), "空载航段不出售原有资产");
    }
    private static void constraintsAndCancellation() throws Exception {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("maxStops", 1), Map.of("maxStops", 2.5),
                Map.of("maxDays", Double.NaN), Map.of("maxSpend", -1), Map.of("reserveCredits", -1),
                Map.of("commodityIds", List.of(1)), Map.of("allowBlackMarket", "true"))) {
            try { TradeRouteOptions.from(bad); throw new AssertionError("接受了无效参数"); } catch (IllegalArgumentException expected) {}
        }
        for (int cap : List.of(1, 19, 1000, 1_000_000)) {
            var values = TradeSnapshot.quantities(cap);
            check(values.get(0) == 1 && values.get(values.size() - 1) == cap && values.size() <= 13, "数量样本有界且包含端点");
        }
        Market a = market("a", 0, row("ore", false, 20, false)), b = market("b", 1000, row("ore", false, 0, true));
        TradeQuotes prices = (m, q, n, buy) -> n * (buy ? 1 : 5);
        check(run(fleet(10, 20, 0), TradeRouteOptions.from(Map.of("reserveCredits", 10)), prices, a, b).plan() == null, "保留资金不能用于采购");
        check(run(fleet(100, 20, 0), TradeRouteOptions.from(Map.of("maxDays", .5)), prices, a, b).plan() == null, "拒绝超时行程");
        Market far = new Market("far", "far", "far", new Point("far-system", false, 0, 0, 20, 0, 0), a.quotes());
        check(run(fleet(100, 20, 0), TradeRouteOptions.from(Map.of("maxStartDistanceLy", 1)), prices, far, b).plan() == null, "先筛选首站范围");
        var calculator = new TradeRouteCalculator(new TradeSnapshot(fleet(100, 20, 0), List.of(a, b), 0), DEFAULT, prices, 2000);
        calculator.cancel();
        try { calculator.calculate(); throw new AssertionError("未响应取消"); } catch (CancellationException expected) {}
        var paused = new TradeRouteCalculator(new TradeSnapshot(fleet(100, 20, 0), List.of(a, b), 0), DEFAULT, prices, 100);
        check(!paused.advance(1), "先执行轻量初始化后让出当前帧");
        paused.pause(); Thread.sleep(150);
        var resumed = paused.calculate();
        check(resumed.plan() != null && !resumed.searchLimited() && resumed.metrics().totalMs() < 100, "游戏暂停不耗尽计算预算");
        check(TradeSnapshotCollector.project(0, 30, 15) == 15 && TradeSnapshotCollector.project(60, 30, 15) == 30, "已访问货架按补货和回落速率估算");
    }
    private static void boundedSearch() {
        List<Market> markets = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            List<Quote> goods = new ArrayList<>();
            for (String id : List.of("ore", "food", "metals", "supplies", "fuel", "organics", "rare_metals", "hand_weapons",
                    "drugs", "organs", "luxury_goods", "domestic_goods", "heavy_machinery", "volatiles", "rare_ore"))
                for (boolean black : List.of(false, true)) goods.add(row(id, black, 1000, true));
            markets.add(new Market("m" + i, "市场" + i, "e" + i,
                    new Point("s" + i, false, 0, 0, i * 2, 0, 0), goods));
        }
        TradeQuotes prices = (m, q, n, buy) -> n * (buy ? 10 + Integer.parseInt(m.id().substring(1)) % 5 : 12 + Integer.parseInt(m.id().substring(1)) % 7);
        var calculator = new TradeRouteCalculator(new TradeSnapshot(fleet(10000, 1000, 100), markets, 0), DEFAULT, prices, 2000);
        while (!calculator.advance(4_000_000)) {}
        var result = calculator.result();
        check(result.plan() != null, "九十市场场景能找到路线");
        check(result.metrics().pairs() <= 6 * 48 + 90 * 16 && result.metrics().pairs() < 90 * 89, "只精算有限出边，未构建完整报价图");
        check(result.metrics().totalMs() < 3000, "时间预算覆盖精确报价与搜索");
        System.out.println("九十市场离线统计：" + result.metrics());
        var shortBudget = new TradeRouteCalculator(new TradeSnapshot(fleet(10000, 1000, 100), markets, 0), DEFAULT, prices, 1).calculate();
        check(shortBudget.searchLimited(), "短预算明确报告截断");
        check(shortBudget.plan() != null || shortBudget.explanation().contains("不能据此判断"), "超时不能误报不存在盈利路线或资源不足");
    }
    private static void manyStartingMarkets() throws Exception {
        List<Market> markets = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            List<Quote> goods = new ArrayList<>();
            for (String id : List.of("ore", "food", "metals", "supplies", "fuel", "organics", "rare_metals", "hand_weapons",
                    "drugs", "organs", "luxury_goods", "domestic_goods", "heavy_machinery", "volatiles", "rare_ore"))
                for (boolean black : List.of(false, true)) goods.add(row(id, black, 400, true));
            markets.add(new Market("m" + i, "市场" + i, "e" + i,
                    new Point("s" + i, false, 0, 0, i * .15, 0, 0), goods));
        }
        var fleet = new Fleet(ORIGIN, 169977, 1902, 0, 0, 800, 2000, 21, 2, 2, 1000);
        var options = TradeRouteOptions.from(Map.of("maxSpend", 140000, "reserveCredits", 29977,
                "reserveFuel", 120, "reserveSupplies", 30, "maxStartDistanceLy", 30));
        TradeQuotes prices = (m, q, n, buy) -> n * (buy ? 10 + Integer.parseInt(m.id().substring(1)) % 5 : 12 + Integer.parseInt(m.id().substring(1)) % 7);
        var calculator = new TradeRouteCalculator(new TradeSnapshot(fleet, markets, 0), options, prices, 2000);
        long began = System.nanoTime();
        while (!calculator.advance(4_000_000)) Thread.sleep(16);
        var result = calculator.result();
        check(result.metrics().starts() == 99 && result.metrics().quotes() > 0 && result.metrics().edges() > 0,
                "全部九十九个市场进入起点池时仍能报价并建立盈利航段");
        check(result.plan() != null, "分帧调度和返程物资预留不导致空图假失败");
        check(result.metrics().cheapPairs() <= 99 * 98, "每对市场只粗算一次，排序比较不重复扫描商品");
        check((System.nanoTime() - began) / 1_000_000 < 3000, "真实帧间等待下仍遵守整体软预算");
        check(result.metrics().pairs() < 99 * 98, "扩大起点范围不退化为全图精算");
        System.out.println("九十九起点分帧回归统计：" + result.metrics());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
