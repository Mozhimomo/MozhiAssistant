package com.mozhi.assistant.runtime;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.*;

/** 每轮仅披露按需加载的功能组，未披露工具不能执行；下轮重新从目录开始。 */
final class ProgressiveTools {
    private final ToolRegistry.Registered all;
    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    private final Set<String> enabled = new LinkedHashSet<>();

    ProgressiveTools(ToolRegistry.Registered all, List<Object> instances) {
        this.all = all;
        // 高频摘要查询常驻，避免每次询问进度都增加一次目录往返。
        if (all.executors().containsKey("getMozhiFleetStatus")) enabled.add("getMozhiFleetStatus");
        for (Object instance : instances) {
            String group = switch (instance.getClass().getSimpleName()) {
                case "FleetCommandTools" -> "fleet";
                case "NavigationTools" -> "markets_navigation";
                case "ShipTools", "DemoTools" -> "player";
                case "SpecTools" -> "specs";
                case "ProfileMemoryTools" -> "memory";
                default -> "other";
            };
            var names = groups.computeIfAbsent(group, ignored -> new ArrayList<>());
            dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(instance).forEach(spec -> names.add(spec.name()));
        }
        for (var spec : all.specifications())
            if (groups.values().stream().noneMatch(names -> names.contains(spec.name())))
                groups.computeIfAbsent("other", ignored -> new ArrayList<>()).add(spec.name());
    }

    @Tool("按需加载工具组；本工具只披露定义，不执行游戏操作。每轮新对话重新加载，历史记录不表示本轮已加载。组：fleet=分舰队任务/状态/交易账本/派出召回/双向转账；markets_navigation=实时市场库存/购买地点/购物路线/导航；player=玩家舰队与舰船详情/整数加法；specs=物品和舰型等规格；memory=长期记忆；other=扩展工具。先加载所需组，等待下一次模型调用提供完整参数定义后再调用业务工具，不与加载放在同批请求中。")
    public String discoverTools(@P("需要加载的组名列表，可一次选择多个组") List<String> groups) {
        if (groups == null || groups.isEmpty()) throw new IllegalArgumentException("请指定至少一个工具组");
        for (String group : groups) if (!this.groups.containsKey(group))
            throw new IllegalArgumentException("不可用工具组：" + group + "；可用：" + this.groups.keySet());
        var names = new ArrayList<String>();
        for (String group : groups) { names.addAll(this.groups.get(group)); enabled.addAll(this.groups.get(group)); }
        return "已加载工具：" + names + "。使用下一轮提供的完整参数定义调用；尚未执行任何业务操作。";
    }

    ToolRegistry.Registered visible(ToolRegistry.Registered discovery) {
        var specs = new ArrayList<>(discovery.specifications());
        var executors = new LinkedHashMap<>(discovery.executors());
        for (var spec : all.specifications()) if (enabled.contains(spec.name())) {
            specs.add(spec); executors.put(spec.name(), all.executors().get(spec.name()));
        }
        return new ToolRegistry.Registered(List.copyOf(specs), Map.copyOf(executors));
    }

    String unavailableHint(String tool) {
        for (var group : groups.entrySet()) if (group.getValue().contains(tool))
            return "工具已注册，但本次模型请求尚未披露：" + tool
                    + "。本次未执行任何业务操作；这不是舰队通道故障。请调用 discoverTools，参数 {\"groups\":[\""
                    + group.getKey() + "\"]}，等待下一次模型请求取得完整参数定义后再调用原工具。"
                    + "每轮新对话需要重新加载；若刚在同批调用中加载，请在下一次模型请求重试。";
        return "未注册工具：" + tool + "。本次未执行任何业务操作；请通过 discoverTools 加载所需组并使用返回的真实工具名，不要猜测名称或参数。";
    }
}
