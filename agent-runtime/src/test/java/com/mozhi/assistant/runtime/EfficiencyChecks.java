package com.mozhi.assistant.runtime;

import com.mozhi.assistant.runtime.model.AgentCallRequest;
import com.mozhi.assistant.runtime.tools.ProfileMemoryTools;
import com.mozhi.llm.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.*;
import java.util.*;

/** 离线验证渐进披露、历史上下文和协议的完整流程。 */
public final class EfficiencyChecks {
    public static final class BusinessTools {
        int calls;
        @Tool("执行明确请求的测试动作") public String doWork(@P("整数数量") int quantity) { calls++; return "已执行 " + quantity; }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "efficiency-");
        ProfileStore profiles = new ProfileStore(root.resolve("profile.json"));
        BusinessTools business = new BusinessTools();
        List<ChatRequest> requests = new ArrayList<>();
        LlmClient model = new LlmClient() {
            public ChatResponse stream(ChatRequest request, LlmStreamListener listener) {
                requests.add(request);
                var names = request.toolSpecifications().stream().map(ToolSpecification::name).toList();
                AiMessage answer;
                if (requests.size() == 1) {
                    require(names.equals(List.of("discoverTools")), "初始仅公开工具目录");
                    answer = AiMessage.from(ToolExecutionRequest.builder().id("load").name("discoverTools").arguments("{\"groups\":[\"other\"]}").build());
                } else if (requests.size() == 2) {
                    require(names.contains("doWork") && !names.contains("rememberFact"), "仅公开选定工具组");
                    answer = AiMessage.from(ToolExecutionRequest.builder().id("action").name("doWork").arguments("{\"quantity\":20}").build());
                } else answer = AiMessage.from("已执行 20。");
                return ChatResponse.builder().aiMessage(answer).build();
            }
            public ChatResponse chat(ChatRequest request) { throw new AssertionError("不应调用摘要模型"); }
            public <T> T aiService(Class<T> type) { throw new AssertionError(); }
        };
        var request = AgentCallRequest.builder().agentId("test").userPrompt("执行20").llmClient(model).tools(List.of(business))
                .progressiveTools(true).contextSummary("旧摘要唯一标记").build();
        var result = new ReActTurn(request, profiles, new ProfileMemoryTools(profiles)).execute();
        require(result.isSuccess() && business.calls == 1 && result.getToolCalls().size() == 2, "先发现再执行，且只执行一次：" + result.getErrorMessage() + "; calls=" + business.calls + "; tools=" + result.getToolCalls());
        require(requests.get(0).messages().get(0) instanceof SystemMessage system && !system.text().contains("旧摘要唯一标记"), "摘要不放入系统提示词");
        require(requests.get(0).messages().toString().contains("旧摘要唯一标记"), "历史摘要仍作为数据提供");
        require(requests.get(0).messages().get(0) instanceof SystemMessage transferSystem
                && transferSystem.text().contains("等待玩家明确同意后再调用工具")
                && transferSystem.text().contains("不能超额或重复使用一次授权"), "对话系统规则保留转账授权要求");

        var invocation = dev.langchain4j.invocation.InvocationContext.builder().invocationId(UUID.randomUUID()).userMessage(UserMessage.from("测试")).timestampNow().build();
        var all = ToolRegistry.register(List.of(business), null, List.of(), invocation);
        var disclosure = new ProgressiveTools(all, List.of(business));
        var catalog = ToolRegistry.register(List.of(disclosure), null, List.of(), invocation);
        var hiddenCall = ToolExecutionRequest.builder().id("hidden").name("doWork").arguments("{\"quantity\":99}").build();
        var rejected = disclosure.visible(catalog).execute(hiddenCall, invocation, disclosure.unavailableHint(hiddenCall.name()));
        require(rejected.result().contains("工具已注册") && rejected.result().contains("discoverTools")
                && rejected.result().contains("other") && business.calls == 1, "未披露的工具不执行，并提供确切加载指引");
        require(disclosure.unavailableHint("nonexistent").contains("未注册工具"), "真正不存在的工具与尚未披露明确区分");
        try { disclosure.discoverTools(List.of("other", "bad-group")); throw new AssertionError("错误地接受了未知工具组"); }
        catch (IllegalArgumentException expected) { }
        require(disclosure.visible(catalog).specifications().size() == 1, "无效发现请求不能启用部分工具");

