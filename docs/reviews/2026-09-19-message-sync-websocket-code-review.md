# 消息同步与全链路 WebSocket 深度代码审查报告

- **日期**：2026-09-19
- **审查范围**：
  - Android 端网关传输与事件客户端：[`GatewayWebSocketTransport.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt)、[`GatewayTransport.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt)、[`GatewayHttpClient.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt)、[`SseParser.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt)、[`GatewayConnectionFactory.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayConnectionFactory.kt)
  - Android 端会话状态与时间线控制：[`WorkbenchController.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt)、[`PairingGrantState.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt)
  - 网关适配层（Hermes 插件）：[`adapter.py`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py)
- **关联改动**：消息同步修复、会话隔离完善与全链路 WebSocket 支持（`fix_message_sync_websocket`）

---

## 一、模块审查结论总览

| 模块 / 核心文件 | 审查结论 |
| :--- | :--- |
| [`GatewayWebSocketTransport.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt) | 存在**阻断级**阻塞式 Socket 协程取消死锁泄漏、**严重级** TLS 域名校验缺失、**严重级** 64 位负数溢出崩溃与 **严重级** RFC 6455 Accept 校验缺失 |
| [`GatewayTransport.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt) | 存在**阻断级**阻塞式 Socket 协程取消死锁与物理线程泄漏 |
| [`GatewayHttpClient.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt) | 存在**阻断级**事件流优雅关闭导致永久假死、**严重级**降级偏好无条件复位引发重连超时震荡 |
| [`WorkbenchController.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) | 存在**严重级**空会话 ID 隔离穿透、**严重级**快速切会话异步覆盖与非原子 CAS 状态丢失、**严重级**待发送排队模糊误删、**严重级**流式到达未决消息瞬时消失、**严重级**历史附件无界驻留 OOM 隐患 |
| [`WorkbenchScreen.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt) | 存在**建议级** `scrollToItem` 传 `Int.MAX_VALUE` 引发 32 位像素算术溢出风险 |
| [`adapter.py`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py) | 存在**严重级**网关握手与广播队列注册竞态丢消息、**建议级**多行 SSE 解析覆盖与空白损毁 |
| [`SseParser.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt) | 逻辑闭环，存在**建议级**循环内频繁内存复制的性能异味 |
| [`GatewayEventDecoder.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayEventDecoder.kt) | **无显式风险**（充分实现 payload 与 body 双向字段降级与别名提取） |
| [`PairingGrantState.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt) | **无显式风险**（已收敛为 `URI` 并校验协议与 Host） |
| [`GatewayConnectionFactory.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayConnectionFactory.kt) | **无显式风险**（重载 `readTimeoutMillis` 参数保持向后兼容） |

---

## 二、深度审查问题清单

### 1. [阻断] [资源泄露/阻塞式 Socket 协程取消死锁与线程泄漏] [`GatewayWebSocketTransport.kt:71-126`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L71-L126) 与 [`GatewayTransport.kt:56-78`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt#L56-L78)
- **风险描述**：
  两处传输层均将底层读取超时设为 0（无限阻塞），并在协程中直接调用阻塞式的 `inputStream.read()`。在 Kotlin 协程中，`job.cancel()` 仅改变 Job 状态，**无法中断阻塞在原生 `SocketInputStream.read()` 上的物理线程**。工作线程无法回到协程调度点检查 `isActive`，也无法进入 `finally` 执行 `socket.close()` 或 `connection.disconnect()`。当切换会话、退出页面断开连接时，旧 Socket 与 `Dispatchers.IO` 线程被永久挂起泄漏，反复操作将迅速耗尽线程池与物理连接。
- **触发代码**：
  ```kotlin
  // GatewayWebSocketTransport.kt:71, 87-88, 124-126
  socket.soTimeout = 0
  try {
      while (currentCoroutineContext().isActive) {
          val frame = readFrame(inputStream) ?: break // 永久卡在 read()，协程取消无法唤醒
          ...
      }
  } finally {
      runCatching { socket.close() }
  }

  // GatewayTransport.kt:56, 70-77
  val connection = open(request, readTimeoutMillis = 0)
  try {
      connection.inputStream.use { stream ->
          while (true) {
              val read = stream.read(buffer) // 同样导致协程取消无法打断
              ...
          }
      }
  } finally {
      connection.disconnect()
  }
  ```
