package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.mozhi.assistant.bridge.GameThreadAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** 有意使用私有 @Tool 方法，验证 LangChain4j 的反射工具执行。 */
public final class DemoTools {
    private final GameThreadAccess gameThread;

    public DemoTools(GameThreadAccess gameThread) { this.gameThread = gameThread; }

    @Tool("实时读取玩家舰队详情：位置、资源、各舰船型号、舰长、战备、船体、武器、战机和舰船插件。"
            + "查询舰队整体情况时使用；查询指定舰船时可使用 searchShips。每次调用均重新读取，不要依赖历史数据猜测。")
    private String getFleetSummary() {
        String details = gameThread.call(PlayerFleetDetails::read);
        return details;
    }
}
