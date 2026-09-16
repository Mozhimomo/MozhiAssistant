# 墨汁助手：Starsector + LangChain4j 最小智能体

Java 17 / Starsector 0.98a-RC5 / Maven 多模块项目。旧 Java、Kotlin 源码已移除。
示例使用 LangChain4j 1.15.0 的 `AiServices` 动态代理、短期聊天记忆和两个私有 `@Tool` 方法。
需要启用 **Console Commands**。战役地图按 **Ctrl+Shift+M** 打开聊天窗口，`MozhiAgent` 指令保留用于调试。不添加情报页。

## 游戏内聊天

1. 填好 `data/config/agent.properties`，构建后重启游戏并载入战役存档。
2. 在战役地图按 **Ctrl+Shift+M**。也可打开控制台输入 **`MozhiAgent chat`**，关闭控制台后进入聊天窗口。
   使用快捷键前先关闭改装、情报等核心界面和其他交互对话。
3. 左侧是可滚动的对话记录，右侧输入消息，点击下方 **“发送”**。输入支持粘贴，最多 2000 个字符。
4. 请求期间显示“墨汁正在思考”，完成后自动显示回复；错误也显示在对话中。发送按钮防止重复提交。
5. **“关闭” / Esc** 保留当前对话和输入草稿，再次打开继续聊天。
   **“新对话 / 重读配置”** 取消当前请求、清空界面记录和模型记忆，下次发送重新读取配置。

这是同一个 LangChain4j 对话智能体，支持连续聊天，并可按需使用舰队摘要、加法工具。
UI 与调试指令共用会话；UI 正常聊天不向控制台输出诊断信息。
本次运行最多保留最近 100 条显示记录，模型记忆长度由 `memoryMaxMessages` 单独控制。
对话和草稿不会写入存档，重新载入或退出游戏会清空。关闭聊天窗口不会取消正在进行的请求。
界面使用游戏原生文本与选项面板，窗口内也会轮询结果，因此暂停战役时仍可收到回复。

## 构建

在本目录使用 **JDK 17 或更新版本**和 Maven 3.9：

```powershell
mvn -Dmaven.test.skip=true clean package
```

默认从 `../../starsector-core/starfarer.api.jar` 编译游戏入口。异地开发可覆盖：

```powershell
mvn "-Dstarsector.core=D:/Game/starsector0.98/Starsector/starsector-core" -Dmaven.test.skip=true clean package
```

产物自动复制到 `jars/mozhi-bootstrap.jar` 和 `jars/agent-runtime.jar`。
游戏 API 使用 Maven `system` 作用域引用本机文件；不从公共仓库下载、不打入产物。
Console Commands 编译依赖默认位于 `../Console Commands/jars/lw_Console.jar`，可用
`"-Dconsole.jar=D:/path/to/lw_Console.jar"` 覆盖；该依赖也不打入产物。
旧 `lib/`、旧 JAR 保留在磁盘，但新构建不引用，`mod_info.json` 也不加载它们。
更新代码后退出并重启游戏，让已加载的类和 JAR 文件句柄释放。

## 手动验证

1. 编辑 `data/config/agent.properties` 的 `baseUrl`、`modelName`、`apiKey`。
   地址使用 OpenAI 兼容的 API 根地址（通常到 `/v1/`，不要加 `/chat/completions`）。
   模型必须支持工具调用。密钥也可通过游戏进程继承的 `MOZHI_API_KEY` 环境变量提供。
2. 使用 Java 17 启动游戏，启用本 Mod 和 Console Commands，开始或载入一个存档。
   上一版 `AgentDemoIntel` 的情报条目会在读档时移除，保留最小兼容类供旧存档解析。
   更早版本含已删除的 `Test_intel` 等类的存档仍不能保证兼容。
3. 在战役地图中按 **Ctrl + Backspace** 打开控制台（默认快捷键），输入 `MozhiAgent test`。
4. 完成后查看控制台输出；也可输入 `MozhiAgent status` 主动获取结果。应看到模型回复及实际工具调用记录：
   `getFleetSummary() -> ...`、`add(17, 25) -> 42`。
