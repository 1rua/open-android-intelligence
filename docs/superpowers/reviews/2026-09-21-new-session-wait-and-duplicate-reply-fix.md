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
   - `observeThreadEvents()` 的 `TimelineUpsert` 分支：**来源会话 + 本机从未见过该 messageId + CONFIRMED 助手消息** ⇒ `armCreationGrace()`；
   - 宽限到期仍无结果 ⇒ `abandonAgentThreadCreation("CONVERSATION_CREATE_NO_RESULT:AGENT_REPLIED_WITHOUT_RESULT")`，停在来源会话、给出可操作提示、可重试；迟到结果仍走既有 `settledCreations`（只刷新列表、不切页）。
   - 「从未见过」这一条是关键：重连重放会被排除，否则会把旧回复误当成"本轮已结束"。
   - `StateViews.kt` 新增 `CONVERSATION_CREATE_NO_RESULT` 的友好文案（"Agent 已经回话了，但没有返回新建会话结果…请重启或升级后重试"）。
2. **以「轮」为边界折叠同内容助手消息**（同文件）
   - `pruneConfirmedAssistantDuplicates()` 与 `deduplicateAssistantGroup()` 去掉 60s/120s 时间窗口，改为「中间无用户消息（同轮）+ 文本/parts 相同 ⇒ 一条」；
   - 同组内没有用户消息是结构性事实，因此渲染顺序无关，切出重进（重新拉取时间线）同样收敛。

### 宿主（`integrations/hermes`）

3. **一条回复一个 id**（`adapter.py`）
   - `send()` 优先采用调用方给的标识（`reply_to`，或 `metadata["messageId"|"message_id"]`），否则取"本轮该会话已发布内容"的记忆；
   - 新增 `_published_reply_id/_remember_turn_reply/_forget_turn_reply`：同一会话、同一轮内再次发布**相同文本**时复用同一 id（落在同一行），首次出现时才是新消息；
   - `edit_message(finalize=True)` 同样过这道判定，覆盖"先 finalize 再 send"的混合路径；
   - `_notify_agent_inbound()` 在收到新的用户消息时清除该会话记忆 ⇒ **用户新一轮里的同文本是新的消息**；
   - 记忆容量 `MAX_REMEMBERED_TURNS = 256`，按会话 LRU 淘汰。

被否方案：等 60s 超时（用户看到的就是"卡死"）；只按 messageId 去重（id 确实不同）；按回复文案匹配（自由文本）；在前端"猜"一个新会话（违反 §7.1/ADR 0043）。

---

## 三、测试

宿主 `integrations/hermes/tests/test_assistant_message_identity.py`（新增 5 例）：
同一回复发布两次只落一行且事件复用同一 id / 先 finalize 再 send 同文本仍一行 / `send()` 优先采用宿主给的 id / 用户新消息后同文本算新消息 / 同轮内不同文本是两条消息。

客户端 `conversation-ui`（新增 7 例）：
- `NewConversationAuthorityTest`：`anAgentReplyWithoutACommandResultEndsTheWaitHonestly`、`aCommandResultArrivingInsideTheGraceStillSwitches`、`aReplayedAgentReplyIsNotEvidenceThatTheTurnEnded`（+ 既有 19 例保持绿）；
- `TimelineTurnDedupTest`（新文件）：同轮同内容（不同 id，含相距 20 万 ms 的工具轮场景）只渲染一条、不同轮同内容保留两条、工具卡片不与回复混淆。

红灯验证：注释掉 `_published_reply_id` 的记忆后，`test_a_reply_published_twice_lands_under_one_message_id` 与 `test_a_finalize_then_a_send_of_the_same_reply_lands_under_one_id` 均失败；恢复后全绿。

---

## 四、验证记录与运维前置

| 验证 | 结果 |
| --- | --- |
| `$HOME/.venvs/oai-gateway/bin/python -m pytest tests/ -q`（Hermes） | **178 passed**（基线 173 + 新增 5） |
| 根 vitest / 契约一致性 | 本次未改 TS 与契约资产，由 CI 复跑 |
| Android 编译与单测 | 本机不跑 Gradle（无 android sdk、用户会取消）⇒ **只能靠 CI**（`ci.yml` 的 `Android Build & Check` 跑 `./gradlew check`） |

**必须先做的一步**：部署宿主补丁后**重启 Hermes 网关进程**——当前进程里仍是旧代码，不重启则客户端再稳也只能"提前结束等待"，不会真正跳转。重启后真机复测：点新建不应再出现 `Session reset!` 文案，界面应切换到新会话；发一条含工具调用的回复后切出重进，应只剩 1 条。

---

## 五、遗留

1. 宿主侧历史重复行不会被自动清理（修复只阻止新增）；如需清理旧数据，另行排期。
2. OpenClaw 无长连接、且不声明 `agent-command-new-v1`，其事件只能按游标以 JSON 读取；本次改动不涉及它。
3. 真实 `BasePlatformAdapter` 路径仍无自动化覆盖（测试环境无 Hermes `gateway` 包，适配器测试走 import 兜底桩），真机回归仍需人工。
4. 契约层「是否下发 agentSessionId」的评估见前一份分析（建议改为下发**状态枚举**而非内部 id）；本次未改契约。
