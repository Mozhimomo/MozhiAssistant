package com.mozhi.assistant.runtime;

import com.mozhi.llm.LlmConfig;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Locale;
import java.util.Set;

/** 读取并校验配置；字段在构造完成后不再变化。配置项名称保持向后兼容。 */
final class AgentConfig {
    private static final int MIN_INPUT_BUDGET = 2048;

    final LlmConfig llm;
    final String summaryThinkingMode;
    final String summaryReasoningEffort;

    // 角色与工具执行预算。
    final String systemPrompt;
    final int maxSequentialToolsInvocations;

    // 上下文压缩与长期记忆。
    final int memoryMaxMessages;
    final int contextWindowTokens;
    final int contextReserveTokens;
    final double compressionTriggerRatio;
    final int compressionKeepRecentTurns;
    final int compressionSummaryTokens;
    final int summaryMaxOutputTokens;
    final String longTermMemoryFile;

    private AgentConfig(Properties properties) {
        LlmConfig connection = LlmConfig.from(properties);
        summaryThinkingMode = thinkingMode(properties, "summaryThinkingMode");
        summaryReasoningEffort = reasoningEffort(properties, "summaryReasoningEffort");
        validateThinking("摘要", summaryThinkingMode, summaryReasoningEffort);
        memoryMaxMessages = integerOrDefault(properties, "memoryMaxMessages", 64, 3, 10000);
        maxSequentialToolsInvocations = integerOrDefault(properties, "maxSequentialToolsInvocations", 4, 1, 100);
        contextWindowTokens = integerOrDefault(properties, "contextWindowTokens", 131072, 4096, 2000000);

        int outputLimit = connection.outputTokens();
        int configuredReserve = integerOrDefault(properties, "contextReserveTokens", outputLimit, 1, 1000000);
        contextReserveTokens = Math.max(outputLimit, configuredReserve);
        boolean explicitOutput = !properties.getProperty("maxTokens", "").isBlank()
                || !properties.getProperty("maxCompletionTokens", "").isBlank();
        llm = explicitOutput ? connection : connection.withGeneration(contextReserveTokens,
                connection.thinkingMode(), connection.reasoningEffort());

        Double triggerRatio = optionalDecimal(properties, "compressionTriggerRatio", 0.1, 0.95);
        compressionTriggerRatio = triggerRatio == null ? 0.8 : triggerRatio;
        compressionKeepRecentTurns = integerOrDefault(properties, "compressionKeepRecentTurns", 4, 1, 100);
        compressionSummaryTokens = integerOrDefault(properties, "compressionSummaryTokens", 2048, 128, 16000);
        summaryMaxOutputTokens = integerOrDefault(properties, "summaryMaxOutputTokens",
                Math.max(compressionSummaryTokens, Math.min(8192, (contextWindowTokens - 2048) / 2)),
                compressionSummaryTokens, contextWindowTokens - 2048);
        validateContextBudget();

        longTermMemoryFile = properties.getProperty("longTermMemoryFile", "../memory/user-profile.json").trim();
        if (longTermMemoryFile.isEmpty()) {
            throw new IllegalArgumentException("longTermMemoryFile 不能为空");
        }
        systemPrompt = properties.getProperty("systemPrompt",
                "你是游戏《远行星号》中的墨汁智能体。简短地用中文回答。").trim();
        if (systemPrompt.isEmpty()) {
            throw new IllegalArgumentException("systemPrompt 不能为空。");
        }
    }

    static AgentConfig load(String configUrl) throws Exception {
        Properties properties = new Properties();
        URL url = URI.create(configUrl).toURL();
        try (InputStreamReader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new AgentConfig(properties);
    }


    private void validateContextBudget() {
        boolean insufficientInputBudget = contextWindowTokens - contextReserveTokens <= MIN_INPUT_BUDGET;
        boolean oversizedSummary = compressionSummaryTokens > (contextWindowTokens - MIN_INPUT_BUDGET) / 2;
        if (insufficientInputBudget || oversizedSummary) {
            throw new IllegalArgumentException("上下文窗口不足，请检查 contextWindowTokens 和输出/摘要预算");
        }
    }

    private static String thinkingMode(Properties properties, String name) {
        return optionalChoice(properties, name, Set.of("enabled", "disabled"));
    }

    private static String reasoningEffort(Properties properties, String name) {
        return optionalChoice(properties, name, Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max"));
    }

    private static String optionalChoice(Properties properties, String name, Set<String> choices) {
        String value = properties.getProperty(name, "").strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (!choices.contains(value)) throw new IllegalArgumentException(name + " 不支持值：" + value);
        return value;
    }

    private static void validateThinking(String purpose, String mode, String effort) {
        if (("enabled".equals(mode) && "none".equals(effort))
                || ("disabled".equals(mode) && effort != null && !"none".equals(effort))) {
            throw new IllegalArgumentException(purpose + "的 thinkingMode 与 reasoningEffort 冲突；"
                    + "关闭思考时 effort 应留空或为 none，开启时不能为 none。");
        }
    }

    private static int integerOrDefault(Properties properties, String name, int fallback, int min, int max) {
        Integer value = optionalInteger(properties, name, min, max);
        return value == null ? fallback : value;
    }

    private static Integer optionalInteger(Properties properties, String name, int min, int max) {
        String text = properties.getProperty(name, "").trim();
        if (text.isEmpty()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须为整数。");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 范围为 " + min + " 到 " + max + "。");
        }
        return value;
    }

    private static Double optionalDecimal(Properties properties, String name, double min, double max) {
        String text = properties.getProperty(name, "").trim();
        if (text.isEmpty()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须为数字。");
        }
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须为 " + min + " 到 " + max + " 之间的有限数值。");
        }
        return value;
    }
}
