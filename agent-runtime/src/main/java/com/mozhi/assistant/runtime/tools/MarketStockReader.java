package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CoreUIAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.SubmarketPlugin.TransferAction;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.HintPanelAPI;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mozhi.assistant.runtime.tools.ShoppingPlanner.*;

/** 一次工具调用创建一个读取器；不跨调用保存市场、CargoAPI、舰船或查询结果。 */
final class MarketStockReader {
    private static final CoreUIAPI OPEN_TRADE = new CoreUIAPI() {
        public CoreUITradeMode getTradeMode() { return CoreUITradeMode.OPEN; }
        public HintPanelAPI getHintPanel() { throw new UnsupportedOperationException("远程查询没有交易界面"); }
    };

    private final Set<Item> wanted;
    private final boolean includeBlackMarket;
    private final List<String> warnings = new ArrayList<>();
    // 仅用于单次扫描内去重：多个实体/交易区可能指向同一个实时仓库。
    private final Map<CargoAPI, Map<String, Offer>> inventories = new IdentityHashMap<>();
    private final List<CargoAPI> cargoOrder = new ArrayList<>();
    private int shopsAttempted;
    private int shopsUpdated;
    private int shopsRead;
    private int entityStoresRead;
    private int shipEntriesRead;

    MarketStockReader(Set<Item> wanted, boolean includeBlackMarket) {
        this.wanted = wanted;
        this.includeBlackMarket = includeBlackMarket;
    }

    Scan scan() {
        CampaignPlaces.requireCampaign();
        var clock = Global.getSector().getClock();
        String time = clock.getDateString() + " [timestamp=" + clock.getTimestamp() + "]";
        List<MarketAPI> markets = CampaignPlaces.allMarkets(warnings);
        Set<String> locations = new LinkedHashSet<>();
        for (MarketAPI market : markets) {
            try {
                if (market.getContainingLocation() != null) locations.add(market.getContainingLocation().getId());
                readMarket(market);
            } catch (RuntimeException exception) {
                warnings.add(market.getId() + "：市场读取不完整（" + exception.getClass().getSimpleName() + "）。");
            }
        }
        List<Offer> offers = new ArrayList<>();
        for (CargoAPI cargo : cargoOrder) offers.addAll(inventories.get(cargo).values());
        String coverage = "搜索范围：当前战役全部市场，包括经济系统和实体绑定市场；不按当前星系、访问记录、隐藏或敌对状态过滤。\n"
                + "读取游戏时刻：" + time + "。\n"
                + "遍历市场：" + markets.size() + "；涉及星系/位置：" + locations.size()
                + "；交易区尝试读取：" + shopsAttempted + "；常规更新成功：" + shopsUpdated
                + "；交易区完整读取：" + shopsRead + "；实体仓库完整读取：" + entityStoresRead
                + "；检查舰船条目（去重前）：" + shipEntriesRead + "。\n"
                + "黑市库存始终查询；本次自动路线" + (includeBlackMarket ? "允许" : "不允许") + "黑市。\n";
        return new Scan(offers, warnings, coverage);
    }

