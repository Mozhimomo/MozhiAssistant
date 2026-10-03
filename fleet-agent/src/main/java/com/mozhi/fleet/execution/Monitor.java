package com.mozhi.fleet.execution;

import com.mozhi.fleet.model.ExecutionResult;
import java.util.Objects;

/** 无状态的执行结果检查器。只返回检查结论，由 Agent 决定如何推进循环。 */
public final class Monitor {
    public enum Decision { CONTINUE, ADVANCE, REPLAN }

    public Decision check(ExecutionResult result) {
        Objects.requireNonNull(result, "执行结果不能为空");
        return switch (result.status()) {
            case RUNNING, WAITING -> Decision.CONTINUE;
            case SUCCEEDED -> Decision.ADVANCE;
            case FAILED -> Decision.REPLAN;
        };
    }
}
