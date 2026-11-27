# 修复报告：`/new` 后卡在「正在创建」与同一条回复重复渲染

日期：2026-09-21
范围：Android `conversation-ui` · Hermes 插件宿主 `integrations/hermes`
用户报告：
1. 点「新建对话」后，Agent 已回 `✨ Session reset! Starting fresh.` / `✨ New session started!`，界面却一直显示「正在由 Agent 创建新对话…」，不切换会话。
2. 收到回复后切走再切回，同一条回复（含工具执行后的普通文本）被重复渲染 2~3 次。

---

## 一、根因（两侧不同，都不是"前端逻辑写错"）

### 1. 卡死：宿主**没有产出**权威结果，前端只能等

- 前端只认 `conversation.command.result`：`WorkbenchController.applyAgentThreadCreation()` / `isThisCreationAnswer()` 是解除 `pendingCreation` 的唯一路径；事件订阅本身是通的（截图里那条 `Session reset!` 正是经 `TimelineUpsert` 渲染出来的），缺的是**能被消费的权威结果**。
- 你的宿主活库 `~/.hermes/open-android-intelligence-gateway/accounts/3e0275…/gateway.sqlite` 中，每次 `/new` 之后落的都是**普通助手消息**（21:17:12 / 21:19:25 / 21:21:07），**没有任何 `conversation.command.result`**；而该文案只可能来自 Hermes 自己的 `/new` 会话重置处理器 ⇒ **正在运行的 Hermes 进程仍在执行旧代码**（插件文件今天 20:37 已同步，但进程没重启）。
- 没有权威 `conversationId` 时，按契约 §7.1 / ADR 0043 前端**不得**本地构造会话，因此"硬跳"不是选项，正确行为是**及时且诚实地结束等待**。原实现只能等 60 秒看门狗，这就是"一直转圈"。

**关于「能否依靠 /new 的回复文案与 agent 端 session 状态判定」**（用户提问）：
- agent 端 session 状态**拿不到**：契约 §7.1 明确 `agentSessionId` 是宿主内部概念、不下发客户端；
- 回复**文案**不能作判定依据：宿主自由文本、可本地化，钉文案等于把 UI 逻辑建立在私有约定上；
- 会话列表多一条也不行：无法归因到本次请求（可能来自其它设备）；
- 唯一可触发**切换**的信号只有 `conversation.command.result(outcome=created-conversation + conversationId)`；「来源会话里出现本轮之后的助手消息」只能用来判断**该轮已结束**（不看内容），这正是本次采用的辅助信号。

### 2. 重复：宿主对同一回复以**不同 messageId**重复落库

- 代码：`adapter.py` 的 `send()` 每次都新铸 `message_id = f"msg_{uuid.uuid4().hex[:12]}"`；而 `core.py:2130-2146 record_assistant_message` 是 `INSERT … ON CONFLICT(message_id) DO UPDATE` ⇒ **id 每次不同，重复行无法收敛**。
- 活库实证（文本完全相同、id 不同、相隔约 110~120ms 的相邻行）：
  - `msg_796b139fde41` 11:33:44.052Z 与 `msg_85a4afca5059` 11:33:44.178Z
  - `msg_ee0e1464f430` 13:20:53.163Z 与 `msg_784dce7736c9` 13:20:53.279Z
  - 另有 3 份完全相同的 `📬 No home channel is set for Open_Android. …`
- 前端为什么没兜住：既有折叠依赖**时间窗口**（`Math.abs(t1-t2) <= 60_000L` / `120_000L`）——工具调用让同一轮内多条消息的时间差超出窗口，第 2/3 份就活下来了。**按 `messageId` 去重对当前数据无效：id 确实不同**，唯一可行的维度是「同轮 + 同内容」。

---

## 二、修复

### 客户端（`conversation-ui`）

