package com.mozhi.fleet.execution;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.MutableValue;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ExecutionHistory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.lwjgl.util.vector.Vector2f;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 使用本地游戏 API 代理和可变资产，验证真实动作逻辑，不启动游戏。 */
public final class ExecutorChecks {
    private record Item(CargoAPI.CargoItemType type, Object data) {}
    private static final Item SUPPLIES = new Item(CargoAPI.CargoItemType.RESOURCES, "supplies");

    public static void main(String[] args) throws Exception {
        navigationAndThreadOwnership();
        tradingAndHistory();
        shipsAndOtherGoods();
        transactionFailures();
        returnAndMerge();
        nativeFractionalCargo();
        deploymentAndRuntime();
        if (args.length > 0) packagedLoading(java.nio.file.Path.of(args[0]));
        System.out.println("Executor / action checks passed");
    }

    private static void navigationAndThreadOwnership() throws Exception {
        World world = new World();
        Executor executor = world.executor();
        Step move = step("MOVE_TO", Map.of("destinationId", "market"));
        Plan plan = Plan.create("采购", List.of(move, trade("BUY", "COMMODITY", "supplies", 2)));
        world.fleet.position.set(5000, 0);
        status(executor.execute(plan, 0), RUNNING);
        check(world.fleet.assignment == FleetAssignment.GO_TO_LOCATION, "Move uses a native navigation assignment");
        int assignments = world.fleet.assignments;
        status(executor.execute(plan, 0), RUNNING);
        check(world.fleet.assignments == assignments && world.shop.quantity(SUPPLIES) == 20, "Do not reset navigation or execute next step");
        world.fleet.position.set(0, 0);
        status(executor.execute(plan, 0), RUNNING);
        check(world.fleet.assignment == FleetAssignment.ORBIT_PASSIVE, "Reach destination before orbiting");
        status(executor.execute(plan, 0), RUNNING);
        world.fleet.orbit = world.planet;
        status(executor.execute(plan, 0), SUCCEEDED);
        status(executor.execute(plan, 0), SUCCEEDED);
        check(world.shop.quantity(SUPPLIES) == 20, "Successful navigation never advances to BUY automatically");
        status(executor.execute(plan, 1), SUCCEEDED);
        check(world.shop.quantity(SUPPLIES) == 18, "Agent can select the next step explicitly");

        World stopped = new World();
        Executor runner = stopped.executor();
        stopped.fleet.position.set(5000, 0);
        Step travel = step("MOVE_TO", Map.of("destinationId", "planet"));
        runner.execute(travel);
        runner.stop();
        check(stopped.fleet.assignment == FleetAssignment.HOLD, "Stopping an active move holds position");
        status(runner.execute(travel), RUNNING);
        stopped.targetExpired = true;
        status(runner.execute(travel), FAILED);
        check(stopped.fleet.assignment == FleetAssignment.HOLD, "Failed movement does not continue an obsolete assignment");

        World paused = new World();
        Executor waiting = paused.executor();
        paused.paused = true;
        status(waiting.execute(move), WAITING);
        check(paused.fleet.assignments == 0, "Pause prevents native action calls");
        paused.paused = false; paused.fleet.battle = true;
        status(waiting.execute(move), WAITING);
        paused.fleet.battle = false; paused.fleet.transition = true;
        status(waiting.execute(move), WAITING);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread background = new Thread(() -> {
            try { waiting.execute(move); } catch (Throwable failure) { error.set(failure); }
        });
        background.start(); background.join(2000);
        check(error.get() instanceof IllegalStateException, "Reject background game operations");
    }

