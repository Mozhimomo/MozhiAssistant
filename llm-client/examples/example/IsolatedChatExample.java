package example;

import com.mozhi.llm.isolation.IsolatedLlmRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 普通 JVM 的隔离加载示例；需要服务调用时由开发者手动运行。 */
public final class IsolatedChatExample {
    private IsolatedChatExample() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("参数：LLM all JAR、示例 classes 目录、配置文件");
        }
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.UTF_8)) {
            // 跳过可选的 UTF-8 编码标记。
            reader.mark(1);
            if (reader.read() != '\uFEFF') reader.reset();
            config.load(reader);
        }
        try (var runtime = IsolatedLlmRuntime.open(ChatBridge.class, "example.runtime.ChatEntry",
                Path.of(args[0]).toUri().toURL(), Path.of(args[1]).toUri().toURL())) {
            runtime.call(entry -> { entry.initialize(config); return null; });
            String answer = runtime.call(entry -> entry.chat("你好", System.out::print));
            System.out.println("\n完整回复：" + answer);
        }
    }
}
