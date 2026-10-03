package com.mozhi.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.Global;
import com.mozhi.assistant.bridge.FleetAgentBridge;
import com.mozhi.fleet.config.FleetSupervisionConfig;
import com.mozhi.fleet.execution.FleetExecutionMonitor;
import com.mozhi.fleet.execution.FleetPlanExecutor;
import com.mozhi.fleet.game.FleetDestinations;
import com.mozhi.fleet.game.FleetTransfer;
import com.mozhi.fleet.game.FleetWorld;
import com.mozhi.fleet.model.FleetMission;
import com.mozhi.fleet.model.FleetPlan;
import com.mozhi.fleet.model.FleetPlanStep;
import com.mozhi.fleet.model.FleetState;
import com.mozhi.fleet.planning.FleetGoalReview;
import com.mozhi.fleet.planning.FleetPlanner;
import com.mozhi.fleet.trade.FleetTrading;
import java.util.*;
import java.util.concurrent.*;

/** 聊天智能体的舰队子智能体：Plan → Execute → Review，偏离原始目标时 Replan。 */
public final class FleetDirector implements FleetAgentBridge {
    private static final String SAVE_KEY = "mozhi_assistant_fleet_agent_v1";
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService plannerThread = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task,"Mozhi-Fleet-Planner"); thread.setDaemon(true); return thread;
    });
    private final FleetPlanExecutor executor = new FleetPlanExecutor();
    private final FleetExecutionMonitor monitor = new FleetExecutionMonitor();
    private enum Work { PLAN, REVIEW, REPLAN }
    private record Result(FleetPlan plan,FleetGoalReview review) {}
    private FleetSupervisionConfig supervision;
    private FleetState state = new FleetState();
    private FleetPlanner planner;
    private Future<Result> pending;
    private Work pendingWork;
    private String pendingMissionId;
    private long pendingRevision;
    private long lastPeriodicReviewNanos;
    private float tickDays, saveDays;
    private boolean closed;
    private Thread owner;

    public FleetDirector() {}
    FleetDirector(FleetPlanner planner,FleetSupervisionConfig supervision,FleetState initialState) {
        this.planner=planner; this.supervision=supervision; this.state=initialState; this.owner=Thread.currentThread();
    }

    @Override public void initialize(String configUrl) throws Exception {
        owner = Thread.currentThread(); planner = new FleetPlanner(configUrl);
        supervision = FleetSupervisionConfig.load(configUrl);
        Object saved = Global.getSector().getPersistentData().get(SAVE_KEY);
        if (saved != null) {
            if (!(saved instanceof String text)) throw new IllegalStateException("舰队存档格式无效，未覆盖");
            state = FleetState.restore(json, text);
        }
        // 清除旧版存档中的原生交易/攻击任务，保留实际舰队资产。
        CampaignFleetAPI fleet = FleetWorld.findFleet(state.fleetId);
        if (fleet != null && fleet.getBattle() == null) { FleetWorld.passive(fleet); FleetWorld.holdPosition(fleet); }
        if(state.mission!=null) switch(state.mission.status) {
            case PLANNING -> submit(Work.PLAN,"读档后继续规划");
            case REVIEWING -> submit(Work.REVIEW,"读档后重新检查目标");
            case REPLANNING -> submit(Work.REPLAN,state.mission.reviewReason);
            default -> {}
        }
    }
    /** 命令入口：直接命令建立计划，order 将完整目的交给后台规划。 */
    @Override public String command(String requestJson) {
        requireOwner();
        try {
            JsonNode request = json.readTree(requestJson);
            String operation = request.path("operation").asText();
            if (operation.equals("status")) return encode(status());
            if (operation.equals("preview")) return encode(FleetTransfer.preview(ships(request.path("ships"))));
            if (operation.equals("dispatch")) {
                if (!state.fleetId.isEmpty()) throw new IllegalStateException("已有墨汁舰队，请先查看状态或召回");
                CampaignFleetAPI fleet = FleetTransfer.depart(ships(request.path("ships")),
                        amount(request,"credits"),amount(request,"supplies"),amount(request,"fuel"),integer(request,"crew"));
                discardPending();
                state = new FleetState();
                state.fleetId = fleet.getId();
                state.order = "放出舰队并跟随玩家";
                state.mission=FleetMission.start(state.order);
                FleetPlan initial=FleetPlan.follow(0,false);
                initial.prepare(state.revision,state.elapsedDays); state.mission.approve(initial);
                adopt(initial); save();
                return encode(status());
            }
            CampaignFleetAPI fleet = controlled();
            switch (operation) {
                case "recall" -> replacePlan(fleet,FleetPlan.recall());
                case "follow" -> {
                    if (!request.path("durationDays").isNumber() || !request.path("returnAfter").isBoolean())
                        throw new IllegalArgumentException("必须提供跟随游戏日数和是否随后召回");
                    FleetPlan plan = FleetPlan.follow(request.path("durationDays").asDouble(),request.path("returnAfter").asBoolean());
                    plan.validate();
                    replacePlan(fleet,plan);
                }
                case "move", "buy", "sell" -> {
                    String destination=text(request,"destination",false);
                    boolean trade=!operation.equals("move");
                    FleetPlanStep.Action action=operation.equals("move")?FleetPlanStep.Action.MOVE_TO:
                            operation.equals("buy")?FleetPlanStep.Action.BUY:FleetPlanStep.Action.SELL;
                    FleetPlan plan=FleetPlan.visit(action,destination,trade?text(request,"submarket",true):"",
                            trade?text(request,"item",false):"",trade?integer(request,"quantity"):0);
                    plan.validate();
                    // 当场确认目的地唯一；执行时再解析并固定实体 ID，不缓存库存。
                    var target=FleetDestinations.resolve(destination,trade);
                    if (trade) FleetWorld.requireOrbit(fleet,target.entity());
                    replacePlan(fleet,plan);
                }
                case "order" -> {
                    String instruction = request.path("instruction").asText();
                    if (instruction.isBlank() || instruction.length()>4000) throw new IllegalArgumentException("指令为空或过长");
                    invalidate("收到新指令"); state.order = instruction;
                    state.mission=FleetMission.start(instruction);
                    state.plan=null;
                    FleetWorld.passive(fleet); FleetWorld.holdPosition(fleet);
                    state.mode = "PLANNING"; state.reason = "正在制定行动计划";
                    submit(Work.PLAN,"聊天智能体委派新任务");
                }
                default -> throw new IllegalArgumentException("未知舰队命令："+operation);
            }
            save();
            return encode(status());
        } catch (Exception error) {
            return encode(Map.of("error",Objects.toString(error.getMessage(),error.getClass().getSimpleName()),
                    "note","请查询实际舰队状态；命令已收到不代表已完成航行、交易或合并"));
        }
    }
    private void replacePlan(CampaignFleetAPI fleet,FleetPlan plan) {
        plan.validate();
        invalidate("新命令替换原计划");
        state.order = plan.goal;
        state.mission=FleetMission.start(state.order);
        state.mission.approve(plan); // 参数化命令就是玩家直接指定的行动约束。
        FleetWorld.passive(fleet);
        if (plan.current().action==FleetPlanStep.Action.BUY || plan.current().action==FleetPlanStep.Action.SELL)
            FleetWorld.holdPosition(fleet);
        else FleetWorld.idle(fleet);
        adopt(plan);
    }
    /** 接纳候选计划后先检查目标；这里不执行任何步骤。 */
    private void adopt(FleetPlan plan) {
        state.mission.validateRemaining(plan);
        plan.prepare(state.revision,state.elapsedDays); state.plan = plan;
        state.mode = plan.current().action.name(); state.reason = plan.goal;
        state.plannerStatus = "计划已就绪，共 " + plan.steps.size() + " 步";
        tickDays = 0; monitor.reset(); state.note("新计划：" + plan.goal);
        submit(Work.REVIEW,"执行新计划前核对原始目标");
    }
    /** 游戏更新入口：收取模型结果，再监控并推进当前步骤。 */
    @Override public void advance(float amount) {
        requireOwner();
        if (closed) return;
        try {
            consumePlan();
            if(state.fleetId.isEmpty()) return;
            if (Global.getSector().isPaused()) return;
            float days = Global.getSector().getClock().convertToDays(amount);
            state.elapsedDays += days;
            tickDays += days;
            saveDays += days;
            if (tickDays < 0.05f) return;
            double elapsed = tickDays; tickDays = 0;
            CampaignFleetAPI fleet = FleetWorld.findFleet(state.fleetId);
            if (fleet == null || fleet.isEmpty() || fleet.isExpired()) {
                invalidate("舰队已覆灭或移除"); state.fleetId = ""; state.mode = "LOST";
                state.note("舰队已覆灭或移除，无法召回"); save(); return;
            }
            if (fleet.isAIMode()) fleet.setAIMode(false);
            if(pending!=null) return; // 检查期间不推进步骤或执行交易；原生航行可继续。
            if(state.mission!=null && state.mission.status==FleetMission.Status.BLOCKED) {
                FleetWorld.holdPosition(fleet); return;
            }
            if(state.plan!=null && state.plan.active()) {
                String deviation=monitor.deviation(fleet,state,supervision.stallDays());
                if(deviation!=null) {replan(deviation); return;}
                if(state.mission!=null && state.elapsedDays-state.mission.lastReviewDay>=supervision.reviewIntervalDays()
                        && (System.nanoTime()-lastPeriodicReviewNanos)/1_000_000_000d>=supervision.minReviewSeconds()) {
                    submit(Work.REVIEW,"执行中的定期目标检查"); return;
                }
            }
            int previousStep=state.plan==null?-1:state.plan.currentStep;
            executor.advance(fleet,state,elapsed);
            if(state.mission!=null && state.plan!=null && state.plan.currentStep!=previousStep) {
                state.mission.capture(state.plan);
                submit(Work.REVIEW,"步骤已完成，核对实际结果和剩余目标");
            }
            if (saveDays >= 0.5f || state.fleetId.isEmpty()
                    || (state.plan!=null && state.plan.currentStep!=previousStep)) { saveDays = 0; save(); }
        } catch (RuntimeException error) {
            state.reason = Objects.toString(error.getMessage(),"执行异常");
            if (state.plan != null && state.plan.active()) state.plan.fail(state.reason,state.elapsedDays);
            state.note(state.reason);
            if(error instanceof FleetTrading.UncertainTransaction) block("交易回滚未能完整确认，需先核实实际资产；不自动重试："+state.reason);
            else replan("执行失败："+state.reason);
        }
    }
    /** 在主线程应用后台结果；版本和任务 ID 不匹配的旧回复直接丢弃。 */
    private void consumePlan() {
        if (pending == null || !pending.isDone()) return;
        Future<Result> finished = pending; pending = null;
        if (pendingRevision != state.revision || state.mission==null || !Objects.equals(pendingMissionId,state.mission.id)) return;
        Work work=pendingWork;
        try {
            Result result=finished.get();
            if(work!=Work.REVIEW) adopt(result.plan());
            else {
                FleetGoalReview review=result.review(); review.validate();
                state.mission.reviewReason=review.reason; state.mission.lastReviewDay=state.elapsedDays;
                switch(review.verdict) {
                    case REPLAN -> replan(review.reason);
                    case BLOCKED -> block(review.reason);
                    case ON_TRACK -> {
                        state.mission.approve(state.plan);
                        boolean completed=state.plan.status==FleetPlan.Status.COMPLETED;
                        state.mission.status=completed?FleetMission.Status.COMPLETED:FleetMission.Status.EXECUTING;
                        state.plannerStatus=completed?"子任务已完成":"目标检查通过，继续执行";
                        var last=state.plan.steps.get(state.plan.steps.size()-1).action;
                        boolean orbit=last==FleetPlanStep.Action.MOVE_TO || last==FleetPlanStep.Action.BUY || last==FleetPlanStep.Action.SELL;
                        state.mode=state.fleetId.isEmpty()?"MERGED":completed?(orbit?"ORBIT":"IDLE"):state.plan.current().action.name();
                        state.reason=review.reason;
                    }
                }
            }
        }
        catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            block(Objects.toString(cause.getMessage(),"规划或目标检查失败"));
        }
        save();
    }
    /** 主线程采集状态文本，后台只调用模型，不访问游戏对象。 */
    private void submit(Work work,String reason) {
        if(pending!=null) throw new IllegalStateException("子智能体已有后台任务");
        state.mission.capture(state.plan);
        state.mission.reviewReason=reason;
        state.mission.status=switch(work) {
            case PLAN -> FleetMission.Status.PLANNING;
            case REVIEW -> FleetMission.Status.REVIEWING;
            case REPLAN -> FleetMission.Status.REPLANNING;
        };
        state.mode=work==Work.REVIEW?"REVIEWING":work==Work.REPLAN?"REPLANNING":"PLANNING";
        state.plannerStatus=work==Work.REVIEW?"正在检查原始目标":work==Work.REPLAN?"偏离目标，正在重新规划":"正在制定子任务计划";
        state.reason=reason;
        String observations=encode(Map.of("instruction",state.mission.originalGoal,"mission",state.mission,
                "trigger",reason,"currentState",status()));
        pendingRevision=state.revision; pendingMissionId=state.mission.id; pendingWork=work;
        lastPeriodicReviewNanos=System.nanoTime();
        pending=plannerThread.submit(()->work==Work.REVIEW?new Result(null,planner.review(observations))
                :new Result(planner.plan(observations),null));
        save();
    }
    /** 保留原任务与执行账本，取消旧计划后重新规划剩余行动。 */
    private void replan(String reason) {
        if(state.mission==null || state.fleetId.isEmpty()) {block(reason); return;}
        state.mission.capture(state.plan);
        if(state.mission.replanCount>=supervision.maxReplans()) {block("已达到自动重规划次数上限："+reason); return;}
        discardPending(); state.revision++;
        if(state.plan!=null) state.plan.cancel("偏离目标："+reason);
        state.mission.replanCount++;
        CampaignFleetAPI fleet=FleetWorld.findFleet(state.fleetId);
        if(fleet!=null && fleet.getBattle()==null && !fleet.isInHyperspaceTransition()) FleetWorld.holdPosition(fleet);
        monitor.reset(); state.note("重规划 #"+state.mission.replanCount+"："+reason);
        submit(Work.REPLAN,reason);
    }
    private void block(String reason) {
        discardPending();
        if(state.mission!=null) {state.mission.capture(state.plan); state.mission.status=FleetMission.Status.BLOCKED; state.mission.reviewReason=reason;}
        state.mode="BLOCKED"; state.reason=reason; state.plannerStatus="子任务需要处理";
        if(state.plan!=null && state.plan.active()) {state.plan.status=FleetPlan.Status.PAUSED; state.plan.feedback=reason;}
        CampaignFleetAPI fleet=FleetWorld.findFleet(state.fleetId);
        if(fleet!=null && fleet.getBattle()==null && !fleet.isInHyperspaceTransition()) FleetWorld.holdPosition(fleet);
        state.note(reason); save();
    }
    private void invalidate(String reason) {
        state.revision++; tickDays = 0; discardPending();
        if (state.plan != null) state.plan.cancel(reason);
        if(state.mission!=null) {state.mission.capture(state.plan); state.mission.status=FleetMission.Status.CANCELLED;}
        monitor.reset();
    }
    private void discardPending() { if (pending != null) pending.cancel(true); pending = null; }
    private CampaignFleetAPI controlled() {
        CampaignFleetAPI fleet = FleetWorld.findFleet(state.fleetId);
        if (fleet == null || fleet.isEmpty() || fleet.isExpired()) throw new IllegalStateException("没有可指挥的墨汁舰队");
        return fleet;
    }
    private Map<String,Object> status() {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("state",state);
        CampaignFleetAPI fleet = FleetWorld.findFleet(state.fleetId);
        if (fleet != null) {
            result.put("location",fleet.getContainingLocation().getName());
            result.put("logistics",FleetWorld.describe(fleet));
            result.put("ships",fleet.getFleetData().getMembersListCopy().stream()
                    .map(m -> Map.of("id",m.getId(),"name",m.getShipName(),"hull",m.getHullId())).toList());
            result.put("cargo",fleet.getCargo().getStacksCopy().stream().filter(s->s.getSize()>0).map(s->Map.of(
                    "name",s.getDisplayName(),"type",s.getType().name(),"quantity",s.getSize(),
                    "item",s.isSpecialStack()?s.getSpecialDataIfSpecial().getId()+":"+s.getSpecialDataIfSpecial().getData():String.valueOf(s.getData()))).toList());
            var assignment=fleet.getAI()==null?null:fleet.getAI().getCurrentAssignment();
            result.put("assignment",assignment==null?"NONE":assignment.getAssignment().name());
            result.put("assignmentTarget",assignment==null || assignment.getTarget()==null?"":assignment.getTarget().getId());
            result.put("orbitTarget",fleet.getOrbit()==null || fleet.getOrbit().getFocus()==null?"":fleet.getOrbit().getFocus().getId());
        }
        return result;
    }
    @Override public Map<String,Object> view() {
        requireOwner();
        return json.convertValue(status(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    @Override public void save() { requireOwner(); Global.getSector().getPersistentData().put(SAVE_KEY,encode(state)); }
    @Override public void close() { closed = true; discardPending(); plannerThread.shutdownNow(); }
    @Override public boolean isStopped() { return plannerThread.isTerminated(); }
    private void requireOwner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("舰队操作必须在主线程执行");
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("舰队数据序列化失败",error); }
    }
    private static List<String> ships(JsonNode values) {
        if (!values.isArray() || values.isEmpty() || values.size()>30) throw new IllegalArgumentException("需提供 1 至 30 个舰船名称/ID");
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>200)
                throw new IllegalArgumentException("舰船名称/ID 无效");
            result.add(value.asText());
        }
        return result;
    }
    private static float amount(JsonNode data,String key) {
        if (!data.path(key).isNumber()) throw new IllegalArgumentException("必须明确提供数值：" + key);
        float value = data.path(key).floatValue(); FleetTransfer.checkAmount(value); return value;
    }
    private static int integer(JsonNode data,String key) {
        if (!data.path(key).isIntegralNumber() || !data.path(key).canConvertToInt()) throw new IllegalArgumentException("必须提供整数：" + key);
        return data.path(key).intValue();
    }
    private static String text(JsonNode data,String key,boolean allowEmpty) {
        if (!data.path(key).isTextual()) throw new IllegalArgumentException("必须提供文本："+key);
        String value=data.path(key).asText().strip();
        if ((!allowEmpty && value.isEmpty()) || value.length()>300) throw new IllegalArgumentException("参数无效："+key);
        return value;
    }
}
