package com.mozhi.fleet.execution;

import com.mozhi.fleet.model.*;
import java.util.Map;

public final class ResourceChecks {
    public static void main(String[] args) {
        Monitor monitor = new Monitor();
        var exact = new FleetResources(30, 30, 2, 60, 2, 10, 10);
        check(monitor.checkResources(exact).status() == ResourceCheck.Status.READY, "正好满足 15 光年、30 天与最低船员时不产生建议");
        var low = monitor.checkResources(new FleetResources(29.25, 40, 2, 58.1, 2, 8, 10));
        check(low.status() == ResourceCheck.Status.ADVISORY && low.issues().equals(java.util.Set.of("fuel", "supplies", "crew")), "短缺只报告建议事项，不规定采购数量");
        check(low.reason().contains("不是执行限制") && low.reason().contains("KEEP"), "建议明确交由轻量模型决定");
        var partial = monitor.checkResources(new FleetResources(30, 40, 2, 60, 2, 10, 10));
        check(partial.status() == ResourceCheck.Status.READY && partial.issues().isEmpty(), "当前阈值观测不强制规定补充目标");
        var impossible = monitor.checkResources(new FleetResources(29, 29, 2, 60, 2, 10, 10));
        check(impossible.status() == ResourceCheck.Status.ADVISORY && impossible.issues().contains("fuelCapacity") && impossible.reason().contains("油船"), "油箱容量不足只作建议，不强制阻塞");
        check(monitor.checkResources(new FleetResources(0, 0, 0, 0, 0, 0, 0)).status() == ResourceCheck.Status.READY, "零消耗时不发生除零");
        check(monitor.checkResources(new FleetResources(30, 30, 2.00000001, 60, 2, 10, 10)).status() == ResourceCheck.Status.READY, "忽略容量阈值附近的微小浮点误差");
        Step work = Step.create("WORK", Map.of(), "工作", "完成");
        var review = monitor.check(new ExecutionResult(work, ExecutionResult.Status.SUCCEEDED, "完成"), low.snapshot());
        check(review.decision() == Monitor.Decision.ADVANCE && review.resources().status() == ResourceCheck.Status.ADVISORY, "资源触发重规划时仍保留成功记录");
        check(monitor.checkResources(new FleetResources(29, 30, 2, 60, 2, 10, 10)).status() == ResourceCheck.Status.ADVISORY,
                "正好支持 15 光年的油箱仍可加油");
        try { new FleetResources(Double.NaN, 1, 1, 1, 1, 1, 1); throw new AssertionError("错误地接受了无效快照"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("资源监视器检查通过");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
