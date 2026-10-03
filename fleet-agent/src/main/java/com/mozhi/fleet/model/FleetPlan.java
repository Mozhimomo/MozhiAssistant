package com.mozhi.fleet.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plan 阶段生成步骤，Execute 阶段独占进度；计划随战役存档保存。 */
public final class FleetPlan {
    public enum Status { READY, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
    public String id = UUID.randomUUID().toString();
    public String goal = "";
    public String source = "LLM";
    public long revision;
    public double createdDay;
    public Status status = Status.READY;
    public String feedback = "";
    public int currentStep;
    public List<FleetPlanStep> steps = new ArrayList<>();

    public FleetPlanStep current() { return currentStep >= 0 && currentStep < steps.size() ? steps.get(currentStep) : null; }
    public boolean active() { return status == Status.READY || status == Status.RUNNING || status == Status.PAUSED; }

    public void validate() {
        if (goal == null || goal.isBlank() || goal.length() > 600 || steps == null || steps.isEmpty() || steps.size() > 8)
            throw new IllegalArgumentException("计划必须包含目标及 1 至 8 个步骤");
        for (int i = 0; i < steps.size(); i++) {
            FleetPlanStep step = steps.get(i);
            if (step == null || step.action == null || step.description == null || step.description.isBlank()
                    || step.description.length() > 600 || !Double.isFinite(step.durationDays) || step.durationDays < 0)
                throw new IllegalArgumentException("计划步骤或跟随时长无效");
            if (step.action != FleetPlanStep.Action.FOLLOW_PLAYER && step.durationDays != 0)
                throw new IllegalArgumentException("只有跟随步骤接受跟随时长");
            if ((step.action == FleetPlanStep.Action.RETURN
                    || (step.action == FleetPlanStep.Action.FOLLOW_PLAYER && step.durationDays == 0)) && i != steps.size() - 1)
                throw new IllegalArgumentException("召回或无限期跟随必须是最后一步");
            if (step.action == FleetPlanStep.Action.MOVE_TO || step.action == FleetPlanStep.Action.BUY
                    || step.action == FleetPlanStep.Action.SELL) {
                if (step.destination == null || step.destination.isBlank() || step.destination.length() > 200)
                    throw new IllegalArgumentException("导航或交易必须指定星球、星系或市场名称/ID");
            }
            if (step.action == FleetPlanStep.Action.BUY || step.action == FleetPlanStep.Action.SELL) {
                if (step.item == null || step.item.isBlank() || step.item.length() > 300
                        || step.quantity <= 0 || step.quantity > 1_000_000)
                    throw new IllegalArgumentException("交易必须指定物品及 1 至 1000000 的整数数量");
                if (step.submarket == null || step.submarket.length() > 200)
                    throw new IllegalArgumentException("交易区必须是名称/ID 或空字符串");
            }
        }
    }
    public void prepare(long commandRevision, double day) {
        validate();
        id = UUID.randomUUID().toString(); revision = commandRevision; createdDay = day;
        currentStep = 0; status = Status.READY; feedback = "";
        for (int i = 0; i < steps.size(); i++) {
            FleetPlanStep step = steps.get(i);
            step.id = id + "-" + (i + 1); step.status = FleetPlanStep.Status.PENDING;
            step.result = ""; step.progressDays = 0; step.startedDay = -1; step.finishedDay = -1;
            step.targetId = ""; step.marketId = ""; step.systemDestination = false; step.orbitStarted = false;
        }
    }
    public void completeStep(String result, double day) {
        FleetPlanStep step = current();
        if (step == null) return;
        step.status = FleetPlanStep.Status.COMPLETED; step.result = result; step.finishedDay = day;
        feedback = result; currentStep++;
        if (currentStep == steps.size()) status = Status.COMPLETED;
    }
    public void fail(String reason, double day) {
        FleetPlanStep step = current();
        if (step != null) { step.status = FleetPlanStep.Status.FAILED; step.result = reason; step.finishedDay = day; }
        status = Status.FAILED; feedback = reason;
    }
    public void cancel(String reason) {
        if (!active()) return;
        status = Status.CANCELLED; feedback = reason;
        for (FleetPlanStep step : steps)
            if (step.status == FleetPlanStep.Status.PENDING || step.status == FleetPlanStep.Status.RUNNING)
                step.status = FleetPlanStep.Status.CANCELLED;
    }
    public static FleetPlan follow(double days, boolean returnAfter) {
        FleetPlan plan = new FleetPlan(); plan.source = "COMMAND";
        plan.goal = returnAfter ? "跟随玩家后召回合并" : "跟随玩家";
        plan.steps.add(new FleetPlanStep(FleetPlanStep.Action.FOLLOW_PLAYER, days,
                days == 0 ? "持续跟随玩家" : "跟随玩家 " + days + " 个游戏日"));
        if (returnAfter) plan.steps.add(new FleetPlanStep(FleetPlanStep.Action.RETURN, 0, "返回玩家并合并舰队"));
        return plan;
    }
    public static FleetPlan recall() {
        FleetPlan plan = new FleetPlan(); plan.source = "COMMAND"; plan.goal = "返回玩家并合并舰队";
        plan.steps.add(new FleetPlanStep(FleetPlanStep.Action.RETURN, 0, plan.goal));
        return plan;
    }
    public static FleetPlan visit(FleetPlanStep.Action action, String destination, String submarket, String item, int quantity) {
        FleetPlan plan = new FleetPlan(); plan.source = "COMMAND";
        FleetPlanStep step = FleetPlanStep.visit(action, destination, submarket, item, quantity);
        plan.goal = step.description; plan.steps.add(step);
        return plan;
    }
}
