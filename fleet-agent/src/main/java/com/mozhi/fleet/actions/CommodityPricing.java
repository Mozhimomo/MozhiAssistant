package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;

/** 计算器和交易执行器共享报价口径：整批总价、交易区关税、最后取整。 */
public final class CommodityPricing {
    private CommodityPricing() {}
    public static double quote(MarketAPI market, SubmarketAPI shop, String commodity, int quantity, boolean buy) {
        if (quantity <= 0) throw new IllegalArgumentException("交易数量必须为正整数");
        double price = buy ? market.getSupplyPrice(commodity, quantity, true) : market.getDemandPrice(commodity, quantity, true);
        double tariff = shop.getTariff();
        if (!Double.isFinite(price) || price < 0 || !Double.isFinite(tariff) || tariff < 0 || tariff > 1)
            throw new IllegalStateException("报价或关税无效");
        double total = price * (buy ? 1 + tariff : 1 - tariff);
        if (!Double.isFinite(total) || total > Integer.MAX_VALUE) throw new IllegalStateException("交易金额过大");
        return Math.round(total);
    }
}
