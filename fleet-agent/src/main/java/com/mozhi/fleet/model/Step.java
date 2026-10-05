package com.mozhi.fleet.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条不可变的工具调用请求。tool 对应注册工具名，arguments 只包含 JSON 数据。
 * description 用于展示，expectedOutcome 描述预期效果，不是实际执行结果或可执行表达式。
 * 同一个步骤跨计划继续执行时保留 ID；改变动作或参数时应创建新步骤。
 */
public record Step(String id, @com.fasterxml.jackson.annotation.JsonAlias("action") String tool,
                   @com.fasterxml.jackson.annotation.JsonAlias("parameters") Map<String, Object> arguments,
                   String description, String expectedOutcome) {
    public Step {
        requireText(id, "步骤 ID");
        requireText(tool, "工具名");
        requireText(description, "步骤说明");
        requireText(expectedOutcome, "预期结果");
        arguments = copyObject(Objects.requireNonNull(arguments, "工具参数不能为空"));
    }

    /** 既有业务代码的访问入口；持久化统一使用 tool 和 arguments。 */
    public String action() { return tool; }
    public Map<String, Object> parameters() { return arguments; }

    /** 无参数的动作使用空 Map；ID 由应用分配，不承载执行进度。 */
    public static Step create(String action, Map<String, Object> parameters,
                              String description, String expectedOutcome) {
        return new Step(UUID.randomUUID().toString(), action, parameters, description, expectedOutcome);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    private static Map<String, Object> copyObject(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("动作参数的对象键必须是字符串");
            }
            copy.put(key, copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 深拷贝后只暴露只读容器，避免调用方修改嵌套参数或传入可变游戏对象。 */
    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        if (value instanceof Map<?, ?> object) return copyObject(object);
        if (value instanceof List<?> array) {
            List<Object> copy = new ArrayList<>(array.size());
            for (Object item : array) copy.add(copyValue(item));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("动作参数只支持 JSON 值和有限数值：" + value.getClass().getName());
    }
}
