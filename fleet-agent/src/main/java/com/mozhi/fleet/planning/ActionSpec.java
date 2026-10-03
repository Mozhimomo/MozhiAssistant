package com.mozhi.fleet.planning;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 执行器向规划器公开的动作契约；游戏中的前提和效果写在 description 中。 */
public record ActionSpec(String name, String description, List<Parameter> parameters) {
    public enum Type { STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY }

    public record Parameter(String name, Type type, boolean required, String description) {
        public Parameter {
            text(name, "参数名");
            Objects.requireNonNull(type, "参数类型");
            text(description, "参数说明");
        }
    }

    public ActionSpec {
        text(name, "动作名");
        text(description, "动作说明");
        parameters = List.copyOf(parameters);
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) throw new IllegalArgumentException("重复参数：" + parameter.name());
        }
    }

    /** 检查声明的参数名、必填项和 JSON 类型；范围和游戏前提仍需执行器核实。 */
    public void validate(Map<String, Object> values) {
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            names.add(parameter.name());
            if (!values.containsKey(parameter.name())) {
                if (parameter.required()) throw new IllegalArgumentException("缺少动作参数：" + parameter.name());
                continue;
            }
            Object value = values.get(parameter.name());
            boolean valid = switch (parameter.type()) {
                case STRING -> value instanceof String;
                case INTEGER -> value instanceof Number number && new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
                case NUMBER -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof List<?>;
            };
            if (!valid) throw new IllegalArgumentException("动作参数类型错误：" + parameter.name());
        }
        for (String key : values.keySet()) {
            if (!names.contains(key)) throw new IllegalArgumentException("未声明的动作参数：" + key);
        }
    }

    static void text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }
}
