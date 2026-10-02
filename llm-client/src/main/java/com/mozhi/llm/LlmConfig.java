package com.mozhi.llm;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.Locale;
import java.util.Set;

/** 读取并校验配置；字段在构造完成后不再变化。配置项名称保持向后兼容。 */
public final class LlmConfig {
    private static final int DEFAULT_OUTPUT_TOKENS = 4096;
    private final Properties values;

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

    private LlmConfig(Properties properties) {
        values = new Properties();
        for (String key : properties.stringPropertyNames()) values.setProperty(key, properties.getProperty(key));
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
        validateThinking("生成", thinkingMode, reasoningEffort);
    }

    /** 配置对象会复制输入，后续修改 Properties 不影响已创建的客户端。 */
    public static LlmConfig from(Properties properties) {
        return new LlmConfig(java.util.Objects.requireNonNull(properties, "properties"));
    }

    public static LlmConfig load(String configUrl) throws java.io.IOException {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                URI.create(configUrl).toURL().openStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return from(properties);
    }

    /** 派生独立生成配置，用于摘要等不同输出预算的请求，不修改原对象。 */
    public LlmConfig withGeneration(int outputTokens, String mode, String effort) {
        if (outputTokens < 1) throw new IllegalArgumentException("outputTokens 必须为正数");
        Properties copy = new Properties();
        copy.putAll(values);
        // 已解析的密钥不因派生配置而重新读取环境变量。
        copy.setProperty("apiKey", apiKey);
        copy.remove("maxTokens");
        copy.remove("maxCompletionTokens");
        copy.setProperty(maxCompletionTokens == null ? "maxTokens" : "maxCompletionTokens",
                Integer.toString(outputTokens));
        copy.remove("thinkingMode");
        copy.remove("reasoningEffort");
        if (mode != null) copy.setProperty("thinkingMode", mode);
        if (effort != null) copy.setProperty("reasoningEffort", effort);
        return from(copy);
    }

    public String modelName() { return modelName; }
    public int timeoutSeconds() { return (int) timeout.toSeconds(); }
    public boolean streamingEnabled() { return streamingEnabled; }
    public String thinkingMode() { return thinkingMode; }
    public String reasoningEffort() { return reasoningEffort; }

    public int outputTokens() {
        if (maxCompletionTokens != null) return maxCompletionTokens;
        return maxTokens == null ? DEFAULT_OUTPUT_TOKENS : maxTokens;
    }

    /** 对外错误和诊断信息统一经此处脱敏；不暴露密钥 getter。 */
    public String redact(String text) {
        return text == null ? null : text.replace(apiKey, "[REDACTED]");
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
            throw new IllegalArgumentException("请填写 modelName。");
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
