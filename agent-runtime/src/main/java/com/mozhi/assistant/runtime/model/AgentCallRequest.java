package com.mozhi.assistant.runtime.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import com.mozhi.assistant.bridge.AgentStreamListener;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/** 一次 Agent 请求，包含当前输入与内存历史；模型和工具实例不可写入游戏存档。 */
@Data
@Builder
@Accessors(chain = true)
public class AgentCallRequest {
    // 身份与本轮输入。
    private String agentId;
    private String name;
    private String modelName;
    private String systemPrompt;
    private String userPrompt;

    // 调用所需的模型和工具。
    private ChatModel model;
    private ChatModel summaryModel;

    /** 配置此模型时使用真实流式响应；同步模型仍供摘要或兼容调用使用。 */
    private StreamingChatModel streamingModel;

    @Builder.Default
    private AgentStreamListener streamListener = AgentStreamListener.NONE;

    @Builder.Default
    private int streamTimeoutSeconds = 60;

    @Builder.Default
    private List<Object> tools = new ArrayList<>();

    private ToolProvider toolProvider;

    /** 可选的本轮生成参数覆盖。 */
    private Double temperature;
    private Integer maxTokens;

    /** 已完成的历史轮次，不包含 system 消息。 */
    @Builder.Default
    private List<ChatMessage> history = new ArrayList<>();

    /** 被压缩的旧轮次摘要，仅保存在内存。 */
    @Builder.Default
    private String contextSummary = "";

    /** 模型调用步数，包括最后一次不提供工具的回答请求。 */
    @Builder.Default
    private int maxSteps = 5;

    /** 消息数压缩触发阈值，不强行拆分当前工具调用组。 */
    @Builder.Default
    private int memoryMaxMessages = 64;

    @Builder.Default
    private int contextWindowTokens = 131072;

    @Builder.Default
    private int contextReserveTokens = 4096;

    /** 达到可用输入预算的该比例时开始压缩。 */
    @Builder.Default
    private double compressionTriggerRatio = 0.8;

    @Builder.Default
    private int compressionKeepRecentTurns = 4;

    @Builder.Default
    private int compressionSummaryTokens = 2048;
}
