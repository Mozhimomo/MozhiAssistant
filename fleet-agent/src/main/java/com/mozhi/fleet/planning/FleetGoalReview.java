package com.mozhi.fleet.planning;

import dev.langchain4j.model.output.structured.Description;

/** 子智能体对原始目标的执行检查，不包含直接游戏操作。 */
public final class FleetGoalReview {
    public enum Verdict { ON_TRACK, REPLAN, BLOCKED }
    @Description("ON_TRACK 仍符合原始目标；REPLAN 已偏离且可重新规划；BLOCKED 缺少指令或当前能力无法完成")
    public Verdict verdict;
    @Description("基于原始目标、实时观测和已完成记录的具体判断依据")
    public String reason;
    public void validate() {
        if(verdict==null || reason==null || reason.isBlank() || reason.length()>2000)
            throw new IllegalArgumentException("目标检查返回内容无效");
    }
}
