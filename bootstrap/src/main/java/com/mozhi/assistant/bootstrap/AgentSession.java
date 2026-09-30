package com.mozhi.assistant.bootstrap;

import com.mozhi.assistant.bridge.AgentBridge;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 游戏主线程持有显示状态；单个后台线程持有 Agent、类加载器和模型会话。 */
public final class AgentSession {
    private static final int MAX_DISPLAY_MESSAGES = 100;
    private static final String AGENT_ENTRY_CLASS = "com.mozhi.assistant.runtime.ReActLoop";
    private static final String REQUEST_FAILURE_PREFIX = "请求失败：";

    private static AgentSession current;

    private final GameThreadQueries gameQueries = new GameThreadQueries();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Mozhi-Agent");
        thread.setDaemon(true);
        return thread;
    });
    private Future<AgentReply> pending;
    private ReplyStream replyStream;
    private long nextStreamRefreshNanos;
    private long activeReplyId;
    private String partialAnswer = "";
    private static final long STREAM_REFRESH_NANOS = 100_000_000L;

    // 只由后台线程创建、使用和释放。
    private AgentClassLoader loader;
    private AgentBridge agent;

    // 只由游戏主线程更新，供 UI 和控制台读取。
    private String status = "等待发送。请先填写 data/config/agent.properties。";
    private String answer = "";
    private String details = "";
    private final List<ChatMessage> history = new ArrayList<>();
    private long nextMessageId;
    private String draft = "";
    private boolean reportToConsole;

    public record ChatMessage(long id, String speaker, String text) {
    }

    /** 命名字段代替 String[]，避免回复与诊断信息依赖数组下标。 */
    private record AgentReply(String answer, String details) {
    }

    public static AgentSession current() {
        if (current == null) {
            current = new AgentSession();
        }
        return current;
    }

    public static void reset() {
        if (current != null) {
            current.close();
        }
        current = new AgentSession();
    }

    public boolean send(String message) {
        return send(message, true);
    }

    public boolean send(String message, boolean consoleOutput) {
        if (pending != null || message == null || message.isBlank()) {
            return false;
        }
        reportToConsole = consoleOutput;
        appendMessage("舰长", message);
        status = "墨汁正在思考，请稍候……";
        answer = "";
        details = "";
        partialAnswer = "";
        appendMessage("墨汁", "等待回复……");
        activeReplyId = nextMessageId;
        ReplyStream stream = new ReplyStream();
        replyStream = stream;
        nextStreamRefreshNanos = 0;
        pending = worker.submit(() -> executeRequest(message, stream));
        return true;
    }

    /** 框架初始化、服务发现和工具反射都必须使用私有运行区加载器。 */
    private AgentReply executeRequest(String message, ReplyStream stream) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            ensureRuntimeLoader();
            Thread.currentThread().setContextClassLoader(loader);
            if (agent == null) {
                initializeAgent();
            }
            return callAgent(message, stream);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void ensureRuntimeLoader() throws IOException {
        if (loader == null) {
            loader = new AgentClassLoader(
                    new URL(bootstrapJarUrl(), "agent-runtime.jar"),
                    AgentSession.class.getClassLoader());
        }
    }

    @SuppressWarnings("deprecation")
    private void initializeAgent() throws Exception {
        // bootstrap 不能先解析 java.lang.reflect.Constructor，因此保留 Class.newInstance。
        AgentBridge candidate = (AgentBridge) loader.loadClass(AGENT_ENTRY_CLASS).newInstance();
        String configUrl = new URL(bootstrapJarUrl(), "../data/config/agent.properties").toExternalForm();
        candidate.initialize(configUrl, gameQueries);
        agent = candidate;
    }

    private AgentReply callAgent(String message, ReplyStream stream) {
        try {
            String response = agent.chat(message, stream);
            return new AgentReply(response, agentDetails());
        } catch (RuntimeException | LinkageError exception) {
            String failure = REQUEST_FAILURE_PREFIX
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage();
            return new AgentReply(failure, agentDetails());
        }
    }

    private String agentDetails() {
        return agent.diagnostics() + "\n" + agent.toolTrace();
    }

    private static URL bootstrapJarUrl() {
        return AgentSession.class.getProtectionDomain().getCodeSource().getLocation();
    }

    /** 每帧由游戏主线程调用，不等待尚未完成的网络请求。 */
    public boolean poll() {
        gameQueries.advance();
        drainStream(pending != null && pending.isDone());
        if (pending == null || !pending.isDone()) {
            return false;
        }
        try {
            displayReply(pending.get());
        } catch (Exception exception) {
            displayInitializationFailure(exception);
        } finally {
            pending = null;
            if (replyStream != null) {
                replyStream.close();
                replyStream = null;
            }
        }
        return true;
    }

    private void displayReply(AgentReply reply) {
        answer = reply.answer();
        details = reply.details();
        boolean failed = answer.startsWith(REQUEST_FAILURE_PREFIX);
        status = failed ? "请求失败，可重试或重置会话。" : "已收到回复。";
        if (failed) {
            markIncompleteReply();
            appendMessage("系统", answer);
        } else {
            updateReplyText(answer);
        }
    }

    private void displayInitializationFailure(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        status = "初始化失败：" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        markIncompleteReply();
        appendMessage("系统", status);
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public String status() {
        return status;
    }

    public String answer() {
        return answer;
    }

    public String details() {
        return details;
    }

    public boolean busy() {
        return pending != null;
    }

    public boolean reportsToConsole() {
        return reportToConsole;
    }

    public List<ChatMessage> history() {
        return List.copyOf(history);
    }

    public String draft() {
        return draft;
    }

    public void setDraft(String value) {
        draft = value;
    }

    public String resultText() {
        return "[MozhiAgent] " + status
                + (answer.isEmpty() ? "" : "\n回复：\n" + answer)
                + (details.isEmpty() ? "" : "\n加载器与工具调用记录：\n" + details);
    }

    private void appendMessage(String speaker, String text) {
        history.add(new ChatMessage(++nextMessageId, speaker, text));
        if (history.size() > MAX_DISPLAY_MESSAGES) {
            history.remove(0);
        }
    }

    /** 仅由游戏主线程处理流式缓冲；每秒最多重排十次长文本。 */
    private void drainStream(boolean force) {
        long now = System.nanoTime();
        if (replyStream == null || (!force && now < nextStreamRefreshNanos)) {
            return;
        }
        ReplyStream.Update update = replyStream.drain();
        if (update == null) {
            return;
        }
        nextStreamRefreshNanos = now + STREAM_REFRESH_NANOS;
        partialAnswer = update.text();
        answer = partialAnswer;
        status = update.status();
        updateReplyText(partialAnswer.isEmpty() ? status : partialAnswer);
    }

    private void updateReplyText(String text) {
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if (message.id() == activeReplyId) {
                history.set(index, new ChatMessage(activeReplyId, "墨汁", text));
                return;
            }
        }
    }

    private void markIncompleteReply() {
        String text = partialAnswer.isEmpty()
                ? "未收到完整回复。"
                : partialAnswer + "\n（回复已中断，以上内容未完成。）";
        updateReplyText(text);
    }

    private void close() {
        if (replyStream != null) {
            replyStream.close();
        }
        gameQueries.close();
        if (pending != null) {
            pending.cancel(true);
        }
        // 清理任务排在当前请求之后，防止框架加载类时关闭 JAR。
        worker.execute(this::releaseRuntime);
        worker.shutdown();
    }

    private void releaseRuntime() {
        agent = null;
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // 会话已结束，释放失败不应阻止新会话创建。
            }
            loader = null;
        }
    }
}