        com.mozhi.assistant.bridge.GameThreadAccess game = actionToRun -> { throw new AssertionError("工具披露不得读取游戏数据"); };
        List<Object> actualTools = List.of(new com.mozhi.assistant.runtime.tools.DemoTools(game),
                new com.mozhi.assistant.runtime.tools.ShipTools(game), new com.mozhi.assistant.runtime.tools.SpecTools(game),
                new com.mozhi.assistant.runtime.tools.NavigationTools(game), new com.mozhi.assistant.runtime.tools.FleetCommandTools(game), new ProfileMemoryTools(profiles));
        var full = ToolRegistry.register(actualTools, null, List.of(), invocation);
        String systemRules = ((SystemMessage) requests.get(0).messages().get(0)).text();
        for (String name : full.executors().keySet())
            require(!systemRules.contains(name), "系统提示词不硬编码业务工具名：" + name);
        for (String name : List.of("discoverTools", "markets_navigation", "TRANSFER_TO_PLAYER", "FOLLOW_FLEET", "CALCULATE_TRADE_ROUTE", "returnAfterCompletion"))
            require(!systemRules.contains(name), "系统提示词不硬编码目录、子工具或参数名：" + name);
        var actualDisclosure = new ProgressiveTools(full, actualTools);
        require(full.specifications().stream().map(ToolSpecification::name).toList()
                .containsAll(List.of("transferCreditsToPlayer", "transferCreditsToMozhi")), "双向转账工具已注册");
        require(full.specifications().stream().filter(s -> s.name().equals("transferCreditsToMozhi")).findFirst().orElseThrow()
                .description().contains("等待玩家明确同意后才能调用"), "玩家出资工具声明授权条件");
        var small = actualDisclosure.visible(ToolRegistry.register(List.of(actualDisclosure), null, List.of(), invocation));
        require(small.specifications().stream().map(ToolSpecification::name).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("discoverTools", "getMozhiFleetStatus")), "初始仅提供目录和常用舰队摘要查询");
        require(small.specifications().toString().length() < full.specifications().toString().length() / 2, "初始真实工具定义显著缩小");
        System.out.println("初始工具定义字符数：" + full.specifications().toString().length() + " -> " + small.specifications().toString().length());
        for (String name : List.of("delegateToFleetAgent", "commandMozhiFleet", "getNavigationStatus"))
            require(actualDisclosure.unavailableHint(name).contains(name.equals("getNavigationStatus") ? "markets_navigation" : "fleet"),
                    "真实工具返回正确的加载组：" + name);
        actualDisclosure.discoverTools(List.of("fleet"));
        var loadedFleet = actualDisclosure.visible(catalog).executors().keySet();
        require(loadedFleet.containsAll(List.of("delegateToFleetAgent", "commandMozhiFleet", "transferCreditsToPlayer", "transferCreditsToMozhi")),
                "同一舰队组同时披露委派、指令和双向转账");

        require(result.getMessages().stream().noneMatch(EfficiencyChecks::isDiscovery), "本轮结束后历史不保留工具发现请求或结果");
        var loadCall = ToolExecutionRequest.builder().id("load-old").name("discoverTools").arguments("{\"groups\":[\"other\"]}").build();
        var workCall = ToolExecutionRequest.builder().id("work-old").name("doWork").arguments("{\"quantity\":1}").build();
        var mixed = HistoryProjection.withoutDiscovery(List.of(AiMessage.from("处理中", List.of(loadCall, workCall)),
                ToolExecutionResultMessage.from(loadCall, "已加载"), ToolExecutionResultMessage.from(workCall, "已执行")));
        require(mixed.size() == 2 && mixed.get(0) instanceof AiMessage ai && ai.toolExecutionRequests().equals(List.of(workCall))
                && "处理中".equals(ai.text()), "混合调用只删除发现请求，业务请求与结果保持配对");

        for (boolean fresh : List.of(true, false)) {
            int callsBefore = business.calls;
            var recoveryCalls = new java.util.concurrent.atomic.AtomicInteger();
            LlmClient recovery = new LlmClient() {
                public ChatResponse stream(ChatRequest next, LlmStreamListener listener) {
                    int index = recoveryCalls.getAndIncrement();
                    AiMessage answer;
                    if (index == 0) {
                        require(!next.toolSpecifications().stream().anyMatch(s -> s.name().equals("doWork")), "新对话不继承历史披露状态");
                        require(next.messages().stream().noneMatch(EfficiencyChecks::isDiscovery), "新轮次不携带工具发现记录");
                        if (fresh) require(next.messages().stream().noneMatch(m -> m instanceof AiMessage || m instanceof ToolExecutionResultMessage)
                                && next.messages().stream().anyMatch(m -> m instanceof UserMessage user && user.singleText().equals("再执行20")),
                                "全新对话包含当前问题，没有任何历史模型回复或工具结果");
                        answer = AiMessage.from(ToolExecutionRequest.builder().id("premature").name("doWork").arguments("{\"quantity\":20}").build());
                    } else if (index == 1) {
                        require(business.calls == callsBefore && next.messages().toString().contains("工具已注册，但本次模型请求尚未披露"), "过早调用没有业务副作用，错误反馈进入上下文");
                        answer = AiMessage.from(ToolExecutionRequest.builder().id("reload").name("discoverTools").arguments("{\"groups\":[\"other\"]}").build());
                    } else if (index == 2) {
                        require(next.toolSpecifications().stream().anyMatch(s -> s.name().equals("doWork")), "恢复后收到完整工具定义");
                        require(next.messages().stream().anyMatch(EfficiencyChecks::isDiscovery), "本轮内保留发现回执以继续执行");
                        answer = AiMessage.from(ToolExecutionRequest.builder().id("retry").name("doWork").arguments("{\"quantity\":20}").build());
                    } else answer = AiMessage.from("已执行。");
                    return ChatResponse.builder().aiMessage(answer).build();
                }
                public ChatResponse chat(ChatRequest next) { throw new AssertionError("无需摘要"); }
                public <T> T aiService(Class<T> type) { throw new AssertionError(); }
            };
            var retried = new ReActTurn(AgentCallRequest.builder().agentId("test").userPrompt("再执行20")
                    .llmClient(recovery).tools(List.of(business)).history(fresh ? List.of() : result.getMessages()).progressiveTools(true).maxSteps(6).build(),
                    profiles, new ProfileMemoryTools(profiles)).execute();
            require(retried.isSuccess() && business.calls == callsBefore + 1 && retried.getToolCalls().size() == 3, "未披露调用经加载后恢复，业务仅执行一次");
            require(retried.getMessages().stream().noneMatch(EfficiencyChecks::isDiscovery), "恢复轮次结束后同样清理发现记录");
        }

        String huge = "{\"taskId\":\"mission\",\"offset\":0,\"total\":100,\"receipts\":[\"" + "data".repeat(2000) + "\"]}";
        var query = ToolExecutionResultMessage.from("receipt", "getMozhiTradeReceipts", huge);
        var action = ToolExecutionResultMessage.from("order", "delegateToFleetAgent", huge);
        var history = new ArrayList<ChatMessage>(List.of(UserMessage.from("以前查询"),
                AiMessage.from(ToolExecutionRequest.builder().id("receipt").name("getMozhiTradeReceipts").arguments("{}").build()), query,
                AiMessage.from(ToolExecutionRequest.builder().id("order").name("delegateToFleetAgent").arguments("{}").build()), action,
                AiMessage.from("已知结果")));
        var context = new ContextCompressor(AgentCallRequest.builder().userPrompt("现在查询").history(history).build());
        var messages = context.messagesSnapshot();
        var oldQuery = (ToolExecutionResultMessage) messages.get(2);
        require(oldQuery.id().equals("receipt") && oldQuery.toolName().equals(query.toolName()) && oldQuery.text().length() < 1000,
                "精简旧的大型查询结果，同时保留工具协议 ID");
        require(messages.get(4) == action && history.get(2) == query, "保留动作结果与调用方历史");
        context.append(query);
        require(context.messagesSnapshot().get(messages.size()) == query, "当前工具结果保持完整");
        Files.delete(root);
        System.out.println("上下文优化检查通过：渐进披露、执行校验、稳定系统提示词、历史查询投影");
    }

    private static boolean isDiscovery(ChatMessage message) {
        return message instanceof ToolExecutionResultMessage result && result.toolName().equals("discoverTools")
                || message instanceof AiMessage ai && ai.hasToolExecutionRequests()
                && ai.toolExecutionRequests().stream().anyMatch(call -> call.name().equals("discoverTools"));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
