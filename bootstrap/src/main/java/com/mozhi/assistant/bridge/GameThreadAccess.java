package com.mozhi.assistant.bridge;

import java.util.function.Supplier;

/** 仅在工具调用时，将游戏 API 操作交给战役主线程执行。 */
public interface GameThreadAccess {
    String call(Supplier<String> action);
}
