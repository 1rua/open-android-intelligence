# Open Android Intelligence 问题审查报告

本报告针对当前系统存在的三个主要异常现象进行全链路深入审查：
1. **接收到 Agent 最终回复时有概率闪退**
2. **发送消息后气泡下方一直显示“等待 Agent 接收”，即使 Agent 已开始输出，但在收到最终回复前仍然显示该状态**
3. **接收 Agent 的回复并不流畅，无法达到流式输出的效果**

排查范围覆盖：HTTP / SSE / WebSocket 传输链路、Agent 插件与 OAI Core 的适配连接、OAI Core 与手机端的通信交互、以及 Android App 内部的状态机、存储层与 Compose UI 渲染树。

---

## 目录
- [问题 1 审查：接收到 Agent 最终回复时概率闪退](#问题-1-审查接收到-agent-最终回复时概率闪退)
  - [1.1 触发链路总览](#11-触发链路总览)
  - [1.2 核心根因 1：Compose LazyColumn 条目 Key 突变与 animateItem 测量冲突](#12-核心根因-1compose-lazycolumn-条目-key-突变与-animateitem-测量冲突)
  - [1.3 核心根因 2：通知服务中 check(commit()) 同步写盘强断言崩溃](#13-核心根因-2通知服务中-checkcommit-同步写盘强断言崩溃)
  - [1.4 核心根因 3：流式结束阶段的 LocalSendAtom 标识竞态冲突](#14-核心根因-3流式结束阶段的-localsendatom-标识竞态冲突)
  - [1.5 边界诱因：Agent 终态 Payload 为空或结构截断](#15-边界诱因agent-终态-payload-为空或结构截断)
- [问题 2 审查：气泡下方常驻“等待 Agent 接收”状态](#问题-2-审查气泡下方常驻等待-agent-接收状态)
  - [2.1 触发链路总览](#21-触发链路总览)
  - [2.2 核心根因 1：Agent 适配器层 delivered 状态上报缺失与滞后](#22-核心根因-1agent-适配器层-delivered-状态上报缺失与滞后)
  - [2.3 核心根因 2：App 状态机未在流式 Chunk 到达时联动更新 Prompt 状态](#23-核心根因-2app-状态机未在流式-chunk-到达时联动更新-prompt-状态)
  - [2.4 核心根因 3：UI 渲染分支优先级硬绑定](#24-核心根因-3ui-渲染分支优先级硬绑定)
- [问题 3 审查：回复不流畅无法达到流式效果](#问题-3-审查回复不流畅无法达到流式效果)
  - [3.1 传输全链路各环节排查结论](#31-传输全链路各环节排查结论)
  - [3.2 核心根因 1：本地加密镜像库在事件流中同步事务刷盘（主要瓶颈）](#32-核心根因-1本地加密镜像库在事件流中同步事务刷盘主要瓶颈)
  - [3.3 核心根因 2：Channel.CONFLATED 与极小缓冲区导致的帧丢弃与跳字](#33-核心根因-2channelconflated-与极小缓冲区导致的帧丢弃与跳字)
  - [3.4 核心根因 3：全量 Timeline 重构与高频 Compose 重组开销](#34-核心根因-3全量-timeline-重构与高频-compose-重组开销)
- [审查结论综合矩阵](#审查结论综合矩阵)

---

## 问题 1 审查：接收到 Agent 最终回复时概率闪退

### 1.1 触发链路总览
当 Agent 生成结束，网关推送最终回复（`conversation.message.completed`）或终态控制事件（`conversation.message.status: COMPLETED`）到达手机端后，App 会在极短时间内（同 1~2 帧内）并发触发：
1. 数据库 / 加密存储的终态写入与通知更新；
2. 会话状态从 `RUNNING` 切换至 `COMPLETED`；
3. 时间线聚合模型将助手消息从流式平铺态折叠为执行汇总态；
4. Compose LazyColumn 针对更新后的数据重新测量与启动位置动画。

闪退往往发生在此瞬时交汇点。

### 1.2 核心根因 1：Compose LazyColumn 条目 Key 突变与 animateItem 测量冲突
- **相关代码位置**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:415-426`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/TimelineDisplay.kt:178-193, 258-269`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3048-3083`

- **机理剖析**：
  1. 在流式输出过程中，`generation` 处于 `GenerationState.RUNNING` 状态，此时 `TimelineDisplayModel.buildSegment` 构建出的时间线元素类型为 `TimelineDisplayItem.Single`，其 LazyColumn 声明的 item Key 为消息本身的字符串 ID（如 `"message:stream_req_1"`）。
  2. 当收到 Agent 最终回复时，`generation` 状态瞬时变为 `GenerationState.COMPLETED`。
  3. `TimelineDisplayModel` 判断 `terminalGeneration == true`，触发聚合折叠逻辑，原有的 `TimelineDisplayItem.Single` 突然消失，被合并为 **`TimelineDisplayItem.Execution`**。
  4. 该条目的 Key 在同一帧内突变为 `"execution_segment:${analyses.first().entry.key}"`，且条目内部的渲染节点从单一的 `AssistantMessageContent` 替换为包裹多步骤面板的复合组件 `ExecutionTurnRow`。
  5. `WorkbenchScreen.kt` 的 LazyColumn 条目应用了 `Modifier.animateItem(placementSpec = ...)`，并且伴随时间线尾部更新（`tailSignature` 变化）触发了 `listState.animateScrollToItem(lastItemIndex)`。
  6. 在 Compose Foundation 运行时中，底部条目在测量高度、执行旧节点退出/新节点进入动画的过程中，其唯一 Key 突然被更换，导致 `LazyItemPlacementAnimator` 与 `LazyListLayoutInfo` 的内部跟踪表发生指针错位与状态不一致，抛出 `IllegalArgumentException` / `IllegalStateException: Item was already removed` / `IndexOutOfBoundsException`，引发不可恢复的直接闪退。

### 1.3 核心根因 2：通知服务中 check(commit()) 同步写盘强断言崩溃
- **相关代码位置**：
  - `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:384-386`
  - `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:557`

- **机理剖析**：
  1. 当收到终态事件（`AgentMessageStatus.COMPLETED` 或 `conversation.message.completed`）时，系统会触发后台通知的收尾清理逻辑 `finish(...)`。
  2. 源码中执行：
     ```kotlin
     val edit = preferences.edit().putLong("done.$key", occurredAt).remove("reply.$key").remove("success.$key")
     check(edit.commit())
     ```
  3. `SharedPreferencesImpl.commit()` 是同步阻塞性写入磁盘，当操作由于底层 I/O 争用（如加密数据库此时正大量写入历史快照）、存储写缓存压力或权限受阻导致返回 `false` 时，Kotlin 标准库的 `check(...)` 会直接抛出 `IllegalStateException: Check failed.`。
  4. 该回调处于事件分发的关键调用栈内，缺乏外层异常捕获防护，导致进程直接闪退。

### 1.4 核心根因 3：流式结束阶段的 LocalSendAtom 标识竞态冲突
- **相关代码位置**：
  - `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt:348-355`
  - `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt:806-808`

- **机理剖析**：
  1. 在 `CoreAgentConversationRepository.kt` 中，流式结束时会调用：
     ```kotlin
     emitEvent(agent.id, VerifiedConversationEvent.MessageStatus(..., messageId, message.clientMessageId, AgentMessageStatus.COMPLETED, ...))
     ```
  2. 在 `EncryptedConversationMirror.kt` 处理状态落盘时，会校验内存中的 Atom 列表：
     ```kotlin
     val existing = atoms.firstOrNull { it.clientMessageId == event.clientMessageId || it.messageId == event.messageId }
     check(existing == null || existing.clientMessageId == event.clientMessageId) { "MESSAGE_ID_CONFLICT" }
     ```
  3. 当服务端下发的 `messageId` 在中途或收尾时与本地预生成的原子标识发生错位映射（或因多次提交导致映射分歧），`check` 失败抛出 `"MESSAGE_ID_CONFLICT"` 异常崩溃。

### 1.5 边界诱因：Agent 终态 Payload 为空或结构截断
- **相关代码位置**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/TimelineDisplay.kt:211-213`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt`

- **机理剖析**：
  当 Agent 仅调用了工具但最终回复正文为空字符串 `""`、或输出流在思考标签 `<think>` 未闭合时直接被服务端 `done` 截断时，`TimelineDisplayModel.buildRun` 在计算 `finalAnalysis.finalVisible` 时可能出现空边界；Markdown AST 解析树在强制收尾折叠时的非预期布局约束可能导致 Compose 测量树抛出尺寸计算异常。

---

## 问题 2 审查：气泡下方常驻“等待 Agent 接收”状态

### 2.1 触发链路总览
用户发送消息后，界面上该用户消息气泡下方显示“等待 Agent 接收”（黄色警告色）。即使此时 Agent 已经开始在下方打印出流式字符，该用户气泡下方的文案依然维持“等待 Agent 接收”，直至最终完成甚至一直不变。

### 2.2 核心根因 1：Agent 适配器层 delivered 状态上报缺失与滞后
- **相关代码位置**：
  - `integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py:863, 873, 1069-1098`
  - `oai-core/src/oai_core/gateway/core.py:3368-3413 (update_dispatch_status)`

- **机理剖析**：
  1. 在 Gateway 模型中，消息生命周期状态定义为：
     `queued -> delivered -> completed / failed`。
  2. 用户提交消息后，Gateway 将 dispatch status 初始化为 `queued`，并通过 SSE 广播 `conversation.message.status: queued`。
  3. 当消息分发到 Agent 适配器（如 Hermes Session Adapter）后，适配器在调用 Agent 运行时处理消息（`await self.handle_message(event)`，第 863 行）的**整个执行与流式生成周期内，均未向 Gateway 上报 `delivered` 状态**。
  4. 直到 Agent 全部输出完毕、整轮 turn 进入 `on_processing_complete`（第 1070 行）时，代码才检测到：
     ```python
     if status == "completed" and record["status"] == "queued":
         account.conversations.update_dispatch_status(record["messageId"], "delivered", None, ...)
     account.conversations.update_dispatch_status(record["messageId"], status, error_code, ...)
     ```
  5. 结论：在 Agent 思考与吐字流式输出的全部时间段内，服务端**从未发出过 `delivered` 状态事件**。

### 2.3 核心根因 2：App 状态机未在流式 Chunk 到达时联动更新 Prompt 状态
- **相关代码位置**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3624-3712`

- **机理剖析**：
  1. 当助手流式内容到达时，客户端接收并解码到的是 `TimelineUpsert` 事件（`sender="assistant", state="STREAMING"`）。
  2. `WorkbenchController` 在处理流式 chunk 时，仅执行了：
     - 更新 assistant 气泡文本内容；
     - 更新全局生成状态 `state.generation = GenerationState.RUNNING`。
  3. 控制器中**没有任何逻辑将触发该轮生成的对应 user 消息（Prompt）的 `messageStatus` 或 `LocalSendAtom.status` 联动置为 `DELIVERED` 或移除等待标记**。
  4. App 严格依赖服务端回传 `conversation.message.status` 事件来变更用户消息的状态。由于服务端在生成过程中不发送 `delivered` 事件，用户消息的状态就一直死锁在发送初始时的 `QUEUED`。

### 2.4 核心根因 3：UI 渲染分支优先级硬绑定
- **相关代码位置**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:246-248, 784-798`

- **机理剖析**：
  1. 在气泡底部元数据渲染组件 `MessageMetadata` 中：
     ```kotlin
     val meta: Pair<String, Color>? = when {
         entry.submissionFailureCode != null -> ...
         entry.messageStatus != null ->
             messageStatusLabel(entry.messageStatus, entry.messageStatusErrorCode) to when {
                 failed -> MaterialTheme.colorScheme.error
                 entry.messageStatus == AgentMessageStatus.QUEUED -> stateColors.warning
                 else -> MaterialTheme.colorScheme.onSurfaceVariant
             }
         entry.pendingAcceptance -> ...
         entry.isStreaming -> "正在输出…" to MaterialTheme.colorScheme.onSurfaceVariant
         else -> formatTime(entry.timestamp)...
     }
     ```
  2. 映射函数：
     ```kotlin
     fun messageStatusLabel(status: AgentMessageStatus, errorCode: AgentMessageErrorCode?): String = when (status) {
         AgentMessageStatus.QUEUED -> "等待 Agent 接收"
         AgentMessageStatus.DELIVERED -> "Agent 已接收"
         AgentMessageStatus.COMPLETED -> "Agent 处理完成"
         ...
     }
     ```
  3. 由于 user 消息的 `entry.messageStatus` 为 `AgentMessageStatus.QUEUED`（非 null），无论后续是否已在流式生成，`when` 分支都会首选命中 `entry.messageStatus != null`，强制显示黄色文字 `"等待 Agent 接收"`。

---

## 问题 3 审查：回复不流畅无法达到流式效果

### 3.1 传输全链路各环节排查结论

为准确定位流式卡顿发生在哪一段，对全链路进行了逐级剖析：
1. **Agent 插件适配器层**（`oai-core/src/oai_core/adapters/remote.py:282-291`）：
   通过 `async for line in response.content` 读取上游 SSE，每收到一行合法 json 立即 `yield value`，内部无批处理阻滞。
2. **OAI Core 服务端层**（`oai-core/src/oai_core/sessions.py:618-623`）：
   采用 aiohttp `web.StreamResponse`，声明 `Cache-Control: no-cache` 并开启分块传输。每到达一个 delta 均调用 `await response.write(...)`，服务端无主动积攒或响应级 buffer。
3. **Android 网络传输层**（`apps/android/gateway-client/.../AgentSessionClient.kt:148-173`）：
   使用阻塞式 `stream.read(buffer)` 读取并由 `SseParser` 解析。一旦收到包含 `\n\n` 终止符的 SSE 事件包，立即回调 `onDelta`，传输层本身无大块延迟。

**流式输出不流畅的瓶颈并非处于网络与网关层，而是在 Android 客户端的数据流持久化与分发管道中。**

### 3.2 核心根因 1：本地加密镜像库在事件流中同步事务刷盘（主要瓶颈）
- **相关代码位置**：
  - `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt:833-841, 942-948`

- **机理剖析**：
  1. UI 时间线控制器 `WorkbenchController.observeThreadEvents()` 监听的 Flow 上游被 `EncryptedConversationMirror.observeEvents()` 拦截：
     ```kotlin
     override fun observeEvents(scope: ConversationScope) = delegate.observeEvents(scope).mapNotNull { event ->
         mirror.applyAndCommit(event)
         mirror.projectEvent(event)
     }
     ```
  2. 而 `applyAndCommit` 的实现为：
     ```kotlin
     suspend fun applyAndCommit(event: VerifiedConversationEvent) = withContext(ioDispatcher) {
         synchronized(this@EncryptedConversationMirror) {
             documents.transaction {
                 apply(event)
                 if (event.eventId.isNotBlank()) saveCursor(scope.accountId, event.eventId)
             }
         }
         documents.flush() // 同步加密刷盘！
     }
     ```
  3. 当 Agent 处于流式输出时，客户端每接收到一个增量快照事件（约每 33ms 一次），管道内就会同步执行一次 `documents.transaction` 并在**每次事件后调用 `documents.flush()` 进行全量加密序列化与磁盘 I/O 写入**。
  4. 磁盘写操作与加密计算的高频开销引发了极大的**反向背压（Backpressure）**，导致上游事件流消费速度严重滞后。

### 3.3 核心根因 2：Channel.CONFLATED 与极小缓冲区导致的帧丢弃与跳字
- **相关代码位置**：
  - `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt:96, 258-273`

- **机理剖析**：
  1. `CoreAgentConversationRepository` 内部定义了事件合并分发信道：
     ```kotlin
     private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1)
     val snapshots = Channel<List<VerifiedConversationEvent>>(Channel.CONFLATED)
     ```
  2. 发送逻辑采用 `snapshots.trySend(pending.values.toList()).getOrThrow()`。
  3. 当下游由于 3.2 节所述的加密磁盘 I/O 以及主线程重组开销而消费变慢时，`Channel.CONFLATED` 特性会直接**用最新的快照覆盖掉管道中尚未被消费的全部历史快照**。
  4. 现象与体感：中间大量流式打字过程被系统静默丢弃，界面表现为画面停顿卡死 1~3 秒，随后“砰”的一下瞬间跳出一大段完整文字，完全失去了逐字流式打印的平滑视觉效果。

### 3.4 核心根因 3：全量 Timeline 重构与高频 Compose 重组开销
- **相关代码位置**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3691-3711, 4117-4203`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:98`

- **机理剖析**：
  1. 即使突破了丢帧，到达 UI 的每次 `TimelineUpsert` 均会调用 `update { state -> state.copy(timeline = Loadable.Ready(renderTimeline(...))) }`。
  2. `renderTimeline` 每次都会将历史所有条目重新执行遍历、`sortedWith` 排序与 `deduplicateTimelineEntries` 去重。
  3. 上层 `rememberTimelineDisplayItems` 会在 `entries` 变化时执行完整的 AST 扫描分析与 `TimelineDisplayModel().build(entries)`，在长对话历史下显著加剧 Android 主线程（UI 线程）调度开销，引发微卡顿与掉帧。

---

## 审查结论综合矩阵

| 问题编号 | 表面现象 | 核心根因归属模块 | 关键代码位置 | 核心机理定位 |
| :--- | :--- | :--- | :--- | :--- |
| **问题 1** | 收到最终回复概率闪退 | Android Compose UI / 通知服务强断言 | `WorkbenchScreen.kt:415`<br>`TimelineDisplay.kt:181`<br>`ReplyNotifications.kt:384` | 终态到达瞬间条目 Key 突变触发 `animateItem` 测量树崩溃；同时通知服务在 I/O 繁忙时触发 `check(preferences.commit())` 强断言失败。 |
| **问题 2** | 气泡常驻“等待 Agent 接收” | Agent 适配器层 / App 状态控制器 | `runtime.py:863, 1070`<br>`WorkbenchController.kt:3688`<br>`MessageTimeline.kt:787` | Adapter 在执行期间未上报 `delivered` 且延后至完成时补发；同时 App 处理 assistant 流式 chunk 时未联动流转 prompt 状态，UI 条件优先匹配 QUEUED 状态标签。 |
| **问题 3** | 回复卡顿无法流式输出 | 客户端加密存储流 / 协程 Channel 缓冲 | `EncryptedConversationMirror.kt:839`<br>`CoreAgentConversationRepository.kt:259` | 客户端在事件流中对每个增量事件同步执行加密数据库事务与 `flush()` 导致背压严重；配合 `Channel.CONFLATED` 丢弃了大量中间增量帧，造成长停顿后突变跳字。 |
