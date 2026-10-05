package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;

/** 动作在游戏主线程运行，一次调用只推进当前步骤。 */
public interface Action {
    default ActionSpec spec() { return com.mozhi.fleet.tools.FleetToolRegistry.definition(getClass()); }
    default ExecutionResult execute(Step step, ActionContext context) {
        return new com.mozhi.fleet.tools.FleetToolRegistry(java.util.List.of(this)).execute(step, context);
    }
    default void stop(ActionContext context) {}
    /** 暂停期间冻结分帧计算的墙钟预算，不读取或修改游戏对象。 */
    default void pause() {}
    /** 读档释放运行区时可从其他线程调用，只取消后台计算，不访问游戏对象。 */
    default void cancelBackground() {}
    default boolean backgroundStopped() { return true; }
}
