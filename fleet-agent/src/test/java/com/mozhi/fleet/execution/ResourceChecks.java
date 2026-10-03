package com.mozhi.fleet.execution;

import com.mozhi.fleet.model.*;
import java.util.List;
import java.util.Map;

public final class ResourceChecks {
    public static void main(String[] args) {
        Monitor monitor = new Monitor();
        var exact = new FleetResources(30, 30, 2, 60, 2, 10, 10);
        check(monitor.checkResources(exact).status() == ResourceCheck.Status.READY, "Exact 15 LY, 30 days and minimum crew are sufficient");
        var low = monitor.checkResources(new FleetResources(29.25, 40, 2, 58.1, 2, 8, 10));
        check(low.status() == ResourceCheck.Status.REPLAN && low.purchases().equals(Map.of("fuel", 11, "supplies", 32, "crew", 4)), "Purchases cover recovery targets, not warning thresholds");
        check(low.targets().equals(Map.of("fuel", 40d, "supplies", 90d, "crew", 12d)), "Targets retain real headroom");
        var partial = monitor.checkResources(new FleetResources(30, 40, 2, 60, 2, 10, 10), low.targets());
        check(partial.status() == ResourceCheck.Status.REPLAN && partial.purchases().equals(Map.of("fuel", 10, "supplies", 30, "crew", 2)), "Crossing trigger thresholds does not finish an active refill");
        var filled = monitor.checkResources(new FleetResources(40, 40, 2, 90, 2, 12, 10), partial.targets());
        check(filled.status() == ResourceCheck.Status.READY && filled.targets().isEmpty(), "Clear each target after reaching it");
        check(monitor.checkResources(new FleetResources(39, 40, 2, 88, 2, 11, 10), filled.targets()).status() == ResourceCheck.Status.READY,
                "Consumption after refill does not immediately retrigger a warning");
        var impossible = monitor.checkResources(new FleetResources(29, 29, 2, 60, 2, 10, 10));
        check(impossible.status() == ResourceCheck.Status.BLOCKED && impossible.reason().contains("油船") && impossible.reason().contains("15"), "Tank capacity has priority over repeated refueling");
        check(monitor.checkResources(new FleetResources(0, 0, 0, 0, 0, 0, 0)).status() == ResourceCheck.Status.READY, "Zero consumption does not divide by zero");
        check(monitor.checkResources(new FleetResources(30, 30, 2.00000001, 60, 2, 10, 10)).status() == ResourceCheck.Status.READY, "Ignore tiny float rounding around capacity threshold");
        Step work = Step.create("WORK", Map.of(), "工作", "完成");
        var review = monitor.check(new ExecutionResult(work, ExecutionResult.Status.SUCCEEDED, "完成"), low.snapshot());
        check(review.decision() == Monitor.Decision.ADVANCE && review.resources().status() == ResourceCheck.Status.REPLAN, "Success remains recorded even when resources trigger a replan");
        Step move = Step.create("MOVE_TO", Map.of("destinationId", "market"), "移动", "到场");
        Plan purchase = Plan.create("补购", List.of(move, buy("fuel", 11), buy("supplies", 32), buy("crew", 4), work));
        check(monitor.coversResupply(purchase, 0, low, true), "Resupply prefix covers all deficits before business");
        check(!monitor.coversResupply(Plan.create("只到警戒线", List.of(buy("fuel", 1), buy("supplies", 2), buy("crew", 2))), 0, low, true), "Reject quantities that only reach warning thresholds");
        check(!monitor.coversResupply(Plan.create("缺项", List.of(buy("fuel", 1))), 0, low, true), "Missing commodities are rejected");
        check(!monitor.coversResupply(Plan.create("顺序错误", List.of(work, buy("fuel", 1), buy("supplies", 2), buy("crew", 2))), 0, low, true), "Business before recovery is rejected");
        check(!monitor.coversResupply(purchase, 2, low, false), "Consumed purchase cannot cover a still-existing shortage");
        try { new FleetResources(Double.NaN, 1, 1, 1, 1, 1, 1); throw new AssertionError("Invalid snapshot accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("Resource monitor checks passed");
    }
    private static Step buy(String id, int quantity) { return Step.create("BUY", Map.of("itemType", "COMMODITY", "itemId", id, "quantity", quantity), "补购", "补足"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
