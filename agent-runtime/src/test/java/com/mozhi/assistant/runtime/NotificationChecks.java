package com.mozhi.assistant.runtime;

import com.mozhi.assistant.bridge.AgentStreamListener;
import com.mozhi.assistant.runtime.tools.ProfileMemoryTools;
import com.mozhi.llm.LlmClient;
import com.mozhi.llm.LlmStreamListener;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises the real notification turn using an offline model, including attempted tool calls. */
public final class NotificationChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "notification-");
        Path config = root.resolve("agent.properties");
        Files.writeString(config, "apiKey=offline-test-key\nbaseUrl=http://localhost/v1/\nmodelName=offline-test-model\n");
        ProfileStore store = new ProfileStore(root.resolve("profile.json"));
        ReActLoop agent = new ReActLoop(store, new ProfileMemoryTools(store));
        FakeModel model = new FakeModel();
        set(agent, "config", AgentConfig.load(config.toUri().toString()));
        set(agent, "llmClient", model); set(agent, "summaryClient", model); set(agent, "tools", List.of());
        agent.chat("购买 100 补给");
        model.notification = true;
        String notice = agent.notifyFleetIntervention("目标：购买 100 补给\n异常原因：库存不足", AgentStreamListener.NONE);
        require(notice.equals("舰长，库存不足，需要调整数量。"), "notification uses model output");
        require(model.request.messages().toString().contains("购买 100 补给"), "notification keeps conversation context");
        model.notification = false;
        agent.chat("那就买 50");
        require(model.request.messages().stream().anyMatch(message -> message instanceof AiMessage ai && notice.equals(ai.text())), "player follow-up retains generated notice");
        model.notification = true; model.attemptTool = true;
        boolean rejected = false;
        try { agent.notifyFleetIntervention("库存不足", AgentStreamListener.NONE); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected && !Files.exists(store.path()), "unsolicited memory tool call must not execute");
        require(agent.toolTrace().contains("没有执行工具"), "notification cannot perform tool operations");
        Files.delete(config); Files.delete(root);
        System.out.println("Notification runtime checks passed: shared history, no tools, model response delivered");
    }

    private static void set(ReActLoop agent, String name, Object value) throws Exception {
        var field = ReActLoop.class.getDeclaredField(name); field.setAccessible(true); field.set(agent, value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class FakeModel implements LlmClient {
        boolean notification, attemptTool;
        ChatRequest request;
        public ChatResponse stream(ChatRequest request, LlmStreamListener listener) {
            this.request = request;
            if (notification) require(request.toolSpecifications() == null || request.toolSpecifications().isEmpty(), "notification must offer no tools");
            AiMessage response = attemptTool
                    ? AiMessage.from(ToolExecutionRequest.builder().id("unauthorized").name("rememberFact").arguments("{\"content\":\"bad\",\"category\":\"test\"}").build())
                    : AiMessage.from(notification ? "舰长，库存不足，需要调整数量。" : "收到。");
            return ChatResponse.builder().aiMessage(response).build();
        }
        public ChatResponse chat(ChatRequest request) { throw new AssertionError("unexpected compression request"); }
        public <T> T aiService(Class<T> type) { throw new AssertionError("unexpected AI service request"); }
    }
}
