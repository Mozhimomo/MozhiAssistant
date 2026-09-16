package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.apache.log4j.Logger;
import org.lazywizard.console.Console;

public final class MozhiModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() {
        Logger log = Global.getLogger(MozhiModPlugin.class);
        log.info("[MozhiReflectionProbe] Plugin loader: " + MozhiModPlugin.class.getClassLoader());
        try {
            // Deliberately execute here, outside AgentClassLoader and the private runtime.
            Method method = MozhiModPlugin.class.getDeclaredMethod("reflectionProbeTarget");
            method.setAccessible(true);
            Object result = method.invoke(this);
            log.info("[MozhiReflectionProbe] ALLOWED: direct plugin reflection returned " + result);
        } catch (SecurityException e) {
            log.warn("[MozhiReflectionProbe] DENIED: direct plugin reflection was rejected; "
                    + "check the exception for the game's script reflection filter.", e);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("[MozhiReflectionProbe] FAILED: direct plugin reflection could not complete; "
                    + "this alone does not prove the game's reflection filter is active.", e);
        }
    }

    private String reflectionProbeTarget() {
        return "mozhi-direct-reflection-ok";
    }

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
