package com.mozhi.fleet.planning;

import com.mozhi.fleet.model.Plan;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 由主线程采集的不可变快照，不传入游戏对象。
 * revision 是调用方分配的快照版本；结果返回后调用方仍须核对当前任务和执行记录。
 * executionHistory 包含最近 20 步实际结果及跨窗口保留的已完成步骤 ID。
 */
public record PlanningRequest(String taskId, long revision, String goal, String worldState,
                              ExecutionHistory.Snapshot executionHistory, String trigger, List<ActionSpec> actions,
                              Plan currentPlan) {
    public PlanningRequest {
        ActionSpec.text(taskId, "任务 ID");
        if (revision < 0) throw new IllegalArgumentException("快照版本不能为负数");
        ActionSpec.text(goal, "原始目标");
        ActionSpec.text(worldState, "世界状态");
        Objects.requireNonNull(executionHistory, "执行记录");
        ActionSpec.text(trigger, "规划触发原因");
        actions = List.copyOf(actions);
        Set<String> names = new HashSet<>();
        for (ActionSpec action : actions) {
            if (!names.add(action.name())) throw new IllegalArgumentException("重复动作名：" + action.name());
        }
        if (currentPlan != null && !currentPlan.goal().equals(goal)) {
            throw new IllegalArgumentException("当前计划与原始目标不一致");
        }
    }
}
