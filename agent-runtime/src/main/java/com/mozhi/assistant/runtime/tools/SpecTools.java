package com.mozhi.assistant.runtime.tools;

import com.mozhi.assistant.bridge.GameThreadAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.mozhi.assistant.runtime.tools.SpecLookup.Category.*;

/** 统一规格查询入口。分类方法可供代码调用，所有游戏数据访问都调度到主线程。 */
public final class SpecTools {
    private final GameThreadAccess gameThread;

    public SpecTools(GameThreadAccess gameThread) {
        this.gameThread = gameThread;
    }

    @Tool("按名称或规格 ID 批量查询舰船舰型、武器、船插（舰船插件）、战术系统和物品。"
            + "同一列表可混合不同类别及名称/ID；自动识别类别，每项返回类别、名称、ID 和详细基础规格。"
            + "物品包括普通商品和特殊物品，查询范围包含原版和已启用模组。"
            + "同类别内 ID 优先，重名及跨类别同名/同 ID 返回全部匹配项，不猜测。"
            + "查询的是基础规格，不是玩家舰船的实时配装，也不是特定蓝图等物品实例的内容。")
    public String searchSpecs(@P("名称或规格 ID 列表，可混合舰型、武器、船插、战术系统、物品") List<String> ids) {
        return query(ids, EnumSet.allOf(SpecLookup.Category.class));
    }

    /** 保留已有工具名，避免旧调用方失效；与通用入口共用匹配和详情实现。 */
    @Tool("仅按舰型名称或 hull ID 批量查询舰型基础规格。混合类别查询请使用 searchSpecs。")
    public String searchHullSpecs(@P("舰型名称或 hull ID 列表，可混合输入") List<String> ids) {
        return query(ids, EnumSet.of(HULL));
    }

    public String searchWeaponSpecs(List<String> ids) {
        return query(ids, EnumSet.of(WEAPON));
    }

    public String searchHullModSpecs(List<String> ids) {
        return query(ids, EnumSet.of(HULL_MOD));
    }

    public String searchShipSystemSpecs(List<String> ids) {
        return query(ids, EnumSet.of(SHIP_SYSTEM));
    }

    public String searchItemSpecs(List<String> ids) {
        return query(ids, EnumSet.of(COMMODITY, SPECIAL_ITEM));
    }

    private String query(List<String> ids, Set<SpecLookup.Category> categories) {
        if (ids == null || ids.isEmpty()) {
            return "请提供至少一个名称或规格 ID。";
        }
        List<String> queries = new ArrayList<>(ids);
        return gameThread.call(() -> SpecLookup.read(queries, categories));
    }
}