- **修复方案**：
  将底层套接字的物理关闭注册到当前协程 Job 的 `invokeOnCompletion` 回调中，并在套接字层设置保活超时（如 45 秒）。当协程取消时，由取消线程主动关闭套接字，迫使阻塞的 `read()` 立即抛出 `SocketException` 解除阻塞：
  ```kotlin
  // GatewayWebSocketTransport.kt
  socket.soTimeout = 45_000
  val job = currentCoroutineContext()[kotlinx.coroutines.Job]
  val completionHandle = job?.invokeOnCompletion {
      runCatching { socket.close() }
  }
  try {
      // 帧读取与分发循环
  } finally {
      completionHandle?.dispose()
      runCatching { socket.close() }
  }

  // GatewayTransport.kt 同理
  val job = currentCoroutineContext()[kotlinx.coroutines.Job]
  val completionHandle = job?.invokeOnCompletion {
      runCatching { connection.disconnect() }
  }
  try {
      // 流读取循环
  } finally {
      completionHandle?.dispose()
      connection.disconnect()
  }
  ```

---

### 2. [阻断] [边界漏洞/事件流正常关闭导致客户端永久假死] [`GatewayHttpClient.kt:171-173`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L171-L173)
- **风险描述**：
  循环退出条件为 `if (!autoReconnect || !streamFailed) { break }`。当服务端或反向代理（如 Nginx `proxy_read_timeout`、AWS ALB 空闲超时）主动向客户端发送 TCP FIN 或 WebSocket CLOSE (1000/1001) 正常挂断长连接时，客户端读到 EOF 或 CLOSE 帧正常退出，**不会抛出异常，因此 `streamFailed` 为 `false`**。这导致 `!streamFailed` 恒为真，`events()` 的重连循环直接 `break` 退出。外层既无异常也无重试触发，事件流静默死亡，移动端不再接收任何新消息，呈现假死状态。
- **触发代码**：
  ```kotlin
  // GatewayHttpClient.kt:170-173
  // If reconnect is disabled, or if stream ended cleanly without error:
  if (!autoReconnect || !streamFailed) {
      break
  }
  ```
- **修复方案**：
  只要 `autoReconnect == true`，无论对端是异常断开还是正常挂断，都必须按最新游标继续重连：
  ```kotlin
  if (!autoReconnect) {
      break
  }
  ```

---

### 3. [严重] [安全风险/TLS 证书域名校验缺失与 SNI 缺失] [`GatewayWebSocketTransport.kt:62-66`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L62-L66)
- **风险描述**：
  代码通过 `SSLSocketFactory.getDefault().createSocket()` 创建未绑定主机名信息的原始套接字。
  1. **MITM 中间人攻击漏洞**：原始 `SSLSocket` 默认仅验证 CA 签名与有效期，**完全不验证证书中的 Common Name 或 SAN 是否与目标 Host 一致**。攻击者只要持有任意合法 CA 签发的证书即可劫持流量并截获 Bearer Token 与通信内容。
  2. **SNI 缺失**：无参创建导致 `peerHost` 为 `null`，握手 ClientHello 不携带 SNI，多域名反向代理（Cloudflare/Nginx vhost）将返回默认证书或直接握手失败。且在 Conscrypt 下如果直接在无参 socket 上设置 `endpointIdentificationAlgorithm = "HTTPS"` 会抛出 `No peer host name provided` 崩溃。
- **触发代码**：
  ```kotlin
  // GatewayWebSocketTransport.kt:62-66
  } else if (isTls) {
      val sslSocket = SSLSocketFactory.getDefault().createSocket()
      sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
      sslSocket
  }
  ```
- **修复方案**：
  连接基础套接字后再通过分层套接字（Layered Socket）绑定目标主机与端口，并启用 HTTPS 端点校验：
  ```kotlin
  } else if (isTls) {
      val plainSocket = Socket()
      plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
      val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
          .createSocket(plainSocket, host, port, true) as SSLSocket
      val params = sslSocket.sslParameters
      params.endpointIdentificationAlgorithm = "HTTPS"
      sslSocket.sslParameters = params
      sslSocket
  }
  ```

---

