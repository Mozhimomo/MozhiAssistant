package com.mozhi.fleet.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.mozhi.assistant.bridge.FleetAgentBridge;
import com.mozhi.fleet.Agent;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.actions.UncertainActionException;
import com.mozhi.fleet.execution.Executor;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ExecutionHistory;
import com.mozhi.fleet.planning.Planner;
import java.util.*;
import java.util.function.Supplier;

/** 游戏桥接：派遣、主线程快照、UI 数据和 JSON 存档。调度由 Agent 完成。 */
public final class FleetRuntime implements FleetAgentBridge {
    public static final String SAVE_KEY = "mozhi_assistant_fleet_agent_v2";
    public record Saved(int version, String fleetId, String mode, String problem, Agent.State agent) {}
    private final ObjectMapper json = new ObjectMapper();
    private Thread owner;
    private SectorAPI sector;
    private String configUrl, fleetId = "", mode = "IDLE", problem = "";
    private Agent agent;
    private Planner planner;
    private Agent.State detachedState;
    private boolean closed;
    private long lastNanos;
    private final Supplier<Planner> plannerFactory;
    private final Supplier<CampaignFleetAPI> fleetFactory;

    public FleetRuntime() { this(null, null, null); }

    /** 注入入口供无网络的游戏桥接检查使用。 */
    public FleetRuntime(SectorAPI sector, Supplier<Planner> plannerFactory, Supplier<CampaignFleetAPI> fleetFactory) {
        this.sector = sector; this.plannerFactory = plannerFactory; this.fleetFactory = fleetFactory;
    }

    @Override public void initialize(String configUrl) throws Exception {
        if (owner != null) throw new IllegalStateException("运行时已初始化");
        owner = Thread.currentThread(); this.configUrl = configUrl;
        if (sector == null) sector = Objects.requireNonNull(Global.getSector());
        lastNanos = System.nanoTime();
        Object stored = sector.getPersistentData().get(SAVE_KEY);
        if (stored != null) {
            if (!(stored instanceof String text)) throw new IllegalStateException("舰队存档不是 JSON 文本");
            Saved saved = json.readValue(text, Saved.class);
            if (saved.version() != 2 || saved.fleetId() == null || saved.mode() == null || saved.problem() == null)
                throw new IllegalStateException("不支持的舰队存档");
            fleetId = saved.fleetId(); mode = saved.mode(); problem = saved.problem(); detachedState = saved.agent();
        } else {
            // 旧版只接管实际舰队，旧动作和任务不自动重放。
            Object old = sector.getPersistentData().get("mozhi_assistant_fleet_agent_v1");
            if (old instanceof String text) {
                fleetId = json.readTree(text).path("fleetId").asText("");
                if (!fleetId.isBlank()) problem = "已接管旧版舰队，请重新下达任务";
            }
        }
        CampaignFleetAPI fleet = GameWorld.find(sector, fleetId);
        if (fleet == null && !fleetId.isBlank()) { fleetId = ""; mode = "LOST"; return; }
        if (fleet != null) {
            GameWorld.passive(fleet);
            Agent.State restored = detachedState;
            attach(fleet);
            if (restored != null) agent.restore(restored);
            else GameWorld.hold(fleet);
        }
    }

    private void attach(CampaignFleetAPI fleet) {
        if (planner == null) planner = plannerFactory == null ? new Planner(configUrl) : plannerFactory.get();
        agent = new Agent(planner, new Executor(new ActionContext(sector, fleet, Global.getSettings(), Global.getFactory()),
                new ExecutionHistory()), () -> encode(GameWorld.observations(sector, controlled(), agent.view().goal(), agent.view().plan())));
        detachedState = null;
    }

