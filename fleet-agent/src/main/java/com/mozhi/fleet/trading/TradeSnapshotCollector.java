package com.mozhi.fleet.trading;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.impl.campaign.submarkets.OpenMarketPlugin;
import com.fs.starfarer.api.ui.HintPanelAPI;
import com.fs.starfarer.api.util.Misc;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.actions.CommodityPricing;
import java.util.*;
import static com.mozhi.fleet.trading.TradeSnapshot.*;
import static com.fs.starfarer.api.campaign.SubmarketPlugin.TransferAction.*;

/** 只采集经济供需和估算货架；不会刷新远方市场，也不会预先读取整批价格。 */
public final class TradeSnapshotCollector {
    public static final Set<String> TRADE_GOODS = Set.of("supplies", "fuel", "food", "organics", "volatiles", "ore",
            "rare_ore", "metals", "rare_metals", "heavy_machinery", "domestic_goods", "organs", "drugs",
            "hand_weapons", "luxury_goods", "lobster");
    private final Thread owner = Thread.currentThread();
    private final ActionContext context;
    private final TradeRouteOptions options;
    private final Deque<MarketAPI> pending;
    private final Map<String, MarketAPI> apis = new LinkedHashMap<>();
    private final List<Market> markets = new ArrayList<>();
    private int skipped;

