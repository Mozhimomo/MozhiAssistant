# 舰队智能体

目前已实现模型层、异步 Planner、Executor、结果检查器 Monitor、Agent 循环和四种游戏动作，包括最近 20 步执行结果的滑动窗口。`game.FleetRuntime` 已接入游戏主循环、聊天命令、状态面板和 JSON 存档。离线验证通过，原生航行和实际模型响应仍需游戏内验证。

## 模型层

两个模型都是 Java 17 record，只描述计划内容，不保存执行状态，也不依赖游戏 API 或 LLM。

| 模型 | 字段 | 含义 |
| --- | --- | --- |
| `Plan` | `id` | 计划 ID；创建新计划时分配新 ID |
| `Plan` | `goal` | 这份计划要达成的目标 |
| `Plan` | `steps` | 按执行顺序排列的非空步骤列表，步骤 ID 不可重复 |
| `Step` | `id` | 步骤 ID；同一动作跨计划继续执行时保留 |
| `Step` | `action` | 动作名称，由后续执行器匹配已注册的动作 |
| `Step` | `parameters` | 动作参数，只允许 JSON 值；无参数时传空 Map |
| `Step` | `description` | 用于界面展示的步骤说明 |
| `Step` | `expectedOutcome` | 给监控器参考的预期效果描述，不是实际执行结果 |

`Plan.create(...)`、`Step.create(...)` 为新对象生成 UUID；构造器可使用已有 ID 恢复数据。改变动作或参数时应创建新步骤。步骤 ID 仅提供身份，后续协调器仍需核对实际执行记录和动作内容，不能仅凭 ID 决定是否跳过或续接。

列表和嵌套参数在创建时拷贝并设为只读，避免规划线程与游戏主线程共享可变数据。构造器拒绝空白必填字段、重复步骤 ID、非有限数值，以及游戏对象等非 JSON 参数。

```java
Step move = Step.create("MOVE_TO", Map.of("destinationId", "jangala"),
        "前往 Jangala", "抵达目标市场");
Step buy = Step.create("BUY", Map.of("item", "supplies", "quantity", 100),
        "购买补给", "补给增加 100");
Plan plan = Plan.create("采购补给", List.of(move, buy));
```

这里展示模型的构造方式，实际动作需要提供下文动作表中的完整参数。模型只校验通用结构，具体动作的参数、前置条件和完成判定由动作实现校验。当前步骤下标和进度版本由 Agent 保存；全部步骤成功后直接视为任务完成。

## Planner

`planning.Planner` 接收 `PlanningRequest`，通过 `plan(request)` 立即返回 `Future<PlanningResult>`。配置读取、LLM 客户端初始化及规划请求在专用后台线程执行，不访问游戏对象。可以传配置 URL，也可以注入已有的 `LlmClient`。

配置 URL 入口支持 `fleetPlannerMaxOutputTokens`、`fleetPlannerThinkingMode` 和 `fleetPlannerReasoningEffort`，分别覆盖舰队规划的输出预算与思考参数，省略时继承聊天配置。模板将规划输出预算设为 32768，包含思考和计划正文；思考参数默认继承聊天配置。

请求包含任务 ID、快照版本、原始目标、世界状态文本、执行历史快照、触发原因、当前计划和 `ActionSpec` 动作列表。动作契约说明前提与效果，并列出允许的参数、JSON 类型及必填项。规划器校验动作名、参数名和类型；具体数值范围及游戏前提由后续执行器校验。

| 结果 | 含义 |
| --- | --- |
| `REPLACE` | 返回新计划，计划目标沿用原始目标，计划 ID 由应用分配 |
| `KEEP` | 返回原计划对象，保留步骤身份，不重置执行进度 |
| `GOAL_REACHED` | 模型认为目标已达成，Agent 核对任务和进度版本后结束任务 |
| `BLOCKED` | 当前无法制定可执行计划，提供阻碍原因 |

