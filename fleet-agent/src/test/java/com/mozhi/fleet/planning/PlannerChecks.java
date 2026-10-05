package com.mozhi.fleet.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.llm.LlmClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 真实 LlmClient / AI Service + 本地假模型，检查规划转换和并发边界。 */
public final class PlannerChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ActionSpec BUY = new ActionSpec("BUY", "只在已经抵达的市场采购，成功后增加库存", List.of(
            new ActionSpec.Parameter("quantity", ActionSpec.Type.INTEGER, true, "采购数量，必须为正数")));

    public static void main(String[] args) throws Exception {
        var configFile = java.nio.file.Files.createTempFile(java.nio.file.Path.of("fleet-agent/target"), "utf8-", ".properties");
        try {
            java.nio.file.Files.writeString(configFile, "\uFEFFmodelName=中文模型\ncheapModelName=轻量模型\napiKey=offline-test-key\n# 中文注释\n");
            check(PlannerConfig.load(configFile.toUri().toString()).modelName().equals("中文模型")
                    && PlannerConfig.load(configFile.toUri().toString(), true).modelName().equals("轻量模型"), "规划配置兼容 UTF-8 编码标记");
        } finally { java.nio.file.Files.deleteIfExists(configFile); }
        asynchronousAndSingleFlight();
        decisionsAndStepIdentity();
        schemaOutput();
        executionHistoryWindow();
        invalidOutput();
        cancellationAndLateReply();
        configurationFailureIsAsynchronous();
        independentPlannerSettings();
        cheapPeriodicRouting();
        truncatedPlanningRecovery();
        integerTradeQuantities();
        System.out.println("规划器检查通过");
    }

    private static void independentPlannerSettings() {
        var values = new java.util.Properties();
        values.setProperty("apiKey", "local-test-only"); values.setProperty("modelName", "local-test-model");
        values.setProperty("baseUrl", "http://localhost:1/v1"); values.setProperty("maxTokens", "8192");
        values.setProperty("thinkingMode", "enabled"); values.setProperty("reasoningEffort", "high");
        var inherited = PlannerConfig.from(values);
        check(inherited.outputTokens() == 8192 && inherited.thinkingMode().equals("enabled"), "现有规划器配置保持兼容");
        var recovery = PlannerConfig.recovery(inherited);
        check(recovery.outputTokens() == inherited.outputTokens() && recovery.modelName().equals(inherited.modelName())
                && recovery.thinkingMode().equals("disabled") && recovery.reasoningEffort() == null, "截断恢复沿用模型与输出上限，仅独立关闭思考");
        check(inherited.thinkingMode().equals("enabled") && inherited.reasoningEffort().equals("high"), "恢复配置不修改正常请求配置");
        values.setProperty("fleetPlannerThinkingMode", "disabled"); values.setProperty("fleetPlannerReasoningEffort", "none");
        values.setProperty("fleetPlannerMaxOutputTokens", "4096");
        var planner = PlannerConfig.from(values);
        check(planner.outputTokens() == 4096 && planner.thinkingMode().equals("disabled") && planner.reasoningEffort().equals("none"),
                "舰队规划器可以独立禁用长时间思考");
        var chat = com.mozhi.llm.LlmConfig.from(values);
        check(chat.thinkingMode().equals("enabled") && chat.reasoningEffort().equals("high") && chat.outputTokens() == 8192,
                "规划器覆盖项不改变聊天生成配置");
        var cheap = com.mozhi.llm.LlmConfig.cheapFrom(values);
        check(cheap.modelName().equals(chat.modelName()) && cheap.outputTokens() == 8192, "轻量模型默认继承主模型配置");
        values.setProperty("cheapModelName", "light-test"); values.setProperty("cheapMaxTokens", "1024");
        values.setProperty("cheapThinkingMode", "disabled"); values.setProperty("cheapReasoningEffort", "none");
        cheap = com.mozhi.llm.LlmConfig.cheapFrom(values);
        check(cheap.modelName().equals("light-test") && cheap.outputTokens() == 1024 && cheap.thinkingMode().equals("disabled")
                && com.mozhi.llm.LlmConfig.from(values).modelName().equals("local-test-model"), "轻量模型路由覆盖项相互独立");
    }

    private static void cheapPeriodicRouting() throws Exception {
        AtomicInteger main = new AtomicInteger(), light = new AtomicInteger();
        ChatModel mainModel = new ChatModel() {
            public ChatResponse chat(ChatRequest request) {
                main.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from(decision("GOAL_REACHED"))).build();
            }
        };
        ChatModel lightModel = new ChatModel() {
            public ChatResponse chat(ChatRequest request) {
                String text = request.messages().toString();
                check(!text.contains("huge-market-catalog") && !text.contains("expensive-action-description"), "周期判断不包含市场库存和动作契约");
                String decision = light.getAndIncrement() == 0 ? "KEEP" : "REPLAN";
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"decision\":\"" + decision + "\",\"reason\":\"检查依据\"}")).build();
            }
        };
        Plan plan = Plan.create("采购", List.of(Step.create("BUY", Map.of("quantity", 20), "购买", "完成")));
        var req = new PlanningRequest("route-test", 0, "采购", "{\"markets\":\"huge-market-catalog\",\"controlledFleet\":{\"credits\":1000}}",
                new ExecutionHistory.Snapshot(List.of(), Set.of()), "定期重新规划",
                List.of(new ActionSpec("BUY", "expensive-action-description", BUY.parameters())), plan);
        try (Planner planner = new Planner(LlmClient.of(mainModel, null, 30), LlmClient.of(lightModel, null, 30))) {
            check(get(planner.plan(req)).decision() == PlanningResult.Decision.KEEP && main.get() == 0, "KEEP 判断不调用主规划器");
            check(get(planner.plan(req)).decision() == PlanningResult.Decision.GOAL_REACHED && main.get() == 1, "REPLAN 判断升级至主规划器");
            var review = new PlanningRequest(req.taskId(), 1, req.goal(), req.worldState(), req.executionHistory(), "验收", req.actions(), plan, null, true);
            get(planner.plan(review));
            check(main.get() == 2 && light.get() == 2, "目标验收直接使用主模型");
        }
    }

    private static void asynchronousAndSingleFlight() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Planner planner = planner(request -> {
            calls.incrementAndGet();
            check(Thread.currentThread() != caller, "大模型调用必须离开调用方线程执行");
            check(request.messages().toString().contains("task-1"), "向模型发送任务快照");
            check(request.messages().toString().contains("world-observation"), "发送世界观测");
            check(request.messages().toString().contains("recentResults"), "发送结构化执行历史");
            entered.countDown();
            await(release);
            return replace("", "BUY", Map.of("quantity", 100));
        })) {
            PlanningRequest request = request(null, Set.of(), 1);
            Future<PlanningResult> future = planner.plan(request);
            await(entered);
            check(planner.isPlanning() && !future.isDone(), "模型仍在等待时调用方即可返回");
            check(planner.plan(request) == future, "合并相同的待处理请求");
            rejects(IllegalStateException.class, () -> planner.plan(request(null, Set.of(), 2)));
            release.countDown();
            PlanningResult result = get(future);
            check(result.taskId().equals("task-1") && result.revision() == 1, "保留快照身份");
            check(result.plan().goal().equals(request.goal()), "应用保留原始目标");
            check(result.plan().steps().get(0).parameters().get("quantity").equals(100), "转换带类型的参数");
            check(calls.get() == 1, "重复触发不得增加模型调用");
        } finally { release.countDown(); }
    }

    private static void decisionsAndStepIdentity() throws Exception {
        Step done = Step.create("BUY", Map.of("quantity", 10), "已执行的采购", "库存增加 10");
        Step remaining = Step.create("BUY", Map.of("quantity", 100L), "继续采购", "库存增加 100");
        Plan current = Plan.create("采购补给", List.of(done, remaining));
        PlanningRequest request = request(current, Set.of(done.id()), 4);
        try (Planner planner = planner(ignored -> replace(remaining.id(), "BUY", Map.of("quantity", 100)))) {
            PlanningResult result = get(planner.plan(request));
            check(!result.plan().id().equals(current.id()), "替换计划获得新的计划 ID");
            check(result.plan().steps().get(0) == remaining, "复用未改变的步骤并保留其身份");
            check(current.steps().size() == 2, "规划不修改当前计划");
        }
        try (Planner planner = planner(ignored -> decision("KEEP"))) {
            check(get(planner.plan(request)).plan() == current, "KEEP 完整保留当前计划");
        }
        for (String decision : List.of("GOAL_REACHED", "BLOCKED")) {
            try (Planner planner = planner(ignored -> decision(decision))) {
                PlanningResult result = get(planner.plan(request));
                check(result.plan() == null && result.decision().name().equals(decision), "允许不含动作的决策");
            }
        }
    }

    private static void invalidOutput() throws Exception {
        Step remaining = Step.create("BUY", Map.of("quantity", 100), "采购", "库存增加 100");
        Plan current = Plan.create("采购补给", List.of(remaining));
        PlanningRequest request = request(current, Set.of(), 1);
        for (String response : List.of(
                "not-json", "{}", decision("REPLACE"),
                replace("", "UNKNOWN", Map.of("quantity", 100)),
                replace("", "BUY", Map.of()),
                replace("", "BUY", Map.of("quantity", "100")),
                replace("", "BUY", Map.of("quantity", 0.5)),
                replace("", "BUY", Map.of("quantity", 100, "extra", true)),
                replace("missing", "BUY", Map.of("quantity", 100)),
                replace(remaining.id(), "BUY", Map.of("quantity", 200)),
                replace("", "BUY", Map.of("quantity", 100)).replace("\"REPLACE\"", "\"KEEP\""))) {
            try (Planner planner = planner(ignored -> response)) {
                rejects(ExecutionException.class, () -> get(planner.plan(request)));
            }
        }
        for (String parameters : List.of("[]", "null", "{", "{} {}", "{\"quantity\":1,\"quantity\":2}")) {
            String response = JSON.writeValueAsString(Map.of("decision", "REPLACE", "reason", "检查参数边界",
                    "steps", List.of(Map.of("reuseStepId", "", "tool", "BUY", "argumentsJson", parameters,
                            "description", "采购", "expectedOutcome", "增加库存"))));
            try (Planner planner = planner(ignored -> response)) {
                rejects(ExecutionException.class, () -> get(planner.plan(request)));
            }
        }
        try (Planner planner = planner(ignored -> replace(remaining.id(), "BUY", Map.of("quantity", 100)))) {
            rejects(ExecutionException.class, () -> get(planner.plan(request(current, Set.of(remaining.id()), 2))));
        }
        try (Planner planner = planner(ignored -> decision("KEEP"))) {
            rejects(ExecutionException.class, () -> get(planner.plan(request(null, Set.of(), 1))));
            rejects(ExecutionException.class, () -> get(planner.plan(request(current, Set.of(remaining.id()), 2))));
        }
        AtomicInteger attempts = new AtomicInteger();
        try (Planner planner = planner(ignored -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("离线模拟服务调用失败");
            return replace("", "BUY", Map.of("quantity", 100));
        })) {
            rejects(ExecutionException.class, () -> get(planner.plan(request)));
            check(get(planner.plan(request)).decision() == PlanningResult.Decision.REPLACE, "调用失败后可恢复");
        }
    }

    private static void truncatedPlanningRecovery() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger(), recoveryCalls = new AtomicInteger();
        ChatModel truncated = new ChatModel() {
            public ChatResponse chat(ChatRequest request) {
                primaryCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("不可复用的截断正文"))
                        .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH).build()).build();
            }
        };
        ChatModel recovered = new ChatModel() {
            public ChatResponse chat(ChatRequest request) {
                recoveryCalls.incrementAndGet();
                String prompt = request.messages().toString();
                check(prompt.contains("这是生成失败") && prompt.contains("world-observation") && !prompt.contains("不可复用的截断正文"), "恢复基于同一快照，不续写损坏输出或伪造动作失败");
                return ChatResponse.builder().aiMessage(AiMessage.from(replace("", "BUY", Map.of("quantity", 16)))).build();
            }
        };
        var main = LlmClient.of(truncated, null, 30);
        try (var planner = new Planner(main, main, LlmClient.of(recovered, null, 30))) {
            var result = get(planner.plan(request(null, Set.of(), 1)));
            check(result.plan().steps().get(0).parameters().get("quantity").equals(16)
                    && primaryCalls.get() == 1 && recoveryCalls.get() == 1, "截断后一次独立恢复得到完整计划");
        }
        primaryCalls.set(0);
        try (var planner = new Planner(main, main, main)) {
            try { get(planner.plan(request(null, Set.of(), 1))); throw new AssertionError("接受了两次截断的结果"); }
            catch (ExecutionException expected) { check(expected.getCause().getMessage().contains("已恢复重试一次"), "持续失败明确报告已尝试恢复"); }
            check(primaryCalls.get() == 2, "恢复最多一次，不进入无限重试");
        }
        ChatModel cancelled = new ChatModel() {
            public ChatResponse chat(ChatRequest request) { Thread.currentThread().interrupt(); return truncated.chat(request); }
        };
        recoveryCalls.set(0);
        try (var planner = new Planner(LlmClient.of(cancelled, null, 30), main, LlmClient.of(recovered, null, 30))) {
            rejects(ExecutionException.class, () -> get(planner.plan(request(null, Set.of(), 1))));
            check(recoveryCalls.get() == 0, "已取消请求不启动恢复调用");
        }
    }

    private static void integerTradeQuantities() throws Exception {
        for (String action : List.of("BUY", "SELL")) for (Number number : List.of(16.9, 16.0, new java.math.BigDecimal("16.999999999999999999"), 20)) {
            var spec = new ActionSpec(action, "交易", BUY.parameters());
            var request = new PlanningRequest("quantity", 1, "按数量成交", "当前库存", new ExecutionHistory.Snapshot(List.of(), Set.of()), "执行失败", List.of(spec), null);
            try (var planner = planner(ignored -> replace("", action, Map.of("quantity", number)))) {
                var step = get(planner.plan(request)).plan().steps().get(0);
                check(step.parameters().get("quantity") instanceof Integer && step.parameters().get("quantity").equals(number.intValue()), "计划中的买卖数量向下取整");
                check(!step.description().contains("16.9"), "界面描述也不能保留未取整数量");
            }
        }
        for (Number invalid : List.of(.9, 0, -1.2, 1_000_001.9)) try (var planner = planner(ignored -> replace("", "BUY", Map.of("quantity", invalid)))) {
            rejects(ExecutionException.class, () -> get(planner.plan(request(null, Set.of(), 1))));
        }
    }

    private static void schemaOutput() throws Exception {
        ChatModel model = new ChatModel() {
            @Override public Set<Capability> supportedCapabilities() {
                return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            }
            @Override public ChatResponse chat(ChatRequest request) {
                check(request.responseFormat() != null && request.responseFormat().jsonSchema() != null,
                        "AI Service 必须使用服务商的 JSON Schema 能力");
                check(request.responseFormat().jsonSchema().toString().contains("argumentsJson=JsonStringSchema"),
                        "动态参数在传输边界使用兼容结构约束的 JSON 字符串");
                return ChatResponse.builder().aiMessage(AiMessage.from(replace("", "BUY", Map.of("quantity", 100)))).build();
            }
        };
        try (Planner planner = new Planner(LlmClient.of(model, null, 30))) {
            check(get(planner.plan(request(null, Set.of(), 1))).plan().steps().size() == 1,
                    "结构约束模式的响应可转换为可执行定义");
        }
    }

    private static void cancellationAndLateReply() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Planner planner = planner(ignored -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try { released = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignoredInterrupt) { continue; }
                    check(released, "等待释放服务商迟到回复超时");
                }
            }
            return replace("", "BUY", Map.of("quantity", 100));
        });
        try {
            Future<PlanningResult> old = planner.plan(request(null, Set.of(), 1));
            await(entered);
            check(planner.cancel() && old.isCancelled(), "立即取消未完成的结果");
            rejects(CancellationException.class, () -> get(old));
            for (int revision = 2; revision < 6; revision++) {
                Future<PlanningResult> queued = planner.plan(request(null, Set.of(), revision));
                check(queued.cancel(true), "支持从外部取消 Future");
            }
            Future<PlanningResult> next = planner.plan(request(null, Set.of(), 6));
            release.countDown();
            check(get(next).revision() == 6, "不得将旧结果作为新快照返回");
            check(calls.get() == 2, "已取消的排队请求不调用模型");
            check(old.isCancelled(), "迟到回复不能覆盖取消状态");
        } finally {
            release.countDown();
            planner.close();
        }
        planner.close();
        rejects(IllegalStateException.class, () -> planner.plan(request(null, Set.of(), 7)));

        CountDownLatch closingEntered = new CountDownLatch(1);
        CountDownLatch closingRelease = new CountDownLatch(1);
        Planner closing = planner(ignored -> {
            closingEntered.countDown();
            await(closingRelease);
            return decision("BLOCKED");
        });
        try {
            Future<PlanningResult> future = closing.plan(request(null, Set.of(), 1));
            await(closingEntered);
            closing.close();
            check(future.isCancelled(), "关闭时取消待处理结果");
        } finally { closingRelease.countDown(); closing.close(); }
    }

    private static void configurationFailureIsAsynchronous() throws Exception {
        try (Planner planner = new Planner("missing-scheme://fleet-planner/config")) {
            rejects(ExecutionException.class, () -> get(planner.plan(request(null, Set.of(), 1))));
        }
    }

    private static void executionHistoryWindow() throws Exception {
        ExecutionHistory history = new ExecutionHistory();
        List<ExecutionResult> all = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            Step step = new Step("history-" + i, "BUY", Map.of("quantity", i), "采购", "增加库存");
            ExecutionResult result = new ExecutionResult(step, ExecutionResult.Status.SUCCEEDED,
                    String.format("detail-%04d", i));
            all.add(result);
            history.record(result);
        }
        ExecutionHistory.Snapshot before = history.snapshot();
        check(before.recentResults().size() == 20, "最多保留 20 步");
        check(before.recentResults().get(0).step().id().equals("history-6"), "丢弃最旧的详细结果");
        check(before.completedStepIds().size() == 25 && before.completedStepIds().contains("history-1"),
                "保留窗口之外的已完成步骤 ID");
        check(new ExecutionHistory.Snapshot(all, Set.of()).equals(before), "直接构建的快照也遵守窗口限制");
        Step active = new Step("active", "BUY", Map.of("quantity", 50), "采购", "增加库存");
        history.record(new ExecutionResult(active, ExecutionResult.Status.RUNNING, "detail-running"));
        history.record(new ExecutionResult(active, ExecutionResult.Status.WAITING, "detail-waiting"));
        history.record(new ExecutionResult(active, ExecutionResult.Status.FAILED, "detail-failed-no-stock"));
        ExecutionHistory.Snapshot after = history.snapshot();
        check(after.recentResults().size() == 20, "同一步骤的重复帧只占一个历史位置");
        check(after.recentResults().get(0).step().id().equals("history-7"), "仅新增步骤时才淘汰一条记录");
        check(after.recentResults().get(19).status() == ExecutionResult.Status.FAILED, "保留最新实际失败结果");
        check(!after.completedStepIds().contains(active.id()), "运行、等待和失败步骤均不属于已完成");
        check(before.recentResults().get(19).step().id().equals("history-25"), "先前捕获的快照保持不变");
        rejects(UnsupportedOperationException.class, () -> after.recentResults().clear());
        rejects(UnsupportedOperationException.class, () -> after.completedStepIds().clear());
        ExecutionHistory.Snapshot restored = JSON.readValue(JSON.writeValueAsString(after), ExecutionHistory.Snapshot.class);
        ExecutionHistory resumed = new ExecutionHistory(restored);
        check(resumed.snapshot().equals(after), "同时恢复历史窗口与已完成步骤身份");
        try (Planner planner = planner(request -> {
            String input = request.messages().toString();
            check(input.contains("detail-0007") && input.contains("detail-failed-no-stock"), "模型接收最近效果与失败信息");
            check(!input.contains("detail-0001") && !input.contains("detail-0006"), "模型不接收已淘汰结果");
            check(!input.contains("detail-running") && !input.contains("detail-waiting"), "模型接收每步的唯一最新结果");
            return decision("BLOCKED");
        })) {
            PlanningRequest request = new PlanningRequest("history-task", 26, "采购补给", "已经抵达市场",
                    after, "执行失败", List.of(BUY), null);
            get(planner.plan(request));
        }
    }

    private static PlanningRequest request(Plan current, Set<String> completed, long revision) {
        return new PlanningRequest("task-1", revision, "采购补给", "world-observation: 已抵达市场",
                new ExecutionHistory.Snapshot(List.of(), completed), "periodic", List.of(BUY), current);
    }

    private static Planner planner(Function<ChatRequest, String> output) {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(output.apply(request))).build();
            }
        };
        return new Planner(LlmClient.of(model, null, 30));
    }

    private static String replace(String reuseId, String action, Map<String, Object> parameters) {
        try {
            return JSON.writeValueAsString(Map.of("decision", "REPLACE", "reason", "需要继续采购", "steps", List.of(
                    Map.of("reuseStepId", reuseId, "tool", action, "argumentsJson", JSON.writeValueAsString(parameters),
                            "description", "采购补给", "expectedOutcome", "库存增加"))));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static String decision(String decision) {
        return "{\"decision\":\"" + decision + "\",\"reason\":\"依据当前观察\",\"steps\":[]}";
    }

    private static PlanningResult get(Future<PlanningResult> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "等待同步信号超时"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> expected, CheckedAction action) {
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Expected " + expected.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
