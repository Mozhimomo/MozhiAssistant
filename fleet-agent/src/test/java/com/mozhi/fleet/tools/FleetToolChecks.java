package com.mozhi.fleet.tools;

import com.mozhi.fleet.actions.*;
import com.mozhi.fleet.model.*;
import dev.langchain4j.agent.tool.*;
import java.util.*;

/** 验证注解契约、反射参数绑定和跨帧工具实例复用，不调用模型或游戏。 */
public final class FleetToolChecks {
    public static void main(String[] args) throws Exception {
        var tools = new FleetToolRegistry(List.of(new BuyAction(), new SellAction(), new MoveToAction(),
                new ReturnToPlayerAction(), new FollowFleetAction(), new CalculateTradeRouteAction(), new PrepareTradeHopAction(), new TransferToPlayerAction()));
        check(tools.specifications().size() == 8, "八个内置工具使用注解注册");
        check(tools.specifications().stream().noneMatch(spec -> spec.name().equals("TRANSFER_TO_MOZHI") || spec.name().equals("SEND_MESSAGE_TO_MAIN")), "子智能体不提供从玩家取款或发消息工具");
        check(tools.specifications().stream().flatMap(s -> s.parameters().stream())
                .noneMatch(p -> Set.of("step", "context").contains(p.name())), "本地执行上下文不暴露给规划器");
        Sample sample = new Sample();
        var registry = new FleetToolRegistry(List.of(sample));
        Step step = Step.create("SAMPLE", Map.of("quantity", 8), "工具请求", "完成");
        check(registry.execute(step, null).status() == ExecutionResult.Status.RUNNING, "首帧启动");
        check(registry.execute(step, null).status() == ExecutionResult.Status.SUCCEEDED && sample.calls == 2,
                "同一工具实例保留跨帧进度，反射参数按类型注入");
        registry.pause("SAMPLE"); registry.stop("SAMPLE", null); registry.cancelBackground();
        check(sample.pauses == 1 && sample.stops == 1 && sample.cancels == 1, "生命周期调用传递给同一实例");
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("quantity", 8.5), Map.of(),
                Map.of("quantity", "8"), Map.of("quantity", 8, "unknown", true), Map.of("quantity", 2147483648L))) {
            rejects(() -> registry.execute(Step.create("SAMPLE", invalid, "无效请求", "拒绝"), null));
        }
        check(sample.calls == 2, "无效参数在调用工具之前失败");
        rejects(() -> registry.execute(Step.create("missing", Map.of(), "未知工具", "拒绝"), null));
        rejects(() -> new FleetToolRegistry(List.of(sample, new Sample())));
        try {
            registry.execute(Step.create("SAMPLE", Map.of("quantity", 0), "异常", "失败"), null);
            throw new AssertionError("工具异常未传递");
        } catch (UncertainActionException expected) { check(expected.getMessage().equals("原始异常"), "反射解包保留异常语义"); }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var encoded = json.valueToTree(step);
        check(encoded.has("tool") && encoded.has("arguments") && !encoded.has("action") && !encoded.has("parameters"),
                "Step 持久化为工具调用请求");
        check(json.readValue(json.writeValueAsString(step), Step.class).equals(step), "工具调用请求可存读档");
        System.out.println("注解工具注册、反射调用及生命周期检查通过");
    }
    public static final class Sample implements Action {
        int calls, pauses, stops, cancels;
        @Tool(name = "SAMPLE", value = "跨帧示例工具")
        public ExecutionResult run(Step step, ActionContext context,
                @P(name = "quantity", value = "数量") int quantity,
                @P(name = "budget", value = "预算", required = false) Double budget) {
            if (quantity == 0) throw new UncertainActionException("原始异常", null);
            check(quantity == 8 && budget == null && context == null, "参数和本地上下文注入正确");
            return new ExecutionResult(step, ++calls == 1 ? ExecutionResult.Status.RUNNING : ExecutionResult.Status.SUCCEEDED, "实际结果");
        }
        public void pause() { pauses++; }
        public void stop(ActionContext context) { stops++; }
        public void cancelBackground() { cancels++; }
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("预期拒绝请求");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
