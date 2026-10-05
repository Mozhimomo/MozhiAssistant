package com.mozhi.assistant.bridge;

/** 两个私有运行区共享同一入口；聊天重置不销毁舰队控制器。 */
public final class FleetAgentAccess {
    private static FleetAgentBridge current;
    private static String error = "舰队控制器尚未初始化";
    private FleetAgentAccess() {}
    public static void bind(FleetAgentBridge service, String failure) {
        current = service;
        error = failure;
    }
    /** 只读状态供 UI 使用，不调用模型，不执行交易。 */
    public static java.util.Map<String,Object> view() {
        FleetAgentBridge service=current;
        if(service==null) return java.util.Map.of("error",error);
        Thread thread=Thread.currentThread();
        ClassLoader previous=thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(service.getClass().getClassLoader());
            return service.view();
        } catch(RuntimeException failure) {
            return java.util.Map.of("error","舰队状态暂不可用："+String.valueOf(failure.getMessage()));
        } finally {thread.setContextClassLoader(previous);}
    }
    public static String command(String json) {
        if (current == null) throw new IllegalStateException(error);
        FleetAgentBridge service = current;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(service.getClass().getClassLoader());
            return service.command(json);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

}
