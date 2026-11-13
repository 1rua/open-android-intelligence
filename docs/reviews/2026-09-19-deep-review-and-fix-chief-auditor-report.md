# Phase 2 首席审计员（Chief Auditor）仲裁与审查聚合报告

**审计员**：Chief Auditor  
**接收人**：Parent Orchestrator  
**审计基准与依据**：
- 契约总纲：[gateway-protocol-v2.md](file:///mnt/数据/项目/open-android-intelligence/docs/contracts/gateway-protocol-v2.md)
- 架构总览：[2026-08-24-modular-plugin-architecture.md](file:///mnt/数据/项目/open-android-intelligence/docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md)
- 仲裁优先级铁律：`功能正确性与契约规范 (Correctness & Spec) > 运行与并发安全 (Safety) > 代码规范与可读性 (Standards & Readability) > 微观性能 (Micro-perf)`
- 5 位专业审查员输入：Worker 1 (复用)、Worker 2 (质量)、Worker 3 (性能/安全)、Worker 4 (规范/异味)、Worker 5 (契约/协议)

---

## 一、执行摘要与仲裁综述 (Executive Summary & Arbitration Overview)

### 1.1 审查汇总与数据总览
本次审计对 5 位专业 Worker 提交的全部审查成果进行了全量去重、跨维度冲突仲裁、风险定级与文件级聚类：
- **原始发现总数**：58 项
- **去重合并后有效缺陷**：29 项
- **多方共识核心重大缺陷**：12 项（涉及 2 位及以上审查员独立捕获的同根因缺陷）
- **风险分级裁决分布**：
  - **`RISKY`（人工门禁区）**：3 项（涉及协议 Schema 演进、跨子系统队列数据契约改造与广播丢弃策略变更）
  - **`CAREFUL`（高/中风险修复区）**：14 项（涉及连接死循环自旋、永久降级死锁、并发竞态、状态逆向覆盖、RFC 6455 状态机漏洞等，需完备单元测试验证）
  - **`SAFE`（低风险局部清理区）**：12 项（涉及假实现清除、死代码/死导入、公共签名复用、工具类复用、O(N^2) 内存拷贝优化、命名规范等）
- **涉及核心文件**：8 个（覆盖 Android 客户端 `gateway-client`、`conversation-ui`、`conversation-data`、`conversation-domain` 及 Hermes 服务端 `adapter.py`）

### 1.2 仲裁核心结论与冲突裁决判定
1. **关于远端正常断开（EOF/Clean Close）的处理判定 [Correctness & Safety > Readability]**：
   Worker 2 与 Worker 3 一致指出：当服务端正常关闭流时，Flow 正常完结而不抛异常，由于外层循环未标记 `streamFailed`，导致以 0ms 零退避陷入死循环自旋，且破坏了 SSE 降级回退通道。**首席裁决**：采纳 Worker 2/3 的意见，判定为最高优先级 `CAREFUL` 缺陷。必须将非客户端主动取消的流结束（无论 EOF 还是远程 Close）显式视为连接断开，施加退避延迟，并触发双通道降级状态机。
2. **关于 WebSocket 默认关闭 `Sec-WebSocket-Accept` 校验的判定 [Spec & Correctness > Standards]**：
   Worker 2、4、5 均捕获到生产代码中 `verifyAcceptHeader` 默认置为 `false`（旨在迁就简易 Mock 测试）。**首席裁决**：依据契约规范优先原则，RFC 6455 的 Handshake 校验是防范中间人协议降级和脏响应的核心防线，生产环境必须默认开启 `verifyAcceptHeader = true` 并补齐 `Connection: Upgrade` 校验；测试代码若使用简易 Mock，必须在测试桩中生成标准 Accept 响应或显式在测试配置中覆写，严禁生产代码为迁就测试而降级安全契约。
3. **关于 `DebounceBatcher.close()` 空壳扩展的判定 [Standards & Correctness > Micro-perf]**：
   Worker 1、2、4 均指出 `(this as? AutoCloseable)?.close()` 为假实现。**首席裁决**：严格执行项目规范“严禁伪造实现与死状态”，将 `DebounceBatcher` 在领域层正式实现 `AutoCloseable`，提供真实的内部 `Job` 取消与队列清理逻辑，彻底杜绝虚假扩展函数。
4. **关于 `adapter.py` WebSocket 逆向反解 SSE 纯文本管道的判定 [Spec & Safety > Micro-perf]**：
   Worker 1、2、3、4 均指责网关将 SSE 格式文本放入广播队列，WS 消费端再用正则/切片逆向反解为 JSON。**首席裁决**：该反向适配严重违反架构分层与性能规范。但由于直接改变队列对象类型涉及 Hermes 核心服务广播与插件契约，将其核心协议调整定级为 `RISKY`（需人类复核确认数据契约），而在当前代码中立即对文本提取与反序列化逻辑做缓存与安全防线加固（定级为 `CAREFUL`）。

---

## 二、多方共识核心重大缺陷 (Multi-Agent Consensus High-Impact Issues)

### 缺陷 1：远端正常断开时 Flow 正常结束未标记失败，导致 0ms 零退避死循环自旋与 SSE 回退机制破坏 `[多方共识: Worker 2 & Worker 3]`
- **目标代码**：[GatewayHttpClient.kt:111-125, 170-193](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L111-L125)
- **缺陷根因**：
  当远端服务端正常关闭 WebSocket（发送 CLOSE 帧或 Clean EOF）时，`webSocketTransport.events(cursor).collect { ... }` 正常结束，不抛出异常。外层局部变量 `streamFailed` 仍为 `false`。循环继续向下执行：
  ```kotlin
  if (streamFailed) {
      delayFn(backoffMillis)
      ...
  } else {
      backoffMillis = 1000L
      kotlinx.coroutines.yield() // 0ms 立即让出CPU并继续下一轮循环！
  }
  ```
  如果服务端主动断开连接，客户端将在 0ms 内瞬间连续重连，形成高频 CPU 死循环自旋（Spin-loop），迅速打爆网络日志和服务器。更严重的是，因为 `streamFailed == false`，条件 `(!preferWebSocket || (!receivedAnyEventInAttempt && streamFailed))` 永远不满足，客户端**永远不会降级至 SSE 备用流**！
- **修复方案**：
  将流正常结束但外层协程未取消的情况显式判定为连接中断（`val endedCleanly = !currentCoroutineContext().isActive`），若外层仍处于 active 状态，必须将 `streamFailed` 标记为 `true` 并触发退避延迟及 SSE 回退逻辑。

---

### 缺陷 2：WebSocket 缺乏读取超时（`socket.soTimeout = 0`），导致网络假死与永久挂死 `[四方共识: Worker 2, Worker 3, Worker 4, Worker 5]`
- **目标代码**：[GatewayWebSocketTransport.kt:86](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L86)
- **缺陷根因**：
  代码显式配置了 `socket.soTimeout = 0`。在移动蜂窝网络或 WiFi 弱网环境下，当基站切换、NAT 会话超时或 TCP 连接发生半开（Half-Open）断开时，底层 `InputStream.read()` 将无限制永久阻塞。由于缺乏读取超时与心跳看门狗检测，客户端永远无法察觉远端掉线，重连机制无法被激活，时间线彻底停止更新，应用陷入死锁假死。
- **修复方案**：
  配置合理的读取超时 `socket.soTimeout = READ_TIMEOUT_MILLIS`（例如 30,000ms - 45,000ms，配合服务端的 15s PING 心跳）；在 `readFrame` 中捕获 `SocketTimeoutException` 并转换为协议掉线异常抛出，促使上层触发自动重连。

---

### 缺陷 3：生产环境默认关闭 RFC 6455 `Sec-WebSocket-Accept` 握手签名校验，且漏检 Connection 标头 `[多方共识: Worker 2, Worker 4, Worker 5]`
- **目标代码**：[GatewayWebSocketTransport.kt:50, 228-238](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L50) 与 [GatewayHttpClient.kt:69](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L69)
- **缺陷根因**：
  `GatewayWebSocketTransport` 构造参数硬编码 `verifyAcceptHeader: Boolean = false`，且在 `GatewayHttpClient` 初始化时未传入 `true`。这意味着客户端完全放弃了对 RFC 6455 核心握手凭证（`Sec-WebSocket-Accept`）的校验，中间人代理或非法端点返回任意 101 均被无条件信任。此外，即便开启校验，代码也漏检了 RFC 6455 强制要求的 `Connection: Upgrade`（包含 Upgrade 关键字）。
- **修复方案**：
  将 `verifyAcceptHeader` 默认值设为 `true`，在握手验证逻辑中增加对 `headers["connection"]?.contains("upgrade", ignoreCase = true) == true` 的严格校验，消除安全规避。

---

### 缺陷 4：假实现与死状态：`DebounceBatcher.close()` 空壳扩展导致批处理消息悬挂与资源泄漏 `[多方共识: Worker 1, Worker 2, Worker 4]`
- **目标代码**：[WorkbenchController.kt:765-767](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L765-L767) 对比 [DebounceBatcher.kt:16-51](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/batch/DebounceBatcher.kt#L16-L51)
- **缺陷根因**：
  `WorkbenchController` 底部编写了私有扩展：
  ```kotlin
  private fun DebounceBatcher.close() {
      (this as? AutoCloseable)?.close()
  }
  ```
  然而在领域模块中，`DebounceBatcher` 是一个普通类，根本**未实现** `AutoCloseable` 或 `Closeable` 接口！该转型恒为 `null`，执行完全为空操作。当 Controller 调用 `close()` 时，`DebounceBatcher` 内部活跃的 `activeJobs`（协程任务）与 `activeBatches`（待发送消息）从未被取消或释放，违背项目铁律“严禁伪造实现与死状态”。
- **修复方案**：
  在 `conversation-domain` 模块中让 `DebounceBatcher` 正式实现 `AutoCloseable`，在 `close()` 中遍历取消所有 `activeJobs` 并清空 `activeBatches`；在 `WorkbenchController` 中彻底删除该欺骗性扩展函数。

---

### 缺陷 5：WebSocket 传输层永久粘滞降级（Sticky Downgrade Bug）导致偶发网络抖动后无法自愈恢复 `[多方共识: Worker 2, Worker 3]`
- **目标代码**：[GatewayHttpClient.kt:121-125, 170-172](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L121-L125)
- **缺陷根因**：
  在断线降级逻辑中：
  ```kotlin
  } catch (e: Throwable) {
      streamFailed = true
      if (!receivedWsEventInAttempt) {
          preferWebSocket = false
      }
  }
  ...
  if (receivedWsEventInAttempt) {
      preferWebSocket = (webSocketTransport != null)
  }
  ```
  如果 WebSocket 连接刚刚建立，尚未收到任何新消息事件（`receivedWsEventInAttempt == false`，空闲连接正常现象），此时遭遇了瞬时网络波动或服务端常规心跳重置，异常捕获后 `preferWebSocket` 立即被置为 `false`。由于 `receivedWsEventInAttempt` 为 false，后续再也没有任何代码能将其置回 `true`！导致 WebSocket 通道永久死锁降级为 SSE，即使网络完全恢复，客户端也再也不会尝试 WebSocket 连接。
- **修复方案**：
  引入自愈尝试机制：在降级为 SSE 后，记录连续成功运行时间或在退避周期重置时（或周期性指数探索）重新将 `preferWebSocket` 探测恢复为 `true`；区分“服务端明确不支持 WS（404/Bad Gateway）”与“网络 I/O 抖动断连”，仅在前者时执行长效降级。

---

### 缺陷 6：WebSocket 传输反向逆向解析 SSE 纯文本的管道扭曲（Layering Violation）与双重解析低效 `[四方共识: Worker 1, Worker 2, Worker 3, Worker 4]`
- **目标代码**：[adapter.py:480-559, 1197-1215, 1278-1292](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L480-L559) 与 [GatewayWebSocketTransport.kt:382-417](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L382-L417)
- **缺陷根因**：
  Hermes 适配器在 `_broadcast_sse` 中将所有事件统一打包为 SSE 格式纯文本字节（`id: ...\nevent: ...\ndata: ...\n\n`）推入广播队列。然而在 WebSocket 的推送循环中，服务端又通过 `_ws_message_from_queue_item` 对出队的 SSE 纯文本进行逐行字符串扫描，重新组装为 JSON 文本帧发给客户端。客户端收到后在 `parseWebSocketEvent` 中又做一次 JSON 解析与 SSE 回退。整个管道架构倒置，带来了大量的 CPU 文本切分、双重反序列化和无谓的内存垃圾分配。
- **修复方案**：
  （详见 RISKY 门禁与 CAREFUL 修复条目）：在事件入队时保留原始结构化事件字典，广播队列分发结构化数据；流式写出层分别按 SSE 格式和 WS JSON 格式序列化；加固 `_extract_event_id_from_item`，对解析结果做单次透传。

---

### 缺陷 7：WebSocket 握手鉴权 9 项标头及签名逻辑完全硬编码手写复制 `[多方共识: Worker 1, Worker 4]`
- **目标代码**：[GatewayWebSocketTransport.kt:162-205](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L162-L205) 对比 [GatewayHttpClient.kt:196-234](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L196-L234)
- **缺陷根因**：
  `GatewayWebSocketTransport` 内部完整复制了 `GatewayHttpClient` 中的 9 个鉴权请求头（`Authorization`、`X-Open-Android-Intelligence-Protocol`、`Account`、`Device`、`Session`、`Request-Id`、`Timestamp`、`Nonce`、`Signature`）以及 `newRequestId`、`newNonce`、`formatTimestamp` 等签名准备逻辑。一旦协议增加或调整鉴权标头，极易导致两处代码修改脱节，产生散弹式修改风险。
- **修复方案**：
  将通用的请求签名与鉴权标头生成逻辑收敛至 `GatewayHttpClient` 或独立的内部共享签名助手（如 `GatewayRequestSigner.buildAuthHeaders(...)`），`GatewayWebSocketTransport` 直接复用。

---

### 缺陷 8：历史消息加载与实时事件流竞态导致 live 消息被 `clear()` 冲刷清空 `[多方共识: Worker 3, Worker 4]`
- **目标代码**：[WorkbenchController.kt:215-217, 683-685](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L215-L217) 及 [440-454](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L440-L454)
- **缺陷根因**：
  在 `openThread` 和 `reloadTimeline` 中，当异步调用 `repository.timeline(threadId, ...)` 完成返回后，无条件执行了：
  ```kotlin
  mirrored.clear()
  mirroredRevisions.clear()
  page.messages.forEach { mirrored[it.id] = it }
  ```
  如果用户刚进入会话，实时事件流（WebSocket/SSE）已经接收并渲染了服务端推送的最新的 live 消息（例如助手正在 streaming 生成的消息），此时慢速的 HTTP 历史记录请求返回，直接粗暴清空 `mirrored`，导致所有 live 消息瞬间被抹去消失！
  同理，在消息发送成功后的回调中，行 454 将 `mirroredRevisions[acceptance.messageId] = 0L` 无条件置为 0，若实时流在此之前已到达并推进了 revision，这一操作会逆向冲刷 revision，导致后续更新失效。
- **修复方案**：
  严禁对 `mirrored` 执行全局暴力清空；采用增量合并策略（Merge），根据消息 `id` 与 `revision` 进行双向校验，保留更高的 revision，防止 live 消息被覆写丢弃。

---

### 缺陷 9：RFC 6455 协议多处缺陷（分片状态机漏洞、无上限重组缓冲区致 OOM 隐患、错误容忍服务端掩码） `[多方共识: Worker 3, Worker 5]`
- **目标代码**：[GatewayWebSocketTransport.kt:102, 126-148, 252, 274-287](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L126-L148)
- **缺陷根因**：
  1. **分片状态机容错错误**：行 138 `if (currentOpcode != -1)` 在遇到孤立的 Continuation 帧时静默忽略，未按 RFC 6455 §5.4 规定将其判定为协议错误关闭连接；遇到新数据帧但未完结时直接 `messageBuffer.reset()` 冲掉旧分片，无任何协议报错。
  2. **累加缓冲区无上限**：行 102 `val messageBuffer = ByteArrayOutputStream()`，在跨帧分片累加过程中没有对累积大小做 `MAX_PAYLOAD_BYTES` 上限检查，恶意或异常大数据包将导致客户端内存暴涨并触发 OOM Crash。
  3. **错误容忍服务端掩码**：RFC 6455 §5.1 明确规定服务端发送给客户端的帧**绝不得包含掩码**（Masked bit 必须为 0，若收到客户端必须立即断开连接），但代码在行 274-287 竟然自动为服务端帧解掩码并正常放行！
- **修复方案**：
  严格对齐 RFC 6455 规范：若 `b1 and 0x80 != 0` 直接抛出 `ProtocolException("Masked frame received from server")`；在分片累加过程中增加累加长度检查；对非法的分片序列直接抛出协议异常并断开。

---

### 缺陷 10：`SseParser.drain()` 内部重复调用 `toByteArray()` 产生 O(N^2) 内存分配与缓冲拷贝开销 `[多方共识: Worker 2, Worker 3]`
- **目标代码**：[SseParser.kt:66-75](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt#L66-L75)
- **缺陷根因**：
  在 `drain()` 的 `while (true)` 循环中，每次解析一帧事件，都会调用一次 `val buffered = buffer.toByteArray()` 全量克隆整个缓冲区，并在消耗部分数据后执行 `buffer.reset(); buffer.write(buffered, consumed, ...)`。当一次网络包中包含多条事件时（如网络恢复后的批量 Backlog 重放），该循环会触发 N 次整块内存重新分配和内存移动，导致 O(N^2) 复杂度的内存开销和高频 GC 停顿。
- **修复方案**：
  重构为单次缓冲区扫描或基于滑动窗口/RingBuffer 读取，避免在单次 `drain()` 循环内反复 `toByteArray()` 和反复拷贝。

---

### 缺陷 11：拷贝粘贴代码同义反复死代码 `sslSocket.sslParameters ?: sslSocket.sslParameters` `[多方共识: Worker 2, Worker 4]`
- **目标代码**：[GatewayWebSocketTransport.kt:77](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L77)
- **缺陷根因**：
  代码中存在明显的拷贝粘贴残留无意义代码：
  ```kotlin
  val params = sslSocket.sslParameters ?: sslSocket.sslParameters
  ```
  `x ?: x` 完全是自指同义反复，如果前者为 null 后者同样为 null。
- **修复方案**：
  修正为 `val params = sslSocket.sslParameters ?: SSLParameters()` 或直接安全调用。

---

### 缺陷 12：未经认证提前注册订阅队列及双重 Ed25519 验签 `[多方共识: Worker 1, Worker 2, Worker 3]`
- **目标代码**：[adapter.py:644-671, 1030-1050, 1131-1150](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L644-L671)
- **缺陷根因**：
  在 SSE 与 WS 的连接处理函数中，代码在尚未调用 `route.event_backlog(raw_req)` 完成身份鉴权前，就先调用 `_peek_account_id`，将未经验证的 `queue` 提前注册到了 `_event_subscribers` 字典中！如果后续鉴权失败（返回 401/403），该队列在异常退出前的一瞬间极有可能收到系统广播的敏感事件数据。此外，`_peek_account_id` 和 `event_backlog` 内部先后执行了两次完全相同的 Ed25519 签名验证和 Session 查询，造成性能严重浪费。
- **修复方案**：
  移除握手前的提前注册逻辑；只有在 `route.event_backlog(raw_req)` 鉴权成功且状态码为 200 后，才将连接队列加入订阅者列表，杜绝鉴权旁路信息泄露与重复验签。

---

## 三、分级裁决清单 (Tiered Adjudication List)

### 3.1 RISKY 门禁审查区（需人类工程师与主 Agent 架构决策）
> **门禁审查说明**：以下 3 项变更涉及协议大版本、跨端契约或跨子系统公共数据模型，禁止自动化修复 Worker 私自改动，必须经过评审裁决：

| 编号 | 涉及文件/模块 | 门禁条目与架构风险阐述 | 裁决建议 |
| :--- | :--- | :--- | :--- |
| **R-01** | `gateway-protocol-v2.md`<br>`2026-08-24-modular-plugin-architecture.md` | **契约文档与架构总规格未同步更新 WebSocket 双通道事件流定义**<br>当前契约文档第 14 行明文断言：*“SSE 是唯一 V1 流式事件通道，不提供等价 WebSocket 通道”*。而代码实现已全链路引入 WebSocket 双通道并作为优先通道。这属于严重的实现脱离契约（Spec Drift）。 | **批准契约升版**：提请批准更新 `gateway-protocol-v2.md` 第 14 行及第 4 节特性协商（`"events": ["sse-cursor-v1", "websocket-cursor-v1"]`），使其合法化。 |
| **R-02** | `adapter.py`<br>Hermes 内部事件总线 | **将广播队列底层数据从 SSE 纯文本字节重构为结构化字典**<br>若将 `_event_subscribers` 队列中的项由 `bytes`（SSE 文本）彻底改为结构化字典，将彻底解决 WebSocket 的逆向解析反向管道问题，但会影响任何依赖原有 byte 流广播的潜在第三方扩展或测试插件。 | **分两步走**：在 Phase 4 暂不变更队列元素原始结构类型，采用 `CAREFUL` 级别的安全缓存与免二次解析；将完全重写队列数据类型列入 Hermes 架构演进后续计划。 |
| **R-03** | `adapter.py:1285-1291` | **广播队列满载时的丢弃策略与连接治理**<br>当前队列满时采用静默丢弃（`put_nowait` 报 `QueueFull` 时仅打 warning），而代码注释却声称“端侧会带游标重放”。如果不主动断开落后严重的慢客户端连接，客户端永远不知道自己丢了消息。然而若主动断开连接，可能在弱网下引发重连风暴。 | **审慎断连**：在慢客户端队列连续满溢超过阈值时，主动关闭连接（CloseCode 1008/1011），迫使客户端使用本地最新游标触发断线恢复重连。 |

---

### 3.2 CAREFUL 修复区（明确 Bug / 运行与并发安全加固，可通过单测闭环）

| 序号 | 目标文件 | 核心问题描述 | 仲裁与修复决策 |
| :--- | :--- | :--- | :--- |
| **C-01** | `GatewayHttpClient.kt` | 远端正常断开（EOF/Close）导致 0ms 零退避死循环自旋与 SSE 降级失效 | 判定为最高危缺陷。将流非取消性结束视同断连，必须设置 `streamFailed = true` 并触发退避与降级分支。 |
| **C-02** | `GatewayHttpClient.kt` | WebSocket 偶发抖动导致的永久粘滞降级（Sticky Downgrade Bug） | 增加恢复机制：仅在握手明确收到非重试协议错误时降级；在网络退避成功恢复或常规轮询时允许自愈尝试。 |
| **C-03** | `GatewayWebSocketTransport.kt` | 生产环境默认关闭 `Sec-WebSocket-Accept` 校验且漏检 `Connection` 标头 | 将构造参数默认值改为 `verifyAcceptHeader: Boolean = true`，并在握手标头中增加 Connection 校验。 |
| **C-04** | `GatewayWebSocketTransport.kt` | `socket.soTimeout = 0` 导致静默掉线、假死与无看门狗保护 | 配置合理的 readTimeout（如 30s），捕获超时转换为 IOException 向上抛出以激活重连。 |
| **C-05** | `GatewayWebSocketTransport.kt` | TLS WebSocket 握手异常时底层 Plain Socket 资源泄漏 | 在建立 SSLSocket 前后用 `runCatching` 确保发生异常时执行 `plainSocket.close()`。 |
| **C-06** | `GatewayWebSocketTransport.kt` | RFC 6455 协议多处缺陷（服务端掩码误放行、控制帧分片与超长未校验、非法分片静默吞没） | 严格遵循 RFC 6455：服务端带掩码直接报协议错误；控制帧不可分片且 payload <= 125；非法分片序列报错。 |
| **C-07** | `GatewayWebSocketTransport.kt` | 分片累加缓冲区 `messageBuffer` 缺乏上限保护，有 OOM 风险 | 在分片写入前校验累加大小不得超过 `MAX_PAYLOAD_BYTES`，超限立即抛出异常。 |
| **C-08** | `GatewayWebSocketTransport.kt` | `readLine()` 无单行长度上限保护，存在 OOM DoS 风险 | 在逐字节累加行内容时，加入单行最大长度检查（如 8192 字节），超限抛出异常。 |
| **C-09** | `WorkbenchController.kt` | `historicalAttachments` 采用 access-order `LinkedHashMap` 导致多协程并发读取下链表指针破坏 | 将其构造第三个参数设为 `false`（保持 insertion-order），避免纯读操作产生结构性链表修改，或施加互斥同步。 |
| **C-10** | `WorkbenchController.kt` | 历史消息加载完成时 `mirrored.clear()` 冲刷掉实时事件流消息的竞态 | 移除 `mirrored.clear()` 暴力清空，改为按消息 ID 和 revision 执行双向合并（Merge）。 |
| **C-11** | `WorkbenchController.kt` | `TimelineUpsert` 乱序状态穿透回退与跨会话空 `conversationId` 隔离缺失 | 仅当 revision 高于已知版本时才更新；空 `conversationId` 且与当前 activeThreadId 不匹配时严格禁止穿透更新。 |
| **C-12** | `WorkbenchController.kt` | 消息发送完成回调中无条件覆盖 `mirrored` 并重置 revision 为 0L 冲掉实时更新 | 在本地消息确认时，检查是否已有来自实时流的更高 revision，避免无条件覆盖。 |
| **C-13** | `WorkbenchController.kt` | `submitBatch` 成功后未暂存镜像导致时间线瞬时空白闪烁 | 在将消息从 `pendingBatch` 移除的同时，同步预存入 `mirrored`，保证渲染连续性。 |
| **C-14** | `adapter.py` | 未经认证提前将队列注册入订阅者列表，存在鉴权绕过与双重验签 | 移除 `_peek_account_id` 提前注册，仅在 `route.event_backlog` 鉴权成功返回 200 后才加入订阅集合。 |

---

### 3.3 SAFE 修复区（局部清理、去冗余、微观性能、代码整洁）

| 序号 | 目标文件 | 局部清理条目 | 修复建议 |
| :--- | :--- | :--- | :--- |
| **S-01** | `DebounceBatcher.kt`<br>`WorkbenchController.kt` | 正式实现 `AutoCloseable` 并移除外层欺骗性扩展函数 | `DebounceBatcher` 实现接口并清理内部 Job；彻底移除 Controller 内的 `close()` 假扩展。 |
| **S-02** | `GatewayWebSocketTransport.kt` | 移除自反冗余语句 `sslSocket.sslParameters ?: sslSocket.sslParameters` | 修复为单次获取或兜底创建新实例。 |
| **S-03** | `GatewayWebSocketTransport.kt` | `parseWebSocketEvent` 手写遍历 JSON 字段未复用 `JsonFields` | 引入同模块 `JsonFields` 工具方法（`string()`, `field()`）进行安全读取。 |
| **S-04** | `GatewayWebSocketTransport.kt`<br>`GatewayHttpClient.kt` | 握手 9 项单例鉴权请求头与签名生成逻辑完全重复 | 将鉴权头生成逻辑提取为公共方法或单例工具，消除重复代码。 |
| **S-05** | `GatewayWebSocketTransport.kt`<br>`GatewayHttpClient.kt` | 常量基路径 `EVENTS_TARGET`、`PROTOCOL_HEADER` 重复定义 | 统一提升至共享常量或 `GatewayEndpoint`。 |
| **S-06** | `GatewayTransport.kt` | 冗余的 `connection.readTimeout = readTimeoutMillis` 重复赋值 | 删除多余赋值语句（`factory.open` 已完成赋值）。 |
| **S-07** | `adapter.py` | 清理死导入 `WSCloseCode` 并统一新旧属性别名 `_active_sse_queues` | 移除未使用的导入；统一收敛属性命名。 |
| **S-08** | `adapter.py` | 方法命名陈旧：`_broadcast_sse` 已承担全通道广播 | 重命名为通道中立的 `_broadcast_event`，同步更新内部 warning 日志。 |
| **S-09** | `adapter.py` | URL query 手工切片导致 URL 编码丢失 | 使用标准 `urllib.parse.parse_qs` / `unquote` 解析 cursor 参数。 |
| **S-10** | `SseParser.kt` | `drain()` 内部重复 `toByteArray()` 产生 O(N^2) 内存拷贝开销 | 避免单次循环重复全量拷贝，优化缓冲区滑动读取。 |
| **S-11** | `SseParser.kt` | `findTerminator` 中 `\r\n` 与 `\n` 双分支嵌套逻辑重复 | 提取统一的行终止符匹配逻辑，精简条件分支。 |
| **S-12** | `GatewayEventDecoder.kt` | `payload === body` 时的无谓重复字段扫描 | 检查当 `payload === body` 时跳过第二次重复查找。 |

---

## 四、按目标文件的聚类修复任务清单 (Clustered Tasks by File)

> 本节为 Phase 4 并行修复 Worker 的直接输入物。所有属于 `SAFE` 和 `CAREFUL` 的修复点已按目标文件聚合，明确了目标行号、问题现象及精准修复指引。

### 4.1 目标文件：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt`
- **任务 C-01 (CAREFUL)**：
  - **行号**：[L111-L125, L180-L193](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L111-L125)
  - **问题**：`webSocketTransport.events(cursor).collect` 遇到远端正常关闭（Clean Close/EOF）时正常返回，未置 `streamFailed = true`，导致 0ms 零退避死循环重试且无法触发 SSE 降级。
  - **精准修复**：在 `collect` 结束后，如果外层 `currentCoroutineContext().isActive` 依然为 true，说明连接并非本地主动取消而是远端提前终止，必须将 `streamFailed` 标记为 `true`；若本轮未收到任何 WS 事件，触发退避并在当前/下一轮尝试 SSE 降级通道。
- **任务 C-02 (CAREFUL)**：
  - **行号**：[L121-L125, L170-L172](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L121-L125)
  - **问题**：WebSocket 空闲连接在遭遇偶发网络波动断开时，因 `!receivedWsEventInAttempt` 导致 `preferWebSocket = false` 且永远无法重置，发生永久粘滞降级（Sticky Downgrade Bug）。
  - **精准修复**：优化降级策略，仅在收到明确不支持 WS 的协议级错误（如 HTTP 404 或非法 Upgrade 响应）时才将 `preferWebSocket` 彻底置为 false；对于网络 I/O 断开，退避重试成功后应允许重新探测 WS。
- **任务 S-04 & S-05 (SAFE)**：
  - **行号**：[L196-L234, L246-L247](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L196-L234)
  - **问题**：鉴权请求头（9 项标头）、签名逻辑和常量基路径与 `GatewayWebSocketTransport` 存在重复定义。
  - **精准修复**：将 `signedInput`、`signatureOf`、`authenticationHeaders` 公开为 `internal` 方法或收敛到公共工具类，供 `GatewayWebSocketTransport` 直接复用；公共常量收敛至统一伴生对象。

---

### 4.2 目标文件：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt`
- **任务 C-03 (CAREFUL)**：
  - **行号**：[L50-L51, L228-L238](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L50-L51)
  - **问题**：`verifyAcceptHeader` 默认值为 `false`，生产环境默认不校验 `Sec-WebSocket-Accept`，且漏检 `Connection: Upgrade` 标头。
  - **精准修复**：将参数默认值改为 `verifyAcceptHeader: Boolean = true`；在握手标头检查中增加 `val conn = headers["connection"]; if (conn == null || !conn.contains("upgrade", ignoreCase = true)) throw IOException(...)`。
- **任务 C-04 (CAREFUL)**：
  - **行号**：[L86](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L86)
  - **问题**：`socket.soTimeout = 0` 导致无读取超时，蜂窝网络半开断网时协程无限假死挂起。
  - **精准修复**：设置合理的 `socket.soTimeout = 30_000`（配合服务端的 15s 心跳周期）。
- **任务 C-05 (CAREFUL)**：
  - **行号**：[L73-L80](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L73-L80)
  - **问题**：TLS 建立（`createSocket`）失败时底层 `plainSocket` 未关闭，导致套接字泄漏。
  - **精准修复**：在建立 SSL 包装时增加 try-catch 保护，若失败立即调用 `runCatching { plainSocket.close() }` 并重新抛出异常。
- **任务 C-06 & C-07 (CAREFUL)**：
  - **行号**：[L102, L126-L148, L252, L274-L287](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L126-L148)
  - **问题**：RFC 6455 状态机漏洞（非法 Continuation 帧静默丢弃、新帧直接重置分片未报错）；错误容忍服务端掩码帧；分片消息重组缓冲区无上限。
  - **精准修复**：
    1. 若 `masked` 为 true，严格按照 RFC 6455 §5.1 抛出 `ProtocolException("Server must not mask frames")`；
    2. 若收到控制帧（PING/CLOSE），校验 `fin == true` 且 `payloadLen <= 125`；
    3. 若在非分片状态收到 Continuation 帧，抛出协议异常；
    4. 分片写入 `messageBuffer` 时，累加长度若超过 `MAX_PAYLOAD_BYTES` 立即抛出异常阻断。
- **任务 C-08 (CAREFUL)**：
  - **行号**：[L340-L360](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L340-L360)
  - **问题**：`readLine` 单字节累加且无单行长度上限保护，存在 OOM 风险。
  - **精准修复**：设置最大单行长度（如 `MAX_LINE_LENGTH = 8192`），在循环内检测 `if (out.size() > MAX_LINE_LENGTH) throw IOException("Header line too long")`。
- **任务 S-02 (SAFE)**：
  - **行号**：[L77](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L77)
  - **问题**：`val params = sslSocket.sslParameters ?: sslSocket.sslParameters` 自指同义反复死代码。
  - **精准修复**：改为 `val params = sslSocket.sslParameters ?: SSLParameters()`。
- **任务 S-03 (SAFE)**：
  - **行号**：[L384-L410](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L384-L410)
  - **问题**：手动遍历 `json.fields` 提取 `id`、`event`、`data`，未复用现成的 `JsonFields` 工具类。
  - **精准修复**：改用 `JsonFields.string(json, "id")`、`JsonFields.string(json, "event")`、`JsonFields.field(json, "data")`，消除样板代码。
- **任务 S-04 & S-05 (SAFE)**：
  - **行号**：[L162-L205, L363-L364](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L162-L205)
  - **问题**：手写构造 9 个鉴权标头与签名，与 `GatewayHttpClient` 重复。
  - **精准修复**：调用 `GatewayHttpClient` 的公共方法生成标头列表，并在循环中写入握手请求流。

---

### 4.3 目标文件：`apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/batch/DebounceBatcher.kt`
- **任务 S-01 (SAFE)**：
  - **行号**：[L16-L50](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/batch/DebounceBatcher.kt#L16-L50)
  - **问题**：`DebounceBatcher` 未实现 `AutoCloseable`，导致调用方扩展函数空转与批处理协程任务泄漏。
  - **精准修复**：声明 `class DebounceBatcher(...) : AutoCloseable`，实现 `override fun close()` 方法：遍历 `activeJobs.values.forEach { it.cancel() }`、`activeJobs.clear()`、`activeBatches.clear()`。

---

### 4.4 目标文件：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt`
- **任务 C-09 (CAREFUL)**：
  - **行号**：[L98-L102, L700](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L98-L102)
  - **问题**：`historicalAttachments` 使用 `accessOrder = true` 的 `LinkedHashMap`，多协程并发读取时（如 `renderTimeline`）导致内部双向链表并发修改指针破坏。
  - **精准修复**：将构造参数中的 `true` 改为 `false`（保持 insertion-order），避免只读查询（`get`）引发结构性链表节点移动；或在访问时使用 `@Synchronized` 同步保护。
- **任务 C-10 (CAREFUL)**：
  - **行号**：[L215-L217, L683-L685](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L215-L217)
  - **问题**：历史消息加载完成回调中暴力调用 `mirrored.clear()` 和 `mirroredRevisions.clear()`，冲刷清空在此期间由实时流推送渲染的 live 消息。
  - **精准修复**：移除 `mirrored.clear()`，采用合并逻辑：对于已存在于 `mirrored` 的消息，保留其更高版本；仅将新获取的历史消息 `putIfAbsent` 或择优合并。
- **任务 C-11 (CAREFUL)**：
  - **行号**：[L590-L620](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L590-L620)
  - **问题**：`TimelineUpsert` 中虽然对 `mirrored` 更新做了 revision 比较，但后续 `update { ... }` 无条件执行，导致旧的 STREAMING 状态逆向覆盖已完成状态；同时空 `conversationId` 缺乏排他性校验。
  - **精准修复**：将 `update` 逻辑收容在 `if (previousRevision == null || event.revision >= previousRevision)` 块内部；校验 `eventConvId == null || eventConvId == currentActiveId`，避免非当前会话的空 ID 消息污染时间线。
- **任务 C-12 (CAREFUL)**：
  - **行号**：[L440-L454](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L440-L454)
  - **问题**：消息发送成功后，无条件执行 `mirroredRevisions[acceptance.messageId] = 0L`，如果实时流已先一步到达并递增了 revision，该操作会逆向冲刷 revision。
  - **精准修复**：仅当 `mirroredRevisions[acceptance.messageId]` 不存在时才设为 0L。
- **任务 C-13 (CAREFUL)**：
  - **行号**：[L487-L493](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L487-L493)
  - **问题**：`submitBatch` 成功后将消息从 `pendingBatch` 剔除，却未同步写入 `mirrored`，造成时间线瞬时空白闪烁。
  - **精准修复**：在过滤 `pendingBatch` 的同时，将已提交的批次消息转换并暂存入 `mirrored`，避免视觉空白。
- **任务 S-01 (SAFE)**：
  - **行号**：[L765-L767](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L765-L767)
  - **问题**：欺骗性私有扩展函数 `DebounceBatcher.close()`。
  - **精准修复**：彻底删除该文件底部的私有扩展函数。
- **任务 S-11 (SAFE)**：
  - **行号**：[L486](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L486)
  - **问题**：神秘命名：`forEach` 返回值（Unit）被命名为 `acceptance`。
  - **精准修复**：将入参重命名为 `_`。
- **任务 S-12 (SAFE)**：
  - **行号**：[L413, L744-L751](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L744-L751)
  - **问题**：`appendLocal` 穿透读取 `_state.value.timeline` 外部快照，破坏原子状态变换的纯函数假定。
  - **精准修复**：重构为 `private fun appendLocal(currentTimeline: Loadable<List<TimelineEntry>>, entry: TimelineEntry): Loadable<List<TimelineEntry>>`，并在调用时传入当前状态的 `it.timeline`。

---

### 4.5 目标文件：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt`
- **任务 S-10 (SAFE)**：
  - **行号**：[L66-L75](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt#L66-L75)
  - **问题**：`drain()` 循环内每次处理一个事件均调用 `buffer.toByteArray()`，导致 O(N^2) 重复内存分配。
  - **精准修复**：重构 `drain()`：仅在循环开始时做单次字节引用，利用局部索引游标向后滑动推进，全部帧解析完成后统一做单次未消耗剩余字节拷贝。
- **任务 S-11 (SAFE)**：
  - **行号**：[L94-L121](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt#L94-L121)
  - **问题**：`findTerminator` 中处理 `\r\n` 与 `\n` 的后续 LF 检查逻辑多层嵌套重复。
  - **精准修复**：提取内联辅助函数统一计算终结符长度，消除重复代码分支。

---

### 4.6 目标文件：`apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayEventDecoder.kt`
- **任务 S-12 (SAFE)**：
  - **行号**：[L25, L63-L66, L145-L155](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayEventDecoder.kt#L63-L66)
  - **问题**：当事件体没有外层 `payload` 包装时，`payload === body`，导致在标题解析与 `conversationIdOf` 循环扫描键时做无谓的双重重复遍历。
  - **精准修复**：在 `listOfNotNull(payload, body)` 前加入去重逻辑：`if (payload === body) listOf(payload) else listOfNotNull(payload, body)`；标题回退链条剔除自同义重复查询。

---

### 4.7 目标文件：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt`
- **任务 S-06 (SAFE)**：
  - **行号**：[L111](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt#L111)
  - **问题**：`connection.readTimeout = readTimeoutMillis` 存在冗余赋值（前一行 `factory.open` 内部已经设置）。
  - **精准修复**：移除该冗余代码行。

---

### 4.8 目标文件：`integrations/hermes/open_android_intelligence_gateway/adapter.py`
- **任务 C-14 (CAREFUL)**：
  - **行号**：[L1030-L1050, L1131-L1150](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L1030-L1050)
  - **问题**：在 `route.event_backlog` 鉴权前，使用未经验证的 `_peek_account_id` 提前将订阅队列注册到全局订阅者集合中，存在信息泄露风险与双重 Ed25519 验签开销。
  - **精准修复**：移除提前注册逻辑；只有在 `handshake = route.event_backlog(raw_req)` 执行完毕且确认 `status == 200` 后，才通过 `self._event_subscribers.setdefault(account_id, set()).add(queue)` 完成注册。
- **任务 S-07 (SAFE)**：
  - **行号**：[L26, L30, L632-L642](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L26)
  - **问题**：死导入 `WSCloseCode`；属性命名别名 `_active_sse_queues` vs `_event_subscribers` 冗余共存。
  - **精准修复**：清理 `WSCloseCode` 导入；内部属性命名统一标准化为 `_event_subscribers`。
- **任务 S-08 (SAFE)**：
  - **行号**：[L1278-L1292](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L1278-L1292)
  - **问题**：`_broadcast_sse` 方法名陈旧且 warning 日志依然输出“SSE subscriber is behind”，即使订阅者走的是 WebSocket 通道。
  - **精准修复**：将方法重命名为 `_broadcast_event`，日志改为通道中立的 `[open_android] Event subscriber queue full; client will resume from cursor`。
- **任务 S-09 (SAFE)**：
  - **行号**：[L1068-L1072, L1160-L1164](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L1068-L1072)
  - **问题**：字符串切片 `part[7:]` 未进行 URL 解码，若客户端游标携带编码字符将匹配失效。
  - **精准修复**：使用 `urllib.parse.parse_qs` 和 `urllib.parse.unquote` 标准提取与解码 `cursor` 参数。

---

## 五、剩余问题与盲区 (Remaining Questions & Gaps)

1. **协议官方契约修订待决 (R-01)**：
   由于当前 [gateway-protocol-v2.md](file:///mnt/数据/项目/open-android-intelligence/docs/contracts/gateway-protocol-v2.md) 明文禁止等价 WebSocket 通道，本报告已将其判定为 `RISKY` 门禁。在契约文件正式合并升版（增加对 WebSocket 端点与握手协商特性的认可）之前，代码实现与契约文档间仍存在形式上的偏离。
2. **广播热路径类型契约重构 (R-02)**：
   `adapter.py` 将 SSE 纯文本推入广播队列导致 WS 端逆向解析反向管道的问题，目前判定为优先采用加固方案以确保向后兼容。未来若要从根源上彻底解耦，需要协同重构 Hermes 核心 exposure 路由的广播生产者（将其改为结构化对象），建议由后续架构迭代专项跟踪。
3. **环境特定限制**：
   本次审查已对相关源码、行号与类结构进行了逐行实证审查，但宿主系统暂未配置单机 `pytest` 环境；Android 端的单元测试已通过 Gradle 配置及源码结构得到彻底确认。建议 Phase 4 修复 Worker 重点通过 `./gradlew testDebugUnitTest` 验证所有 `CAREFUL` 级别的安全加固。

---
**报告交付完毕**。本审计报告已完整归纳、去重与仲裁 5 位审查员的意见，并为 Phase 4 并行修复 Worker 提供了清晰的文件级工作清单与安全门禁。
