package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.List;
import org.lwjgl.input.Keyboard;

/** Opens from the campaign map only; never replaces an existing interaction or core screen. */
public final class ChatHotkeyListener implements CampaignInputListener {
    private static boolean pending;

    @Override public int getListenerInputPriority() { return 100; }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (!canOpen()) return;
        for (InputEventAPI event : events) {
            if (!event.isConsumed() && event.isKeyDownEvent() && !event.isRepeat()
                    && event.isCtrlDown() && event.isShiftDown() && !event.isAltDown()
                    && event.getEventValue() == Keyboard.KEY_M) {
                pending = true;
                event.consume();
            }
        }
    }

    static void clearPending() { pending = false; }

    static void openPending() {
        if (!pending) return;
        pending = false;
        if (canOpen()) {
            Global.getSector().getCampaignUI().showInteractionDialog(new ChatDialog(),
                    Global.getSector().getPlayerFleet());
        }
    }

    private static boolean canOpen() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return false;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        return !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null;
    }

    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) { }
    @Override public void processCampaignInputPostCore(List<InputEventAPI> events) { }
}
