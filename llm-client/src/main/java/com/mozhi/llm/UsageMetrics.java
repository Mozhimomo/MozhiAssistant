package com.mozhi.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 仅记录用量元数据，绝不写提示词、正文、思考、异常正文、HTTP 头或密钥。 */
public final class UsageMetrics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<String> CATEGORY = ThreadLocal.withInitial(() -> "other");
    private static final Map<String, long[]> TOTALS = new TreeMap<>();
    private static Path logFile;
    private static long logErrors;
    private UsageMetrics() {}

    public static synchronized void configure(String configUrl, String component) {
        if (!Set.of("fleet", "chat").contains(component)) throw new IllegalArgumentException("未知的用量统计组件");
        try {
            URI uri = URI.create(configUrl);
            if ("file".equalsIgnoreCase(uri.getScheme()))
                logFile = Path.of(uri).toAbsolutePath().getParent().getParent().resolve("diagnostics/usage-" + component + ".jsonl");
        } catch (RuntimeException ignored) { logErrors++; }
    }

    public static Scope scope(String category) {
        if (!Set.of("initial", "periodic_check", "resource_check", "periodic", "replan", "review", "planning_recovery", "chat", "notification", "summary", "other").contains(category))
            throw new IllegalArgumentException("未知的用量统计分类");
        String previous = CATEGORY.get(); CATEGORY.set(category); return () -> CATEGORY.set(previous);
    }
    public interface Scope extends AutoCloseable { @Override void close(); }

    static ChatResponse measure(ChatRequest request, Supplier<ChatResponse> call) {
        long started = System.nanoTime();
        ChatResponse response = null;
        boolean success = false;
        try { response = call.get(); success = response != null; return response; }
        finally { record(request, response, success, (System.nanoTime() - started) / 1_000_000); }
    }

    private static synchronized void record(ChatRequest request, ChatResponse response, boolean success, long millis) {
        try {
            var usage = response == null ? null : response.tokenUsage();
            Integer input = usage == null ? null : usage.inputTokenCount(), output = usage == null ? null : usage.outputTokenCount();
            Integer hit = null, miss = null;
            if (usage instanceof OpenAiTokenUsage openai && openai.inputTokensDetails() != null) hit = openai.inputTokensDetails().cachedTokens();
            if (response != null && response.metadata() instanceof OpenAiChatResponseMetadata metadata) {
                JsonNode raw = null;
                if (metadata.rawHttpResponse() != null) raw = rawUsage(metadata.rawHttpResponse().body());
                if (metadata.rawServerSentEvents() != null) for (var event : metadata.rawServerSentEvents()) {
                    JsonNode candidate = rawUsage(event.data()); if (candidate != null) raw = candidate;
                }
                if (raw != null) {
                    if (raw.hasNonNull("prompt_cache_hit_tokens")) hit = raw.get("prompt_cache_hit_tokens").intValue();
                    if (raw.hasNonNull("prompt_cache_miss_tokens")) miss = raw.get("prompt_cache_miss_tokens").intValue();
                }
            }
            if (hit != null && input != null && miss == null && hit >= 0 && hit <= input) miss = input - hit;
            long[] totals = TOTALS.computeIfAbsent(CATEGORY.get(), ignored -> new long[8]);
            totals[0]++; if (!success) totals[1]++;
            if (input != null) totals[2] += input;
            if (output != null) totals[3] += output;
            if (hit != null && miss != null) { totals[4] += hit; totals[5] += miss; totals[6]++; }
            if (input != null && output != null) totals[7]++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", Instant.now().toString()); row.put("kind", "call"); row.put("category", CATEGORY.get());
            row.put("success", success); row.put("elapsedMillis", millis);
            row.put("messageChars", request.messages().stream().mapToInt(message -> message.toString().length()).sum());
            row.put("inputTokens", input); row.put("outputTokens", output); row.put("cacheHitTokens", hit); row.put("cacheMissTokens", miss);
            row.put("finishReason", response == null || response.finishReason() == null ? null : response.finishReason().name());
            var answer = response == null ? null : response.aiMessage();
            row.put("answerChars", answer == null || answer.text() == null ? 0 : answer.text().length());
            row.put("thinkingChars", answer == null || answer.thinking() == null ? 0 : answer.thinking().length());
            write(row);
        } catch (RuntimeException ignored) { logErrors++; }
    }

    private static JsonNode rawUsage(String text) {
        if (text == null || text.isBlank() || text.equals("[DONE]")) return null;
        try { JsonNode root = JSON.readTree(text); return root.hasNonNull("usage") ? root.get("usage") : null; }
        catch (Exception ignored) { return null; }
    }

    public static synchronized void event(String kind) {
        if (!Set.of("staleResult", "cancelledRequest").contains(kind)) throw new IllegalArgumentException("未知的用量统计事件");
        TOTALS.computeIfAbsent(kind, ignored -> new long[8])[0]++;
        write(Map.of("time", Instant.now().toString(), "kind", kind));
    }

    public static synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        TOTALS.forEach((key, n) -> result.put(key, Map.of("calls", n[0], "failedCalls", n[1], "knownInputTokens", n[2],
                "knownOutputTokens", n[3], "knownCacheHitTokens", n[4], "knownCacheMissTokens", n[5], "cacheReportedCalls", n[6], "usageReportedCalls", n[7])));
        result.put("logErrors", logErrors);
        result.put("scope", "当前类加载器生命周期累计；未知用量不作为零用量，失败/取消请求可能已计费。缓存比例只用 cacheReportedCalls 对应 hit/miss 计算；SDK 内部重试可能不可见");
        return result;
    }

    private static void write(Map<String, Object> row) {
        if (logFile == null) return;
        try {
            Files.createDirectories(logFile.getParent());
            if (Files.exists(logFile) && Files.size(logFile) >= 5 * 1024 * 1024)
                Files.move(logFile, logFile.resolveSibling(logFile.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(logFile, JSON.writeValueAsString(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { logErrors++; }
    }
}
