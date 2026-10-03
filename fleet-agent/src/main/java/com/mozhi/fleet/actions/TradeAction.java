package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.model.TradeReceipt;
import com.mozhi.fleet.planning.ActionSpec;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static com.mozhi.fleet.model.ExecutionResult.Status.SUCCEEDED;

/** 买卖共享的本地交易实现；只操作明确指定的交易区，不自动移动或拆分成交。 */
abstract class TradeAction implements Action {
    private enum ItemType { COMMODITY, WEAPON, FIGHTER, HULLMOD, SPECIAL, SHIP }
    private final boolean buy;
    private final ActionSpec spec;

    TradeAction(boolean buy) {
        this.buy = buy;
        spec = new ActionSpec(buy ? "BUY" : "SELL",
                (buy ? "购买" : "出售") + "指定交易区的真实货物或舰船。必须先 MOVE_TO 对应市场并实际入轨；"
                        + "按实时库存、价格和关税一次性成交，成功返回 tradeReceipt 实际支出 creditsSpent、收入 creditsReceived 及含税报价 quotedTotal；库存或资金不足时失败；不自动导航，不部分成交。",
                List.of(ActionSupport.parameter("marketId", ActionSpec.Type.STRING, true, "市场 ID"),
                        ActionSupport.parameter("submarketId", ActionSpec.Type.STRING, true, "交易区 ID；不可使用免费仓储或隐藏交易区"),
                        ActionSupport.parameter("itemType", ActionSpec.Type.STRING, true, "COMMODITY、WEAPON、FIGHTER、HULLMOD、SPECIAL 或 SHIP"),
                        ActionSupport.parameter("itemId", ActionSpec.Type.STRING, true, "物品规格 ID；SHIP 必须使用舰船实例 ID"),
                        ActionSupport.parameter("quantity", ActionSpec.Type.INTEGER, true, "1 至 1000000；SHIP 必须为 1"),
                        ActionSupport.parameter("itemData", ActionSpec.Type.STRING, false, "仅 SPECIAL 使用；同 ID 多种特殊物品时必须给出实例数据")));
    }

    @Override public final ActionSpec spec() { return spec; }

