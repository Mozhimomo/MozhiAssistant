package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.trading.*;
import java.util.*;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 跑商计算器生成的决策步骤：到站刷新本地货架，返回本航段 Plan，不直接改变舰队资产。 */
public final class PrepareTradeHopAction implements Action {
    public static final String NAME = "PREPARE_TRADE_HOP";
    @dev.langchain4j.agent.tool.Tool(name = NAME, value = "跑商计算器生成的到站采购单重算步骤。保持下一站不变，按真实库存、资金和空间展开买卖 Plan，无 LLM。通常由 CALCULATE_TRADE_ROUTE 生成，不需 Planner 自行拼接。")
    public ExecutionResult prepare(Step step, ActionContext context,
            @dev.langchain4j.agent.tool.P(name = "marketId", value = "当前已环绕市场 ID") String from,
            @dev.langchain4j.agent.tool.P(name = "destinationMarketId", value = "下一站市场 ID") String to,
            @dev.langchain4j.agent.tool.P(name = "options", value = "路线计算器保留的原始规划约束") Map<String, Object> raw) {
        if (from.equals(to)) throw new IllegalArgumentException("贸易航段的起点和终点不能相同");
        var api = ActionSupport.market(context, from);
        if (!ActionSupport.orbiting(context.fleet(), ActionSupport.marketEntity(api)))
            throw new IllegalStateException("尚未抵达采购市场，需要先执行移动步骤");
        var options = TradeRouteOptions.from(raw);
        var collector = new TradeSnapshotCollector(context, options);
        var snapshot = collector.liveHop(from, to);
        var prices = new TradeQuotes.Cached(collector.prices());
        long started = System.nanoTime(), deadline = started + 2_000_000_000L;
        var planner = new TradeHopPlanner(snapshot, options, prices, () -> {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("到站采购单计算超时，请重新规划");
        });
        var origin = snapshot.markets().get(0); var destination = snapshot.markets().get(1);
        if (destination.closedDays() > snapshot.travel(origin.position(), destination.position()).days() + .01)
            return new ExecutionResult(step, FAILED, "下一站到达时仍无法交易，需要重新规划");
        var hop = planner.plan(origin, destination, planner.initial(), true);
        if (hop == null) return new ExecutionResult(step, FAILED, "当前库存、资金或容量无法满足本航段采购与航行条件，需要重新规划");
        var plan = TradePlanSteps.executable(hop);
        org.apache.log4j.Logger.getLogger(PrepareTradeHopAction.class).info("到站重算采购单：" + from + " → " + to
                + "，报价 " + prices.calls() + " 次，耗时 " + (System.nanoTime() - started) / 1_000_000 + " 毫秒");
        return new ExecutionResult(step, SUCCEEDED, hop.load().lots().isEmpty()
                ? "当前已无可盈利货物，更新为空载航段并保留后续行程"
                : "已按真实货架重算本段采购单，预计贸易差价 " + Math.round(hop.load().profit()), plan);
    }
}
