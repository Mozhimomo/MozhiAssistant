package com.mozhi.assistant.bootstrap;

import com.mozhi.assistant.bridge.AgentBridge;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Main thread owns display state; a single daemon worker owns the agent and its memory. */
public final class AgentSession {
    private static AgentSession current;
    private final GameThreadQueries gameQueries = new GameThreadQueries();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Mozhi-Agent");
        thread.setDaemon(true);
        return thread;
    });
    private Future<String[]> pending;
    private AgentClassLoader loader;
    private AgentBridge agent;
    private String status = "等待发送。请先填写 data/config/agent.properties。";
    private String answer = "";
    private String details = "";
    private final List<ChatMessage> history = new ArrayList<>();
    private long nextMessageId;
    private String draft = "";
    private boolean reportToConsole;

    public record ChatMessage(long id, String speaker, String text) { }

    public static AgentSession current() {
        if (current == null) current = new AgentSession();
        return current;
    }

    public static void reset() {
        if (current != null) current.close();
        current = new AgentSession();
    }

    public boolean send(String message) {
        return send(message, true);
    }

    public boolean send(String message, boolean consoleOutput) {
        if (pending != null || message == null || message.isBlank()) return false;
        reportToConsole = consoleOutput;
        appendMessage("舰长", message);
        status = "墨汁正在思考，请稍候……";
        answer = "";
        details = "";
        pending = worker.submit(() -> {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                if (loader == null) {
                    URL bootstrapJar = AgentSession.class.getProtectionDomain().getCodeSource().getLocation();
                    loader = new AgentClassLoader(new URL(bootstrapJar, "agent-runtime.jar"),
                            AgentSession.class.getClassLoader());
                }
                // Framework SPI, HTTP client discovery, Jackson and proxy creation stay in this zone.
                Thread.currentThread().setContextClassLoader(loader);
                if (agent == null) initializeAgent();
                try {
                    String response = agent.chat(message);
                    return new String[]{response, agent.diagnostics() + "\n" + agent.toolTrace()};
                } catch (RuntimeException | LinkageError e) {
                    return new String[]{"请求失败：" + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            agent.diagnostics() + "\n" + agent.toolTrace()};
                }
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        });
        return true;
    }

    @SuppressWarnings("deprecation")
    private void initializeAgent() throws Exception {
        // Deliberately use Class.newInstance: calling getDeclaredConstructor here would
        // resolve forbidden java.lang.reflect.Constructor before reaching the private loader.
        AgentBridge candidate = (AgentBridge) loader.loadClass(
                "com.mozhi.assistant.runtime.LangChainAgent").newInstance();
        URL bootstrapJar = AgentSession.class.getProtectionDomain().getCodeSource().getLocation();
        candidate.initialize(new URL(bootstrapJar, "../data/config/agent.properties").toExternalForm(), gameQueries);
        agent = candidate;
    }

    /** Called only from the game's main thread; never blocks waiting for a network response. */
    public boolean poll() {
        gameQueries.advance();
        if (pending == null || !pending.isDone()) return false;
        try {
            String[] result = pending.get();
            answer = result[0];
            details = result[1];
            status = answer.startsWith("请求失败：") ? "请求失败，可重试或重置会话。" : "已收到回复。";
            appendMessage(answer.startsWith("请求失败：") ? "系统" : "墨汁", answer);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            status = "初始化失败：" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            appendMessage("系统", status);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            pending = null;
        }
        return true;
    }

    public String status() { return status; }
    public String answer() { return answer; }
    public String details() { return details; }
    public boolean busy() { return pending != null; }
    public boolean reportsToConsole() { return reportToConsole; }
    public List<ChatMessage> history() { return List.copyOf(history); }
    public String draft() { return draft; }
    public void setDraft(String value) { draft = value; }

    private void appendMessage(String speaker, String text) {
        history.add(new ChatMessage(++nextMessageId, speaker, text));
        if (history.size() > 100) history.remove(0);
    }

    public String resultText() {
        return "[MozhiAgent] " + status
                + (answer.isEmpty() ? "" : "\n回复：\n" + answer)
                + (details.isEmpty() ? "" : "\n加载器与工具调用记录：\n" + details);
    }

    private void close() {
        gameQueries.close();
        if (pending != null) pending.cancel(true);
        // Queue cleanup AFTER the active call: never close a jar while framework code is loading.
        worker.execute(() -> {
            agent = null;
            if (loader != null) {
                try { loader.close(); } catch (java.io.IOException ignored) { }
                loader = null;
            }
        });
        worker.shutdown();
    }
}