    @Override public String command(String requestJson) {
        requireOwner();
        try {
            if (closed) throw new IllegalStateException("舰队运行时已关闭");
            JsonNode request = json.readTree(requestJson);
            String operation = text(request, "operation");
            if (operation.equals("status")) return encode(view());
            if (operation.equals("preview")) return encode(FleetDeployment.preview(sector.getPlayerFleet(), ships(request)));
            if (!problem.isBlank() && problem.startsWith("资产")) throw new IllegalStateException(problem);
            if (operation.equals("dispatch")) {
                if (GameWorld.find(sector, fleetId) != null) throw new IllegalStateException("已有分舰队，请先召回");
                List<String> ships = ships(request);
                float credits = amount(request, "credits"), supplies = amount(request, "supplies"), fuel = amount(request, "fuel");
                int crew = integer(request, "crew");
                FleetDeployment.preview(sector.getPlayerFleet(), ships);
                CampaignFleetAPI fleet = fleetFactory == null ? newFleet() : fleetFactory.get();
                FleetDeployment.depart(sector.getPlayerFleet(), fleet, ships, credits, supplies, fuel, crew);
                fleetId = fleet.getId(); mode = "IDLE"; problem = "";
                attach(fleet);
            } else {
                controlled();
                switch (operation) {
                    case "recall" -> agent.start(Plan.create("回归玩家舰队", List.of(Step.create("RETURN", Map.of(), "返回玩家并合并", "分舰队资产并入玩家"))));
                    case "move" -> {
                        String destination = text(request, "destination");
                        var target = GameWorld.destination(sector, destination);
                        agent.start(Plan.create("移动至 " + destination, List.of(Step.create("MOVE_TO", Map.of("destinationId", target.getId()),
                                "前往 " + target.getName(), "实际环绕目标"))));
                    }
                    case "order" -> agent.start(text(request, "instruction"));
                    case "buy", "sell" -> {
                        String destination = text(request, "destination"), item = text(request, "item");
                        int quantity = integer(request, "quantity");
                        if (quantity <= 0) throw new IllegalArgumentException("交易数量必须为正整数");
                        String submarket = request.path("submarket").asText("");
                        agent.start("仅在已经环绕的市场 " + destination + " " + (operation.equals("buy") ? "购买" : "出售")
                                + quantity + " 个 " + item + "。交易区：" + (submarket.isBlank() ? "选择可交易区" : submarket)
                                + "。不自动航行，未入轨则返回 BLOCKED。");
                    }
                    case "cancel" -> agent.cancel();
                    default -> throw new IllegalArgumentException("不支持的命令：" + operation + "；当前动作仅购买、出售、移动、回归");
                }
                problem = "";
            }
            save();
            return encode(view());
        } catch (UncertainActionException error) {
            problem = "资产划拨恢复无法确认，已阻止新命令：" + error.getMessage();
            save();
            return encode(Map.of("error", problem));
        } catch (Exception error) {
            return encode(Map.of("error", Objects.toString(error.getMessage(), error.getClass().getSimpleName()),
                    "note", "接受任务不代表完成；查询舰队状态确认实际结果"));
        }
    }

    private CampaignFleetAPI newFleet() {
        CampaignFleetAPI fleet = FleetFactoryV3.createEmptyFleet(Factions.PLAYER, FleetTypes.MERC_SCOUT, null);
        fleet.setName("墨汁远征舰队"); fleet.setNoFactionInName(true); fleet.setNoAutoDespawn(true);
        fleet.setCommander(Global.getFactory().createPerson()); fleet.getCommander().setFaction(Factions.PLAYER);
        fleet.getCommander().getName().setFirst("墨汁"); fleet.getCommander().setPortraitSprite("graphics/portraits/SOD_portrait_mozhi.png");
        fleet.getCargo().getCredits().set(0);
        fleet.getMemoryWithoutUpdate().set("$mozhiExpedition", true);
        GameWorld.passive(fleet);
        return fleet;
    }

    @Override public void advance(float amount) {
        requireOwner();
        long now = System.nanoTime();
        double seconds = Math.max(0, (now - lastNanos) / 1_000_000_000d); lastNanos = now;
        if (closed || agent == null || fleetId.isBlank()) return;
        try {
            var fleet = GameWorld.find(sector, fleetId);
            if (fleet == null || fleet.isExpired() || fleet.isEmpty()) {
                agent.suspend("舰队已覆灭或移除"); mode = "LOST"; fleetId = ""; save(); return;
            }
            if (!sector.isPaused()) GameWorld.passive(fleet);
            agent.advance(seconds, sector.isPaused());
            var result = agent.view().lastResult();
            if (result != null && result.step().action().equals("RETURN") && result.status() == com.mozhi.fleet.model.ExecutionResult.Status.SUCCEEDED) {
                mode = "MERGED"; fleetId = ""; save();
            }
        } catch (RuntimeException error) {
            problem = "舰队更新失败：" + Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            agent.suspend(problem);
        }
    }

