package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;

/**
 * Retired save-compatibility shell. Never created or displayed by this version.
 * Kept so XStream can load older saves; MozhiModPlugin removes existing entries.
 */
@Deprecated
public final class AgentDemoIntel extends BaseIntelPlugin {
    // Preserve the field name used by previous saves until the entry is removed.
    @SuppressWarnings("unused")
    private String prompt;

    @Override public boolean shouldRemoveIntel() { return true; }
}
