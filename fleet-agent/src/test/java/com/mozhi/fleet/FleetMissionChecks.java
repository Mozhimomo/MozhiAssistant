package com.mozhi.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.Global;
import com.mozhi.fleet.config.FleetSupervisionConfig;
import com.mozhi.fleet.execution.FleetExecutionMonitor;
import com.mozhi.fleet.model.FleetMission;
import com.mozhi.fleet.model.FleetPlan;
import com.mozhi.fleet.model.FleetPlanStep;
import com.mozhi.fleet.model.FleetState;
import com.mozhi.fleet.planning.FleetPlanner;
import com.mozhi.llm.LlmClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class FleetMissionChecks {
    public static void main(String[] args) throws Exception {
        ledger(); asynchronousReplan(); staleTask(); monitor();
        System.out.println("PASS: immutable goal, no duplicate/oversized trades, partial follow accounting, mission persistence, review-triggered replan, retry limit, superseded async task, assignment drift and stalled movement");
    }
    private static void ledger() throws Exception {
        FleetMission mission=FleetMission.start("在 A 买十个补给，然后跟随三天再召回");
        FleetPlan plan=FleetPlan.visit(FleetPlanStep.Action.BUY,"A","","supplies",10);
        plan.steps.add(new FleetPlanStep(FleetPlanStep.Action.FOLLOW_PLAYER,3,"跟随三天"));
        plan.steps.add(new FleetPlanStep(FleetPlanStep.Action.RETURN,0,"召回"));
        plan.prepare(0,0); mission.approve(plan);
        String buyId=plan.steps.get(0).objectiveId, followId=plan.steps.get(1).objectiveId;
        plan.completeStep("购买十个补给",0); plan.current().progressDays=1.25;
        mission.capture(plan); mission.capture(plan);
        check(mission.receipts.size()==2,"Capture progress idempotently");
        FleetPlan duplicate=FleetPlan.visit(FleetPlanStep.Action.BUY,"A","","supplies",1);
        duplicate.current().objectiveId=buyId;
        fails(()->mission.validateRemaining(duplicate));
        FleetPlan remaining=FleetPlan.follow(1.75,false); remaining.current().objectiveId=followId;
        mission.validateRemaining(remaining);
        remaining.current().durationDays=3; fails(()->mission.validateRemaining(remaining));
        remaining.current().durationDays=1.75; remaining.prepare(1,5); mission.validateRemaining(remaining);
        check(mission.originalGoal.equals("在 A 买十个补给，然后跟随三天再召回"),"Replan must not change original goal");
        FleetState state=new FleetState();state.plan=plan;state.mission=mission;state.order=mission.originalGoal;
        ObjectMapper json=new ObjectMapper(); FleetState restored=FleetState.restore(json,json.writeValueAsString(state));
        fails(()->restored.mission.validateRemaining(duplicate));
        check(restored.mission.receipts.size()==2,"Save/load retains completed effect budget");

        FleetMission fresh=FleetMission.start("在 A 买十个补给");
        FleetPlan original=FleetPlan.visit(FleetPlanStep.Action.BUY,"A","","supplies",10);
        original.prepare(0,0); fresh.approve(original);
        FleetPlan repair=FleetPlan.visit(FleetPlanStep.Action.MOVE_TO,"A","","",0);
        repair.current().objectiveId=original.current().objectiveId;
        var buy=FleetPlanStep.visit(FleetPlanStep.Action.BUY,"A","","supplies",10);
        buy.objectiveId=original.current().objectiveId;repair.steps.add(buy);fresh.validateRemaining(repair);
        buy.quantity=11;fails(()->fresh.validateRemaining(repair));
        buy.quantity=10;buy.destination="B";fails(()->fresh.validateRemaining(repair));
    }
    private static void asynchronousReplan() throws Exception {
        var world=new FleetTradingChecks.World();var previous=Global.getSector();Global.setSector(world.sector);
        try {
            for(boolean alwaysDeviates:new boolean[]{false,true}) {
                AtomicInteger reviews=new AtomicInteger(), plans=new AtomicInteger();
                ChatModel model=new ChatModel() {
                    @Override public ChatResponse chat(ChatRequest request) {
                        boolean review=request.messages().get(0).toString().contains("目标检查器");
                        String content;
                        if(review) content="{\"verdict\":\""+((reviews.getAndIncrement()==0 || alwaysDeviates)?"REPLAN":"ON_TRACK")
                                +"\",\"reason\":\"核对原始目标\"}";
                        else {plans.incrementAndGet();content=planJson();}
                        return response(content);
                    }
                };
                FleetState state=new FleetState();state.fleetId="test-fleet";
                FleetDirector director=new FleetDirector(new FleetPlanner(LlmClient.of(model,null,30)),
                        new FleetSupervisionConfig(1,30,3,2),state);
                try {
                    String accepted=director.command("{\"operation\":\"order\",\"instruction\":\"跟随三天\"}");
                    check(!accepted.contains("\"error\""),"Parent delegation accepted");
                    await(()->{director.advance(0);return state.mission.status==FleetMission.Status.EXECUTING || state.mission.status==FleetMission.Status.BLOCKED;});
                    check(state.mission.originalGoal.equals("跟随三天") && state.order.equals("跟随三天"),"Original task survives automatic replan");
                    check(state.mission.replanCount==(alwaysDeviates?2:1),"Review triggers replan and obeys limit");
                    check(state.mission.status==(alwaysDeviates?FleetMission.Status.BLOCKED:FleetMission.Status.EXECUTING),"Review verdict controls execution");
                    check(plans.get()==(alwaysDeviates?3:2),"Only bounded planning attempts");
                } finally {director.close();}
            }
        } finally {Global.setSector(previous);}
    }
    private static void staleTask() throws Exception {
        var world=new FleetTradingChecks.World();var previous=Global.getSector();Global.setSector(world.sector);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        ChatModel model=new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                if(calls.getAndIncrement()==0) {
                    entered.countDown(); boolean released=false;
                    while(!released) try {released=release.await(3,TimeUnit.SECONDS);if(!released)throw new AssertionError("Timed out");}
                    catch(InterruptedException ignored) {} // Simulate a provider returning after cancellation.
                }
                return response(request.messages().get(0).toString().contains("目标检查器")
                        ?"{\"verdict\":\"ON_TRACK\",\"reason\":\"符合新任务\"}":planJson());
            }
        };
        FleetState state=new FleetState();state.fleetId="test-fleet";
        FleetDirector director=new FleetDirector(new FleetPlanner(LlmClient.of(model,null,30)),new FleetSupervisionConfig(1,30,3,2),state);
        try {
            director.command("{\"operation\":\"order\",\"instruction\":\"旧任务\"}");
            check(entered.await(3,TimeUnit.SECONDS),"First planning request entered");String oldId=state.mission.id;
            director.command("{\"operation\":\"order\",\"instruction\":\"新任务\"}");release.countDown();
            await(()->{director.advance(0);return state.mission.status==FleetMission.Status.EXECUTING;});
            check(!state.mission.id.equals(oldId) && state.mission.originalGoal.equals("新任务") && state.mission.receipts.isEmpty(),
                    "Late old result cannot overwrite new mission or leak old receipts");
        } finally {release.countDown();director.close();Global.setSector(previous);}
    }
    private static void monitor() {
        var world=new FleetTradingChecks.World();var previous=Global.getSector();Global.setSector(world.sector);
        try {
            FleetState state=new FleetState();state.plan=FleetPlan.visit(FleetPlanStep.Action.MOVE_TO,"jangala","","",0);
            state.plan.prepare(0,0);state.plan.current().status=FleetPlanStep.Status.RUNNING;
            world.fleet.position.set(5000,0);world.fleet.assignment=FleetAssignment.GO_TO_LOCATION;world.fleet.target=world.planet;
            FleetExecutionMonitor monitor=new FleetExecutionMonitor();
            check(monitor.deviation(world.fleet.api,state,3)==null,"Normal movement not treated as deviation");
            state.elapsedDays=4;check(monitor.deviation(world.fleet.api,state,3)!=null,"Stationary travel triggers replan after deadline");
            monitor.reset();state.elapsedDays=5;monitor.deviation(world.fleet.api,state,3);
            state.elapsedDays=7;world.fleet.position.set(4000,0);check(monitor.deviation(world.fleet.api,state,3)==null,"Approaching target counts as progress");
            world.fleet.target=world.remote;check(monitor.deviation(world.fleet.api,state,3)!=null,"Wrong native target triggers replan");
        } finally {Global.setSector(previous);}
    }
    private static String planJson() {return "{\"goal\":\"跟随\",\"error\":\"\",\"steps\":[{\"action\":\"FOLLOW_PLAYER\",\"durationDays\":3,\"description\":\"跟随三天\"}]}";}
    private static ChatResponse response(String text) {return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();}
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()) {if(System.nanoTime()>deadline)throw new AssertionError("Async mission timed out");Thread.sleep(5);}
    }
    private static void fails(Runnable action) {try {action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){}}
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
