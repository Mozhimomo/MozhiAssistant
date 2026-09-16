package com.mozhi.assistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozhi.assistant.bridge.AgentBridge;
import com.mozhi.assistant.bridge.GameThreadAccess;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.lang.reflect.Proxy;

/** All framework objects, tool classes and proxy interfaces live inside agent-runtime.jar. */
public final class LangChainAgent implements AgentBridge {
    public interface Assistant {
        String chat(String message);
    }

    private DemoTools tools;
    private MessageWindowChatMemory memory;
    private Assistant assistant;
    private AgentConfig config;

    @Override
    public void initialize(String configUrl, GameThreadAccess gameThread) throws Exception {
        config = AgentConfig.load(configUrl);
        tools = new DemoTools(gameThread);
        try {
            OpenAiChatModel.OpenAiChatModelBuilder modelBuilder = OpenAiChatModel.builder()
                    .baseUrl(config.baseUrl)
                    .apiKey(config.apiKey)
                    .modelName(config.modelName)
                    .timeout(config.timeout)
                    .maxRetries(config.maxRetries)
                    .logRequests(false)
                    .logResponses(false);
            // Omit blank optional parameters; some compatible providers reject unsupported fields.
            if (config.temperature != null) modelBuilder.temperature(config.temperature);
            if (config.topP != null) modelBuilder.topP(config.topP);
            if (config.maxTokens != null) modelBuilder.maxTokens(config.maxTokens);
            if (config.maxCompletionTokens != null) modelBuilder.maxCompletionTokens(config.maxCompletionTokens);
            if (config.presencePenalty != null) modelBuilder.presencePenalty(config.presencePenalty);
            if (config.frequencyPenalty != null) modelBuilder.frequencyPenalty(config.frequencyPenalty);
            if (config.seed != null) modelBuilder.seed(config.seed);
            OpenAiChatModel model = modelBuilder.build();
            memory = MessageWindowChatMemory.withMaxMessages(config.memoryMaxMessages);
            assistant = AiServices.builder(Assistant.class)
                    .chatModel(model)
                    .systemMessageProvider(memoryId -> config.systemPrompt)
                    .chatMemory(memory)
                    .tools(tools)
                    .maxSequentialToolsInvocations(config.maxSequentialToolsInvocations)
                    .build();
        } catch (RuntimeException e) {
            throw sanitized(e);
        }
    }

    @Override
    public String chat(String message) {
        tools.beginTurn();
        try {
            return assistant.chat(message);
        } catch (RuntimeException e) {
            // An interrupted/failed tool exchange must not poison subsequent chat history.
            memory.clear();
            throw sanitized(e);
        }
    }

    private IllegalStateException sanitized(RuntimeException failure) {
        String message = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        if (config != null) message = message.replace(config.apiKey, "[REDACTED]");
        return new IllegalStateException(message);
    }

    @Override
    public String diagnostics() {
        ClassLoader runtime = getClass().getClassLoader();
        return "运行区：" + runtime.getName()
                + "\nLangChain4j 位于运行区：" + (AiServices.class.getClassLoader() == runtime)
                + "\nJackson 位于运行区：" + (ObjectMapper.class.getClassLoader() == runtime)
                + "\nAiServices 动态代理已创建：" + Proxy.isProxyClass(assistant.getClass())
                + "\n桥接接口由父加载器共享：" + (AgentBridge.class.getClassLoader() != runtime);
    }

    @Override
    public String toolTrace() { return tools.trace(); }
}
