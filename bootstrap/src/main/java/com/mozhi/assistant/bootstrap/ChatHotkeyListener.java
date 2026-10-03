package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.mozhi.assistant.bootstrap.ui.ChatWindow;
import com.mozhi.assistant.bootstrap.ui.CampaignChatButton;
import java.util.List;
import org.lazywizard.console.Console;
import org.lwjgl.input.Keyboard;

/** Modal campaign overlay: custom rendering, exclusive input and reversible pause state. */
public final class ChatHotkeyListener implements CampaignInputListener, CampaignUIRenderingListener {
    private static boolean pending;
    private static boolean open;
    private static boolean wasPaused;
    private static boolean repeatWasEnabled;
    private static ChatWindow window;
    private static CampaignChatButton launcher;

    @Override public int getListenerInputPriority() { return 10000; }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (open) {
            // Consume the whole batch even if Esc closes the window midway through it.
            for (InputEventAPI event : events) {
                try {
                    if (!event.isConsumed() && open && window != null) window.input(event);
                } catch (RuntimeException | LinkageError exception) {
                    fail(exception);
                }
                event.consume();
            }
            if (open) suppressInteractions();
            return;
        }
        if (!canOpen()) { if (launcher != null) launcher.hidden(); return; }
        for (InputEventAPI event : events) {
            if (!event.isConsumed() && launcher != null && launcher.input(event)) continue;
            if (!event.isConsumed() && event.isKeyDownEvent() && !event.isRepeat()
                    && event.isCtrlDown() && event.isShiftDown() && !event.isAltDown()
                    && event.getEventValue() == Keyboard.KEY_M) {
                requestOpen();
                event.consume();
            }
        }
    }

    static void requestOpen() { pending = true; }

    static void clearPending() {
        pending = false;
        // Loading a save must not restore the pause state from the previous campaign.
        open = false;
        if (launcher != null) launcher.reset();
        if (window != null) Keyboard.enableRepeatEvents(repeatWasEnabled);
        // GPU deletion is deferred to the next render callback, where a GL context exists.
    }

    static void openPending() {
        if (launcher == null) launcher = new CampaignChatButton(ChatHotkeyListener::requestOpen);
        launcher.update(open);
        if (open) {
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui.isShowingDialog() || ui.isShowingMenu() || ui.getCurrentCoreTab() != null) {
                close();
            } else {
                suppressInteractions();
                try { window.update(); }
                catch (RuntimeException | LinkageError exception) { fail(exception); }
            }
        }
        if (!pending) return;
        pending = false;
        if (!canOpen()) return;
        try {
            // Reuse the closed canvas until its texture is disposed on the render thread.
            if (window == null) window = new ChatWindow(ChatHotkeyListener::close);
            wasPaused = Global.getSector().isPaused();
            repeatWasEnabled = Keyboard.areRepeatEventsEnabled();
            Keyboard.enableRepeatEvents(true);
            open = true;
            launcher.acknowledge();
            suppressInteractions();
        } catch (RuntimeException | LinkageError exception) {
            fail(exception);
        }
    }

    private static void suppressInteractions() {
        Global.getSector().setPaused(true);
        Global.getSector().getCampaignUI().setDisallowPlayerInteractionsForOneFrame();
    }

    private static void close() {
        if (!open) return;
        open = false;
        Keyboard.enableRepeatEvents(repeatWasEnabled);
        if (Global.getSector() != null) {
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (!ui.isShowingDialog() && !ui.isShowingMenu()) Global.getSector().setPaused(wasPaused);
        }
    }

    private static boolean canOpen() {
        if (open || Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return false;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        return !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null;
    }

    @Override
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        try {
            if (open && window != null) window.render();
            else if (window != null) {
                try { window.dispose(); }
                finally { window = null; }
            }
            if (!open && launcher != null) {
                if (canOpen()) launcher.render();
                else launcher.hidden();
            }
        } catch (RuntimeException | LinkageError exception) {
            fail(exception);
        }
    }

    private static void fail(Throwable error) {
        close();
        pending = false;
        Global.getLogger(ChatHotkeyListener.class).error("Could not display Mozhi chat", error);
        Console.showMessage("墨汁窗口无法显示，请查看 starsector.log；可继续用 MozhiAgent test 交互。");
    }

    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
        if (open) events.forEach(InputEventAPI::consume);
    }
    @Override public void processCampaignInputPostCore(List<InputEventAPI> events) { }
    @Override public void renderInUICoordsBelowUI(ViewportAPI viewport) { }
    @Override public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) { }
}
