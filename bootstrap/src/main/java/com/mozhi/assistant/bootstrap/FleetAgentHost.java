package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.Global;
import com.mozhi.assistant.bridge.FleetAgentAccess;
import com.mozhi.assistant.bridge.FleetAgentBridge;
import java.net.URL;

/** 生命周期属于战役，与聊天会话独立；没有私有加载器实例写进存档。 */
final class FleetAgentHost {
    private static AgentClassLoader loader;
    private static FleetAgentBridge service;
    private FleetAgentHost() {}

    @SuppressWarnings("deprecation")
    static void reset() {
        retire(service, loader);
        service = null;
        loader = null;
        FleetAgentAccess.bind(null, "舰队控制器初始化中");
        try {
            URL base = FleetAgentHost.class.getProtectionDomain().getCodeSource().getLocation();
            loader = new AgentClassLoader(FleetAgentHost.class.getClassLoader(),
                    new URL(base, "fleet-agent.jar"), new URL(base, "mozhi-llm-client.jar"));
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                service = (FleetAgentBridge) loader.loadClass("com.mozhi.fleet.FleetDirector").newInstance();
                service.initialize(new URL(base, "../data/config/agent.properties").toExternalForm());
            } finally { Thread.currentThread().setContextClassLoader(previous); }
            FleetAgentAccess.bind(service, "");
        } catch (Exception | LinkageError failure) {
            retire(service, loader);
            service = null;
            loader = null;
            FleetAgentAccess.bind(null, "舰队控制器初始化失败，请查看 starsector.log");
            Global.getLogger(FleetAgentHost.class).error("Fleet agent initialization failed", failure);
        }
    }

    private static void retire(FleetAgentBridge oldService, AgentClassLoader oldLoader) {
        if (oldService != null) oldService.close();
        if (oldLoader == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                // 等待旧模型任务响应中断，避免读档后旧线程继续占用 JAR。
                while (oldService != null && !oldService.isStopped()) Thread.sleep(100);
                oldLoader.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) { }
        }, "Mozhi-Fleet-Cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    static void advance(float amount) {
        if (service == null) return;
        inRuntime(() -> service.advance(amount));
    }

    static void save() {
        if (service != null) inRuntime(service::save);
    }

    private static void inRuntime(Runnable action) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            action.run();
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
