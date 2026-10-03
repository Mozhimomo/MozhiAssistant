package com.mozhi.llm;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Set;
import java.util.concurrent.CancellationException;

/** 在 AI Services 反序列化之前检查正文，避免把 thinking-only 响应当作 JSON 解析。 */
final class AiServiceResponseModel implements ChatModel {
    private final ChatModel delegate;

    AiServiceResponseModel(ChatModel delegate) { this.delegate = delegate; }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // 只重试未产出正文的生成请求，不重放任何工具或游戏动作。
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("请求已取消");
            ChatResponse response = delegate.chat(request);
            if (response != null && response.finishReason() == FinishReason.LENGTH) {
                throw new IllegalStateException("模型达到输出上限，未完成结构化回答；"
                        + "请增加 maxTokens/maxCompletionTokens 或降低思考预算。" + diagnostic(response));
            }
            if (hasAnswer(response)) return response;
            if (attempt == 1) {
                throw new IllegalStateException("模型没有返回文本正文（已重试一次），无法解析结构化结果。"
                        + diagnostic(response));
            }
        }
        throw new AssertionError("unreachable");
    }

    private static boolean hasAnswer(ChatResponse response) {
        if (response == null || response.aiMessage() == null) return false;
        // AI Services 若配置工具，需要先把工具请求交回框架；普通 chat/stream 不经过本包装。
        return response.aiMessage().hasToolExecutionRequests()
                || response.aiMessage().text() != null && !response.aiMessage().text().isBlank();
    }

    private static String diagnostic(ChatResponse response) {
        if (response == null) return " response=null";
        TokenUsage usage = response.tokenUsage();
        boolean thinking = response.aiMessage() != null && response.aiMessage().thinking() != null
                && !response.aiMessage().thinking().isBlank();
        // 仅记录元数据，不包含提示词、思考内容、正文或原始提供商响应。
        return " finishReason=" + response.finishReason()
                + ", inputTokens=" + (usage == null ? null : usage.inputTokenCount())
                + ", outputTokens=" + (usage == null ? null : usage.outputTokenCount())
                + ", thinkingPresent=" + thinking;
    }

    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
}
