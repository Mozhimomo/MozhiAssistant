package com.mozhi.fleet.trading;

import com.mozhi.fleet.actions.*;
import com.mozhi.fleet.model.Step;
import java.util.*;
import java.util.concurrent.CancellationException;
import static com.mozhi.fleet.trading.TradeSnapshot.*;

/** Pure snapshots: no network or live game assets. */
public final class TradeRouteChecks {
    private static final Point ORIGIN = new Point("system", false, 0, 0, 0, 0, 0);
    public static void main(String[] args) {
        batchPricesAndPlan();
        mixedGoodsAndLimits();
        multipleStopsAndClosure();
        travelAndReserves();
        invalidOptionsAndCancellation();
        System.out.println("Trade route checks passed");
    }
    private static Fleet fleet(double credits, double cargo, double fuelRoom, double crewRoom) {
        return new Fleet(ORIGIN, credits, cargo, fuelRoom, crewRoom, 100, 100, 1, 1, 2, 1000);
    }
    private static Quote quote(String id, Hold hold, String channel, Map<Integer, Double> buy, Map<Integer, Double> sell) {
        return new Quote(id, id, hold, 1, channel, buy, sell);
    }
    private static Quote buy(String id, int quantity, double cost) {
        return quote(id, Hold.CARGO, "open_market", Map.of(quantity, cost), Map.of());
    }
    private static Quote sell(String id, int quantity, double revenue) {
        return quote(id, Hold.CARGO, "open_market", Map.of(), Map.of(quantity, revenue));
    }
    private static Market market(String id, Quote... quotes) { return new Market(id, id, "entity-" + id, ORIGIN, List.of(quotes)); }
    private static TradeRouteCalculator.Result run(Fleet fleet, Map<String, Object> options, Market... markets) {
        return new TradeRouteCalculator(new TradeSnapshot(fleet, List.of(markets), 0), TradeRouteOptions.from(options)).calculate();
    }
    private static List<Step> buys(TradeRouteCalculator.Result result) {
        return result.plan().steps().stream().filter(step -> step.action().equals("BUY")).toList();
    }
    private static void batchPricesAndPlan() {
        Market a = market("a", quote("ore", Hold.CARGO, "source", Map.of(10, 10d, 20, 70d), Map.of()));
        Market b = market("b", quote("ore", Hold.CARGO, "destination", Map.of(), Map.of(10, 40d, 20, 80d)));
        var result = run(fleet(100, 100, 0, 0), Map.of(), a, b);
        check(result.expectedTradeProfit() == 30, "Nonlinear batch prices select 10, not maximum quantity 20");
        check(result.plan().steps().stream().map(Step::action).toList().equals(List.of("MOVE_TO", "BUY", "MOVE_TO", "SELL")), "Executable trade sequence");
        var specs = List.of(new BuyAction().spec(), new SellAction().spec(), new MoveToAction().spec());
        for (Step step : result.plan().steps()) specs.stream().filter(spec -> spec.name().equals(step.action())).findFirst().orElseThrow().validate(step.parameters());
        check(buys(result).get(0).parameters().get("submarketId").equals("source"), "Preserve source channel");
        check(result.plan().steps().get(3).parameters().get("submarketId").equals("destination"), "Preserve destination channel");
        check(result.plan().steps().get(0).parameters().get("destinationId").equals("entity-a"), "Navigate to actual entity ID");
        check(result.plan().steps().get(3).parameters().get("quantity").equals(10), "Sell only newly purchased quantity");
        check(run(fleet(100, 100, 0, 0), Map.of("minProfit", 31), a, b).plan() == null, "Minimum profit respected");
        check(run(fleet(100, 100, 0, 0), Map.of(), market("a", buy("ore", 10, 40)), market("b", sell("ore", 10, 39))).plan() == null,
                "No plan for a losing trade");
    }
    private static void mixedGoodsAndLimits() {
        Market a = market("a", buy("ore", 2, 20), buy("food", 3, 30),
                quote("fuel", Hold.FUEL, "open_market", Map.of(4, 10d), Map.of()),
                quote("crew", Hold.PERSONNEL, "open_market", Map.of(2, 10d), Map.of()));
        Market b = market("b", sell("ore", 2, 50), sell("food", 3, 60),
                quote("fuel", Hold.FUEL, "open_market", Map.of(), Map.of(4, 30d)),
                quote("crew", Hold.PERSONNEL, "open_market", Map.of(), Map.of(2, 30d)));
        var mixed = run(fleet(70, 5, 4, 2), Map.of(), a, b);
        check(buys(mixed).size() == 4 && mixed.expectedTradeProfit() == 100, "Mix commodities across independent cargo, fuel and personnel holds");
        var cargoOnly = run(fleet(70, 5, 0, 0), Map.of(), a, b);
        check(buys(cargoOnly).size() == 2 && cargoOnly.expectedTradeProfit() == 60, "Full tanks and berths do not block ordinary cargo");
        check(buys(run(fleet(100, 4, 0, 0), Map.of(), a, b)).size() == 1, "Combined cargo cannot exceed free space");
        check(buys(run(fleet(40, 5, 0, 0), Map.of(), a, b)).size() == 1, "Combined purchases cannot exceed cash");
        check(buys(run(fleet(100, 5, 0, 0), Map.of("maxSpend", 40), a, b)).size() == 1, "Per-hop spend cap applies to combined purchases");
        check(run(fleet(100, 5, 0, 0), Map.of("reserveCredits", 90), a, b).plan() == null, "Reserved credits cannot be spent");
        check(run(fleet(100, 1, 0, 0), Map.of(), a, b).plan() == null, "Do not fabricate a smaller quantity without an actual quote");
    }
    private static void multipleStopsAndClosure() {
        Market a = market("a", buy("ore", 5, 50));
        Market b = market("b", sell("ore", 5, 100), buy("food", 5, 100));
        Market c = market("c", sell("food", 5, 1000));
        var result = run(fleet(50, 5, 0, 0), Map.of("maxStops", 3), a, b, c);
        check(result.expectedTradeProfit() == 950 && buys(result).size() == 2, "Use realized sale proceeds to finance a later leg");
        check(result.plan().steps().stream().filter(step -> step.action().equals("MOVE_TO")).count() == 3, "Multi-stop route");
        var two = run(fleet(50, 5, 0, 0), Map.of("maxStops", 2), a, b, c);
        check(two.expectedTradeProfit() == 50, "Stop limit prevents a third market");
        var loop = run(fleet(50, 5, 0, 0), Map.of("closedLoop", true, "maxStops", 3), a, b, c);
        Step last = loop.plan().steps().get(loop.plan().steps().size() - 1);
        check(last.action().equals("MOVE_TO") && last.parameters().get("destinationId").equals("entity-a"), "Closed loop can return empty to first market");
        check(loop.estimatedDays() > result.estimatedDays(), "Closing travel counts in score and resources");
    }
    private static void travelAndReserves() {
        var far = new Point("other", false, 0, 0, 4, 0, 1000);
        Market a = market("a", buy("ore", 2, 10));
        Market b = new Market("b", "b", "entity-b", far, List.of(sell("ore", 2, 30)));
        var result = run(fleet(100, 10, 0, 0), Map.of(), a, b);
        check(result.fuel() == 4 && result.estimatedDays() == 3.5 && result.supplies() == 3.5, "Hyper and local approach travel plus docking are accounted");
        check(run(fleet(100, 10, 0, 0), Map.of("maxDays", 3), a, b).plan() == null, "Reject excessive travel duration");
        check(run(fleet(100, 10, 0, 0), Map.of("reserveFuel", 97), a, b).plan() == null, "Fuel reserve retained");
        check(run(fleet(100, 10, 0, 0), Map.of("reserveSupplies", 97), a, b).plan() == null, "Supply reserve retained");
        Market sourceFar = new Market("far", "far", "entity-far", far, List.of(buy("ore", 2, 10)));
        check(run(fleet(100, 10, 0, 0), Map.of("maxStartDistanceLy", 3), sourceFar, market("home", sell("ore", 2, 30))).plan() == null,
                "First market distance limit applied");
    }
    private static void invalidOptionsAndCancellation() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("maxStops", 1), Map.of("maxStops", 2.5),
                Map.of("maxDays", Double.NaN), Map.of("maxSpend", -1), Map.of("reserveCredits", -1),
                Map.of("commodityIds", List.of(1)), Map.of("allowBlackMarket", "true"))) {
            try { TradeRouteOptions.from(bad); throw new AssertionError("Accepted invalid options: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        for (int cap : List.of(1, 8, 19, 1000, 1_000_000)) {
            var quantities = TradeSnapshot.quantities(cap);
            check(quantities.get(0) == 1 && quantities.get(quantities.size() - 1) == cap, "Sample endpoints");
            check(new HashSet<>(quantities).size() == quantities.size() && quantities.size() < 50, "Bounded unique quote samples");
        }
        Thread.currentThread().interrupt();
        try {
            run(fleet(100, 10, 0, 0), Map.of(), market("a", buy("ore", 1, 1)), market("b", sell("ore", 1, 2)));
            throw new AssertionError("Calculator ignored cancellation");
        } catch (CancellationException expected) { }
        finally { Thread.interrupted(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
