package com.mozhi.fleet.planning;

import com.mozhi.llm.LlmConfig;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 舰队规划可独立设置输出预算和思考参数，不改变聊天客户端的配置。 */
final class PlannerConfig {
    private PlannerConfig() {}

    static LlmConfig load(String url) throws IOException {
        Properties values = new Properties();
        try (var reader = new InputStreamReader(URI.create(url).toURL().openStream(), StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return from(values);
    }

    static LlmConfig from(Properties values) {
        LlmConfig base = LlmConfig.from(values);
        String budget = values.getProperty("fleetPlannerMaxOutputTokens", "").trim();
        int tokens;
        try { tokens = budget.isEmpty() ? base.outputTokens() : Integer.parseInt(budget); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("fleetPlannerMaxOutputTokens 必须为正整数", error); }
        return base.withGeneration(tokens,
                values.getProperty("fleetPlannerThinkingMode", base.thinkingMode()),
                values.getProperty("fleetPlannerReasoningEffort", base.reasoningEffort()));
    }
}
