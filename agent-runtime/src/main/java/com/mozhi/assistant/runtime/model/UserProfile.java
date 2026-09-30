package com.mozhi.assistant.runtime.model;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * 用户画像：一个用户一份，长期存储
 */
@Data
@Builder
@Jacksonized
@Accessors(chain = true)
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    // ===== 基础属性 =====
    private BasicInfo basic;

    // ===== 偏好 =====
    private Preferences preferences;

    // ===== 兴趣标签（带权重，可衰减） =====
    @Builder.Default
    private Map<String, Double> interests = new HashMap<>();

    // ===== 事实记忆 =====
    @Builder.Default
    private List<Fact> facts = new ArrayList<>();

    // ===== 行为统计 =====
    private Stats stats;

    // ===== 禁止项/硬约束 =====
    @Builder.Default
    private List<String> constraints = new ArrayList<>();

    // ===== 自由文本画像（LLM 生成的摘要） =====
    private String narrative;

    // ===== 元信息 =====
    private Instant createdAt;
    private Instant updatedAt;

    // ==================== 内部结构 ====================

    @Data
    @Builder
    @Jacksonized
    @Accessors(chain = true)
    public static class BasicInfo implements Serializable {
        private String name;
        private String nickname;
        /** ISO 639-1，如 zh / en */
        private String language;
        /** IANA 时区，如 Asia/Shanghai */
        private String timezone;
        private String occupation;
        private String location;
    }

    @Data
    @Builder
    @Jacksonized
    @Accessors(chain = true)
    public static class Preferences implements Serializable {
        /** 回复详细度：concise / normal / detailed */
        private String verbosity;
        /** 语气：formal / casual / friendly */
        private String tone;
        /** 是否偏好代码示例 */
        private Boolean preferCode;
        /** 是否偏好列表/表格 */
        private Boolean preferStructured;
        /** 自定义风格提示词，直接拼进 system prompt */
        private String styleHint;
    }

    @Data
    @Builder
    @Jacksonized
    @Accessors(chain = true)
    public static class Fact implements Serializable {
        /** 事实唯一 ID，便于更新/删除 */
        private String id;
        /** 事实内容，如"用户有一只叫豆豆的猫" */
        private String content;
        /** 分类：personal / work / preference / relationship ... */
        private String category;
        /** 置信度 0~1 */
        private Double confidence;
        /** 来源：user_stated / llm_inferred */
        private String source;
        private Instant createdAt;
        private Instant updatedAt;
        /** 最后一次被引用时间，用于衰减 */
        private Instant lastReferencedAt;
    }

    @Data
    @Builder
    @Jacksonized
    @Accessors(chain = true)
    public static class Stats implements Serializable {
        private Long totalSessions;
        private Long totalMessages;
        private Instant firstSeenAt;
        private Instant lastSeenAt;
        /** 常用工具名 -> 次数 */
        @Builder.Default
        private Map<String, Long> toolUsage = new HashMap<>();
        /** 活跃时段 0-23 -> 次数 */
        @Builder.Default
        private Map<Integer, Long> activeHours = new HashMap<>();
    }
}