    public TradeSnapshotCollector(ActionContext context, TradeRouteOptions options) {
        this.context = context; this.options = options;
        for (String id : options.commodityIds()) {
            var spec = context.settings().getCommoditySpec(id);
            if (!TRADE_GOODS.contains(id) || spec == null || spec.isNonEcon() || spec.isPersonnel() || spec.isMeta())
                throw new IllegalArgumentException("commodityIds 只支持跑商经济商品，不包含人员或未知商品：" + id);
        }
        pending = new ArrayDeque<>(context.sector().getEconomy().getMarketsCopy().stream()
                .sorted(Comparator.comparing(MarketAPI::getId)).toList());
    }
    public boolean advance() {
        requireOwner();
        long end = System.nanoTime() + 4_000_000L;
        do {
            if (pending.isEmpty()) break;
            MarketAPI api = pending.removeFirst();
            try {
                Market row = read(api, false);
                if (row != null && !row.quotes().isEmpty()) { markets.add(row); apis.put(api.getId(), api); }
                else skipped++;
            } catch (RuntimeException unavailable) { skipped++; }
        } while (System.nanoTime() < end);
        return pending.isEmpty();
    }
    public TradeSnapshot snapshot() {
        requireOwner();
        if (!pending.isEmpty()) throw new IllegalStateException("市场快照尚未完成");
        return new TradeSnapshot(captureFleet(context), markets, skipped);
    }
    public String progress() { return "正在读取市场供需：已筛选 " + (markets.size() + skipped) + " 个，剩余 " + pending.size() + " 个"; }
    public TradeQuotes prices() {
        return (market, item, quantity, buy) -> {
            requireOwner();
            MarketAPI api = apis.get(market.id());
            if (api == null) return -1;
            SubmarketAPI shop = api.getSubmarket(item.submarketId());
            if (shop == null) return -1;
            try { return CommodityPricing.quote(api, shop, item.commodityId(), quantity, buy); }
            catch (RuntimeException unavailable) { return -1; }
        };
    }
    /** 到站只打开当前市场货架，目的地依然读取轻量估算。 */
    public TradeSnapshot liveHop(String sourceId, String destinationId) {
        requireOwner();
        List<Market> rows = new ArrayList<>();
        for (String id : List.of(sourceId, destinationId)) {
            MarketAPI api = context.sector().getEconomy().getMarketsCopy().stream()
                    .filter(m -> id.equals(m.getId())).findFirst().orElseThrow(() -> new IllegalArgumentException("跑商市场不存在：" + id));
            Market row = read(api, id.equals(sourceId));
            if (row == null || row.quotes().isEmpty()) throw new IllegalStateException("市场当前无法交易：" + api.getName());
            apis.put(id, api); rows.add(row);
        }
        return new TradeSnapshot(captureFleet(context), rows, 0);
    }
    private Market read(MarketAPI api, boolean live) {
        var target = api.getPrimaryEntity();
        if (!api.isInEconomy() || api.isHidden() || api.isPlanetConditionMarketOnly() || !api.hasSpaceport()
                || target == null || target.isExpired() || target.getContainingLocation() == null) return null;
        double closed = closedDays(api);
        if (closed > options.maxDays() || live && closed > 0.01) return null;
        List<Quote> items = new ArrayList<>();
        for (String channel : List.of("black_market", "open_market")) {
            boolean black = channel.equals("black_market");
            SubmarketAPI shop = api.getSubmarket(channel);
            if (shop == null || black && !options.allowBlackMarket() || !canTrade(shop, options.allowBlackMarket())) continue;
            boolean hostile = api.getFaction() != null && api.getFaction().isHostileTo(context.sector().getPlayerFaction());
            if (!black && hostile && !context.fleet().isTransponderOn()) continue;
            CargoAPI shelf = null;
            if (live) {
                shop.getPlugin().updateCargoPrePlayerInteraction();
                shelf = shop.getCargo();
                if (shelf == context.fleet().getCargo()) throw new IllegalStateException("市场货架错误地指向分舰队");
            }
            for (CommodityOnMarketAPI commodity : api.getAllCommodities()) {
                var spec = commodity.getCommodity();
                if (spec == null || !TRADE_GOODS.contains(spec.getId()) || spec.isNonEcon() || spec.isMeta() || spec.isPersonnel()) continue;
                String id = spec.getId();
                // 限定交易商品时仍保留燃料和补给，用于本段后勤采购。
                if (!options.commodityIds().isEmpty() && !options.commodityIds().contains(id) && !id.equals("fuel") && !id.equals("supplies")) continue;
                double space = spec.isFuel() ? 1 : spec.getCargoSpace();
                if (!Double.isFinite(space) || space <= 0) continue;
                boolean buy = !shop.getPlugin().isIllegalOnSubmarket(id, PLAYER_BUY) && (black || !api.isIllegal(id));
                boolean sell = !shop.getPlugin().isIllegalOnSubmarket(id, PLAYER_SELL) && (black || !api.isIllegal(id));
                if (!buy && !sell) continue;
                int cap = live ? shelf == null ? 0 : bounded(shelf.getCommodityQuantity(id)) : estimate(api, shop, commodity, black);
                items.add(new Quote(id, spec.getName(), spec.isFuel() ? Hold.FUEL : Hold.CARGO, space, channel,
                        Map.of(), Map.of(), buy ? cap : 0, spec.getEconUnit(), Math.max(0, commodity.getExcessQuantity()),
                        Math.max(0, commodity.getDeficitQuantity()), buy, sell));
            }
        }
        items.sort(Comparator.comparing(Quote::commodityId).thenComparing(Quote::submarketId));
        return new Market(api.getId(), api.getName(), target.getId(), point(target), items, closed);
    }
    private static int bounded(double quantity) {
        return Double.isFinite(quantity) ? (int) Math.max(0, Math.min(1_000_000, Math.floor(quantity))) : 0;
    }
    private static int estimate(MarketAPI market, SubmarketAPI shop, CommodityOnMarketAPI commodity, boolean black) {
        double stability = Math.max(0, Math.min(1, market.getStabilityValue() / 10d));
        double limit = Math.floor(Math.max(0, OpenMarketPlugin.getApproximateStockpileLimit(commodity))
                * (0.25 + 0.75 * (black ? 1 - stability : stability)));
        double estimate = limit;
        if (shop.getPlugin() instanceof BaseSubmarketPlugin plugin && plugin.getCargoNullOk() != null)
            estimate = project(plugin.getCargoNullOk().getCommodityQuantity(commodity.getId()), limit, plugin.getSinceLastCargoUpdate());
        return bounded(estimate * 0.9);
    }
    static double project(double current, double limit, double days) {
        current = Math.max(0, current); days = Math.max(0, days);
        return current < limit ? Math.min(limit, current + limit * days / 30)
                : Math.max(limit, current - (current - limit) * 2 * days / 30);
    }
    private static double closedDays(MarketAPI market) {
        var memory = market.getMemoryWithoutUpdate();
        if (memory == null || !memory.contains(MemFlags.MEMORY_KEY_PLAYER_HOSTILE_ACTIVITY_NEAR_MARKET)) return 0;
        double days = memory.getExpire(MemFlags.MEMORY_KEY_PLAYER_HOSTILE_ACTIVITY_NEAR_MARKET);
        return days < 0 ? Double.MAX_VALUE : days;
    }
    /** 交易权限由游戏插件决定，不添加舰队资源保留限制。 */
    public static boolean canTrade(SubmarketAPI shop, boolean allowBlackMarket) {
        var plugin = shop.getPlugin();
        if (plugin == null || plugin.isHidden() || plugin.isFreeTransfer() || !plugin.isParticipatesInEconomy()
                || plugin.isBlackMarket() && !allowBlackMarket) return false;
        CoreUIAPI ui = new CoreUIAPI() {
            public CampaignUIAPI.CoreUITradeMode getTradeMode() { return CampaignUIAPI.CoreUITradeMode.OPEN; }
            public HintPanelAPI getHintPanel() { throw new UnsupportedOperationException("跑商计算没有交易界面"); }
        };
        return plugin.isEnabled(ui) && plugin.getOnClickAction(ui) == SubmarketPlugin.OnClickAction.OPEN_SUBMARKET;
    }
    public static Fleet captureFleet(ActionContext context) {
        var fleet = context.fleet(); var cargo = fleet.getCargo(); var logistics = fleet.getLogistics();
        double speed = fleet.getFleetData().getTravelSpeed();
        if (speed <= 0) speed = context.settings().getSpeedPerBurnLevel() * Math.max(1, fleet.getFleetData().getBurnLevel());
        return new Fleet(point(fleet), cargo.getCredits().get(), Math.max(0, cargo.getSpaceLeft()), Math.max(0, cargo.getFreeFuelSpace()),
                Math.max(0, cargo.getFreeCrewSpace()), cargo.getFuel(), cargo.getSupplies(), logistics.getFuelCostPerLightYear(),
                logistics.getTotalSuppliesPerDay(), Misc.getLYPerDayAtBurn(fleet, Math.max(1, fleet.getFleetData().getBurnLevel())),
                speed * context.sector().getClock().getSecondsPerDay(), context.settings().getCommoditySpec("supplies").getCargoSpace());
    }
    public static Point point(SectorEntityToken entity) {
        var location = Objects.requireNonNull(entity.getContainingLocation());
        var local = entity.getLocation(); var hyper = entity.getLocationInHyperspace();
        double jumpDistance = 0;
        if (!location.isHyperspace()) {
            var jump = Misc.findNearestJumpPointTo(entity);
            if (jump != null) jumpDistance = Math.hypot(local.x - jump.getLocation().x, local.y - jump.getLocation().y);
        }
        double scale = Misc.getUnitsPerLightYear();
        TradeRouteOptions.positive(scale, "光年单位");
        return new Point(location.getId(), location.isHyperspace(), local.x, local.y, hyper.x / scale, hyper.y / scale, jumpDistance);
    }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("市场数据只能在游戏主线程读取"); }
}
