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

/** 使用离线模型验证真实通知轮次，包括模型尝试调用工具的情况。 */
public final class NotificationChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "notification-");
        Path config = root.resolve("agent.properties");
        Files.writeString(config, "\uFEFFsystemPrompt=中文测试提示词\napiKey=offline-test-key\nbaseUrl=http://localhost/v1/\nmodelName=offline-test-model\n");
        require(AgentConfig.load(config.toUri().toString()).systemPrompt.equals("中文测试提示词"), "对话配置正确读取编码标记后的首项与中文值");
        ProfileStore store = new ProfileStore(root.resolve("profile.json"));
        ReActLoop agent = new ReActLoop(store, new ProfileMemoryTools(store));
        FakeModel model = new FakeModel();
        set(agent, "config", AgentConfig.load(config.toUri().toString()));
        set(agent, "llmClient", model); set(agent, "summaryClient", model); set(agent, "cheapClient", model); set(agent, "tools", List.of());
        agent.chat("旧上下文标记，不应带入通知");
        agent.chat("购买 100 补给");
        model.notification = true;
        String notice = agent.notifyFleetIntervention("目标：购买 100 补给\n异常原因：库存不足", AgentStreamListener.NONE);
        require(notice.equals("舰长，库存不足，需要调整数量。"), "通知使用模型输出");
        require(model.request.messages().toString().contains("购买 100 补给") && !model.request.messages().toString().contains("旧上下文标记"), "通知接收事件事实，不携带旧对话");
        model.notification = false;
        agent.chat("那就买 50");
        require(model.request.messages().toString().contains("旧上下文标记"), "通知不得清除已有对话");
        require(model.request.messages().stream().anyMatch(message -> message instanceof AiMessage ai && notice.equals(ai.text())), "玩家追问时保留已生成的通知");
        model.notification = true; model.completed = true;
        String completed = agent.notifyFleetIntervention("事件：任务完成，等待玩家决定是否返航\n验收结论：舰队拥有100万星币", AgentStreamListener.NONE);
        require(completed.contains("是否返航") && model.request.messages().toString().contains("不把任务成功说成异常"),
                "完成通知遵守提示词要求，询问返航权限");
        model.notification = false;
        agent.chat("先别回来");
        require(model.request.messages().stream().anyMatch(message -> message instanceof AiMessage ai && completed.equals(ai.text())),
                "返航询问保留在对话中，等待玩家决定");
        model.notification = true; model.attemptTool = true;
        boolean rejected = false;
        try { agent.notifyFleetIntervention("库存不足", AgentStreamListener.NONE); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected && !Files.exists(store.path()), "未经请求的记忆工具调用不得执行");
        require(agent.toolTrace().contains("没有执行工具"), "通知不能执行工具操作");
        Files.delete(config); Files.delete(root);
        System.out.println("通知运行检查通过：共享历史、禁用工具、投递模型回复");
    }

    private static void set(ReActLoop agent, String name, Object value) throws Exception {
        var field = ReActLoop.class.getDeclaredField(name); field.setAccessible(true); field.set(agent, value);
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class FakeModel implements LlmClient {
        boolean notification, attemptTool, completed;
        ChatRequest request;
        public ChatResponse stream(ChatRequest request, LlmStreamListener listener) {
            this.request = request;
            if (notification) require(request.toolSpecifications() == null || request.toolSpecifications().isEmpty(), "通知请求不得提供工具");
            AiMessage response = attemptTool
                    ? AiMessage.from(ToolExecutionRequest.builder().id("unauthorized").name("rememberFact").arguments("{\"content\":\"bad\",\"category\":\"test\"}").build())
                    : AiMessage.from(notification ? completed ? "舰长，100万目标已达成，是否返航？" : "舰长，库存不足，需要调整数量。" : "收到。");
            return ChatResponse.builder().aiMessage(response).build();
        }
        public ChatResponse chat(ChatRequest request) { throw new AssertionError("出现预期之外的压缩请求"); }
        public <T> T aiService(Class<T> type) { throw new AssertionError("出现预期之外的 AI Service 请求"); }
    }
}