    private void readMarket(MarketAPI market) {
        try {
            // 先完成本市场交易区的常规更新，再读取仓库；不设置刷新计时或强制重掷库存。
            List<SubmarketAPI> shops = market.getSubmarketsCopy();
            Map<SubmarketAPI, String> updateFailures = new IdentityHashMap<>();
            for (SubmarketAPI shop : shops) {
                shopsAttempted++;
                try {
                    shop.getPlugin().updateCargoPrePlayerInteraction();
                    shopsUpdated++;
                } catch (RuntimeException exception) {
                    updateFailures.put(shop, "库存更新失败，时效未确认");
                    warnings.add(market.getId() + "/" + shop.getSpecId() + "：常规更新失败，仍尝试读取现有货物（"
                            + exception.getClass().getSimpleName() + "）。");
                }
            }
            for (SubmarketAPI shop : shops) readShop(market, shop, updateFailures.getOrDefault(shop, ""));
        } catch (RuntimeException exception) {
            warnings.add(market.getId() + "：交易区列表读取失败，继续读取实体仓库。");
        }
        try {
            for (var entity : CampaignPlaces.marketEntities(market)) {
                try {
                    CargoAPI cargo = entity.getCargo();
                    if (cargo != null && readCargo(market, cargo, "实体仓库 " + CampaignPlaces.label(entity),
                            null, "实体仓库；未确认出售权限", Float.NaN)) entityStoresRead++;
                } catch (RuntimeException exception) {
                    warnings.add(entity.getId() + "：实体仓库读取失败（" + exception.getClass().getSimpleName() + "）。");
                }
            }
        } catch (RuntimeException exception) {
            warnings.add(market.getId() + "：关联实体枚举失败。");
        }
        // 经济信息是补充线索，不能拿可用等级代替货物件数。
        try {
            for (var commodity : market.getAllCommodities()) {
                if (commodity.getAvailable() <= 0 && commodity.getStockpile() <= 0) continue;
                for (Item item : wanted) {
                    if (item.category() == SpecLookup.Category.COMMODITY && item.id().equals(commodity.getId())) {
                        boolean found = inventories.values().stream().flatMap(m -> m.values().stream())
                                .anyMatch(o -> o.market() == market && o.item().equals(item));
                        if (!found) warnings.add(market.getName() + " [" + market.getId() + "]：经济数据有 "
                                + item.name() + " 供应，当前仓库未读到对应货物件数。");
                    }
                }
            }
        } catch (RuntimeException exception) {
            warnings.add(market.getId() + "：经济货物信息读取失败。");
        }
    }

    private void readShop(MarketAPI market, SubmarketAPI shop, String freshnessProblem) {
        String source = shop.getNameOneLine() + " [" + shop.getSpecId() + "]";
        try {
            SubmarketPlugin plugin = null;
            String restriction = freshnessProblem;
            try {
                plugin = shop.getPlugin();
                restriction = join(restriction, restrictions(market, plugin));
            } catch (RuntimeException exception) {
                restriction = join(restriction, "交易权限未能确认");
            }
            float tariff = Float.NaN;
            try { tariff = shop.getTariff(); } catch (RuntimeException ignored) { }
            CargoAPI cargo = shop.getCargo();
            if (cargo == null) {
                warnings.add(market.getId() + "/" + shop.getSpecId() + "：未返回 cargo。");
                return;
            }
            if (readCargo(market, cargo, source, plugin, restriction, tariff)) shopsRead++;
        } catch (RuntimeException exception) {
            warnings.add(market.getId() + "/" + shop.getSpecId() + "：库存读取失败（"
                    + exception.getClass().getSimpleName() + "）。");
        }
    }

    private String restrictions(MarketAPI market, SubmarketPlugin plugin) {
        List<String> reasons = new ArrayList<>();
        if (market.isHidden()) reasons.add("隐藏市场");
        if (market.isPlanetConditionMarketOnly()) reasons.add("非交易市场");
        if (CampaignPlaces.marketTarget(market) == null) reasons.add("无可导航实体");
        if (market.getFaction().isHostileTo(Global.getSector().getPlayerFaction())) reasons.add("敌对市场");
        if (plugin.isHidden()) reasons.add("隐藏交易区");
        if (plugin.isFreeTransfer()) reasons.add("仓储/免费转移，非出售库存");
        if (!includeBlackMarket && plugin.isBlackMarket()) reasons.add("黑市未纳入本次路线");
        if (!plugin.isEnabled(OPEN_TRADE)) reasons.add("当前不满足商店开放权限");
        if (plugin.getOnClickAction(OPEN_TRADE) != SubmarketPlugin.OnClickAction.OPEN_SUBMARKET) reasons.add("需要自定义交易交互");
        return String.join("；", reasons);
    }

