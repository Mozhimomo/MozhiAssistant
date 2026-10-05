package com.mozhi.fleet.trading;

import com.fs.starfarer.api.*;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.ai.*;
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

/** 使用游戏 API 代理验证真实采集器与动作，拒绝所有后台线程访问。 */
public final class TradeSnapshotChecks {
    private static final Thread OWNER = Thread.currentThread();
    public static void main(String[] args) throws Exception {
        collectionAndChannels();
        decisionAction();
        liveRepack();
        resourceMarketInformation();
        System.out.println("跑商快照、分帧计算与到站动作检查通过");
    }
    private static TradeSnapshot collect(World world, Map<String, Object> params) {
        TradeSnapshotCollector collector = new TradeSnapshotCollector(world.context, TradeRouteOptions.from(params));
        int frames = 1;
        while (!collector.advance()) check(++frames < 200, "快照采集必须完成");
        return collector.snapshot();
    }
    private static void collectionAndChannels() throws Exception {
        World world = new World();
        TradeSnapshot snapshot = collect(world, Map.of("allowBlackMarket", false));
        check(snapshot.markets().size() == 2, "采集两个经济市场");
        for (var market : snapshot.markets()) {
            check(market.quotes().size() == 1 && market.quotes().get(0).submarketId().equals("open_market"), "明确关闭黑市时排除黑市");
            check(market.quotes().get(0).buyCap() == 7 && market.quotes().get(0).buys().isEmpty(), "从经济供需估算库存，不预先报价");
        }
        check(world.priceCalls == 0 && world.shelfReads == 0 && world.shelfUpdates == 0, "轻量扫描不刷新货架、不读真实库存、不查询报价");
        var openCollector = new TradeSnapshotCollector(world.context, TradeRouteOptions.from(Map.of("allowBlackMarket", false)));
        while (!openCollector.advance()) {}
        var result = new TradeRouteCalculator(openCollector.snapshot(), TradeRouteOptions.from(Map.of("allowBlackMarket", false)), openCollector.prices(), 2000).calculate();
        check(result.expectedTradeProfit() == 28, "与交易执行共用准确的含税取整报价：成本 84，收入 112");
        var blackCollector = new TradeSnapshotCollector(world.context, TradeRouteOptions.from(Map.of()));
        while (!blackCollector.advance()) {}
        var black = new TradeRouteCalculator(blackCollector.snapshot(), TradeRouteOptions.from(Map.of()), blackCollector.prices(), 2000).calculate();
        check(black.expectedTradeProfit() == 126, "先黑市、后开放市场采购，默认包含免税黑市卖出");
        check(world.shelfReads == 0 && world.shelfUpdates == 0, "精确报价也不刷新远方货架");
        TradeSnapshotCollector incomplete = new TradeSnapshotCollector(world.context, TradeRouteOptions.from(Map.of()));
        try { incomplete.snapshot(); throw new AssertionError("错误地公开了不完整快照"); } catch (IllegalStateException expected) { }
        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Thread background = new Thread(() -> { try { incomplete.advance(); } catch (Throwable error) { crossThread.set(error); } });
        background.start(); background.join(2000);
        check(crossThread.get() instanceof IllegalStateException, "采集器在读取 API 前校验所属线程");
        for (String restriction : List.of("hidden", "disabled", "free", "illegal", "noPort")) {
            World blocked = new World(); blocked.restriction = restriction;
            var collector = new TradeSnapshotCollector(blocked.context, TradeRouteOptions.from(Map.of()));
            while (!collector.advance()) { }
            check(collector.snapshot().markets().isEmpty(), "排除无权限或不可读取的渠道：" + restriction);
        }
        World hostile = new World(); hostile.restriction = "hostile";
        check(collect(hostile, Map.of()).markets().stream().allMatch(m -> m.quotes().stream().allMatch(TradeSnapshot.Quote::black)), "敌对市场仍可考虑可用黑市");
        World broken = new World(); broken.restriction = "broken";
        var brokenCollector = new TradeSnapshotCollector(broken.context, TradeRouteOptions.from(Map.of()));
        while (!brokenCollector.advance()) {}
        check(new TradeRouteCalculator(brokenCollector.snapshot(), TradeRouteOptions.from(Map.of()), brokenCollector.prices(), 2000).calculate().plan() == null, "报价失败不能变成免费或盈利交易");
        World unknown = new World();
        try {
            new TradeSnapshotCollector(unknown.context, TradeRouteOptions.from(Map.of("commodityIds", List.of("missing"))));
            throw new AssertionError("错误地接受了未知商品");
        } catch (IllegalArgumentException expected) { }
    }
    private static void liveRepack() throws Exception {
        World world = new World();
        world.stock = 2;
        var executor = new Executor(world.context, new ExecutionHistory());
        var step = Step.create("PREPARE_TRADE_HOP", Map.of("marketId", "a", "destinationMarketId", "b", "options", Map.of("commodityIds", List.of("ore"))), "重算", "采购单");
        var result = executor.execute(step);
        check(result.status() == FAILED && world.shelfReads == 0, "未到站不能打开货架或生成采购");
        world.orbit = world.markets.get(0).getPrimaryEntity();
        executor = new Executor(world.context, new ExecutionHistory());
        result = executor.execute(step);
        check(result.status() == SUCCEEDED && result.generatedPlan() != null, "到站生成真实采购单");
        check(world.refreshed.equals(Set.of("a")) && world.shelfReads == 2 && world.shelfUpdates == 2, "只刷新当前市场两个货架，未刷新目的地");
        var buys = result.generatedPlan().steps().stream().filter(s -> s.action().equals("BUY")).toList();
        check(buys.size() == 2 && buys.stream().allMatch(s -> ((Number) s.parameters().get("quantity")).intValue() == 2), "真实货架缩水后按两件重算，忽略原估算七件");
        check(world.credits.get() == 1000, "重算只生成步骤，不直接交易");
        executor.validateGeneratedPlan(result.generatedPlan());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var saved = json.readValue(json.writeValueAsString(executor.snapshot()), Executor.State.class);
        var restored = new Executor(world.context, new ExecutionHistory()); restored.restore(saved);
        int reads = world.shelfReads;
        check(restored.execute(step).equals(result) && world.shelfReads == reads, "读档重放终态不会刷新并重复插入不同采购单");
        world.stock = 16.9f;
        result = new Executor(world.context, new ExecutionHistory()).execute(step);
        check(result.generatedPlan().steps().stream().filter(s -> s.action().equals("BUY"))
                .allMatch(s -> s.parameters().get("quantity") instanceof Integer n && n <= 16), "小数库存只生成向下取整的整数买单");
        for (float stock : new float[]{0.9f, 0f}) {
            world.stock = stock;
            result = new Executor(world.context, new ExecutionHistory()).execute(step);
            check(result.status() == SUCCEEDED && result.generatedPlan().steps().stream().map(Step::action).toList().equals(List.of("MOVE_TO")),
                    "零库存或不足一件不能生成购买或依赖该购买的出售步骤");
        }
        world.stock = 2;
        world.sellUnitPrice = 5;
        result = new Executor(world.context, new ExecutionHistory()).execute(step);
        check(result.status() == SUCCEEDED && result.generatedPlan().steps().stream().map(Step::action).toList().equals(List.of("MOVE_TO")), "无利可图时保留空载行程，不执行过期买单");
    }
    private static void decisionAction() throws Exception {
        World world = new World();
        ExecutionHistory history = new ExecutionHistory();
        Executor executor = new Executor(world.context, history);
        Step step = Step.create("CALCULATE_TRADE_ROUTE", Map.of("commodityIds", List.of("ore")), "计算", "路线");
        world.paused = true;
        check(executor.execute(step).status() == WAITING && world.priceCalls == 0, "暂停计算时不采集数据，也不执行计算");
        world.paused = false;
        ExecutionResult result = executor.execute(step);
        check(result.status() == RUNNING && result.generatedPlan() == null, "决策动作起始阶段异步执行");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Executor.State saved = json.readValue(json.writeValueAsString(executor.snapshot()), Executor.State.class);
        executor.stop();
        history = new ExecutionHistory();
        executor = new Executor(world.context, history);
        executor.restore(saved);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (result.status() == RUNNING) {
            check(System.nanoTime() < deadline, "决策计算超时");
            result = executor.execute(step); Thread.sleep(2);
        }
        check(result.status() == SUCCEEDED && result.generatedPlan() != null, "读档后真实动作重新启动未完成的计算并返回 Plan");
        executor.validatePlan(result.generatedPlan());
        check(executor.execute(step).equals(result), "终态缓存返回相同的生成计划与步骤身份");
        check(history.snapshot().recentResults().size() == 1 && world.credits.get() == 1000, "计算只记录一次结果，不执行交易");
        executor.cancelBackground();
        check(executor.backgroundStopped(), "后台任务完成后释放生命周期资源");
        World noProfit = new World(); noProfit.sellUnitPrice = 5;
        Executor rejected = new Executor(noProfit.context, new ExecutionHistory());
        result = rejected.execute(step);
        deadline = System.nanoTime() + 5_000_000_000L;
        while (result.status() == RUNNING) {
            check(System.nanoTime() < deadline, "无利润路线计算超时");
            result = rejected.execute(step); Thread.sleep(2);
        }
        check(result.status() == FAILED && result.generatedPlan() == null, "无盈利路线时决策失败，不编造计划");
    }
    private static void resourceMarketInformation() {
        World world = new World();
        world.fuel = 10; world.supplies = 20; world.crew = 4;
        var resources = com.mozhi.fleet.game.GameWorld.resources(world.context.fleet());
        var check = new com.mozhi.fleet.execution.Monitor().checkResources(resources);
        var info = com.mozhi.fleet.game.ResourceMarkets.collect(world.context, check);
        var candidates = (List<?>) info.get("candidates");
        check(candidates.size() == 4, "后勤采购可见真实开放市场和黑市渠道");
        boolean black = false;
        for (Object raw : candidates) {
            var row = (Map<?, ?>) raw;
            black |= Boolean.TRUE.equals(row.get("blackMarket"));
            check(row.get("destinationId") != null && ((Number) row.get("fuelToReach")).doubleValue() == 0, "提供可执行的目标与可达性");
            List<?> goods = (List<?>) row.get("goods");
            check(goods.size() == 3, "规划可以读取三类后勤资源的真实库存");
            var fuel = (Map<?, ?>) goods.stream().filter(g -> ((Map<?, ?>) g).get("itemId").equals("fuel")).findFirst().orElseThrow();
            check(!fuel.containsKey("targetNeededOnArrival") && !fuel.containsKey("targetQuotedQuantity")
                    && ((Number) fuel.get("sampleQuantity")).intValue() > 0, "提供价格样例，不指定采购目标量");
            var supplies = (Map<?, ?>) goods.stream().filter(g -> ((Map<?, ?>) g).get("itemId").equals("supplies")).findFirst().orElseThrow();
            check(supplies.containsKey("capacityOnArrival") && supplies.containsKey("sampleTotalPrice"), "提供真实库存容量与价格样例，不规定补给天数");
        }
        check(black, "后勤采购搜索包含黑市");
        world.restriction = "disabled";
        check(((List<?>) com.mozhi.fleet.game.ResourceMarkets.collect(world.context, check).get("candidates")).isEmpty(), "禁用的渠道不能用于补充资源");
        world.restriction = ""; world.supplies = 0;
        var stranded = new com.mozhi.fleet.execution.Monitor().checkResources(com.mozhi.fleet.game.GameWorld.resources(world.context.fleet()));
        check(((List<?>) com.mozhi.fleet.game.ResourceMarkets.collect(world.context, stranded).get("candidates")).isEmpty(), "航行补给不足时不能推荐该路线");
    }
    private static final class World {
        final MutableValue credits = new MutableValue(1000);
        final List<MarketAPI> markets = new ArrayList<>();
        final ActionContext context;
        boolean paused;
        String restriction = "";
        int priceCalls;
        int shelfReads, shelfUpdates;
        float stock = 7;
        final Set<String> refreshed = new HashSet<>();
        SectorEntityToken orbit;
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
                case "getId", "getName" -> "ore"; case "getCargoSpace" -> 1f; case "getEconUnit" -> 35f; default -> null;
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
                case "getCargo" -> cargo; case "getFleetData" -> data; case "getLogistics" -> logistics;
                case "getOrbit" -> orbit == null ? null : proxy(OrbitAPI.class, (name, params) -> name.equals("getFocus") ? orbit : null);
                case "getAI" -> proxy(CampaignFleetAIAPI.class, (name, params) -> name.equals("getCurrentAssignment") && orbit != null
                        ? proxy(FleetAssignmentDataAPI.class, (key, args) -> switch (key) {
                            case "getAssignment" -> FleetAssignment.ORBIT_PASSIVE; case "getTarget" -> orbit; default -> null;
                        }) : null);
                default -> null;
            });
            for (String id : List.of("a", "b")) {
                SectorEntityToken entity = proxy(SectorEntityToken.class, (m, a) -> switch (m) {
                    case "getId" -> "entity-" + id; case "getContainingLocation" -> location;
                    case "getLocation" -> new Vector2f(100, 0); case "getLocationInHyperspace" -> new Vector2f(); default -> null;
                });
                List<SubmarketAPI> shops = new ArrayList<>();
                for (boolean black : List.of(false, true)) {
                    SubmarketPlugin plugin = proxy(SubmarketPlugin.class, (m, a) -> switch (m) {
                        case "updateCargoPrePlayerInteraction" -> { shelfUpdates++; refreshed.add(id); yield null; }
                        case "isParticipatesInEconomy" -> true; case "isEnabled" -> !restriction.equals("disabled");
                        case "getOnClickAction" -> SubmarketPlugin.OnClickAction.OPEN_SUBMARKET;
                        case "isBlackMarket" -> black; case "isFreeTransfer" -> restriction.equals("free");
                        case "isIllegalOnSubmarket" -> restriction.equals("illegal"); default -> null;
                    });
                    CargoAPI shelf = proxy(CargoAPI.class, (m, a) -> m.equals("getCommodityQuantity") ? a[0].equals("ore") ? stock : 200f : null);
                    shops.add(proxy(SubmarketAPI.class, (m, a) -> switch (m) {
                        case "getSpecId" -> black ? "black_market" : "open_market"; case "getPlugin" -> plugin;
                        case "getCargo" -> { shelfReads++; yield shelf; } case "getTariff" -> black ? 0f : 0.2f; default -> null;
                    }));
                }
                markets.add(proxy(MarketAPI.class, (m, a) -> switch (m) {
                    case "getId", "getName" -> id; case "getPrimaryEntity" -> entity; case "getFaction" -> faction;
                    case "getSubmarketsCopy" -> shops; case "isHidden" -> restriction.equals("hidden");
                    case "getSubmarket" -> shops.stream().filter(s -> s.getSpecId().equals(a[0])).findFirst().orElse(null);
                    case "isInEconomy" -> true; case "hasSpaceport" -> !restriction.equals("noPort"); case "getStabilityValue" -> 5f;
                    case "getAllCommodities" -> List.of(proxy(CommodityOnMarketAPI.class, (key, args) -> switch (key) {
                        case "getId" -> "ore"; case "getCommodity" -> goods.get(0);
                        case "getAvailable", "getMaxSupply", "getMaxDemand" -> 1; default -> null;
                    }));
                    case "getSupplyPrice", "getDemandPrice" -> {
                        priceCalls++;
                        if (restriction.equals("broken")) throw new IllegalStateException("报价不可用");
                        check(Boolean.TRUE.equals(a[2]), "与执行器使用相同的玩家价格语义");
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
            check(Thread.currentThread() == OWNER, "后台计算访问了游戏对象：" + type + "." + method.getName());
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
