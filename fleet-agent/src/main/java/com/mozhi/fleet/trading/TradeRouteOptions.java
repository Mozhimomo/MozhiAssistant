package com.mozhi.fleet.trading;

import java.util.List;
import java.util.Map;

/** 玩家/Planner 明确给出的约束；不含游戏对象。 */
public record TradeRouteOptions(int maxStops, double maxDays, double maxStartDistanceLy,
        double maxSpend, double minProfit, double reserveCredits, double reserveFuel, double reserveSupplies,
        boolean allowBlackMarket, boolean closedLoop, List<String> commodityIds) {
    public TradeRouteOptions {
        if (maxStops < 2 || maxStops > 6) throw new IllegalArgumentException("maxStops 必须为 2 至 6");
        positive(maxDays, "maxDays"); positive(maxSpend, "maxSpend");
        nonnegative(maxStartDistanceLy, "maxStartDistanceLy"); nonnegative(minProfit, "minProfit");
        nonnegative(reserveCredits, "reserveCredits"); nonnegative(reserveFuel, "reserveFuel"); nonnegative(reserveSupplies, "reserveSupplies");
        commodityIds = List.copyOf(commodityIds);
        if (commodityIds.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("商品 ID 不能为空");
    }
    public static TradeRouteOptions from(Map<String, Object> p) {
        double stops = number(p, "maxStops", 4);
        if (stops != Math.rint(stops) || stops < 2 || stops > 6) throw new IllegalArgumentException("maxStops 必须为 2 至 6 的整数");
        Object goods = p.getOrDefault("commodityIds", List.of());
        if (!(goods instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException("commodityIds 必须是商品 ID 字符串列表");
        return new TradeRouteOptions((int) stops, number(p, "maxDays", 30), number(p, "maxStartDistanceLy", 10),
                number(p, "maxSpend", 1_000_000_000), number(p, "minProfit", 1), number(p, "reserveCredits", 0),
                number(p, "reserveFuel", 0), number(p, "reserveSupplies", 0), flag(p, "allowBlackMarket"), flag(p, "closedLoop"),
                list.stream().map(String.class::cast).toList());
    }
    private static boolean flag(Map<String, Object> p, String key) {
        Object value = p.getOrDefault(key, key.equals("allowBlackMarket"));
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(key + " 必须为布尔值");
        return flag;
    }
    private static double number(Map<String, Object> p, String key, double fallback) {
        Object value = p.getOrDefault(key, fallback);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " 必须为数值");
        return number.doubleValue();
    }
    static void nonnegative(double n, String name) { if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException(name + " 必须为有限非负数"); }
    static void positive(double n, String name) { nonnegative(n, name); if (n == 0) throw new IllegalArgumentException(name + " 必须大于零"); }
}
