package com.mozhi.fleet.planning;

import com.mozhi.fleet.model.Plan;
import java.util.Objects;

/** 候选规划结果。GOAL_REACHED 只是模型判断，任务是否结束由 Agent 核实。 */
public record PlanningResult(String taskId, long revision, Decision decision, String reason, Plan plan) {
    public enum Decision { REPLACE, KEEP, GOAL_REACHED, BLOCKED }

    public PlanningResult {
        ActionSpec.text(taskId, "任务 ID");
        if (revision < 0) throw new IllegalArgumentException("快照版本不能为负数");
        Objects.requireNonNull(decision, "规划决策");
        ActionSpec.text(reason, "规划原因");
        boolean needsPlan = decision == Decision.REPLACE || decision == Decision.KEEP;
        if (needsPlan != (plan != null)) throw new IllegalArgumentException("规划决策与计划不一致");
    }
}
