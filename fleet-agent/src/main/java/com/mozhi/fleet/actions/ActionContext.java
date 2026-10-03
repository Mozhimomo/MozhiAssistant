package com.mozhi.fleet.actions;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import java.util.Objects;

/** 仅供主线程动作使用；不能传入 Planner 的快照或写入 JSON 存档。 */
public record ActionContext(SectorAPI sector, CampaignFleetAPI fleet, SettingsAPI settings, FactoryAPI factory) {
    public ActionContext {
        Objects.requireNonNull(sector, "星区");
        Objects.requireNonNull(fleet, "受控舰队");
        Objects.requireNonNull(settings, "游戏设置");
        Objects.requireNonNull(factory, "游戏工厂");
    }

    public static ActionContext forFleet(CampaignFleetAPI fleet) {
        return new ActionContext(Global.getSector(), fleet, Global.getSettings(), Global.getFactory());
    }

    public CampaignFleetAPI player() {
        return Objects.requireNonNull(sector.getPlayerFleet(), "玩家舰队不存在");
    }
}
