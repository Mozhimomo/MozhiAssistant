package com.mozhi.assistant.runtime;

import com.fs.starfarer.api.Global;
import com.mozhi.assistant.bridge.GameThreadAccess;
import com.fs.starfarer.api.fleet.FleetMemberType;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.List;

/** Deliberately private @Tool methods exercise LangChain4j's reflective tool execution. */
final class DemoTools {
    private final GameThreadAccess gameThread;
    private final List<String> calls = new ArrayList<>();

    DemoTools(GameThreadAccess gameThread) { this.gameThread = gameThread; }

    void beginTurn() {
        calls.clear();
    }

    @Tool("实时读取玩家舰队详情：位置、资源、各舰船型号、舰长、战备、船体、武器、战机和舰船插件。"
            + "涉及当前舰队的问题必须调用此工具；每次调用均重新读取，不要依赖历史数据猜测。")
    private String getFleetSummary() {
        String details = gameThread.call(PlayerFleetDetails::read);
        calls.add("getFleetSummary() -> " + details);
        return details;
    }

    @Tool("精确计算两个整数之和。遇到加法问题时调用此工具。")
    private long add(@P("第一个整数") int a, @P("第二个整数") int b) {
        long result = (long) a + b;
        calls.add("add(" + a + ", " + b + ") -> " + result);
        return result;
    }

    @Tool("向当前舰队里添加一艘攻势。")
    private String addOnslaught() {
        String result = gameThread.call(() -> {
            Global.getSector().getPlayerFleet().getFleetData().addFleetMember(
                    Global.getFactory().createFleetMember(FleetMemberType.SHIP, "onslaught_Standard"));
            return "添加成功";
        });
        calls.add("addOnslaught() -> " + result);
        return result;
    }

    String trace() {
        return calls.isEmpty() ? "本轮没有执行工具；单纯文本回复不能证明 @Tool 调用成功。"
                : "实际执行的私有 @Tool 方法：\n" + String.join("\n", calls);
    }
}
