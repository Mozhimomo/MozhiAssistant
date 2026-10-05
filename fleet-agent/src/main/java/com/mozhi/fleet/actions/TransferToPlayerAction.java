package com.mozhi.fleet.actions;

import com.mozhi.fleet.game.CreditTransfer;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import static com.mozhi.fleet.model.ExecutionResult.Status.SUCCEEDED;

/** 将分舰队星币转给玩家；步骤终态由执行器保存，防止重复扣款。 */
public final class TransferToPlayerAction implements Action {
    @Tool(name = "TRANSFER_TO_PLAYER", value = "将分舰队的星币转给玩家舰队。amount 为本次转账金额，必须为有限正数且不超过分舰队余额；允许转出全部余额，无距离或保留资金要求。不能从玩家账户取款。")
    public ExecutionResult transfer(Step step, ActionContext context,
            @P(name = "amount", value = "本次转给玩家的星币金额，不是目标余额") float amount) {
        CreditTransfer.transfer(context.fleet().getCargo(), context.player().getCargo(), amount);
        return ActionSupport.result(step, SUCCEEDED, "已向玩家转账 " + amount + " 星币");
    }
}
