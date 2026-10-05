package com.mozhi.llm;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Set;

final class MeasuredChatModel implements ChatModel {
    private final ChatModel delegate;
    MeasuredChatModel(ChatModel delegate) { this.delegate = delegate; }
    @Override public ChatResponse chat(ChatRequest request) { return UsageMetrics.measure(request, () -> delegate.chat(request)); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
}
