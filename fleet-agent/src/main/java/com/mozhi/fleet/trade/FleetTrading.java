package com.mozhi.fleet.trade;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.Global;
import com.mozhi.fleet.game.FleetDestinations;
import com.mozhi.fleet.model.FleetPlanStep;
import java.util.*;

/** NPC 交易：到场读取现货、报价，再在主线程一次性转移实物和信用点。 */
public final class FleetTrading {
    public static final class UncertainTransaction extends IllegalStateException {
        UncertainTransaction(String message,Throwable cause) {super(message,cause);}
    }
    private record Lot(FleetTradeInventory.Entry entry, int quantity, double price) {}
    private FleetTrading() {}

    public static String execute(CampaignFleetAPI fleet, MarketAPI market, FleetPlanStep step) {
        boolean buy = step.action == FleetPlanStep.Action.BUY;
        if (!buy && step.action != FleetPlanStep.Action.SELL) throw new IllegalArgumentException("不是交易步骤");
        if (step.quantity <= 0) throw new IllegalArgumentException("交易数量必须为正整数");
        List<SubmarketAPI> shops = shops(market, step.submarket);
        List<FleetTradeInventory.Entry> inventory = new ArrayList<>();
        if (buy) {
            Set<CargoAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (SubmarketAPI shop : shops) {
                // 走原生常规更新，不重设刷新计时，不依赖玩家是否进入市场。
                shop.getPlugin().updateCargoPrePlayerInteraction();
                CargoAPI cargo = shop.getCargo();
                if (cargo != null && seen.add(cargo))
                    inventory.addAll(FleetTradeInventory.read(shop,cargo,cargo.getMothballedShips()));
            }
        } else {
            inventory.addAll(FleetTradeInventory.read(shops.get(0),fleet.getCargo(),fleet.getFleetData()));
        }
        List<FleetTradeInventory.Entry> matches = FleetTradeInventory.matching(inventory,step.item);
        List<Lot> lots = new ArrayList<>();
        int remaining = step.quantity;
        for (var entry : matches) {
            int quantity = (int)Math.min(remaining,Math.floor(entry.available()));
            if (quantity <= 0) continue;
            lots.add(new Lot(entry,quantity,price(market,entry,quantity,buy)));
            remaining -= quantity;
            if (remaining == 0) break;
        }
        if (remaining > 0) throw new IllegalArgumentException((buy ? "市场" : "舰队")+"库存不足：需要 "
                +step.quantity+"，当前可交易数量 "+(step.quantity-remaining)+"；未成交");

        // 空舰队会被引擎移除；此限制只防止出售最后一艘船后丢失货舱和资金。
        long soldShips = buy ? 0 : lots.stream().filter(l -> l.entry().ship()!=null).count();
        if (soldShips > 0 && soldShips >= fleet.getFleetData().getNumMembers())
            throw new IllegalArgumentException("不能出售最后一艘舰船；空舰队会被游戏移除，请先召回合并");
        double total = lots.stream().mapToDouble(Lot::price).sum();
        float balance = fleet.getCargo().getCredits().get();
        if (!Double.isFinite(total) || total < 0 || !Float.isFinite(balance)) throw new IllegalStateException("交易价格或余额无效");
        if (buy && balance < total) throw new IllegalArgumentException(String.format(Locale.ROOT,
                "信用点不足：需要 %.0f，实际 %.0f；未成交",total,balance));
        float nextBalance = (float)(balance + (buy ? -total : total));
        if (!Float.isFinite(nextBalance)) throw new IllegalStateException("交易金额超出范围");
        String result = String.format(Locale.ROOT,"已在 %s %s %d × %s，%s %.0f 信用点（含交易区关税）；%s",
                market.getName(),buy?"购买":"出售",step.quantity,lots.get(0).entry().label(),buy?"支出":"收入",total,
                lots.stream().map(l -> l.entry().shop().getNameOneLine()).distinct().toList());
        List<Runnable> undo = new ArrayList<>();
        try {
            for (Lot lot : lots) transfer(fleet,lot,buy,undo);
            fleet.getCargo().getCredits().set(nextBalance);
            fleet.forceSync();
        } catch (RuntimeException failure) {
            fleet.getCargo().getCredits().set(balance);
            Collections.reverse(undo);
            for (Runnable restore : undo) {
                try { restore.run(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            if(failure.getSuppressed().length>0)
                throw new UncertainTransaction("交易回滚失败，实际资产变更尚未确认",failure);
            throw new IllegalStateException("交易执行失败，已尝试恢复本次货物和资金："+failure.getMessage(),failure);
        }
        return result;
    }

    private static List<SubmarketAPI> shops(MarketAPI market, String requested) {
        List<SubmarketAPI> result = new ArrayList<>();
        for (SubmarketAPI shop : market.getSubmarketsCopy()) {
            // 免费仓储不是出售库存；不调用依赖玩家声望/许可/UI 的玩家专用权限判断。
            if (shop.getPlugin().isFreeTransfer() || shop.getPlugin().isHidden()) continue;
            if (requested == null || requested.isBlank() || FleetDestinations.same(requested,shop.getSpecId())
                    || FleetDestinations.same(requested,shop.getNameOneLine())) result.add(shop);
        }
        result.sort(Comparator.comparingInt(s -> s.getPlugin().isOpenMarket()?0:
                s.getPlugin().isMilitaryMarket()?1:s.getPlugin().isBlackMarket()?2:3));
        if (result.isEmpty()) throw new IllegalArgumentException("市场没有对应的交易区："+Objects.toString(requested,""));
        if (requested != null && !requested.isBlank() && result.size()!=1)
            throw new IllegalArgumentException("交易区重名，请使用交易区 ID");
        return result;
    }

    private static double price(MarketAPI market, FleetTradeInventory.Entry entry, int quantity, boolean buy) {
        double base;
        if (entry.ship()!=null) base=buy?entry.ship().getBaseBuyValue():entry.ship().getBaseSellValue();
        else if (entry.stack().isCommodityStack()) {
            base=buy?market.getSupplyPrice(entry.stack().getCommodityId(),quantity,true)
                    :market.getDemandPrice(entry.stack().getCommodityId(),quantity,true);
        } else if (entry.stack().isSpecialStack() && entry.stack().getPlugin()!=null) {
            base=(double)entry.stack().getPlugin().getPrice(market,entry.shop())*quantity;
        } else base=(double)entry.stack().getBaseValuePerUnit()*quantity;
        if (entry.stack()!=null && !entry.stack().isCommodityStack()) {
            String prefix=entry.stack().isWeaponStack() || entry.stack().isFighterWingStack()
                    ? "shipWeapon" : "nonEconItem";
            base*=Global.getSettings().getFloat(prefix+(buy?"BuyPriceMult":"SellPriceMult"));
        }
        double tariff=entry.shop().getTariff();
        if (!Double.isFinite(base) || base<0 || !Double.isFinite(tariff) || tariff<0 || tariff>1)
            throw new IllegalStateException("交易区报价或关税无效");
        return Math.round(base*(buy?1+tariff:1-tariff));
    }

    private static void transfer(CampaignFleetAPI fleet, Lot lot, boolean buy, List<Runnable> undo) {
        var entry=lot.entry();
        CargoAPI shopCargo=entry.shop().getCargo();
        if (entry.ship()==null) {
            CargoAPI source=buy?entry.cargo():fleet.getCargo(), target=buy?fleet.getCargo():shopCargo;
            if(source==target) throw new IllegalStateException("交易源和目标不能是同一个仓库");
            var type=entry.stack().getType(); Object data=entry.stack().getData();
            float from=source.getQuantity(type,data), to=target.getQuantity(type,data);
            if(from<lot.quantity()) throw new IllegalStateException("货物库存已变化");
            undo.add(() -> { restoreQuantity(source,type,data,from); restoreQuantity(target,type,data,to); });
            source.removeItems(type,data,lot.quantity());
            target.addItems(type,data,lot.quantity());
            if (Math.abs(source.getQuantity(type,data)-(from-lot.quantity()))>0.01f
                    || Math.abs(target.getQuantity(type,data)-(to+lot.quantity()))>0.01f)
                throw new IllegalStateException("仓库未完成指定数量的转移");
        } else {
            if (!buy && shopCargo.getMothballedShips()==null) shopCargo.initMothballedShips(entry.shop().getFaction().getId());
            FleetDataAPI source=buy?entry.ships():fleet.getFleetData();
            FleetDataAPI target=buy?fleet.getFleetData():shopCargo.getMothballedShips();
            var ship=entry.ship();
            boolean mothballed=ship.isMothballed();
            var captain=ship.getCaptain();
            undo.add(() -> {
                target.removeFleetMember(ship);
                if(!source.getMembersListCopy().contains(ship)) source.addFleetMember(ship);
                ship.getRepairTracker().setMothballed(mothballed); ship.setCaptain(captain);
            });
            source.removeFleetMember(ship); target.addFleetMember(ship);
            ship.getRepairTracker().setMothballed(!buy);
            // 军官仍留在分舰队的军官列表中，不随船卖出。
            if (!buy) ship.setCaptain(Global.getFactory().createPerson());
            if (source.getMembersListCopy().contains(ship) || !target.getMembersListCopy().contains(ship))
                throw new IllegalStateException("舰船转移未完成");
        }
    }

    private static void restoreQuantity(CargoAPI cargo, CargoAPI.CargoItemType type, Object data, float desired) {
        float delta=desired-cargo.getQuantity(type,data);
        if(delta>0) cargo.addItems(type,data,delta);
        else if(delta<0) cargo.removeItems(type,data,-delta);
    }
}
