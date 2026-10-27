# Review 审查报告：OAI Core 重构后会话系统全链路回归缺陷深度分析

**文档版本**：v1.0
**日期**：2026-10-27
**审查范围**：Android 客户端 UI、数据仓储层（`CoreAgentConversationRepository`）、网络层（`AgentSessionClient`）、OAI Core 路由与协议（`sessions.py`）、Hermes Session Adapter（`oai_hermes_adapter`）及底座交互链路
**关联 Bug**：Bug 01 ~ Bug 08

---

## 1. 概述与总体架构问题

在近期 OAI Core 统一架构重构中，系统为支持多 Agent（如 Hermes、Codex 等）的原生会话接入，引入了全新的轻量化 `/open-android-intelligence/v2/agents/{agent_id}/sessions` 系列接口，并在 Android 端启用了 `nativeAgentSessions = true`。

然而，Android 客户端原有的工作台控制器（`WorkbenchController`）是面向具备复杂组织架构、多模态附件、加密持久化及精细状态机的完整 Gateway 协议设计的。新增的桥接仓储层 `CoreAgentConversationRepository` 与底层原生接口之间产生了多处严重的协议降级、状态机失配与数据模型断层：

1. **协议降级与附件丢弃**：会话接口被裁剪为纯文本流，未定义附件数据结构，导致多模态功能全面断裂。
2. **组织架构能力未适配**：会话文件夹（Folders）与自定义排序（Custom Order）在 Core 协议端完全缺失，客户端仓储未实现对应 CAS 接口导致异常中断。
3. **消息身份与时钟失步**：本地流式临时自造 ID 与远端持久化真实 ID 脱节，且去重规则遗漏前缀；时间戳解析异常导致时间线排序退化倒错。
4. **背压与异常统配粗暴**：事件流 `MutableSharedFlow` 默认挂起引发网络层 SSE 读取阻塞；非网络异常统一映射为 `AGENT_UNAVAILABLE`，掩盖真实故障。

---

## 2. 8 个 Bug 根因矩阵

| 编号 | Bug 现象 | 故障层级 | 根因或主要嫌疑 | 确定程度 | 代码直接证据（文件及行号） |
| :---: | :--- | :--- | :--- | :---: | :--- |
| **01** | **流式传输卡住**<br>(中途停滞，切换会话后才显示完整) | Android 仓储层与网络层 | `_events` 的 `MutableSharedFlow` 采用默认挂起策略且容量仅 64，高频 token 导致 UI 消费背压挂起 `_events.emit`，反向死锁 `AgentSessionClient` 的 SSE 读取循环。 | **已确认** | `CoreAgentConversationRepository.kt:90-94, 270-275`<br>`AgentSessionClient.kt:136-170` |
| **02** | **重复回复**<br>(切换会话后同一回复出现两次) | Android 客户端状态合并与去重 | 流式阶段本地随机生成 `asst_${UUID}` 作为消息 ID，与服务端历史返回的真实 ID 脱节；且 `normalizeEntryKey` 遗漏 `asst_` 前缀，去重修剪失效，两套 ID 独立并存。 | **已确认** | `CoreAgentConversationRepository.kt:233, 244-246, 281-285`<br>`WorkbenchController.kt:3982-4038, 4172-4177` |
| **03** | **消息乱序**<br>(切换会话后消息顺序发生颠倒) | Android 仓储层 & UI 排序机制 | `parseDate` 强假定 ISO 8601 且截断毫秒，面对 Hermes 返回的浮点数/数字时间戳解析抛异常，全部回退为当前时间；相同时间戳导致比较器退化并跨轮次错乱。 | **已确认** | `CoreAgentConversationRepository.kt:426-436`<br>`hermes_adapter/sessions.py:114-116`<br>`WorkbenchController.kt:1470-1472, 4130-4138` |
| **04** | **消息类型错误**<br>(正常回复被当作思考或执行步骤) | Android 客户端 UI 状态机与解析 | 因 Bug 03 导致跨轮次 Assistant 消息相邻，`TimelineDisplayModel` 贪婪聚合将其作为单次执行段，非末尾的正常文本回答被错误降级为 `ExecutionStep.Narrative` 塞入折叠面板。 | **已确认** | `TimelineDisplay.kt:150-158, 369-371, 207-238`<br>`MarkdownParser.kt:37-46` |
| **05** | **新建文件夹失效**<br>(点击新建文件夹无反应/报错) | Android 客户端仓储层 & OAI Core | `CoreAgentConversationRepository` 未覆写 `updateOrganization`，直接抛出 `CONVERSATION_MANAGEMENT_UNSUPPORTED`；且 Core 完全未提供 organization 接口。 | **已确认** | `CoreAgentConversationRepository.kt:390-392`<br>`ConversationPorts.kt:402-403`<br>`oai_core/server.py:130-131` |
| **06** | **自定义排序失效**<br>(会话排序设置无实际效果) | Android 客户端仓储层 & OAI Core | 会话排序与文件夹同样依赖 `commitOrganizationChange`，在调用未覆写的 `repository.updateOrganization` 时触发未受支持异常，本地修改无法提交。 | **已确认** | `CoreAgentConversationRepository.kt:390-392`<br>`WorkbenchController.kt:1016-1039` |
| **07** | **删除会话失效**<br>(删除按钮无法点击或操作不生效) | UI 能力声明门禁与 Hermes Adapter | 1. 客户端 `CoreAgentConversationRepository.deleteConversation` 包含 `if (!agent.capabilities.delete) return`，缺少能力时静默吞掉操作；2. UI 盲目声明受支持，而 Hermes Adapter 仅在具备特定 native 历史与 `conversation_manager` 时才开放 `delete`。 | **高概率** | `CoreAgentConversationRepository.kt:408-420`<br>`GatewayRuntime.kt:1420`<br>`hermes_adapter/sessions.py:29-33` |
| **08** | **图片与附件发送失败**<br>(显示 Agent 暂不可用，本次生成失败) | 全链路：协议层、网络层、仓储层与 Adapter | 1. `CoreAgentConversationRepository` 只把 `message.text` 传给底层，完全丢弃了 `attachmentIds`；2. 纯附件时因空文本被 `require(text.isNotBlank())` 拦截；3. 异常被统配为 `AGENT_UNAVAILABLE`。 | **已确认** | `CoreAgentConversationRepository.kt:212-213, 258, 290-300`<br>`AgentSessionClient.kt:114-120`<br>`oai_core/sessions.py:539-549`<br>`hermes_adapter/sessions.py:178` |