### 4. [严重] [边界漏洞/空会话 ID 全量穿透破坏会话数据隔离] [`WorkbenchController.kt:567-635`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L567-L635)
- **风险描述**：
  1. 在 `TimelineUpsert` 中，校验逻辑为：
     `if (eventConvId != null && eventConvId != currentActiveId) return@collect`
     `if (currentActiveId == null && eventConvId != null) return@collect`
     当 `currentActiveId != null` 且事件未带会话 ID（`eventConvId == null`）时，两项检查均不拦截，任何无主事件都会被直接写入当前活跃会话中；
  2. 在 `TimelineTombstoned`、`GenerationCancelled`、`SnapshotInvalidated` 等处理中，条件均写为 `if (eventConvId == null || eventConvId == currentActiveId)`。一旦服务端广播缺少会话 ID 的事件，将无条件在当前活跃会话中执行删除消息、取消生成或时间线重载。
- **触发代码**：
  ```kotlin
  // WorkbenchController.kt:571-578
  if (eventConvId != null && eventConvId != currentActiveId) {
      refreshThreads()
      return@collect
  }
  if (currentActiveId == null && eventConvId != null) {
      refreshThreads()
      return@collect
  }
  if (message.sender == "user" || message.sender == "assistant") {
      mirrored[message.id] = message // 穿透注入
  }
  ```
- **修复方案**：
  实施严格的会话归属判定：无活跃会话时直接拦截；仅当事件会话 ID 与当前会话 ID 严格精确匹配时才允许修改时间线：
  ```kotlin
  val currentActiveId = activeThreadId ?: run {
      refreshThreads()
      return@collect
  }
  if (eventConvId != currentActiveId) {
      refreshThreads()
      return@collect
  }
  ```

---

### 5. [严重] [并发竞争/会话切换异步覆盖与非原子状态更新丢失] [`WorkbenchController.kt:195-215, 724-726`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L195-L215)
- **风险描述**：
  1. **异步覆盖**：`openThread(threadId)` 发起异步加载时未持有 `Job` 取消旧任务，且在回调中未核验 `activeThreadId == threadId`。用户快速在 A、B 会话间切换时，A 会话慢速响应返回后执行 `mirrored.clear()`，导致当前会话被 A 的内容篡改覆盖。
  2. **状态更新丢失 (Lost Update)**：`update` 方法实现为 `_state.value = transform(_state.value)`，未使用原子的 CAS 操作。在用户输入与推送事件高频并发时，存在写覆盖导致状态丢失的竞态风险。
- **触发代码**：
  ```kotlin
  // WorkbenchController.kt:195-199
  scope.launch {
      Result.runCatching { repository.timeline(threadId, PageRequest()) }.fold(
          onSuccess = { page ->
              mirrored.clear()
              page.messages.forEach { mirrored[it.id] = it }
  ...
  // WorkbenchController.kt:724-726
  private fun update(transform: (WorkbenchUiState) -> WorkbenchUiState) {
      _state.value = transform(_state.value) // 非原子 CAS
  }
  ```
- **修复方案**：
  维护 `timelineJob` 并在切换时取消前序任务，并在回调中做一致性断言；`update` 方法改为 `_state.update(transform)`：
  ```kotlin
  timelineJob?.cancel()
  timelineJob = scope.launch {
      Result.runCatching { repository.timeline(threadId, PageRequest()) }.fold(
          onSuccess = { page ->
              if (activeThreadId != threadId) return@launch
              mirrored.clear()
              page.messages.forEach { mirrored[it.id] = it }
              // ...
          }
      )
  }

  // 原子 CAS 状态更新
  private fun update(transform: (WorkbenchUiState) -> WorkbenchUiState) {
      _state.update(transform)
  }
  ```

---

### 6. [严重] [并发竞争/网关握手与广播队列注册时间窗丢消息] [`adapter.py:912-933, 965-983`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L912-L933)
- **风险描述**：
  在网关适配器处理 `_handle_ws_stream` / `_handle_sse_stream` 时，执行时序为：
  1. `handshake = route.event_backlog(...)`（获取历史积压）
  2. `await ws.prepare(request)`（与客户端进行网络 I/O 握手）
  3. `subscribers.add(queue)`（注册实时广播队列）
  在步骤 1 完成至步骤 3 注册成功的网络 I/O 时间窗口内，系统若产生新广播事件，由于此时队列尚未加入广播池且历史积压已查询结束，**该事件将被静默丢弃，对客户端造成永久性漏消息**。
