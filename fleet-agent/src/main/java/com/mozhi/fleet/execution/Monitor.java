package com.mozhi.fleet.execution;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.FleetResources;
import com.mozhi.fleet.model.ResourceCheck;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/** 无状态的执行结果检查器。只返回检查结论，由 Agent 决定如何推进循环。 */
public final class Monitor {
    public static final double FUEL_RANGE_LY = 15, SUPPLY_DAYS = 30;
    private static final double EPSILON = 0.0001;
    public enum Decision { CONTINUE, ADVANCE, REPLAN }
    public record Review(Decision decision, ResourceCheck resources) {}

    public Review check(ExecutionResult result, FleetResources resources) {
        return new Review(check(result), checkResources(resources));
    }

    public ResourceCheck checkResources(FleetResources r) {
        Objects.requireNonNull(r);
        var issues = new LinkedHashSet<String>();
        var notes = new ArrayList<String>();
        if (r.fuelPerLightYear() * FUEL_RANGE_LY - r.fuel() > EPSILON) {
            issues.add("fuel"); notes.add("当前燃料航程不足 15 光年");
        }
        if (r.fuelPerLightYear() * FUEL_RANGE_LY - r.fuelCapacity() > EPSILON) {
            issues.add("fuelCapacity");
            notes.add(String.format(Locale.ROOT, "满油最多航行 %.1f 光年，可考虑向玩家建议增加油船", r.fuelCapacity() / r.fuelPerLightYear()));
        }
        if (r.suppliesPerDay() * SUPPLY_DAYS - r.supplies() > EPSILON) {
            issues.add("supplies"); notes.add("当前补给不足以支持 30 天");
        }
        if (r.minimumCrew() - r.crew() > EPSILON) {
            issues.add("crew"); notes.add("当前船员低于舰队最低需求人数");
        }
        String reason = issues.isEmpty() ? "未发现后勤建议事项" : "后勤建议（不是执行限制）：" + String.join("；", notes)
                + "。后勤健康是持续任务的第一优先级：通常应 REPLAN 先补充、再跑商。已有有效补充安排或明确暂时无法补充时才 KEEP，并说明依据。数量由规划器决定，执行器不因建议自动停止任务。";
        return new ResourceCheck(issues.isEmpty() ? ResourceCheck.Status.READY : ResourceCheck.Status.ADVISORY, r, issues, reason);
    }

    public static double amount(FleetResources r, String id) {
        return switch (id) { case "fuel" -> r.fuel(); case "supplies" -> r.supplies(); case "crew" -> r.crew(); default -> throw new IllegalArgumentException("未知后勤资源：" + id); };
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
