# 独立审查报告：新建对话改由 Agent 端创建（提交 `19e8ef3` + 返修）

审查对象：`git diff 23cf33a...HEAD`（单条提交「修复: 新建对话改由 Agent 端创建并回传权威会话标识」）。
审查轴：**标准符合性** 与 **规格符合性**，两条轴独立给出结论，不互相覆盖。

---

## 1. 标准轴结论

**硬违规**

1. `AGENTS.md`「重构必须原子化清除废弃旧代码，严禁新旧两套架构长期并存」：`createThread()` 已改走 `/new`，但 `bootstrapConversation()` 仍调用 `repository.createConversation` 本地造会话，两套创建路径并存，与新增契约 §7.1「不得本地构造会话」字面冲突。
2. `AGENTS.md`「严禁伪造实现与死状态」：解码器解出的 `sourceConversationId` / `sourceMessageId` 全仓无人消费，属带进来的死字段。

**判断项**

- `NewConversationTimeouts(timeoutMillis = 60_000L)` 为裸字面量（`AGENTS.md` 禁魔数）。
- `GatewayConversationRepository.readConversation` 在 `conversation-data` 无单元测试。
- Hermes 声明能力、OpenClaw 不声明是有意为之，但需长期同步。
- 符合项：UI 层只用 `conversation.ports.*`，未引用 gateway 网络类型；无 `Color(0x)` 与越界圆角，门禁不受影响；契约、schema、摘要与三方测试同步入库。

**基线气味**：`openThread + syncThreadMetadata + "已创建新对话"` 重复三处（伴生霰弹式修改）；`commandOutcomeOf` 与 `applyAgentThreadCreation` 两处平行 `when`；`event.conversationId?.value` 一类消息链。

---

## 2. 规格轴结论

**(a) 规格要求但缺失/只做了一部分**

1. ADR 0043 / UI 规格 §14.2「Android 生成 `clientConversationId` + requestId」未字面实现：`clientConversationId` 由 Hermes 侧拼成 `{clientMessageId}-new`；「宿主生成 `agentSessionId`」被注释显式跳过。
2. UI 规格 §14.2「来源线程显示紧凑『已创建新对话』跳转项」未做，目前只有瞬时提示 + 来源线程里的一条 `/new` 文本。
3. 契约 §7.1「保存『命令请求↔新会话』绑定」只落在审计行，没有独立绑定表。
4. 验证尚有缺口：`gateway-contract/vectors` 未加入 `conversation.command.result` 与能力位向量；OpenClaw 缺少「`/new` 原样透传且不创建会话」的测试（本次返修已补）。

**(b) 超出规格（范围蔓延）**

1. 等待期间整体禁发 `sendDraft`（需求只要求失败回滚/报错）。
2. 用户手输 `/new` 也强制跳页（需求只覆盖顶部「新建对话」）。
3. 契约 §4 把 `conversationUi` 写成全量闭集枚举，超出「新增一个能力位」。

**(c) 看似实现、实则有误**

1. `applyAgentThreadCreation` 丢弃已解码的 `sourceConversationId` / `sourceMessageId`：跨设备、跨线程的 `command.result` 也会触发切换；手输分支甚至不校验 `activeThreadId`，会劫持页面。
2. 用户已离开来源线程时只弹提示、不切换也无跳转项，新会话在该路径下不可达。

---

## 3. 返修处理

