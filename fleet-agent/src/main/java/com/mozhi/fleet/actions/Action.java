package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;

/** 动作在游戏主线程运行，一次调用只推进当前步骤。 */
public interface Action {
    ActionSpec spec();
    ExecutionResult execute(Step step, ActionContext context);
    default void stop(ActionContext context) {}
    /** 读档释放运行区时可从其他线程调用，只取消后台计算，不访问游戏对象。 */
    default void cancelBackground() {}
    default boolean backgroundStopped() { return true; }
}