- **触发代码**：
  ```python
  # adapter.py:965-983
  handshake = route.event_backlog(raw_req) # 1. 抓取积压
  ...
  ws = web.WebSocketResponse(heartbeat=15.0)
  await ws.prepare(request) # 2. 网络 IO 耗时窗口，此时新事件无处可投

  queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
  subscribers = self._active_sse_queues.setdefault(account_id, set())
  subscribers.add(queue) # 3. 此时才加入广播池
  ```
- **修复方案**：
  在读取历史 backlog 之前先行注册监听队列，确保握手期间产生的增量事件能被队列缓冲接收：
  ```python
  queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
  subscribers = self._active_sse_queues.setdefault(account_id, set())
  subscribers.add(queue)
  try:
      ws = web.WebSocketResponse(heartbeat=15.0)
      await ws.prepare(request)
      handshake = route.event_backlog(raw_req)
      ...
  ```

---

### 7. [严重] [边界漏洞/模糊匹配导致排队待发送消息被批量误删] [`WorkbenchController.kt:561`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L561)
- **风险描述**：
  在处理 `MessageAccepted` 时使用了 `it.key.endsWith(event.correlationId)`。若服务端返回的事件未携带 `correlationId`，将被解码为空字符串 `""`。在 Kotlin 中任意字符串 `endsWith("")` 恒为真，这会导致待发送队列中的所有本地消息被瞬间全部误清空；且当 ID 较短时也存在后缀意外碰撞误删。
- **触发代码**：
  ```kotlin
  // WorkbenchController.kt:559-563
  update { state ->
      state.copy(
          pendingBatch = state.pendingBatch.filterNot { it.key.endsWith(event.correlationId) },
      )
  }
  ```
- **修复方案**：
  校验 `correlationId` 非空并执行严格的等值匹配：
  ```kotlin
  val correlationId = event.correlationId.trim()
  if (correlationId.isNotEmpty()) {
      update { state ->
          state.copy(
              pendingBatch = state.pendingBatch.filterNot { 
                  it.key == "local_$correlationId" || it.key == correlationId 
              },
          )
      }
  }
  ```

---

### 8. [严重] [未捕获异常/64 位 WebSocket Payload 长度符号溢出导致致命崩溃] [`GatewayWebSocketTransport.kt:211-232`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L211-L232)
- **风险描述**：
  代码通过位移拼装 `len: Long`。当收到损坏或恶意构造的帧导致最高位（符号位）为 1 时，`len` 将成为负数。后续 `payloadLen > MAX_PAYLOAD_BYTES` 对负数判定为 false，紧接着调用 `ByteArray(payloadLen.toInt())` 时会直接抛出未捕获的 `NegativeArraySizeException` 致命异常，导致整个事件流崩溃。
- **触发代码**：
  ```kotlin
  // GatewayWebSocketTransport.kt:218, 221, 231
  payloadLen = len
  if (payloadLen > MAX_PAYLOAD_BYTES) {
      throw IOException("WebSocket frame payload exceeds limit: $payloadLen bytes")
  }
  val payload = ByteArray(payloadLen.toInt())
  ```
- **修复方案**：
  严格增加非负数校验：
  ```kotlin
  if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
      throw IOException("WebSocket frame payload length invalid: $payloadLen bytes")
  }
  ```

---

### 9. [严重] [网络设计缺陷/降级状态无条件复位导致断网恢复时遭受持续重连震荡] [`GatewayHttpClient.kt:166-168`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L166-L168)
- **风险描述**：
  若所连接的网关或企业代理不支持 WebSocket，客户端在首次连接时 WS 失败并降级至 SSE。SSE 成功收到事件后将 `receivedAnyEventInAttempt` 置为 true。在第 167 行，代码无条件执行 `preferWebSocket = (webSocketTransport != null)` 将偏好重置为 true。导致移动端每次基站切换或网络闪断重连时，都会被强制先尝试必然失败的 WebSocket，白白经历 10 秒超时挂起后才降级回 SSE，造成严重的用户界面卡顿与消息接收延迟。