| 结论 | 处理 | 说明 |
| --- | --- | --- |
| 标准-1 两套创建路径 | **保留例外并收紧** | 重命名为 `bootstrapConversationForFirstMessage()`，KDoc 明确「唯一被允许的端点创建，且仅可达于零会话时的首条发送；新建对话入口永不调用它」。零会话时 `/new` 无处承载，用户已确认保留该例外。 |
| 标准-2 死字段 | **已修** | `PendingCreation` 记录 Gateway 颁发的 `sourceMessageId`；新增 `isThisCreationAnswer()` 用来源会话与来源消息做相关性判定，不匹配的结果不得切换或结束等待；手输 `/new` 分支增加「必须仍在来源会话」守卫。 |
| 标准-魔数 | **已修** | 提为 `NEW_CONVERSATION_TIMEOUT_MILLIS` 并写明取值依据。 |
| 标准-重复代码 | **已修** | 收敛为 `switchToAgentCreatedThread()` 单一开关路径。 |
| 标准-readConversation 无测试 | 记入未完成 | 12 行映射，待与 `conversation-data` 的其它仓储测试一并补。 |
| 规格-(a)1 clientConversationId / agentSessionId | **明确列为已接受偏差** | `message.create` 为 `additionalProperties:false` 的闭集，新增字段会再次触发 core schema 摘要联动；改用「客户端 `clientMessageId` 派生 + 既有 `Idempotency-Key` 重放」保证幂等。`agentSessionId` 属宿主内部概念且当前无 Agent 运行时，编造即是伪实现，代码注释已写明席位留给运行时接入。 |
| 规格-(a)2 来源线程跳转项 | 记入未完成 | 需要在时间线引入系统行与跳转语义，超出本次修复范围。 |
| 规格-(a)3 绑定仅落审计 | 记入未完成 | 当前审计行已完整记录「请求 ↔ 新会话」，独立绑定表待 Agent 运行时接入后随 `agentSessionId` 一并落地。 |
| 规格-(a)4 向量与 OpenClaw 透传测试 | **部分已修** | 已补 `integrations/openclaw/test/agent-command-new.test.ts`（`/new` 作为普通文本被接受、不新建会话、不发命令结果事件）；共享向量仍待补。 |
| 规格-(b)1 等待期禁发 | **保留** | 与需求⑤「后续消息必须落在新会话」一致：等待期落到旧会话正是本次要消除的不一致。 |
| 规格-(b)2 手输也跳页 | **保留但加守卫** | 手输 `/new` 与顶部按钮是同一条命令通道，区别只在等待态；已加「必须仍在来源会话」守卫，不再劫持。 |
| 规格-(b)3 全量闭集枚举 | **保留** | 契约 §4 原已枚举 7 项，补齐第 8 项后写法不变，无新增约束。 |
| 规格-(c)1 相关性 | **已修**（同标准-2） | 并新增用例 `commandResultFromAnotherThreadIsNotThisRequest`。 |
| 规格-(c)2 已离开来源线程 | **保留现状 + 说明** | 不劫持是刻意取舍；新会话已通过 `refreshThreads()` 进入列表，跳转项待 (a)2 落地。 |

---

## 4. 验证记录（返修后重跑）

- Hermes pytest：`157 passed`（基线 149 + 新增 8）。
- OpenClaw vitest：`53 passed`（含新增 `/new` 透传用例）。
- 契约一致性：`gateway:v2:conformance` Hermes 24/24 + TS 全通过。
- 根 vitest：`798 passed`，唯一红灯为既有 `plugin-tooling` 的 `FIXTURE_ALP_SHA256`。
- Android：`conversation-ui` / `conversation-data` / `conversation-domain` / `gateway-client` / `app:testFullDebugUnitTest` 全绿；
  新增 12 条用例在「恢复旧行为」下 **11/11 失败**（红灯验证），恢复修复后全绿。
- 编译与全量测试交 CI（`ci.yml`、`android-apk.yml`）。

---

## 5. 未完成（按优先级）

1. UI 规格 §14.2：来源线程紧凑「已创建新对话」跳转项（P2，需时间线系统行）。
2. `conversation.command.result` 与 `agent-command-new-v1` 进入共享向量套件（P2）。
3. `GatewayConversationRepository.readConversation` 的仓储级单测（P3）。
4. Agent 运行时接入后补 `agentSessionId` 绑定表与 `commandContext.clientConversationId` 契约字段（P3，依赖 Agent 宿主能力）。
