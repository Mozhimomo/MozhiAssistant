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

    public static void main(String[] args) {
        emptyResponses();
        Properties properties = new Properties();
        properties.setProperty("apiKey", "offline-secret");
        properties.setProperty("modelName", "offline-model");
        LlmConfig prompt = LlmConfig.from(properties);
        check(!ModelFactory.createChatModel(prompt).supportedCapabilities()
                .contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA), "Default does not require native schema");
        properties.setProperty("structuredOutputMode", "json_schema");
        LlmConfig schema = LlmConfig.from(properties).withGeneration(4096, null, null);
        check(ModelFactory.createChatModel(schema).supportedCapabilities()
                .contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA), "Derived config retains schema capability");
        properties.setProperty("structuredOutputMode", "invalid");
        try { LlmConfig.from(properties); throw new AssertionError("Invalid output mode accepted"); }
        catch (IllegalArgumentException expected) {}

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new ClassLoader(null) {};
        try {
            Thread.currentThread().setContextClassLoader(caller);
            ChatModel good = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    check(Thread.currentThread().getContextClassLoader() == DefaultLlmClient.class.getClassLoader(),
                            "Model invocation uses private runtime context");
                    return ChatResponse.builder().aiMessage(AiMessage.from("{\"value\":\"ok\"}")).build();
                }
            };
            Service service = LlmClient.of(good, null, 30).aiService(Service.class);
            check(Thread.currentThread().getContextClassLoader() == caller, "Restore context after service construction");
            check(service.answer("test").value().equals("ok"), "Convert directly to record");
            check(Thread.currentThread().getContextClassLoader() == caller, "Restore context after successful call");

            ChatModel failing = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    throw new IllegalStateException("Rejected offline-secret");
                }
            };
            Service bad = new DefaultLlmClient(failing, null, 30, prompt::redact).aiService(Service.class);
            try { bad.answer("test"); throw new AssertionError("Expected model failure"); }
            catch (IllegalStateException expected) {
                check(!expected.getMessage().contains("offline-secret") && expected.getCause() == null,
                        "Redact typed service errors and remove original exception chain");
            }
            check(Thread.currentThread().getContextClassLoader() == caller, "Restore context after failure");
            Thread.currentThread().interrupt();
            try { service.answer("test"); throw new AssertionError("Expected cancellation"); }
            catch (CancellationException expected) {}
            finally { Thread.interrupted(); }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        System.out.println("PASS: typed service record output, schema config, classloader restoration, redaction, cancellation");
    }

    private static void emptyResponses() {
        ChatResponse thinkingOnly = ChatResponse.builder()
                .aiMessage(AiMessage.builder().thinking("private reasoning").build())
                .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP)
                        .tokenUsage(new TokenUsage(100, 4096)).build()).build();
        ChatResponse valid = ChatResponse.builder().aiMessage(AiMessage.from("{\"value\":\"ok\"}")).build();
        SequenceModel transientEmpty = new SequenceModel(thinkingOnly, valid);
        check(LlmClient.of(transientEmpty, null, 30).aiService(Service.class).answer("test").value().equals("ok")
                && transientEmpty.calls == 2, "Retry empty response before POJO parsing");

        for (ChatResponse empty : new ChatResponse[]{null, thinkingOnly}) {
            SequenceModel alwaysEmpty = new SequenceModel(empty, empty);
            try {
                LlmClient.of(alwaysEmpty, null, 30).aiService(Service.class).answer("test");
                throw new AssertionError("Empty output accepted");
            } catch (IllegalStateException expected) {
                check(alwaysEmpty.calls == 2 && expected.getMessage().contains("已重试一次")
                                && !expected.getMessage().contains("Failed to parse null")
                                && !expected.getMessage().contains("private reasoning"),
                        "Bounded retries and safe diagnostics instead of null parser error");
                if (empty != null) check(expected.getMessage().contains("outputTokens=4096")
                        && expected.getMessage().contains("thinkingPresent=true"), "Report safe response metadata");
            }
        }
        ChatResponse exhausted = thinkingOnly.toBuilder()
                .metadata(thinkingOnly.metadata().toBuilder().finishReason(FinishReason.LENGTH).build()).build();
        SequenceModel length = new SequenceModel(exhausted);
        try {
            LlmClient.of(length, null, 30).aiService(Service.class).answer("test");
            throw new AssertionError("Exhausted budget accepted");
        } catch (IllegalStateException expected) {
            check(length.calls == 1 && expected.getMessage().contains("finishReason=LENGTH"),
                    "Do not retry with the same exhausted budget");
        }
        SequenceModel cancelled = new SequenceModel(thinkingOnly, valid) {
            @Override public ChatResponse chat(ChatRequest request) {
                Thread.currentThread().interrupt(); return super.chat(request);
            }
        };
        try {
            LlmClient.of(cancelled, null, 30).aiService(Service.class).answer("test");
            throw new AssertionError("Cancelled retry proceeded");
        } catch (CancellationException expected) {
            check(cancelled.calls == 1, "Cancellation prevents retry");
        } finally { Thread.interrupted(); }

        SequenceModel raw = new SequenceModel(thinkingOnly);
        ChatRequest request = ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("test")).build();
        check(LlmClient.of(raw, null, 30).chat(request) == thinkingOnly && raw.calls == 1,
                "Regular chat remains untouched");
        ChatResponse tools = ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id("1").name("test").arguments("{}").build())).build();
        SequenceModel toolModel = new SequenceModel(tools);
        check(new AiServiceResponseModel(toolModel).chat(request) == tools && toolModel.calls == 1,
                "Tool-only response is not retried or parsed as empty text");
        System.out.println("PASS: empty/thinking-only output, bounded retry, output limit diagnostics, cancellation, raw chat and tool responses");
    }

    private static class SequenceModel implements ChatModel {
        private final ChatResponse[] responses;
        int calls;
        SequenceModel(ChatResponse... responses) { this.responses = responses; }
        @Override public ChatResponse chat(ChatRequest request) {
            if (calls >= responses.length) throw new AssertionError("Unexpected extra model request");
            return responses[calls++];
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
