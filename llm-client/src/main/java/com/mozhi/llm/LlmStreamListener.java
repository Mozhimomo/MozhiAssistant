package com.mozhi.llm;

/** 调用方接收可见文本；回调可能发生在网络线程，不应直接修改游戏/UI 状态。 */
public interface LlmStreamListener {
    LlmStreamListener NONE = new LlmStreamListener() {};
    default void onResponseStart() {}
    default void onPartialText(String text) {}
    default void onStatus(String status) {}
}
