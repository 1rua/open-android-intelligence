# 根因分析与修复：新建对话不切换、切换会话时 Agent 端 session 不真切换

日期：2026-09-21
范围：Gateway Protocol v2 · ADR 0043 · Hermes 插件宿主 · Android `conversation-ui`
现象（用户实测，宿主 = 真实 Hermes Agent 内的 `integrations/hermes` 插件）：

1. 点「新建对话」后**界面完全没反应**，只在来源会话里多出一条 `/new`；
2. 手动切换会话时，Agent 端对应的会话（Hermes 的会话/记忆上下文）没有真实切换。

结论先行：**这不是 Android 前端写错了**。前端按契约实现（只发 `/new`、只在 `created-conversation` 时切换、绝不本地造会话）；断点在宿主侧——事件只落库不推送，命令又被二次转交给 Agent。

---

## 一、三个维度的根因

### 维度 A（前后端交互协议）：已提交的事件从不投递给在线订阅者

Hermes 侧唯一向在线 SSE/WebSocket 订阅者投递帧的路径是 `adapters._publish_event()`，而它只被**助手消息**的 `complete_message` / `stream_delta` 调用（修复前 `integrations/hermes/open_android_intelligence_gateway/adapter.py:825-855`）。`/new` 的命令结果却是由协议层自己追加的：

```3027:3121:integrations/hermes/open_android_intelligence_gateway/core.py
            account.events.append(
                "conversation.command.result",
                correlation_id,
                {
                    "command": "new",
                    "commandId": "new",
                    "outcome": "created-conversation",
                    "sourceConversationId": source_conversation_id,
                    "sourceMessageId": accepted["messageId"],
                    "conversationId": created["conversationId"],
                },
                current,
            )
```

`EventStore.append` 只做 `INSERT`，没有任何订阅者通知；SSE/WS 写出循环只消费内存队列。于是**健康连接上手机永远收不到命令结果**：它不会触发任何回拉，只能等 60 秒看门狗（`WorkbenchController.NewConversationTimeouts`）——用户看到的就是“只多了一条 `/new`、界面没反应”。

既有用例全部绕开了这条缝：`test_conversation_new_command.py` 直接读 events 表，`test_event_stream_transport.py` / `test_websocket_stream_transport.py` 只用 `adapter.complete_message` 或直接调 `_broadcast_sse`。所以这个洞在 CI 里是绿的。

### 维度 B（Agent 端 session 同步）：`/new` 被二次转交，来源会话被重置、新会话没有会话

`_dispatch_gateway_request` 对**任何** `POST …/conversations/{id}/messages`（200/201）都会 `_notify_agent_inbound`，而 `/new` 走的正是这条路径。转交出去的事件带着来源会话的 `chat_id`，Hermes 自己把 `/new`、`/reset` 当作 reset 触发器（`gateway/config.py` 的 `reset_triggers`，`gateway/slash_commands.py` 的 `reset_session(session_key)`，session key 由 `event.source.chat_id` 经 `gateway/session.py:build_session_key` 推导）。后果有两层，且方向相反：

- **来源会话的 agent 会话被重置** —— 用户仍在阅读的上下文被清掉，直接违反契约 §7.1「旧会话及其未完成的 generation 继续存在、可恢复」；
- **新会话根本没有 agent 会话** —— 它要等到第一条消息才被隐式创建，ADR 0043 要求的「Agent 宿主生成 `agentSessionId`、Adapter 保存绑定」在协议层只落了审计行。

从用户视角看，这就是「Agent 端 session 没有真切换」。

### 维度 C（前端会话管理逻辑）：逻辑正确，但承载它的东西不存在；切换清理不完整

前端切换逻辑本身符合 §7.1（`createThread()`、`applyAgentThreadCreation()`、`switchToAgentCreatedThread()`）。真正的缺口是：

- 它把「切换」完全押在维度 A 那个从未被实时投递的事件上，因此**只能表现为静默**；
- `openThread()` 只清了 `mirrored`/`mirroredRevisions`/`pendingBatch`，**没有清 `generation`、`notice`、输入框失败态与附件渲染缓存**——上一会话的运行中状态与失败提示会跟着切过去（时间线读取失败时尤其明显，因为失败分支不重算 `generation`）；
- 等待期只有 `creatingThread` 布尔量 + 60 秒静默，没有可见进行中状态与退出入口；
- UI 规格 §14.2 的「来源线程显示紧凑『已创建新对话』跳转项」未实现，且当用户已离开来源线程时新会话在该路径下不可达。