5. 诊断区应显示 LangChain4j/Jackson 位于运行区、动态代理已创建、桥接接口由父加载器共享均为 `true`。
   只有回复、没有工具调用记录，说明模型没有执行工具，不能据此判定工具反射链路验证成功。

### 指令

```text
MozhiAgent
MozhiAgent chat
MozhiAgent test
MozhiAgent test 请调用加法工具计算 17 + 25
MozhiAgent status
MozhiAgent reset
```

`chat`：关闭控制台后打开聊天界面。无参数或 `test`：使用默认提问验证舰队摘要和加法工具。
`test <文本>`：发送自定义消息。`status`：查看进度、最近回复和调用记录。
`reset`：取消当前请求并清空会话，下次 `test` 重新读取配置。
仅支持战役地图；战斗、模拟战中会提示上下文不适用。请求进行中不会重复提交。

指令注册表：`data/console/commands.csv`。
调用路径：`MozhiAgentCommand.runCommand()` → `runTest(String)` → `AgentSession.send()` → 运行区 `LangChainAgent.chat()`。
需要修改测试逻辑时编辑 `MozhiAgentCommand.runTest(String)`；这个方法应在战役主线程调用。

不会在启动或载入存档时自动请求模型。执行测试指令会把输入和工具返回的舰队名称、舰船数量发给配置的服务。
默认最多进行 4 轮连续工具调用；每个 HTTP 请求独立超时，默认不自动重试，均可通过配置调整。
`MozhiAgent reset` 清空记忆、取消当前请求，并在下次测试时重新读取配置。
会话不写入存档。发送消息时不采集舰队；只有模型实际调用工具时，才通过
`GameThreadAccess.call(...)` 在游戏主线程执行 `Global.getSector().getPlayerFleet().getFleetData()`。
每次工具调用重新读取，返回位置、资源、逐舰型号、舰长、CR、船体、武器、战机和插件等详情。
`getMembersListCopy()` 只是本次调用中遍历成员的 API，不作为跨请求缓存。
HTTP 请求在后台线程进行，游戏界面由主线程更新；`addOnslaught()` 等修改游戏状态的工具同样回到主线程。
使用控制台测试时，提交后关闭控制台返回战役，让主线程处理工具请求；也可用 `status` 推进，或直接使用聊天窗口。

## 模型与智能体配置

首次克隆后，将 `data/config/agent.properties.example` 复制为 `data/config/agent.properties`，再填写模型和密钥。文件按 UTF-8 读取。
实际配置文件已被 Git 忽略，只上传不含密钥的示例模板。本地游戏依赖、构建产物和 Maven 缓存也不上传。
已有地址、模型名、密钥保持原值；新增配置缺失时仍可按默认行为运行。

| 配置项 | 默认值 / 留空行为 | 用途与范围 |
| --- | --- | --- |
| `baseUrl` | `https://api.openai.com/v1/` | OpenAI 兼容服务的 API 根地址 |
| `modelName` | 必填 | 支持工具调用的模型 ID |
| `apiKey` | 留空读取 `MOZHI_API_KEY` | 服务密钥 |
| `temperature` | 不发送，使用服务端默认值 | 采样温度，0–2 |
| `topP` | 不发送，使用服务端默认值 | 累积概率采样阈值，0–1 |
| `maxTokens` | 不发送，使用服务端默认值 | 输出 token 上限，正整数 |
| `maxCompletionTokens` | 不发送，使用服务端默认值 | 部分模型使用的输出 token 上限，正整数；与 `maxTokens` 二选一 |
| `presencePenalty` | 不发送，使用服务端默认值 | 对已出现内容的惩罚，-2–2 |
| `frequencyPenalty` | 不发送，使用服务端默认值 | 按出现频率施加的惩罚，-2–2 |
| `seed` | 不发送 | 32 位整数随机种子，实际效果由服务端决定 |
| `timeoutSeconds` | `60` | 每次 HTTP 请求超时秒数，1–300 |
| `maxRetries` | `0` | 请求重试次数，0–10 |
| `memoryMaxMessages` | `12` | 记忆消息上限，3–10000；包括系统、用户、模型及工具消息，不是对话轮数 |
| `maxSequentialToolsInvocations` | `4` | 连续工具调用轮数上限，1–100；一轮可能包含多个工具 |
| `systemPrompt` | 示例中的墨汁提示词 | 可直接修改系统指令；显式填写时不能为空，值内可用 `\n` 换行 |

