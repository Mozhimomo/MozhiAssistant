package com.mozhi.fleet.model;

import java.util.Map;
import java.util.Objects;

/** purchases 是补至恢复目标的采购量，不是补至触发线的缺口；另计采购途中消耗。 */
public record ResourceCheck(Status status, FleetResources snapshot, Map<String, Integer> purchases, String reason,
                            Map<String, Double> targets) {
    public enum Status { READY, REPLAN, BLOCKED }
    public ResourceCheck {
        Objects.requireNonNull(status); Objects.requireNonNull(snapshot); Objects.requireNonNull(reason);
        purchases = Map.copyOf(purchases);
        targets = Map.copyOf(targets);
    }
}
