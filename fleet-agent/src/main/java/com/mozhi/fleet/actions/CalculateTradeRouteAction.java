package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;
import com.mozhi.fleet.trading.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.mozhi.fleet.model.ExecutionResult.Status.*;
import static com.mozhi.fleet.planning.ActionSpec.Type.*;

/** 决策动作：分帧读取快照，后台纯计算，返回 Plan 供 Agent 插入；自身不交易。 */
public final class CalculateTradeRouteAction implements Action {
    public static final String NAME = "CALCULATE_TRADE_ROUTE";
    private static final ActionSpec SPEC = new ActionSpec(NAME,
            "根据分舰队当前资金、空余货舱、燃料和补给计算多站商品跑商路线。使用真实库存及含税整批报价，无 LLM。"
                    + "成功结果包含 generatedPlan，Agent 自动插在本步骤之后、原后续步骤之前。"
                    + "计算前不要编造买卖市场、商品或数量；后续可放 RETURN。执行历史中的计算结果及 currentPlan 才是后续决策依据。"
                    + "仅处理新购商品，不出售原有货物；不自动补充航行物资。约束仅涵盖生成路线，后续回归玩家需另留燃料和补给。"
                    + "利润为含税贸易差价，未扣燃料补给成本；无可行路线则失败。",
            List.of(ActionSupport.parameter("maxSpend", NUMBER, false, "每一航段最多投入的信用点，默认可用资金，不得超出玩家预算"),
                    ActionSupport.parameter("maxStops", INTEGER, false, "2 至 6 个不同市场，默认 4；闭环回到首站不计额外市场"),
                    ActionSupport.parameter("maxDays", NUMBER, false, "预计天数上限，默认 30"),
                    ActionSupport.parameter("maxStartDistanceLy", NUMBER, false, "首站最大光年距离，默认 10；同星系始终考虑"),
                    ActionSupport.parameter("minProfit", NUMBER, false, "最低预计贸易利润，默认 1"),
                    ActionSupport.parameter("reserveCredits", NUMBER, false, "保留信用点，默认 0"),
                    ActionSupport.parameter("reserveFuel", NUMBER, false, "航行完成后保留燃料，默认 0"),
                    ActionSupport.parameter("reserveSupplies", NUMBER, false, "航行完成后保留补给，默认 0"),
                    ActionSupport.parameter("allowBlackMarket", BOOLEAN, false, "是否考虑黑市，默认 true；玩家明确排除黑市时才设为 false"),
                    ActionSupport.parameter("closedLoop", BOOLEAN, false, "是否最终回首个市场，默认 false；不是回玩家舰队"),
                    ActionSupport.parameter("commodityIds", ARRAY, false, "可交易的经济商品 ID 字符串列表，省略/空列表表示全部")));
    private Step current;
    private TradeSnapshotCollector collector;
    private TradeRouteOptions options;
    private volatile FutureTask<TradeRouteCalculator.Result> pending;
    private final AtomicInteger workers = new AtomicInteger();
    public ActionSpec spec() { return SPEC; }
    public ExecutionResult execute(Step step, ActionContext context) {
        if (!step.equals(current)) {
            stop(context); current = step;
            options = TradeRouteOptions.from(step.parameters());
            collector = new TradeSnapshotCollector(context, options);
        }
        if (collector != null) {
            if (!collector.advance()) return new ExecutionResult(step, RUNNING, collector.progress());
            var snapshot = collector.snapshot();
            var settings = options;
            collector = null;
            workers.incrementAndGet();
            pending = new FutureTask<>(() -> new TradeRouteCalculator(snapshot, settings).calculate()) {
                @Override public void run() { try { super.run(); } finally { workers.decrementAndGet(); } }
            };
            try { ForkJoinPool.commonPool().execute(pending); }
            catch (RuntimeException error) { workers.decrementAndGet(); pending.cancel(true); throw error; }
            return new ExecutionResult(step, RUNNING, "市场快照已就绪，正在计算跑商路线");
        }
        if (!pending.isDone()) return new ExecutionResult(step, RUNNING, "正在比较货物组合和多站路线");
        try {
            var result = pending.get();
            return new ExecutionResult(step, result.plan() == null ? FAILED : SUCCEEDED, result.explanation(), result.plan());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("跑商计算被中断", error);
        } catch (ExecutionException error) { throw new IllegalStateException("跑商计算失败：" + error.getCause().getMessage(), error.getCause()); }
    }
    public void stop(ActionContext context) { cancelBackground(); current = null; collector = null; }
    public void cancelBackground() { FutureTask<?> task = pending; if (task != null) task.cancel(true); }
    public boolean backgroundStopped() { return workers.get() == 0; }
}