附带发现：`noticeText()` 只取 `:` **之后**的片段做映射，因此 `CONVERSATION_CREATE_TIMEOUT:NO_COMMAND_RESULT` 这类「前缀才是可操作码」的提示全部落到兜底文案——这也是「点了没反应」的一部分观感来源。

---

## 二、修复

### 1. 提交后事件接缝（宿主存储层）

- `AccountStore.transaction()` 在 **COMMIT 成功之后**刷新本次事务累积的事件通知；回滚与「提交结果未知」两条路径都丢弃，绝不投递未落库的事实（`core.py:1154-1185`）。
- `EventStore.append()` 只把事件登记到待通知集合（O(1)，无额外 I/O）；非事务内追加立即投递。
- `create_gateway_core` / `GatewayCore` 支持注册与注销事件接收器（`core.py:2644-2676`），`adapter.connect()` 注册、`disconnect()` 与启动失败路径注销（`adapter.py:723 / 757 / 766`）。
- 适配器按账号把帧投递给在线订阅者；跨线程提交时经 `loop.call_soon_threadsafe` 转交，队列满时跳过并由游标重放补偿（`adapter.py:1414-1480`）。**旧的显式广播被删除**，一个事件只有一条通往在线订阅者的路径。

契约 §9 增补了对应义务，并明确「只以游标文档提供事件读取、不提供长连接的宿主不承担该义务」。

### 2. `/new` 由命令入口独占，新会话获得自己的 agent 会话

- 适配器识别保留命令：命中 `/new` 时**不再**把原文交给 Agent（`adapter.py:1129-1175`），来源会话的上下文因此不再被重置。
- 命令结果事件携带 `outcome=created-conversation` 时，宿主为新会话**强制新建**一个不继承旧上下文的 agent 会话（`adapter.py:1443-1461` → `_ensure_agent_session(..., force_new=True)`），并把 `agentSessionId`/`sessionKey` 写回绑定行；`agentSessionId` 仍不下发客户端。
- 没有注入 session store 的宿主（独立运行/测试）**不编造标识**：绑定行保留 `agent_session_id = NULL`，如实记录。

### 3. 会话 ↔ agent 会话的持久绑定与「切换即路由」

- 账户库新增 `conversation_agent_sessions`（`core.py:967`），提供 `AgentSessionBindings.record/attach/lookup/list`（`core.py:1328-1406`）。
- `/new` 在**同一事务**内写入绑定（`core.py:3123`），履行 ADR 0043 的「保存绑定」。
- 入站消息派发前先 `ensure`（`created_via=inbound-message`）；**打开会话或读取其时间线**成功后也 `ensure`（`created_via=conversation-read`，`adapter.py:1005-1020`）。手动切换因此真的切过去：读哪个会话，后续消息就路由到哪个会话的 agent 会话，而重复打开只会复用既有绑定，不会被强制新建。

被否方案：**不新增「会话激活」端点**。契约已把 `conversationId` 定为路由键，读会话/读时间线本身就是「用户切到这里」的既有信号；新增端点需要双宿主对等实现、能力位、schema 与向量联动，成本远大于收益，且既往评审已把同类新增判为范围蔓延。该结论已写入契约 §4/§7.1。

### 4. Android：切换清理、可见等待与跳转项

- `openThread()` 现在同时清 `generation`、`notice`、输入框状态与附件渲染缓存（`WorkbenchController.kt:460-486`）；草稿文本与待发附件按既有设计保持全局（它们属于输入框，丢弃已有上传是数据损失）。
- 新增 `cancelThreadCreation()`（`:598`）：取消只停止等待，不伪造会话、不移动页面；迟到答案按既有「已放弃请求」机制只刷新列表。
- 新增 `/new` 收据：来源线程渲染一行紧凑的「已创建新对话」跳转项（本地导航，不进 Gateway 正文，`:611-635`、`renderTimeline()` 末尾追加、`WorkbenchScreen.kt:284/494`）。用户已离开来源线程时也记录收据，因此新会话仍可达。
- 等待期在时间线里显示「正在由 Agent 创建新对话…」与「取消」（`WorkbenchScreen.kt:296/538`）。
- `noticeText()` 改为前缀/后缀都参与映射，`CONVERSATION_CREATE_*` 类提示不再落到兜底文案；新增取消文案（`StateViews.kt:50-121`）。