1. **宽限后结束等待**（`WorkbenchController.kt`）
   - `NewConversationTimeouts` 新增 `commandResultGraceMillis = COMMAND_RESULT_GRACE_MILLIS`（5s，含取值依据注释）；
   - `PendingCreation` 记录 `replyBaselineTimestamp`：**发命令前**来源会话里最新的消息时间戳（在 `/new` 回显进入镜像之前读取，避免自我抬高基线）；
   - 触发条件（事件路径 `TimelineUpsert`）：来源会话 + `sender=assistant` + `state=CONFIRMED` + `timestamp > replyBaselineTimestamp` ⇒ `armCreationGrace()`。用的是宿主给的时间戳之间的比较，不信任手机时钟；
   - 流式宿主也让同一判定生效：delta（STREAMING）只入镜像，随后的完成帧带同一时间戳，仍会越过基线 ⇒ 布防（复审阻塞项，已补用例）；
   - 触发条件（读取路径）：`openThread()` 成功合并与 `reloadTimeline()` 成功后调用 `maybeArmCreationGraceFromTimeline()`，覆盖"事件流断开、靠回拉拿到回复"的场景；基线为 0（会话当时是空的）时不布防，交给 60s 看门狗，避免把历史误判成本轮；
   - 宽限到期仍无结果 ⇒ `abandonAgentThreadCreation("CONVERSATION_CREATE_NO_RESULT:AGENT_REPLIED_WITHOUT_RESULT")`，停在来源会话、给出可操作提示、可重试；迟到结果仍走既有 `settledCreations`（只刷新列表、不切页）；
   - `StateViews.kt` 新增 `CONVERSATION_CREATE_NO_RESULT` 的友好文案（"Agent 已经回话了，但没有返回新建会话结果…请重启或升级后重试"）。
2. **以「轮」为边界折叠同内容助手消息**（同文件）
   - `pruneConfirmedAssistantDuplicates()`、`deduplicateAssistantGroup()` 与 `isAlreadyCoveredByConfirmedReply()`（从事件分支抽出的共用判定）**三处**时间窗口（60s / 120s）全部去掉，改为「中间无用户消息（同轮）+ 文本/parts 相同 ⇒ 一条」；
   - 同组内没有用户消息是结构性事实，因此渲染顺序无关，切出重进（重新拉取时间线）同样收敛。

### 宿主（`integrations/hermes`）

3. **一条回复一个 id**（`adapter.py`）
   - `_published_reply_id()`：同一会话、同一轮内**相邻**重复发布**相同文本**时复用同一 id（落在同一行；文本变了就是新消息）；记忆为进程内单槽/会话，`_notify_agent_inbound()` 收到新的用户消息时清除 ⇒ 用户新一轮里的同文本是新消息；容量 `MAX_REMEMBERED_TURNS = 256`，按最近写入淘汰；
   - `send()` 只在宿主**明确给出本条消息的 id**（`metadata["messageId"|"message_id"]`）时采用它；**`reply_to` 是"被回复的那条消息"，绝不能用**——否则 `ON CONFLICT(message_id)` 会覆盖用户行（复审阻塞项）；
   - `edit_message(finalize=True)` 同样过这道判定，覆盖"先 finalize 再 send"的混合路径；
   - 纵深防御：`record_assistant_message` 的 upsert 加 `WHERE messages.sender = 'assistant'`，从 SQL 层保证任何情况下都不会改写用户消息行。

被否方案：等 60s 超时（用户看到的就是"卡死"）；只按 messageId 去重（id 确实不同）；按回复文案匹配（自由文本）；在前端"猜"一个新会话（违反 §7.1/ADR 0043）。

---

## 三、测试

宿主 `integrations/hermes/tests/test_assistant_message_identity.py`（新增 6 例）：
同一回复发布两次只落一行且事件复用同一 id / 先 finalize 再 send 同文本仍一行 / `send()` 优先采用宿主显式给出的 id / **`reply_to` 指向的用户行不被覆盖** / 用户新消息后同文本算新消息 / 同轮内不同文本是两条消息。

客户端 `conversation-ui`（新增 9 例）：
- `NewConversationAuthorityTest`：`anAgentReplyWithoutACommandResultEndsTheWaitHonestly`、`aCommandResultArrivingInsideTheGraceStillSwitches`、`aReplayedAgentReplyIsNotEvidenceThatTheTurnEnded`、`aStreamedReplyEndsTheWaitWhenItsCompletionArrives`、`aReplyReadBackThroughTheTimelineAlsoEndsTheWait`（+ 既有 19 例保持绿）；
- `TimelineTurnDedupTest`（新文件）：同轮同内容（不同 id，含相距 20 万 ms 的工具轮场景）只渲染一条、不同轮同内容保留两条、工具卡片不与回复混淆。

红灯验证：注释掉 `_published_reply_id` 的记忆后，`test_a_reply_published_twice_lands_under_one_message_id` 与 `test_a_finalize_then_a_send_of_the_same_reply_lands_under_one_id` 均失败；恢复后全绿。

---

## 四、验证记录与运维前置

