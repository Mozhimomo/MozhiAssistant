package com.mozhi.assistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.assistant.bridge.AgentBridge;
import com.mozhi.assistant.bridge.AgentStreamListener;
import com.mozhi.assistant.bridge.GameThreadAccess;
import com.mozhi.assistant.runtime.model.AgentCallRequest;
import com.mozhi.assistant.runtime.model.AgentCallResponse;
import com.mozhi.assistant.runtime.tools.DemoTools;
import com.mozhi.assistant.runtime.tools.ShipTools;
import com.mozhi.assistant.runtime.tools.SpecTools;
import com.mozhi.assistant.runtime.tools.NavigationTools;
import com.mozhi.assistant.runtime.tools.ProfileMemoryTools;
import com.mozhi.assistant.runtime.tools.FleetCommandTools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import com.mozhi.llm.LlmClient;
import dev.langchain4j.service.tool.ToolExecution;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 游戏加载的 Agent 入口，负责初始化和会话生命周期。
 * 单次推理委托给 ReActTurn；所有可变状态都由 AgentSession 的单个工作线程访问。
 */
public final class ReActLoop implements AgentBridge {
    // 会话状态只保存在内存中，不写入用户画像或游戏存档。
    private final String sessionId = UUID.randomUUID().toString();
    private List<ChatMessage> history = new ArrayList<>();
    private String summary = "";
    private AgentCallResponse lastResponse;

    // 初始化后复用的服务。
    private AgentConfig config;
    private LlmClient llmClient;
    private LlmClient summaryClient;
    private List<Object> tools;
    private ProfileStore profiles;
    private ProfileMemoryTools memoryTools;

    /** bootstrap 通过公开无参构造器加载入口，然后调用 initialize。 */
    public ReActLoop() {
    }

    /** 使用调用方提供的模型和历史执行 run 时，可直接注入记忆服务。 */
    public ReActLoop(ProfileStore profiles, ProfileMemoryTools memoryTools) {
        this.profiles = profiles;
        this.memoryTools = memoryTools;
    }

    @Override
    public void initialize(String configUrl, GameThreadAccess gameThread) throws Exception {
        config = AgentConfig.load(configUrl);
        try {
            Path configFile = Path.of(URI.create(configUrl));
            profiles = new ProfileStore(configFile.getParent().resolve(config.longTermMemoryFile));
            // 画像损坏时明确报错，不能用空画像覆盖原文件。
            profiles.read();

            llmClient = LlmClient.create(config.llm);
            summaryClient = LlmClient.create(config.llm.withGeneration(config.summaryMaxOutputTokens,
                    config.summaryThinkingMode, config.summaryReasoningEffort));
            tools = List.of(new DemoTools(gameThread), new ShipTools(gameThread), new SpecTools(gameThread), new NavigationTools(gameThread), new FleetCommandTools(gameThread));
            memoryTools = new ProfileMemoryTools(profiles);
        } catch (RuntimeException exception) {
            throw sanitized(exception);
        }
    }

    /** UI 和控制台使用此入口，自动带入并更新当前会话的历史。 */
    @Override
    public String chat(String message) {
        return chat(message, AgentStreamListener.NONE);
    }

    @Override
    public String chat(String message, AgentStreamListener listener) {
        lastResponse = null;
        try {
            lastResponse = run(createRequest(message, listener));
            updateConversation(lastResponse);
            if (!lastResponse.isSuccess()) {
                throw new IllegalStateException(
                        lastResponse.getErrorCode() + ": " + lastResponse.getErrorMessage());
            }
            return lastResponse.getOutput();
        } catch (RuntimeException exception) {
            throw sanitized(exception);
        }
    }

    /** 直接执行一个完整请求；调用方自行保存响应中的历史和摘要。 */
    public AgentCallResponse run(AgentCallRequest request) {
        return new ReActTurn(request, profiles, memoryTools).execute();
    }

    private AgentCallRequest createRequest(String message, AgentStreamListener listener) {
        return AgentCallRequest.builder()
                .agentId(sessionId)
                .name("墨汁")
                .modelName(config.llm.modelName())
                .systemPrompt(config.systemPrompt)
                .userPrompt(message)
                .llmClient(llmClient)
                .summaryClient(summaryClient)
                .streamListener(listener == null ? AgentStreamListener.NONE : listener)
                .tools(tools)
                .history(new ArrayList<>(history))
                .contextSummary(summary)
                .maxSteps(config.maxSequentialToolsInvocations + 1)
                .memoryMaxMessages(config.memoryMaxMessages)
                .contextWindowTokens(config.contextWindowTokens)
                .contextReserveTokens(config.contextReserveTokens)
                .compressionTriggerRatio(config.compressionTriggerRatio)
                .compressionKeepRecentTurns(config.compressionKeepRecentTurns)
                .compressionSummaryTokens(config.compressionSummaryTokens)
                .summaryMaxOutputTokens(config.summaryMaxOutputTokens)
                .build();
    }

    private void updateConversation(AgentCallResponse response) {
        if (response.isHistoryUpdated()) {
            history = new ArrayList<>(response.getMessages());
            summary = response.getContextSummary();
        }
    }

    @Override
    public String diagnostics() {
        ClassLoader runtime = getClass().getClassLoader();
        String details = "运行区：" + runtime.getName()
                + "\n调用路径：AgentCallRequest -> ReActLoop -> ReActTurn -> ToolInjector -> ToolRegistry -> AgentCallResponse"
                + "\nLangChain4j 位于运行区：" + (ChatModel.class.getClassLoader() == runtime)
                + "\nJackson 位于运行区：" + (ObjectMapper.class.getClassLoader() == runtime)
                + "\n桥接接口由父加载器共享：" + (AgentBridge.class.getClassLoader() != runtime)
                + "\n短期消息：" + history.size() + "；历史摘要：" + (!summary.isEmpty())
                + "\n长期记忆：" + profiles.path();
        if (lastResponse != null) {
            details += "\n本轮压缩次数：" + lastResponse.getCompressionCount()
                    + "；用量（含摘要）：" + lastResponse.getUsage();
        }
        return details;
    }

    @Override
    public String toolTrace() {
        if (lastResponse == null || lastResponse.getToolCalls().isEmpty()) {
            return "本轮没有执行工具；单纯文本回复不能证明 @Tool 调用成功。";
        }

        boolean hideMemoryDetails = "memory_updated".equals(lastResponse.getFinishReason());
        StringBuilder trace = new StringBuilder("实际工具调用记录：");
        for (ToolExecution execution : lastResponse.getToolCalls()) {
            trace.append("\n").append(execution.request().name())
                    .append(execution.hasFailed() ? " [失败]" : " [成功]");
            // 遗忘或修改记忆后，诊断信息也不再重复旧内容。
            if (!hideMemoryDetails) {
                trace.append("(").append(execution.request().arguments())
                        .append(") -> ").append(execution.result());
            }
        }
        return redact(trace.toString());
    }

    private IllegalStateException sanitized(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return new IllegalStateException(redact(message));
    }

    private String redact(String text) {
        return config == null ? text : config.llm.redact(text);
    }
}
