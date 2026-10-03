package com.mozhi.fleet.planning;

import com.mozhi.fleet.model.FleetPlanStep;
import dev.langchain4j.model.output.structured.Description;

/** 模型只定义行动参数，进度、实际目标 ID 与交易结果由执行器独占。 */
public final class FleetPlanStepDraft {
    @Description("首次规划为空字符串；重规划必须引用 mission.objectives 中对应的 id，补充交易前的 MOVE_TO 可引用该交易目标的 id")
    public String objectiveId;
    @Description("FOLLOW_PLAYER 跟随；RETURN 返回合并；MOVE_TO 前往目的地；BUY 购买；SELL 出售")
    public FleetPlanStep.Action action;
    @Description("非负的游戏日数；跟随为 0 表示持续跟随；其他行动必须为 0")
    public Double durationDays;
    @Description("此步骤的简短说明，必填且最长 600 字")
    public String description;
    @Description("MOVE_TO 的星球/星系/市场名称或 ID；BUY/SELL 必须指定具体市场或所属星球；其他行动为空字符串")
    public String destination;
    @Description("交易区名称或 ID（如 open_market、black_market）；未指定时为空字符串，按公开市场、军用市场、黑市顺序查找真实库存")
    public String submarket;
    @Description("交易物品或舰船的名称/ID；特殊物品可用 ID:实例数据，舰船可用实例 ID；非交易行动为空字符串")
    public String item;
    @Description("交易的正整数数量，必须来自玩家指令；非交易行动为 0")
    public Integer quantity;

    FleetPlanStep toStep() {
        if (durationDays == null) throw new IllegalArgumentException("计划步骤缺少 durationDays");
        FleetPlanStep step = new FleetPlanStep(action, durationDays, description);
        step.objectiveId = objectiveId == null ? "" : objectiveId;
        step.destination = destination == null ? "" : destination;
        step.submarket = submarket == null ? "" : submarket;
        step.item = item == null ? "" : item;
        step.quantity = quantity == null ? 0 : quantity;
        return step;
    }
}
