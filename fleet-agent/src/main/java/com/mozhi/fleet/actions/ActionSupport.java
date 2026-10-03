package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;
import java.math.BigDecimal;
import java.util.LinkedHashSet;

final class ActionSupport {
    private ActionSupport() {}

    static ActionSpec.Parameter parameter(String name, ActionSpec.Type type, boolean required, String description) {
        return new ActionSpec.Parameter(name, type, required, description);
    }

    static String text(Step step, String key) {
        Object value = step.parameters().get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("参数不能为空：" + key);
        return text;
    }

    static int quantity(Step step) {
        Object raw = step.parameters().get("quantity");
        if (!(raw instanceof Number)) throw new IllegalArgumentException("quantity 必须是整数");
        int quantity;
        try { quantity = new BigDecimal(raw.toString()).intValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("quantity 必须是有效整数", error); }
        if (quantity <= 0 || quantity > 1_000_000) throw new IllegalArgumentException("quantity 必须在 1 至 1000000 之间");
        return quantity;
    }

    static ExecutionResult result(Step step, ExecutionResult.Status status, String message) {
        return new ExecutionResult(step, status, message);
    }

    static MarketAPI market(ActionContext context, String id) {
        var markets = new LinkedHashSet<MarketAPI>(context.sector().getEconomy().getMarketsCopy());
        for (var location : context.sector().getAllLocations()) {
            for (var entity : location.getAllEntities()) {
                if (entity.getMarket() != null) markets.add(entity.getMarket());
            }
        }
        MarketAPI found = null;
        for (MarketAPI market : markets) {
            if (!id.equals(market.getId())) continue;
            if (found != null && found != market) throw new IllegalArgumentException("市场 ID 不唯一：" + id);
            found = market;
        }
        if (found == null || found.isPlanetConditionMarketOnly()) throw new IllegalArgumentException("可交易市场不存在：" + id);
        return found;
    }

    static SectorEntityToken marketEntity(MarketAPI market) {
        SectorEntityToken primary = market.getPrimaryEntity();
        if (primary != null && !primary.isExpired()) return primary;
        if (market.getConnectedEntities() != null) {
            for (var entity : market.getConnectedEntities()) if (!entity.isExpired()) return entity;
        }
        throw new IllegalArgumentException("市场没有有效实体：" + market.getId());
    }

    static SectorEntityToken destination(ActionContext context, String id) {
        SectorEntityToken entity = context.sector().getEntityById(id);
        if (entity != null && !entity.isExpired()) return entity;
        for (var market : context.sector().getEconomy().getMarketsCopy()) {
            if (id.equals(market.getId())) return marketEntity(market);
        }
        for (var system : context.sector().getStarSystems()) {
            if (id.equals(system.getId())) {
                SectorEntityToken center = system.getCenter() != null ? system.getCenter() : system.getStar();
                if (center != null && !center.isExpired()) return center;
            }
        }
        // 部分模组市场只绑定在实体上，不在经济列表中。
        for (var location : context.sector().getAllLocations()) {
            for (var target : location.getAllEntities()) {
                if (target.getMarket() != null && id.equals(target.getMarket().getId())) return marketEntity(target.getMarket());
            }
        }
        throw new IllegalArgumentException("目的地 ID 不存在：" + id);
    }

    static boolean near(SectorEntityToken fleet, SectorEntityToken target) {
        return fleet.getContainingLocation() != null && fleet.getContainingLocation() == target.getContainingLocation()
                && Math.hypot(fleet.getLocation().x - target.getLocation().x, fleet.getLocation().y - target.getLocation().y)
                < Math.max(350, fleet.getRadius() + target.getRadius() + 150);
    }

    static boolean orbiting(CampaignFleetAPI fleet, SectorEntityToken target) {
        var assignment = fleet.getAI() == null ? null : fleet.getAI().getCurrentAssignment();
        return fleet.getContainingLocation() != null && fleet.getContainingLocation() == target.getContainingLocation()
                && fleet.getOrbit() != null && fleet.getOrbit().getFocus() == target
                && assignment != null && assignment.getAssignment() == FleetAssignment.ORBIT_PASSIVE
                && assignment.getTarget() == target;
    }

    static void assign(CampaignFleetAPI fleet, FleetAssignment action, SectorEntityToken target, String description) {
        if (fleet.getAI() == null) throw new IllegalStateException("受控舰队没有任务 AI");
        var current = fleet.getAI().getCurrentAssignment();
        if (current != null && current.getAssignment() == action && current.getTarget() == target) return;
        fleet.clearAssignments();
        fleet.addAssignment(action, target, 100000f, description);
    }

    static void hold(ActionContext context) {
        CampaignFleetAPI fleet = context.fleet();
        if (fleet.isExpired() || fleet.getBattle() != null || fleet.isInHyperspaceTransition()) return;
        if (fleet.getOrbit() != null && fleet.getOrbit().getFocus() != null) {
            assign(fleet, FleetAssignment.ORBIT_PASSIVE, fleet.getOrbit().getFocus(), "等待下一步");
        } else assign(fleet, FleetAssignment.HOLD, fleet, "等待下一步");
    }
}
