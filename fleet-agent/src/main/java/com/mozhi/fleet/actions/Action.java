package com.mozhi.fleet.actions;

import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;

/** 动作在游戏主线程运行，一次调用只推进当前步骤。 */
public interface Action {
    ActionSpec spec();
    ExecutionResult execute(Step step, ActionContext context);
    default void stop(ActionContext context) {}
}