### 5. 共享向量与契约

- 新增共享 fixture `event.conversation-command-result.v1` 与 `conversation.command.result` 绑定：`created-conversation` 必须携带 `conversationId`，其它 outcome 不得携带（用 `oneOf` 双分支表达，因为共享 dispatched-schema 只允许封闭子集，禁用 `if/then/else/not`）。
- `sse-events.json` 增两条用例（合法/缺会话标识），`protocol-negotiation.json` 增两条用例（声明 `agent-command-new-v1` 合法 / 未声明的能力位被拒）。
- 契约 §4 明确声明 `agent-command-new-v1` 所承担的全部义务；§7.1 增加「命令入口独占」「新会话必须拥有自己的 agent 会话」「切换即路由」「命令结果 payload 形状」四条规则。

---

## 三、OpenClaw 的处置（诚实记录）

- 仍未声明 `agent-command-new-v1`：`/new` 在其上仅作为普通文本被接受与透传，不产生会话与命令结果事件——这是既有且正确的降级，已由 `integrations/openclaw/test/agent-command-new.test.ts` 锁定。
- 它**没有**长连接实时通道（`src/` 内不存在 `text/event-stream`），只按游标以 JSON 提供事件读取；因此本次新增的「提交后即时投递」义务在契约里被限定为「提供 SSE 或 WebSocket 的宿主」，OpenClaw 的现状被如实记录而非冒充。补齐长连接是一次独立的宿主能力变更，不在本次修复范围。

---

## 四、验证记录

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| Hermes 全量 | `$HOME/.venvs/oai-gateway/bin/python -m pytest tests/ -q` | **172 passed**（基线 157 + 新增 15） |
| 契约一致性（双宿主 + 跨宿主） | `./tools/run-node24 npm run gateway:v2:conformance` | 全通过（含新增 4 条用例） |
| 契约/OpenClaw vitest | `./tools/run-node24 npx vitest run gateway-contract integrations/openclaw` | **217 passed** |
| 根 vitest | `./tools/run-node24 npm test` | 仅 `plugin-tooling` 的既有 `FIXTURE_ALP_SHA256` 红灯（与本改动无关，基线即有） |

红灯验证（新增用例确实能抓住缺陷）：

- 关闭 `EventStore.append → record_event` 后：`test_a_committed_core_event_reaches_the_live_stream`、`test_committed_events_for_another_account_stay_off_this_stream` **均失败**（读帧超时）；
- 关闭 `/new` 独占与创建后绑定后：`test_reserved_new_command_is_never_handed_to_the_agent`、`test_a_created_conversation_gets_a_brand_new_agent_session` **失败**。

Android 侧：本机按仓库规则不执行 Gradle，`conversation-ui` 的最小必要单测（`NewConversationAuthorityTest` 新增 4 例 + `StateViewsTest` / `WorkbenchLayoutRegressionTest`）与编译交由 CI（`ci.yml` 的 `./gradlew check`、`android-apk.yml` 的 `:app:assembleFullDebug`）。

---

## 五、已接受偏差与未完成

1. **`clientConversationId` 字段（ADR 0043 字面项）本次未做。** 现状是「客户端 `clientMessageId` 派生 + 既有 `Idempotency-Key` 重放」保证同一请求不会产生第二个会话，行为上满足 ADR 0043 的意图；改动它需要动 `conversation.schema.json#messageCreate`，从而变更 core schema 摘要并在 Android `SchemaContractHash.CORE`、OpenClaw `plugin-manifest.json#capabilitySchemaHash`、共享向量与 Android/宿主两端联动。它不影响本次三个缺陷的行为，故按计划「收益不足可整项跳过」处理，作为独立契约变更跟进。
2. `agentSessionId` 仍不下发客户端（契约明确要求），前端也无法据此判断绑定是否建立；宿主侧的缺失以「绑定行为空 + 日志」如实呈现，未做客户端可见状态。
3. 既有游标竞态：SSE/WS 订阅时「读 backlog」与「注册队列」之间仍有微秒级窗口，落在窗口里的事件要等下一次重连才可见（既有行为，本次未改；重连后由游标补齐）。
4. 真机验证（Hermes 宿主 + 真机 App 的新建对话/切换会话端到端）未在本机完成，依赖 CI 产物与用户真机复核。