模型只能指定 `reuseStepId` 来建议续接当前计划中未完成的步骤。代码会核对动作和参数一致，并复用原步骤对象；新步骤由代码生成 ID。不存在、已完成、重复复用或修改参数的复用请求会被拒绝。Agent 丢弃执行进度变化前生成的计划，并阻止续接刚失败的步骤。相同语义但不同 ID 的动作仍依赖 Planner 结合历史判断，Agent 不额外做语义审核。

响应通过 `LlmClient.aiService(...)` 转成草案，沿用现有连接、输出预算、思考和结构化输出配置。为兼容严格 JSON Schema，草案的动态参数使用 `parametersJson` 文本传输，解析及校验后才进入 `Step.parameters`。重复 JSON 键、非对象参数、尾随内容和无效计划都会使 Future 异常完成，不回退为成功或保留计划。

同一快照的重复请求共用进行中的 Future。忙碌时提交不同快照会立即报错，调用方应合并周期触发，等待当前结果后使用新快照再次规划，避免请求堆积或每 15 秒取消尚未完成的请求。新用户任务可以显式 `cancel()` 后提交；`close()` 取消请求并关闭后台线程。底层请求如果忽略中断，后续请求仍需等待它完成或超时，但取消的 Future 不会返回迟到结果。`isStopped()` 可供后续类加载器清理逻辑使用。

结果带回原任务 ID 和快照版本。调用方应在游戏主线程轮询 `isDone()`，完成后再读取结果、核对任务与实际进度并决定是否接纳。Planner 不自动替换执行中的计划，也不启动周期计时器。

## 最近 20 步执行历史

`model.ExecutionResult` 包含步骤定义、`RUNNING / WAITING / SUCCEEDED / FAILED` 状态和实际结果说明。Executor 每轮反馈自动写入共享的 `ExecutionHistory`；外部接入时不要重复记录。

- 窗口从旧到新保留最近 20 个步骤的最新结果，包含成功、失败及仍在执行的步骤。
- 同一步骤的多轮反馈更新原记录，不会让航行等长动作挤满窗口。
- 超出窗口时移除最旧的详细结果；已成功步骤的 ID 单独保留，直到该任务结束。
- 每次规划使用不可变的 `snapshot()`，包含动作、参数、状态及实际效果或失败原因；提交后的执行更新不改变已有请求。
- Agent 启动新任务时清空共享历史和终态缓存。存档通过 `Agent.State` 和 `Executor.State` 保存历史窗口、完成身份、终态缓存和当前步骤。

历史记录与规划请求示例（使用 Executor 时，它会代为调用 `history.record`）：

```java
ExecutionHistory history = new ExecutionHistory(); // 一个任务持有一份
history.record(new ExecutionResult(step, ExecutionResult.Status.SUCCEEDED, "已实际买入 100 个补给"));
PlanningRequest request = new PlanningRequest(taskId, revision, goal, worldState,
        history.snapshot(), "步骤完成后重新规划", actions, currentPlan);
Future<PlanningResult> pending = planner.plan(request);
// 后续游戏更新中判断 pending.isDone()，再读取和核对结果。
```

## Executor 与动作

`execution.Executor` 在创建它的游戏主线程运行，不调用 LLM。`execute(plan, stepIndex)` 只推进 Agent 指定的一步，返回 `ExecutionResult`；成功后不会自动进入下一步。`execute(step)` 也可用于执行单个步骤。

所有动作放在 `actions/`：

| 动作 | 实现 | 参数和完成条件 |
| --- | --- | --- |
| `BUY` | `BuyAction` | `marketId`、`submarketId`、`itemType`、`itemId`、`quantity`；先实际入轨，再转移库存和扣款 |
| `SELL` | `SellAction` | 参数同 BUY；实际移出自有库存，转入交易区并收款 |
| `MOVE_TO` | `MoveToAction` | `destinationId` 为实体、市场或星系 ID；航行并确认实际环绕目标后成功 |
| `RETURN` | `ReturnToPlayerAction` | 无参数；驶向玩家，靠近后合并全部舰船、军官、货物和信用点，最后移除分舰队；必须位于计划末尾 |

