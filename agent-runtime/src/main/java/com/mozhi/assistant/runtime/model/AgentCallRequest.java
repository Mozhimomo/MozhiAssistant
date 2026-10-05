package com.mozhi.assistant.runtime.model;

import dev.langchain4j.data.message.ChatMessage;
import com.mozhi.llm.LlmClient;
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
    private LlmClient llmClient;
    private LlmClient summaryClient;

    @Builder.Default
    private AgentStreamListener streamListener = AgentStreamListener.NONE;

    @Builder.Default
    private List<Object> tools = new ArrayList<>();

    private ToolProvider toolProvider;
    private boolean progressiveTools;
    private boolean notification;

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

    /** 摘要提示词中的目标 UTF-8 字节数，仅作引导；完整保留模型返回正文。 */
    @Builder.Default
    private int compressionSummaryTokens = 2048;

    /** 摘要请求生成预算包含服务端思考，独立于最终保存的摘要长度。 */
    @Builder.Default
    private int summaryMaxOutputTokens = 8192;
}
