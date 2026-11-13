# 消息重复接收/重复显示修复审查报告（提交 `0bddeb9`）

- 日期：2026-09-20
- 提交：`0bddeb9`（父提交 `6aaadc6`，作者 1rua），`修复: 消除消息重复接收与重复显示`，6 文件 `+295 / -24`
- 审查范围：
  - `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt`
  - `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ports/ConversationPorts.kt`
  - `apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt`
  - `apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchSendRegressionTest.kt`
  - `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/http/GatewayEventStreamTest.kt`
- 判定依据：`AGENTS.md`（§2 通用开发约束）、`docs/contracts/gateway-protocol-v2.md`（§6.1/§6.5/§7/§9）、`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`（§2/§4.4）、`docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md`（§12/§17.2/§18）、`CONTEXT.md`（本地发送单元/事件流等词条）、`docs/adr/0013`、`docs/adr/0045`
- 审查方式：**静态走读 + 跨文件证据核对**。本机未执行 Gradle：`apps/android/gradle/wrapper/gradle-wrapper.jar` 内不含 `org.gradle.wrapper.GradleWrapperMain`（`java -cp … GradleWrapperMain --version` 报 `ClassNotFoundException`），故"四模块单测全绿"这一说法未由本次审查独立复现，报告中所有结论均通过源码路径推导给出，并在必要处注明"未实测"。
- 结论一句话：**重复接收的两条真实根因（会话级订阅重叠、批量镜像用本地 id 建键）确实被修掉且补齐了回归测试；但本次同时引入的发送失败回滚存在一处可稳定复现的失效守卫（`draftRevision` 被 `removeAttachment` 自增导致恒不等），并且新增的 `memberIds` 与既有 `acceptedMessageIds` 形成两层重复表示、`memberIds` 缺失时仍会静默退回旧行为。**

---

## 1. 结论摘要

| 级别 | 数量 | 主题 |
| --- | --- | --- |
| 阻断 | 1 | 带附件的发送失败后正文不会回填输入框（`draftRevision` 守卫恒为 false） |
| 严重 | 3 | 附件类发送失败后内容从界面彻底消失；`memberIds` 缺失时镜像仍用本地 id（重复显示回归）；`acceptedMessageIds` 沦为死字段 |
| 建议 | 10 | 双层去重职责重叠、窗口常量无依据、`markEventHandled` 调用顺序、`close()` 非终态、单订阅者前提未声明、KDoc 与实现不符、游标 epoch 未纳入去重身份、失败回滚缺少线程守卫、测试硬化、模块边界清理 |

最关键的两条发现：

1. **阻断**：`WorkbenchController.kt:644` 的回滚守卫 `draftRevision == submission.revision` 在"提交带附件的消息"时**永远为 false**——因为 `:548` 的 `submission.attachmentIds.forEach(::removeAttachment)` 会调用 `removeAttachment`（`:479` 有 `draftRevision++`）。结果是提交说明里承诺的"把文本交还输入框"只在无附件时成立，带附件的失败发送会连带附件一起从界面消失。
2. **严重**：`WorkbenchController.kt:678` 的 `acceptance.memberIds[localId] ?: localId` 在 Gateway 未返回成员映射时退回到**本次修复之前的旧行为**（用本地 id 建镜像），而 `ConversationClient.kt:344-348` 对缺失/畸形的 `members` 数组是静默返回空 map，不会报错；此时只能依赖 `:1145-1177` 的"同文本 + 10 秒内"启发式去重兜底，跨端时钟偏差超过 10 秒即重新出现双份。

---

## 2. 阻断问题

### P0-1 带附件的发送失败时，正文不会回填输入框（回滚守卫恒为 false）

