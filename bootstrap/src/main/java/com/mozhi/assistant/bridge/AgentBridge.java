package com.mozhi.assistant.bridge;

/** The only API crossing the loader boundary. No framework or reflection types here. */
public interface AgentBridge {
    void initialize(String configUrl, GameThreadAccess gameThread) throws Exception;
    String chat(String message);
    String diagnostics();
    String toolTrace();
}