---

## 3. 逐项技术分析与代码证据

### 3.1 Bug 01：流式传输卡住
- **完整调用链路**：
  `Hermes Agent SSE` $\rightarrow$ `oai_hermes_adapter/sessions.py:send()` $\rightarrow$ `oai_core/sessions.py:send()` $\rightarrow$ `AgentSessionClient.kt:send()` (读取网络流 `stream.read(buffer)`) $\rightarrow$ `onDelta 回调` $\rightarrow$ `CoreAgentConversationRepository.kt:publish("STREAMING")` $\rightarrow$ `emitEvent()` $\rightarrow$ `_events.emit()` $\rightarrow$ `WorkbenchController.kt:observeThreadEvents()` $\rightarrow$ Compose UI 重绘。
- **异常逻辑与代码证据**：
  1. `CoreAgentConversationRepository.kt:90-94`：
     ```kotlin
     private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
     private suspend fun emitEvent(agentId: String, event: VerifiedConversationEvent) {
         _events.emit(AgentEvent(agentId, event))
     }
     ```
     `MutableSharedFlow` 默认溢出策略为 `BufferOverflow.SUSPEND`。
  2. `CoreAgentConversationRepository.kt:270-275`：
     ```kotlin
     } { delta ->
         if (!turn.cancelled) {
             text.append(delta)
             publish("STREAMING")
         }
     }
     ```
     在高频吐字场景下，每个 delta 触发一次挂起级 emit。UI 消费端因执行 Markdown 复杂解析与列表重绘若稍有延迟，64 个缓冲区 slot 瞬间被打满，导致 `_events.emit` 挂起。
  3. `onDelta` 被挂起导致 `AgentSessionClient.kt:136-170` 的 SSE 循环在调用 `onDelta` 时阻塞，底层 `stream.read(buffer)` 停止读取网络字节。UI 侧流式停止刷新，表现为中途卡住。
  4. 切换会话时，`openThread` 调用 `repository.completeTimeline(threadId)` 走独立的 HTTP GET 重新拉取服务端完整历史，因此重新进入后显示完整内容。

---

### 3.2 Bug 02：重复回复
- **完整调用链路**：
  `CoreAgentConversationRepository.kt:submitMessage()` 生成本地 `assistantMsgId` $\rightarrow$ 分发流式及完成消息 $\rightarrow$ `WorkbenchController.mirrored` 记录该消息 $\rightarrow$ 切换会话再切回 $\rightarrow$ `openThread()` 触发 `timeline()` 拉取远端历史 $\rightarrow$ `pruneConfirmedAssistantDuplicates()` 执行去重修剪 $\rightarrow$ 界面渲染。
