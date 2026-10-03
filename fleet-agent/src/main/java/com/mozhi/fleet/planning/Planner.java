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
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Supplier<LlmClient> clientFactory;
    private final ThreadPoolExecutor worker;
    private PlanningService service; // 仅由后台线程访问。
    private PlanningRequest pendingRequest;
    private FutureTask<PlanningResult> pending;
    private boolean closed;

    public Planner(LlmClient client) {
        Objects.requireNonNull(client, "LLM 客户端");
        clientFactory = () -> client;
        worker = newWorker();
    }

    /** 配置读取、客户端初始化和网络请求均延迟到后台线程。 */
    public Planner(String configUrl) {
        ActionSpec.text(configUrl, "配置 URL");
        clientFactory = () -> {
            try {
                return LlmClient.create(PlannerConfig.load(configUrl));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("无法读取舰队规划器的模型配置", error);
            }
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

    private PlanningResult generate(PlanningRequest request) throws JsonProcessingException {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("规划已取消");
        if (service == null) service = clientFactory.get().aiService(PlanningService.class);
        PlanningService.Draft draft = service.plan(json.writeValueAsString(request));
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("规划已取消");
        return convert(request, draft);
    }

    private PlanningResult convert(PlanningRequest request, PlanningService.Draft draft) {
        Objects.requireNonNull(draft, "规划输出");
        Objects.requireNonNull(draft.decision(), "规划决策");
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
                for (Step step : remaining) validateAction(step, actions);
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
            Step step = Step.create(draft.action(), parseParameters(draft.parametersJson()), draft.description(), draft.expectedOutcome());
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
