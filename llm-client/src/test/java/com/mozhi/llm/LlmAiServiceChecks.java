package com.mozhi.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.UserMessage;
import java.util.Properties;
import java.util.concurrent.CancellationException;

/** 无网络检查：类型化入口仍遵循客户端配置、隔离、脱敏和取消约定。 */
public final class LlmAiServiceChecks {
    public record Answer(String value) {}
    public interface Service { Answer answer(@UserMessage String text); }

    public static void main(String[] args) throws Exception {
        var configFile = java.nio.file.Files.createTempFile(java.nio.file.Path.of("llm-client/target"), "utf8-", ".properties");
        try {
            for (String prefix : new String[]{"", "\uFEFF"}) {
                java.nio.file.Files.writeString(configFile, prefix + "modelName=中文模型\napiKey=offline-test-key\n# 中文注释\n");
                check(LlmConfig.load(configFile.toUri().toString()).modelName().equals("中文模型"), "带或不带编码标记时均正确读取首项配置与中文");
            }
        } finally { java.nio.file.Files.deleteIfExists(configFile); }
        usageMetrics();
        emptyResponses();
        Properties properties = new Properties();
        properties.setProperty("apiKey", "offline-secret");
        properties.setProperty("modelName", "offline-model");
        LlmConfig prompt = LlmConfig.from(properties);
        check(!ModelFactory.createChatModel(prompt).supportedCapabilities()
                .contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA), "默认不要求服务端支持原生结构约束");
        properties.setProperty("structuredOutputMode", "json_schema");
        LlmConfig schema = LlmConfig.from(properties).withGeneration(4096, null, null);
        check(ModelFactory.createChatModel(schema).supportedCapabilities()
                .contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA), "派生配置保留结构约束能力");
        properties.setProperty("structuredOutputMode", "invalid");
        try { LlmConfig.from(properties); throw new AssertionError("错误地接受了无效的输出模式"); }
        catch (IllegalArgumentException expected) {}

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new ClassLoader(null) {};
        try {
            Thread.currentThread().setContextClassLoader(caller);
            ChatModel good = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    check(Thread.currentThread().getContextClassLoader() == DefaultLlmClient.class.getClassLoader(),
                            "模型调用使用私有运行环境的上下文");
                    return ChatResponse.builder().aiMessage(AiMessage.from("{\"value\":\"ok\"}")).build();
                }
            };
            Service service = LlmClient.of(good, null, 30).aiService(Service.class);
            check(Thread.currentThread().getContextClassLoader() == caller, "创建服务后恢复上下文");
            check(service.answer("test").value().equals("ok"), "直接转换为记录类型");
            check(Thread.currentThread().getContextClassLoader() == caller, "调用成功后恢复上下文");

            ChatModel failing = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    throw new IllegalStateException("拒绝 offline-secret");
                }
            };
            Service bad = new DefaultLlmClient(failing, null, 30, prompt::redact).aiService(Service.class);
            try { bad.answer("test"); throw new AssertionError("预期模型调用失败"); }
            catch (IllegalStateException expected) {
                check(!expected.getMessage().contains("offline-secret") && expected.getCause() == null,
                        "类型化服务错误应脱敏并移除原始异常链");
            }
            check(Thread.currentThread().getContextClassLoader() == caller, "调用失败后恢复上下文");
            Thread.currentThread().interrupt();
            try { service.answer("test"); throw new AssertionError("预期操作被取消"); }
            catch (CancellationException expected) {}
            finally { Thread.interrupted(); }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        System.out.println("检查通过：类型化服务记录输出、结构配置、类加载器恢复、脱敏与取消");
    }

    private static void emptyResponses() {
        ChatResponse thinkingOnly = ChatResponse.builder()
                .aiMessage(AiMessage.builder().thinking("私密思考").build())
                .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP)
                        .tokenUsage(new TokenUsage(100, 4096)).build()).build();
        ChatResponse valid = ChatResponse.builder().aiMessage(AiMessage.from("{\"value\":\"ok\"}")).build();
        SequenceModel transientEmpty = new SequenceModel(thinkingOnly, valid);
        check(LlmClient.of(transientEmpty, null, 30).aiService(Service.class).answer("test").value().equals("ok")
                && transientEmpty.calls == 2, "在解析普通 Java 对象前重试空响应");

        for (ChatResponse empty : new ChatResponse[]{null, thinkingOnly}) {
            SequenceModel alwaysEmpty = new SequenceModel(empty, empty);
            try {
                LlmClient.of(alwaysEmpty, null, 30).aiService(Service.class).answer("test");
                throw new AssertionError("错误地接受了空输出");
            } catch (IllegalStateException expected) {
                check(alwaysEmpty.calls == 2 && expected.getMessage().contains("已重试一次")
                                && !expected.getMessage().contains("Failed to parse null") // 第三方解析器错误原文，用于验证该错误未泄漏。
                                && !expected.getMessage().contains("私密思考"),
                        "有限重试并给出安全诊断，避免空值解析错误");
                if (empty != null) check(expected.getMessage().contains("outputTokens=4096")
                        && expected.getMessage().contains("thinkingPresent=true"), "报告可安全输出的响应元数据");
            }
        }
        ChatResponse exhausted = thinkingOnly.toBuilder()
                .metadata(thinkingOnly.metadata().toBuilder().finishReason(FinishReason.LENGTH).build()).build();
        SequenceModel length = new SequenceModel(exhausted);
        try {
            LlmClient.of(length, null, 30).aiService(Service.class).answer("test");
            throw new AssertionError("错误地接受了预算耗尽的响应");
        } catch (IllegalStateException expected) {
            check(length.calls == 1 && expected.getMessage().contains("finishReason=LENGTH"),
                    "不使用已耗尽的同一预算重试");
        }
        SequenceModel cancelled = new SequenceModel(thinkingOnly, valid) {
            @Override public ChatResponse chat(ChatRequest request) {
                Thread.currentThread().interrupt(); return super.chat(request);
            }
        };
        try {
            LlmClient.of(cancelled, null, 30).aiService(Service.class).answer("test");
            throw new AssertionError("已取消的重试仍继续执行");
        } catch (CancellationException expected) {
            check(cancelled.calls == 1, "取消操作阻止重试");
        } finally { Thread.interrupted(); }

        SequenceModel raw = new SequenceModel(thinkingOnly);
        ChatRequest request = ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("test")).build();
        check(LlmClient.of(raw, null, 30).chat(request) == thinkingOnly && raw.calls == 1,
                "普通聊天行为保持不变");
        ChatResponse tools = ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id("1").name("test").arguments("{}").build())).build();
        SequenceModel toolModel = new SequenceModel(tools);
        check(new AiServiceResponseModel(toolModel).chat(request) == tools && toolModel.calls == 1,
                "仅含工具请求的响应不重试，也不按空文本解析");
        System.out.println("检查通过：空输出、仅思考响应、有限重试、输出上限诊断、取消及原始聊天和工具响应");
    }

    private static void usageMetrics() throws Exception {
        var root = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("llm-client/target"), "usage-check-");
        UsageMetrics.configure(root.resolve("config/agent.properties").toUri().toString(), "fleet");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"model\":\"offline\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"私密回复\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"total_tokens\":1010,\"prompt_cache_hit_tokens\":900,\"prompt_cache_miss_tokens\":100}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var properties = new Properties(); properties.setProperty("apiKey", "private-secret"); properties.setProperty("modelName", "offline");
            properties.setProperty("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            try (var scope = UsageMetrics.scope("periodic")) { LlmClient.create(LlmConfig.from(properties)).chat("私密提示词"); }
            var totals = (java.util.Map<?, ?>) UsageMetrics.snapshot().get("periodic");
            check(totals.get("knownInputTokens").equals(1000L) && totals.get("knownCacheHitTokens").equals(900L)
                    && totals.get("knownCacheMissTokens").equals(100L), "真实 SDK 保留 DeepSeek 原始缓存用量");
            var response = ChatResponse.builder().aiMessage(AiMessage.from("私密流式回复"))
                    .tokenUsage(dev.langchain4j.model.openai.OpenAiTokenUsage.builder().inputTokenCount(200).outputTokenCount(5)
                            .inputTokensDetails(dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(100).build()).build()).build();
            dev.langchain4j.model.chat.StreamingChatModel stream = new dev.langchain4j.model.chat.StreamingChatModel() {
                @Override public void chat(ChatRequest request, dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) { handler.onCompleteResponse(response); }
            };
            try (var scope = UsageMetrics.scope("notification")) {
                LlmClient.of(new SequenceModel(), stream, 5).stream(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("私密提示词")).build(), LlmStreamListener.NONE);
            }
            var streamed = (java.util.Map<?, ?>) UsageMetrics.snapshot().get("notification");
            check(streamed.get("calls").equals(1L) && streamed.get("knownCacheHitTokens").equals(100L), "流式调用只计数一次，并采用标准缓存用量字段");
            try (var scope = UsageMetrics.scope("review")) { LlmClient.of(new SequenceModel(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build()), null, 5).chat("私密提示词"); }
            var unknown = (java.util.Map<?, ?>) UsageMetrics.snapshot().get("review");
            check(unknown.get("calls").equals(1L) && unknown.get("usageReportedCalls").equals(0L) && unknown.get("cacheReportedCalls").equals(0L), "未报告用量时保持未知");
            UsageMetrics.event("staleResult");
            String log = java.nio.file.Files.readString(root.resolve("diagnostics/usage-fleet.jsonl"));
            check(!log.contains("private") && !log.contains("私密") && log.contains("staleResult") && log.contains("\"cacheHitTokens\":null"), "只写入元数据；缺少缓存用量时使用空值");
            System.out.println("检查通过：仅元数据统计、真实 SDK 缓存用量、流式输出、未知用量和过期事件");
        } finally { server.stop(0); }
    }

    private static class SequenceModel implements ChatModel {
        private final ChatResponse[] responses;
        int calls;
        SequenceModel(ChatResponse... responses) { this.responses = responses; }
        @Override public ChatResponse chat(ChatRequest request) {
            if (calls >= responses.length) throw new AssertionError("出现预期之外的额外模型请求");
            return responses[calls++];
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
