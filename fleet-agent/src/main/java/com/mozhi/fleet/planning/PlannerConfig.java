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
        return load(url, false);
    }

    static LlmConfig load(String url, boolean cheap) throws IOException {
        Properties values = new Properties();
        try (var reader = new java.io.BufferedReader(new InputStreamReader(URI.create(url).toURL().openStream(), StandardCharsets.UTF_8))) {
            // 接受带或不带 UTF-8 编码标记的配置文件。
            reader.mark(1);
            if (reader.read() != '\uFEFF') reader.reset();
            values.load(reader);
        }
        return cheap ? LlmConfig.cheapFrom(values) : from(values);
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
    /** 仅供一次截断恢复使用；保持模型、密钥与输出上限，避免高强度思考再次占满生成预算。 */
    static LlmConfig recovery(LlmConfig base) {
        boolean thinking = "enabled".equals(base.thinkingMode());
        return base.withGeneration(base.outputTokens(), thinking ? "disabled" : base.thinkingMode(),
                thinking || "disabled".equals(base.thinkingMode()) ? null : base.reasoningEffort() == null ? null : "low");
    }
}
