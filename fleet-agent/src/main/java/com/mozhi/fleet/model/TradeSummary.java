package com.mozhi.fleet.model;

import java.util.*;

/** 本地全量账本的有界聚合。现金净流入不是已实现利润；未知历史交易单独标记。 */
public final class TradeSummary {
    private TradeSummary() {}
    public static Map<String, Object> of(List<ExecutionResult> results) {
        double spent = 0, received = 0;
        int trades = 0, unknown = 0;
        var commodities = new TreeMap<String, double[]>();
        for (var result : results) {
            if (result.status() != ExecutionResult.Status.SUCCEEDED || !Set.of("BUY", "SELL").contains(result.step().action())) continue;
            trades++;
            var receipt = result.tradeReceipt();
            if (receipt == null) { unknown++; continue; }
            spent += receipt.creditsSpent(); received += receipt.creditsReceived();
            var p = result.step().parameters();
            if ("COMMODITY".equals(p.get("itemType")) && p.get("quantity") instanceof Number quantity) {
                var totals = commodities.computeIfAbsent(p.get("itemId").toString(), ignored -> new double[4]);
                totals[result.step().action().equals("BUY") ? 0 : 1] += quantity.doubleValue();
                totals[2] += receipt.creditsSpent(); totals[3] += receipt.creditsReceived();
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("settledTrades", trades - unknown); result.put("unknownAmountTrades", unknown);
        result.put("creditsSpent", spent); result.put("creditsReceived", received); result.put("netCashflow", received - spent);
        result.put("commodities", commodities.entrySet().stream().map(entry -> Map.of("itemId", entry.getKey(),
                "bought", entry.getValue()[0], "sold", entry.getValue()[1], "creditsSpent", entry.getValue()[2], "creditsReceived", entry.getValue()[3])).toList());
        result.put("scope", "本任务全部成功买卖，含后勤采购；netCashflow 仅现金净流入，不含初始资金、存货价值及未计价消耗，不等于净利润。历史缺失收据见 unknownAmountTrades；最近交易是该汇总的子集，勿重复相加");
        return result;
    }
}
