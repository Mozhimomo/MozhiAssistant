package com.mozhi.fleet.model;

/** 一个可持久化的执行步骤；行动参数与执行进度分别保存。 */
public final class FleetPlanStep {
    public enum Action { FOLLOW_PLAYER, RETURN, MOVE_TO, BUY, SELL }
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
    public String id = "";
    public String objectiveId = "";
    public Action action;
    public String description = "";
    /** 跟随的游戏日数，0 表示持续跟随；召回必须为 0。 */
    public double durationDays;
    public String destination = "";
    public String submarket = "";
    public String item = "";
    public int quantity;
    /** 执行时解析并固定目标 ID，不保存游戏对象或库存快照。 */
    public String targetId = "";
    public String marketId = "";
    public boolean systemDestination;
    public boolean orbitStarted;
    public double progressDays;
    public Status status = Status.PENDING;
    public String result = "";
    public double startedDay = -1;
    public double finishedDay = -1;

    public FleetPlanStep() {}
    public FleetPlanStep(Action action, double durationDays, String description) {
        this.action = action; this.durationDays = durationDays; this.description = description;
    }

    public static FleetPlanStep visit(Action action, String destination, String submarket, String item, int quantity) {
        FleetPlanStep step = new FleetPlanStep(action, 0, switch (action) {
            case MOVE_TO -> "前往 " + destination;
            case BUY -> "在 " + destination + " 购买 " + quantity + " × " + item;
            case SELL -> "在 " + destination + " 出售 " + quantity + " × " + item;
            default -> throw new IllegalArgumentException("不是导航或交易行动");
        });
        step.destination = destination; step.submarket = submarket; step.item = item; step.quantity = quantity;
        return step;
    }
}
