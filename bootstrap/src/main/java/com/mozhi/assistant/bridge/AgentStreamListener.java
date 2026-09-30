package com.mozhi.assistant.bridge;

/** 跨加载器只传递文本；实现方负责将后台回调交给 UI 主线程。 */
public interface AgentStreamListener {
    AgentStreamListener NONE = new AgentStreamListener() {};

    /** 每次模型请求开始，替换上一轮尚未定稿的回复。 */
    default void onResponseStart() {
    }

    /** 仅接收可见回答的增量，不包含内部推理或工具参数片段。 */
    default void onPartialText(String text) {
    }

    default void onStatus(String status) {
    }
}