- 位置：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:644-648`（守卫），根因在 `:548` 与 `:477-485`。
- 证据链（按执行顺序）：
  1. `:506` 发送时冻结 `DraftSubmission(..., revision = draftRevision, ...)`；
  2. `:530` `if (draftRevision == submission.revision) update { it.copy(draft = "") }`——此时守卫成立，输入框被清空；
  3. `:548` `submission.attachmentIds.forEach(::removeAttachment)`——`removeAttachment` 在 `:479` 执行 `draftRevision++`，**每个附件自增一次**；
  4. `:591` `repository.submitMessage(target, message)` 抛错 → 跳转 `:624` catch；
  5. `:644` `if (state.draft.isEmpty() && draftRevision == submission.revision)`——`draftRevision` 已 ≥ `submission.revision + attachmentIds.size`，条件恒为 false → 走 `else` 分支 `state.draft`（空串）。
- 复现条件：任意带 ≥1 个已 VERIFIED 附件的发送（`remoteIds` 非空 → 走 `:588` 的非批次分支），且 `submitMessage` 抛异常（离线即可）。断言 `controller.state.value.draft == 原文本` 会失败。
- 影响：提交说明的"发送失败回滚待发条目并把文本交还输入框，重试不再叠加第二条"只对**纯文本**发送成立；带附件的失败发送既不回填正文，附件也已在 `:548` 被摘除（见 S1），用户看到的是 `SEND_FAILED` 通知 + 空输入框。
- 建议修法（二选一，均为一处小改）：
  - 在摘除附件前冻结"草稿未被用户改动"这一事实：
    ```kotlin
    // :530 附近
    val draftUntouched = draftRevision == submission.revision
    ...
    // :644
    draft = if (state.draft.isEmpty() && draftUntouched) submission.text else state.draft,
    ```
  - 或把 `removeAttachment`（面向用户的删除动作，理应变更 revision）与发送路径的内部摘除拆开：新增 `private fun detachAttachment(draftId: String)`（只清 `attachmentJobs`/`attachmentSelections`/`state.attachments`，**不**自增 `draftRevision`），`:548` 改调它。
- 测试缺口：新增的 `WorkbenchSendRegressionTest.kt:1551-1576`（`aFailedSendLeavesNoPendingCopyAndRestoresTheDraft`）没有附件，因此该守卫失效在本次提交里完全未被覆盖。

---

## 3. 严重问题

### P1-1 附件类发送失败后，用户选择的内容从界面上彻底消失且无重试入口

- 位置：`WorkbenchController.kt:543-551`（发送前摘除附件）、`:629-650`（失败回滚只处理文本与 `pendingBatch`）。
- 证据：
  - `:548` `submission.attachmentIds.forEach(::removeAttachment)` 在 HTTP 请求**之前**执行，`removeAttachment`（`:477-485`）会从 `state.attachments` 过滤掉草稿、从 `attachmentSelections` 移除选区（`:481`）；
  - `:551` 时间线条目的文本对"纯附件发送"是空串（`submission.text.ifBlank { … "" … }`）；
  - 失败回滚只回填 `draft = submission.text`（纯附件时为空串）并把 `:634` 的待发条目从 `pendingBatch` 删除。
- 复现条件：只选附件、不输入文字 → `canSend` 仍为 true（`WorkbenchController.kt:70-71`）→ 上传完成 VERIFIED → `submitMessage` 失败 → 输入框为空、时间线无该条目、`state.attachments` 已空。附件字节仍留在 `historicalAttachments`（`:540`，容量 30 的有界 LRU），但没有任何 UI 路径能把它取回。
- 与父提交的对比：`6aaadc6` 的失败分支只写 `composer = FAILED, notice = SEND_FAILED`，条目不删除，用户至少还能看到"卡住的待发项"；本次改动让内容直接消失（信息更少、无重试入口），属**新引入的可用性回归**。
- 建议修法：失败时不要"抹掉"，而是保留可重试的失败原子——三选一：
  1. 在 `TimelineEntry` 上增加失败态（例如 `sendFailed: Boolean`），失败时保留该条目并允许长按/点击重发；
  2. 失败时用 `historicalAttachments` 中的字节重建 `AttachmentDraft` 重新挂回 `state.attachments`（该表仍持有 `imageBytes`，见 `:534-541`）；
  3. 把 `forEach(::removeAttachment)` 从"发送前"挪到"接受成功后"（`:591-621` 的成功分支内），失败时草稿区保持原样。
  并补一条"带附件的发送失败"回归测试（可同时覆盖 P0-1）。

### P1-2 `memberIds` 缺失/畸形时镜像退回本地 id，重复显示在被修复的路径上静默回归

- 位置：`WorkbenchController.kt:672-701`，其中 `:678` 为 `val id = acceptance.memberIds[localId]?.takeIf { it.isNotBlank() } ?: localId`。
- 证据：
  - 设计规格要求映射必返：`docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md:603`"响应必须返回批次 ID、每个成员的 `clientMessageId → messageId` 映射、状态和 generation 排队信息"；ADR 0045 亦以"成员映射"为批次语义基础（`docs/adr/0045-…md:8`）。
  - 但客户端对缺失字段是**静默降级**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/conversations/ConversationClient.kt:344-348` 用 `JsonFields.objects(body, "members").mapNotNull { … }`，缺字段/缺 `messageId` 都只会得到空 map（`JsonFields.objects` 语义见 `…/schema/JsonFields.kt:34-35`），不抛错、不告警。
  - 降级后的行为：镜像键 = 本地 `clientMessageId`，与父提交 `6aaadc6`（`onSuccess = { _ -> val id = msg.clientMessageId.value }`）完全一致 → 服务端随后以自己颁发的 `messageId` 推送 `conversation.timeline.upsert` 时，`mirrored` 会同时存在两个键的两条用户消息（`:862-865`）。
  - 唯一的兜底是 `deduplicateTimelineEntries`（`:1145-1177`）里"同文本 + 同附件 + |Δt| ≤ 10 秒"的启发式（`:1154-1157`）；本地时间戳取 `entry?.timestamp`（`:680`，点击发送时的 `System.currentTimeMillis()`），服务端时间戳来自 Gateway，跨端时钟偏差 > 10 秒即双份渲染。
