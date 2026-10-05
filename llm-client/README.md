# 墨汁大模型客户端

Java 17 的 LLM 调用基础设施，支持 OpenAI 兼容的 Chat Completions 接口。

模块独立于墨汁 agent、游戏 API、UI、记忆与工具执行。提供同步请求、流式正文回调、超时、重试、环境变量密钥、思考参数以及错误脱敏。调用方传入历史和工具定义，取得完整 LangChain4j 响应，自行决定如何处理工具请求。

## 用量诊断

`UsageMetrics.configure(configUrl, "fleet" 或 "chat")` 将元数据日志写到配置目录的同级 `diagnostics` 目录；不配置时仅在内存累计。通过 `try (var scope = UsageMetrics.scope("review")) { ... }` 分类调用，`snapshot()` 查看当前类加载器生命周期汇总。同步、AI Service（含空正文重试）和流式调用均计数，支持 OpenAI cachedTokens 和 DeepSeek 原始 usage 的命中/未命中字段。缺失用量保留为未知，失败或取消不代表无费用，SDK 内部重试可能不可见。

结构化回答截断或重试后仍无正文时，抛出 `StructuredOutputException`，分别标记 `OUTPUT_LIMIT` / `EMPTY_RESPONSE`；错误脱敏后仍保留类型，不保留原始异常链。业务层可以据此进行有界恢复。日志额外记录 `answerChars`、`thinkingChars` 两个长度，不记录正文或思考文本；舰队恢复请求单列为 `planning_recovery`。

日志只含分类、字符数、时间、耗时、token 数及完成原因，不记录原始请求/响应。每份日志超过 5 MiB 时轮转并保留一份旧文件，写入失败不影响游戏操作，可通过 `logErrors` 查看失败计数。

## 引入方式

### 通过 Maven 引入

先在本模块目录执行：

```powershell
mvn -Dmaven.test.skip=true package
mvn -Dmaven.test.skip=true install
```

本模块有独立 POM，可以单独复制到游戏目录之外构建，无需 Starsector 或 Console Commands。尚未发布到 Maven Central；其他机器需自行构建安装，或使用下面的单 JAR。

消费项目只需声明这一项依赖，Maven 自动解析其传递依赖：

```xml
<dependency>
    <groupId>com.mozhi</groupId>
    <artifactId>mozhi-llm-client</artifactId>
    <version>0.1.4</version>
</dependency>
```

### 直接引入一个 JAR

构建得到：

- `target/mozhi-llm-client-0.1.4.jar`：普通 Maven 构件，依赖由 Maven 管理。
- `target/mozhi-llm-client-0.1.4-all.jar`：包含 LangChain4j 的 OpenAI 客户端、核心请求/响应类型、HTTP 客户端、Jackson 等运行依赖。手动引入时选这个文件。

两者选一种。单 JAR 未重定位第三方包，其他版本的 LangChain4j/Jackson 应使用独立类加载器隔离。单 JAR 同时包含 LangChain4j 的工具和 AiServices 模块；LlmClient 接口本身只负责模型调用，不自动执行工具。

## 普通 Java 项目

复制 `llm.properties.example` 并填写配置，然后：

```java
import com.mozhi.llm.LlmClient;
import com.mozhi.llm.LlmConfig;
import java.nio.file.Path;

LlmClient client = LlmClient.create(
        LlmConfig.load(Path.of("llm.properties").toUri().toString()));
String answer = client.chat("你好");
```

客户端可复用，不自动保存会话。流式方法也会等待完整响应，应在自己的后台线程调用；回调可能来自网络线程，UI 更新由调用方调度。

### 历史、工具定义和流式回复

```java
import com.mozhi.llm.LlmStreamListener;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;

ChatRequest request = ChatRequest.builder()
        .messages(List.of(
                SystemMessage.from("请用中文简短回答。"),
                UserMessage.from("我想买一艘船"),
                AiMessage.from("你希望它承担什么任务？"),
                UserMessage.from("护航")))
        // .toolSpecifications(yourToolSpecifications)
        .build();

ChatResponse response = client.stream(request, new LlmStreamListener() {
    @Override
    public void onPartialText(String text) {
        System.out.print(text);
    }
});
// response 包含最终正文、工具调用请求、结束原因和 token 用量。
// 工具调用由业务代码执行，结果以 ToolExecutionResultMessage 放入下一次请求。
```

`client.chat(request)` 始终同步请求；`client.stream(request, listener)` 在配置关闭流式时会同步请求，然后发布完整正文。模型生成的思考和工具参数片段不发送到正文监听器。

同步重试由 `maxRetries` 控制。流式连接异常在尚未发布正文时独立重试一次；已发布正文则失败并保留片段，不自动重跑业务或工具。流式总等待上限为每次尝试的 `timeoutSeconds`，调用线程中断可取消等待。

### 类型化输出（AI Services）

定义公开接口，方法直接返回 Java POJO 或 record，再通过本库创建 LangChain4j AI Service：

```java
public record ShipChoice(String hullId, String reason) {}

public interface ShipAdvisor {
    @dev.langchain4j.service.SystemMessage("根据输入需求推荐一艘舰船，并说明原因。")
    ShipChoice choose(@dev.langchain4j.service.UserMessage String request);
}

ShipAdvisor advisor = client.aiService(ShipAdvisor.class);
ShipChoice choice = advisor.choose("需要一艘便宜的护航舰");
```

上述公开类型分别放在对应的 Java 文件中。接口和返回类型都应在私有运行区加载；它们可以包含嵌套对象、列表与枚举。框架负责格式要求和反序列化，业务代码仍需校验结果。该入口提供同步、无历史记忆的服务，不自动执行工具；后台线程调用，沿用客户端连接参数、模型重试、私有类加载上下文及异常脱敏。

