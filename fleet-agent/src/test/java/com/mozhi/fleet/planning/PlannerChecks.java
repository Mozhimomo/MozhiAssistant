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
        asynchronousAndSingleFlight();
        decisionsAndStepIdentity();
        schemaOutput();
        executionHistoryWindow();
        invalidOutput();
        cancellationAndLateReply();
        configurationFailureIsAsynchronous();
        independentPlannerSettings();
        System.out.println("Planner checks passed");
    }

    private static void independentPlannerSettings() {
        var values = new java.util.Properties();
        values.setProperty("apiKey", "local-test-only"); values.setProperty("modelName", "local-test-model");
        values.setProperty("baseUrl", "http://localhost:1/v1"); values.setProperty("maxTokens", "8192");
        values.setProperty("thinkingMode", "enabled"); values.setProperty("reasoningEffort", "high");
        var inherited = PlannerConfig.from(values);
        check(inherited.outputTokens() == 8192 && inherited.thinkingMode().equals("enabled"), "Existing planner config remains compatible");
        values.setProperty("fleetPlannerThinkingMode", "disabled"); values.setProperty("fleetPlannerReasoningEffort", "none");
        values.setProperty("fleetPlannerMaxOutputTokens", "4096");
        var planner = PlannerConfig.from(values);
        check(planner.outputTokens() == 4096 && planner.thinkingMode().equals("disabled") && planner.reasoningEffort().equals("none"),
                "Fleet planner can disable lengthy reasoning independently");
        var chat = com.mozhi.llm.LlmConfig.from(values);
        check(chat.thinkingMode().equals("enabled") && chat.reasoningEffort().equals("high") && chat.outputTokens() == 8192,
                "Planner overrides never change chat generation settings");
    }

    private static void asynchronousAndSingleFlight() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Planner planner = planner(request -> {
            calls.incrementAndGet();
            check(Thread.currentThread() != caller, "LLM call must run off the caller thread");
            check(request.messages().toString().contains("task-1"), "Send task snapshot to model");
            check(request.messages().toString().contains("world-observation"), "Send world observations");
            check(request.messages().toString().contains("recentResults"), "Send structured execution history");
            entered.countDown();
            await(release);
            return replace("", "BUY", Map.of("quantity", 100));
        })) {
            PlanningRequest request = request(null, Set.of(), 1);
            Future<PlanningResult> future = planner.plan(request);
            await(entered);
            check(planner.isPlanning() && !future.isDone(), "Return while model is still waiting");
            check(planner.plan(request) == future, "Coalesce identical pending requests");
            rejects(IllegalStateException.class, () -> planner.plan(request(null, Set.of(), 2)));
            release.countDown();
            PlanningResult result = get(future);
            check(result.taskId().equals("task-1") && result.revision() == 1, "Preserve snapshot identity");
            check(result.plan().goal().equals(request.goal()), "Application preserves original goal");
            check(result.plan().steps().get(0).parameters().get("quantity").equals(100), "Convert typed parameters");
            check(calls.get() == 1, "Duplicate triggers must not issue another model call");
        } finally { release.countDown(); }
    }

    private static void decisionsAndStepIdentity() throws Exception {
        Step done = Step.create("BUY", Map.of("quantity", 10), "已执行的采购", "库存增加 10");
        Step remaining = Step.create("BUY", Map.of("quantity", 100L), "继续采购", "库存增加 100");
        Plan current = Plan.create("采购补给", List.of(done, remaining));
        PlanningRequest request = request(current, Set.of(done.id()), 4);
        try (Planner planner = planner(ignored -> replace(remaining.id(), "BUY", Map.of("quantity", 100)))) {
            PlanningResult result = get(planner.plan(request));
            check(!result.plan().id().equals(current.id()), "Replacement receives a fresh plan ID");
            check(result.plan().steps().get(0) == remaining, "Reuse unchanged step, including its identity");
            check(current.steps().size() == 2, "Planning leaves the current plan untouched");
        }
        try (Planner planner = planner(ignored -> decision("KEEP"))) {
            check(get(planner.plan(request)).plan() == current, "KEEP preserves the exact current plan");
        }
        for (String decision : List.of("GOAL_REACHED", "BLOCKED")) {
            try (Planner planner = planner(ignored -> decision(decision))) {
                PlanningResult result = get(planner.plan(request));
                check(result.plan() == null && result.decision().name().equals(decision), "Allow no-action decisions");
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
                replace("", "BUY", Map.of("quantity", 1.5)),
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
                    "steps", List.of(Map.of("reuseStepId", "", "action", "BUY", "parametersJson", parameters,
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
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("offline provider failed");
            return replace("", "BUY", Map.of("quantity", 100));
        })) {
            rejects(ExecutionException.class, () -> get(planner.plan(request)));
            check(get(planner.plan(request)).decision() == PlanningResult.Decision.REPLACE, "Recover after failed call");
        }
    }

    private static void schemaOutput() throws Exception {
        ChatModel model = new ChatModel() {
            @Override public Set<Capability> supportedCapabilities() {
                return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
            }
            @Override public ChatResponse chat(ChatRequest request) {
                check(request.responseFormat() != null && request.responseFormat().jsonSchema() != null,
                        "AI Service must use provider JSON Schema capability");
                check(request.responseFormat().jsonSchema().toString().contains("parametersJson=JsonStringSchema"),
                        "Dynamic arguments use a schema-compatible JSON string at the transport boundary");
                return ChatResponse.builder().aiMessage(AiMessage.from(replace("", "BUY", Map.of("quantity", 100)))).build();
            }
        };
        try (Planner planner = new Planner(LlmClient.of(model, null, 30))) {
            check(get(planner.plan(request(null, Set.of(), 1))).plan().steps().size() == 1,
                    "Schema-mode response converts to executable definition");
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
                    check(released, "Timed out waiting to release late provider reply");
                }
            }
            return replace("", "BUY", Map.of("quantity", 100));
        });
        try {
            Future<PlanningResult> old = planner.plan(request(null, Set.of(), 1));
            await(entered);
            check(planner.cancel() && old.isCancelled(), "Cancel outstanding result immediately");
            rejects(CancellationException.class, () -> get(old));
            for (int revision = 2; revision < 6; revision++) {
                Future<PlanningResult> queued = planner.plan(request(null, Set.of(), revision));
                check(queued.cancel(true), "External Future cancellation is supported");
            }
            Future<PlanningResult> next = planner.plan(request(null, Set.of(), 6));
            release.countDown();
            check(get(next).revision() == 6, "Never deliver old result as the new snapshot");
            check(calls.get() == 2, "Cancelled queued requests never reach the model");
            check(old.isCancelled(), "Late reply cannot overwrite cancellation");
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
            check(future.isCancelled(), "Close cancels pending result");
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
        check(before.recentResults().size() == 20, "Keep at most 20 steps");
        check(before.recentResults().get(0).step().id().equals("history-6"), "Drop oldest detailed results");
        check(before.completedStepIds().size() == 25 && before.completedStepIds().contains("history-1"),
                "Preserve completed IDs beyond the window");
        check(new ExecutionHistory.Snapshot(all, Set.of()).equals(before), "Direct snapshots also enforce window");
        Step active = new Step("active", "BUY", Map.of("quantity", 50), "采购", "增加库存");
        history.record(new ExecutionResult(active, ExecutionResult.Status.RUNNING, "detail-running"));
        history.record(new ExecutionResult(active, ExecutionResult.Status.WAITING, "detail-waiting"));
        history.record(new ExecutionResult(active, ExecutionResult.Status.FAILED, "detail-failed-no-stock"));
        ExecutionHistory.Snapshot after = history.snapshot();
        check(after.recentResults().size() == 20, "Repeated ticks consume one history slot");
        check(after.recentResults().get(0).step().id().equals("history-7"), "Only one additional step evicts one record");
        check(after.recentResults().get(19).status() == ExecutionResult.Status.FAILED, "Keep latest actual failure");
        check(!after.completedStepIds().contains(active.id()), "Running, waiting and failed steps are not completed");
        check(before.recentResults().get(19).step().id().equals("history-25"), "Previously captured snapshot stays unchanged");
        rejects(UnsupportedOperationException.class, () -> after.recentResults().clear());
        rejects(UnsupportedOperationException.class, () -> after.completedStepIds().clear());
        ExecutionHistory.Snapshot restored = JSON.readValue(JSON.writeValueAsString(after), ExecutionHistory.Snapshot.class);
        ExecutionHistory resumed = new ExecutionHistory(restored);
        check(resumed.snapshot().equals(after), "Restore window and completed identities together");
        try (Planner planner = planner(request -> {
            String input = request.messages().toString();
            check(input.contains("detail-0007") && input.contains("detail-failed-no-stock"), "Model receives recent effects and failures");
            check(!input.contains("detail-0001") && !input.contains("detail-0006"), "Model does not receive evicted results");
            check(!input.contains("detail-running") && !input.contains("detail-waiting"), "Model receives one latest result per step");
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
                    Map.of("reuseStepId", reuseId, "action", action, "parametersJson", JSON.writeValueAsString(parameters),
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
        try { check(latch.await(5, TimeUnit.SECONDS), "Latch timed out"); }
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
