package com.mozhi.assistant.runtime;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.Map;

/** 将配置转换为模型实例；对话和摘要独立设置思考模式、思考强度与输出预算。 */
final class AgentModelFactory {
    private AgentModelFactory() {
    }

    static ChatModel createChatModel(AgentConfig config) {
        int outputLimit = config.contextReserveTokens;
        if (config.maxCompletionTokens != null) {
            outputLimit = config.maxCompletionTokens;
        } else if (config.maxTokens != null) {
            outputLimit = config.maxTokens;
        }
        return createModel(config, outputLimit, config.thinkingMode, config.reasoningEffort);
    }

    static StreamingChatModel createStreamingChatModel(AgentConfig config) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl)
                .apiKey(config.apiKey)
                .modelName(config.modelName)
                .timeout(config.timeout)
                .logRequests(false)
                .logResponses(false);
        if (config.maxCompletionTokens != null) {
            builder.maxCompletionTokens(config.maxCompletionTokens);
        } else {
            builder.maxTokens(config.maxTokens != null ? config.maxTokens : config.contextReserveTokens);
        }
        if (config.temperature != null) {
            builder.temperature(config.temperature);
        }
        if (config.topP != null) {
            builder.topP(config.topP);
        }
        if (config.presencePenalty != null) {
            builder.presencePenalty(config.presencePenalty);
        }
        if (config.frequencyPenalty != null) {
            builder.frequencyPenalty(config.frequencyPenalty);
        }
        if (config.seed != null) {
            builder.seed(config.seed);
        }
        if (config.thinkingMode != null) {
            builder.customParameters(thinkingParameters(config.thinkingMode));
        }
        if (config.reasoningEffort != null) builder.reasoningEffort(config.reasoningEffort);
        boolean exchangeThinking = config.exchangeThinking(config.thinkingMode, config.reasoningEffort);
        builder.returnThinking(exchangeThinking).sendThinking(exchangeThinking, "reasoning_content");
        // 流已开始后不自动重试，避免重复文本或重复工具请求。
        return builder.build();
    }

    static ChatModel createSummaryModel(AgentConfig config) {
        return createModel(config, config.compressionSummaryTokens,
                config.summaryThinkingMode, config.summaryReasoningEffort);
    }

    private static ChatModel createModel(AgentConfig config, int outputLimit, String mode, String effort) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl)
                .apiKey(config.apiKey)
                .modelName(config.modelName)
                .timeout(config.timeout)
                .maxRetries(config.maxRetries)
                .logRequests(false)
                .logResponses(false);

        // 部分模型只接受其中一种输出参数，不能同时发送。
        if (config.maxCompletionTokens != null) {
            builder.maxCompletionTokens(outputLimit);
        } else {
            builder.maxTokens(outputLimit);
        }
        if (mode != null) builder.customParameters(thinkingParameters(mode));
        if (effort != null) builder.reasoningEffort(effort);
        boolean exchangeThinking = config.exchangeThinking(mode, effort);
        builder.returnThinking(exchangeThinking).sendThinking(exchangeThinking, "reasoning_content");
        applyOptionalParameters(builder, config);
        return builder.build();
    }

    /** DeepSeek 使用请求体顶层 thinking 对象；不要包在 extra_body 中发送。 */
    private static Map<String, Object> thinkingParameters(String mode) {
        return Map.of("thinking", Map.of("type", mode));
    }

    private static void applyOptionalParameters(
            OpenAiChatModel.OpenAiChatModelBuilder builder, AgentConfig config) {
        if (config.temperature != null) {
            builder.temperature(config.temperature);
        }
        if (config.topP != null) {
            builder.topP(config.topP);
        }
        if (config.presencePenalty != null) {
            builder.presencePenalty(config.presencePenalty);
        }
        if (config.frequencyPenalty != null) {
            builder.frequencyPenalty(config.frequencyPenalty);
        }
        if (config.seed != null) {
            builder.seed(config.seed);
        }
    }
}
