package com.mozhi.llm;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 无会话状态的 LLM 调用入口。历史和工具定义由调用方在 ChatRequest 中传入。
 * 所有方法等待完整响应；stream 在等待期间发布增量，因此也应在后台线程调用。
 */
public interface LlmClient {
    static LlmClient create(LlmConfig config) {
        return DefaultLlmClient.create(config);
    }

    /** 用于自定义提供商或离线验证；调用方负责自建模型中的配置和错误脱敏。 */
    static LlmClient of(ChatModel model, StreamingChatModel streamingModel, int timeoutSeconds) {
        return new DefaultLlmClient(model, streamingModel, timeoutSeconds, text -> text);
    }

    ChatResponse chat(ChatRequest request);

    /** streamingEnabled=false 或未提供流式模型时，同步调用后发布完整正文。 */
    ChatResponse stream(ChatRequest request, LlmStreamListener listener);

    default String chat(String prompt) {
        ChatResponse response = chat(ChatRequest.builder().messages(UserMessage.from(prompt)).build());
        if (response.aiMessage() == null || response.aiMessage().text() == null) {
            throw new IllegalStateException("模型没有返回文本正文");
        }
        return response.aiMessage().text();
    }
}
