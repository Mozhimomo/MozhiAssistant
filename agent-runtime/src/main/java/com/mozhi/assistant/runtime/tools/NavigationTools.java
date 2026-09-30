package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.mozhi.assistant.bridge.GameThreadAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 星球、市场、购买规划与原生导航工具。路线状态只属于本次 Agent 会话。 */
public final class NavigationTools {
    private final GameThreadAccess gameThread;
    private List<String> route = List.of();
    private int currentStop;

    public NavigationTools(GameThreadAccess gameThread) {
        this.gameThread = gameThread;
    }

    @Tool("按名称或 ID 批量查询已知星球、殖民地或空间站市场，返回位置、距离、环境资源、产业与交易区。重名返回全部。")
    public String searchPlanets(@P("完整星球/市场名称或 ID 列表") List<String> namesOrIds) {
        if (namesOrIds == null || namesOrIds.isEmpty()) return "请提供星球或市场名称/ID。";
        var queries = new ArrayList<>(namesOrIds);
        return onGameThread(() -> CampaignPlaces.search(queries));
    }

    @Tool("仅用于浏览指定星系的已知星球；空星系表示玩家所在星系。本工具不查购买地点。玩家问哪里买舰船或物品时，应直接使用 findBuyingLocations 全星区搜索，不要先逐个列出本地星球。")
    public String listPlanets(@P("星系名称或 ID；留空表示当前星系") String system,
                              @P("返回上限，1 至 30") int limit) {
        return onGameThread(() -> CampaignPlaces.listPlanets(system, limit(limit)));
    }

    @Tool("仅查询一个已指定星球/市场的实时货物与仓库，无需玩家进入。读取交易区前执行游戏常规库存更新，不使用助手快照。查哪里能买舰船或物品时优先使用 findBuyingLocations 全星区搜索。包含 getAllCommodities 的经济货物信息、实体 cargo 中的商品和舰船，以及各交易区库存。经济等级不是可买件数，仓库内容不一定出售。")
    public String queryMarketInventory(@P("星球/市场的完整名称或 ID") String destination,
                                       @P("每个仓库的货物及舰船显示上限，1 至 30") int limit) {
        return onGameThread(() -> MarketInventory.read(uniqueDestination(destination), limit(limit)));
    }

    @Tool("在当前战役全部市场查找哪里能买舰船或物品，不限当前星系，也不要求玩家去过其他星系。接受混合名称/ID列表和数量，直接调用即可，无需先列星球或逐个查询仓库。每次在主线程常规更新商店后实时读取交易区和实体仓库，不读历史库存；返回市场、交易区、库存数量、游戏读取时间和覆盖统计。隐藏、敌对、黑市、仓储或交易受限的匹配项也返回，并标注购买状态。同名舰型可匹配多个实际型号和关联 D 型，结果列出实际 hull ID。支持舰型现货、商品、武器、船插、特殊物品。先全范围搜索，再按距离排序截取展示；limit只限制返回条目，不限制搜索范围。只查询，不购买或转移货物；商店常规更新会按游戏计时生成/更新到期库存。")
    public String findBuyingLocations(@P("购买名称或规格 ID 列表") List<String> items,
                                      @P("与 items 一一对应的购买数量；空列表表示每项 1 个") List<Integer> quantities,
                                      @P("是否允许黑市进入自动路线；false 时仍查询并展示黑市库存，但标为不参与路线") boolean includeBlackMarket,
                                      @P("全星区搜索后每种物品按距离排序的显示条目上限，1 至 30，不限制搜索范围") int limit) {
        var names = copy(items);
        var counts = copy(quantities);
        return onGameThread(() -> {
            var request = ShoppingPlanner.requests(names, counts);
            return ShoppingPlanner.buyingLocations(request, ShoppingPlanner.scan(request.keySet(), includeBlackMarket), limit(limit));
        });
    }

