package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** 出售指定交易区的真实货物或舰船。 */
public final class SellAction extends TradeAction {
    public SellAction() { super(false); }

    @Tool(name = "SELL", value = "出售指定交易区的真实货物或舰船。先 MOVE_TO 并实际入轨；按实时库存、价格和关税一次成交，不部分成交。成功返回 tradeReceipt 实际收支与含税报价；库存或资金不足失败，不自动导航。")
    public ExecutionResult invoke(Step step, ActionContext context,
            @P(name = "marketId", value = "市场 ID") String marketId,
            @P(name = "submarketId", value = "交易区 ID；不可使用免费仓储或隐藏交易区") String submarketId,
            @P(name = "itemType", value = "COMMODITY、WEAPON、FIGHTER、HULLMOD、SPECIAL 或 SHIP") String itemType,
            @P(name = "itemId", value = "物品规格 ID；SHIP 使用舰船实例 ID") String itemId,
            @P(name = "quantity", value = "1 至 1000000 的整数；SHIP 必须为 1") int quantity,
            @P(name = "itemData", value = "仅 SPECIAL 使用；多种实例时必须指定", required = false) String itemData) {
        return trade(step, context, marketId, submarketId, itemType, itemId, quantity, itemData);
    }
}