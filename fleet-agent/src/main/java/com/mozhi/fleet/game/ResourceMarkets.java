package com.mozhi.fleet.game;

import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.actions.CommodityPricing;
import com.mozhi.fleet.model.ResourceCheck;
import com.mozhi.fleet.execution.Monitor;
import com.mozhi.fleet.trading.TradeSnapshot;
import com.mozhi.fleet.trading.TradeSnapshotCollector;
import java.util.*;
import static com.fs.starfarer.api.campaign.SubmarketPlugin.TransferAction.PLAYER_BUY;

/** 只在资源短缺的规划请求中采集采购渠道，供 Planner 选择真实可达的市场。 */
public final class ResourceMarkets {
    private ResourceMarkets() {}
    public static Map<String, Object> collect(ActionContext context, ResourceCheck check) {
        var fleet = TradeSnapshotCollector.captureFleet(context);
        var geometry = new TradeSnapshot(fleet, List.of(), 0);
        var r = check.snapshot();
        List<Map<String, Object>> rows = new ArrayList<>();
        int skipped = 0;
        for (var market : GameWorld.markets(context.sector())) {
            try {
                var target = market.getPrimaryEntity();
                if (market.isHidden() || target == null || target.isExpired() || target.getContainingLocation() == null
                        || market.getFaction().isHostileTo(context.sector().getPlayerFaction())) continue;
                var trip = geometry.travel(fleet.position(), TradeSnapshotCollector.point(target));
                // 已在目标轨道时可立即购买，无需额外停靠旅时或消耗。
                boolean docked = context.fleet().getOrbit() != null && context.fleet().getOrbit().getFocus() == target;
                double fuel = docked ? 0 : trip.fuel(), supplies = docked ? 0 : trip.supplies();
                if (fuel > r.fuel() || supplies > r.supplies()) continue;
                for (var shop : market.getSubmarketsCopy()) {
                    try {
                        if (!TradeSnapshotCollector.canTrade(shop, true)) continue;
                        shop.getPlugin().updateCargoPrePlayerInteraction();
                        var cargo = shop.getCargo();
                        if (cargo == null || cargo == context.fleet().getCargo()) continue;
                        List<Map<String, Object>> goods = new ArrayList<>();
                        for (String id : List.of("fuel", "supplies", "crew")) {
                            if (shop.getPlugin().isIllegalOnSubmarket(id, PLAYER_BUY)) continue;
                            float available = cargo.getCommodityQuantity(id);
                            if (!Float.isFinite(available) || available < 1) continue;
                            double targetAmount = check.targets().getOrDefault(id, switch (id) {
                                case "fuel" -> r.fuelCapacity();
                                case "supplies" -> Monitor.REFILL_SUPPLY_DAYS * r.suppliesPerDay();
                                default -> Math.max(r.minimumCrew() + 1, Math.ceil(r.minimumCrew() * Monitor.CREW_BUFFER));
                            });
                            double wanted = switch (id) {
                                case "fuel" -> targetAmount - r.fuel() + fuel;
                                case "supplies" -> targetAmount - r.supplies() + supplies;
                                default -> targetAmount - r.crew();
                            };
                            double room = switch (id) {
                                case "fuel" -> fleet.fuelRoom() + fuel;
                                case "crew" -> fleet.personnelRoom();
                                default -> {
                                    double space = context.settings().getCommoditySpec(id).getCargoSpace();
                                    if (!Double.isFinite(space) || space <= 0) throw new IllegalStateException("补给占用空间无效");
                                    yield (fleet.cargoRoom() + supplies * space) / space;
                                }
                            };
                            int quantity = (int) Math.min(1_000_000, Math.min(Math.floor(available), Math.min(Math.floor(room), Math.ceil(Math.max(0, wanted)))));
                            if (quantity <= 0) continue;
                            int minimumNeeded = (int) Math.ceil(Math.max(0, wanted));
                            int minimumQuoted = Math.min(quantity, minimumNeeded);
                            goods.add(Map.of("itemId", id, "available", available, "quotedQuantity", quantity,
                                    "totalPrice", CommodityPricing.quote(market, shop, id, quantity, true),
                                    "targetNeededOnArrival", minimumNeeded, "targetQuotedQuantity", minimumQuoted,
                                    "targetTotalPrice", minimumQuoted == 0 ? 0 : CommodityPricing.quote(market, shop, id, minimumQuoted, true)));
                        }
                        if (goods.stream().noneMatch(good -> check.purchases().containsKey(good.get("itemId")))) continue;
                        rows.add(Map.of("marketId", market.getId(), "destinationId", target.getId(), "name", market.getName(),
                                "submarketId", shop.getSpecId(), "blackMarket", shop.getPlugin().isBlackMarket(),
                                "distanceLy", trip.lightYears(), "estimatedDays", docked ? 0 : trip.days(),
                                "fuelToReach", fuel, "suppliesToReach", supplies, "goods", goods));
                    } catch (RuntimeException unavailable) { skipped++; }
                }
            } catch (RuntimeException unavailable) { skipped++; }
        }
        rows.sort(Comparator.comparingDouble(row -> ((Number) row.get("estimatedDays")).doubleValue()));
        return Map.of("candidates", List.copyOf(rows.subList(0, Math.min(24, rows.size()))), "matchingChannels", rows.size(),
                "skipped", skipped, "scope", "全星区可达采购渠道，按预计旅时展示最近 24 个；报价数量受现货和容量限制，资金及缺口需逐项核对；含黑市");
    }
}