- 复现条件：`submitBatch` 返回 `BatchAcceptance(batchId, acceptedMessageIds, memberIds = emptyMap())`（即未映射），随后推送同一成员的服务端 `TimelineUpsert`，且两端时间戳相差 > 10 秒 → 时间线出现两条相同文本的用户消息。
- 现状说明（严重性校准）：两个参考 Gateway 当前都不协商 `message-batches-v1`——Hermes 的 `integrations/hermes/open_android_intelligence_gateway/core.py:2633-2637` 只回 `agent-command-catalog-v1`；OpenClaw 侧测试明确断言不回该能力（`integrations/openclaw/test/gateway-capabilities.test.ts:159-160`）。因此 `supportsMessageBatches`（`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:360`）今天恒为 false，本条属**潜在风险**：一旦服务端补上该能力而对响应字段不完全合规，本次修复会静默失效。
- 建议修法：把"没有映射"当成**不能建镜像**，而不是"用本地 id 建镜像"：
  ```kotlin
  val serverId = acceptance.memberIds[localId]?.takeIf { it.isNotBlank() }
  if (serverId == null) {
      // 该成员没有服务端身份：保留在 pendingBatch 等 MessageAccepted/TimelineUpsert 确认，
      // 绝不按本地 id 建镜像（那正是重复显示的来源）
      return@forEach
  }
  ```
  同时让 `ConversationClient.submitBatch` 对"响应缺 `members` 数组"与"成员缺 `messageId`"分别显式失败或上报（`SUBMIT_BATCH_FAILED:missing-member-mapping`），避免契约违例被静默吞掉。

### P1-3 `BatchAcceptance.acceptedMessageIds` 成为死字段，新旧两种表示并存