`itemType` 支持 `COMMODITY`、`WEAPON`、`FIGHTER`、`HULLMOD`、`SPECIAL`、`SHIP`。普通物品使用规格 ID；舰船使用实例 ID，数量必须为 1。特殊物品可通过 `itemData` 指定实例数据，同一物品 ID 对应多种数据时必须明确选择。数量必须为 1 至 1000000 的整数，不能出售分舰队最后一艘舰船。

买卖在执行当次更新并读取指定交易区的实际库存与价格，计入关税；数量或资金不足时整步失败，不部分成交，不代替 MOVE_TO 导航。交易通过 NPC 的资产转移完成，不模拟玩家交易 UI 或模组商店的专用交互脚本。免费仓储与隐藏交易区不可用于买卖。

游戏暂停、受控舰队战斗或跃迁期间返回 WAITING。RETURN 也等待玩家结束战斗和跃迁；已有原生航行由游戏继续更新。MOVE_TO 下达环绕任务时仍返回 RUNNING，实际 OrbitAPI 确认入轨才返回 SUCCEEDED。`stop()` 可停止未完成的移动并保留已有轨道；之后可以续接同一步骤。

执行器缓存成功和失败的终态，同一步骤的重复调用返回原结果，防止重复买卖与合并。失败步骤不会因下一轮调用而自动重试；需要 Agent 决定是否创建新步骤。相同 ID 不能改变步骤定义。终态缓存、历史和当前步骤都随游戏保存，恢复时沿用实际进度。

购买、出售及派遣的资产转移中出现异常时，按逆序恢复并核对变更。恢复无法确认时，`isBlocked()` 为 true，Agent 停止任务；新任务和读档均不能清除这个阻塞。RETURN 到达后直接合并舰船、军官、货物和信用点，移除分舰队即完成，不进行结果数量校验或事务回滚。

原生货舱的商品小数累计量不包含在 `CargoAPI.getQuantity()` 中。资产转移与回滚使用货堆加 `CargoData.getPartial()` 的实际总量；回归也合并只有小数余额而没有可见货堆的商品。该适配针对本项目的 0.98a 游戏库，并有真实 `CargoData` 回归测试。

通过 Executor 调用动作，以确保主线程检查、状态检查、终态缓存和执行历史都生效：

```java
ExecutionHistory history = new ExecutionHistory();
Executor executor = new Executor(ActionContext.forFleet(controlledFleet), history);
// executor.actionSpecs() 提供给 PlanningRequest，确保规划和执行使用同一份动作契约。
ExecutionResult result = executor.execute(plan, currentStepIndex);
// 由 Agent 根据 result 和实际世界状态决定推进、等待、结束或重规划。
```

`ActionContext` 持有实时游戏对象，仅供主线程使用；Planner 仍然只接收不可变的文本及数据快照。

## Monitor

`execution.Monitor` 是无状态的结果检查器，只接收 `ExecutionResult` 并返回结论：

| 执行结果 | 检查结论 |
| --- | --- |
| `RUNNING / WAITING` | `CONTINUE`：当前步骤继续 |
| `SUCCEEDED` | `ADVANCE`：当前步骤完成，可以推进 |
| `FAILED` | `REPLAN`：需要重新规划 |

Monitor 不持有 Planner、Executor、计划进度或计时器，也不调用模型。Agent 根据检查结论执行推进、结束或规划操作。

## Agent 循环

`com.mozhi.fleet.Agent` 在游戏主线程协调 Planner、Executor 与 Monitor，持有任务和计划进度：

- `RUNNING / WAITING`：继续当前步骤；每轮最多执行一步。
- `SUCCEEDED`：推进下标，全部步骤成功后结束任务。
- `FAILED`：暂停执行并携带失败历史重新规划；资产状态不确定时直接阻塞。
- 默认每 15 秒重新规划，可通过构造器调整。目标偏差由 Planner 根据最新世界状态和历史判断。
- 规划期间继续执行有效的旧计划；忙碌期间的触发合并为一次。步骤成功或失败后，旧版本规划结果会被丢弃并重新请求。
- `KEEP` 保留进度；替换计划时可续接相同的当前步骤；`GOAL_REACHED` 直接完成，`BLOCKED` 停止任务。
- 周期规划失败时继续有效的旧计划，下一周期再请求；没有可执行计划时进入阻塞状态。

