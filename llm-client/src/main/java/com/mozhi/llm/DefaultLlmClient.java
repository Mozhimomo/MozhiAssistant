package com.mozhi.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** 所有提供商调用集中于此；不执行工具，也不维护历史。 */
final class DefaultLlmClient implements LlmClient {
    private final ChatModel model;
    private final StreamingChatModel streamingModel;
    private final int timeoutSeconds;
    private final UnaryOperator<String> redact;

    DefaultLlmClient(ChatModel model, StreamingChatModel streamingModel,
                     int timeoutSeconds, UnaryOperator<String> redact) {
        this.model = Objects.requireNonNull(model, "model");
        this.streamingModel = streamingModel;
        if (timeoutSeconds < 1) throw new IllegalArgumentException("timeoutSeconds 必须为正数");
        this.timeoutSeconds = timeoutSeconds;
        this.redact = redact;
    }

    static LlmClient create(LlmConfig config) {
        Objects.requireNonNull(config, "config");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(DefaultLlmClient.class.getClassLoader());
            return new DefaultLlmClient(ModelFactory.createChatModel(config),
                    config.streamingEnabled ? ModelFactory.createStreamingChatModel(config) : null,
                    config.timeoutSeconds(), config::redact);
        } catch (RuntimeException exception) {
            throw sanitized(exception, config::redact);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return invoke(() -> model.chat(request));
    }

    @Override
    public ChatResponse stream(ChatRequest request, LlmStreamListener listener) {
        Objects.requireNonNull(request, "request");
        LlmStreamListener sink = listener == null ? LlmStreamListener.NONE : listener;
        return invoke(() -> {
            sink.onResponseStart();
            if (streamingModel != null) {
                return StreamingModelCall.execute(streamingModel, request, sink, timeoutSeconds);
            }
            ChatResponse response = model.chat(request);
            if (response != null && response.aiMessage() != null && response.aiMessage().text() != null) {
                sink.onPartialText(response.aiMessage().text());
            }
            return response;
        });
    }

    private ChatResponse invoke(Supplier<ChatResponse> operation) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("请求已取消");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(DefaultLlmClient.class.getClassLoader());
            ChatResponse response = operation.get();
            if (response == null) throw new IllegalStateException("模型返回空响应");
            return response;
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 不附带可能包含原始请求/密钥的异常链。
            throw sanitized(exception, redact);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static IllegalStateException sanitized(RuntimeException exception, UnaryOperator<String> redact) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        return new IllegalStateException(redact.apply(message));
    }
}
