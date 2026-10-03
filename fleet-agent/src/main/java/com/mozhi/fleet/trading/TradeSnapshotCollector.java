package com.mozhi.fleet.trading;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.ui.HintPanelAPI;
import com.fs.starfarer.api.util.Misc;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.actions.CommodityPricing;
import com.mozhi.fleet.game.GameWorld;
import java.util.*;
import static com.mozhi.fleet.trading.TradeSnapshot.*;
import static com.fs.starfarer.api.campaign.SubmarketPlugin.TransferAction.*;

/** 主线程分帧采集；一次报价任务最多读取一个数量的买卖价格。 */
public final class TradeSnapshotCollector {
    private final Thread owner = Thread.currentThread();
    private final ActionContext context;
    private final TradeRouteOptions options;
    private final Fleet initial;
    private final Deque<Runnable> jobs = new ArrayDeque<>();
    private final List<MarketRow> markets = new ArrayList<>();
    private final Set<CargoAPI> seenCargo = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<CommoditySpecAPI> goods;
    private int skipped;
    private record MarketRow(MarketAPI api, String id, String name, String destination, Point point, List<Quote> quotes) {}

    public TradeSnapshotCollector(ActionContext context, TradeRouteOptions options) {
        this.context = context; this.options = options; this.initial = captureFleet(context);
        goods = context.settings().getAllCommoditySpecs().stream().filter(spec -> !spec.isNonEcon())
                .filter(spec -> options.commodityIds().isEmpty() || options.commodityIds().contains(spec.getId()))
                .sorted(Comparator.comparing(CommoditySpecAPI::getId)).toList();
        if (!options.commodityIds().isEmpty() && !goods.stream().map(CommoditySpecAPI::getId).toList().containsAll(options.commodityIds()))
            throw new IllegalArgumentException("commodityIds 包含未知或非经济商品");
        GameWorld.markets(context.sector()).stream().sorted(Comparator.comparing(MarketAPI::getId))
                .forEach(market -> jobs.add(() -> market(market)));
    }
    public boolean advance() {
        requireOwner();
        long deadline = System.nanoTime() + 3_000_000L;
        int operations = 0;
        while (!jobs.isEmpty() && operations++ < 32 && System.nanoTime() < deadline) {
            try { jobs.removeFirst().run(); }
            catch (RuntimeException unavailable) { skipped++; }
        }
        return jobs.isEmpty();
    }
    public TradeSnapshot snapshot() {
        requireOwner();
        if (!jobs.isEmpty()) throw new IllegalStateException("市场快照尚未完成");
        return new TradeSnapshot(captureFleet(context), markets.stream().filter(row -> !row.quotes.isEmpty())
                .map(row -> new Market(row.id, row.name, row.destination, row.point, row.quotes)).toList(), skipped);
    }
    public String progress() { return "正在采集市场整批报价，已读取 " + markets.size() + " 个市场"; }
    private void market(MarketAPI market) {
        var target = market.getPrimaryEntity();
        if (market.isHidden() || target == null || target.isExpired() || target.getContainingLocation() == null
                || market.getFaction().isHostileTo(context.sector().getPlayerFaction())) { skipped++; return; }
        MarketRow row = new MarketRow(market, market.getId(), market.getName(), target.getId(), point(target), new ArrayList<>());
        markets.add(row);
        market.getSubmarketsCopy().stream().sorted(Comparator.comparing(SubmarketAPI::getSpecId))
                .forEach(shop -> jobs.add(() -> shop(row, shop)));
    }
    private void shop(MarketRow row, SubmarketAPI shop) {
        var plugin = shop.getPlugin();
        if (!canTrade(shop, options.allowBlackMarket())) { skipped++; return; }
        plugin.updateCargoPrePlayerInteraction();
        var cargo = shop.getCargo();
        if (cargo == null || cargo == context.fleet().getCargo() || !seenCargo.add(cargo)) { skipped++; return; }
        for (CommoditySpecAPI spec : goods) jobs.add(() -> commodity(row, shop, cargo, spec));
    }
    /** 主线程读取，供跑商与后勤采购统一过滤交易渠道。 */
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
    private void commodity(MarketRow row, SubmarketAPI shop, CargoAPI cargo, CommoditySpecAPI spec) {
        Hold hold = spec.isFuel() ? Hold.FUEL : spec.isPersonnel() ? Hold.PERSONNEL : Hold.CARGO;
        double space = hold == Hold.CARGO ? spec.getCargoSpace() : 1;
        if (!Double.isFinite(space) || space <= 0) return;
        int cap = (int) Math.min(1_000_000, Math.floor(initial.room(hold) / space));
        if (cap < 1) return;
        var plugin = shop.getPlugin();
        boolean buy = !plugin.isIllegalOnSubmarket(spec.getId(), PLAYER_BUY);
        boolean sell = !plugin.isIllegalOnSubmarket(spec.getId(), PLAYER_SELL);
        if (!buy && !sell) return;
        double stock = cargo.getCommodityQuantity(spec.getId());
        if (!Double.isFinite(stock)) throw new IllegalStateException("商品库存无效");
        List<Integer> quantities = TradeSnapshot.quantities(cap);
        class Prices implements Runnable {
            int index;
            final Map<Integer, Double> buys = new LinkedHashMap<>(), sells = new LinkedHashMap<>();
            public void run() {
                int q = quantities.get(index++);
                if (buy && q <= Math.floor(stock)) buys.put(q, CommodityPricing.quote(row.api, shop, spec.getId(), q, true));
                if (sell) sells.put(q, CommodityPricing.quote(row.api, shop, spec.getId(), q, false));
                if (index < quantities.size()) jobs.addFirst(this);
                else if (!buys.isEmpty() || !sells.isEmpty()) row.quotes.add(new Quote(spec.getId(), spec.getName(), hold, space, shop.getSpecId(), buys, sells));
            }
        }
        jobs.addFirst(new Prices());
    }
    public static Fleet captureFleet(ActionContext context) {
        var fleet = context.fleet(); var cargo = fleet.getCargo(); var logistics = fleet.getLogistics();
        double speed = fleet.getFleetData().getTravelSpeed();
        if (speed <= 0) speed = context.settings().getSpeedPerBurnLevel() * Math.max(1, fleet.getFleetData().getBurnLevel());
        return new Fleet(point(fleet), cargo.getCredits().get(), Math.max(0, cargo.getSpaceLeft()), Math.max(0, cargo.getFreeFuelSpace()),
                Math.max(0, cargo.getFreeCrewSpace()), cargo.getFuel(), cargo.getSupplies(), logistics.getFuelCostPerLightYear(),
                logistics.getTotalSuppliesPerDay(), Misc.getLYPerDayAtBurn(fleet, Math.max(1, fleet.getFleetData().getBurnLevel())),
                speed * context.sector().getClock().getSecondsPerDay());
    }
    public static Point point(SectorEntityToken entity) {
        var location = Objects.requireNonNull(entity.getContainingLocation());
        var local = entity.getLocation(); var hyper = entity.getLocationInHyperspace();
        double jumpDistance = 0;
        if (!location.isHyperspace()) {
            var jump = Misc.findNearestJumpPointTo(entity);
            if (jump == null) jumpDistance = Math.hypot(local.x, local.y) + 2000;
            else jumpDistance = Math.hypot(local.x - jump.getLocation().x, local.y - jump.getLocation().y);
        }
        double scale = Misc.getUnitsPerLightYear();
        TradeRouteOptions.positive(scale, "光年单位");
        return new Point(location.getId(), location.isHyperspace(), local.x, local.y, hyper.x / scale, hyper.y / scale, jumpDistance);
    }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("市场数据只能在游戏主线程读取"); }
}
