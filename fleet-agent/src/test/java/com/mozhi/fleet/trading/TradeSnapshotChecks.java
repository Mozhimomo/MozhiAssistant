package com.mozhi.fleet.trading;

import com.fs.starfarer.api.*;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.fleet.FleetLogisticsAPI;
import com.fs.starfarer.api.util.MutableValue;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.execution.Executor;
import com.mozhi.fleet.model.*;
import com.mozhi.fleet.planning.ExecutionHistory;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.lwjgl.util.vector.Vector2f;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** Exercises real collector/action with game API proxies that reject any background access. */
public final class TradeSnapshotChecks {
    private static final Thread OWNER = Thread.currentThread();
    public static void main(String[] args) throws Exception {
        collectionAndChannels();
        decisionAction();
        resourceMarketInformation();
        System.out.println("Trade snapshot / asynchronous action checks passed");
    }
    private static TradeSnapshot collect(World world, Map<String, Object> params) {
        TradeSnapshotCollector collector = new TradeSnapshotCollector(world.context, TradeRouteOptions.from(params));
        int frames = 1;
        while (!collector.advance()) check(++frames < 200, "Collection must finish");
        check(frames > 1, "Market quotes collected across several frames");
        return collector.snapshot();
    }
    private static void collectionAndChannels() throws Exception {
        World world = new World();
        TradeSnapshot snapshot = collect(world, Map.of("allowBlackMarket", false));
        check(snapshot.markets().size() == 2, "Collect both economy markets");
        for (var market : snapshot.markets()) {
            check(market.quotes().size() == 1 && market.quotes().get(0).submarketId().equals("open_market"), "Explicit opt-out excludes black market");
            check(market.quotes().get(0).buys().keySet().stream().allMatch(q -> q <= 7), "No buy quote exceeds actual stock");
        }
        var result = new TradeRouteCalculator(snapshot, TradeRouteOptions.from(Map.of())).calculate();
        check(result.expectedTradeProfit() == 28, "Exact rounded tariffs shared with trade execution (84 cost, 112 revenue)");
        var black = new TradeRouteCalculator(collect(world, Map.of()), TradeRouteOptions.from(Map.of())).calculate();
        check(black.expectedTradeProfit() == 70, "Default calculation includes untaxed black-market channels");
        TradeSnapshotCollector incomplete = new TradeSnapshotCollector(world.context, TradeRouteOptions.from(Map.of()));
        try { incomplete.snapshot(); throw new AssertionError("Exposed partial snapshot"); } catch (IllegalStateException expected) { }
        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Thread background = new Thread(() -> { try { incomplete.advance(); } catch (Throwable error) { crossThread.set(error); } });
        background.start(); background.join(2000);
        check(crossThread.get() instanceof IllegalStateException, "Collector enforces owner thread before reading APIs");
        for (String restriction : List.of("hidden", "hostile", "disabled", "free", "illegal", "broken")) {
            World blocked = new World(); blocked.restriction = restriction;
            var collector = new TradeSnapshotCollector(blocked.context, TradeRouteOptions.from(Map.of()));
            while (!collector.advance()) { }
            check(collector.snapshot().markets().isEmpty(), "Exclude inaccessible or unreadable channels: " + restriction);
        }
        World unknown = new World();
        try {
            new TradeSnapshotCollector(unknown.context, TradeRouteOptions.from(Map.of("commodityIds", List.of("missing"))));
            throw new AssertionError("Unknown commodity accepted");
        } catch (IllegalArgumentException expected) { }
    }
    private static void decisionAction() throws Exception {
        World world = new World();
        ExecutionHistory history = new ExecutionHistory();
        Executor executor = new Executor(world.context, history);
        Step step = Step.create("CALCULATE_TRADE_ROUTE", Map.of("commodityIds", List.of("ore")), "计算", "路线");
        world.paused = true;
        check(executor.execute(step).status() == WAITING && world.priceCalls == 0, "Paused calculation does not collect or compute");
        world.paused = false;
        ExecutionResult result = executor.execute(step);
        check(result.status() == RUNNING && result.generatedPlan() == null, "Decision initially runs asynchronously");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Executor.State saved = json.readValue(json.writeValueAsString(executor.snapshot()), Executor.State.class);
        executor.stop();
        history = new ExecutionHistory();
        executor = new Executor(world.context, history);
        executor.restore(saved);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (result.status() == RUNNING) {
            check(System.nanoTime() < deadline, "Decision timed out");
            result = executor.execute(step); Thread.sleep(2);
        }
        check(result.status() == SUCCEEDED && result.generatedPlan() != null, "Actual action restarts unfinished calculation after load and returns Plan");
        executor.validatePlan(result.generatedPlan());
        check(executor.execute(step).equals(result), "Terminal cache returns the same generated plan and step identities");
        check(history.snapshot().recentResults().size() == 1 && world.credits.get() == 1000, "Calculation records one result and does not trade");
        executor.cancelBackground();
        check(executor.backgroundStopped(), "Background task releases lifecycle after completion");
        World noProfit = new World(); noProfit.sellUnitPrice = 5;
        Executor rejected = new Executor(noProfit.context, new ExecutionHistory());
        result = rejected.execute(step);
        deadline = System.nanoTime() + 5_000_000_000L;
        while (result.status() == RUNNING) {
            check(System.nanoTime() < deadline, "No-profit calculation timed out");
            result = rejected.execute(step); Thread.sleep(2);
        }
        check(result.status() == FAILED && result.generatedPlan() == null, "No profitable route is a failed decision with no invented plan");
    }
    private static void resourceMarketInformation() {
        World world = new World();
        world.fuel = 10; world.supplies = 20; world.crew = 4;
        var resources = com.mozhi.fleet.game.GameWorld.resources(world.context.fleet());
        var check = new com.mozhi.fleet.execution.Monitor().checkResources(resources);
        var info = com.mozhi.fleet.game.ResourceMarkets.collect(world.context, check);
        var candidates = (List<?>) info.get("candidates");
        check(candidates.size() == 4, "Resupply sees real open and black market channels");
        boolean black = false;
        for (Object raw : candidates) {
            var row = (Map<?, ?>) raw;
            black |= Boolean.TRUE.equals(row.get("blackMarket"));
            check(row.get("destinationId") != null && ((Number) row.get("fuelToReach")).doubleValue() == 0, "Expose executable target and reachability");
            List<?> goods = (List<?>) row.get("goods");
            check(goods.size() == 3, "All three actual resource inventories are available for planning");
            var fuel = (Map<?, ?>) goods.stream().filter(g -> ((Map<?, ?>) g).get("itemId").equals("fuel")).findFirst().orElseThrow();
            check(((Number) fuel.get("targetNeededOnArrival")).intValue() == 90
                    && ((Number) fuel.get("quotedQuantity")).intValue() == 90, "Quote full-tank recovery instead of 15-LY threshold");
            var supplies = (Map<?, ?>) goods.stream().filter(g -> ((Map<?, ?>) g).get("itemId").equals("supplies")).findFirst().orElseThrow();
            check(((Number) supplies.get("targetNeededOnArrival")).intValue() == 26, "Purchase reaches 45 days and accounts for travel consumption");
        }
        check(black, "Black market included in resupply search");
        world.restriction = "disabled";
        check(((List<?>) com.mozhi.fleet.game.ResourceMarkets.collect(world.context, check).get("candidates")).isEmpty(), "Disabled channels cannot support recovery");
        world.restriction = ""; world.supplies = 0;
        var stranded = new com.mozhi.fleet.execution.Monitor().checkResources(com.mozhi.fleet.game.GameWorld.resources(world.context.fleet()));
        check(((List<?>) com.mozhi.fleet.game.ResourceMarkets.collect(world.context, stranded).get("candidates")).isEmpty(), "Cannot suggest a route with insufficient travel supplies");
    }
    private static final class World {
        final MutableValue credits = new MutableValue(1000);
        final List<MarketAPI> markets = new ArrayList<>();
        final ActionContext context;
        boolean paused;
        String restriction = "";
        int priceCalls;
        float sellUnitPrice = 20;
        float fuel = 100, supplies = 100;
        int crew = 10;
        World() {
            List<CommoditySpecAPI> goods = new ArrayList<>();
            SettingsAPI settings = proxy(SettingsAPI.class, (m, a) -> switch (m) {
                case "getAllCommoditySpecs" -> goods;
                case "getCommoditySpec" -> proxy(CommoditySpecAPI.class, (name, params) -> name.equals("getCargoSpace") ? 1f : null);
                case "getFloat", "getUnitsPerLightYear" -> 1000f;
                case "getSpeedPerBurnLevel", "getBaseTravelSpeed" -> 10f; default -> null;
            });
            Global.setSettings(settings);
            goods.add(proxy(CommoditySpecAPI.class, (m, a) -> switch (m) {
                case "getId", "getName" -> "ore"; case "getCargoSpace" -> 1f; default -> null;
            }));
            LocationAPI location = proxy(LocationAPI.class, (m, a) -> switch (m) {
                case "getId" -> "system"; case "getAllEntities", "getEntities" -> List.of(); default -> null;
            });
            FactionAPI faction = proxy(FactionAPI.class, (m, a) -> m.equals("isHostileTo") ? restriction.equals("hostile") : null);
            CargoAPI cargo = proxy(CargoAPI.class, (m, a) -> switch (m) {
                case "getCredits" -> credits;
                case "getSpaceLeft" -> 20f; case "getFuel" -> fuel; case "getSupplies" -> supplies; case "getCrew" -> crew;
                case "getMaxFuel" -> 100f; case "getFreeFuelSpace" -> (int) (100 - fuel); case "getFreeCrewSpace" -> 30; default -> null;
            });
            FleetDataAPI data = proxy(FleetDataAPI.class, (m, a) -> switch (m) {
                case "getTravelSpeed" -> 100f; case "getBurnLevel" -> 10f; case "getMinCrew" -> 10f; default -> null;
            });
            FleetLogisticsAPI logistics = proxy(FleetLogisticsAPI.class, (m, a) -> 1f);
            CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (m, a) -> switch (m) {
                case "getContainingLocation" -> location; case "getLocation", "getLocationInHyperspace" -> new Vector2f();
                case "getCargo" -> cargo; case "getFleetData" -> data; case "getLogistics" -> logistics; default -> null;
            });
            for (String id : List.of("a", "b")) {
                SectorEntityToken entity = proxy(SectorEntityToken.class, (m, a) -> switch (m) {
                    case "getId" -> "entity-" + id; case "getContainingLocation" -> location;
                    case "getLocation", "getLocationInHyperspace" -> new Vector2f(); default -> null;
                });
                List<SubmarketAPI> shops = new ArrayList<>();
                for (boolean black : List.of(false, true)) {
                    SubmarketPlugin plugin = proxy(SubmarketPlugin.class, (m, a) -> switch (m) {
                        case "isParticipatesInEconomy" -> true; case "isEnabled" -> !restriction.equals("disabled");
                        case "getOnClickAction" -> SubmarketPlugin.OnClickAction.OPEN_SUBMARKET;
                        case "isBlackMarket" -> black; case "isFreeTransfer" -> restriction.equals("free");
                        case "isIllegalOnSubmarket" -> restriction.equals("illegal"); default -> null;
                    });
                    CargoAPI stock = proxy(CargoAPI.class, (m, a) -> m.equals("getCommodityQuantity") ? a[0].equals("ore") ? 7f : 200f : null);
                    shops.add(proxy(SubmarketAPI.class, (m, a) -> switch (m) {
                        case "getSpecId" -> black ? "black_market" : "open_market"; case "getPlugin" -> plugin;
                        case "getCargo" -> stock; case "getTariff" -> black ? 0f : 0.2f; default -> null;
                    }));
                }
                markets.add(proxy(MarketAPI.class, (m, a) -> switch (m) {
                    case "getId", "getName" -> id; case "getPrimaryEntity" -> entity; case "getFaction" -> faction;
                    case "getSubmarketsCopy" -> shops; case "isHidden" -> restriction.equals("hidden");
                    case "getSupplyPrice", "getDemandPrice" -> {
                        priceCalls++;
                        if (restriction.equals("broken")) throw new IllegalStateException("unavailable price");
                        check(Boolean.TRUE.equals(a[2]), "Use same player-price semantics as executor");
                        double unit = m.equals("getSupplyPrice") ? id.equals("a") ? 10 : 300 : id.equals("b") ? sellUnitPrice : 1;
                        yield (float) (((Number) a[1]).doubleValue() * unit);
                    }
                    default -> null;
                }));
            }
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (m, a) -> m.equals("getSecondsPerDay") ? 10f : null);
            EconomyAPI economy = proxy(EconomyAPI.class, (m, a) -> m.equals("getMarketsCopy") ? markets : null);
            CampaignFleetAPI player = proxy(CampaignFleetAPI.class, (m, a) -> null);
            SectorAPI sector = proxy(SectorAPI.class, (m, a) -> switch (m) {
                case "getClock" -> clock; case "getEconomy" -> economy; case "getAllLocations" -> List.of(location);
                case "getPlayerFleet" -> player; case "getPlayerFaction" -> faction; case "isPaused" -> paused; default -> null;
            });
            Global.setSettings(settings); Global.setSector(sector);
            context = new ActionContext(sector, fleet, settings, proxy(FactoryAPI.class, (m, a) -> null));
        }
    }
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            check(Thread.currentThread() == OWNER, "Background computation accessed a game object: " + type + "." + method.getName());
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> instance == args[0]; case "hashCode" -> System.identityHashCode(instance); default -> type.getSimpleName();
            };
            Object result = handler.apply(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive() || method.getReturnType() == void.class) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == long.class) return 0L;
            return 0;
        }));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