    private static void tradingAndHistory() throws Exception {
        World world = new World();
        Executor executor = world.executor();
        check(executor.actionSpecs().stream().map(spec -> spec.name()).toList().equals(List.of("BUY", "SELL", "MOVE_TO", "RETURN", "CALCULATE_TRADE_ROUTE")),
                "Planner sees the exact implemented action set");
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        check(world.fleet.assignments == 0 && world.shop.quantity(SUPPLIES) == 20, "Remote purchase fails without navigation or transfer");
        world.orbit();
        world.fleet.cargo.credits.set(600);
        Step buy = trade("BUY", "COMMODITY", "supplies", 5);
        var bought = executor.execute(buy);
        status(bought, SUCCEEDED);
        check(bought.tradeReceipt().creditsSpent() == 600 && bought.tradeReceipt().creditsReceived() == 0
                && bought.tradeReceipt().quotedTotal() == 600, "Buy returns actual cost including tariff");
        check(executor.execute(buy).equals(bought), "Cached buy returns identical receipt");
        check(world.fleet.cargo.quantity(SUPPLIES) == 5 && world.shop.quantity(SUPPLIES) == 15
                && world.fleet.cargo.credits.get() == 0, "Purchase transfers real stock and charges once including tariff");
        Step sell = trade("SELL", "COMMODITY", "supplies", 2);
        var sold = executor.execute(sell);
        status(sold, SUCCEEDED);
        check(sold.tradeReceipt().creditsSpent() == 0 && sold.tradeReceipt().creditsReceived() == 128,
                "Sell returns actual net proceeds after tariff");
        check(executor.execute(sell).equals(sold), "Cached sell returns identical receipt");
        check(world.fleet.cargo.quantity(SUPPLIES) == 3 && world.shop.quantity(SUPPLIES) == 17
                && world.fleet.cargo.credits.get() == 128, "Sale moves real stock and settles once");
        var failed = executor.execute(trade("BUY", "COMMODITY", "supplies", 2));
        status(failed, FAILED);
        check(failed.tradeReceipt() == null && executor.tradeResults().size() == 2, "Failed trade has no settlement receipt");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var saved = mapper.readValue(mapper.writeValueAsString(executor.snapshot()), Executor.State.class);
        var reloaded = new Executor(new ActionContext(world.sector, world.fleet.api, world.settings, world.factory), new ExecutionHistory());
        reloaded.restore(saved);
        check(reloaded.execute(buy).equals(bought) && reloaded.tradeResults().size() == 2, "Save preserves receipts without replay");
        var legacy = mapper.valueToTree(bought);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("tradeReceipt");
        check(mapper.treeToValue(legacy, ExecutionResult.class).tradeReceipt() == null, "Legacy missing receipt remains unknown");
        check(world.shop.quantity(SUPPLIES) == 17, "Insufficient funds do not buy partially");
        world.fleet.cargo.credits.set(100000);
        world.shop.items.put(SUPPLIES, 1f);
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 2)), FAILED);
        check(world.shop.quantity(SUPPLIES) == 1, "Read current stock rather than a planning snapshot");
        world.freeShop = true;
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        world.freeShop = false; world.hiddenShop = true;
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        check(world.shop.quantity(SUPPLIES) == 1, "Do not treat free storage or hidden stock as merchandise");
        check(world.history.snapshot().completedStepIds().containsAll(List.of(buy.id(), sell.id())), "Executor records actual successes for Planner");
        check(world.history.snapshot().recentResults().stream().filter(result -> result.step().id().equals(buy.id())).count() == 1,
                "Repeated success reads do not duplicate history");
        Step changed = new Step(buy.id(), buy.action(), Map.of(), buy.description(), buy.expectedOutcome());
        try { executor.execute(changed); throw new AssertionError("Changed step ID accepted"); }
        catch (IllegalArgumentException expected) {}
        Executor restored = world.executor();
        status(restored.execute(buy), FAILED);
        check(world.shop.quantity(SUPPLIES) == 1, "Completed history prevents replay after executor replacement");
        World largeBalance = new World(); largeBalance.orbit();
        largeBalance.fleet.cargo.credits.set(1_000_000_000);
        var rounded = largeBalance.executor().execute(trade("BUY", "COMMODITY", "supplies", 1));
        check(rounded.tradeReceipt().quotedTotal() == 120 && rounded.tradeReceipt().creditsSpent() == 128,
                "Receipt reports actual float balance delta separately from quote");
    }

    private static void shipsAndOtherGoods() {
        World world = new World(); world.orbit();
        Executor executor = world.executor();
        Ship ship = new Ship("for-sale"); ship.mothballed = true;
        world.shop.ships.members.add(ship.api);
        var captain = ship.captain;
        var officer = proxy(OfficerDataAPI.class, (m, a) -> m.equals("getPerson") ? captain : null);
        world.fleet.ships.officers.add(officer);
        world.fleet.cargo.credits.set(1200);
        status(executor.execute(trade("BUY", "SHIP", "for-sale", 1)), SUCCEEDED);
        check(world.fleet.ships.members.contains(ship.api) && !world.shop.ships.members.contains(ship.api)
                && !ship.mothballed && world.fleet.cargo.credits.get() == 0, "Buy the original ship with its equipment");
        status(executor.execute(trade("SELL", "SHIP", "for-sale", 1)), SUCCEEDED);
        check(world.shop.ships.members.contains(ship.api) && !world.fleet.ships.members.contains(ship.api)
                && ship.mothballed && world.fleet.cargo.credits.get() == 400, "Sell original ship and credit its value");
        check(ship.captain != captain && world.fleet.ships.officers.contains(officer), "Sold ship does not take the officer");
        status(executor.execute(trade("SELL", "SHIP", "fleet-own", 1)), FAILED);
        world.fleet.cargo.credits.set(10000);
        Item special = new Item(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData("blueprint", "hull-a"));
        Item other = new Item(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData("blueprint", "hull-b"));
        world.shop.items.put(special, 1f); world.shop.items.put(other, 1f);
        status(executor.execute(trade("BUY", "SPECIAL", "blueprint", 1)), FAILED);
        Map<String, Object> exact = new LinkedHashMap<>(trade("BUY", "SPECIAL", "blueprint", 1).parameters());
        exact.put("itemData", "hull-a");
        status(executor.execute(step("BUY", exact)), SUCCEEDED);
        check(world.fleet.cargo.quantity(special) == 1 && world.shop.quantity(other) == 1, "Preserve special item instance data");
        Item weapon = new Item(CargoAPI.CargoItemType.WEAPONS, "weapon-id");
        Item fighter = new Item(CargoAPI.CargoItemType.FIGHTER_CHIP, "fighter-id");
        world.shop.items.put(weapon, 2f); world.shop.items.put(fighter, 2f);
        status(executor.execute(trade("BUY", "WEAPON", "weapon-id", 1)), SUCCEEDED);
        status(executor.execute(trade("BUY", "FIGHTER", "fighter-id", 1)), SUCCEEDED);
        check(world.fleet.cargo.quantity(weapon) == 1 && world.fleet.cargo.quantity(fighter) == 1, "Trade weapons and fighter LPCs");
    }

    private static void transactionFailures() {
        World world = new World(); world.orbit();
        Executor executor = world.executor();
        world.fleet.cargo.failAdds = 1;
        Step broken = trade("BUY", "COMMODITY", "supplies", 3);
        status(executor.execute(broken), FAILED);
        check(world.shop.quantity(SUPPLIES) == 20 && world.fleet.cargo.quantity(SUPPLIES) == 0
                && world.fleet.cargo.credits.get() == 10000, "Failed addition restores source stock and money");
        status(executor.execute(broken), FAILED);
        check(world.shop.quantity(SUPPLIES) == 20, "Do not automatically retry the same failed step");
        world.fleet.cargo.failAfterAdd = true;
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 3)), FAILED);
        check(world.shop.quantity(SUPPLIES) == 20 && world.fleet.cargo.quantity(SUPPLIES) == 0, "Recover from writes that mutate before throwing");
        Ship ship = new Ship("rollback-ship"); ship.mothballed = true;
        world.shop.ships.members.add(ship.api); world.fleet.failSync = true;
        status(executor.execute(trade("BUY", "SHIP", "rollback-ship", 1)), FAILED);
        check(world.shop.ships.members.contains(ship.api) && !world.fleet.ships.members.contains(ship.api)
                && ship.mothballed && world.fleet.cargo.credits.get() == 10000, "Restore ship and credits after late failure");

        World uncertain = new World(); uncertain.orbit();
        Executor blocked = uncertain.executor();
        uncertain.fleet.cargo.failAdds = 1; uncertain.shop.failAdds = 1;
        status(blocked.execute(trade("BUY", "COMMODITY", "supplies", 2)), FAILED);
        check(blocked.isBlocked(), "A failed rollback blocks future actions");
        float quantity = uncertain.shop.quantity(SUPPLIES);
        status(blocked.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        check(uncertain.shop.quantity(SUPPLIES) == quantity, "No later trade after uncertain asset mutation");
    }

    private static void returnAndMerge() {
        World world = new World();
        Executor executor = world.executor();
        Step recall = step("RETURN", Map.of());
        world.player.position.set(8000, 0);
        status(executor.execute(recall), RUNNING);
        check(world.fleet.target == world.player.api, "Return navigates to current player fleet");
        world.player.position.set(0, 0); world.player.battle = true;
        status(executor.execute(recall), WAITING);
        world.player.battle = false; world.player.transition = true;
        status(executor.execute(recall), WAITING);
        world.player.transition = false;
        Ship own = new Ship("escorted"); world.fleet.ships.members.add(own.api);
        PersonAPI captain = own.captain;
        OfficerDataAPI officer = proxy(OfficerDataAPI.class, (m, a) -> m.equals("getPerson") ? captain : null);
        world.fleet.ships.officers.add(officer);
        Ship stored = new Ship("stored"); world.fleet.cargo.ships.members.add(stored.api);
        world.fleet.cargo.items.put(SUPPLIES, 5f); world.player.cargo.items.put(SUPPLIES, 10f);
        world.fleet.cargo.credits.set(300); world.player.cargo.credits.set(700);
        var flagship = world.player.api.getFlagship();
        status(executor.execute(recall), SUCCEEDED);
        status(executor.execute(recall), SUCCEEDED);
        check(world.fleet.expired && !world.entities.contains(world.fleet.api), "Remove merged fleet only after transfer");
        check(world.player.ships.members.size() == 4 && world.player.ships.members.contains(own.api)
                && world.player.ships.members.contains(stored.api), "Merge original active and stored ships exactly once");
        check(world.player.ships.officers.contains(officer) && own.captain == captain && world.fleet.ships.officers.isEmpty(), "Keep officers and captains");
        check(world.player.api.getFlagship() == flagship, "Keep the player's existing flagship");
        check(world.player.cargo.quantity(SUPPLIES) == 15 && world.fleet.cargo.quantity(SUPPLIES) == 0
                && world.player.cargo.credits.get() == 1000 && world.fleet.cargo.credits.get() == 0, "Merge cargo and credits once");

        World order = new World();
        Executor guarded = order.executor();
        status(guarded.execute(Plan.create("无效顺序", List.of(step("RETURN", Map.of()), trade("BUY", "COMMODITY", "supplies", 1))), 0), FAILED);
        check(!order.fleet.expired, "Reject return before the end of a plan");
    }

    private static Step step(String action, Map<String, Object> parameters) { return Step.create(action, parameters, action, "执行动作的实际效果"); }
    private static Step trade(String action, String type, String item, int quantity) {
        return step(action, Map.of("marketId", "market", "submarketId", "open_market", "itemType", type, "itemId", item, "quantity", quantity));
    }
    private static void status(ExecutionResult result, ExecutionResult.Status expected) {
        check(result.status() == expected, "Expected " + expected + ", got " + result.status() + ": " + result.result());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static com.fs.starfarer.campaign.fleet.CargoData nativeCargo(float visible, boolean partials) {
        var cargo = new com.fs.starfarer.campaign.fleet.CargoData(true);
        // 仅初始化货堆，避免离线测试依赖游戏的 SpecStore；读写和 partials 运算使用真实游戏实现。
        var stack = new com.fs.starfarer.campaign.ui.trade.CargoItemStack(CargoAPI.CargoItemType.RESOURCES, cargo);
        stack.setData("supplies"); stack.setMaxSize(100000); stack.setSize(visible); cargo.addStack(stack);
        if (partials) cargo.initPartialsIfNeeded();
        return cargo;
    }

    private static void nativeFractionalCargo() {
        var source = nativeCargo(84.375f, false);
        var target = nativeCargo(10, true);
        target.addSupplies(.25f);
        check(target.getQuantity(SUPPLIES.type(), SUPPLIES.data()) == 10, "Native getQuantity excludes partials");
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 10.25f, "Read native fractional balance");
        World world = new World(); world.fleet.cargoOverride = source; world.player.cargoOverride = target;
        source.getCredits().set(300); target.getCredits().set(700);
        status(world.executor().execute(step("RETURN", Map.of())), SUCCEEDED);
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 94.625f
                && com.mozhi.fleet.actions.CargoAmounts.quantity(source, SUPPLIES.type(), SUPPLIES.data()) == 0, "RETURN preserves native fractional cargo");
        check(world.fleet.expired && target.getCredits().get() == 1000, "Native cargo merge completes");

        source = nativeCargo(100, true); source.removeSupplies(.25f); // 负 partial：真实余额 99.75。
        target = nativeCargo(10, true); target.addSupplies(.5f);
        var tx = new com.mozhi.fleet.actions.AssetTransaction();
        tx.moveItems(source, target, SUPPLIES.type(), SUPPLIES.data(), 84.375f);
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(source, SUPPLIES.type(), SUPPLIES.data()) == 15.375f, "Negative partial is counted");
        RuntimeException rolledBack = tx.rollback(new IllegalStateException("later operation failed"));
        check(!(rolledBack instanceof com.mozhi.fleet.actions.UncertainActionException), "Native fractional rollback is confirmed");
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(source, SUPPLIES.type(), SUPPLIES.data()) == 99.75f
                && com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 10.5f, "Rollback restores visible plus hidden balances");

        source = new com.fs.starfarer.campaign.fleet.CargoData(true); source.initPartialsIfNeeded(); source.addSupplies(.375f);
        target = nativeCargo(10, true);
        world = new World(); world.fleet.cargoOverride = source; world.player.cargoOverride = target;
        com.fs.starfarer.api.Global.setSettings(world.settings);
        com.fs.starfarer.api.Global.setFactory(world.factory);
        var spec = proxy(CommoditySpecAPI.class, (method, args) -> method.equals("getId") ? "supplies" : null);
        var settings = proxy(SettingsAPI.class, (method, args) -> method.equals("getAllCommoditySpecs") ? List.of(spec) : null);
        var executor = new Executor(new ActionContext(world.sector, world.fleet.api, settings, world.factory), new ExecutionHistory());
        status(executor.execute(step("RETURN", Map.of())), SUCCEEDED);
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 10.375f, "Merge includes partial-only commodities without a stack");
        System.out.println("Native CargoData fractional merge / rollback checks passed");
    }

    private static void deploymentAndRuntime() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        World world = new World();
        world.entities.remove(world.fleet.api); world.fleet.location = null;
        world.fleet.ships.members.clear(); world.fleet.cargo.credits.set(0);
        var selected = new Ship("selected"); world.player.ships.members.add(selected.api);
        world.player.cargo.credits.set(5000); world.player.cargo.items.put(SUPPLIES, 40f);
        com.fs.starfarer.api.Global.setSettings(world.settings);
        com.fs.starfarer.api.Global.setFactory(world.factory);
        com.fs.starfarer.api.Global.setSector(world.sector);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<com.mozhi.fleet.planning.Planner> planners = () -> new com.mozhi.fleet.planning.Planner(
                com.mozhi.llm.LlmClient.of(new dev.langchain4j.model.chat.ChatModel() {
                    @Override public dev.langchain4j.model.chat.response.ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
                        String prompt = request.messages().toString();
                        if (prompt.contains("\"completionReview\":true")) return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                .aiMessage(dev.langchain4j.data.message.AiMessage.from("{\"decision\":\"GOAL_REACHED\",\"reason\":\"实际结果确认目标已达成\",\"steps\":[]}")).build();
                        calls.incrementAndGet();
                        check(prompt.contains("open_market") && prompt.contains("supplies"), "World snapshot includes actual trade IDs");
                        String answer = """
                                {"decision":"REPLACE","reason":"买补给后回归","steps":[
                                {"reuseStepId":"","action":"BUY","parametersJson":"{\\"marketId\\":\\"market\\",\\"submarketId\\":\\"open_market\\",\\"itemType\\":\\"COMMODITY\\",\\"itemId\\":\\"supplies\\",\\"quantity\\":2}","description":"购买","expectedOutcome":"买入2补给"}
                                ]}
                                """;

                        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                .aiMessage(dev.langchain4j.data.message.AiMessage.from(answer)).build();
                    }
                }, null, 30));
        var runtime = new com.mozhi.fleet.game.FleetRuntime(world.sector, planners, () -> world.fleet.api);
        runtime.initialize("file:/unused.properties");
        check(!json.readTree(runtime.command("{\"operation\":\"preview\",\"ships\":[\"selected\"]}")).has("error"), "Preview bridge works");
        String dispatched = runtime.command("{\"operation\":\"dispatch\",\"ships\":[\"selected\"],\"credits\":1000,\"supplies\":10,\"fuel\":0,\"crew\":0}");
        check(!json.readTree(dispatched).has("error"), "Dispatch bridge works: " + dispatched);
        check(world.fleet.ships.members.contains(selected.api) && !world.player.ships.members.contains(selected.api), "Dispatch moves original ship");
        check(world.player.cargo.credits.get() == 4000 && world.fleet.cargo.credits.get() == 1000
                && world.player.cargo.quantity(SUPPLIES) == 30 && world.fleet.cargo.quantity(SUPPLIES) == 10, "Dispatch transfers exact resources");
        check(calls.get() == 0, "Dispatch does not require the model");
        String moved = runtime.command("{\"operation\":\"move\",\"destination\":\"market\"}");
        check(!json.readTree(moved).has("error"), "Direct movement bridge accepts known destination");
        runtime.advance(0); world.orbit(); runtime.advance(0);
        long reviewDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!json.valueToTree(runtime.view()).path("state").path("mission").path("status").asText().equals("COMPLETED") && System.nanoTime() < reviewDeadline) { runtime.advance(0); Thread.sleep(2); }
        check(json.valueToTree(runtime.view()).path("state").path("mission").path("status").asText().equals("COMPLETED"), "Movement requires actual orbit");
        check(calls.get() == 0, "Direct move does not require model");
        check(json.valueToTree(runtime.view()).path("state").path("awaitingReturnConfirmation").asBoolean(), "Unrequested return waits for player after success");
        runtime.command("{\"operation\":\"order\",\"instruction\":\"在 market 买2补给后回归\",\"returnAfterCompletion\":true}");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (world.shop.quantity(SUPPLIES) == 20 && System.nanoTime() < deadline) { runtime.advance(0); Thread.sleep(2); }
        check(world.shop.quantity(SUPPLIES) == 18, "Game bridge drives a real BUY action: " + runtime.view());
        check(json.valueToTree(runtime.view()).path("state").path("tradeReceipts").get(0).path("creditsSpent").asDouble() == 240,
                "Status exposes actual purchase expense");
        runtime.save(); runtime.close();
        check(world.persistent.get(com.mozhi.fleet.game.FleetRuntime.SAVE_KEY) instanceof String, "Save only JSON, never private-loader objects");
        var restored = new com.mozhi.fleet.game.FleetRuntime(world.sector, planners, () -> world.fleet.api);
        restored.initialize("file:/unused.properties");
        check(json.valueToTree(restored.view()).path("state").path("plan").path("currentStep").asInt() == 1, "Restore the exact execution cursor");
        check(json.valueToTree(restored.view()).path("state").path("plan").path("steps").get(0).path("tradeReceipt").path("creditsSpent").asDouble() == 240,
                "Plan step retains purchase receipt after load");
        reviewDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!json.valueToTree(restored.view()).path("state").path("mode").asText().equals("MERGED") && System.nanoTime() < reviewDeadline) { restored.advance(0); Thread.sleep(2); }
        check(json.valueToTree(restored.view()).path("state").path("mode").asText().equals("MERGED"), "Resume RETURN after load: " + restored.view());
        check(world.shop.quantity(SUPPLIES) == 18 && calls.get() == 1, "Load never repeats the completed purchase or replans it");
        check(world.player.cargo.quantity(SUPPLIES) == 42 && world.player.cargo.credits.get() == 4760, "Merge conserves assets after one purchase");
        check(json.valueToTree(restored.view()).path("state").path("mission").path("status").asText().equals("COMPLETED"), "Authorized return happens after goal review");
        restored.save(); restored.close();
        var reviewing = new com.mozhi.fleet.game.FleetRuntime(world.sector, planners, () -> world.fleet.api);
        reviewing.initialize("file:/unused.properties");
        reviewDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!json.valueToTree(reviewing.view()).path("state").path("mission").path("status").asText().equals("COMPLETED") && System.nanoTime() < reviewDeadline) { reviewing.advance(0); Thread.sleep(2); }
        check(json.valueToTree(reviewing.view()).path("state").path("mission").path("status").asText().equals("COMPLETED"), "Review continues after merge and save/load without a controlled fleet");
        check(world.player.cargo.quantity(SUPPLIES) == 42, "Review does not merge assets twice");
        reviewing.close();

        World rollback = new World();
        rollback.entities.remove(rollback.fleet.api); rollback.fleet.location = null;
        rollback.fleet.ships.members.clear(); rollback.fleet.cargo.credits.set(0);
        var ship = new Ship("dispatch-rollback"); rollback.player.ships.members.add(ship.api);
        rollback.player.cargo.items.put(SUPPLIES, 20f); rollback.player.cargo.credits.set(1000);
        rollback.fleet.cargo.failAfterAdd = true;
        try {
            com.mozhi.fleet.game.FleetDeployment.depart(rollback.player.api, rollback.fleet.api, List.of("dispatch-rollback"), 100, 10, 0, 0);
            throw new AssertionError("Expected dispatch mutation failure");
        } catch (IllegalStateException expected) { }
        check(rollback.player.ships.members.contains(ship.api) && rollback.fleet.ships.members.isEmpty()
                && rollback.player.cargo.quantity(SUPPLIES) == 20 && rollback.fleet.cargo.quantity(SUPPLIES) == 0
                && rollback.player.cargo.credits.get() == 1000 && rollback.fleet.location == null, "Dispatch failure rolls back all assets");
        System.out.println("Game bridge / dispatch / save-load checks passed");
    }

    private static void packagedLoading(java.nio.file.Path root) throws Exception {
        World world = new World();
        com.fs.starfarer.api.Global.setSettings(world.settings);
        com.fs.starfarer.api.Global.setFactory(world.factory);
        com.fs.starfarer.api.Global.setSector(world.sector);
        world.persistent.put("mozhi_assistant_fleet_agent_v1", "{\"fleetId\":\"fleet\"}");
        try (var loader = new com.mozhi.assistant.bootstrap.AgentClassLoader(ExecutorChecks.class.getClassLoader(),
                root.resolve("jars/fleet-agent.jar").toUri().toURL(), root.resolve("jars/mozhi-llm-client.jar").toUri().toURL())) {
            Class<?> type = loader.loadClass("com.mozhi.fleet.game.FleetRuntime");
            check(type.getClassLoader() == loader, "Game runtime loads from its private jar");
            var config = root.resolve("data/config/agent.properties");
            if (java.nio.file.Files.isRegularFile(config)) {
                loader.loadClass("com.mozhi.llm.LlmConfig").getMethod("load", String.class).invoke(null, config.toUri().toString());
                System.out.println("Local model configuration parsed (no network request)");
            }
            var runtime = (com.mozhi.assistant.bridge.FleetAgentBridge) type.getConstructor().newInstance();
            try {
                runtime.initialize("file:/unused.properties");
                String response = runtime.command("{\"operation\":\"recall\"}");
                check(!new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).has("error"), "Cross-loader recall accepted");
                runtime.advance(0);
                runtime.save();
                var state = (Map<?, ?>) runtime.view().get("state");
                check(state.get("mode").equals("MERGED"), "Packaged entry point can execute RETURN and report JDK-only state");
            } finally { runtime.close(); }
            long stopDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!runtime.isStopped() && System.nanoTime() < stopDeadline) Thread.sleep(2);
            check(runtime.isStopped(), "Review planner closes without a thread leak");
        }
        System.out.println("Packaged private classloader / bridge checks passed");
    }

    private static final class Cargo {
        final Map<Item, Float> items = new LinkedHashMap<>();
        final MutableValue credits = new MutableValue();
        final Ships ships = new Ships();
        int failAdds;
        boolean failAfterAdd;
        final CargoAPI api = proxy(CargoAPI.class, (m, a) -> switch (m) {
            case "getCredits" -> credits;
            case "getSupplies" -> quantity(SUPPLIES);
            case "getFuel" -> quantity(new Item(CargoAPI.CargoItemType.RESOURCES, "fuel"));
            case "getCrew" -> (int) quantity(new Item(CargoAPI.CargoItemType.RESOURCES, "crew"));
            case "getMaxCapacity", "getMaxFuel", "getMaxPersonnel" -> 1000f;
            case "getMothballedShips" -> ships.api;
            case "getStacksCopy" -> items.keySet().stream().filter(item -> quantity(item) > 0).map(this::stack).toList();
            case "getQuantity" -> quantity(new Item((CargoAPI.CargoItemType) a[0], a[1]));
            case "addItems", "removeItems" -> {
                boolean add = m.equals("addItems");
                if (add && failAdds > 0) { failAdds--; throw new IllegalStateException("simulated add failure"); }
                Item item = new Item((CargoAPI.CargoItemType) a[0], a[1]);
                items.put(item, quantity(item) + (float) a[2] * (add ? 1 : -1));
                if (add && failAfterAdd) { failAfterAdd = false; throw new IllegalStateException("simulated failure after mutation"); }
                yield add ? null : true;
            }
            default -> null;
        });
        float quantity(Item item) { return items.getOrDefault(item, 0f); }
        CargoStackAPI stack(Item item) {
            return proxy(CargoStackAPI.class, (m, a) -> switch (m) {
                case "getSize" -> quantity(item); case "getType" -> item.type(); case "getData" -> item.data();
                case "getDisplayName" -> String.valueOf(item.data());
                case "isCommodityStack" -> item.type() == CargoAPI.CargoItemType.RESOURCES;
                case "isSpecialStack" -> item.type() == CargoAPI.CargoItemType.SPECIAL;
                case "isWeaponStack" -> item.type() == CargoAPI.CargoItemType.WEAPONS;
                case "isFighterWingStack" -> item.type() == CargoAPI.CargoItemType.FIGHTER_CHIP;
                case "getCommodityId", "getSpecialDataIfSpecial" -> item.data();
                case "getWeaponSpecIfWeapon" -> proxy(WeaponSpecAPI.class, (method, args) -> method.equals("getWeaponId") ? item.data() : null);
                case "getFighterWingSpecIfWing" -> proxy(FighterWingSpecAPI.class, (method, args) -> method.equals("getId") ? item.data() : null);
                case "getBaseValuePerUnit" -> 100; default -> null;
            });
        }
    }

    private static final class Ships {
        final List<FleetMemberAPI> members = new ArrayList<>();
        final List<OfficerDataAPI> officers = new ArrayList<>();
        FleetMemberAPI flagship;
        final FleetDataAPI api = proxy(FleetDataAPI.class, (m, a) -> switch (m) {
            case "getMembersListCopy" -> new ArrayList<>(members); case "getNumMembers" -> members.size();
            case "addFleetMember" -> { members.add((FleetMemberAPI) a[0]); yield null; }
            case "removeFleetMember" -> { members.remove(a[0]); yield null; }
            case "getOfficersCopy" -> new ArrayList<>(officers);
            case "getOfficerData" -> officers.stream().filter(officer -> officer.getPerson() == a[0]).findFirst().orElse(null);
            case "addOfficer" -> { officers.add((OfficerDataAPI) a[0]); yield null; }
            case "removeOfficer" -> { officers.removeIf(officer -> officer.getPerson() == a[0]); yield null; }
            case "setFlagship" -> { flagship = (FleetMemberAPI) a[0]; yield null; }
            default -> null;
        });
    }

    private static final class Ship {
        boolean mothballed;
        PersonAPI captain = proxy(PersonAPI.class, (m, a) -> null);
        final FleetMemberAPI api;
        Ship(String id) {
            RepairTrackerAPI repairs = proxy(RepairTrackerAPI.class, (m, a) -> {
                if (m.equals("setMothballed")) mothballed = (boolean) a[0];
                return null;
            });
            api = proxy(FleetMemberAPI.class, (m, a) -> switch (m) {
                case "getId", "getShipName", "getHullId" -> id; case "getCaptain" -> captain;
                case "getHullSpec" -> proxy(com.fs.starfarer.api.combat.ShipHullSpecAPI.class, (method, args) -> null);
                case "setCaptain" -> { captain = (PersonAPI) a[0]; yield null; }
                case "isMothballed" -> mothballed; case "getRepairTracker" -> repairs;
                case "getBaseBuyValue" -> 1000f; case "getBaseSellValue" -> 500f; default -> null;
            });
        }
    }

    private static final class Fleet {
        final Cargo cargo = new Cargo();
        CargoAPI cargoOverride;
        final Ships ships = new Ships();
        final Vector2f position = new Vector2f();
        LocationAPI location;
        FleetAssignment assignment;
        SectorEntityToken target, orbit;
        boolean expired, battle, transition, failSync;
        int assignments;
        final CampaignFleetAPI api;
        Fleet(String id) {
            ships.members.add(new Ship(id + "-own").api);
            CampaignFleetAIAPI ai = proxy(CampaignFleetAIAPI.class, (m, a) -> m.equals("getCurrentAssignment") && assignment != null
                    ? proxy(FleetAssignmentDataAPI.class, (method, args) -> switch (method) {
                        case "getAssignment" -> assignment; case "getTarget" -> target; default -> null;
                    }) : null);
            api = proxy(CampaignFleetAPI.class, (m, a) -> switch (m) {
                case "getId", "getName" -> id; case "getCargo" -> cargoOverride == null ? cargo.api : cargoOverride; case "getFleetData" -> ships.api;
                case "getFlagship" -> ships.flagship != null ? ships.flagship : ships.members.isEmpty() ? null : ships.members.get(0);
                case "getLogistics" -> proxy(com.fs.starfarer.api.fleet.FleetLogisticsAPI.class, (method, args) -> null);
                case "getMemoryWithoutUpdate" -> proxy(com.fs.starfarer.api.campaign.rules.MemoryAPI.class, (method, args) -> null);
                case "isEmpty" -> ships.members.isEmpty();
                case "setLocation" -> { position.set((float) a[0], (float) a[1]); yield null; }
                case "getAI" -> ai; case "getLocation" -> position; case "getContainingLocation" -> location; case "getRadius" -> 20f;
                case "getBattle" -> battle ? proxy(BattleAPI.class, (method, args) -> null) : null;
                case "isInHyperspaceTransition" -> transition; case "isExpired" -> expired;
                case "setExpired" -> { expired = (boolean) a[0]; yield null; }
                case "getOrbit" -> orbit == null ? null : proxy(OrbitAPI.class, (method, args) -> method.equals("getFocus") ? orbit : null);
                case "clearAssignments" -> { assignment = null; target = null; orbit = null; yield null; }
                case "addAssignment" -> { assignment = (FleetAssignment) a[0]; target = (SectorEntityToken) a[1]; assignments++; yield null; }
                case "forceSync" -> { if (failSync) { failSync = false; throw new IllegalStateException("simulated sync failure"); } yield null; }
                default -> null;
            });
        }
    }

    private static final class World {
        final Fleet fleet = new Fleet("fleet"), player = new Fleet("player");
        final Cargo shop = new Cargo();
        final ExecutionHistory history = new ExecutionHistory();
        final List<SectorEntityToken> entities = new ArrayList<>();
        final MarketAPI market;
        final SectorEntityToken planet;
        final SectorAPI sector;
        final Map<String, Object> persistent = new LinkedHashMap<>();
        final SettingsAPI settings = proxy(SettingsAPI.class, (m, a) -> m.equals("getFloat") ? ((String) a[0]).endsWith("BuyPriceMult") ? 1.2f : 0.8f : null);
        final FactoryAPI factory = proxy(FactoryAPI.class, (m, a) -> m.equals("createPerson") ? proxy(PersonAPI.class, (method, args) -> null) : null);
        boolean paused, freeShop, hiddenShop, targetExpired, failRemove;
        World() {
            LocationAPI location = proxy(LocationAPI.class, (m, a) -> switch (m) {
                case "getId", "getName" -> "test-system";
                case "getFleets" -> entities.stream().filter(entity -> entity instanceof CampaignFleetAPI).map(entity -> (CampaignFleetAPI) entity).toList();
                case "getAllEntities" -> entities;
                case "addEntity" -> { entities.add((SectorEntityToken) a[0]); if (a[0] == fleet.api) fleet.location = player.location; yield null; }
                case "removeEntity" -> {
                    entities.remove(a[0]);
                    if (a[0] == fleet.api) fleet.location = null;
                    if (failRemove) { failRemove = false; throw new IllegalStateException("simulated removal failure"); }
                    yield null;
                }
                default -> null;
            });
            fleet.location = location; player.location = location;
            fleet.cargo.credits.set(10000); shop.items.put(SUPPLIES, 20f);
            SubmarketPlugin plugin = proxy(SubmarketPlugin.class, (m, a) -> switch (m) {
                case "isFreeTransfer" -> freeShop; case "isHidden" -> hiddenShop; default -> null;
            });
            SubmarketAPI submarket = proxy(SubmarketAPI.class, (m, a) -> switch (m) {
                case "getCargo" -> shop.api; case "getPlugin" -> plugin; case "getSpecId", "getNameOneLine" -> "open_market";
                case "getTariff" -> 0.2f; default -> null;
            });
            SectorEntityToken[] target = new SectorEntityToken[1];
            market = proxy(MarketAPI.class, (m, a) -> switch (m) {
                case "getId", "getName" -> "market"; case "getPrimaryEntity" -> target[0];
                case "getSubmarketsCopy" -> List.of(submarket); case "getConnectedEntities" -> List.of();
                case "getSupplyPrice" -> (float) ((double) a[1] * 100);
                case "getDemandPrice" -> (float) ((double) a[1] * 80); default -> null;
            });
            planet = proxy(SectorEntityToken.class, (m, a) -> switch (m) {
                case "getId" -> "planet"; case "getName" -> "Planet"; case "getMarket" -> market;
                case "isExpired" -> targetExpired; case "getContainingLocation" -> location;
                case "getLocation" -> new Vector2f(); case "getRadius" -> 100f; default -> null;
            });
            target[0] = planet; entities.add(planet); entities.add(fleet.api); entities.add(player.api);
            EconomyAPI economy = proxy(EconomyAPI.class, (m, a) -> m.equals("getMarketsCopy") ? List.of(market) : null);
            sector = proxy(SectorAPI.class, (m, a) -> switch (m) {
                case "isPaused" -> paused; case "getPlayerFleet" -> player.api;
                case "getPersistentData" -> persistent;
                case "getAllLocations" -> List.of(location); case "getEconomy" -> economy; case "getStarSystems" -> List.of();
                case "getEntityById" -> a[0].equals("planet") ? planet : null;
                default -> null;
            });
        }
        Executor executor() { return new Executor(new ActionContext(sector, fleet.api, settings, factory), history); }
        void orbit() { fleet.assignment = FleetAssignment.ORBIT_PASSIVE; fleet.target = planet; fleet.orbit = planet; }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
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
}
