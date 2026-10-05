package com.mozhi.fleet.model;

import java.util.Set;
import java.util.Objects;

/** 只描述当前风险，不指定采购量或强制重规划。issues 是供轻量模型判断的建议类型。 */
public record ResourceCheck(Status status, FleetResources snapshot, Set<String> issues, String reason) {
    public enum Status { READY, ADVISORY }
    public ResourceCheck {
        Objects.requireNonNull(status); Objects.requireNonNull(snapshot); Objects.requireNonNull(reason);
        issues = Set.copyOf(issues);
    }
}