- **异常逻辑与代码证据**：
  1. `CoreAgentConversationRepository.kt:233, 244-246`：
     ```kotlin
     val assistantMsgId = "asst_${UUID.randomUUID()}"
     ...
     message = TimelineMessage(id = assistantMsgId, sender = "assistant", ..., state = state)
     ```
     流式及结束时均使用自造的随机 `asst_${UUID}` 作为消息 ID。而服务端返回的历史消息 ID 为真实原生 ID（如 `msg_...` 或整数）。
  2. `WorkbenchController.kt:4172-4177`：
     ```kotlin
     private fun normalizeEntryKey(key: String): String =
         key.removePrefix("local_")
             .removePrefix("stream_")
             .removePrefix("msg_")
             .removePrefix("cconv_")
     ```
     `normalizeEntryKey` 未包含 `"asst_"` 前缀，导致 `normalizeEntryKey("asst_...") != normalizeEntryKey("msg_...")`。
  3. `pruneConfirmedAssistantDuplicates`（`WorkbenchController.kt:3982-4028`）第二重时间戳比对因时钟差及毫秒截断失效，最终本地自造的 `asst_...` 完成态与远端拉回的 `msg_...` 记录被判定为两条独立消息同时展示。

---

### 3.3 Bug 03：消息乱序
- **完整调用链路**：
  `Hermes DB / Adapter` $\rightarrow$ `Core /history` $\rightarrow$ `AgentSessionClient.history()` $\rightarrow$ `CoreAgentConversationRepository.timeline()` $\rightarrow$ `parseDate()` $\rightarrow$ `WorkbenchController.reloadTimeline() / renderTimeline()` 排序 $\rightarrow$ UI 列表展现。
- **异常逻辑与代码证据**：
  1. `CoreAgentConversationRepository.kt:426-436`：
     ```kotlin
     private fun parseDate(iso: String?): Long {
         if (iso.isNullOrBlank()) return System.currentTimeMillis()
         return try {
             val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                 timeZone = TimeZone.getTimeZone("UTC")
             }
             format.parse(iso.substringBefore('.'))?.time ?: System.currentTimeMillis()
         } catch (_: Exception) {
             System.currentTimeMillis()
         }
     }
     ```
     - 截断 `.substringBefore('.')` 丢弃了 ISO 8601 毫秒部分。
     - Hermes Session Adapter (`sessions.py:114-116`) 中的时间戳来自 SQLite 表的数字浮点数或秒级时间戳（非 ISO 8601 字符串），导致 `format.parse` 抛出 `ParseException`，走入 `catch` 块返回 `System.currentTimeMillis()`。所有历史记录在拉取瞬间被全部赋值为相同的当前时间。
  2. `WorkbenchController.kt:1470-1472` 与 `4130-4138`：
     ```kotlin
     page.messages.sortedWith(
         compareBy<TimelineMessage> { it.timestamp }.thenBy { if (it.sender == "user") 0 else 1 }
     )
     ```
     当 `timestamp` 全相等时，排序完全退化为依据 `sender == "user"`（User 为 0，Assistant 为 1），导致不同轮次的历史消息错乱堆叠（全部用户提问在上，全部助手回答在下）。

---

### 3.4 Bug 04：消息类型错误
- **完整调用链路**：
  `WorkbenchController.renderTimeline()` $\rightarrow$ `TimelineDisplay.kt:build()` 聚合执行块 $\rightarrow$ `buildRun()` $\rightarrow$ `ExecutionProcessPanel.kt` 渲染折叠卡片。
- **异常逻辑与代码证据**：
  1. `TimelineDisplay.kt:150-158, 369-371`：
     ```kotlin
     private fun isExecutionEligible(entry: TimelineEntry): Boolean =
         !entry.isUser && entry.approval == null && entry.systemThreadId == null
     ```
     判定所有非用户消息均属于同一执行段（Segment），直到遇到用户消息才打断。
  2. 因 Bug 03 造成消息乱序，跨轮次的多条 Assistant 消息相连排列；
  3. `TimelineDisplay.kt:207-238` 中的 `buildRun()` 将段内非末尾条目的所有 Markdown 块一律转换为 `ExecutionStep.Narrative`（叙述步骤），最终塞入“执行过程 · N 步 · 点击收起”折叠面板内；导致正常的长文本回复被包裹在执行步骤中。
  4. Hermes 实时输出的日志行（如 `Reading config.yaml...`）被 `MarkdownParser.kt:37-46` 正则识别为工具调用卡，未完成时尾随渲染 `StreamingCursor`（即“正在输出...”）。

---

### 3.5 Bug 05：新建文件夹失效 & Bug 06：自定义排序失效
- **完整调用链路**：
  UI 点击新建文件夹/调整排序 $\rightarrow$ `ConversationBrowser.kt:488 / 330` $\rightarrow$ `WorkbenchController.createFolder() / setThreadSortMode()` $\rightarrow$ `changeOrganization()` $\rightarrow$ `commitOrganizationChange()` $\rightarrow$ `repository.updateOrganization()`。
