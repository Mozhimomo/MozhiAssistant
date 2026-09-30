package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.mozhi.assistant.bridge.GameThreadAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 玩家舰船查询；匹配和详情读取均在同一次游戏主线程调用内完成。 */
public final class ShipTools {
    private final GameThreadAccess gameThread;

    public ShipTools(GameThreadAccess gameThread) {
        this.gameThread = Objects.requireNonNull(gameThread, "gameThread");
    }

    @Tool("批量查询当前玩家舰队中具体舰船的实时详情，包括舰船 ID、船体、舰长、战备、装配、武器、战机、插件及子模块。"
            + "传入舰船名字或舰船 ID 的列表，可混合使用，工具自动识别。同名舰船全部返回，未找到的条目单独说明。"
            + "舰船 ID 是具体舰船的唯一标识；舰型基础资料请使用 searchHullSpecs。")
    private String searchShips(@P("要查询的舰船名字或舰船 ID 列表，可混合，例如 [\"典范\", \"onslaught\"]。传入名字时，用户可能会说攻势级战列舰或攻势级等类似名字，名字统一为级前的内容，比如攻势。") List<String> namesOrIds) {
        if (namesOrIds == null || namesOrIds.isEmpty()) {
            return "请提供至少一个舰船名字或舰船 ID。";
        }
        // 只复制调用参数，不缓存舰队或舰船状态。
        List<String> queries = new ArrayList<>(namesOrIds);
        return gameThread.call(() -> readShips(queries));
    }

    private static String readShips(List<String> queries) {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return "当前没有已载入的玩家舰队。";
        }
        List<FleetMemberAPI> members = Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
        Map<String, List<FleetMemberAPI>> byId = new LinkedHashMap<>();
        Map<String, List<FleetMemberAPI>> byName = new LinkedHashMap<>();
        for (FleetMemberAPI member : members) {
            if (member.isFighterWing()) continue;
            index(byId, member.getId(), member);
            index(byName, member.getShipName(), member);
        }

        StringBuilder out = new StringBuilder("【玩家舰船查询结果】\n");
        Set<FleetMemberAPI> reported = new LinkedHashSet<>();
        for (int i = 0; i < queries.size(); i++) {
            String query = normalize(queries.get(i));
            out.append("\n【查询 ").append(i + 1).append("】");
            if (query.isEmpty()) {
                out.append("查询条件为空，请提供舰船名字或舰船 ID。\n");
                continue;
            }
            out.append(queries.get(i).strip()).append('\n');
            // ID 命中优先，避免恰好与另一艘舰船的名字相同而查到错误目标。
            List<FleetMemberAPI> matches = byId.getOrDefault(query, byName.getOrDefault(query, List.of()));
            if (matches.isEmpty()) {
                out.append("当前玩家舰队中未找到对应舰船。\n");
                continue;
            }
            out.append("匹配舰船：").append(matches.size()).append(" 艘。\n");
            for (FleetMemberAPI member : matches) {
                if (reported.add(member)) {
                    out.append(ShipDetails.read(member)).append('\n');
                } else {
                    out.append(member.getShipName()).append(" [舰船 ID：").append(member.getId())
                            .append("]：详情已在前文列出。\n");
                }
            }
        }
        return out.toString();
    }

    private static void index(Map<String, List<FleetMemberAPI>> index, String value, FleetMemberAPI member) {
        String key = normalize(value);
        if (!key.isEmpty()) index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(member);
    }

    /** 名字和 ID 使用同一规则：忽略首尾空白及英文大小写，保留名称内部空格。 */
    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}