package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.trading.*;
import java.util.List;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;

/** 决策动作：轻量采集、分帧按需报价与搜索，返回 Plan 供 Agent 插入；自身不交易。 */
public final class CalculateTradeRouteAction implements Action {
    public static final String NAME = "CALCULATE_TRADE_ROUTE";
    private Step current;
    private TradeSnapshotCollector collector;
    private TradeRouteOptions options;
    private volatile TradeRouteCalculator calculator;
    private long collectionStarted;

    @dev.langchain4j.agent.tool.Tool(name = NAME, value = "根据舰队实际资金与容量计算多站商品跑商路线，无 LLM。远程估库存粗筛，到站 PREPARE_TRADE_HOP 按真实货架确定买卖。成功 generatedPlan 自动插入当前步骤之后；只表示路线生成，不表示交易完成。只交易经济货物，不交易人员或出售原有货物；后勤采购写入计划，保留量按参数决定。利润为含税差价，未扣途中消耗；无可行路线则失败。")
    public ExecutionResult calculate(Step step, ActionContext context,
            @dev.langchain4j.agent.tool.P(name = "maxSpend", value = "每航段最多投入星币；默认可用资金，不超出玩家预算", required = false) Double maxSpend,
            @dev.langchain4j.agent.tool.P(name = "maxStops", value = "2 至 6 个不同市场，默认 4；闭环回首站不另计", required = false) Integer maxStops,
            @dev.langchain4j.agent.tool.P(name = "maxDays", value = "预计天数上限，默认 30", required = false) Double maxDays,
            @dev.langchain4j.agent.tool.P(name = "maxStartDistanceLy", value = "首站最大光年距离，默认 10；同星系始终考虑", required = false) Double maxStartDistanceLy,
            @dev.langchain4j.agent.tool.P(name = "minProfit", value = "最低预计贸易利润，默认 1", required = false) Double minProfit,
            @dev.langchain4j.agent.tool.P(name = "reserveCredits", value = "保留星币，默认 0", required = false) Double reserveCredits,
            @dev.langchain4j.agent.tool.P(name = "reserveFuel", value = "航行完成后保留燃料，默认 0", required = false) Double reserveFuel,
            @dev.langchain4j.agent.tool.P(name = "reserveSupplies", value = "航行完成后保留补给，默认 0", required = false) Double reserveSupplies,
            @dev.langchain4j.agent.tool.P(name = "allowBlackMarket", value = "默认 true；仅玩家明确排除黑市时设 false", required = false) Boolean allowBlackMarket,
            @dev.langchain4j.agent.tool.P(name = "closedLoop", value = "最终回首个市场，默认 false；不是回玩家舰队", required = false) Boolean closedLoop,
            @dev.langchain4j.agent.tool.P(name = "commodityIds", value = "经济商品 ID 列表，省略或空表示全部；不包含人员", required = false) List<String> commodityIds) {
        if (!step.equals(current)) {
            stop(context); current = step;
            var arguments = new java.util.LinkedHashMap<String, Object>();
            Object[][] values = {{"maxSpend", maxSpend}, {"maxStops", maxStops}, {"maxDays", maxDays},
                    {"maxStartDistanceLy", maxStartDistanceLy}, {"minProfit", minProfit}, {"reserveCredits", reserveCredits},
                    {"reserveFuel", reserveFuel}, {"reserveSupplies", reserveSupplies}, {"allowBlackMarket", allowBlackMarket},
                    {"closedLoop", closedLoop}, {"commodityIds", commodityIds}};
            for (Object[] value : values) if (value[1] != null) arguments.put((String) value[0], value[1]);
            options = TradeRouteOptions.from(arguments);
            collector = new TradeSnapshotCollector(context, options);
            collectionStarted = System.nanoTime();
        }
        if (collector != null) {
            if (!collector.advance()) return new ExecutionResult(step, RUNNING, collector.progress());
            var snapshot = collector.snapshot();
            calculator = new TradeRouteCalculator(snapshot, options, collector.prices(), 2000);
            org.apache.log4j.Logger.getLogger(CalculateTradeRouteAction.class).info("跑商供需快照：市场 " + snapshot.markets().size()
                    + "，跳过 " + snapshot.skipped() + "，耗时 " + (System.nanoTime() - collectionStarted) / 1_000_000 + " 毫秒");
            collector = null;
            return new ExecutionResult(step, RUNNING, "市场供需快照已就绪，正在筛选候选路线");
        }
        if (!calculator.advance(4_000_000L)) return new ExecutionResult(step, RUNNING, calculator.progress());
        var result = calculator.result();
        org.apache.log4j.Logger.getLogger(CalculateTradeRouteAction.class).info("跑商计算统计：" + result.metrics());
        return new ExecutionResult(step, result.plan() == null ? FAILED : SUCCEEDED, result.explanation(), result.plan());
    }
    public void stop(ActionContext context) { cancelBackground(); current = null; collector = null; calculator = null; }
    public void cancelBackground() { var active = calculator; if (active != null) active.cancel(); }
    public void pause() { if (calculator != null) calculator.pause(); }
    public boolean backgroundStopped() { return true; }
}
