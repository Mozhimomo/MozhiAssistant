package com.mozhi.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.mozhi.fleet.game.FleetWorld;
import com.mozhi.fleet.model.FleetPlan;
import com.mozhi.fleet.model.FleetPlanStep;
import com.mozhi.fleet.model.FleetState;
import com.mozhi.fleet.planning.FleetPlanner;
import com.mozhi.llm.LlmClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 计划与存档回归，不启动游戏或调用模型。 */
public final class FleetRegressionChecks {
    public static void main(String[] args) throws Exception {
        passivePreservesMovement();
        structuredPlanning();
        ObjectMapper json = new ObjectMapper();
        FleetPlan plan = FleetPlan.follow(3,true);
        plan.prepare(4,10);
        check(plan.steps.size()==2 && plan.current().action==FleetPlanStep.Action.FOLLOW_PLAYER,"Follow then return plan");
        plan.current().progressDays=3;
        plan.completeStep("followed",13);
        check(plan.current().action==FleetPlanStep.Action.RETURN && plan.active(),"Return must be an executable plan step");
        FleetState state = new FleetState(); state.fleetId="fleet"; state.plan=plan;
        FleetState saved = FleetState.restore(json,json.writeValueAsString(state));
        check(saved.plan.currentStep==1 && saved.plan.steps.get(0).progressDays==3,"Preserve follow progress and return cursor");
        saved.plan.completeStep("merged",14);
        check(saved.plan.status==FleetPlan.Status.COMPLETED && saved.plan.current()==null,"Recall completes plan");

        invalid(FleetPlan.follow(0,true)); // An infinite follow must not hide an unreachable recall.
        invalid(FleetPlan.follow(-1,false));
        invalid(FleetPlan.follow(Double.NaN,false));
        FleetPlan afterReturn=FleetPlan.recall();
        afterReturn.steps.add(new FleetPlanStep(FleetPlanStep.Action.FOLLOW_PLAYER,1,"follow"));
        invalid(afterReturn);
        FleetPlan continuous=FleetPlan.follow(0,false);continuous.prepare(1,0);
        check(continuous.active(),"Continuous follow stays active");

        FleetPlan untrusted=FleetPlan.follow(2,true);
        untrusted.currentStep=1;untrusted.status=FleetPlan.Status.COMPLETED;
        untrusted.steps.get(0).progressDays=999;untrusted.steps.get(0).status=FleetPlanStep.Status.COMPLETED;
        untrusted.prepare(2,0);
        check(untrusted.currentStep==0 && untrusted.current().progressDays==0
                && untrusted.current().status==FleetPlanStep.Status.PENDING,"Model cannot provide execution state");
        untrusted.completeStep("followed",2);untrusted.cancel("new command");
        check(untrusted.steps.get(0).status==FleetPlanStep.Status.COMPLETED
                && untrusted.steps.get(1).status==FleetPlanStep.Status.CANCELLED,"Cancel preserves completed work");

        FleetState old=FleetState.restore(json,"""
                {"version":1,"fleetId":"keep-original-fleet","mode":"DEVELOP","elapsedDays":12,
                 "buyShips":true,"plan":{"steps":[{"action":"TRADE"}]} }
                """);
        check(old.version==2 && old.fleetId.equals("keep-original-fleet")
                && old.plan.current().action==FleetPlanStep.Action.FOLLOW_PLAYER,"Drop old development plan, retain real fleet id");
        FleetState oldReturn=FleetState.restore(json,"{\"version\":1,\"fleetId\":\"fleet\",\"mode\":\"RETURN\"}");
        check(oldReturn.plan.current().action==FleetPlanStep.Action.RETURN,"Preserve existing recall intent");
        try {
            json.readValue("{\"action\":\"TRADE\"}",FleetPlanStep.class);
            throw new AssertionError("Removed actions must not be accepted");
        } catch (com.fasterxml.jackson.databind.JsonMappingException expected) {}
        System.out.println("PASS: typed planning (prompt + JSON schema), malformed/refused plans, passive preserves movement, follow/return composition, timing validation, persistence, cancellation, proposal normalization, legacy migration, removed actions");
    }
    private static void structuredPlanning() throws Exception {
        String response = """
                {"goal":"跟随三天后召回","error":"","steps":[
                  {"action":"FOLLOW_PLAYER","durationDays":3,"description":"跟随三天"},
                  {"action":"RETURN","durationDays":0,"description":"召回"}]}
                """;
        for (boolean schema : new boolean[]{false, true}) {
            PlanningModel model = new PlanningModel(response, schema);
            FleetPlan plan = new FleetPlanner(LlmClient.of(model, null, 60)).plan("跟随三天后召回");
            check(plan.steps.size() == 2 && plan.steps.get(0).durationDays == 3
                    && plan.steps.get(1).action == FleetPlanStep.Action.RETURN, "Parse typed nested steps");
            check(plan.status == FleetPlan.Status.READY && plan.current().progressDays == 0
                    && plan.current().status == FleetPlanStep.Status.PENDING, "Executor owns execution state");
            if (schema) {
                JsonObjectSchema root = (JsonObjectSchema) model.request.responseFormat().jsonSchema().rootElement();
                check(root.properties().keySet().equals(Set.of("goal", "error", "steps")), "Only draft fields in schema");
                JsonObjectSchema step = (JsonObjectSchema) ((JsonArraySchema) root.properties().get("steps")).items();
                check(step.properties().keySet().equals(Set.of("objectiveId", "action", "durationDays", "description", "destination", "submarket", "item", "quantity")),
                        "Schema cannot solicit execution progress or status");
                JsonEnumSchema action = (JsonEnumSchema) step.properties().get("action");
                check(Set.copyOf(action.enumValues()).equals(Set.of("FOLLOW_PLAYER", "RETURN", "MOVE_TO", "BUY", "SELL")), "Restrict action enum");
            } else {
                check(model.request.responseFormat() == null || model.request.responseFormat().jsonSchema() == null,
                        "Do not require native schemas from unsupported providers");
            }
        }
        planningFails("{\"goal\":\"\",\"error\":\"不支持主动战斗\",\"steps\":[]}", "不支持主动战斗");
        planningFails("not a plan", null);
        planningFails(response.replace("FOLLOW_PLAYER", "TRADE"), null);
        planningFails(response.replace("\"durationDays\":3,", ""), "durationDays");
        planningFails(response.replace("\"durationDays\":3", "\"durationDays\":0"), "最后一步");
    }
    private static void planningFails(String response, String reason) throws Exception {
        try {
            new FleetPlanner(LlmClient.of(new PlanningModel(response, false), null, 60)).plan("指令");
            throw new AssertionError("Invalid model output accepted");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            if (reason != null) check(expected.getMessage().contains(reason), "Keep meaningful refusal/validation reason");
        }
    }
    private static final class PlanningModel implements ChatModel {
        private final String response;
        private final boolean schema;
        private ChatRequest request;
        PlanningModel(String response, boolean schema) { this.response = response; this.schema = schema; }
        @Override public Set<Capability> supportedCapabilities() {
            return schema ? Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA) : Set.of();
        }
        @Override public ChatResponse chat(ChatRequest request) {
            this.request = request;
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }
    private static void passivePreservesMovement() {
        // 模拟正在跟随的原生 AI：禁止防交战逻辑改写战术目标或任务队列。
        TacticalModulePlugin tactical = proxy(TacticalModulePlugin.class, (p, method, args) -> {
            throw new AssertionError("Passive control touched tactical movement: " + method.getName());
        });
        ModularFleetAIAPI ai = proxy(ModularFleetAIAPI.class, (p, method, args) -> {
            if (method.getName().equals("getTacticalModule")) return tactical;
            throw new AssertionError("Passive control touched assignments: " + method.getName());
        });
        Map<String,Object> flags = new HashMap<>();
        MemoryAPI memory = proxy(MemoryAPI.class, (p, method, args) -> {
            if (method.getName().equals("set")) { flags.put((String) args[0], args[1]); return null; }
            throw new AssertionError("Unexpected memory call: " + method.getName());
        });
        float[] noEngaging = {0};
        CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (p, method, args) -> {
            return switch (method.getName()) {
                case "getAI" -> ai;
                case "getMemoryWithoutUpdate" -> memory;
                case "setNoEngaging" -> { noEngaging[0] = (float) args[0]; yield null; }
                default -> throw new AssertionError("Passive control changed fleet movement: " + method.getName());
            };
        });
        for (int tick = 0; tick < 20; tick++) FleetWorld.passive(fleet);
        check(noEngaging[0] > 0 && Boolean.TRUE.equals(flags.get(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE)),
                "Keep passive behavior without resetting native follow or jump targets");
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static void invalid(FleetPlan plan) {
        try {plan.prepare(0,0);throw new AssertionError("Invalid plan accepted");}
        catch(IllegalArgumentException expected) {}
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
