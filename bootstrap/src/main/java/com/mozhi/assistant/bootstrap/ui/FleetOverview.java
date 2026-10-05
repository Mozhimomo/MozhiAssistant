package com.mozhi.assistant.bootstrap.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 从实时快照提取资源显示值；缺失数据不伪装成零，不影响任何执行规则。 */
record FleetOverview(boolean present, String location, String credits, String footer, String empty,
                     List<Metric> metrics) {
    record Metric(String title, String value, String detail, boolean warning) {}
    static FleetOverview from(Map<String, Object> data) {
        Map<?, ?> state = map(data.get("state")), logistics = map(data.get("logistics"));
        boolean present = data.get("fleetId") instanceof String id && !id.isBlank() && !logistics.isEmpty();
        String mode = String.valueOf(state.get("mode"));
        String empty = data.containsKey("error") ? "舰队状态暂不可用，请稍后重试"
                : mode.equals("MERGED") ? "分舰队已回归，资产已合并至玩家舰队"
                : mode.equals("LOST") ? "分舰队已失联或被移除" : "尚未派遣分舰队，可在对话中安排派遣";
        Double crew = number(logistics, "crew"), required = number(logistics, "requiredCrew");
        Double range = number(logistics, "fuelRangeLy"), days = number(logistics, "supplyDays");
        Double daily = number(logistics, "suppliesPerDay"), readiness = number(logistics, "readiness");
        String endurance = daily != null && daily == 0 ? "无日常消耗" : days == null ? "续航未知" : "约 " + format(days) + " 天";
        return new FleetOverview(present, data.get("location") instanceof String location ? location : "位置未知",
                format(number(logistics, "credits")),
                (data.get("ships") instanceof List<?> ships ? ships.size() + " 艘舰船" : "舰船数未知")
                        + "   ·   剩余货舱 " + format(number(logistics, "cargoSpaceLeft")), empty,
                List.of(new Metric("补给", format(number(logistics, "supplies")), endurance,
                                days != null && days < 30 && (daily == null || daily > 0)),
                        new Metric("燃料", format(number(logistics, "fuel")) + " / " + format(number(logistics, "fuelCapacity")),
                                range == null ? "航程未知" : "约 " + format(range) + " 光年", range != null && range < 15),
                        new Metric("船员", format(crew), "最低需求 " + format(required), crew != null && required != null && crew < required),
                        new Metric("平均战备", readiness == null ? "—" : String.format(Locale.ROOT, "%.0f%%", readiness * 100),
                                "不含封存舰船", false)));
    }
    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static Double number(Map<?, ?> values, String key) {
        return values.get(key) instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : null;
    }
    private static String format(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, value == Math.rint(value) || Math.abs(value) >= 1000 ? "%,.0f" : "%,.1f", value);
    }
}