- 位置：`apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ports/ConversationPorts.kt:49-53`；赋值点 `apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt:149-155`。
- 证据：
  - `GatewayConversationRepository.kt:151` `acceptedMessageIds = acceptance.memberIds.values.toList()`——它现在**恒等于** `memberIds.values`（同一构造块 `:149-155`）；
  - 全仓检索 `acceptedMessageIds` 只有三处：定义（`ConversationPorts.kt:51`）、该处赋值、以及测试 `WorkbenchSendRegressionTest.kt:1528`；**没有任何生产读取方**（`WorkbenchController` 的批次成功分支只读 `acceptance.memberIds`，`:678`）；
  - 父提交里它同样没有读取方，即本次"新增 `memberIds`"时没有顺手清掉被替代的表示。
- 规范依据：`AGENTS.md:42`"严禁伪造实现与死状态"、`AGENTS.md:46`"重构必须原子化清除废弃旧代码：引入新方案时必须同步清理被替代的旧实现与调用方，严禁新旧两套架构长期并存"。
- 复现条件：静态可验证——删除 `acceptedMessageIds` 字段后编译器只会报出 `GatewayConversationRepository.kt:151` 与 3 个测试构造点（`WorkbenchSendRegressionTest.kt:1528`、`:1619`、`WorkbenchTitleRegressionTest.kt:187`、`WorkbenchLayoutRegressionTest.kt:129/220/262/306`），无生产代码受影响。
- 建议修法：以 `memberIds` 为唯一权威（它同时可导出 `acceptedMessageIds`），删除 `acceptedMessageIds` 并同步改动上述测试构造点；若确实需要"仅需要 id 列表"的调用方视图，则改为计算属性 `val acceptedMessageIds: List<String> get() = memberIds.values.toList()`，彻底消除可漂移的冗余状态。

---

## 4. 建议