    @Override public Map<String, Object> view() {
        requireOwner();
        var result = new LinkedHashMap<String, Object>();
        var fleet = GameWorld.find(sector, fleetId);
        if (fleet != null) result.putAll(GameWorld.fleet(fleet));
        var state = new LinkedHashMap<String, Object>();
        state.put("fleetId", fleetId); state.put("mode", mode); state.put("reason", problem);
        var current = agent != null ? agent.view() : detachedState == null ? null : detachedState.view();
        if (current != null) {
            if (!fleetId.isBlank()) state.put("mode", current.status().name());
            state.put("order", current.goal()); state.put("reason", problem.isBlank() ? current.reason() : problem);
            state.put("mission", Map.of("id", current.taskId(), "originalGoal", current.goal(), "status", current.status().name(), "reviewReason", current.reason()));
            state.put("plannerStatus", current.planning() ? "后台规划中" : "");
            if (current.plan() != null) {
                List<Map<String, Object>> steps = new ArrayList<>();
                for (int i = 0; i < current.plan().steps().size(); i++) {
                    Step step = current.plan().steps().get(i);
                    String status = i < current.currentStep() ? "COMPLETED" : "PENDING", feedback = "";
                    if (current.lastResult() != null && current.lastResult().step().id().equals(step.id())) {
                        status = current.lastResult().status().name(); if (status.equals("SUCCEEDED")) status = "COMPLETED";
                        feedback = current.lastResult().result();
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", step.id()); row.put("action", step.action()); row.put("description", step.description());
                    row.put("status", status); row.put("result", feedback); row.put("parameters", step.parameters());
                    row.put("destination", step.parameters().getOrDefault("destinationId", step.parameters().getOrDefault("marketId", "")));
                    row.put("item", step.parameters().getOrDefault("itemId", "")); row.put("quantity", step.parameters().getOrDefault("quantity", 0));
                    row.put("submarket", step.parameters().getOrDefault("submarketId", "")); steps.add(row);
                }
                state.put("plan", Map.of("id", current.plan().id(), "goal", current.goal(), "currentStep", current.currentStep(),
                        "status", current.status().name(), "feedback", current.reason(), "steps", steps));
            }
        }
        result.put("state", state);
        return result;
    }

    @Override public void save() {
        requireOwner();
        sector.getPersistentData().put(SAVE_KEY, encode(new Saved(2, fleetId, mode, problem, agent == null ? detachedState : agent.snapshot())));
    }

    @Override public void close() {
        // 宿主在读档时可从其他线程释放旧运行区；只取消后台工作，不触碰旧世界对象。
        closed = true;
        if (planner != null) planner.close();
    }
    @Override public boolean isStopped() { return planner == null || planner.isStopped(); }

    private CampaignFleetAPI controlled() {
        var fleet = GameWorld.find(sector, fleetId);
        if (fleet == null || fleet.isExpired() || fleet.isEmpty()) throw new IllegalStateException("没有可指挥的分舰队，请先派遣");
        return fleet;
    }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("舰队桥接必须在游戏主线程调用"); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("舰队数据编码失败", error); }
    }
    private static String text(JsonNode data, String key) {
        if (data == null || !data.path(key).isTextual() || data.path(key).asText().isBlank() || data.path(key).asText().length() > 4000)
            throw new IllegalArgumentException("文本参数无效：" + key);
        return data.path(key).asText().strip();
    }
    private static List<String> ships(JsonNode request) {
        if (!request.path("ships").isArray()) throw new IllegalArgumentException("需要舰船列表");
        List<String> result = new ArrayList<>();
        for (JsonNode ship : request.path("ships")) {
            if (!ship.isTextual()) throw new IllegalArgumentException("舰船名称或 ID 必须为文本");
            result.add(ship.asText());
        }
        return result;
    }
    private static float amount(JsonNode request, String key) {
        if (!request.path(key).isNumber()) throw new IllegalArgumentException("必须明确提供数值：" + key);
        float value = request.path(key).floatValue();
        if (!Float.isFinite(value) || value < 0 || value > 1_000_000_000) throw new IllegalArgumentException("资源数量无效：" + key);
        return value;
    }
    private static int integer(JsonNode request, String key) {
        if (!request.path(key).isIntegralNumber() || !request.path(key).canConvertToInt()) throw new IllegalArgumentException("整数参数无效：" + key);
        return request.path(key).intValue();
    }
}
