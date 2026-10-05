package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;

/** 已停用的存档兼容外壳，本版本不会创建或显示。保留此类供 XStream 加载旧存档，已有条目由 MozhiModPlugin 移除。 */
@Deprecated
public final class AgentDemoIntel extends BaseIntelPlugin {
    // 在条目被移除前，保留旧存档使用的字段名。
    @SuppressWarnings("unused")
    private String prompt;

    @Override public boolean shouldRemoveIntel() { return true; }
}
