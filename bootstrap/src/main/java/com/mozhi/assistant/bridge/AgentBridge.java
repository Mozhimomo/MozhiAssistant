package com.mozhi.assistant.bridge;

/** The only API crossing the loader boundary. No framework or reflection types here. */
public interface AgentBridge {
    void initialize(String configUrl, GameThreadAccess gameThread) throws Exception;
    String chat(String message);

    default String chat(String message, AgentStreamListener listener) {
        return chat(message);
    }
    String diagnostics();
    String toolTrace();
}
