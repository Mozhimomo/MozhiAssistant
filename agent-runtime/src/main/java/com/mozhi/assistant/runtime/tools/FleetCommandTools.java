package com.mozhi.assistant.runtime.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.assistant.bridge.FleetAgentAccess;
import com.mozhi.assistant.bridge.GameThreadAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import java.util.Map;

/** 聊天向独立舰队提交明确命令，实际航行和交易由主线程计划执行器完成。 */
public final class FleetCommandTools {
    private final GameThreadAccess gameThread;
    private final ObjectMapper json = new ObjectMapper();
    public FleetCommandTools(GameThreadAccess gameThread) { this.gameThread = gameThread; }

    @Tool("预览玩家指定的舰船及出发资源建议，不实际转移；建议不是最低划拨量。不能派出玩家旗舰；重名时先查询舰船实例 ID。")
    public String previewMozhiFleet(@P("玩家指定的舰船实例 ID/名字列表") List<String> ships) {
        return send(Map.of("operation","preview","ships",List.copyOf(ships)));
    }
    @Tool("实际放出墨汁舰队，从玩家转移明确指定的舰船、军官、资金及物资。放出后原地待命。须先预览并取得玩家明确选择的舰船与各项划拨数量；全部资源可按实时库存换算，不擅自留余量。允许缺员或超载；实际资源不够则返回普通工具错误，不部分划拨。")
    public String dispatchMozhiFleet(@P("指定舰船实例 ID/名字列表") List<String> ships,
            @P("划拨星币") float credits,@P("划拨补给") float supplies,
            @P("划拨燃料") float fuel,@P("划拨船员人数") int crew) {
        return send(Map.of("operation","dispatch","ships",List.copyOf(ships),
                "credits",credits,"supplies",supplies,"fuel",fuel,"crew",crew));
    }
    @Tool("读取分舰队实时状态摘要：mission.id/原目标、位置、资源、计划进度及最多3个后续步骤、tradeSummary 累计实际收支。netCashflow 是现金净流入，不等于利润；不包含全部交易回执。对账时另用 getMozhiTradeReceipts 分页查明细。")
    public String getMozhiFleetStatus() { return send(Map.of("operation","status")); }

    @Tool("立即将指定星币从墨汁分舰队转给玩家，允许转走全部资金，不预留余额；成功返回转账回执，失败按普通工具错误处理。转账不是贸易收入或利润。")
    public String transferCreditsToPlayer(@P("转给玩家的星币，必须为有限正数") float amount) {
        return send(Map.of("operation", "transferToPlayer", "amount", amount));
    }

    @Tool("立即将玩家明确授权的星币转给墨汁分舰队。玩家直接要求转账时按授权金额执行，无需重复确认；若由墨汁主动提出，必须先说明金额和用途，等待玩家明确同意后才能调用，不能在询问同意的同一轮先转账。跑商委派、资金不足或后勤建议都不代表玩家授权出资；不能超出同意金额或重复使用一次授权。只检查金额有效及玩家余额充足，允许转走全部资金，不预留余额；失败按普通工具错误处理。转账不是贸易收入或利润。")
    public String transferCreditsToMozhi(@P("玩家明确要求或已经同意转出的星币，必须为有限正数") float amount) {
        return send(Map.of("operation", "transferToMozhi", "amount", amount));
    }

    @Tool("分页读取本任务已成交交易回执，保留实际支出 creditsSpent、收入 creditsReceived、含税报价 quotedTotal 和步骤参数。先从状态取得 mission.id；按 stepId 去重，勿把明细与累计汇总重复相加。offset 从0起，沿 nextOffset 翻页，-1 表示当前已读完；任务变化会报错。")
    public String getMozhiTradeReceipts(@P("状态中的 mission.id，翻页时沿用") String taskId,
            @P("从0开始的记录偏移") int offset, @P("每页1至50条，通常20条") int limit) {
        return send(Map.of("operation", "tradeReceipts", "taskId", taskId, "offset", offset, "limit", limit));
    }

    @Tool("仅在玩家明确要求立即返航或同意返航询问后使用。立即用召回计划替换当前计划，实际返回玩家附近后合并资产并完成。条件指令如赚到100万再回来必须委派原目标且设 returnAfterCompletion=true，不能使用此工具提前召回。")
    public String recallMozhiFleet() { return send(Map.of("operation","recall")); }

