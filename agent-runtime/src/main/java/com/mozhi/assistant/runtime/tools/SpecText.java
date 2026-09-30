package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.WithSourceMod;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.Consumer;

/** 规格详情共享的文本格式与分节容错。 */
final class SpecText {
    private SpecText() {
    }

    static String description(String id, Description.Type type) {
        if (id == null || id.isBlank()) {
            return "未提供";
        }
        try {
            Description description = Global.getSettings().getDescription(id, type);
            if (description == null) {
                return "未提供";
            }
            var paragraphs = new LinkedHashSet<String>();
            for (String text : new String[]{description.getText1(), description.getText2(), description.getText3(),
                    description.getText4(), description.getText5()}) {
                if (text != null && !text.isBlank()) {
                    paragraphs.add(text.strip());
                }
            }
            return paragraphs.isEmpty() ? "未提供" : String.join("\n", paragraphs);
        } catch (RuntimeException exception) {
            return "描述不可用 [" + id + "]";
        }
    }

    static void source(StringBuilder out, WithSourceMod spec) {
        var mod = spec.getSourceMod();
        line(out, "来源模组", mod == null ? "未标注" : mod.getName() + " [" + mod.getId() + "] " + mod.getVersion());
    }

    static void section(StringBuilder out, String title, Consumer<StringBuilder> reader) {
        out.append('【').append(title).append("】\n");
        try {
            reader.accept(out);
        } catch (RuntimeException exception) {
            out.append("本节部分数据不可用：").append(exception.getClass().getSimpleName()).append('\n');
        }
    }

    static void line(StringBuilder out, String label, Object value) {
        out.append(label).append("：");
        if (value == null || value instanceof String text && text.isBlank()) {
            out.append("未提供");
        } else if (value instanceof Float || value instanceof Double) {
            out.append(number(((Number) value).doubleValue()));
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "是" : "否");
        } else {
            out.append(value);
        }
        out.append('\n');
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }
}
