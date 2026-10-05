package com.mozhi.fleet.trading;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 经济层的轻量快照；数量是估算值，整批价格通过报价接口按需读取。 */
public record TradeSnapshot(Fleet fleet, List<Market> markets, int skipped) {
    public TradeSnapshot { markets = List.copyOf(markets); }
    public enum Hold { CARGO, FUEL, PERSONNEL }
    public record Point(String locationId, boolean hyperspace, double x, double y, double hyperX,
                        double hyperY, double jumpDistance) {}
    public record Fleet(Point position, double credits, double cargoRoom, double fuelRoom, double personnelRoom,
                        double fuel, double supplies, double fuelPerLy, double suppliesPerDay,
                        double lyPerDay, double unitsPerDay, double supplySpace) {
        public Fleet(Point position, double credits, double cargoRoom, double fuelRoom, double personnelRoom,
                     double fuel, double supplies, double fuelPerLy, double suppliesPerDay, double lyPerDay, double unitsPerDay) {
            this(position, credits, cargoRoom, fuelRoom, personnelRoom, fuel, supplies, fuelPerLy, suppliesPerDay, lyPerDay, unitsPerDay, 1);
        }
        public Fleet {
            for (double value : new double[]{credits, cargoRoom, fuelRoom, personnelRoom, fuel, supplies, fuelPerLy, suppliesPerDay})
                TradeRouteOptions.nonnegative(value, "舰队快照");
            TradeRouteOptions.positive(lyPerDay, "超空间航速"); TradeRouteOptions.positive(unitsPerDay, "星系内航速");
            TradeRouteOptions.positive(supplySpace, "补给占用空间");
        }
        public double room(Hold hold) { return switch (hold) { case CARGO -> cargoRoom; case FUEL -> fuelRoom; case PERSONNEL -> personnelRoom; }; }
    }
    /** 买入上限只用于规划；到站后用真实货架替换。报价表用于离线测试，不作线性外推。 */
    public record Quote(String commodityId, String name, Hold hold, double space, String submarketId,
                        Map<Integer, Double> buys, Map<Integer, Double> sells, int buyCap, double econUnit,
                        int excess, int deficit, boolean canBuy, boolean canSell) {
        public Quote { buys = Map.copyOf(buys); sells = Map.copyOf(sells); TradeRouteOptions.positive(space, "单位占用空间"); }
        public Quote(String id, String name, Hold hold, double space, String channel, Map<Integer, Double> buys, Map<Integer, Double> sells) {
            this(id, name, hold, space, channel, buys, sells, buys.keySet().stream().mapToInt(Integer::intValue).max().orElse(0),
                    1, 0, 0, !buys.isEmpty(), !sells.isEmpty());
        }
        public boolean black() { return submarketId.equals("black_market"); }
    }
    public record Market(String id, String name, String destinationId, Point position, List<Quote> quotes, double closedDays) {
        public Market { quotes = List.copyOf(quotes); }
        public Market(String id, String name, String destinationId, Point position, List<Quote> quotes) {
            this(id, name, destinationId, position, quotes, 0);
        }
    }
    public record Travel(double days, double fuel, double supplies, double lightYears) {}
    public Travel travel(Point from, Point to) {
        double ly = 0, local;
        if (from.locationId().equals(to.locationId()) && !from.hyperspace()) local = Math.hypot(from.x() - to.x(), from.y() - to.y());
        else {
            ly = Math.hypot(from.hyperX() - to.hyperX(), from.hyperY() - to.hyperY());
            local = from.jumpDistance() + to.jumpDistance();
        }
        double days = ly / fleet.lyPerDay() + local / fleet.unitsPerDay();
        return new Travel(days, ly * fleet.fuelPerLy(), days * fleet.suppliesPerDay(), ly);
    }
    /** 采样最大值、分数档位、经济单位与固定数量；选中的数量仍须读取整批报价。 */
    public static List<Integer> quantities(int cap) {
        return quantities(cap, 1);
    }
    public static List<Integer> quantities(int cap, double econUnit) {
        TreeSet<Integer> values = new TreeSet<>();
        if (cap <= 0) return List.of();
        int unit = (int) Math.max(1, Math.min(1_000_000, Math.round(econUnit)));
        values.add(cap); values.add(Math.max(1, cap / 2)); values.add(Math.max(1, cap / 4));
        values.add(Math.min(cap, unit));
        if (unit * 2 < cap) values.add(unit * 2);
        for (int q : new int[]{10, 25, 50, 100, 250, 500, 1000, 2000}) if (q <= cap) values.add(q);
        return List.copyOf(values);
    }
}
