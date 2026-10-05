package com.mozhi.fleet.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import com.mozhi.llm.LlmClient;
import com.mozhi.llm.StructuredOutputException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 单线程异步规划器。只接收快照、返回候选结果，不访问游戏或修改当前计划。
 * 调用方在主线程轮询 Future，再核对任务、版本和实际进度；不要在游戏线程阻塞等待。
 */
public final class Planner implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Supplier<LlmClient> clientFactory;
    private final Supplier<LlmClient> cheapClientFactory;
    private final Supplier<LlmClient> recoveryClientFactory;
    private final ThreadPoolExecutor worker;
    private PlanningService service; // 仅由后台线程访问。
    private ReplanCheckService checkService;
    private PlanningService recoveryService;
    private PlanningRequest pendingRequest;
    private FutureTask<PlanningResult> pending;
    private boolean closed;

    public Planner(LlmClient client) {
        this(client, client);
    }

    public Planner(LlmClient client, LlmClient cheapClient) {
        this(client, cheapClient, client);
    }

    public Planner(LlmClient client, LlmClient cheapClient, LlmClient recoveryClient) {
        Objects.requireNonNull(client, "LLM 客户端");
        clientFactory = () -> client;
        cheapClientFactory = () -> Objects.requireNonNull(cheapClient);
        recoveryClientFactory = () -> Objects.requireNonNull(recoveryClient);
        worker = newWorker();
    }

    /** 配置读取、客户端初始化和网络请求均延迟到后台线程。 */
    public Planner(String configUrl) {
        ActionSpec.text(configUrl, "配置 URL");
        com.mozhi.llm.UsageMetrics.configure(configUrl, "fleet");
        clientFactory = () -> {
            try {
                return LlmClient.create(PlannerConfig.load(configUrl));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("无法读取舰队规划器的模型配置", error);
            }
        };
        cheapClientFactory = () -> {
            try { return LlmClient.create(PlannerConfig.load(configUrl, true)); }
            catch (java.io.IOException error) { throw new IllegalStateException("无法读取轻量模型配置", error); }
        };
        recoveryClientFactory = () -> {
            try { return LlmClient.create(PlannerConfig.recovery(PlannerConfig.load(configUrl))); }
            catch (java.io.IOException error) { throw new IllegalStateException("无法读取规划恢复配置", error); }
        };
        worker = newWorker();
    }

    private static ThreadPoolExecutor newWorker() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "Mozhi-Fleet-Planner");
            thread.setDaemon(true);
            thread.setContextClassLoader(Planner.class.getClassLoader());
            return thread;
        });
    }

    /**
     * 同一未完成快照的重复请求共享 Future。不同快照在忙碌时被拒绝，调用方合并触发原因后稍后再试；
     * 定时触发不应取消正在进行的规划。新用户任务可显式 cancel() 后提交。
     */
    public synchronized Future<PlanningResult> plan(PlanningRequest request) {
        Objects.requireNonNull(request, "规划请求");
        if (closed) throw new IllegalStateException("规划器已关闭");
        if (pending != null && !pending.isDone()) {
            if (request.equals(pendingRequest)) return pending;
            throw new IllegalStateException("已有规划请求正在进行，请合并触发并等待结果");
        }
        FutureTask<PlanningResult> task = new FutureTask<>(() -> generate(request));
        worker.purge();
        worker.execute(task);
        pendingRequest = request;
        pending = task;
        return task;
    }

    public synchronized boolean isPlanning() { return pending != null && !pending.isDone(); }

    /** 请求中断；即使提供商忽略中断，取消的 Future 也不会发布迟到结果。 */
    public synchronized boolean cancel() {
        boolean cancelled = pending != null && pending.cancel(true);
        worker.purge();
        return cancelled;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cancel();
        worker.shutdownNow();
    }

    /** 供后续类加载器释放逻辑查询；close() 本身不会阻塞游戏线程。 */
    public boolean isStopped() { return worker.isTerminated(); }

    private PlanningResult generate(PlanningRequest request) throws java.io.IOException {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("规划已取消");
        String advisoryDecision = null;
        if (request.lightCheck()) {
            try (var scope = com.mozhi.llm.UsageMetrics.scope(request.trigger().equals("定期重新规划") ? "periodic_check" : "resource_check")) {
                if (checkService == null) checkService = cheapClientFactory.get().aiService(ReplanCheckService.class);
                var check = checkService.check(PlanningPrompt.periodic(request));
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("规划已取消");
                Objects.requireNonNull(check.decision(), "周期检查决策");
                ActionSpec.text(check.reason(), "周期检查依据");
                if (check.decision() == ReplanCheckService.Decision.KEEP && request.currentPlan() != null)
                    return convert(request, new PlanningService.Draft(PlanningResult.Decision.KEEP, check.reason(), List.of()));
                advisoryDecision = check.decision() + "：" + check.reason();
            }
        }
        String category = request.completionReview() ? "review" : request.trigger().equals("新任务") ? "initial"
                : request.trigger().equals("定期重新规划") ? "periodic" : "replan";
        try (var scope = com.mozhi.llm.UsageMetrics.scope(category)) {
            if (service == null) service = clientFactory.get().aiService(PlanningService.class);
            PlanningService.Draft draft;
            try { draft = service.plan(PlanningPrompt.encode(request, advisoryDecision)); }
            catch (StructuredOutputException truncated) {
                if (truncated.kind() != StructuredOutputException.Kind.OUTPUT_LIMIT) throw truncated;
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("规划已取消");
                try (var retryScope = com.mozhi.llm.UsageMetrics.scope("planning_recovery")) {
                    if (recoveryService == null) recoveryService = recoveryClientFactory.get().aiService(PlanningService.class);
                    try { draft = recoveryService.plan(PlanningPrompt.recovery(request, advisoryDecision)); }
                    catch (RuntimeException failure) {
                        if (failure instanceof CancellationException) throw failure;
                        throw new IllegalStateException("结构化规划截断后已恢复重试一次，仍未成功：" + failure.getMessage());
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("规划已取消");
            return convert(request, draft);
        }
    }

    private PlanningResult convert(PlanningRequest request, PlanningService.Draft draft) {
        Objects.requireNonNull(draft, "规划输出");
        Objects.requireNonNull(draft.decision(), "规划决策");
        if (request.completionReview() && draft.decision() == PlanningResult.Decision.KEEP)
            throw new IllegalArgumentException("目标验收不能 KEEP，需要明确确认达成、重新规划或请玩家检查");
        ActionSpec.text(draft.reason(), "规划原因");
        Objects.requireNonNull(draft.steps(), "候选步骤");
        if (draft.decision() != PlanningResult.Decision.REPLACE && !draft.steps().isEmpty()) {
            throw new IllegalArgumentException("只有 REPLACE 决策可以包含新步骤");
        }
        Map<String, ActionSpec> actions = request.actions().stream()
                .collect(Collectors.toMap(ActionSpec::name, action -> action));
        Plan plan = switch (draft.decision()) {
            case REPLACE -> buildPlan(request, draft.steps(), actions);
            case KEEP -> {
                Plan current = Objects.requireNonNull(request.currentPlan(), "没有可保留的当前计划");
                List<Step> remaining = current.steps().stream()
                        .filter(step -> !request.executionHistory().completedStepIds().contains(step.id())).toList();
                if (remaining.isEmpty()) throw new IllegalArgumentException("当前计划已无剩余步骤，不能 KEEP");
                // KEEP 只能保留已有步骤；已由 Agent 授权的 RETURN 也可继续，仍禁止生成新 RETURN。
                for (Step step : remaining) if (!step.action().equals("RETURN")) validateAction(step, actions);
                yield current;
            }
            case GOAL_REACHED, BLOCKED -> null;
        };
        return new PlanningResult(request.taskId(), request.revision(), draft.decision(), draft.reason(), plan);
    }

    private Plan buildPlan(PlanningRequest request, List<PlanningService.DraftStep> drafts,
                           Map<String, ActionSpec> actions) {
        Map<String, Step> previous = request.currentPlan() == null ? Map.of()
                : request.currentPlan().steps().stream().collect(Collectors.toMap(Step::id, step -> step));
        List<Step> steps = new ArrayList<>();
        for (PlanningService.DraftStep draft : drafts) {
            Objects.requireNonNull(draft, "候选步骤");
            Objects.requireNonNull(draft.reuseStepId(), "复用步骤 ID；新步骤使用空字符串");
            Map<String, Object> raw = parseParameters(draft.parametersJson());
            Map<String, Object> parameters = tradeQuantities(draft.action(), raw);
            boolean normalized = !Objects.equals(raw.get("quantity"), parameters.get("quantity"));
            String description = normalized ? (draft.action().equals("BUY") ? "购买 " : "出售 ") + parameters.get("quantity")
                    + " × " + Objects.toString(parameters.get("itemId"), "指定物品") + "（数量已向下取整）" : draft.description();
            Step step = Step.create(draft.action(), parameters, description,
                    normalized ? "按整数计划数量成交并记录实际回执" : draft.expectedOutcome());
            validateAction(step, actions);
            if (!draft.reuseStepId().isEmpty()) {
                Step old = previous.get(draft.reuseStepId());
                if (old == null || request.executionHistory().completedStepIds().contains(old.id())) {
                    throw new IllegalArgumentException("不能复用不存在或已完成的步骤：" + draft.reuseStepId());
                }
                if (!old.action().equals(step.action()) || !sameParameters(old.parameters(), step.parameters())) {
                    throw new IllegalArgumentException("复用步骤不能改变动作或参数：" + old.id());
                }
                step = old;
            }
            steps.add(step);
        }
        return Plan.create(request.goal(), steps);
    }

    private static void validateAction(Step step, Map<String, ActionSpec> actions) {
        ActionSpec action = actions.get(step.action());
        if (action == null) throw new IllegalArgumentException("未知动作：" + step.action());
        action.validate(step.parameters());
    }

    /** 交易数量在计划落地前向下取整；Executor 只执行已确定的整数，不偷偷改成交量。 */
    private static Map<String, Object> tradeQuantities(String action, Map<String, Object> parameters) {
        if (!(action.equals("BUY") || action.equals("SELL")) || !(parameters.get("quantity") instanceof Number number)) return parameters;
        int quantity;
        try { quantity = new BigDecimal(number.toString()).setScale(0, RoundingMode.FLOOR).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("交易数量必须是有限且可表示的正数", invalid); }
        if (quantity < 1 || quantity > 1_000_000) throw new IllegalArgumentException("交易数量向下取整后必须在 1 至 1000000 之间");
        var normalized = new java.util.LinkedHashMap<>(parameters);
        normalized.put("quantity", quantity);
        return normalized;
    }

    private Map<String, Object> parseParameters(String text) {
        ActionSpec.text(text, "动作参数 JSON");
        try {
            JsonNode node = json.readTree(text);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("动作参数必须是 JSON 对象");
            return json.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("动作参数不是有效的 JSON 对象", error);
        }
    }

    private boolean sameParameters(Map<String, Object> left, Map<String, Object> right) {
        JsonNode first = json.valueToTree(left);
        JsonNode second = json.valueToTree(right);
        return first.equals((a, b) -> a.isNumber() && b.isNumber()
                ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, second);
    }
}
