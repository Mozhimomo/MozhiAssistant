package com.mozhi.fleet.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.fleet.actions.Action;
import com.mozhi.fleet.actions.ActionContext;
import com.mozhi.fleet.model.ExecutionResult;
import com.mozhi.fleet.model.Step;
import com.mozhi.fleet.planning.ActionSpec;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

/** 注册时解析注解并缓存方法；Planner 和 Executor 共用同一份工具契约。 */
public final class FleetToolRegistry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Binding(Method method, ActionSpec spec, List<String> names) {}
    private record Entry(Action instance, Binding binding) {}
    private static final ClassValue<List<Binding>> DEFINITIONS = new ClassValue<>() {
        @Override protected List<Binding> computeValue(Class<?> type) {
            List<Binding> found = new ArrayList<>();
            for (Method method : type.getMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) continue;
                if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != ExecutionResult.class)
                    throw new IllegalArgumentException("舰队工具必须是返回 ExecutionResult 的实例方法：" + method);
                List<ActionSpec.Parameter> parameters = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.getType() == Step.class || parameter.getType() == ActionContext.class) {
                        names.add(""); continue; // 执行上下文由本地注入，不公开给 Planner。
                    }
                    P p = parameter.getAnnotation(P.class);
                    if (p == null || p.name().isBlank()) throw new IllegalArgumentException("工具参数必须声明 @P(name=...)：" + method);
                    if (!p.required() && parameter.getType().isPrimitive()) throw new IllegalArgumentException("可选工具参数不能使用基本类型：" + p.name());
                    if (!P.NO_DEFAULT.equals(p.defaultValue())) throw new IllegalArgumentException("工具默认值由实现处理：" + p.name());
                    names.add(p.name());
                    parameters.add(new ActionSpec.Parameter(p.name(), parameterType(parameter.getType()), p.required(),
                            p.description().isBlank() ? p.value() : p.description()));
                }
                method.setAccessible(true);
                found.add(new Binding(method, new ActionSpec(annotation.name().isBlank() ? method.getName() : annotation.name(),
                        String.join("\n", annotation.value()), parameters), List.copyOf(names)));
            }
            return List.copyOf(found);
        }
    };
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FleetToolRegistry(List<Action> tools) {
        for (Action tool : List.copyOf(tools)) {
            List<Binding> definitions = DEFINITIONS.get(tool.getClass());
            // 保留已有自定义 Action 扩展入口；内置工具全部由注解定义，仍统一通过反射执行。
            if (definitions.isEmpty()) {
                try {
                    Method method = tool.getClass().getMethod("execute", Step.class, ActionContext.class);
                    if (method.getDeclaringClass() == Action.class) throw new IllegalArgumentException("工具没有 @Tool 方法：" + tool.getClass());
                    method.setAccessible(true);
                    definitions = List.of(new Binding(method, tool.spec(), List.of("", "")));
                } catch (NoSuchMethodException error) { throw new IllegalArgumentException("工具缺少执行方法", error); }
            }
            for (Binding definition : definitions)
                if (entries.putIfAbsent(definition.spec().name(), new Entry(tool, definition)) != null)
                    throw new IllegalArgumentException("重复工具：" + definition.spec().name());
        }
    }

    public static ActionSpec definition(Class<?> type) {
        var definitions = DEFINITIONS.get(type);
        if (definitions.size() != 1) throw new IllegalArgumentException("Action 必须对应一个注解工具：" + type);
        return definitions.get(0).spec();
    }
    public List<ActionSpec> specifications() { return entries.values().stream().map(e -> e.binding().spec()).toList(); }
    private Entry entry(String name) {
        Entry entry = entries.get(name);
        if (entry == null) throw new IllegalArgumentException("未知工具：" + name);
        return entry;
    }
    public void validate(Step step) { entry(step.tool()).binding().spec().validate(step.arguments()); }
    public ExecutionResult execute(Step step, ActionContext context) {
        Entry entry = entry(step.tool());
        Binding binding = entry.binding();
        binding.spec().validate(step.arguments());
        Parameter[] parameters = binding.method().getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (type == Step.class) values[i] = step;
            else if (type == ActionContext.class) values[i] = context;
            else {
                Object value = step.arguments().get(binding.names().get(i));
                if (value instanceof List<?> list && parameters[i].getParameterizedType() instanceof ParameterizedType generic
                        && generic.getActualTypeArguments()[0] == String.class && list.stream().anyMatch(item -> !(item instanceof String)))
                    throw new IllegalArgumentException("工具参数必须是字符串列表：" + binding.names().get(i));
                if (value == null) values[i] = null;
                else if (type == int.class || type == Integer.class) values[i] = new BigDecimal(value.toString()).intValueExact();
                else if (type == long.class || type == Long.class) values[i] = new BigDecimal(value.toString()).longValueExact();
                else values[i] = JSON.convertValue(value, JSON.getTypeFactory().constructType(parameters[i].getParameterizedType()));
            }
        }
        try { return Objects.requireNonNull((ExecutionResult) binding.method().invoke(entry.instance(), values), "工具返回空结果"); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("工具执行失败：" + step.tool(), cause);
        } catch (IllegalAccessException error) { throw new IllegalStateException("无法访问工具方法", error); }
    }
    public void stop(String name, ActionContext context) { entry(name).instance().stop(context); }
    public void pause(String name) { entry(name).instance().pause(); }
    public void cancelBackground() { entries.values().stream().map(Entry::instance).distinct().forEach(Action::cancelBackground); }
    public boolean backgroundStopped() { return entries.values().stream().allMatch(e -> e.instance().backgroundStopped()); }

    private static ActionSpec.Type parameterType(Class<?> type) {
        if (type == String.class) return ActionSpec.Type.STRING;
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) return ActionSpec.Type.INTEGER;
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) return ActionSpec.Type.NUMBER;
        if (type == Boolean.class || type == boolean.class) return ActionSpec.Type.BOOLEAN;
        if (Map.class.isAssignableFrom(type)) return ActionSpec.Type.OBJECT;
        if (List.class.isAssignableFrom(type)) return ActionSpec.Type.ARRAY;
        throw new IllegalArgumentException("不支持的工具参数类型：" + type);
    }
}