- **触发代码**：
  ```kotlin
  // GatewayHttpClient.kt:166-168
  if (receivedAnyEventInAttempt) {
      preferWebSocket = (webSocketTransport != null)
  }
  ```
- **修复方案**：
  仅当当前连接是通过 WebSocket 成功收到事件时，才维持 WebSocket 偏好；降级至 SSE 后不因 SSE 收到数据而强行复位：
  ```kotlin
  if (receivedWsEventInAttempt) {
      preferWebSocket = true
  }
  ```

---

#### 10. [严重] [安全风险/RFC 6455 协议缺失 Sec-WebSocket-Accept 校验] [`GatewayWebSocketTransport.kt:183-193`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L183-L193)
- **风险描述**：
  代码在读取到响应状态行包含 `101` 后，直接跳过并丢弃了所有的响应头，完全没有按 RFC 6455 §4.2.2 规范校验服务端的 `Sec-WebSocket-Accept` 签名。当遭遇透明 HTTP 代理或公共 Wi-Fi 伪造 101 时，客户端将普通 HTTP 流量误当成 WebSocket 帧解析，引发死锁或协议异常。
- **触发代码**：
  ```kotlin
  // GatewayWebSocketTransport.kt:183-193
  val statusLine = readLine(input) ?: throw IOException(...)
  if (!statusLine.contains(" 101 ") && !statusLine.endsWith(" 101")) {
      throw IOException("WEBSOCKET_HANDSHAKE_FAILED: $statusLine")
  }
  while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break // 未提取校验 Sec-WebSocket-Accept
  }
  ```
- **修复方案**：
  解析响应头并完成 SHA-1 与 Base64 签名比对：
  ```kotlin
  val expectedAccept = java.util.Base64.getEncoder().encodeToString(
      java.security.MessageDigest.getInstance("SHA-1").digest(
          (secWebSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)
      )
  )
  var acceptVerified = false
  while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon != -1) {
          val name = line.substring(0, colon).trim()
          val value = line.substring(colon + 1).trim()
          if (name.equals("Sec-WebSocket-Accept", ignoreCase = true) && value == expectedAccept) {
              acceptVerified = true
          }
      }
  }
  if (!acceptVerified) throw IOException("WEBSOCKET_HANDSHAKE_FAILED: missing or invalid Sec-WebSocket-Accept")
  ```

---

#### 11. [建议] [代码坏味道/removeAttachment 提前清理导致引用恒空] [`WorkbenchController.kt:379, 420`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L379)
- **风险描述**：
  在 `submitWhenAttachmentsVerified()` 中，第 379 行先行调用 `removeAttachment` 清理了 `attachmentSelections`；而在随后的第 420 行构建已发送消息部件时，又试图执行 `val sel = attachmentSelections[draftId]`。此时该 Map 中对应条目已被移除，`sel` 恒为 `null`，属于死代码与状态时序混乱。
- **触发代码**：
  ```kotlin
  // WorkbenchController.kt:379, 420
  submission.attachmentIds.forEach(::removeAttachment)
  ...
  val sel = attachmentSelections[draftId] // 恒为 null
  ```
- **修复方案**：
  直接复用第 362 行已构建完成的 `submittedAttachments`，或从 `historicalAttachments[remoteId]` 中读取。

---

#### 12. [建议] [协议与边界/多行 SSE data 帧解析覆盖与空白字符损毁] [`adapter.py:466-467`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L466-L467)
- **风险描述**：
  在 `_ws_message_from_queue_item` 中，`stripped.startswith("data:"): data_str = stripped[5:].strip()` 会导致：连续多行 `data:` 仅保留最后一行；且 `strip()` 强行抹去数据 payload 内部合法的缩进空格与前导/尾随空格。
- **触发代码**：
  ```python
  # adapter.py:466-467
  elif stripped.startswith("data:"):
      data_str = stripped[5:].strip()
  ```
- **修复方案**：
  收集所有 `data:` 行并遵循 SSE 规范仅剥离首个可选空格：
  ```python
  data_lines = []
  for line in text.splitlines():
      line = line.rstrip("\r")
      if line.startswith("data:"):
          val = line[5:]
          if val.startswith(" "):
              val = val[1:]
          data_lines.append(val)
  data_str = "\n".join(data_lines) if data_lines else "{}"
  ```