在配置中设置 `structuredOutputMode`：

- `prompt`（默认）：LangChain4j 根据返回类型自动生成格式提示，然后解析为对象，适用于不支持原生 Schema 的兼容接口。
- `json_schema`：启用模型的 `RESPONSE_FORMAT_JSON_SCHEMA` 和严格 Schema，AI Services 自动从返回类型生成 Schema。需要服务端支持；不支持时会报错，不静默降级。

两种模式都是类型化调用；服务端 Schema 约束只在第二种模式启用。此设置不会把普通 `chat` / `stream` 对话强制变成 JSON。通过 `LlmClient.of(...)` 提供自建模型时，由该模型声明 `supportedCapabilities()`。

### 派生不同用途的配置

```java
LlmConfig config = LlmConfig.load(configUrl);
LlmClient summaryClient = LlmClient.create(config.withGeneration(8192, null, null));
```

三个参数是生成预算、thinkingMode、reasoningEffort。思考参数为 null 表示不发送，不继承原配置。服务支持时可以传 `"disabled", "none"`。摘要策略和最终摘要保留方式属于调用方业务，本库不截取返回内容。

`LlmClient.of(ChatModel, StreamingChatModel, timeoutSeconds)` 支持自建模型/其他提供商；此入口没有密钥配置，调用方需自行保证该模型的异常信息已脱敏。

## 在远行星号等受限宿主中使用

普通游戏加载器下直接创建 LangChain4j 模型仍可能遇到反射限制。使用本包的 `IsolatedLlmRuntime`，将 LLM 实现放进私有运行区。

本模块的 `examples/` 提供完整源码：

- `example.ChatBridge`：宿主共享接口，只使用 JDK 类型。
- `example.runtime.ChatEntry`：公开无参入口，内部使用 LlmClient。
- `example.IsolatedChatExample`：启动示例。

自己的 Mod 将 all JAR 和业务 JAR 放在 `jars/`，在 `mod_info.json` 中注册它们供宿主加载接口与加载器。宿主不要直接引用 ChatEntry、LlmClient 或 LangChain4j 类型；通过以下方式调用：

```java
// llmJarUrl 指向本库 all JAR，yourModJarUrl 指向包含 ChatEntry 的业务 JAR。
var runtime = IsolatedLlmRuntime.open(
        ChatBridge.class, "example.runtime.ChatEntry", llmJarUrl, yourModJarUrl);
runtime.call(entry -> { entry.initialize(properties); return null; });

// 后台线程调用；textQueue 交由游戏主线程消费。
String answer = runtime.call(entry -> entry.chat("你好", textQueue::add));

// 在请求结束、业务不再使用它时关闭；不要每次回复都重建或在请求中途关闭。
runtime.close();
```

共享接口及其嵌套类型从宿主加载；JDK 类型从平台加载器读取；其他实现和 SDK 从指定 JAR 读取。服务发现只搜索私有 JAR。若业务入口必须引用宿主 API，可使用带 `String[] sharedPackages` 的重载显式共享包，例如 `new String[]{"com.fs.", "org.lwjgl."}`。游戏数据读写仍应在游戏主线程执行。

这解决的是类加载隔离，不能绕过 Java 模块强封装或授予进程原本没有的权限。

当前墨汁 agent 的游戏专用类加载器同时加载 `agent-runtime.jar` 与 `mozhi-llm-client.jar`，二者共享同一个私有运行区。根项目打包时将本模块的 all JAR 复制为 `jars/mozhi-llm-client.jar`，agent JAR 不再内嵌本库及第三方依赖。单独构建本模块只生成 target 下的产物。

## 编译示例

在本模块目录执行（Windows）：

```powershell
javac -encoding UTF-8 --release 17 -cp target/mozhi-llm-client-0.1.4-all.jar -d target/example-classes examples/example/ChatBridge.java examples/example/runtime/ChatEntry.java examples/example/IsolatedChatExample.java
```

仅编译示例不发送模型请求。填写自己的配置后可自行运行 `example.IsolatedChatExample`。

构建后运行 `./verify.ps1` 可离线检查类型化输出、Schema 配置、类加载上下文恢复、错误脱敏和取消，不请求模型服务。

## 配置兼容

使用现有 agent.properties 中的连接字段即可；额外业务字段会被忽略。支持：

`baseUrl`、`modelName`、`apiKey`、`timeoutSeconds`、`streamingEnabled`、`structuredOutputMode`、`maxRetries`、`maxTokens` / `maxCompletionTokens`（二选一）、`temperature`、`topP`、`presencePenalty`、`frequencyPenalty`、`seed`、`thinkingMode`、`reasoningEffort`。

`apiKey` 可直接填真实密钥，或填写 `${MOZHI_API_KEY}` 等环境变量引用。库不记录 HTTP 请求/响应日志，配置创建的客户端会脱敏抛出的错误信息。

`LlmConfig.cheapFrom(properties)` 派生独立轻量模型配置，空项继承主模型。支持 `cheapModelName`、`cheapBaseUrl`、`cheapApiKey`、`cheapTimeoutSeconds`、`cheapMaxRetries`、`cheapStreamingEnabled`、`cheapTemperature`、`cheapTopP`、`cheapMaxTokens` / `cheapMaxCompletionTokens`、`cheapThinkingMode`、`cheapReasoningEffort` 和 `cheapStructuredOutputMode`。派生配置不修改主模型，不将密钥写回文件；两项输出预算不能同时填写。
