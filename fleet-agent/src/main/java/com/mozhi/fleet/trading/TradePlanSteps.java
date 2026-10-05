package com.mozhi.fleet.trading;

import com.mozhi.fleet.model.Plan;
import com.mozhi.fleet.model.Step;
import java.util.*;
import static com.mozhi.fleet.trading.TradeSnapshot.*;

/** 路线保留行程；每站生成本航段真实采购、移动和出售步骤，沿用 Agent 的计划插入机制。 */
public final class TradePlanSteps {
    private TradePlanSteps() {}
    public static Step move(Market market) {
        return Step.create("MOVE_TO", Map.of("destinationId", market.destinationId()), "前往 " + market.name(), "抵达并环绕市场");
    }
    public static Step prepare(TradeHopPlanner.Hop hop, TradeRouteOptions options) {
        String goods = hop.load().lots().stream().map(l -> l.quantity() + " × " + l.buy().name()).reduce((a, b) -> a + "、" + b).orElse("空载");
        return Step.create("PREPARE_TRADE_HOP", Map.of("marketId", hop.from().id(), "destinationMarketId", hop.to().id(), "options", options.parameters()),
                hop.from().name() + " → " + hop.to().name() + "：预计 " + goods,
                "到站按真实货架重算本段后勤及买卖数量，展开为可执行计划");
    }
    public static Plan executable(TradeHopPlanner.Hop hop) {
        List<Step> steps = new ArrayList<>();
        for (var purchase : hop.purchases()) steps.add(trade("BUY", hop.from(), purchase.quote(), purchase.quantity(), "本段航行采购"));
        for (var lot : hop.load().lots()) steps.add(trade("BUY", hop.from(), lot.buy(), lot.quantity(), "跑商采购"));
        steps.add(move(hop.to()));
        // 原有资源承担航行消耗，出售数量严格等于本轮贸易采购量；不触碰原有库存。
        for (var lot : hop.load().lots()) steps.add(trade("SELL", hop.to(), lot.sell(), lot.quantity(), "出售本段新购商品"));
        return Plan.create("执行 " + hop.from().name() + " 至 " + hop.to().name() + " 的贸易航段", steps);
    }
    private static Step trade(String action, Market market, Quote quote, int quantity, String purpose) {
        return Step.create(action, Map.of("marketId", market.id(), "submarketId", quote.submarketId(),
                "itemType", "COMMODITY", "itemId", quote.commodityId(), "quantity", quantity),
                purpose + "：" + quantity + " × " + quote.name(), "按计划数量及成交时价格执行并记录回执");
    }
}