配置加载时校验数值范围、非数值、无穷值及冲突的 token 上限；错误会显示在控制台。
不同服务、模型支持的参数和范围可能不同，不支持的可选参数请留空。
新增或修改参数后，输入 **`MozhiAgent reset`**，在下一次测试时生效，无需重新编译。
首次安装本次代码更新仍需重新启动游戏以加载新的 JAR。

## “反射安全区”的实现

```text
游戏脚本加载器（有反射过滤）
  └─ mozhi-bootstrap.jar
      ├─ 游戏插件、聊天窗口、快捷键、控制台指令、线程调度
      ├─ bridge.AgentBridge（唯一的共享接口）
      └─ AgentClassLoader（自行定义运行区的类）
          └─ agent-runtime.jar
              ├─ LangChainAgent / Assistant / DemoTools
              ├─ LangChain4j / Jackson / SLF4J
              └─ HTTP 客户端及其 ServiceLoader 配置
```

- **关键不是只改线程上下文加载器。** `AgentClassLoader.loadClass()` 对运行区代码和所有第三方依赖直接 `findClass()`，由它自己定义类。缺失依赖直接报错，避免意外绑定其他 Mod 的同名类。
- JDK 类型直接交给平台加载器（也覆盖 `org.w3c.dom` 等非 `java.*` 包）；平台找不到的私有代码由运行区自己定义，因此解析 `Field`、`Method`、`Proxy` 时不会经过游戏的反射过滤器。
- 桥接接口和游戏类型继续委派给游戏加载器，保留相同的类身份。
- 线程上下文加载器在整个智能体初始化和调用期间切换到运行区，结束后恢复；服务资源仅从运行区 JAR 发现，Shade 合并 `META-INF/services`。
- **`agent-runtime.jar` 不可加入 `mod_info.json` 的 `jars` 数组。** 否则框架可能先被游戏加载，隔离失效。
- bootstrap 不能直接 `new LangChainAgent()` 或导入 LangChain4j。通过类名加载入口，再转换成共享的 `AgentBridge`。入口有公开无参构造器；这里刻意使用 `Class.newInstance()`，避免在受限一侧解析 `Constructor`。

这里的“安全区”指可反射的独立类加载环境，不是隔离恶意代码的安全沙箱。
它解除游戏脚本类加载过滤对运行区的影响；**不解除 Java 17 模块强封装**，也不会让 bootstrap 中的直接反射自动可用。
普通业务对象和这里的 LangChain4j 工具反射不需要修改游戏 JAR 或 `vmparams`。

## 扩展

- 新工具放在 `agent-runtime/src/main/java/com/mozhi/assistant/runtime/`，用 `@Tool` 标记并注册到 `AiServices`。
- 第三方依赖加入 `agent-runtime/pom.xml`，Maven 自动打入私有 JAR，无需维护依赖包名前缀列表。
- 需要游戏 API 时，在运行区工具内调用 `gameThread.call(() -> { /* Global... */ return result; })`。
  回调仍由运行区的类定义，但在游戏主线程执行；只将文本结果交给后台模型。切换线程与类加载隔离是两回事。
- 不把运行区的代理、记忆、工具对象放进游戏存档；跨加载器的公开签名不使用框架类型或 `java.lang.reflect.*`。

实现参考：[LangChain4j AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)、
[工具调用](https://docs.langchain4j.dev/tutorials/tools/)、
[Java ClassLoader](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ClassLoader.html)。

按要求，本次不运行自动测试、游戏运行测试或真实模型请求；手动验证入口已提供。
