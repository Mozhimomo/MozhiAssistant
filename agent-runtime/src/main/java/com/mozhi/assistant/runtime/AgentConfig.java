package com.mozhi.assistant.runtime;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

final class AgentConfig {
    final String apiKey;
    final String baseUrl;
    final String modelName;
    final Duration timeout;
    final Double temperature;
    final Double topP;
    final Integer maxTokens;
    final Integer maxCompletionTokens;
    final Double presencePenalty;
    final Double frequencyPenalty;
    final Integer seed;
    final int maxRetries;
    final int memoryMaxMessages;
    final int maxSequentialToolsInvocations;
    final String systemPrompt;

    private AgentConfig(Properties properties) {
        String key = properties.getProperty("apiKey", "").trim();
        if (key.isEmpty()) {
            String environmentKey = System.getenv("MOZHI_API_KEY");
            key = environmentKey == null ? "" : environmentKey.trim();
        }
        if (key.isEmpty()) throw new IllegalArgumentException("请填写 apiKey，或设置 MOZHI_API_KEY 环境变量。");
        String baseUrl = properties.getProperty("baseUrl", "https://api.openai.com/v1/").trim();
        URI endpoint;
        try { endpoint = URI.create(baseUrl); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("baseUrl 不是有效的 URL。"); }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("baseUrl 必须是 http/https 地址。");
        }
        String model = properties.getProperty("modelName", "").trim();
        if (model.isEmpty() || model.equals("YOUR_TOOL_CAPABLE_MODEL")) {
            throw new IllegalArgumentException("请填写支持 tool/function calling 的 modelName。");
        }
        this.apiKey = key;
        this.baseUrl = baseUrl;
        this.modelName = model;
        timeout = Duration.ofSeconds(integer(properties, "timeoutSeconds", 60, 1, 300));
        temperature = decimal(properties, "temperature", 0, 2);
        topP = decimal(properties, "topP", 0, 1);
        maxTokens = optionalInteger(properties, "maxTokens", 1, Integer.MAX_VALUE);
        maxCompletionTokens = optionalInteger(properties, "maxCompletionTokens", 1, Integer.MAX_VALUE);
        if (maxTokens != null && maxCompletionTokens != null) {
            throw new IllegalArgumentException("maxTokens 和 maxCompletionTokens 只能填写一个，另一个请留空。");
        }
        presencePenalty = decimal(properties, "presencePenalty", -2, 2);
        frequencyPenalty = decimal(properties, "frequencyPenalty", -2, 2);
        seed = optionalInteger(properties, "seed", Integer.MIN_VALUE, Integer.MAX_VALUE);
        maxRetries = integer(properties, "maxRetries", 0, 0, 10);
        memoryMaxMessages = integer(properties, "memoryMaxMessages", 12, 3, 10000);
        maxSequentialToolsInvocations = integer(properties, "maxSequentialToolsInvocations", 4, 1, 100);
        systemPrompt = properties.getProperty("systemPrompt",
                "你是游戏《远行星号》中的墨汁智能体。简短地用中文回答。"
                        + "涉及玩家舰队时必须调用 getFleetSummary 实时读取最新详情，不要凭记忆猜测。"
                        + "涉及整数加法时必须调用 add 工具。调用完工具后向用户解释结果。").trim();
        if (systemPrompt.isEmpty()) throw new IllegalArgumentException("systemPrompt 不能为空。");
    }

    static AgentConfig load(String configUrl) throws Exception {
        Properties properties = new Properties();
        URL url = URI.create(configUrl).toURL();
        try (InputStreamReader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new AgentConfig(properties);
    }

    private static int integer(Properties properties, String name, int fallback, int min, int max) {
        Integer value = optionalInteger(properties, name, min, max);
        return value == null ? fallback : value;
    }

    private static Integer optionalInteger(Properties properties, String name, int min, int max) {
        String text = properties.getProperty(name, "").trim();
        if (text.isEmpty()) return null;
        int value;
        try { value = Integer.parseInt(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " 必须为整数。"); }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 范围为 " + min + " 到 " + max + "。");
        }
        return value;
    }

    private static Double decimal(Properties properties, String name, double min, double max) {
        String text = properties.getProperty(name, "").trim();
        if (text.isEmpty()) return null;
        double value;
        try { value = Double.parseDouble(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " 必须为数字。"); }
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须为 " + min + " 到 " + max + " 之间的有限数值。");
        }
        return value;
    }
}