    private boolean readCargo(MarketAPI market, CargoAPI cargo, String source, SubmarketPlugin plugin,
                              String restriction, float tariff) {
        boolean complete = true;
        Map<String, Offer> found = inventories.get(cargo);
        if (found == null) {
            found = new LinkedHashMap<>();
            inventories.put(cargo, found);
            cargoOrder.add(cargo);
        }
        try {
            List<CargoStackAPI> stacks = cargo.getStacksCopy();
            for (int i = 0; i < stacks.size(); i++) {
                try {
                    CargoStackAPI stack = stacks.get(i);
                    if (stack.getSize() <= 0) continue;
                    for (Item item : wanted) {
                        if (!matches(item, stack)) continue;
                        String status = restriction;
                        try {
                            if (plugin != null && plugin.isIllegalOnSubmarket(stack, TransferAction.PLAYER_BUY)) {
                                status = join(status, "该货物当前不允许买入");
                            }
                        } catch (RuntimeException exception) { status = join(status, "物品购买权限未知"); }
                        String instance = stack.getDisplayName();
                        if (stack.isSpecialStack()) instance += "；实例数据=" + stack.getSpecialDataIfSpecial().getData();
                        add(found, "stack:" + i + ":" + item.label(),
                                new Offer(item, market, source, instance, stack.getSize(), tariff, status));
                    }
                } catch (RuntimeException exception) {
                    complete = false;
                    warnings.add(market.getId() + "/" + source + "：货物条目 " + i + " 读取失败，其余条目保留。");
                }
            }
        } catch (RuntimeException exception) {
            complete = false;
            warnings.add(market.getId() + "/" + source + "：货物列表读取失败，继续检查舰船。");
        }
        if (wanted.stream().noneMatch(item -> item.category() == SpecLookup.Category.HULL)) return complete;
        try {
            var fleet = cargo.getMothballedShips();
            if (fleet == null) return complete;
            for (FleetMemberAPI ship : fleet.getMembersListCopy()) {
                shipEntriesRead++;
                try {
                    String hullId = ship.getHullSpec().getHullId();
                    for (Item item : wanted) {
                        if (item.category() != SpecLookup.Category.HULL || !item.hullIds().contains(hullId)) continue;
                        String status = restriction;
                        try {
                            if (plugin != null && plugin.isIllegalOnSubmarket(ship, TransferAction.PLAYER_BUY)) {
                                status = join(status, "该舰船当前不允许买入（声望/军用许可等限制）");
                            }
                        } catch (RuntimeException exception) { status = join(status, "舰船购买权限未知"); }
                        String instance = ship.getShipName() + " / " + ship.getHullSpec().getHullName()
                                + " [hull=" + hullId + ", instance=" + ship.getId() + "]";
                        add(found, "ship:" + ship.getId() + ":" + item.label(),
                                new Offer(item, market, source, instance, 1, tariff, status));
                    }
                } catch (RuntimeException exception) {
                    complete = false;
                    warnings.add(market.getId() + "/" + source + "：舰船条目读取失败，其余舰船保留。");
                }
            }
        } catch (RuntimeException exception) {
            complete = false;
            warnings.add(market.getId() + "/" + source + "：舰船列表读取失败。");
        }
        return complete;
    }

    private static void add(Map<String, Offer> found, String key, Offer next) {
        Offer previous = found.get(key);
        // 同一仓库被实体与交易区引用时只计数一次，优先保留已确认可买的来源。
        if (previous == null || (!previous.canBuy() && next.canBuy())) found.put(key, next);
    }

    private static boolean matches(Item item, CargoStackAPI stack) {
        return switch (item.category()) {
            case COMMODITY -> stack.isCommodityStack() && item.id().equals(stack.getCommodityId());
            case WEAPON -> stack.isWeaponStack() && item.id().equals(stack.getWeaponSpecIfWeapon().getWeaponId());
            case HULL_MOD -> stack.isModSpecStack() && item.id().equals(stack.getHullModSpecIfHullMod().getId());
            case SPECIAL_ITEM -> stack.isSpecialStack() && item.id().equals(stack.getSpecialDataIfSpecial().getId());
            default -> false;
        };
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + "；" + b;
    }
}
