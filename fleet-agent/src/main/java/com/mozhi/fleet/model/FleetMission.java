package com.mozhi.fleet.model;

import java.util.*;

/** 聊天委派的原始任务与执行账本。重新规划只能替换剩余步骤，不能改写目标或已发生的效果。 */
public final class FleetMission {
    public enum Status { PLANNING, REVIEWING, EXECUTING, REPLANNING, COMPLETED, BLOCKED, CANCELLED }
    public String id=UUID.randomUUID().toString();
    public String parentAgent="chat-agent";
    public String originalGoal="";
    public Status status=Status.PLANNING;
    public int replanCount;
    public String reviewReason="";
    public double lastReviewDay;
    public List<Objective> objectives=new ArrayList<>();
    public List<Receipt> receipts=new ArrayList<>();

    public static final class Objective {
        public String id;
        public FleetPlanStep.Action action;
        public String destination,submarket,item;
        public int quantity;
        public double durationDays;
    }
    public static final class Receipt {
        public String stepId,objectiveId,result;
        public FleetPlanStep.Action action;
        public FleetPlanStep.Status status;
        public int quantity;
        public double progressDays;
    }
    public static FleetMission start(String goal) {
        FleetMission mission=new FleetMission(); mission.originalGoal=goal; return mission;
    }
    /** 首份计划通过目标检查后，固定行动目标和数量授权。 */
    public void approve(FleetPlan plan) {
        if(!objectives.isEmpty()) return;
        for(int i=0;i<plan.steps.size();i++) {
            FleetPlanStep step=plan.steps.get(i);
            Objective objective=new Objective(); objective.id=id+"/"+(i+1);
            objective.action=step.action; objective.destination=step.destination;
            objective.submarket=step.submarket; objective.item=step.item;
            objective.quantity=step.quantity; objective.durationDays=step.durationDays;
            objectives.add(objective); step.objectiveId=objective.id;
        }
    }
    public void capture(FleetPlan plan) {
        if(plan==null) return;
        for(FleetPlanStep step:plan.steps) {
            if(step.status!=FleetPlanStep.Status.COMPLETED && !(step.action==FleetPlanStep.Action.FOLLOW_PLAYER && step.progressDays>0)) continue;
            Receipt receipt=receipts.stream().filter(r->Objects.equals(r.stepId,step.id)).findFirst().orElse(null);
            if(receipt==null) {receipt=new Receipt(); receipts.add(receipt); receipt.stepId=step.id;}
            receipt.objectiveId=step.objectiveId; receipt.action=step.action; receipt.status=step.status;
            receipt.quantity=step.status==FleetPlanStep.Status.COMPLETED?step.quantity:0;
            receipt.progressDays=step.progressDays; receipt.result=step.result;
        }
    }
    public void validateRemaining(FleetPlan plan) {
        if(objectives.isEmpty()) return; // 首份计划尚未通过语义检查，且没有执行任何效果。
        Map<String,Integer> quantities=new HashMap<>(); Map<String,Double> durations=new HashMap<>();
        Set<String> once=new HashSet<>();
        for(FleetPlanStep step:plan.steps) {
            Objective original=objectives.stream().filter(o->Objects.equals(o.id,step.objectiveId)).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("重规划步骤必须引用原任务 objectiveId"));
            if(step.action==FleetPlanStep.Action.MOVE_TO && (original.action==FleetPlanStep.Action.BUY || original.action==FleetPlanStep.Action.SELL)) {
                if(!same(step.destination,original.destination)) throw new IllegalArgumentException("重规划不能更换交易目的地");
                continue; // 可补回同一市场的移动前置条件，不算重复交易。
            }
            if(step.action!=original.action || !same(step.destination,original.destination)
                    || !same(step.item,original.item) || !same(step.submarket,original.submarket))
                throw new IllegalArgumentException("重规划不能改写原任务行动、目的地或交易品种");
            var executed=receipts.stream().filter(r->Objects.equals(r.objectiveId,original.id) && r.action==original.action).toList();
            if(step.action==FleetPlanStep.Action.BUY || step.action==FleetPlanStep.Action.SELL) {
                int done=executed.stream().mapToInt(r->r.quantity).sum();
                int proposed=quantities.merge(original.id,step.quantity,Integer::sum);
                if(proposed>original.quantity-done) throw new IllegalArgumentException("重规划重复或超量交易，原目标剩余数量："+(original.quantity-done));
            } else if(step.action==FleetPlanStep.Action.FOLLOW_PLAYER && original.durationDays>0) {
                double done=executed.stream().mapToDouble(r->r.progressDays).sum();
                double proposed=durations.merge(original.id,step.durationDays,Double::sum);
                if(step.durationDays<=0 || proposed>Math.max(0,original.durationDays-done)+0.00001)
                    throw new IllegalArgumentException("重规划必须扣除已完成的跟随时长");
            } else {
                if(!once.add(original.id) || executed.stream().anyMatch(r->r.status==FleetPlanStep.Status.COMPLETED))
                    throw new IllegalArgumentException("重规划不能重复已完成的行动");
                if(step.action==FleetPlanStep.Action.FOLLOW_PLAYER && step.durationDays!=0)
                    throw new IllegalArgumentException("持续跟随不能改为定时跟随");
            }
        }
    }
    private static boolean same(String a,String b) {return Objects.toString(a,"").strip().equalsIgnoreCase(Objects.toString(b,"").strip());}
}
