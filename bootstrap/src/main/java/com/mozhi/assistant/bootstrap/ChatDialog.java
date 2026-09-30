package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import java.util.Collections;
import java.util.Map;

/** Console Commands can schedule only interaction dialogs; immediately hand off to our own overlay. */
public final class ChatDialog implements InteractionDialogPlugin {
    @Override public void init(InteractionDialogAPI dialog) {
        dialog.dismiss();
        ChatHotkeyListener.requestOpen();
    }
    @Override public void optionSelected(String text, Object data) { }
    @Override public void optionMousedOver(String text, Object data) { }
    @Override public void advance(float amount) { }
    @Override public void backFromEngagement(EngagementResultAPI result) { }
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return Collections.emptyMap(); }
}