package com.mozhi.fleet.game;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.mozhi.fleet.actions.AssetTransaction;

/** 主线程即时转账；正常转账只校验前提，不读取或比对转账后的余额。 */
public final class CreditTransfer {
    private CreditTransfer() {}

    public static void transfer(CargoAPI source, CargoAPI target, float amount) {
        if (source == null || target == null || source == target)
            throw new IllegalArgumentException("转账双方必须是不同的有效账户");
        if (!Float.isFinite(amount) || amount <= 0)
            throw new IllegalArgumentException("转账金额必须为有限正数");
        var debit = source.getCredits();
        var credit = target.getCredits();
        if (debit == credit) throw new IllegalArgumentException("转账双方不能共用账户");
        float from = debit.get(), to = credit.get();
        if (!Float.isFinite(from) || from < 0 || !Float.isFinite(to) || to < 0 || !Float.isFinite(to + amount))
            throw new IllegalArgumentException("账户余额无效或转入后数值溢出");
        if (from < amount) throw new IllegalArgumentException("付款方星币不足：请求 " + amount + "，余额 " + from);
        var transaction = AssetTransaction.trade();
        // 仅当游戏 API 抛出异常时恢复，不将正常转账后的余额变化作为失败条件。
        transaction.onRollback(() -> debit.set(from));
        transaction.onRollback(() -> credit.set(to));
        try {
            debit.set(from - amount);
            credit.set(to + amount);
        } catch (RuntimeException error) {
            throw transaction.rollback(error);
        }
    }
}
