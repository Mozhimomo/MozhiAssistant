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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static com.mozhi.fleet.model.ExecutionResult.Status.SUCCEEDED;

/** 买卖共享的本地交易实现；只操作明确指定的交易区，不自动移动或拆分成交。 */
abstract class TradeAction implements Action {
    private enum ItemType { COMMODITY, WEAPON, FIGHTER, HULLMOD, SPECIAL, SHIP }
    private final boolean buy;
    TradeAction(boolean buy) { this.buy = buy; }

    protected final ExecutionResult trade(Step step, ActionContext context, String marketId, String submarketId,
                                           String itemType, String itemId, int quantity, String itemData) {
        if (quantity < 1 || quantity > 1_000_000) throw new IllegalArgumentException("quantity 必须在 1 至 1000000 之间");
        ItemType type;
        try { type = ItemType.valueOf(itemType); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("不支持的 itemType", error); }

        if (type != ItemType.SPECIAL && step.parameters().containsKey("itemData")) throw new IllegalArgumentException("只有 SPECIAL 接受 itemData");
        if (type == ItemType.SHIP && quantity != 1) throw new IllegalArgumentException("按舰船实例交易时 quantity 必须为 1");
        MarketAPI market = ActionSupport.market(context, marketId);
        if (!ActionSupport.orbiting(context.fleet(), ActionSupport.marketEntity(market))) {
            throw new IllegalStateException("尚未实际环绕指定市场，请先完成 MOVE_TO");
        }
        String shopId = submarketId;
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
            String data = itemData;
            var found = source.getStacksCopy().stream().filter(entry -> matches(entry, type, itemId, data)).toList();
            if (found.isEmpty()) throw unavailable(market, shopId, itemId, quantity, 0);
            stack = found.get(0);
            Object key = stack.getData();
            if (found.stream().anyMatch(entry -> !Objects.equals(entry.getData(), key))) {
                throw new IllegalArgumentException("特殊物品存在多种实例数据，请指定 itemData");
            }
            float available = source.getQuantity(stack.getType(), stack.getData());
            if (!Float.isFinite(available) || available < quantity) throw unavailable(market, shopId, itemId, quantity, available);
        }
        double total = price(context, market, shop, stack, ship, quantity);
        float balance = context.fleet().getCargo().getCredits().get();
        if (!Float.isFinite(balance) || balance < 0) throw new IllegalStateException("舰队星币无效");
        if (buy && balance < total) throw new IllegalArgumentException("星币不足，需要 " + total + "，实际 " + balance);
        float next = (float) (balance + (buy ? -total : total));
        if (!Float.isFinite(next) || next < 0) throw new IllegalArgumentException("交易金额超出可结算范围");
        AssetTransaction transaction = AssetTransaction.trade();
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
                "已在 %s / %s %s %d × %s，%s %.0f 星币（含关税）", market.getName(), shopId,
                buy ? "购买" : "出售", quantity, itemId, buy ? "支出" : "收入", actual), null, receipt);
    }

    private IllegalArgumentException unavailable(MarketAPI market, String shopId, String itemId, int requested, float available) {
        return new IllegalArgumentException("实际库存不足：市场 " + market.getId() + " / " + shopId + "，物品 " + itemId
                + "，请求" + (buy ? "购买 " : "出售 ") + requested + "，"
                + (buy ? "市场" : "舰队") + "实际库存 " + available + "，可成交整数数量 "
                + (Float.isFinite(available) ? (long) Math.max(0, Math.floor(available)) : "未知")
                + "；未成交，请重新规划；库存小数不是采购指令");
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
