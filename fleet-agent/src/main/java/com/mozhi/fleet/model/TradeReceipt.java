package com.mozhi.fleet.model;

/** 已成功结算的信用点；实际收支按游戏 float 余额差计算，报价包含关税。 */
public record TradeReceipt(double creditsSpent, double creditsReceived, double quotedTotal) {
    public TradeReceipt {
        if (!Double.isFinite(creditsSpent) || creditsSpent < 0
                || !Double.isFinite(creditsReceived) || creditsReceived < 0
                || !Double.isFinite(quotedTotal) || quotedTotal < 0
                || creditsSpent > 0 && creditsReceived > 0)
            throw new IllegalArgumentException("交易收支金额无效");
    }
}
