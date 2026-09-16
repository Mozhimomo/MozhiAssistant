package com.mozhi.assistant.bootstrap;

import com.mozhi.assistant.bridge.GameThreadAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Queues code, not precomputed game data. Polled by the campaign script and chat dialog. */
final class GameThreadQueries implements GameThreadAccess {
    private final Thread owner = Thread.currentThread();
    private final ConcurrentLinkedQueue<Query> requests = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private record Query(Supplier<String> action, CompletableFuture<String> result) { }

    @Override
    public String call(Supplier<String> action) {
        if (closed) throw new IllegalStateException("会话已结束。");
        if (Thread.currentThread() == owner) return action.get();
        Query query = new Query(action, new CompletableFuture<>());
        requests.add(query);
        try {
            if (closed) throw new IllegalStateException("会话已结束。");
            return query.result().get(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("游戏操作等待已取消；若操作已经开始，请检查游戏状态。");
        } catch (TimeoutException e) {
            throw new IllegalStateException("等待游戏主线程超时，请返回战役或打开聊天窗口；重试前请检查游戏状态。");
        } catch (ExecutionException e) {
            throw new IllegalStateException("游戏操作失败。", e.getCause());
        } finally {
            query.result().cancel(false);
            requests.remove(query);
        }
    }

    void advance() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("必须在游戏主线程执行。");
        Query query;
        while ((query = requests.poll()) != null) {
            if (query.result().isDone()) continue;
            if (closed) {
                query.result().completeExceptionally(new IllegalStateException("会话已结束。"));
                continue;
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(query.action().getClass().getClassLoader());
                query.result().complete(query.action().get());
            } catch (RuntimeException | LinkageError e) {
                query.result().completeExceptionally(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    void close() {
        closed = true;
        Query query;
        while ((query = requests.poll()) != null) {
            query.result().completeExceptionally(new IllegalStateException("会话已结束。"));
        }
    }
}
