package com.mozhi.fleet.model;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一份不可变的有序行动计划，可作为后台规划与游戏主线程之间的数据。
 * 执行下标、状态和实际结果由运行时单独保存；执行完所有步骤不等于已经完成任务目标。
 */
public record Plan(String id, String goal, List<Step> steps) {
    public Plan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("计划 ID 不能为空");
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("计划目标不能为空");
        Objects.requireNonNull(steps, "计划步骤不能为空");
        if (steps.isEmpty()) throw new IllegalArgumentException("计划至少需要一个步骤");
        steps = List.copyOf(steps);
        Set<String> ids = new HashSet<>();
        for (Step step : steps) {
            if (!ids.add(step.id())) throw new IllegalArgumentException("计划中存在重复步骤 ID：" + step.id());
        }
    }

    /** 为新计划分配 ID；重规划可复用仍然有效的 Step，保留其身份。 */
    public static Plan create(String goal, List<Step> steps) {
        return new Plan(UUID.randomUUID().toString(), goal, steps);
    }

    /** 在刚完成的步骤之后展开决策结果，保留原始目标、已有步骤身份及后续步骤。 */
    public Plan insertAfter(int stepIndex, Plan addition) {
        if (stepIndex < 0 || stepIndex >= steps.size()) throw new IllegalArgumentException("插入位置越界");
        List<Step> expanded = new ArrayList<>(steps.subList(0, stepIndex + 1));
        expanded.addAll(Objects.requireNonNull(addition).steps());
        expanded.addAll(steps.subList(stepIndex + 1, steps.size()));
        return create(goal, expanded);
    }
}
