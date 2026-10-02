package example.runtime;

import com.mozhi.llm.LlmClient;
import com.mozhi.llm.LlmConfig;
import com.mozhi.llm.LlmStreamListener;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import example.ChatBridge;
import java.util.Properties;
import java.util.function.Consumer;

/** 该实现由私有加载器加载；无 game API 依赖，可直接用于自己的 Mod。 */
public final class ChatEntry implements ChatBridge {
    private LlmClient client;

    public ChatEntry() {}

    @Override
    public void initialize(Properties properties) {
        client = LlmClient.create(LlmConfig.from(properties));
    }

    @Override
    public String chat(String prompt, Consumer<String> onText) {
        if (client == null) throw new IllegalStateException("请先 initialize");
        ChatResponse response = client.stream(
                ChatRequest.builder().messages(UserMessage.from(prompt)).build(),
                new LlmStreamListener() {
                    @Override
                    public void onPartialText(String text) { onText.accept(text); }
                });
        return response.aiMessage().text();
    }
}
