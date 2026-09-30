package com.mozhi.assistant.runtime;

import com.mozhi.assistant.bridge.AgentStreamListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将异步流式 HTTP 响应接回 ReAct 工作线程。
 * 回调只发布文本，工具仍在完整 ChatResponse 到达后由 ReActTurn 执行。
 */
final class StreamingModelCall implements StreamingChatResponseHandler {
    private final AgentStreamListener listener;
    private final CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
    private volatile StreamingHandle handle;
    private volatile boolean stopped;

    private StreamingModelCall(AgentStreamListener listener) {
        this.listener = listener;
    }

    static ChatResponse execute(
            StreamingChatModel model, ChatRequest request,
            AgentStreamListener listener, int timeoutSeconds) {
        StreamingModelCall call = new StreamingModelCall(listener);
        try {
            model.chat(request, call);
            return call.completed.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("流式请求已取消");
        } catch (TimeoutException exception) {
            throw new IllegalStateException("流式回复超时；已显示的内容可能不完整。");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalStateException("流式请求失败：" + cause.getMessage(), cause);
        } finally {
            call.stop();
        }
    }

    @Override
    public void onPartialResponse(String text) {
        if (!stopped) {
            listener.onPartialText(text);
        }
    }

    @Override
    public void onPartialResponse(PartialResponse response, PartialResponseContext context) {
        captureHandle(context.streamingHandle());
        onPartialResponse(response.text());
    }

    @Override
    public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
        // 思考片段不发往 UI，但仍保留取消句柄。
        captureHandle(context.streamingHandle());
    }

    @Override
    public void onPartialToolCall(PartialToolCall toolCall, PartialToolCallContext context) {
        captureHandle(context.streamingHandle());
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        if (!stopped) {
            completed.complete(response);
        }
    }

    @Override
    public void onError(Throwable error) {
        if (!stopped) {
            completed.completeExceptionally(error);
        }
    }

    private void captureHandle(StreamingHandle value) {
        handle = value;
        // 在首个片段到达之前被取消，也不能让该流继续输出。
        if (stopped && value != null) {
            value.cancel();
        }
    }

    private void stop() {
        stopped = true;
        StreamingHandle current = handle;
        if (current != null && !completed.isDone()) {
            current.cancel();
        }
    }
}
