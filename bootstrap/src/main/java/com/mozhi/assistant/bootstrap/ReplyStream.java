package com.mozhi.assistant.bootstrap;

import com.mozhi.assistant.bridge.AgentStreamListener;

/** 网络线程写入、游戏主线程读取；合并片段，避免每个 token 排入一个 UI 任务。 */
final class ReplyStream implements AgentStreamListener {
    record Update(String text, String status) {
    }

    private final StringBuilder text = new StringBuilder();
    private String status = "墨汁正在思考……";
    private boolean dirty;
    private boolean closed;

    @Override
    public synchronized void onResponseStart() {
        if (closed) {
            return;
        }
        text.setLength(0);
        status = "墨汁正在思考……";
        dirty = true;
    }

    @Override
    public synchronized void onPartialText(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        text.append(delta);
        status = "墨汁正在回复……";
        dirty = true;
    }

    @Override
    public synchronized void onStatus(String value) {
        if (!closed) {
            status = value;
            dirty = true;
        }
    }

    synchronized Update drain() {
        if (!dirty || closed) {
            return null;
        }
        dirty = false;
        return new Update(text.toString(), status);
    }

    synchronized void close() {
        closed = true;
        dirty = false;
        text.setLength(0);
    }
}
