package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;

import java.util.ArrayList;

import org.lazywizard.console.Console;

public final class MozhiModPlugin extends BaseModPlugin {
    @Override
    public void onGameLoad(boolean newGame) {
        AgentSession.reset();
        // Old saves must resolve the retired class before onGameLoad can remove its entries.
        for (IntelInfoPlugin oldIntel : new ArrayList<>(
                Global.getSector().getIntelManager().getIntel(AgentDemoIntel.class))) {
            Global.getSector().getIntelManager().removeIntel(oldIntel);
        }
        Global.getSector().removeTransientScriptsOfClass(ReplyPoller.class);
        Global.getSector().addTransientScript(new ReplyPoller());
        Global.getSector().getListenerManager().removeListenerOfClass(ChatHotkeyListener.class);
        Global.getSector().getListenerManager().addListener(new ChatHotkeyListener(), true);
        ChatHotkeyListener.clearPending();
    }

    public static final class ReplyPoller implements EveryFrameScript {
        @Override public boolean isDone() { return false; }
        @Override public boolean runWhilePaused() { return true; }
        @Override public void advance(float amount) {
            ChatHotkeyListener.openPending();
            AgentSession session = AgentSession.current();
            if (session.poll() && session.reportsToConsole()) {
                Console.showMessage(session.resultText());
            }
        }
    }
}
