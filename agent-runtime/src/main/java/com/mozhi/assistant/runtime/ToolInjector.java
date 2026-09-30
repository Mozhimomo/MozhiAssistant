package com.mozhi.assistant.runtime;

import com.mozhi.assistant.runtime.model.AgentCallRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 请求级工具注入入口，在每轮 ReAct 开始前调用一次。 */
public final class ToolInjector {
    private ToolInjector() {
    }

    /**
     * 当前透传请求中的工具，保留实例及顺序，不筛选或新增工具。
     * 返回独立的可变列表，避免后续追加内置工具时修改原请求；null 工具列表视为空列表。
     */
    public static List<Object> inject(AgentCallRequest request) {
        Objects.requireNonNull(request, "request");
        List<Object> tools = request.getTools();
        return tools == null ? new ArrayList<>() : new ArrayList<>(tools);
    }
}