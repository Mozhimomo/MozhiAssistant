package com.mozhi.fleet.trading;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 整批报价与空间位置的不可变快照；后台计算只能持有这些值。 */
public record TradeSnapshot(Fleet fleet, List<Market> markets, int skipped) {
    public TradeSnapshot { markets = List.copyOf(markets); }
    public enum Hold { CARGO, FUEL, PERSONNEL }
    public record Point(String locationId, boolean hyperspace, double x, double y, double hyperX,
                        double hyperY, double jumpDistance) {}
    public record Fleet(Point position, double credits, double cargoRoom, double fuelRoom, double personnelRoom,
                        double fuel, double supplies, double fuelPerLy, double suppliesPerDay,
                        double lyPerDay, double unitsPerDay) {
        public Fleet {
            for (double value : new double[]{credits, cargoRoom, fuelRoom, personnelRoom, fuel, supplies, fuelPerLy, suppliesPerDay})
                TradeRouteOptions.nonnegative(value, "舰队快照");
            TradeRouteOptions.positive(lyPerDay, "超空间航速"); TradeRouteOptions.positive(unitsPerDay, "星系内航速");
        }
        public double room(Hold hold) { return switch (hold) { case CARGO -> cargoRoom; case FUEL -> fuelRoom; case PERSONNEL -> personnelRoom; }; }
    }
    /** quantities 对应的报价为整批总价（已含关税、取整），负一表示本方向不能交易该数量。 */
    public record Quote(String commodityId, String name, Hold hold, double space, String submarketId,
                        Map<Integer, Double> buys, Map<Integer, Double> sells) {
        public Quote { buys = Map.copyOf(buys); sells = Map.copyOf(sells); TradeRouteOptions.positive(space, "单位占用空间"); }
    }
    public record Market(String id, String name, String destinationId, Point position, List<Quote> quotes) {
        public Market { quotes = List.copyOf(quotes); }
    }
    public record Travel(double days, double fuel, double supplies, double lightYears) {}
    public Travel travel(Point from, Point to) {
        double ly = 0, local;
        if (from.locationId().equals(to.locationId()) && !from.hyperspace()) local = Math.hypot(from.x() - to.x(), from.y() - to.y());
        else {
            ly = Math.hypot(from.hyperX() - to.hyperX(), from.hyperY() - to.hyperY());
            local = from.jumpDistance() + to.jumpDistance();
        }
        double days = ly / fleet.lyPerDay() + local / fleet.unitsPerDay() + 0.25; // 每站入轨/停靠时间估计。
        return new Travel(days, ly * fleet.fuelPerLy(), days * fleet.suppliesPerDay(), ly);
    }
    /** 所有市场使用同一组数量，选中的数量均有实测整批报价，不用单价线性外推。 */
    public static List<Integer> quantities(int cap) {
        TreeSet<Integer> values = new TreeSet<>();
        for (int q = 1; q <= Math.min(cap, 8); q++) values.add(q);
        for (int q = 16; q <= cap && q > 0; q *= 2) values.add(q);
        for (int i = 1; i <= 16; i++) { int q = (int) ((long) cap * i / 16); if (q > 0) values.add(q); }
        return List.copyOf(values);
    }
}
