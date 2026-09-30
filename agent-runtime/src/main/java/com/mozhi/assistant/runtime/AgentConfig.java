package com.mozhi.assistant.runtime;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.Locale;
import java.util.Set;

/** 读取并校验配置；字段在构造完成后不再变化。配置项名称保持向后兼容。 */
final class AgentConfig {
    private static final int DEFAULT_OUTPUT_TOKENS = 4096;
    private static final int MIN_INPUT_BUDGET = 2048;

    // 模型连接与生成参数。
    final String apiKey;
    final String baseUrl;
    final String modelName;
    final Duration timeout;
    final int maxRetries;
    final boolean streamingEnabled;
    final Double temperature;
    final Double topP;
    final Integer maxTokens;
    final Integer maxCompletionTokens;
    final Double presencePenalty;
    final Double frequencyPenalty;
    final Integer seed;
    final String thinkingMode;
    final String reasoningEffort;
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
    final String longTermMemoryFile;

    private AgentConfig(Properties properties) {
        String streaming = properties.getProperty("streamingEnabled", "true").trim();
        if (!streaming.equalsIgnoreCase("true") && !streaming.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("streamingEnabled 必须为 true 或 false");
        }
        streamingEnabled = Boolean.parseBoolean(streaming);
        apiKey = readApiKey(properties);
        baseUrl = readBaseUrl(properties);
        modelName = readModelName(properties);
        timeout = Duration.ofSeconds(integerOrDefault(properties, "timeoutSeconds", 60, 1, 300));

        temperature = optionalDecimal(properties, "temperature", 0, 2);
        topP = optionalDecimal(properties, "topP", 0, 1);
        maxTokens = optionalInteger(properties, "maxTokens", 1, Integer.MAX_VALUE);
        maxCompletionTokens = optionalInteger(properties, "maxCompletionTokens", 1, Integer.MAX_VALUE);
        if (maxTokens != null && maxCompletionTokens != null) {
            throw new IllegalArgumentException("maxTokens 和 maxCompletionTokens 只能填写一个，另一个请留空。");
        }
        presencePenalty = optionalDecimal(properties, "presencePenalty", -2, 2);
        frequencyPenalty = optionalDecimal(properties, "frequencyPenalty", -2, 2);
        seed = optionalInteger(properties, "seed", Integer.MIN_VALUE, Integer.MAX_VALUE);
        maxRetries = integerOrDefault(properties, "maxRetries", 0, 0, 10);
        thinkingMode = thinkingMode(properties, "thinkingMode");
        reasoningEffort = reasoningEffort(properties, "reasoningEffort");
        summaryThinkingMode = thinkingMode(properties, "summaryThinkingMode");
        summaryReasoningEffort = reasoningEffort(properties, "summaryReasoningEffort");
        validateThinking("对话", thinkingMode, reasoningEffort);
        validateThinking("摘要", summaryThinkingMode, summaryReasoningEffort);

        memoryMaxMessages = integerOrDefault(properties, "memoryMaxMessages", 64, 3, 10000);
        maxSequentialToolsInvocations = integerOrDefault(properties, "maxSequentialToolsInvocations", 4, 1, 100);
        contextWindowTokens = integerOrDefault(properties, "contextWindowTokens", 131072, 4096, 2000000);

        int outputLimit = configuredOutputLimit();
        int configuredReserve = integerOrDefault(properties, "contextReserveTokens", outputLimit, 1, 1000000);
        contextReserveTokens = Math.max(outputLimit, configuredReserve);

        Double triggerRatio = optionalDecimal(properties, "compressionTriggerRatio", 0.1, 0.95);
        compressionTriggerRatio = triggerRatio == null ? 0.8 : triggerRatio;
        compressionKeepRecentTurns = integerOrDefault(properties, "compressionKeepRecentTurns", 4, 1, 100);
        compressionSummaryTokens = integerOrDefault(properties, "compressionSummaryTokens", 2048, 128, 16000);
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

    private int configuredOutputLimit() {
        if (maxTokens != null) {
            return maxTokens;
        }
        if (maxCompletionTokens != null) {
            return maxCompletionTokens;
        }
        return DEFAULT_OUTPUT_TOKENS;
    }

    private void validateContextBudget() {
        boolean insufficientInputBudget = contextWindowTokens - contextReserveTokens <= MIN_INPUT_BUDGET;
        boolean oversizedSummary = compressionSummaryTokens > (contextWindowTokens - MIN_INPUT_BUDGET) / 2;
        if (insufficientInputBudget || oversizedSummary) {
            throw new IllegalArgumentException("上下文窗口不足，请检查 contextWindowTokens 和输出/摘要预算");
        }
    }

    private static String readApiKey(Properties properties) {
        String key = properties.getProperty("apiKey", "").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("请填写 apiKey：可以直接填写密钥，或使用 ${环境变量名}。");
        }
        if (!key.contains("${")) {
            return key;
        }
        if (!key.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}")) {
            throw new IllegalArgumentException("apiKey 的环境变量引用必须完整写为 ${变量名}，不支持拼接或嵌套。");
        }
        String variable = key.substring(2, key.length() - 1);
        String environmentKey = System.getenv(variable);
        if (environmentKey == null || environmentKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 引用的环境变量未设置或为空，请设置后重启游戏及启动器。");
        }
        return environmentKey.trim();
    }

    private static String readBaseUrl(Properties properties) {
        String baseUrl = properties.getProperty("baseUrl", "https://api.openai.com/v1/").trim();
        URI endpoint;
        try {
            endpoint = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl 不是有效的 URL。");
        }
        boolean httpEndpoint = "http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme());
        if (!httpEndpoint || endpoint.getHost() == null) {
            throw new IllegalArgumentException("baseUrl 必须是 http/https 地址。");
        }
        return baseUrl;
    }

    private static String readModelName(Properties properties) {
        String modelName = properties.getProperty("modelName", "").trim();
        if (modelName.isEmpty() || modelName.equals("YOUR_TOOL_CAPABLE_MODEL")) {
            throw new IllegalArgumentException("请填写支持 tool/function calling 的 modelName。");
        }
        return modelName;
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

    /** DeepSeek 的工具调用续轮需要原样回传 reasoning_content；不将该字段发送给其他协议。 */
    boolean exchangeThinking(String mode, String effort) {
        boolean deepSeekProtocol = mode != null || "api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost());
        return deepSeekProtocol && !"disabled".equals(mode) && !"none".equals(effort);
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
