package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import static com.mozhi.assistant.runtime.tools.SpecText.*;

/** 市场经济信息与实体仓库分别保留来源，避免把供给等级或玩家仓库当成商店现货。 */
final class MarketInventory {
    private MarketInventory() { }

    static String read(SectorEntityToken entity, int limit) {
        StringBuilder out = new StringBuilder();
        line(out, "地点", CampaignPlaces.label(entity));
        line(out, "读取游戏时刻", com.fs.starfarer.api.Global.getSector().getClock().getTimestamp());
        MarketAPI market = entity.getMarket();
        if (market != null) {
            section(out, "市场货物 / MarketAPI.getAllCommodities", text -> {
                int shown = 0;
                for (var commodity : market.getAllCommodities()) {
                    if (commodity.isMeta()) continue;
                    if (++shown > limit) { text.append("已达显示上限。\n"); break; }
                    line(text, "货物", commodity.getCommodity().getName() + " [" + commodity.getId() + "]");
                    line(text, "可用等级（非件数）", commodity.getAvailable());
                    line(text, "最大供给/需求等级", commodity.getMaxSupply() + " / " + commodity.getMaxDemand());
                    line(text, "经济储备量（非特定商店库存）", commodity.getStockpile());
                    line(text, "市场违禁品", commodity.isIllegal());
                }
            });
        }
        if (market != null) {
            for (var shop : market.getSubmarketsCopy()) {
                try {
                    shop.getPlugin().updateCargoPrePlayerInteraction();
                } catch (RuntimeException exception) {
                    line(out, "库存更新未完成", shop.getSpecId() + "：仍尝试读取当前 cargo，时效未确认");
                }
            }
        }
        section(out, "实体仓库 / SectorEntityToken.getCargo", text -> {
            text.append("这里列出仓库实际内容；拥有货物不等于对玩家出售，买入权限需核对具体交易区。\n");
            appendCargo(text, entity.getCargo(), limit);
        });
        if (market != null) {
            for (var shop : market.getSubmarketsCopy()) {
                section(out, "交易区 " + shop.getNameOneLine() + " [" + shop.getSpecId() + "]", text -> {
                    line(text, "免费转移/仓储", shop.getPlugin().isFreeTransfer());
                    line(text, "黑市", shop.getPlugin().isBlackMarket());
                    appendCargo(text, shop.getCargo(), limit);
                });
            }
        }
        return out.toString();
    }

    private static void appendCargo(StringBuilder out, CargoAPI cargo, int limit) {
        if (cargo == null) { out.append("此接口未返回仓库。\n"); return; }
        int shown = 0;
        for (var stack : cargo.getStacksCopy()) {
            if (stack.getSize() <= 0) continue;
            if (++shown > limit) { out.append("货物已达显示上限。\n"); break; }
            String id = String.valueOf(stack.getType());
            if (stack.isCommodityStack()) id = stack.getCommodityId();
            else if (stack.isWeaponStack()) id = stack.getWeaponSpecIfWeapon().getWeaponId();
            else if (stack.isModSpecStack()) id = stack.getHullModSpecIfHullMod().getId();
            else if (stack.isSpecialStack()) id = stack.getSpecialDataIfSpecial().getId() + ":" + stack.getSpecialDataIfSpecial().getData();
            line(out, stack.getDisplayName() + " [" + id + "]", stack.getSize());
        }
        if (cargo.getMothballedShips() == null) return;
        shown = 0;
        for (var ship : cargo.getMothballedShips().getMembersListCopy()) {
            if (++shown > limit) { out.append("舰船已达显示上限。\n"); break; }
            line(out, "仓库舰船", ship.getShipName() + " / " + ship.getHullSpec().getHullName()
                    + " [hull=" + ship.getHullSpec().getHullId() + ", instance=" + ship.getId() + "]");
        }
    }
}
