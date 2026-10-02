package example;

import java.util.Properties;
import java.util.function.Consumer;

/** 宿主与私有运行区共享；签名中只出现 JDK 类型。 */
public interface ChatBridge {
    void initialize(Properties properties);
    String chat(String prompt, Consumer<String> onText);
}