---

#### 13. [建议] [网络边界/IPv6 Host Header 缺失方括号格式损坏] [`GatewayWebSocketTransport.kt:56`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt#L56)
- **风险描述**：
  若网关配置为 IPv6 地址（如 `[::1]`），Java `URI.host` 返回不带括号的纯文本（如 `::1`）。代码直接拼接为 `Host: ::1:8080`，违反 RFC 3986 / RFC 7230 规范，会导致标准 HTTP 代理及网关解析失败并返回 400 Bad Request。
- **触发代码**：
  ```kotlin
  // GatewayWebSocketTransport.kt:56
  val hostHeader = if ((isTls && port == 443) || (!isTls && port == 80)) host else "$host:$port"
  ```
- **修复方案**：
  ```kotlin
  val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
  val hostHeader = if ((isTls && port == 443) || (!isTls && port == 80)) formattedHost else "$formattedHost:$port"
  ```

---

#### 14. [建议] [性能异味/SseParser.drain 频繁内存全量复制] [`SseParser.kt:61-75`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt#L61-L75)
- **风险描述**：
  `drain()` 在 `while(true)` 循环中，每次切出一个完整帧，都对缓冲区调用 `buffer.toByteArray()` 并在截断后调用 `buffer.reset()` + `buffer.write()`。在密集高频推送场景下产生无谓的 $O(N^2)$ 数组分配和内存拷贝开销。
- **触发代码**：
  ```kotlin
  // SseParser.kt:62, 68-69
  val buffered = buffer.toByteArray()
  ...
  buffer.reset()
  buffer.write(buffered, consumed, buffered.size - consumed)
  ```
#### 15. [严重] [界面交互缺陷/流式更新覆盖致使未决发送消息瞬时消失] [`WorkbenchController.kt:582-597, 665-706`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L582-L597)
- **风险描述**：
  在用户发送消息时，消息先作为本地占位消息加入 `state.timeline` 与 `state.pendingBatch`。一旦远端有任何流式更新（如 `TimelineUpsert`）到达，控制器会调用 `renderTimeline()` 重构时间线。然而 `renderTimeline()` **仅遍历服务端已镜像的 `mirrored` 字典，完全丢弃了 `pendingBatch`**。如果用户刚点击发送且网络稍有延迟，只要流式事件到达，未决发送的消息将瞬间从界面上凭空蒸发，直到几秒后服务端回执返回才重新出现。
- **触发代码**：
  ```kotlin
  // WorkbenchController.kt:582-597
  update { state ->
      state.copy(
          timeline = if (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty) {
              Loadable.Ready(renderTimeline()) // renderTimeline() 仅读取 mirrored.values！
          } else {
              state.timeline
          },
          ...
      )
  }
  ```
- **修复方案**：
  `renderTimeline()` 必须将 `mirrored` 的已确认消息与 `_state.value.pendingBatch` 中未被确认的条目合并输出：
  ```kotlin
  private fun renderTimeline(): List<TimelineEntry> {
      val confirmed = mirrored.values.sortedBy { it.timestamp }.map { ... }
      val pending = _state.value.pendingBatch.filterNot { local ->
          mirrored.containsKey(local.key.removePrefix("local_"))
      }
      return confirmed + pending
  }
  ```

---

#### 16. [严重] [内存泄露/原始二进制图片无界驻留导致 OOM 崩溃隐患] [`WorkbenchController.kt:96, 371-377`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L96)
- **风险描述**：
  `historicalAttachments` 是一个普通的 `LinkedHashMap<String, TimelineAttachment>`。在用户发送附件时，包含原始完整图片字节（`att.imageBytes: ByteArray`，单张几 MB 至十几 MB）的实体被永久放入该 Map 中以便显示缩略图。但在整个应用运行期间，**该字典从未执行清理或 LRU 淘汰**。随着会话切换或持续对话，无界膨胀的图片字节将永久驻留堆内存，最终导致 Android 进程 OOM（内存溢出）崩溃。
- **触发代码**：
  ```kotlin
  // WorkbenchController.kt:96, 371-377
  private val historicalAttachments = LinkedHashMap<String, com.openandroidintelligence.conversation.model.TimelineAttachment>()
  ...
  historicalAttachments[id] = att // att 包含完整原始 imageBytes
  historicalAttachments[remoteId] = att.copy(draftId = remoteId)
  // 全生命周期内无任何清理、移除或容量上限限制
  ```
