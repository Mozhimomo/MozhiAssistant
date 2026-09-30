package com.mozhi.assistant.runtime.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 调用响应：描述"这一次"调用的结果
 * <p>
 * 与 {@link AgentCallRequest} 对称，承载本次调用的输出、工具调用记录、用量与状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class AgentCallResponse {

    // ===== 追踪（与请求对应） =====

    /** 请求 ID，由每轮执行生成 */
    private String requestId;

    /** 会话 ID，对应请求的 agentId */
    private String sessionId;

    // ===== 结果输出 =====

    /** 最终回复文本（面向用户的答案） */
    private String output;

    /** 压缩后保留的历史（含成对的 assistant/tool 消息，不含 system 消息） */
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    /** 本次触发的工具调用记录 */
    @Builder.Default
    private List<ToolExecution> toolCalls = new ArrayList<>();

    // ===== 状态 =====

    /** 是否调用成功 */
    private boolean success;

    /** 结束原因：stop / length / tool_calls / error 等 */
    private String finishReason;

    /** 错误码（失败时） */
    private String errorCode;

    /** 错误信息（失败时） */
    private String errorMessage;

    // ===== 用量与耗时 =====

    /** token 用量 */
    private TokenUsage usage;

    /** 被压缩的旧轮次摘要，仅保存在内存。 */
    @Builder.Default
    private String contextSummary = "";
    /** 本次请求完成的压缩次数。 */
    private int compressionCount;

    /** 为 true 时，调用方应使用 messages 和 contextSummary 更新会话。 */
    private boolean historyUpdated;

    /** 总耗时（毫秒） */
    private Long latencyMs;

    // ===== 便捷工厂 =====

    public static AgentCallResponse ok(String output) {
        return AgentCallResponse.builder()
                .output(output)
                .success(true)
                .finishReason("stop")
                .build();
    }

    public static AgentCallResponse fail(String errorCode, String errorMessage) {
        return AgentCallResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .finishReason("error")
                .build();
    }
}