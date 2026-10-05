package com.mozhi.llm;

/** 结构化生成未完成；只携带可脱敏的诊断，不保留模型正文或思考内容。 */
public final class StructuredOutputException extends IllegalStateException {
    public enum Kind { OUTPUT_LIMIT, EMPTY_RESPONSE }
    private final Kind kind;
    public StructuredOutputException(Kind kind, String message) { super(message); this.kind = java.util.Objects.requireNonNull(kind); }
    public Kind kind() { return kind; }
}