1. **去重职责重叠（传输层 + 会话层）应收敛为一处。** `GatewayHttpClient.kt:95-119`（实例级 `deliveredEventIds`，窗口 4096）与 `WorkbenchController.kt:117-125 / :799 / :1265-1278`（窗口 2048）对同一 event id 做了两次幂等，且插入顺序相同、内层窗口更小 → 内层保留集是外层保留集的**严格子集**：经 `GatewayConversationRepository`（`:203-207`，`client.rawEvents()` 即 `events()`）这条唯一生产路径，`markEventHandled` 的丢弃分支**不可达**，`handledEventIds` 只是多占内存与多一层语义。建议指定唯一归属（倾向保留 port 层语义，因为 fake/未来镜像实现同样需要它，测试 `WorkbenchSendRegressionTest.kt:1489-1519` 也依赖它），把传输层那份降级为"注释化的优化"或直接删除；若保留双层，请统一常量并写明各自理由。
2. **两个窗口常量缺依据。** `GatewayHttpClient.kt:329-335`（`MAX_TRACKED_EVENT_IDS = 4096`）与 `WorkbenchController.kt:1284-1290`（`MAX_HANDLED_EVENT_IDS = 2048`）用了措辞相同的 KDoc（"窗口只需长过一次重连"）却给出不同数值，且都没有论证；父提交用的是 1000/500。建议：单一常量 + 明确依据（"同一 client 内游标由 `EventCursorStore` 保持，重连重放距离 ≤ 游标保留窗口"，契约 §9:544 与 ADR 0013 的"有界事件游标"），并说明淘汰最旧一半的策略为何安全。这也顺带满足 `AGENTS.md:44` 对硬编码魔数的约束。
3. **`markEventHandled` 的调用位置早于 `activeThreadId` 空值分支。** `WorkbenchController.kt:799` 先登记，`:800-803` 才判断 `activeThreadId == null` 并 `return@collect`；一旦该分支可达，"首次到达时无活跃会话"的事件就被永久消费（结合 `:800` 的 `refreshThreads()`，用户切回该会话也看不到）。**本次审查未能构造出可达路径**：`activeThreadId` 只在 `:329`（`openThread`）与 `:404`（`createThreadAsync`）被赋值，全文没有置空语句，而订阅只在 `:384 / :413 / :560-562` 启动（均在 `activeThreadId` 非空之后），因此**不计为缺陷**；但顺序仍建议对调（先判会话再登记）以消除这个未来陷阱。顺带指出：该分支本身属 `AGENTS.md:42` 意义上的死状态，宜删除或改为有明确触发条件的路径。
4. **`close()` / `cancel()` 不是终态标志，且没有生产调用点。** `WorkbenchController.kt:203-214` 只取消各 `Job`；由于它不是"已关闭"标记，关闭后再调 `sendDraft()`（`:560-562` 判断 `eventJob?.isActive != true`）或 `openThread()`（`:384`）会**重新订阅**。检索 `apps/android/app/src/main` 与 `apps/android/conversation-ui/src/main` 未发现任何 `close()`/`cancel()` 调用点（仅测试调用），生产释放完全依赖 `GatewayRuntime.teardown()` 取消 `sessionJob`（`GatewayRuntime.kt:374-381`，`sessionScope` 定义在 `:339`）。建议加 `private var closed = false` 让 `observeThreadEvents`/`submitWhenAttachmentsVerified` 拒绝在关闭后重启订阅，或把 `cancel()` 收敛为内部 API，避免"已被销毁的控制器仍能拉起网络订阅"这种误用。
5. **单订阅者前提应写进契约化注释。** 实例级集合使 `events()` 从"每个 collector 各自看到全量"变成"每个 event 只会交给一个 collector"（`GatewayHttpClient.kt:179-184 / :247-252` 先登记后 `emit`）。当前生产只有一个订阅者（`WorkbenchController.kt:787`），所以没有故障；但第二个 collector（未来某个持有 `StreamHealthSource` 的界面）会被静默饿死。建议在 `events()` 的 KDoc 上明确"同一 client 只允许一个并发 collector"，或改为带内部缓存的广播实现。
6. **KDoc 与实现不符，建议写明 at-most-once 权衡。** `GatewayHttpClient.kt:85-94` 声称记录的是"已经交给 collector 的事件 id"，但登记发生在 `emit` **之前**（`:179`/`:247`）；若 collector 在 `emit` 挂起期间被取消，事件会"已登记但未应用"，且此后永不再投递。补偿控制是会话层的健康度回拉（`WorkbenchController.kt:245-249`，LIVE → 非 LIVE 时 `reloadTimeline`）与重连时的 `SnapshotInvalidated`（`:925-930`）。建议在 KDoc 里写明"投递语义为至多一次，丢失由时间线回拉补偿"，避免后来者误以为它是"至少一次"。
7. **去重身份缺 epoch/fencing，未来的游标重建会与它冲突。** 契约 §9（`docs/contracts/gateway-protocol-v2.md:544`）要求 `CURSOR_EXPIRED` 后"先重建资源快照，再使用响应给出的新游标恢复流"。当前代码没有任何 `cursorStore.clear(...)` 调用（`EventCursorStore.clear` 定义在 `…/events/EventCursorStore.kt:15`，无调用方），所以问题尚不成立；但一旦按契约在过期时清空游标，`deliveredEventIds` 必须**同时**清空，否则重建后的重放会被静默丢弃。参考实现用 `evt_{uuid4}`（`integrations/hermes/open_android_intelligence_gateway/core.py:1206`）不会复用 id，建议把"event id 全局唯一、跨 epoch 不复用"写成显式假设，或把 epoch/`snapshotRevision` 纳入去重键。
8. **失败回滚缺少成功路径已有的线程守卫。** `WorkbenchController.kt:592` 的成功分支有 `if (activeThreadId == target)` 保护，而 `:629-650` 的新回滚分支没有：它无条件写 `timeline = Loadable.Ready(renderTimeline(remainingBatch))`。由于 `openThread` 不会取消在飞的发送协程（`:328` 的 `cancelPendingSubmission()` 只清 `pendingSubmission`），线程切换期间落地的失败回调会把新会话的 `Loading`（`:341`）覆盖成当前 `mirrored` 的 `Ready`（自愈，但瞬时状态错误）。建议补同一守卫（`if (activeThreadId == (submission.conversationId ?: target))`）。注：输入框 `draft` 是全局字段（切会话不重置），因此 `draft` 回填不必加线程守卫。
9. **测试硬化（维度 5 逐条结论）。** 现有 5 个新测试都能在父提交上失败，**非恒真断言**，逐条依据：
   - `WorkbenchSendRegressionTest.kt:1463-1487`（`switchingThreadsKeepsASingleEventSubscription`）：父提交 `openThread` 每次都 `eventJob?.cancel()` 后重建，计数会是 3 → 断言 1 会失败。属可接受的实现耦合（这正是被修缺陷本身），但建议同时断言"切换会话后同一条事件只被应用一次"的行为面。
   - `:1489-1519`（`aReplayedEventIdIsAppliedOnlyOnce`）：用 `SnapshotInvalidated → reloadTimeline` 作为可观测副作用，断言精确值 `1`，父提交会是 2 → 有效。
   - `:1521-1549`（`batchMembersAreMirroredUnderTheGatewaysMessageId`）：断言渲染键是服务端 id，父提交是本地 id → 有效；但 `advanceTimeBy(1_501L)` 硬编码了防抖默认值 1500ms（`apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/batch/DebounceBatcher.kt:11`），建议 `batchedController` 传入显式的小窗口策略。
   - `:1551-1576`（`aFailedSendLeavesNoPendingCopyAndRestoresTheDraft`）：父提交会保留待发条目并清空草稿 → 3 条断言全失败，有效；**但无附件**，因此漏掉 P0-1（建议补带附件用例）。
   - `GatewayEventStreamTest.kt:447-458`（`re-subscribing …`）：父提交的 `seenEventIds` 是 `flow {}` 内局部变量，第二次订阅仍会发出 `evt_01` → 断言 0 有效；建议同时断言第二次订阅仍能收到**新**事件（在 `RecordingTransport` 的第二批 chunk 里加 `id: evt_02`）来证明"去重不等于掐断"。另注意它验证的是实例级跨订阅语义，运行期请确认没有别的用例共享同一 `GatewayHttpClient` 实例。
   - 结构性缺口：5 个新用例都建立在 fake repository 上，**没有任何用例覆盖"传输层去重 + 会话层去重"的真实组合**（这也正是建议 1 的依据）；建议补一个用 `GatewayHttpClient` + `GatewayConversationRepository`（`GatewayWireRegressionTest.kt:75-78` 已有此装配范式）的集成用例。
