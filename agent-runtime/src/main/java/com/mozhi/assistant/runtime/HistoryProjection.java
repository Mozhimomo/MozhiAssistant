package com.mozhi.assistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

/** 只精简已经结束轮次的大型查询结果；当前工具调用链和有副作用的命令结果完整保留。 */
final class HistoryProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> QUERIES = Set.of("getMozhiFleetStatus", "getMozhiTradeReceipts", "getFleetSummary",
            "searchShips", "searchSpecs", "searchHullSpecs", "searchPlanets", "listPlanets", "queryMarketInventory",
            "findBuyingLocations", "getNavigationStatus");

    /** 工具发现只在本轮有效；结束时同时移除请求与结果，保留同批业务调用。 */
    static List<ChatMessage> withoutDiscovery(List<ChatMessage> messages) {
        var retained = new ArrayList<ChatMessage>();
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result && result.toolName().equals("discoverTools")) continue;
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()
                    && ai.toolExecutionRequests().stream().anyMatch(call -> call.name().equals("discoverTools"))) {
                var calls = ai.toolExecutionRequests().stream().filter(call -> !call.name().equals("discoverTools")).toList();
                if (!calls.isEmpty()) retained.add(AiMessage.builder().text(ai.text()).toolExecutionRequests(calls).build());
                else if (ai.text() != null && !ai.text().isBlank()) retained.add(AiMessage.from(ai.text()));
            } else retained.add(message);
        }
        return retained;
    }

    static ChatMessage compact(ChatMessage message) {
        if (!(message instanceof ToolExecutionResultMessage result) || !QUERIES.contains(result.toolName())
                || result.text().length() <= 2000 || result.text().startsWith("工具失败：")) return message;
        var brief = JSON.createObjectNode();
        brief.put("historicalQuery", result.toolName());
        brief.put("note", "较早轮次的大型查询结果已从对话上下文省略。以下仅为历史索引，非实时事实；需要明细或当前状态时重新调用工具，本地交易账本仍完整保存。");
        try {
            var full = JSON.readTree(result.text());
            if (full.has("error")) return message;
            for (String key : new String[]{"taskId", "offset", "total", "nextOffset", "hasMore"})
                if (full.has(key) && full.get(key).isValueNode()) brief.set(key, full.get(key));
            if (result.toolName().equals("getMozhiFleetStatus")) {
                var state = full.path("state");
                if (state.has("mission")) brief.set("mission", state.get("mission"));
                var totals = state.path("tradeSummary");
                var ledger = brief.putObject("historicalTotals");
                for (String key : new String[]{"creditsSpent", "creditsReceived", "netCashflow", "settledTrades", "unknownAmountTrades"})
                    if (totals.has(key)) ledger.set(key, totals.get(key));
                brief.put("accounting", "累计现金收支，不能与分页明细重复相加，netCashflow 不是净利润。");
            }
        } catch (Exception ignored) { /* 非 JSON 查询仅保留明确的省略标记。 */ }
        return ToolExecutionResultMessage.from(result.id(), result.toolName(), brief.toString());
    }
}
