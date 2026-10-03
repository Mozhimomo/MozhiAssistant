package com.mozhi.fleet.planning;

import com.mozhi.fleet.model.FleetPlan;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/** 模型输出的计划定义，不包含执行状态、存档标识或已完成进度。 */
public final class FleetPlanDraft {
    @Description("计划目标，成功时必填，最长 600 字；拒绝规划时为空字符串")
    public String goal;
    @Description("成功时为空字符串；无法完成指令时填写拒绝原因，并返回空步骤列表")
    public String error;
    @Description("按执行顺序排列的 1 至 8 个步骤；拒绝规划时为空列表")
    public List<FleetPlanStepDraft> steps;

    FleetPlan toPlan() {
        if (error != null && !error.isBlank()) throw new IllegalArgumentException(error);
        if (steps == null || steps.isEmpty() || steps.size() > 8) {
            throw new IllegalArgumentException("计划必须包含 1 至 8 个步骤");
        }
        FleetPlan plan = new FleetPlan();
        plan.goal = goal;
        for (FleetPlanStepDraft step : steps) {
            if (step == null) throw new IllegalArgumentException("计划步骤不可为空");
            plan.steps.add(step.toStep());
        }
        // 格式正确不代表业务规则正确；继续检查无限跟随、召回顺序及数值范围。
        plan.prepare(0, 0);
        plan.source = "LLM";
        return plan;
    }
}