- **修复方案**：
  采用具有容量上限的 LRU 淘汰策略：
  ```kotlin
  private val historicalAttachments = object : LinkedHashMap<String, TimelineAttachment>(32, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimelineAttachment>?): Boolean {
          return size > 30 // 仅保留最近 30 张附件缓存，超出自动驱逐释放内存
      }
  }
  ```

---

#### 17. [建议] [潜在 Bug/滚动像素偏移整型溢出风险] [`WorkbenchScreen.kt:85`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt#L85)
- **风险描述**：
  代码在跟踪最新消息时调用了 `listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)`。在 Compose 规范中，第二个参数为像素偏移量（pixels）。传入 `Int.MAX_VALUE`（21.4 亿像素）会导致 Compose 内部在几何运算 `scrollOffset + itemHeight` 时发生 **32 位有符号整数算术溢出**，变为负数，导致列表在特定 Compose 运行时版本中跳变到异常滚动位置甚至触发测量异常。
- **触发代码**：
  ```kotlin
  // WorkbenchScreen.kt:85
  if (totalItems > 0) listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
  ```
- **修复方案**：
  将像素偏移参数改为 0 即可：
  ```kotlin
  if (totalItems > 0) listState.scrollToItem(totalItems - 1, 0)
  ```

---

## 三、无显式风险模块说明

- **[`GatewayEventDecoder.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayEventDecoder.kt)**：
  对 SSE/WS 事件的多形态 JSON 数据源（payload 与 body 互为兜底）、多 key 别名解析（`conversationId`/`conversation_id`/`chat_id`）及空安全保护十分完善，会话隔离过滤严密，**无显式风险**。
- **[`PairingGrantState.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt)**：
  将原本的 `URL` 构造迁移为 `URI(gatewayId)`，规避了 `java.net.URL` 在特定 DNS/IP 环境下的解析歧义，严格限制了 `http`/`https` 协议与非空主机名，静态规约检测完全通过，**无显式风险**。
- **[`GatewayConnectionFactory.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayConnectionFactory.kt)**：
  增加可选的 `readTimeoutMillis` 参数并默认保持 30 秒超时向后兼容，**无显式风险**。

---

## 四、后续修复优先级与建议

1. **最高优先级 (P0 阻断修复)**：
   - **问题 1（Socket 协程取消死锁与线程泄漏）**：在 [`GatewayWebSocketTransport.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt) 与 [`GatewayTransport.kt`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt) 中挂接 `Job.invokeOnCompletion`，防止应用退出/切会话时物理线程与连接泄漏。
   - **问题 2（正常关闭导致事件流假死）**：移除 [`GatewayHttpClient.kt:171`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt#L171) 的 `!streamFailed` 判定，保证长连接正常断开时依然能够自动重连。
2. **第二优先级 (P1 严重质量与安全缺陷)**：
   - **问题 3（分层 Socket HTTPS/SNI 校验）**：杜绝中间人攻击与 Conscrypt 握手崩溃。
   - **问题 4（空会话 ID 隔离漏洞）**：防止未带会话 ID 的广播事件污染或删除当前活跃会话数据。
   - **问题 5（CAS 状态更新与异步覆盖）**：引入 `timelineJob` 防止快速切换数据错乱，修复 `update` 为原子更新。
   - **问题 6（网关实时事件注册竞态）**：在 [`adapter.py`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py) 中提前注册订阅队列，避免握手耗时窗口内丢失消息。
   - **问题 7、8、9、10**：修复排队误删、载荷负数溢出崩溃、降级震荡与协议签名校验。
   - **问题 15（未决消息流式瞬时消失）**：在 `renderTimeline` 中合并未决待发送消息。
   - **问题 16（历史附件无界驻留 OOM 隐患）**：为附件原始二进制缓存施加容量上限与 LRU 淘汰策略。
3. **第三优先级 (P2 建议与性能优化)**：
   - 修复附件引用恒空（问题 11）、多行 SSE 换行（问题 12）、IPv6 主机头方括号（问题 13）、内存重复分配（问题 14）与滚动像素偏移整型溢出（问题 17）。