    @Tool("在当前战役全部市场查找购买清单并规划多站路线，不限当前星系，无需先列星球。每次常规更新并实时读取全部库存，先全范围搜索，再从当前可购买的项中选择最近市场，可跨星系分站凑齐。返回各站购买数量、交易区、距离和缺货。navigate=true 时在清单可凑齐后设置第一站游戏导航；玩家只问哪里卖时使用 false。不会自动交易。")
    public String planShoppingRoute(@P("购买名称或规格 ID 列表") List<String> items,
                                    @P("与 items 一一对应的数量；空列表表示每项 1 个") List<Integer> quantities,
                                    @P("是否允许黑市进入自动路线；false 时仍查询并展示黑市库存，但标为不参与路线") boolean includeBlackMarket,
                                    @P("玩家要求带路/导航购买时 true；仅询问方案时 false") boolean navigate) {
        var names = copy(items);
        var counts = copy(quantities);
        return onGameThread(() -> {
            var request = ShoppingPlanner.requests(names, counts);
            var plan = ShoppingPlanner.plan(request, ShoppingPlanner.scan(request.keySet(), includeBlackMarket));
            String result = ShoppingPlanner.describe(plan);
            if (!navigate) return result + "\n本次仅查询路线，未改变导航。";
            if (!plan.missing().isEmpty() || plan.stops().isEmpty()) {
                return result + "\n清单未能凑齐，未改变现有导航。可以调整清单，或明确指定市场后使用 navigateTo 前往核实。";
            }
            var nextRoute = plan.stops().stream().map(stop -> CampaignPlaces.marketTarget(stop.market()).getId()).toList();
            SectorEntityToken first = uniqueDestination(nextRoute.get(0));
            Global.getSector().layInCourseFor(first);
            route = nextRoute;
            currentStop = 0;
            return result + "\n已设置第一站导航：" + CampaignPlaces.label(first)
                    + "。到站购买完成后告诉我继续下一站。路线只保存在当前会话，不会自动购买。";
        });
    }

    @Tool("设置原生游戏导航到指定已知星球或市场，替换当前航线并清除旧购买路线。仅在玩家要求前往/导航时使用。名称重名时须用返回的 ID，不猜选。")
    public String navigateTo(@P("完整星球/市场名称或 ID") String destination) {
        return onGameThread(() -> {
            SectorEntityToken target = uniqueDestination(destination);
            Global.getSector().layInCourseFor(target);
            route = List.of();
            currentStop = 0;
            return "已设置游戏导航：" + CampaignPlaces.label(target) + "；位置：" + target.getContainingLocation().getName()
                    + "。关闭对话后可跟随游戏航线前往。";
        });
    }

    @Tool("查询游戏当前导航目标与本次会话购买路线的进度；不改变航线。")
    public String getNavigationStatus() {
        return onGameThread(() -> {
            var target = Global.getSector().getCampaignUI().getUltimateCourseTarget();
            String result = target == null ? "当前没有游戏导航目标。" : "游戏导航目标：" + CampaignPlaces.label(target);
            if (route.isEmpty()) return result + "\n没有正在进行的购买路线。";
            return result + "\n购买路线第 " + (currentStop + 1) + "/" + route.size() + " 站；站点 ID：" + route
                    + "\n购买路线不证明物品已购买，库存变化时请重新规划。";
        });
    }

    @Tool("玩家已到达当前购买站且明确表示购买完成后，切换至购买路线下一站。不会确认或代替交易，不得在刚规划完时连续调用。")
    public String nextShoppingStop() {
        return onGameThread(() -> {
            if (route.isEmpty()) return "没有正在进行的购买路线。";
            var current = uniqueDestination(route.get(currentStop));
            if (ShoppingPlanner.localDistance(Global.getSector().getPlayerFleet(), current) > Math.max(1000, current.getRadius() + 500)) {
                return "尚未到达当前购买站附近：" + CampaignPlaces.label(current) + "，未切换航线。";
            }
            if (currentStop + 1 >= route.size()) {
                Global.getSector().getCampaignUI().clearLaidInCourse();
                route = List.of();
                currentStop = 0;
                return "购买路线已结束，已清除导航。购买是否完成以玩家实际交易为准。";
            }
            var next = uniqueDestination(route.get(currentStop + 1));
            Global.getSector().layInCourseFor(next);
            currentStop++;
            return "已导航至第 " + (currentStop + 1) + "/" + route.size() + " 站：" + CampaignPlaces.label(next)
                    + "。库存或交易权限变化时请重新查询。";
        });
    }

    @Tool("玩家要求取消导航时，清除游戏航线及当前购买路线。")
    public String cancelNavigation() {
        return onGameThread(() -> {
            Global.getSector().getCampaignUI().clearLaidInCourse();
            route = List.of();
            currentStop = 0;
            return "已取消导航并清除购买路线。";
        });
    }

    private SectorEntityToken uniqueDestination(String query) {
        var matches = CampaignPlaces.marketDestinations(query);
        if (matches.isEmpty()) matches = CampaignPlaces.destinations(query);
        if (matches.size() != 1) {
            throw new IllegalArgumentException(matches.isEmpty() ? "未找到已知目的地，请提供完整名称或 ID。"
                    : "目的地不唯一，请指定 ID：" + matches.stream().map(CampaignPlaces::label).toList());
        }
        return matches.get(0);
    }

    private String onGameThread(Supplier<String> action) {
        return gameThread.call(() -> {
            try {
                CampaignPlaces.requireCampaign();
                return action.get();
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return "无法完成：" + exception.getMessage();
            }
        });
    }

    private static int limit(int value) {
        return value <= 0 ? 10 : Math.min(value, 30);
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : new ArrayList<>(list);
    }
}
