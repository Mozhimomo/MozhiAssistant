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

    private static void transferToPlayer() {
        World world = new World();
        Executor executor = world.executor();
        world.fleet.cargo.credits.set(300);
        world.player.cargo.credits.set(700);
        world.player.position.set(50000, 0);
        Step transfer = step("TRANSFER_TO_PLAYER", Map.of("amount", 300));
        var result = executor.execute(transfer);
        status(result, SUCCEEDED);
        check(world.fleet.cargo.credits.get() == 0 && world.player.cargo.credits.get() == 1000,
                "分舰队可以远程转出全部余额，无保留资金限制");
        check(result.tradeReceipt() == null && result.result().contains("300"), "转账记录实际金额但不伪造成交利润");
        check(executor.execute(transfer).equals(result) && world.player.cargo.credits.get() == 1000, "相同步骤不重复转账");
        Executor restored = new Executor(new ActionContext(world.sector, world.fleet.api, world.settings, world.factory), new ExecutionHistory());
        restored.restore(executor.snapshot());
        status(restored.execute(transfer), SUCCEEDED);
        check(world.player.cargo.credits.get() == 1000, "恢复执行器后也不重复转账");
        for (Object amount : List.of(1, 0, -1, 1e40, "20")) {
            status(restored.execute(step("TRANSFER_TO_PLAYER", Map.of("amount", amount))), FAILED);
            check(world.fleet.cargo.credits.get() == 0 && world.player.cargo.credits.get() == 1000, "余额不足或无效金额不修改资产");
        }
        status(restored.execute(step("TRANSFER_TO_MOZHI", Map.of("amount", 100))), FAILED);
        check(world.player.cargo.credits.get() == 1000, "子智能体无法调用玩家出资工具");
    }

    private static void followFleet() {
        World world = new World();
        Executor executor = world.executor();
        Step step = Step.create("FOLLOW_FLEET", Map.of("targetFleetId", "player"), "跟随玩家", "持续跟随");
        world.player.position.set(3000, 0);
        float credits = world.fleet.cargo.credits.get();
        int ships = world.fleet.ships.members.size();
        status(executor.execute(step), RUNNING);
        check(world.fleet.assignment == FleetAssignment.GO_TO_LOCATION && world.fleet.target == world.player.api, "跟随沿用返航的导航方式");
        int assignments = world.fleet.assignments;
        status(executor.execute(step), RUNNING);
        check(world.fleet.assignments == assignments, "追赶期间不会逐帧重复下达导航任务");
        world.player.position.set(0, 0);
        status(executor.execute(step), RUNNING);
        check(world.fleet.assignment == FleetAssignment.HOLD && !world.fleet.expired && world.fleet.ships.members.size() == ships
                && world.fleet.cargo.credits.get() == credits, "靠近后保持任务，不回收或合并任何资产");
        var saved = executor.snapshot();
        Executor restored = new Executor(new ActionContext(world.sector, world.fleet.api, world.settings, world.factory), new ExecutionHistory());
        restored.restore(saved);
        world.player.position.set(4000, 0);
        status(restored.execute(step), RUNNING);
        check(world.fleet.assignment == FleetAssignment.GO_TO_LOCATION, "读档后目标离开可继续追赶");
        world.player.transition = true;
        status(restored.execute(step), WAITING);
        check(world.fleet.assignment == FleetAssignment.HOLD, "目标跃迁时停止旧追赶并等待");
        world.player.transition = false; world.player.battle = true;
        status(restored.execute(step), WAITING);
        world.player.battle = false;
        LocationAPI oldLocation = world.player.location;
        world.player.location = proxy(LocationAPI.class, (m, a) -> null);
        status(restored.execute(step), RUNNING);
        check(world.fleet.assignment == FleetAssignment.GO_TO_LOCATION && world.fleet.target == world.player.api, "跨星系继续导航到同一目标舰队");
        world.player.location = oldLocation;
        restored.stop();
        check(world.fleet.assignment == FleetAssignment.HOLD, "停止跟随后原地待命");
        world.player.expired = true;
        status(restored.execute(step), FAILED);
        check(world.fleet.assignment == FleetAssignment.HOLD, "目标消失后终止旧追赶");
        world.player.expired = false;
        Fleet other = new Fleet("other"); other.location = world.fleet.location; other.position.set(5000, 0); world.entities.add(other.api);
        status(executor.execute(Step.create("FOLLOW_FLEET", Map.of("targetFleetId", "other"), "跟随指定舰队", "持续")), RUNNING);
        check(world.fleet.target == other.api, "支持跟随非玩家舰队");
        status(executor.execute(Step.create("FOLLOW_FLEET", Map.of("targetFleetId", "fleet"), "不能跟随自己", "拒绝")), FAILED);
        status(executor.execute(Step.create("FOLLOW_FLEET", Map.of("targetFleetId", "missing"), "目标不存在", "拒绝")), FAILED);
        check(com.mozhi.fleet.game.GameWorld.followTarget(world.sector, "玩家舰队") == world.player.api, "玩家目标别名");
    }

    private static void creditTransferChecks() {
        class Balance extends MutableValue {
            boolean written;
            boolean failOnce;
            Balance(float value) { super(value); }
            @Override public float get() {
                if (written) throw new AssertionError("正常转账不得再次读取余额");
                return super.get();
            }
            @Override public void set(float value) {
                written = true;
                super.set(value);
                if (failOnce) { failOnce = false; throw new IllegalStateException("模拟入账后异常"); }
            }
            float actual() { return super.get(); }
        }
        Balance from = new Balance(100), to = new Balance(20);
        CargoAPI source = proxy(CargoAPI.class, (m, a) -> m.equals("getCredits") ? from : null);
        CargoAPI target = proxy(CargoAPI.class, (m, a) -> m.equals("getCredits") ? to : null);
        com.mozhi.fleet.game.CreditTransfer.transfer(source, target, 100);
        check(from.actual() == 0 && to.actual() == 120, "一次前向校验后完成转账，无后向余额读取");
        from.written = false; to.written = false;
        from.failOnce = true;
        try {
            com.mozhi.fleet.game.CreditTransfer.transfer(target, source, 20);
            throw new AssertionError("应返回实际游戏异常");
        } catch (IllegalStateException expected) {
            check(from.actual() == 0 && to.actual() == 120, "游戏写入异常时恢复双方余额");
        }
    }
    private static final Item SUPPLIES = new Item(CargoAPI.CargoItemType.RESOURCES, "supplies");

    public static void main(String[] args) throws Exception {
        creditTransferChecks();
        navigationAndThreadOwnership();
        tradingAndHistory();
        unavailablePurchase();
        focusedWorld();
        shipsAndOtherGoods();
        transactionFailures();
        returnAndMerge();
        followFleet();
        transferToPlayer();
        nativeFractionalCargo();
        nativeTradeRemainder();
        resourceTransfersWithoutReserves();
        deploymentAndRuntime();
        if (args.length > 0) packagedLoading(java.nio.file.Path.of(args[0]));
        System.out.println("执行器与动作检查通过");
    }

    private static void navigationAndThreadOwnership() throws Exception {
        World world = new World();
        Executor executor = world.executor();
        Step move = step("MOVE_TO", Map.of("destinationId", "market"));
        Plan plan = Plan.create("采购", List.of(move, trade("BUY", "COMMODITY", "supplies", 2)));
        world.fleet.position.set(5000, 0);
        status(executor.execute(plan, 0), RUNNING);
        check(world.fleet.assignment == FleetAssignment.GO_TO_LOCATION, "移动使用原生导航任务");
        int assignments = world.fleet.assignments;
        status(executor.execute(plan, 0), RUNNING);
        check(world.fleet.assignments == assignments && world.shop.quantity(SUPPLIES) == 20, "不重置导航，也不执行下一步");
        world.fleet.position.set(0, 0);
        status(executor.execute(plan, 0), RUNNING);
        check(world.fleet.assignment == FleetAssignment.ORBIT_PASSIVE, "到达目标后才开始入轨");
        status(executor.execute(plan, 0), RUNNING);
        world.fleet.orbit = world.planet;
        status(executor.execute(plan, 0), SUCCEEDED);
        status(executor.execute(plan, 0), SUCCEEDED);
        check(world.shop.quantity(SUPPLIES) == 20, "导航成功不自动推进至购买");
        status(executor.execute(plan, 1), SUCCEEDED);
        check(world.shop.quantity(SUPPLIES) == 18, "智能体可以明确选择下一步");

        World stopped = new World();
        Executor runner = stopped.executor();
        stopped.fleet.position.set(5000, 0);
        Step travel = step("MOVE_TO", Map.of("destinationId", "planet"));
        runner.execute(travel);
        runner.stop();
        check(stopped.fleet.assignment == FleetAssignment.HOLD, "停止移动时原地待命");
        status(runner.execute(travel), RUNNING);
        stopped.targetExpired = true;
        status(runner.execute(travel), FAILED);
        check(stopped.fleet.assignment == FleetAssignment.HOLD, "移动失败后不继续过期任务");

        World paused = new World();
        Executor waiting = paused.executor();
        paused.paused = true;
        status(waiting.execute(move), WAITING);
        check(paused.fleet.assignments == 0, "暂停时不调用原生动作");
        paused.paused = false; paused.fleet.battle = true;
        status(waiting.execute(move), WAITING);
        paused.fleet.battle = false; paused.fleet.transition = true;
        status(waiting.execute(move), WAITING);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread background = new Thread(() -> {
            try { waiting.execute(move); } catch (Throwable failure) { error.set(failure); }
        });
        background.start(); background.join(2000);
        check(error.get() instanceof IllegalStateException, "拒绝后台线程操作游戏");
    }

    private static void tradingAndHistory() throws Exception {
        World world = new World();
        Executor executor = world.executor();
        check(executor.actionSpecs().stream().map(spec -> spec.name()).toList().equals(List.of("BUY", "SELL", "MOVE_TO", "FOLLOW_FLEET", "RETURN", "CALCULATE_TRADE_ROUTE", "PREPARE_TRADE_HOP", "TRANSFER_TO_PLAYER")),
                "规划器可见动作集与实际实现完全一致");
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        check(world.fleet.assignments == 0 && world.shop.quantity(SUPPLIES) == 20, "远程购买失败，不导航也不转移资产");
        world.orbit();
        world.fleet.cargo.credits.set(600);
        Step buy = trade("BUY", "COMMODITY", "supplies", 5);
        var bought = executor.execute(buy);
        status(bought, SUCCEEDED);
        check(bought.tradeReceipt().creditsSpent() == 600 && bought.tradeReceipt().creditsReceived() == 0
                && bought.tradeReceipt().quotedTotal() == 600, "购买返回实际含税成本");
        check(executor.execute(buy).equals(bought), "购买缓存返回相同回执");
        check(world.fleet.cargo.quantity(SUPPLIES) == 5 && world.shop.quantity(SUPPLIES) == 15
                && world.fleet.cargo.credits.get() == 0, "购买转移真实库存，并且只收取一次含税费用");
        Step sell = trade("SELL", "COMMODITY", "supplies", 2);
        var sold = executor.execute(sell);
        status(sold, SUCCEEDED);
        check(sold.tradeReceipt().creditsSpent() == 0 && sold.tradeReceipt().creditsReceived() == 128,
                "出售返回实际税后净收入");
        check(executor.execute(sell).equals(sold), "出售缓存返回相同回执");
        check(world.fleet.cargo.quantity(SUPPLIES) == 3 && world.shop.quantity(SUPPLIES) == 17
                && world.fleet.cargo.credits.get() == 128, "出售转移真实库存，并且只结算一次");
        var failed = executor.execute(trade("BUY", "COMMODITY", "supplies", 2));
        status(failed, FAILED);
        check(failed.tradeReceipt() == null && executor.tradeResults().size() == 2, "失败交易没有结算回执");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var saved = mapper.readValue(mapper.writeValueAsString(executor.snapshot()), Executor.State.class);
        var reloaded = new Executor(new ActionContext(world.sector, world.fleet.api, world.settings, world.factory), new ExecutionHistory());
        reloaded.restore(saved);
        check(reloaded.execute(buy).equals(bought) && reloaded.tradeResults().size() == 2, "存档保留回执，不重放交易");
        var legacy = mapper.valueToTree(bought);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("tradeReceipt");
        check(mapper.treeToValue(legacy, ExecutionResult.class).tradeReceipt() == null, "旧记录缺少回执时仍视为未知");
        check(world.shop.quantity(SUPPLIES) == 17, "资金不足时不部分购买");
        world.fleet.cargo.credits.set(100000);
        world.shop.items.put(SUPPLIES, 1f);
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 2)), FAILED);
        check(world.shop.quantity(SUPPLIES) == 1, "读取当前库存，不使用规划时的快照");
        world.freeShop = true;
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        world.freeShop = false; world.hiddenShop = true;
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        check(world.shop.quantity(SUPPLIES) == 1, "不将免费仓储或隐藏库存当成商品");
        check(world.history.snapshot().completedStepIds().containsAll(List.of(buy.id(), sell.id())), "执行器为规划器记录真实成功结果");
        check(world.history.snapshot().recentResults().stream().filter(result -> result.step().id().equals(buy.id())).count() == 1,
                "重复读取成功结果不产生重复历史");
        Step changed = new Step(buy.id(), buy.action(), Map.of(), buy.description(), buy.expectedOutcome());
        try { executor.execute(changed); throw new AssertionError("错误地接受了被修改的步骤 ID"); }
        catch (IllegalArgumentException expected) {}
        Executor restored = world.executor();
        status(restored.execute(buy), FAILED);
        check(world.shop.quantity(SUPPLIES) == 1, "更换执行器后，已完成历史仍防止重放");
        World largeBalance = new World(); largeBalance.orbit();
        largeBalance.fleet.cargo.credits.set(1_000_000_000);
        var rounded = largeBalance.executor().execute(trade("BUY", "COMMODITY", "supplies", 1));
        check(rounded.tradeReceipt().quotedTotal() == 120 && rounded.tradeReceipt().creditsSpent() == 128,
                "回执单独报告实际浮点余额差与报价");
    }

    private static void unavailablePurchase() {
        for (float stock : new float[]{0.9f, 0f}) {
            World world = new World(); world.orbit();
            world.shop.items.put(SUPPLIES, stock);
            float credits = world.fleet.cargo.credits.get();
            Executor executor = world.executor();
            Step buy = trade("BUY", "COMMODITY", "supplies", 16);
            ExecutionResult result = executor.execute(buy);
            check(result.status() == FAILED && new Monitor().check(result) == Monitor.Decision.REPLAN,
                    "不足一件或无货立即失败并要求重规划，不处于等待状态");
            check(result.result().contains("请求购买 16") && result.result().contains("可成交整数数量 0")
                    && result.result().contains("open_market"), "失败信息区分请求数量、市场库存和交易区");
            check(world.fleet.cargo.credits.get() == credits && world.shop.quantity(SUPPLIES) == stock,
                    "缺货失败不扣款，不部分成交");
            check(executor.execute(buy).equals(result), "失败步骤不在下一帧自动重试");
        }
    }

    private static void focusedWorld() {
        World world = new World();
        var unrelated = com.mozhi.fleet.game.GameWorld.observations(world.sector, world.fleet.api, "自主跑商", null);
        check(((List<?>) unrelated.get("markets")).isEmpty(), "自主跑商不发送全部市场目录");
        world.shop.items.put(new Item(CargoAPI.CargoItemType.WEAPONS, "unrelated-weapon"), 100f);
        world.player.cargo.items.put(new Item(CargoAPI.CargoItemType.WEAPONS, "private-player-item"), 100f);
        var focused = com.mozhi.fleet.game.GameWorld.observations(world.sector, world.fleet.api, "在 market 购买 supplies", null);
        var markets = (List<?>) focused.get("markets");
        var shops = (List<?>) ((Map<?, ?>) markets.get(0)).get("submarkets");
        var items = (List<?>) ((Map<?, ?>) shops.get(0)).get("items");
        check(items.size() == 1 && ((Map<?, ?>) items.get(0)).get("itemId").equals("supplies"), "保留指定市场和商品，省略无关装备");
        check(!focused.containsKey("playerFleet") && !focused.toString().contains("private-player-item"), "不向规划器发送玩家库存");
        check(focused.get("inventoryScope").toString().contains("省略不代表无货"), "投影明确区分省略库存与没有库存");
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
                && !ship.mothballed && world.fleet.cargo.credits.get() == 0, "购买原始舰船并保留其装备");
        status(executor.execute(trade("SELL", "SHIP", "for-sale", 1)), SUCCEEDED);
        check(world.shop.ships.members.contains(ship.api) && !world.fleet.ships.members.contains(ship.api)
                && ship.mothballed && world.fleet.cargo.credits.get() == 400, "出售原始舰船并入账其价值");
        check(ship.captain != captain && world.fleet.ships.officers.contains(officer), "出售舰船不带走军官");
        status(executor.execute(trade("SELL", "SHIP", "fleet-own", 1)), FAILED);
        world.fleet.cargo.credits.set(10000);
        Item special = new Item(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData("blueprint", "hull-a"));
        Item other = new Item(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData("blueprint", "hull-b"));
        world.shop.items.put(special, 1f); world.shop.items.put(other, 1f);
        status(executor.execute(trade("BUY", "SPECIAL", "blueprint", 1)), FAILED);
        Map<String, Object> exact = new LinkedHashMap<>(trade("BUY", "SPECIAL", "blueprint", 1).parameters());
        exact.put("itemData", "hull-a");
        status(executor.execute(step("BUY", exact)), SUCCEEDED);
        check(world.fleet.cargo.quantity(special) == 1 && world.shop.quantity(other) == 1, "保留特殊物品实例数据");
        Item weapon = new Item(CargoAPI.CargoItemType.WEAPONS, "weapon-id");
        Item fighter = new Item(CargoAPI.CargoItemType.FIGHTER_CHIP, "fighter-id");
        world.shop.items.put(weapon, 2f); world.shop.items.put(fighter, 2f);
        status(executor.execute(trade("BUY", "WEAPON", "weapon-id", 1)), SUCCEEDED);
        status(executor.execute(trade("BUY", "FIGHTER", "fighter-id", 1)), SUCCEEDED);
        check(world.fleet.cargo.quantity(weapon) == 1 && world.fleet.cargo.quantity(fighter) == 1, "交易武器和战机 LPC");
    }

    private static void transactionFailures() {
        World world = new World(); world.orbit();
        Executor executor = world.executor();
        world.fleet.cargo.failAdds = 1;
        Step broken = trade("BUY", "COMMODITY", "supplies", 3);
        status(executor.execute(broken), FAILED);
        check(world.shop.quantity(SUPPLIES) == 20 && world.fleet.cargo.quantity(SUPPLIES) == 0
                && world.fleet.cargo.credits.get() == 10000, "添加失败时恢复来源库存与资金");
        status(executor.execute(broken), FAILED);
        check(world.shop.quantity(SUPPLIES) == 20, "不自动重试同一失败步骤");
        world.fleet.cargo.failAfterAdd = true;
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 3)), FAILED);
        check(world.shop.quantity(SUPPLIES) == 20 && world.fleet.cargo.quantity(SUPPLIES) == 0, "可恢复先写入再抛出异常的操作");
        Ship ship = new Ship("rollback-ship"); ship.mothballed = true;
        world.shop.ships.members.add(ship.api); world.fleet.failSync = true;
        status(executor.execute(trade("BUY", "SHIP", "rollback-ship", 1)), FAILED);
        check(world.shop.ships.members.contains(ship.api) && !world.fleet.ships.members.contains(ship.api)
                && ship.mothballed && world.fleet.cargo.credits.get() == 10000, "后续阶段失败时恢复舰船与星币");

        World uncertain = new World(); uncertain.orbit();
        Executor blocked = uncertain.executor();
        uncertain.fleet.cargo.failAdds = 1; uncertain.shop.failAdds = 1;
        status(blocked.execute(trade("BUY", "COMMODITY", "supplies", 2)), FAILED);
        check(blocked.isBlocked(), "回滚失败阻止后续动作");
        float quantity = uncertain.shop.quantity(SUPPLIES);
        status(blocked.execute(trade("BUY", "COMMODITY", "supplies", 1)), FAILED);
        check(uncertain.shop.quantity(SUPPLIES) == quantity, "资产变更无法确认时不再进行后续交易");
    }

    private static void returnAndMerge() {
        World world = new World();
        Executor executor = world.executor();
        Step recall = step("RETURN", Map.of());
        world.player.position.set(8000, 0);
        status(executor.execute(recall), RUNNING);
        check(world.fleet.target == world.player.api, "返航导航至当前玩家舰队");
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
        check(world.fleet.expired && !world.entities.contains(world.fleet.api), "完成转移后才移除已合并舰队");
        check(world.player.ships.members.size() == 4 && world.player.ships.members.contains(own.api)
                && world.player.ships.members.contains(stored.api), "原始现役和存储舰船只合并一次");
        check(world.player.ships.officers.contains(officer) && own.captain == captain && world.fleet.ships.officers.isEmpty(), "保留军官和舰长");
        check(world.player.api.getFlagship() == flagship, "保留玩家原有旗舰");
        check(world.player.cargo.quantity(SUPPLIES) == 15 && world.fleet.cargo.quantity(SUPPLIES) == 0
                && world.player.cargo.credits.get() == 1000 && world.fleet.cargo.credits.get() == 0, "货物与星币只合并一次");

        World order = new World();
        Executor guarded = order.executor();
        status(guarded.execute(Plan.create("无效顺序", List.of(step("RETURN", Map.of()), trade("BUY", "COMMODITY", "supplies", 1))), 0), FAILED);
        check(!order.fleet.expired, "拒绝在计划末尾之前返航");
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
        check(target.getQuantity(SUPPLIES.type(), SUPPLIES.data()) == 10, "原生 getQuantity 不包含小数累计量");
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 10.25f, "读取原生小数余额");
        World world = new World(); world.fleet.cargoOverride = source; world.player.cargoOverride = target;
        source.getCredits().set(300); target.getCredits().set(700);
        status(world.executor().execute(step("RETURN", Map.of())), SUCCEEDED);
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 94.625f
                && com.mozhi.fleet.actions.CargoAmounts.quantity(source, SUPPLIES.type(), SUPPLIES.data()) == 0, "返航保留原生货舱小数数量");
        check(world.fleet.expired && target.getCredits().get() == 1000, "原生货舱合并完成");

        source = nativeCargo(100, true); source.removeSupplies(.25f); // 负 partial：真实余额 99.75。
        target = nativeCargo(10, true); target.addSupplies(.5f);
        var tx = new com.mozhi.fleet.actions.AssetTransaction();
        tx.moveItems(source, target, SUPPLIES.type(), SUPPLIES.data(), 84.375f);
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(source, SUPPLIES.type(), SUPPLIES.data()) == 15.375f, "计入负的小数累计量");
        RuntimeException rolledBack = tx.rollback(new IllegalStateException("后续操作失败"));
        check(!(rolledBack instanceof com.mozhi.fleet.actions.UncertainActionException), "确认原生小数数量已回滚");
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(source, SUPPLIES.type(), SUPPLIES.data()) == 99.75f
                && com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 10.5f, "回滚恢复可见数量与隐藏余额");

        source = new com.fs.starfarer.campaign.fleet.CargoData(true); source.initPartialsIfNeeded(); source.addSupplies(.375f);
        target = nativeCargo(10, true);
        world = new World(); world.fleet.cargoOverride = source; world.player.cargoOverride = target;
        com.fs.starfarer.api.Global.setSettings(world.settings);
        com.fs.starfarer.api.Global.setFactory(world.factory);
        var spec = proxy(CommoditySpecAPI.class, (method, args) -> method.equals("getId") ? "supplies" : null);
        var settings = proxy(SettingsAPI.class, (method, args) -> method.equals("getAllCommoditySpecs") ? List.of(spec) : null);
        var executor = new Executor(new ActionContext(world.sector, world.fleet.api, settings, world.factory), new ExecutionHistory());
        status(executor.execute(step("RETURN", Map.of())), SUCCEEDED);
        check(com.mozhi.fleet.actions.CargoAmounts.quantity(target, SUPPLIES.type(), SUPPLIES.data()) == 10.375f, "合并包含没有货堆、只有小数累计量的商品");
        System.out.println("原生 CargoData 小数合并与回滚检查通过");
    }

    private static void nativeTradeRemainder() {
        World buyWorld = new World(); buyWorld.orbit();
        var shelf = nativeCargo(8.516921f, false);
        buyWorld.shopOverride = shelf;
        var bought = buyWorld.executor().execute(trade("BUY", "COMMODITY", "supplies", 8));
        status(bought, SUCCEEDED);
        check(shelf.getCommodityQuantity("supplies") == 0 && buyWorld.fleet.cargo.quantity(SUPPLIES) == 8
                && bought.tradeReceipt().creditsSpent() == 960, "原生清理市场尾数后仍正常买入八件并结算");

        World sellWorld = new World(); sellWorld.orbit();
        var cargo = nativeCargo(8.516921f, false); cargo.getCredits().set(1000);
        sellWorld.fleet.cargoOverride = cargo;
        var sold = sellWorld.executor().execute(trade("SELL", "COMMODITY", "supplies", 8));
        status(sold, SUCCEEDED);
        check(cargo.getCommodityQuantity("supplies") == 0 && sellWorld.shop.quantity(SUPPLIES) == 28
                && sold.tradeReceipt().creditsReceived() == 512, "原生清理舰队尾数后仍正常卖出八件并结算");
        System.out.println("原生买卖尾数清理检查通过");
    }

    private static void resourceTransfersWithoutReserves() {
        Item crew = new Item(CargoAPI.CargoItemType.RESOURCES, "crew");
        Item fuel = new Item(CargoAPI.CargoItemType.RESOURCES, "fuel");
        for (int allocation : new int[]{0, 10, 180}) {
            World world = new World();
            world.entities.remove(world.fleet.api); world.fleet.location = null;
            world.fleet.ships.members.clear(); world.fleet.cargo.credits.set(0);
            var retained = new Ship("retained"); retained.minimumCrew = 880;
            var selected = new Ship("selected"); selected.minimumCrew = 800;
            world.player.ships.members.clear();
            world.player.ships.members.add(retained.api); world.player.ships.members.add(selected.api);
            world.player.cargo.credits.set(5000);
            world.player.cargo.items.put(crew, 180f);
            world.player.cargo.items.put(SUPPLIES, 2000f);
            world.player.cargo.items.put(fuel, 1500f);
            com.mozhi.fleet.game.FleetDeployment.depart(world.player.api, world.fleet.api,
                    List.of("selected"), 5000, 2000, 1500, allocation);
            check(world.player.cargo.credits.get() == 0 && world.player.cargo.quantity(SUPPLIES) == 0
                    && world.player.cargo.quantity(fuel) == 0 && world.player.cargo.quantity(crew) == 180 - allocation,
                    "派遣不为玩家保留资源，即使低于最低船员人数");
            check(world.fleet.cargo.credits.get() == 5000 && world.fleet.cargo.quantity(SUPPLIES) == 2000
                    && world.fleet.cargo.quantity(fuel) == 1500 && world.fleet.cargo.quantity(crew) == allocation
                    && world.fleet.ships.members.contains(selected.api), "按指定数量派遣，允许缺员和超载");
        }
        World shortfall = new World();
        shortfall.entities.remove(shortfall.fleet.api); shortfall.fleet.location = null;
        shortfall.fleet.ships.members.clear(); shortfall.fleet.cargo.credits.set(0);
        var selected = new Ship("selected"); shortfall.player.ships.members.add(selected.api);
        shortfall.player.cargo.items.put(crew, 100f);
        try {
            com.mozhi.fleet.game.FleetDeployment.depart(shortfall.player.api, shortfall.fleet.api,
                    List.of("selected"), 0, 0, 0, 200);
            throw new AssertionError("不得划拨不存在的船员");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("200") && expected.getMessage().contains("100"), "缺口错误报告请求数量与可用数量");
        }
        check(shortfall.player.ships.members.contains(selected.api) && shortfall.fleet.ships.members.isEmpty()
                && shortfall.player.cargo.quantity(crew) == 100 && shortfall.fleet.location == null, "不可执行的派遣不改变任何资产");

        World trade = new World(); trade.orbit();
        var executor = trade.executor();
        for (String resource : List.of("crew", "fuel", "supplies")) {
            Item item = new Item(CargoAPI.CargoItemType.RESOURCES, resource);
            trade.fleet.cargo.items.put(item, 20f);
            status(executor.execute(trade("SELL", "COMMODITY", resource, 21)), FAILED);
            check(trade.fleet.cargo.quantity(item) == 20, "库存不足时失败，不部分出售");
            status(executor.execute(trade("SELL", "COMMODITY", resource, 20)), SUCCEEDED);
            check(trade.fleet.cargo.quantity(item) == 0, "允许卖光所有资源，不强制保留安全库存");
        }
        trade.shop.items.put(SUPPLIES, 2000f); trade.fleet.cargo.credits.set(240000);
        status(executor.execute(trade("BUY", "COMMODITY", "supplies", 2000)), SUCCEEDED);
        check(trade.fleet.cargo.quantity(SUPPLIES) == 2000 && trade.fleet.cargo.credits.get() == 0,
                "允许按指定数量买入，即使超载且花完星币");
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
                        check(prompt.contains("open_market") && prompt.contains("supplies"), "世界快照包含真实交易 ID");
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
        check(!json.readTree(runtime.command("{\"operation\":\"preview\",\"ships\":[\"selected\"]}")).has("error"), "预览桥接正常");
        String dispatched = runtime.command("{\"operation\":\"dispatch\",\"ships\":[\"selected\"],\"credits\":1000,\"supplies\":10,\"fuel\":0,\"crew\":0}");
        check(!json.readTree(dispatched).has("error"), "派遣桥接正常：" + dispatched);
        check(world.fleet.ships.members.contains(selected.api) && !world.player.ships.members.contains(selected.api), "派遣转移原始舰船");
        check(world.player.cargo.credits.get() == 4000 && world.fleet.cargo.credits.get() == 1000
                && world.player.cargo.quantity(SUPPLIES) == 30 && world.fleet.cargo.quantity(SUPPLIES) == 10, "派遣精确转移指定资源");
        check(calls.get() == 0, "派遣不需要调用模型");
        check(!json.readTree(runtime.command("{\"operation\":\"follow\",\"targetFleet\":\"player\"}")).has("error"), "跟随命令直接创建计划");
        runtime.advance(0);
        var followingView = json.valueToTree(runtime.view());
        check(followingView.path("state").path("plan").path("steps").get(0).path("action").asText().equals("FOLLOW_FLEET")
                && followingView.path("state").path("mission").path("status").asText().equals("EXECUTING")
                && world.fleet.ships.members.contains(selected.api), "桥接跟随即使靠近也不合并舰队");
        check(json.readTree(runtime.command("{\"operation\":\"follow\",\"targetFleet\":\"missing\"}")).has("error")
                && followingView.path("state").path("mission").path("id").equals(json.valueToTree(runtime.view()).path("state").path("mission").path("id")), "无效跟随目标不覆盖原任务");
        runtime.command("{\"operation\":\"cancel\"}");
        check(json.valueToTree(runtime.view()).path("state").path("mission").path("status").asText().equals("CANCELLED")
                && world.fleet.assignment == FleetAssignment.HOLD, "停止跟随通过命令链路原地待命");
        String moved = runtime.command("{\"operation\":\"move\",\"destination\":\"market\"}");
        check(!json.readTree(moved).has("error"), "直接移动桥接接受已知目的地");
        var beforeTransfer = json.valueToTree(runtime.view()).path("state").path("plan");
        var sent = json.readTree(runtime.command("{\"operation\":\"transferToMozhi\",\"amount\":4000}"));
        check(sent.path("status").asText().equals("SUCCEEDED") && sent.path("amount").asInt() == 4000
                && world.player.cargo.credits.get() == 0 && world.fleet.cargo.credits.get() == 5000, "玩家可转走全部余额");
        check(json.readTree(runtime.command("{\"operation\":\"transferToMozhi\",\"amount\":1}")).has("error")
                && world.fleet.cargo.credits.get() == 5000 && world.player.cargo.credits.get() == 0, "余额不足是普通工具错误且不扣款");
        check(!json.readTree(runtime.command("{\"operation\":\"transferToPlayer\",\"amount\":5000}")).has("error")
                && world.fleet.cargo.credits.get() == 0 && world.player.cargo.credits.get() == 5000, "分舰队也可转走全部余额");
        runtime.command("{\"operation\":\"transferToMozhi\",\"amount\":1000}");
        for (String invalid : List.of("0", "-1", "1e40", "\"20\"", "null")) {
            check(json.readTree(runtime.command("{\"operation\":\"transferToPlayer\",\"amount\":" + invalid + "}")).has("error"), "拒绝无效金额：" + invalid);
        }
        check(world.player.cargo.credits.get() == 4000 && world.fleet.cargo.credits.get() == 1000, "无效转账不修改余额");
        check(beforeTransfer.equals(json.valueToTree(runtime.view()).path("state").path("plan")), "双向转账不改变正在执行的计划");
        runtime.advance(0); world.orbit(); runtime.advance(0);
        long reviewDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!json.valueToTree(runtime.view()).path("state").path("mission").path("status").asText().equals("COMPLETED") && System.nanoTime() < reviewDeadline) { runtime.advance(0); Thread.sleep(2); }
        check(json.valueToTree(runtime.view()).path("state").path("mission").path("status").asText().equals("COMPLETED"), "移动要求实际进入轨道");
        check(calls.get() == 0, "直接移动不需要调用模型");
        check(json.valueToTree(runtime.view()).path("state").path("awaitingReturnConfirmation").asBoolean(), "未授权返航时，成功后等待玩家决定");
        runtime.command("{\"operation\":\"order\",\"instruction\":\"在 market 买2补给后回归\",\"returnAfterCompletion\":true}");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (world.shop.quantity(SUPPLIES) == 20 && System.nanoTime() < deadline) { runtime.advance(0); Thread.sleep(2); }
        check(world.shop.quantity(SUPPLIES) == 18, "游戏桥接驱动真实购买动作：" + runtime.view());
        check(json.valueToTree(runtime.view()).path("state").path("tradeReceipts").get(0).path("creditsSpent").asDouble() == 240,
                "状态公开实际购买支出");
        var compactStatus = json.readTree(runtime.command("{\"operation\":\"status\"}"));
        check(!compactStatus.path("state").has("tradeReceipts") && !compactStatus.path("state").has("usageStatistics")
                && compactStatus.path("state").path("tradeSummary").path("creditsSpent").asDouble() == 240, "默认状态提供汇总，不包含完整账本或诊断信息");
        String task = compactStatus.path("state").path("mission").path("id").asText();
        var page = json.readTree(runtime.command(json.writeValueAsString(Map.of("operation", "tradeReceipts", "taskId", task, "offset", 0, "limit", 1))));
        check(page.path("receipts").size() == 1 && page.path("receipts").get(0).path("creditsSpent").asDouble() == 240 && page.path("nextOffset").asInt() == -1,
                "分页账本返回准确的真实购买回执");
        check(json.readTree(runtime.command("{\"operation\":\"tradeReceipts\",\"taskId\":\"old-task\",\"offset\":0,\"limit\":1}")).has("error"), "防止混合不同任务的回执");
        int callsBeforeRestore = calls.get();
        runtime.save(); runtime.close();
        check(world.persistent.get(com.mozhi.fleet.game.FleetRuntime.SAVE_KEY) instanceof String, "仅保存 JSON，不保存私有加载器对象");
        var restored = new com.mozhi.fleet.game.FleetRuntime(world.sector, planners, () -> world.fleet.api);
        restored.initialize("file:/unused.properties");
        check(json.valueToTree(restored.view()).path("state").path("plan").path("currentStep").asInt() == 1, "精确恢复执行位置");
        check(json.valueToTree(restored.view()).path("state").path("plan").path("steps").get(0).path("tradeReceipt").path("creditsSpent").asDouble() == 240,
                "读档后计划步骤保留购买回执");
        reviewDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!json.valueToTree(restored.view()).path("state").path("mode").asText().equals("MERGED") && System.nanoTime() < reviewDeadline) { restored.advance(0); Thread.sleep(2); }
        check(json.valueToTree(restored.view()).path("state").path("mode").asText().equals("MERGED"), "读档后继续返航：" + restored.view());
        check(world.shop.quantity(SUPPLIES) == 18 && calls.get() == callsBeforeRestore, "读档不重复已完成购买，也不对其重规划");
        check(world.player.cargo.quantity(SUPPLIES) == 42 && world.player.cargo.credits.get() == 4760, "一次购买后的合并保持资产守恒");
        check(json.valueToTree(restored.view()).path("state").path("mission").path("status").asText().equals("COMPLETED"), "授权返航在目标验收后执行");
        restored.save(); restored.close();
        var reviewing = new com.mozhi.fleet.game.FleetRuntime(world.sector, planners, () -> world.fleet.api);
        reviewing.initialize("file:/unused.properties");
        reviewDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!json.valueToTree(reviewing.view()).path("state").path("mission").path("status").asText().equals("COMPLETED") && System.nanoTime() < reviewDeadline) { reviewing.advance(0); Thread.sleep(2); }
        check(json.valueToTree(reviewing.view()).path("state").path("mission").path("status").asText().equals("COMPLETED"), "合并和存读档后即使没有受控舰队也可继续验收");
        check(world.player.cargo.quantity(SUPPLIES) == 42, "验收不会重复合并资产");
        reviewing.close();

        World rollback = new World();
        rollback.entities.remove(rollback.fleet.api); rollback.fleet.location = null;
        rollback.fleet.ships.members.clear(); rollback.fleet.cargo.credits.set(0);
        var ship = new Ship("dispatch-rollback"); rollback.player.ships.members.add(ship.api);
        rollback.player.cargo.items.put(SUPPLIES, 20f); rollback.player.cargo.credits.set(1000);
        rollback.fleet.cargo.failAfterAdd = true;
        try {
            com.mozhi.fleet.game.FleetDeployment.depart(rollback.player.api, rollback.fleet.api, List.of("dispatch-rollback"), 100, 10, 0, 0);
            throw new AssertionError("预期派遣资产变更失败");
        } catch (IllegalStateException expected) { }
        check(rollback.player.ships.members.contains(ship.api) && rollback.fleet.ships.members.isEmpty()
                && rollback.player.cargo.quantity(SUPPLIES) == 20 && rollback.fleet.cargo.quantity(SUPPLIES) == 0
                && rollback.player.cargo.credits.get() == 1000 && rollback.fleet.location == null, "派遣失败回滚全部资产");
        System.out.println("游戏桥接、派遣与存读档检查通过");
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
            check(type.getClassLoader() == loader, "游戏运行时从私有 JAR 加载");
            var config = root.resolve("data/config/agent.properties");
            if (java.nio.file.Files.isRegularFile(config)) {
                loader.loadClass("com.mozhi.llm.LlmConfig").getMethod("load", String.class).invoke(null, config.toUri().toString());
                System.out.println("本地模型配置解析完成，未发送网络请求");
            }
            var runtime = (com.mozhi.assistant.bridge.FleetAgentBridge) type.getConstructor().newInstance();
            try {
                runtime.initialize("file:/unused.properties");
                String response = runtime.command("{\"operation\":\"recall\"}");
                check(!new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).has("error"), "跨加载器召回请求被接受");
                runtime.advance(0);
                runtime.save();
                var state = (Map<?, ?>) runtime.view().get("state");
                check(state.get("mode").equals("MERGED"), "打包入口可以执行返航并仅使用 JDK 类型报告状态");
            } finally { runtime.close(); }
            long stopDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!runtime.isStopped() && System.nanoTime() < stopDeadline) Thread.sleep(2);
            check(runtime.isStopped(), "验收规划器关闭后不泄漏线程");
        }
        System.out.println("打包后的私有类加载器与桥接检查通过");
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
                if (add && failAdds > 0) { failAdds--; throw new IllegalStateException("模拟添加失败"); }
                Item item = new Item((CargoAPI.CargoItemType) a[0], a[1]);
                items.put(item, quantity(item) + (float) a[2] * (add ? 1 : -1));
                if (add && failAfterAdd) { failAfterAdd = false; throw new IllegalStateException("模拟资产变更后失败"); }
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
            case "getMinCrew" -> (float) members.stream().mapToDouble(FleetMemberAPI::getMinCrew).sum();
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
        float minimumCrew;
        PersonAPI captain = proxy(PersonAPI.class, (m, a) -> null);
        final FleetMemberAPI api;
        Ship(String id) {
            RepairTrackerAPI repairs = proxy(RepairTrackerAPI.class, (m, a) -> {
                if (m.equals("setMothballed")) mothballed = (boolean) a[0];
                return null;
            });
            api = proxy(FleetMemberAPI.class, (m, a) -> switch (m) {
                case "getMinCrew" -> minimumCrew;
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
                case "forceSync" -> { if (failSync) { failSync = false; throw new IllegalStateException("模拟同步失败"); } yield null; }
                default -> null;
            });
        }
    }

    private static final class World {
        CargoAPI shopOverride;
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
                    if (failRemove) { failRemove = false; throw new IllegalStateException("模拟移除失败"); }
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
                case "getCargo" -> shopOverride == null ? shop.api : shopOverride; case "getPlugin" -> plugin; case "getSpecId", "getNameOneLine" -> "open_market";
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