```java
Agent agent = new Agent(planner, executor, () -> collectWorldState());
agent.start(goal);
// 游戏主循环传入未加速的真实秒数增量；暂停期间不累计时间、不执行或接纳计划。
agent.advance(realElapsedSeconds, sector.isPaused());
Agent.View state = agent.view();
```

`collectWorldState()` 由接入层实现，在主线程采集文本快照。`view()` 提供任务、计划、步骤下标、最近结果、规划状态及原因；状态包括 `IDLE / PLANNING / EXECUTING / COMPLETED / BLOCKED / CANCELLED`。外部事件可调用 `requestReplan(reason)`，下一轮更新时提交；`cancel()` 停止当前任务，`close()` 同时关闭 Planner。`start()` 替换旧任务并清空执行历史，迟到的旧任务响应不会被接纳。

## 验证

先在仓库根目录执行 `mvn -Dmozhi.skipDeployment=true -Dmaven.test.skip=true package`，然后运行 `./fleet-agent/verify.ps1`。脚本使用 Java 17 和本地 `starsector-core` API 编译当前源码，验证模型、Planner、20 步历史窗口、Executor 的动作与资产操作，以及 Agent 经 Monitor 检查后的步骤推进、周期调度、暂停、失败重规划、过期结果丢弃和任务切换。还覆盖派遣回滚、桥接命令、购买后保存恢复、回归合并、规划中读档和阻塞状态恢复。

退出游戏并构建部署后，可运行 `./fleet-agent/verify.ps1 -CheckPackaged`，通过游戏使用的私有类加载器加载 `jars/` 中的入口并执行召回。存在本地配置时仅校验配置解析，不输出密钥、不发网络请求。以上检查使用本地假模型及可变游戏 API 代理，不启动游戏。

## 游戏接入与测试

`FleetAgentHost` 加载 `game.FleetRuntime`，由主线程每帧调用 Agent。真实时间通过单调时钟计算，不受游戏加速倍率影响；暂停期间不累计规划时间。`GameWorld` 采集舰队资产、实际目的地 ID、市场和相关库存，供 Planner 规划。其他市场只提供目录信息，库存未知时不编造。

派遣后舰队原地待命。目前只支持购买、出售、移动和回归，旧版跟随任务不再提供。明确地点的移动和召回可直接创建 Plan，自然语言复合任务由 Planner 生成。

游戏存档只写 JSON 字符串（键 `mozhi_assistant_fleet_agent_v2`），不保存私有类加载器对象或 Future。读档后恢复执行下标，未结束的规划重新取快照提交。旧版存档只接管原舰队资产，需重新下达任务，旧计划不自动执行。

建议依次测试：指定非旗舰及资金物资派遣 → 前往市场入轨 → 少量购买/出售 → 保存读档确认进度 → 召回确认资产合并。解除暂停才会执行；状态面板可查看每步结果。复杂模组市场和真实模型响应仍以游戏内结果为准。

## 架构进度

- **Planner（已实现）**：根据原始目标、世界状态和最近 20 步执行结果，异步生成候选计划。
- **Executor（已实现）**：不调用 LLM，执行指定步骤；四种动作独立放在 actions 目录中，结果写入历史窗口。
- **Monitor（已实现）**：只检查执行结果，返回继续、推进或重规划结论。
- **Agent 循环（已实现）**：管理任务与计划进度，按 Monitor 结论推进；按可配置间隔（默认 15 秒）触发规划、合并忙碌期间的触发、接纳有效候选计划。

- **游戏桥接（已实现）**：派遣、世界状态、主循环、聊天工具、状态面板、JSON 存档与类加载生命周期。