10. **模块边界清理。** `apps/android/conversation-ui/build.gradle.kts` 声明了 `implementation(project(":gateway-client"))`，但 `conversation-ui/src/main` 与 `src/test` 中检索 `openandroidintelligence.gateway` 为 0 命中（UI 只用 `conversation-domain`/`conversation.model`）。这是架构规格"可见核心 / 内核"边界（`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md` §2、§4.4）上的隐患：留着它，UI 层随时可能误引网络类型。建议删除该依赖。

---

## 5. 已核实无问题的点

1. **本次修复的核心目标确实达成（重复接收/重复显示的两条根因）。** ① 订阅重叠：`WorkbenchController.kt:776-785` 改为"已活跃则返回"，并删除了 `openThread`（原 `:323` 附近）与 `createThreadAsync`（原 `:399` 附近）里的 `eventJob?.cancel()`；`repository.observeEvents` 只在 `:787` 一处被调用，账号级单订阅成立（事件本身是账号级：Hermes 侧有"别的账号的事件不得泄漏到该流"的测试，`integrations/hermes/tests/test_event_stream_transport.py:132-139`）。② 批量镜像键：`:678` 优先用 Gateway 颁发的 `messageId`，与设计规格 §17.2 的成员映射要求一致。③ 顺带消除了一个真实隐患：`CommandResult` 分支在 `collect` 内部调用 `openThread`（`:932-937`），旧实现会在自己的 `collect` 中取消自己所在的 `Job`，新守卫（`:785`）不会。
2. **模块依赖方向未被破坏。** `conversation-domain` 的构建脚本只依赖 coroutines（`apps/android/conversation-domain/build.gradle.kts`），`ConversationPorts.kt` 的 import 只有 `conversation.model.*` 与 `kotlinx.coroutines.flow.Flow`（`:3-4`），新增字段 `memberIds: Map<String, String>`（`:52`）纯 stdlib，未引入任何 gateway 类型；`conversation-ui/src/main` 对 `com.openandroidintelligence.gateway` 0 命中；`conversation-data` 仍是唯一把 wire 类型映射到领域类型的适配层（`GatewayConversationRepository.kt:20-22` 的 `as WireBatchAcceptance` 与 `:135-155` 的映射），方向正确（ui → domain ← data → gateway-client）。
3. **契约层面一致，既有语义未被改写。** `memberIds` 是设计规格 §17.2 明确要求的响应字段（`…ui-design.md:603`），wire 类型早已建模（`ConversationClient.kt:98-103`，解析 `:344-355`），本次只是把它带到领域端口；`acceptedMessageIds` 取值仍是 `memberIds.values.toList()`（`GatewayConversationRepository.kt:151`），与父提交的 `acceptance.memberIds.values.toList()` 逐字节等价，因此"没有改变既有语义"这一说法成立（但该字段已死，见 P1-3）。SSE/游标契约未被触碰：cursor 仍由 canonical query 承载（契约 §9:520；`GatewayHttpClient.kt:210-213`），心跳/无 id 帧不参与去重（`apps/android/gateway-client/…/events/SseParser.kt:8-12` 的 `id: String?` 与 `GatewayHttpClient.kt:107` 的 `isNullOrBlank()` 早退），`Last-Event-ID` 语义未被本次改动引入；本次改动的方向与设计规格 §18"进程死亡、超时、重复回调和 SSE 重放不重复提交"的要求一致（`docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md:764`）。
4. **旧实现已原子清除。** 父提交 `events()` 内的局部 `seenEventIds`（1000/500 字面量）在 `GatewayHttpClient.kt` 中已完全消失，全仓检索 `seenEventIds` 为 0 命中，符合 `AGENTS.md:46`。
5. **内存与淘汰策略可接受，且不会淘汰"仍需记住"的 id。** 两个集合都是 `LinkedHashSet`，上限 4096 / 2048，超限时按插入顺序移除最旧一半（`GatewayHttpClient.kt:109-117`、`WorkbenchController.kt:1268-1276`）；以 `evt_<uuid4>` ≈ 40 字符估算，最坏约数百 KB，不构成移动端内存风险。关键点是**淘汰不会误伤**：同一 `GatewayHttpClient` 实例内 `EventCursorStore` 持续保存游标（`:178`、`:219`），重连是从游标之后续传而非全量重放，重放距离远小于窗口；而进程重启/重新 `establish()` 时客户端实例与 `InMemoryEventCursorStore`（`GatewayRuntime.kt:317-323`）一同重建，去重集合也随之清空，不存在"用新客户端的旧集合误丢事件"。唯一残余情形是"同一连接被重放超过 4096 条"，与本条注释声明的有界窗口取舍一致（ADR 0013:8"有界事件游标"；`CONTEXT.md:305-307` 把"无界重放"列为应避免项）。
6. **`@Synchronized` 不会造成 Main 线程阻塞。** 事实核对：事件流确实在 Main 上被收集——`sessionScope = CoroutineScope(ownedJob + Dispatchers.Main.immediate)`（`GatewayRuntime.kt:339`）→ `WorkbenchController.kt:786-787` → `repository.observeEvents` → `GatewayHttpClient.events()`（`GatewayHttpClient.kt:144`）；传输层的阻塞 I/O 通过 `flowOn(Dispatchers.IO)` 隔离（`apps/android/gateway-client/…/GatewayTransport.kt:113`），因此 `markEventDelivered`（`:105-119`）在 Main 上执行。但临界区只有一次哈希插入，最坏再叠加 2048 次迭代删除，无 I/O、无挂起、无分配热点，不构成 ANR 风险；且当前只有一个 collector，锁竞争只是理论问题。附注：该文件 `:15` 的 `import kotlinx.coroutines.flow.flowOn` 未被使用（父提交 `6aaadc6` 同样未使用，非本次引入），属可顺手清理的无用导入。
7. **"已活跃则返回"不会导致事件流永久不恢复。** 逐条排除：① 正常结束——`events(autoReconnect = true)`（`:144`）的 `while (currentCoroutineContext().isActive)` 不会自然退出，只有 `autoReconnect = false` 才 `break`（`:284-287`），而生产走 `ConversationClient.rawEvents()` 的无参调用（`ConversationClient.kt:116`）；② 传输层放弃——连续 6 轮失败后 `throw lastFailure`（`:277-280`），但 `retryWhen`（`WorkbenchController.kt:788-796`）除 `CancellationException` 外一律 `delay(1000L)` 后重试，`Job` 保持活跃；③ 极端情况下 job 真的完成——`eventJob?.isActive != true` 成立，下一次 `openThread`（`:384`）或发送（`:560-562`）会重建订阅，不存在"无人重新订阅"的死角。
8. **移除 `eventJob?.cancel()` 后，登出/释放路径仍正确。** `WorkbenchController.close()`（`:203-212`）仍显式 `eventJob?.cancel()`；生产侧最终释放由 `GatewayRuntime.teardown()`（`GatewayRuntime.kt:374-381`）取消 `sessionJob` 并把 `_controller.value` 置空完成，而 `sessionJob` 正是 `WorkbenchController` 所在 `CoroutineScope` 的父 `Job`（`:336-339`、`:354-355`），订阅会随作用域取消而终止。也就是说"生产环境里谁取消订阅"这件事没有因为本次删除而失去归属，只是归属从"切会话"变成了"会话结束"——这正是提交说明想表达的账号级语义。
9. **`markEventHandled` 先于 `activeThreadId` 空值判断，不会造成"首帧永久丢弃"。** 见建议 3 的完整证据：`activeThreadId` 一旦被赋值就不会再回到 `null`（全文无置空语句），而订阅的三种启动点都在其非空之后，故该分支在生产路径不可达，本次**不计为缺陷**，仅作为顺序加固建议。
10. **5 个新增测试均非恒真断言，且各自锁定一个由本次提交引入的可观测差异**（逐条依据见建议 9）。唯一需要补充的是覆盖面（带附件的失败发送、`memberIds` 为空的降级、双层去重组合），已分别对应 P0-1 / P1-2 与建议 1。

---

## 6. 附：本次审查未覆盖/未能验证的内容

- 未执行任何 Gradle 任务（wrapper 主类缺失，见文首"审查方式"），因此"四模块单测全绿"与 CI 结果（`.github/workflows/ci.yml`）需由作者/CI 侧确认。
- 未做真机/真 Gateway 的端到端重放验证（Hermes 侧无 `message-batches` 实现，OpenClaw 不协商该能力，批量路径当前无服务端可对拍；事件流去重的端到端验证需要真机断线重连场景）。
- `WorkbenchController` 中既有的启发式去重（`pruneConfirmedAssistantDuplicates`、`deduplicateAssistantGroup` 等）不在本次改动范围，本次未评估其正确性。