    @Tool("令墨汁分舰队实际前往指定星球、星系或市场；支持完整名称/ID，重名时使用 ID。必须实际进入目标环绕轨道才算完成，不会移动玩家舰队。")
    public String moveMozhiFleet(@P("目的地的完整名称或 ID") String destination) {
        return send(Map.of("operation","move","destination",destination));
    }
    @Tool("让墨汁分舰队持续跟随指定舰队，替换当前任务。与返航一样追赶目标，靠近后等待、目标移动后继续追赶，不合并资产，不因靠近而完成。可跨星系追赶；不使用游戏原生跟随任务。玩家停止或下达新任务后结束跟随，目标消失则失败。")
    public String followMozhiFleet(@P("目标舰队准确 ID 或完整名称；跟随玩家时传 player；重名时根据错误返回的候选 ID 让玩家明确目标") String targetFleet) {
        return send(Map.of("operation", "follow", "targetFleet", targetFleet));
    }
    @Tool("按玩家指令停止分舰队当前任务，包括持续跟随，并原地待命。不返航、不合并资产。")
    public String stopMozhiFleet() { return send(Map.of("operation", "cancel")); }
    @Tool("墨汁分舰队在已经环绕的指定市场购买货物或舰船。只交易，不航行或设置环绕；未在目标轨道时拒绝。去市场再购买应使用 commandMozhiFleet 安排 MOVE_TO→BUY。使用分舰队资金和交易时真实库存，不预留资金。返回接受状态；实际成交后用 getMozhiTradeReceipts 读取 creditsSpent 购买支出，不把接受命令当作成交。")
    public String buyForMozhiFleet(@P("市场或所属星球的名称/ID，不能只指定星系") String destination,
            @P("交易区名称/ID；未指定时传空字符串") String submarket,
            @P("物品、舰船名称或 ID；特殊物品可用 ID:实例数据") String item,
            @P("玩家指定的购买数量，正整数") int quantity) {
        return trade("buy",destination,submarket,item,quantity);
    }
    @Tool("墨汁分舰队在已经环绕的指定市场出售自身货物或舰船，收入归分舰队。只交易，不航行或设置环绕；去市场再出售应使用 commandMozhiFleet 安排 MOVE_TO→SELL。只卖指定数量，不出售最后一艘船。返回接受状态；实际成交后用 getMozhiTradeReceipts 读取 creditsReceived 出售收入，不把接受命令当作成交。")
    public String sellForMozhiFleet(@P("市场或所属星球的名称/ID") String destination,
            @P("交易区名称/ID；未指定时传空字符串") String submarket,
            @P("货物名称/ID；卖船建议使用舰船实例 ID") String item,
            @P("玩家指定的出售数量，正整数") int quantity) {
        return trade("sell",destination,submarket,item,quantity);
    }
    private String trade(String operation,String destination,String submarket,String item,int quantity) {
        return send(Map.of("operation",operation,"destination",destination,"submarket",submarket,"item",item,"quantity",quantity));
    }
    @Tool("将玩家完整指令交给独立舰队规划器。支持 MOVE_TO、BUY、SELL、向玩家转账 TRANSFER_TO_PLAYER、持续跟随 FOLLOW_FLEET 和决策工具 CALCULATE_TRADE_ROUTE。指定采购需保留市场、物品和数量；自主跑商由计算器生成 Plan 并插入计算步骤之后。新指令会替换当前任务，追加规则时保留原目标和约束。返航必须通过 returnAfterCompletion 单独授权，规划器不能加入 RETURN，目标验收成功后由 Agent 处理。不支持主动战斗、整备或自主发展。")
    public String commandMozhiFleet(@P("玩家的完整行动指令，包含指定交易需求或自主跑商目标及约束") String instruction,
            @P("只有玩家明确要求目标完成后返航时为 true；未提、否定、询问或含糊时为 false") boolean returnAfterCompletion) {
        return delegateToFleetAgent(instruction, returnAfterCompletion);
    }
    @Tool("将玩家的舰队任务委派给舰队子智能体。完整保留原始目的和约束，Agent 循环协调异步 Planner、Executor 和结果检查器 Monitor，失败时重规划，每 15 秒由轻量模型检查是否需要主模型重规划，仅处理剩余工作。去市场买卖先 MOVE_TO 再 BUY/SELL。自主跑商由 CALCULATE_TRADE_ROUTE 生成并插入实际计划；可指定预算、商品范围、天数和保留资源，无需玩家提前选定买卖市场或数量。返回任务 ID 和接受状态，不代表执行完成；使用 getMozhiFleetStatus 查询结果。")
    public String delegateToFleetAgent(@P("玩家完整原始目的、目的地、商品数量及其他明确约束；不得删减或擅自添加目标") String originalGoal,
            @P("玩家明确说目标完成后回来/返航才为 true；默认 false。条件返航绝不能改成立即召回") boolean returnAfterCompletion) {
        return send(Map.of("operation","order","instruction",originalGoal,"returnAfterCompletion",returnAfterCompletion));
    }
    private String send(Map<String,Object> data) {
        try {
            String payload = json.writeValueAsString(data);
            return gameThread.call(() -> FleetAgentAccess.command(payload));
        } catch (java.io.IOException error) { throw new IllegalStateException("舰队命令编码失败",error); }
    }
}