| 验证 | 结果 |
| --- | --- |
| `$HOME/.venvs/oai-gateway/bin/python -m pytest tests/ -q`（Hermes） | **179 passed**（基线 173 + 新增 6） |
| 根 vitest / 契约一致性 | 本次未改 TS 与契约资产，由 CI 复跑 |
| Android 编译与单测 | 本机不跑 Gradle（无 android sdk、用户会取消）⇒ **只能靠 CI**（`ci.yml` 的 `Android Build & Check` 跑 `./gradlew check`） |

CI 首轮红了 1 例（`aStreamedReplyEndsTheWaitWhenItsCompletionArrives`）：测试辅助函数给同一回复的 STREAMING 与 CONFIRMED 两帧用了**同一个 eventId**，第二帧被 `markEventHandled` 当重复帧丢弃（生产环境每帧 id 唯一）——属测试缺陷，已修并把「分片必须真的被应用」写成断言，避免同类假绿。

**必须先做的一步**：部署宿主补丁后**重启 Hermes 网关进程**——当前进程里仍是旧代码，不重启则客户端再稳也只能"提前结束等待"，不会真正跳转。重启后真机复测：点新建不应再出现 `Session reset!` 文案，界面应切换到新会话；发一条含工具调用的回复后切出重进，应只剩 1 条。

---

## 五、独立复审（两轴）与返修

推送后启动了两个互不共享上下文的审查子 Agent（标准轴 + 规格轴）。**判为阻塞并已修复**：

| 复审发现 | 处理 |
| --- | --- |
| 宽限触发条件用「本机从未见过该 messageId」，而流式宿主先发 `message.delta`（STREAMING）已把该 id 登记进镜像 ⇒ 完成帧到达时 `unseen=false`，**宽限永不布防，现象 1 在流式路径下未修** | **已修**：改为「回复时间戳 > 发命令前的基线」，delta/完成帧都带同一时间戳故都能命中；新增 `aStreamedReplyEndsTheWaitWhenItsCompletionArrives` |
| 布防只挂在事件 upsert，时间线回拉拿到同一证据时不布防 | **已修**：`openThread()`/`reloadTimeline()` 成功后调用 `maybeArmCreationGraceFromTimeline()`（基线为 0 时不布防）；新增 `aReplyReadBackThroughTheTimelineAlsoEndsTheWait` |
| `send()` 把 `reply_to` 当作本条消息的 id：`reply_to` 语义是"被回复的消息"，`ON CONFLICT(message_id)` 会**覆盖用户消息行** | **已修**：只接受宿主显式给出的 `metadata["messageId"]`；`record_assistant_message` 加 `WHERE messages.sender = 'assistant'` 纵深防御；新增 `test_the_message_being_replied_to_is_not_overwritten` |
| 报告称时间窗"已去掉"，但事件分支里仍有 `120_000L`（旧实现残留，等于两套折叠逻辑并存） | **已修**：抽出共用判定 `isAlreadyCoveredByConfirmedReply()` 并去掉该窗口；报告同步更正 |
| 报告措辞失准（"按 LRU 淘汰"、"只落一行"） | **已修**：改为"按最近写入淘汰"与"相邻重复发布"（宿主记忆是单槽：A→B→A 的第三份仍是新消息，重启即失效） |
| 端到端用例存在竞态（命令入口先落绑定行，宿主的 attach 在后台任务里；用例在 attach 前就读到快照） | **已修**：`_await_agent_session_binding()` 等"带 agentSessionId 的行"，而不是只等行存在 |

**判断项（保留并说明）**：宿主记忆为进程内单槽（跨重启失效、非相邻重复不折叠）——与"一轮一条回复"的语义一致；`hasUserMessageBetween` 在两端时间戳完全相等时返回 false（不同轮、同毫秒、同文本的极端组合会被折叠），现有用例覆盖不同时间戳，该边界记录在案；`_remember_turn_reply` 的淘汰与 `send()` 的 check-then-act 在并发同文本两发时理论上可各铸一个 id（生产路径为单轮顺序发布，风险极低，未加锁）。

## 六、遗留

1. 宿主侧历史重复行不会被自动清理（修复只阻止新增）；如需清理旧数据，另行排期。
2. OpenClaw 无长连接、且不声明 `agent-command-new-v1`，其事件只能按游标以 JSON 读取；本次改动不涉及它。
3. 真实 `BasePlatformAdapter` 路径仍无自动化覆盖（测试环境无 Hermes `gateway` 包，适配器测试走 import 兜底桩），真机回归仍需人工。
4. 契约层「是否下发 agentSessionId」的评估见前一份分析（建议改为下发**状态枚举**而非内部 id）；本次未改契约。
