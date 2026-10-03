package com.mozhi.assistant.bridge;

/** Bootstrap 持有的舰队控制接口。除 close 外均由游戏主线程调用；只传 JDK 类型。 */
public interface FleetAgentBridge {
    void initialize(String configUrl) throws Exception;
    String command(String requestJson);
    java.util.Map<String,Object> view();
    void advance(float amount);
    void save();
    void close();
    boolean isStopped();
}
