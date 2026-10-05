package com.mozhi.fleet.trading;

import java.util.*;
import static com.mozhi.fleet.trading.TradeSnapshot.*;
import static com.mozhi.fleet.trading.TradeCargoPacker.*;

/** 航段规划：先把所需后勤采购写入结果，再计算贸易装载；执行器不追加保留量限制。 */
public final class TradeHopPlanner {
    public record Resources(double cash, double cargo, double fuel, double supplies) {}
    public record Purchase(Quote quote, int quantity, double cost) {}
    public record Hop(Market from, Market to, List<Purchase> purchases, Load load, Travel travel, Resources after) {
        public Hop { purchases = List.copyOf(purchases); }
    }
    private final TradeSnapshot snapshot;
    private final TradeRouteOptions options;
    private final TradeQuotes prices;
    private final TradeCargoPacker packer;
    private final Map<String, Load> cache = new HashMap<>();
    public TradeHopPlanner(TradeSnapshot snapshot, TradeRouteOptions options, TradeQuotes prices, Runnable checkpoint) {
        this.snapshot = snapshot; this.options = options; this.prices = prices;
        packer = new TradeCargoPacker(prices, options, checkpoint);
    }
    public Resources initial() {
        var f = snapshot.fleet();
        return new Resources(f.credits(), f.cargoRoom(), f.fuel(), f.supplies());
    }
    public Resources position(Travel trip) {
        var f = snapshot.fleet();
        if (trip.fuel() > f.fuel()) return null;
        double consumed = Math.min(f.supplies(), trip.supplies());
        return new Resources(f.credits(), f.cargoRoom() + consumed * f.supplySpace(), f.fuel() - trip.fuel(), f.supplies() - consumed);
    }
    public Load estimate(Market from, Market to) {
        var f = snapshot.fleet();
        double room = Math.max(0, f.cargoRoom() - Math.max(0, options.reserveSupplies() - f.supplies()) * f.supplySpace());
        Load load = packer.pack(from, to, room, spend(f.credits()), f.fuelRoom(), Map.of());
        cache.put(key(from, to), load);
        return load;
    }
    public Hop plan(Market from, Market to, Resources at, boolean allowEmpty) {
        Travel trip = snapshot.travel(from.position(), to.position());
        var f = snapshot.fleet();
        double tankMax = f.fuel() + f.fuelRoom();
        if (trip.fuel() > tankMax || trip.days() > options.maxDays()) return null;
        List<Purchase> purchases = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        double budget = spend(at.cash());
        double fuelWanted = Math.max(0, Math.min(tankMax, trip.fuel() + options.reserveFuel()) - at.fuel());
        double fuelBought = purchase(from, "fuel", fuelWanted, Math.max(0, tankMax - at.fuel()), budget, purchases, used);
        double spent = purchases.stream().mapToDouble(Purchase::cost).sum();
        double supplyWanted = Math.max(0, trip.supplies() + options.reserveSupplies() - at.supplies());
        double supplyBought = purchase(from, "supplies", supplyWanted, at.cargo() / f.supplySpace(), budget - spent, purchases, used);
        spent = purchases.stream().mapToDouble(Purchase::cost).sum();
        double fuelReady = at.fuel() + fuelBought, suppliesReady = at.supplies() + supplyBought;
        // 参数中的保留量是本次规划约束，不是买卖动作的隐含拦截条件。
        if (fuelReady + 0.001 < trip.fuel() + options.reserveFuel() || suppliesReady + 0.001 < trip.supplies() + options.reserveSupplies()) return null;
        double cargo = Math.max(0, at.cargo() - supplyBought * f.supplySpace());
        double fuelRoom = Math.max(0, tankMax - fuelReady);
        Load load = cache.get(key(from, to));
        if (load == null || load.cost() > budget - spent || load.cargo() > cargo || load.fuel() > fuelRoom
                || !fitsStock(load, used)) {
            load = packer.pack(from, to, cargo, budget - spent, fuelRoom, used);
            if (!load.lots().isEmpty()) cache.put(key(from, to), load);
        }
        if (load.lots().isEmpty() && !allowEmpty) return null;
        return new Hop(from, to, purchases, load, trip,
                new Resources(at.cash() - spent + load.profit(), cargo + trip.supplies() * f.supplySpace(),
                        fuelReady - trip.fuel(), suppliesReady - trip.supplies()));
    }
    private double purchase(Market market, String id, double wanted, double room, double money,
                            List<Purchase> purchases, Map<String, Integer> used) {
        int remaining = (int) Math.max(0, Math.min(Math.ceil(wanted), Math.floor(room)));
        int bought = 0;
        for (Quote q : market.quotes().stream().sorted(Comparator.comparing(Quote::black).reversed()).toList()) {
            if (!q.commodityId().equals(id) || !q.canBuy() || q.black() && !options.allowBlackMarket() || remaining == 0) continue;
            int qty = packer.affordable(market, q, Math.min(remaining, q.buyCap()), money);
            if (qty <= 0) continue;
            double cost = prices.quote(market, q, qty, true);
            if (cost < 0 || cost > money) continue;
            purchases.add(new Purchase(q, qty, cost)); used.merge(TradeCargoPacker.key(q), qty, Integer::sum);
            remaining -= qty; bought += qty; money -= cost;
        }
        return bought;
    }
    private static boolean fitsStock(Load load, Map<String, Integer> used) {
        Map<String, Integer> quantities = new HashMap<>(used);
        for (Lot lot : load.lots()) if (quantities.merge(TradeCargoPacker.key(lot.buy()), lot.quantity(), Integer::sum) > lot.buy().buyCap()) return false;
        return true;
    }
    private double spend(double cash) { return Math.max(0, Math.min(options.maxSpend(), cash - options.reserveCredits())); }
    private static String key(Market from, Market to) { return from.id() + ">" + to.id(); }
}