    @Override public final ExecutionResult execute(Step step, ActionContext context) {
        int quantity = ActionSupport.quantity(step);
        ItemType type;
        try { type = ItemType.valueOf(ActionSupport.text(step, "itemType")); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("不支持的 itemType", error); }
        String itemId = ActionSupport.text(step, "itemId");
        if (type != ItemType.SPECIAL && step.parameters().containsKey("itemData")) throw new IllegalArgumentException("只有 SPECIAL 接受 itemData");
        if (type == ItemType.SHIP && quantity != 1) throw new IllegalArgumentException("按舰船实例交易时 quantity 必须为 1");
        MarketAPI market = ActionSupport.market(context, ActionSupport.text(step, "marketId"));
        if (!ActionSupport.orbiting(context.fleet(), ActionSupport.marketEntity(market))) {
            throw new IllegalStateException("尚未实际环绕指定市场，请先完成 MOVE_TO");
        }
        String shopId = ActionSupport.text(step, "submarketId");
        var matches = market.getSubmarketsCopy().stream().filter(shop -> shopId.equals(shop.getSpecId())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("交易区不存在或 ID 不唯一：" + shopId);
        SubmarketAPI shop = matches.get(0);
        if (shop.getPlugin() == null || shop.getPlugin().isFreeTransfer() || shop.getPlugin().isHidden()) {
            throw new IllegalArgumentException("该交易区不支持买卖");
        }
        shop.getPlugin().updateCargoPrePlayerInteraction();
        CargoAPI cargo = Objects.requireNonNull(shop.getCargo(), "市场库存不可用");
        if (cargo == context.fleet().getCargo()) throw new IllegalStateException("交易区与舰队共用货舱，不能交易");
        CargoAPI source = buy ? cargo : context.fleet().getCargo();
        CargoAPI target = buy ? context.fleet().getCargo() : cargo;
        FleetDataAPI ships = buy ? cargo.getMothballedShips() : context.fleet().getFleetData();
        FleetMemberAPI ship = null;
        CargoStackAPI stack = null;
        if (type == ItemType.SHIP) {
            var found = ships == null ? List.<FleetMemberAPI>of() : ships.getMembersListCopy().stream()
                    .filter(member -> itemId.equals(member.getId()) && !member.isFighterWing() && !member.isStation()).toList();
            if (found.size() != 1) throw new IllegalArgumentException("实际库存中没有该舰船实例：" + itemId);
            ship = found.get(0);
            if (!buy && ships.getNumMembers() <= 1) throw new IllegalArgumentException("不能出售分舰队最后一艘舰船");
        } else {
            String data = step.parameters().containsKey("itemData") ? (String) step.parameters().get("itemData") : null;
            var found = source.getStacksCopy().stream().filter(entry -> matches(entry, type, itemId, data)).toList();
            if (found.isEmpty()) throw new IllegalArgumentException("实际库存中没有该物品：" + itemId);
            stack = found.get(0);
            Object key = stack.getData();
            if (found.stream().anyMatch(entry -> !Objects.equals(entry.getData(), key))) {
                throw new IllegalArgumentException("特殊物品存在多种实例数据，请指定 itemData");
            }
            if (source.getQuantity(stack.getType(), stack.getData()) < quantity) throw new IllegalArgumentException("实际库存不足，未成交");
        }
        double total = price(context, market, shop, stack, ship, quantity);
        float balance = context.fleet().getCargo().getCredits().get();
        if (!Float.isFinite(balance) || balance < 0) throw new IllegalStateException("舰队信用点无效");
        if (buy && balance < total) throw new IllegalArgumentException("信用点不足，需要 " + total + "，实际 " + balance);
        float next = (float) (balance + (buy ? -total : total));
        if (!Float.isFinite(next) || next < 0) throw new IllegalArgumentException("交易金额超出可结算范围");
        AssetTransaction transaction = new AssetTransaction();
        try {
            if (ship != null) {
                if (!buy && cargo.getMothballedShips() == null) cargo.initMothballedShips(shop.getFaction().getId());
                FleetDataAPI destination = buy ? context.fleet().getFleetData() : cargo.getMothballedShips();
                transaction.moveShip(ships, Objects.requireNonNull(destination, "舰船目标库存不可用"), ship);
                ship.getRepairTracker().setMothballed(!buy);
                if (!buy) ship.setCaptain(context.factory().createPerson()); // 军官继续留在分舰队。
            } else transaction.moveItems(source, target, stack.getType(), stack.getData(), quantity);
            transaction.credits(context.fleet().getCargo(), next);
            context.fleet().forceSync();
        } catch (RuntimeException failure) { throw transaction.rollback(failure); }
        double actual = buy ? (double) balance - next : (double) next - balance;
        TradeReceipt receipt = new TradeReceipt(buy ? actual : 0, buy ? 0 : actual, total);
        return new ExecutionResult(step, SUCCEEDED, String.format(Locale.ROOT,
                "已在 %s / %s %s %d × %s，%s %.0f 信用点（含关税）", market.getName(), shopId,
                buy ? "购买" : "出售", quantity, itemId, buy ? "支出" : "收入", actual), null, receipt);
    }

    private static boolean matches(CargoStackAPI stack, ItemType type, String id, String data) {
        if (stack.getSize() <= 0) return false;
        return switch (type) {
            case COMMODITY -> stack.isCommodityStack() && id.equals(stack.getCommodityId());
            case WEAPON -> stack.isWeaponStack() && id.equals(stack.getWeaponSpecIfWeapon().getWeaponId());
            case FIGHTER -> stack.isFighterWingStack() && id.equals(stack.getFighterWingSpecIfWing().getId());
            case HULLMOD -> stack.isModSpecStack() && id.equals(stack.getHullModSpecIfHullMod().getId());
            case SPECIAL -> stack.isSpecialStack() && id.equals(stack.getSpecialDataIfSpecial().getId())
                    && (data == null || data.equals(Objects.toString(stack.getSpecialDataIfSpecial().getData(), "")));
            case SHIP -> false;
        };
    }

    private double price(ActionContext context, MarketAPI market, SubmarketAPI shop,
                         CargoStackAPI stack, FleetMemberAPI ship, int quantity) {
        double base;
        if (ship != null) base = buy ? ship.getBaseBuyValue() : ship.getBaseSellValue();
        else if (stack.isCommodityStack()) return CommodityPricing.quote(market, shop, stack.getCommodityId(), quantity, buy);
        else {
            base = (double) (stack.isSpecialStack() && stack.getPlugin() != null
                    ? stack.getPlugin().getPrice(market, shop) : stack.getBaseValuePerUnit()) * quantity;
            String prefix = stack.isWeaponStack() || stack.isFighterWingStack() ? "shipWeapon" : "nonEconItem";
            base *= context.settings().getFloat(prefix + (buy ? "BuyPriceMult" : "SellPriceMult"));
        }
        double tariff = shop.getTariff();
        if (!Double.isFinite(base) || base < 0 || !Double.isFinite(tariff) || tariff < 0 || tariff > 1) throw new IllegalStateException("报价或关税无效");
        double total = base * (buy ? 1 + tariff : 1 - tariff);
        if (!Double.isFinite(total) || total > Integer.MAX_VALUE) throw new IllegalStateException("交易金额过大");
        return Math.round(total);
    }
}
