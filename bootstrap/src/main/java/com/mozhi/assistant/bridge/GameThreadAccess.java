package com.mozhi.assistant.bridge;

import java.util.function.Supplier;

/** Executes game API work on the campaign thread, only when a tool invokes it. */
public interface GameThreadAccess {
    String call(Supplier<String> action);
}
