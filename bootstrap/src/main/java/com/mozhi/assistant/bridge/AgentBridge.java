package com.mozhi.assistant.bridge;

/** 唯一跨越类加载器边界的接口，不包含框架或反射类型。 */
public interface AgentBridge {
    void initialize(String configUrl, GameThreadAccess gameThread) throws Exception;
    String chat(String message);

    default String chat(String message, AgentStreamListener listener) {
        return chat(message);
    }
    /** 根据主线程提供的异常快照主动汇报；该回合不执行工具或修改游戏状态。 */
    default String notifyFleetIntervention(String snapshot, AgentStreamListener listener) {
        throw new UnsupportedOperationException("对话运行区不支持舰队异常通知，请更新运行 JAR");
    }
    String diagnostics();
    String toolTrace();
}
