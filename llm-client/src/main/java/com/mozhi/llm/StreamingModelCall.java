package com.mozhi.llm;

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

import java.io.EOFException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将异步流式 HTTP 响应接回调用线程。
 * 回调只发布文本，工具仍在完整 ChatResponse 到达后交给调用方处理。
 */
final class StreamingModelCall implements StreamingChatResponseHandler {
    private final LlmStreamListener listener;
    private final CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
    private volatile StreamingHandle handle;
    private volatile boolean stopped;
    private volatile boolean publishedText;

    private StreamingModelCall(LlmStreamListener listener) {
        this.listener = listener;
    }

    static ChatResponse execute(
            StreamingChatModel model, ChatRequest request,
            LlmStreamListener listener, int timeoutSeconds) {
        for (int attempt = 0; ; attempt++) {
            checkInterrupted();
            StreamingModelCall call = new StreamingModelCall(listener);
            try {
                return call.awaitResponse(model, request, timeoutSeconds);
            } catch (RuntimeException exception) {
                // 只重发当前模型请求，完整响应到达前工具尚未执行；不重跑整个 ReAct 轮次。
                if (attempt != 0 || call.publishedText || !isTransientConnectionFailure(exception)) {
                    throw exception;
                }
                checkInterrupted();
                listener.onStatus("连接中断，正在重新连接（1/1）……");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("流式请求已取消");
                }
            }
        }
    }

    private ChatResponse awaitResponse(StreamingChatModel model, ChatRequest request, int timeoutSeconds) {
        try {
            model.chat(request, this);
            return completed.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("流式请求已取消");
        } catch (TimeoutException exception) {
            throw new IllegalStateException("流式回复超时；已显示的内容可能不完整。");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalStateException("流式请求失败：" + cause.getMessage(), cause);
        } finally {
            stop();
        }
    }

    @Override
    public synchronized void onPartialResponse(String text) {
        if (!stopped && !completed.isDone() && text != null && !text.isEmpty()) {
            publishedText = true;
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

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("流式请求已取消");
    }

    private static boolean isTransientConnectionFailure(Throwable exception) {
        // 不重试鉴权、参数校验等错误，也不重试已取消的请求。
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException || cause instanceof InterruptedException) {
                return false;
            }
            if (cause instanceof SocketException || cause instanceof EOFException
                    || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void captureHandle(StreamingHandle value) {
        handle = value;
        // 在首个片段到达之前被取消，也不能让该流继续输出。
        if (stopped && value != null) {
            value.cancel();
        }
    }

    private void stop() {
        StreamingHandle current;
        synchronized (this) {
            // 与发布文本互斥；退出后旧请求不再向监听器写入。
            stopped = true;
            current = handle;
        }
        if (current != null && (!completed.isDone() || completed.isCompletedExceptionally())) {
            current.cancel();
        }
    }
}
