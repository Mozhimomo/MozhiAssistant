package com.mozhi.assistant.runtime;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 同时注册工具定义与执行器，防止模型看到的工具和实际可调用工具不一致。 */
public final class ToolRegistry {
    private ToolRegistry() {
    }

    public record Registered(
            List<ToolSpecification> specifications,
            Map<String, ToolExecutor> executors) {

        /** 执行和记录使用同一个上下文；失败也形成可回传模型的工具结果。 */
        ToolExecution execute(ToolExecutionRequest call, InvocationContext invocationContext) {
            return execute(call, invocationContext, null);
        }

        /** 未披露的工具返回加载指引，仍不执行其业务方法。 */
        ToolExecution execute(ToolExecutionRequest call, InvocationContext invocationContext, String unavailableHint) {
            Objects.requireNonNull(invocationContext, "invocationContext");
            LocalDateTime startedAt = LocalDateTime.now();
            ToolExecutionResult result;
            try {
                ToolExecutor executor = executors.get(call.name());
                if (executor == null) {
                    throw new IllegalArgumentException(unavailableHint == null ? "未找到工具：" + call.name() : unavailableHint);
                }
                result = Objects.requireNonNull(
                        executor.executeWithContext(call, invocationContext),
                        "工具执行器返回了 null 结果");
                // 某些执行器延迟生成结果文本；该过程的异常也归为工具失败。
                result.resultText();
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                result = ToolExecutionResult.builder()
                        .resultText("工具失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage())
                        .isError(true)
                        .build();
            }
            return ToolExecution.builder()
                    .request(call)
                    .result(result)
                    .invocationContext(invocationContext)
                    .startTime(startedAt)
                    .finishTime(LocalDateTime.now())
                    .build();
        }
    }

    public static Registered register(
            List<Object> tools,
            ToolProvider provider,
            List<ChatMessage> messages,
            InvocationContext invocationContext) {
        Map<String, ToolSpecification> specifications = new LinkedHashMap<>();
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();

        registerAnnotatedTools(tools, specifications, executors);
        registerProvidedTools(provider, messages, invocationContext, specifications, executors);
        return new Registered(List.copyOf(specifications.values()), Map.copyOf(executors));
    }

    private static void registerAnnotatedTools(
            List<Object> tools,
            Map<String, ToolSpecification> specifications,
            Map<String, ToolExecutor> executors) {
        if (tools == null) {
            return;
        }
        for (Object tool : tools) {
            for (ToolSpecification specification : ToolSpecifications.toolSpecificationsFrom(tool)) {
                Method method = findMethod(tool.getClass(), specification.name());
                method.setAccessible(true);
                addTool(specifications, executors, specification, new DefaultToolExecutor(tool, method));
            }
        }
    }

    private static void registerProvidedTools(
            ToolProvider provider,
            List<ChatMessage> messages,
            InvocationContext invocationContext,
            Map<String, ToolSpecification> specifications,
            Map<String, ToolExecutor> executors) {
        if (provider == null) {
            return;
        }
        ToolProviderRequest request = ToolProviderRequest.builder()
                .messages(List.copyOf(messages))
                .userMessage(invocationContext.userMessage())
                .invocationContext(invocationContext)
                .build();
        ToolProviderResult provided = provider.provideTools(request);
        if (provided == null) {
            throw new IllegalArgumentException("ToolProvider 返回了 null");
        }
        provided.tools().forEach((specification, executor) ->
                addTool(specifications, executors, specification, executor));
    }

    private static void addTool(
            Map<String, ToolSpecification> specifications,
            Map<String, ToolExecutor> executors,
            ToolSpecification specification,
            ToolExecutor executor) {
        if (executor == null || specifications.putIfAbsent(specification.name(), specification) != null) {
            throw new IllegalArgumentException("工具重复或没有执行器：" + specification.name());
        }
        executors.put(specification.name(), executor);
    }

    private static Method findMethod(Class<?> type, String toolName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) {
                    continue;
                }
                String declaredName = annotation.name().isEmpty() ? method.getName() : annotation.name();
                if (declaredName.equals(toolName)) {
                    return method;
                }
            }
        }
        throw new IllegalArgumentException("找不到 @Tool 方法：" + toolName);
    }
}