- **异常逻辑与代码证据**：
  1. `CoreAgentConversationRepository.kt:390-392`：
     ```kotlin
     override suspend fun readOrganization(): ConversationOrganization {
         return ConversationOrganization()
     }
     ```
     硬编码返回空配置，且**未覆写 `updateOrganization` 方法**。
  2. 接口默认实现（`ConversationPorts.kt:402-403`）：
     ```kotlin
     suspend fun updateOrganization(expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization =
         error("CONVERSATION_MANAGEMENT_UNSUPPORTED")
     ```
     导致每次调用必抛出 `CONVERSATION_MANAGEMENT_UNSUPPORTED` 异常，随后被 Controller 捕获并提示操作失败。
  3. `oai-core/src/oai_core/server.py:130-131` 显式拦截并禁用了 `/conversation-organization` 端点（抛出 `CAPABILITY_UNSUPPORTED`），表明 Core 协议层尚未为原生 Agent 会话提供组织管理支持。

---

### 3.6 Bug 07：删除会话失效
- **完整调用链路**：
  UI 点击删除对话 $\rightarrow$ `ConversationBrowser.kt:599-600` $\rightarrow$ 弹出确认对话框 $\rightarrow$ `WorkbenchController.deleteThread()` $\rightarrow$ `CoreAgentConversationRepository.deleteConversation()`。
- **异常逻辑与代码证据**：
  1. `CoreAgentConversationRepository.kt:408-412`：
     ```kotlin
     override suspend fun deleteConversation(conversationId: String) {
         val agent = requireAgent()
         if (!agent.capabilities.delete || !agent.online) return
         withContext(ioDispatcher) { client.delete(agent, conversationId) }
     ```
     当 Agent 声明的 capabilities 中 `delete == false` 时，函数直接静默返回。
  2. `GatewayRuntime.kt:1420`：直连模式下无条件为 UI 设置了 `supportsConversationManagement = true`，导致 UI 菜单中的“删除对话”保持可用。
  3. `hermes-session-adapter/sessions.py:29-33`：远程适配模式下，默认 capabilities 中 `delete` 为 `False`。
  4. 用户点击删除后，控制器乐观移除了本地项，但紧接着在 `finally` 中触发 `refreshThreads()` 重新拉取；由于后端从未执行删除，会话重新出现。

---

### 3.7 Bug 08：图片与附件发送失败
- **完整调用链路**：
  用户选择图片/文档 $\rightarrow$ `AttachmentCoordinator` 分块上传并校验成功 $\rightarrow$ 构造带有附件的 `OutgoingMessage(text, attachmentIds)` $\rightarrow$ `CoreAgentConversationRepository.submitMessage()` $\rightarrow$ 丢弃附件 $\rightarrow$ `AgentSessionClient.send(text)` 拦截空文本 $\rightarrow$ 抛异常 $\rightarrow$ 统配为 `AGENT_UNAVAILABLE` $\rightarrow$ UI 显示“Agent 处理失败：Agent 暂不可用”。
- **异常逻辑与代码证据**：
  1. **仓储层丢弃附件**：`CoreAgentConversationRepository.kt:258`：
     ```kotlin
     val result = client.send(agent, conversationId, message.text, onEvent = { ... })
     ```
     向底层传递时只取了 `message.text`，`message.attachmentIds` 被抛弃；`submitBatch`（第 212-213 行）同样未传递附件。
  2. **网络层签名缺陷与空文本拦截**：`AgentSessionClient.kt:114-120`：
     ```kotlin
     suspend fun send(agent: CoreAgent, sessionId: String, text: String, ...): NativeGenerationResult {
         requireCapability(...)
         require(text.isNotBlank()) // 纯发图/文档时 text 为空，在此直接抛 IllegalArgumentException！
         val request = jsonRequest("POST", sessionTarget(agent, sessionId) + "/messages", mapOf("text" to text))
     ```
     请求模型无 `attachmentIds` 字段；纯发图/文件时因 `text.isNotBlank()` 强校验在客户端直接抛异常崩溃。
  3. **Core 端路由协议阻断**：`oai-core/src/oai_core/sessions.py:546-549`：
     ```python
     body = await body_for(request, {"text"})
     text = body.get("text")
     if not isinstance(text, str) or not text.strip() or len(text) > 262144:
         raise AdapterError("INVALID_MESSAGE")
     ```
     Core 原生消息接口只允许 `text` 字段，且强制要求非空。
  4. **错误信息被统配掩盖**：`CoreAgentConversationRepository.kt:290-300`：
     除 `OUTCOME_UNKNOWN` 之外，所有本地参数不符、400 校验失败或接口不支持的异常，全被映射为 `AGENT_UNAVAILABLE`，导致 UI（`MessageTimeline.kt:250`）在图片和 docx 附件下均死板显示“Agent 处理失败：Agent 暂不可用”且底部提示“本次生成失败”。
