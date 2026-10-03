package com.mozhi.fleet.execution;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.FleetResources;
import com.mozhi.fleet.model.ResourceCheck;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/** 无状态的执行结果检查器。只返回检查结论，由 Agent 决定如何推进循环。 */
public final class Monitor {
    public static final double FUEL_RANGE_LY = 15, SUPPLY_DAYS = 30;
    public static final double REFILL_SUPPLY_DAYS = 45, CREW_BUFFER = 1.2;
    private static final double EPSILON = 0.0001;
    public enum Decision { CONTINUE, ADVANCE, REPLAN }
    public record Review(Decision decision, ResourceCheck resources) {}

    public Review check(ExecutionResult result, FleetResources resources) {
        return new Review(check(result), checkResources(resources));
    }

    public Review check(ExecutionResult result, FleetResources resources, Map<String, Double> targets) {
        return new Review(check(result), checkResources(resources, targets));
    }

    public ResourceCheck checkResources(FleetResources r) {
        return checkResources(r, Map.of());
    }

    public ResourceCheck checkResources(FleetResources r, Map<String, Double> pendingTargets) {
        Objects.requireNonNull(r);
        double fuelTarget = r.fuelPerLightYear() * FUEL_RANGE_LY;
        if (fuelTarget - r.fuelCapacity() > EPSILON) {
            return new ResourceCheck(ResourceCheck.Status.BLOCKED, r, Map.of(), String.format(Locale.ROOT,
                    "油箱容量不足：加满燃料最多航行 %.1f 光年，低于 15 光年。补油无法解决，请玩家购买油船并编入墨汁舰队后重新下达任务。",
                    r.fuelCapacity() / r.fuelPerLightYear()), Map.of());
        }
        Map<String, Double> targets = new LinkedHashMap<>();
        for (var entry : pendingTargets.entrySet())
            if (entry.getValue() - amount(r, entry.getKey()) > EPSILON) targets.put(entry.getKey(), entry.getValue());
        if (fuelTarget - r.fuel() > EPSILON) targets.put("fuel", r.fuelCapacity());
        if (r.suppliesPerDay() * SUPPLY_DAYS - r.supplies() > EPSILON) targets.put("supplies", r.suppliesPerDay() * REFILL_SUPPLY_DAYS);
        if (r.minimumCrew() - r.crew() > EPSILON) targets.put("crew", Math.max(r.minimumCrew() + 1, Math.ceil(r.minimumCrew() * CREW_BUFFER)));
        if (targets.containsKey("fuel")) {
            if (r.fuelCapacity() - fuelTarget <= EPSILON) return new ResourceCheck(ResourceCheck.Status.BLOCKED, r, Map.of(),
                    "油箱加满也无法留出超过 15 光年的燃料余量，请玩家购买油船并调整分舰队配置。", Map.of());
            targets.put("fuel", r.fuelCapacity());
        }
        Map<String, Integer> needs = new LinkedHashMap<>();
        targets.forEach((id, target) -> need(needs, id, target - amount(r, id)));
        String reason = needs.isEmpty() ? "舰队资源充足" : "舰队资源不足，优先补购：" + needs.entrySet().stream()
                .map(entry -> switch (entry.getKey()) { case "fuel" -> "燃料"; case "crew" -> "船员"; default -> "补给"; }
                        + "至少 " + entry.getValue()).collect(java.util.stream.Collectors.joining("、"))
                + "；15 光年、30 天和最低船员数仅为触发线。对应补购目标：燃料加满、补给 45 天、船员至少最低人数的 120%（至少多 1 人），另计采购途中消耗；不能只补到触发线。";
        return new ResourceCheck(needs.isEmpty() ? ResourceCheck.Status.READY : ResourceCheck.Status.REPLAN, r, needs, reason, targets);
    }

    public static double amount(FleetResources r, String id) {
        return switch (id) { case "fuel" -> r.fuel(); case "supplies" -> r.supplies(); case "crew" -> r.crew(); default -> throw new IllegalArgumentException("未知后勤资源：" + id); };
    }

    private static void need(Map<String, Integer> needs, String id, double deficit) {
        if (deficit <= EPSILON) return;
        if (!Double.isFinite(deficit) || deficit > Integer.MAX_VALUE) throw new IllegalArgumentException("后勤需求超出可规划范围");
        needs.put(id, (int) Math.ceil(deficit - EPSILON));
    }

    /** 补购前缀只允许移动和采购后勤商品，不能先跑商、出售、召回或计算其他路线。 */
    public boolean coversResupply(Plan plan, int start, ResourceCheck check, boolean checkQuantities) {
        if (plan == null) return false;
        Map<String, Double> quantities = new LinkedHashMap<>();
        for (int i = start; i < plan.steps().size(); i++) {
            Step step = plan.steps().get(i);
            if (step.action().equals("MOVE_TO")) continue;
            Object id = step.parameters().get("itemId"), quantity = step.parameters().get("quantity");
            if (!step.action().equals("BUY") || !"COMMODITY".equals(step.parameters().get("itemType"))
                    || !(id instanceof String commodity) || !java.util.Set.of("fuel", "supplies", "crew").contains(commodity)
                    || !(quantity instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() <= 0) break;
            quantities.merge(commodity, n.doubleValue(), Double::sum);
        }
        return check.purchases().entrySet().stream().allMatch(need -> quantities.getOrDefault(need.getKey(), 0d)
                >= (checkQuantities ? need.getValue() : 1));
    }

    public Decision check(ExecutionResult result) {
        Objects.requireNonNull(result, "执行结果不能为空");
        return switch (result.status()) {
            case RUNNING, WAITING -> Decision.CONTINUE;
            case SUCCEEDED -> Decision.ADVANCE;
            case FAILED -> Decision.REPLAN;
        };
    }
}
