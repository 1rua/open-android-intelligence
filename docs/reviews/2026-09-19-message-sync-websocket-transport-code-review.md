# 消息同步与全链路 WebSocket 传输：深度代码评审

- 评审日期：2026-09-19
- 评审范围：提交 `90b773a`（修复消息同步与传输），17 个文件，新增 2131 行 / 删除 138 行
- 评审维度：潜在 Bug 与边界漏洞、代码坏味道与重构点、安全风险
- 结论：4 项阻断、7 项严重、7 项建议。其中「WS 正常关闭后事件流永久终止」「WS 缺失 TLS 主机名校验」「阻塞读不可取消」三项应在合入主线前修复。

## 评审对象

| 文件 | 关注点 |
| --- | --- |
| `apps/android/gateway-client/.../ws/GatewayWebSocketTransport.kt` | 新增 RFC 6455 客户端（368 行） |
| `apps/android/gateway-client/.../http/GatewayHttpClient.kt` | WS 优先 + SSE 降级 + 指数退避 |
| `apps/android/gateway-client/.../http/GatewayTransport.kt` | SSE 流移除读超时 |
| `apps/android/gateway-client/.../http/GatewayConnectionSecurity.kt` | 新增 `Socket` 分类重载 |
| `apps/android/gateway-client/.../events/SseParser.kt` | 换行切分修正 |
| `apps/android/conversation-ui/.../state/WorkbenchController.kt` | 会话隔离、发送链路 |
| `apps/android/conversation-data/.../data/GatewayEventDecoder.kt` | payload/body 多字段解析 |
| `integrations/hermes/open_android_intelligence_gateway/adapter.py` | WS 升级握手鉴权与广播 |
| `apps/android/platform-kernel/.../kernel/PairingGrantState.kt` | URI 规范调用修正 |

---

## 一、阻断级

### 1. [阻断] [并发/资源泄露] `GatewayWebSocketTransport.kt:71,87-88,124-126`

- **风险描述**：`socket.soTimeout = 0` 让 `input.read()` 无限阻塞，而协程取消只对**挂起点**生效。循环体内在 `readFrame` 阻塞期间没有任何挂起点，因此 `eventJob.cancel()`、切换会话、关闭控制器时取消信号根本传不进去：`finally { socket.close() }` 不执行，Dispatchers.IO 线程被永久占用，socket 泄漏。每次切换会话泄漏一个线程 + 一个 socket，很快耗尽 IO 线程池。
- **触发代码**：
```71:71:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt
        socket.soTimeout = 0
```
```87:88:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt
            while (currentCoroutineContext().isActive) {
                val frame = readFrame(inputStream) ?: break
```
- **修复方案**：把 socket 生命周期挂到协程取消回调上，取消时强制关闭以打断阻塞读：
```kotlin
val socket: Socket = ...
currentCoroutineContext().job.invokeOnCancel { runCatching { socket.close() } }
```
> `GatewayTransport.eventStream` 的 `inputStream.use { while(true) stream.read(buffer) }` 在 `readTimeoutMillis = 0` 下存在完全相同的问题，需一并加 `invokeOnCancel { connection.disconnect() }`。

### 2. [阻断] [逻辑 Bug] `GatewayHttpClient.kt:127,166-173`

- **风险描述**：服务端**正常**发来 CLOSE 帧（空闲回收、网关重启、aiohttp heartbeat 超时断连）时，WS flow 是 `break` 后**正常完成**的，`streamFailed` 保持 `false`，于是 `if (!autoReconnect || !streamFailed) break` 直接跳出外层 `while` —— 事件流**永久终止**。用户表现为「用着用着再也收不到新消息」，必须杀进程重启。这正是本次「断线自愈」目标的核心反例，且测试未覆盖。
- **触发代码**：
```127:127:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt
            if (!preferWebSocket || (!receivedAnyEventInAttempt && streamFailed)) {
```
```171:173:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt
            if (!autoReconnect || !streamFailed) {
                break
            }
```
- **修复方案**：让「通道结束」本身成为重连理由，只有显式停止才退出：
```kotlin
// WS/SSE 任一通道正常结束都视为需要重连；autoReconnect=false 才退出
if (!autoReconnect) break
```
`receivedAnyEventInAttempt` 的语义收窄为只用于 backoff 复位，不再参与是否重连的判断。

### 3. [阻断] [安全 — 鉴权缺失] `GatewayWebSocketTransport.kt:63-64` + `GatewayConnectionSecurity.kt:39-50`

- **风险描述**：HTTPS 通道走 `HttpsURLConnection`，平台会做主机名校验；而 WS 通道用 `SSLSocketFactory.getDefault().createSocket()` 无参构造再 `connect(InetSocketAddress)`，**既没有 SNI、也没有 endpoint identification**。当 `pins` 为空时（`SpkiPinning.verify` 首行 `if (pins.isEmpty()) return`）只验证「证书链可被系统信任」，不验证证书属于该网关域名 → 任意受信任证书的中间人可冒充网关，窃取 Bearer token 与请求签名。这等于给 ADR 0047 的传输安全模型开了后门。
- **触发代码**：
```63:64:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt
            val sslSocket = SSLSocketFactory.getDefault().createSocket()
            sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
```
- **修复方案**：握手前强制主机名校验（并顺带保证 SNI）：
```kotlin
val sslSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
sslSocket.sslParameters = sslSocket.sslParameters.apply {
    endpointIdentificationAlgorithm = "HTTPS"
}
sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
```
`GatewayConnectionSecurity.classify(socket, pins)` 内亦应断言 `session.peerPrincipal` 的主机名匹配，避免换实现时再次遗漏。

### 4. [阻断] [可用性/无超时兜底] `GatewayTransport.kt:55-79`

- **风险描述**：本次「移除 30 秒超时强断」把 `readTimeout` 设为 0，且未引入任何停滞检测。TCP 半开连接（Wi-Fi 切换、NAT 老化、对端进程挂死）下 `stream.read()` 会**永久阻塞且不抛异常**，上层 `catch (e: Throwable)` 永不触发 → 重连逻辑永不执行，SSE 静默死亡。服务端 `: ping` 心跳只在服务端健康时有效，客户端缺少「多久没收到字节就判定链路死」的看门狗。
- **触发代码**：
```68:75:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt
            connection.inputStream.use { stream ->
                val buffer = ByteArray(EVENT_CHUNK_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (read > 0) emit(buffer.copyOf(read))
                }
            }
```
- **修复方案**：保留长连接但加「最后字节时间」看门狗，超时主动断开让上层重连。最简落地是保留 socket `soTimeout` 为 45s，捕获 `SocketTimeoutException` 后 `break` 交给上层重连，而不是设为 0：
```kotlin
val idleTimeoutMs = 45_000L
while (currentCoroutineContext().isActive) {
    val read = withTimeoutOrNull(idleTimeoutMs) { readAsync(stream, buffer) }
        ?: throw IOException("EVENT_STREAM_STALLED: no bytes for ${idleTimeoutMs}ms")
    if (read == -1) break
    if (read > 0) emit(buffer.copyOf(read))
}
```

---

## 二、严重级

### 5. [严重] [边界/健壮性] `GatewayWebSocketTransport.kt:211-219,231`

- **风险描述**：64 位长度字段按 RFC 6455 最高位必须为 0，代码未校验。恶意或错误服务端发带符号位的 `payloadLen` 时 `len` 变负，`payloadLen > MAX_PAYLOAD_BYTES` 检查直接通过，随后 `ByteArray(payloadLen.toInt())` 抛 `NegativeArraySizeException`（非 IOException），绕过所有网络错误处理。同时未校验 RSV1-3 与保留 opcode。
- **触发代码**：
```211:219:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt
        } else if (payloadLen == 127L) {
            var len = 0L
            for (i in 0 until 8) {
                val b = input.read()
                if (b == -1) throw EOFException("Unexpected EOF reading 64-bit payload length")
                len = (len shl 8) or (b.toLong() and 0xFFL)
            }
            payloadLen = len
        }
```
- **修复方案**：
```kotlin
    payloadLen = len
    if (len < 0) throw IOException("WEBSOCKET_PROTOCOL_ERROR: payload length MSB must be 0")
}
// readFrame 头部解析处同时拒绝保留位：
if ((b0 and 0x70) != 0) throw IOException("WEBSOCKET_PROTOCOL_ERROR: RSV bits must be 0")
```

### 6. [严重] [安全 — 响应未校验 / 头注入] `GatewayWebSocketTransport.kt:58,162,183-192`

- **风险描述**：① 握手响应**只检查状态行含 101**，所有响应头被 `while` 循环丢弃，既不校验 `Upgrade: websocket`，也不校验 `Sec-WebSocket-Accept` —— 无法证明对端真的完成了 WebSocket 协商，代理/缓存返回 101 时客户端会把响应体当事件流消费。② `target` 由 `cursor` 直接拼进请求行，作为 public API 的 `events(cursor)` **没有任何校验**，cursor 含 `\r\n` 即构成请求行/头注入（请求走私）。目前仅依赖 `GatewayHttpClient` 侧 `CURSOR_ALPHABET` 兜底，属单点防御。
- **触发代码**：
```58:58:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt
        val target = if (cursor == null) EVENTS_TARGET else "$EVENTS_TARGET?cursor=$cursor"
```
```183:192:apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt
        val statusLine = readLine(input)
            ?: throw IOException("WEBSOCKET_HANDSHAKE_FAILED: unexpected end of stream")
        if (!statusLine.contains(" 101 ") && !statusLine.endsWith(" 101")) {
            throw IOException("WEBSOCKET_HANDSHAKE_FAILED: $statusLine")
        }
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
```
- **修复方案**：
```kotlin
val target = if (cursor == null) EVENTS_TARGET
    else "$EVENTS_TARGET?cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8")

// 解析并校验响应头，而不是丢弃
val headers = readHeaders(input)
if (!headers.any { it.first.equals("Upgrade", true) && it.second.contains("websocket", true) })
    throw IOException("WEBSOCKET_HANDSHAKE_FAILED: missing Upgrade")
val expected = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-1").digest(
        (secWebSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
if (headers.firstOrNull { it.first.equals("Sec-WebSocket-Accept", true) }?.second?.trim() != expected)
    throw IOException("WEBSOCKET_HANDSHAKE_FAILED: bad Sec-WebSocket-Accept")
```

### 7. [严重] [并发竞争] `WorkbenchController.kt:92,197-199,580,656-660,492-494,665-667`

- **风险描述**：`mirrored` 是普通 `LinkedHashMap`，却被**至少三类协程**并发写：事件流 `collect`（580）、`openThread`/`reloadTimeline` 的网络协程（197-199、656-660）、以及 UI 线程直接调用的 `stopGeneration`（492-494）。665-667 的 `renderTimeline()` 还在迭代 `mirrored.values`。任一处迭代期间另一线程 `put` 立即抛 `ConcurrentModificationException`（`stopGeneration` 与流式 delta 同时发生时概率极高），并造成 timeline 状态错乱。整个文件没有任何锁或线程约束。
- **触发代码**：
```492:494:apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt
                mirrored.values.filter { it.state == "STREAMING" }.forEach { streamingMsg ->
                    mirrored[streamingMsg.id] = streamingMsg.copy(state = "CANCELLED")
                }
```
- **修复方案**：把状态修改收敛到单一线程，而不是加散落的锁：
```kotlin
class WorkbenchController(
    private val scope: CoroutineScope, // 必须是 Main.immediate 单线程 scope
    ...
)
private fun update(transform: (WorkbenchUiState) -> WorkbenchUiState) {
    scope.launch(Dispatchers.Main.immediate) { _state.value = transform(_state.value) }
}
```
所有 `mirrored[...] = ...`（含 `stopGeneration`）统一改到该 scope 内执行；若无法保证单线程，则改用 `ConcurrentHashMap` 并在 `renderTimeline` 中基于快照迭代。

### 8. [严重] [伪造状态 / 违反项目铁律] `WorkbenchController.kt:487-500`

- **风险描述**：拿不到服务端 `generationId` 时，代码**本地**把 `STREAMING` 消息改成 `CANCELLED` 并置 `GenerationState.CANCELLED`，弹「已停止生成」。这直接违反项目硬约束「严禁伪造实现」「取消意图不是结果」：手机显示已停止，服务端其实仍在生成，随后到来的 delta 会把消息改回 STREAMING，用户看到状态来回跳；`renderTimeline` 的 `isStreaming` 也随之变成假值。
- **触发代码**：
```487:500:apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt
        if (generationId == null) {
            if (_state.value.generation == GenerationState.QUEUED || ...) {
                mirrored.values.filter { it.state == "STREAMING" }.forEach { streamingMsg ->
                    mirrored[streamingMsg.id] = streamingMsg.copy(state = "CANCELLED")
                }
                update { it.copy(generation = GenerationState.CANCELLED, timeline = ..., notice = "已停止生成") }
                return
            }
            update { it.copy(notice = "STOP_UNAVAILABLE:NO_GENERATION") }
            return
        }
```
- **修复方案**：无 generationId 就是「无法取消」，如实呈现，不造终态：
```kotlin
if (generationId == null) {
    update { it.copy(generation = GenerationState.UNSUPPORTED, notice = "STOP_UNAVAILABLE:NO_GENERATION") }
    return
}
```
复用已有的 `GenerationState.UNSUPPORTED` 语义，不要再新增本地假状态。

### 9. [严重] [乱序覆盖] `WorkbenchController.kt:580` + `GatewayEventDecoder.kt:126`

- **风险描述**：`mirrored[message.id] = message` 是**无条件覆盖**，而 `GatewayEventDecoder` 在 payload 缺 `revision` 时回退 `0L`。网络乱序时（先到 `completed`、后到迟到的 `delta`），早先的 STREAMING 内容会覆盖已确认正文，用户看到消息「倒退」。`revision` 字段形同虚设。
- **触发代码**：
```579:581:apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt
                            if (message.sender == "user" || message.sender == "assistant") {
                                mirrored[message.id] = message
                            }
```
- **修复方案**：
```kotlin
val previous = mirrored[message.id]
if (previous == null || message.revision >= previous.revision) {
    mirrored[message.id] = message
}
```
`GatewayEventDecoder` 侧应把缺失 revision 视为「未知」而非 0（例如回退到 `occurredAt` 参与比较），避免 0 永远小于任何 revision。

### 10. [严重] [资源泄露 / 脆弱解析] `integrations/hermes/open_android_intelligence_gateway/adapter.py:977-1000,1032-1053`

- **风险描述**：① WS 订阅者复用了 SSE 的**字节队列**，于是 `_ws_message_from_queue_item` 必须靠 `"id:" in text or "event:" in text` 这种**全文子串启发式**把 SSE 帧反向解析成 JSON；一旦 payload 正文里出现 `id:`（任何含该子串的文本），就会走错分支产出畸形帧。这是典型的过度兼容 + 过度设计，本应直接投递事件对象。② `send_loop` 的 `await queue.get()` **无超时**，当 `self._running` 置 False（关闭 adapter）时任务仍阻塞在 `queue.get()`，`asyncio.wait` 永不返回 → 关闭流程挂起、任务泄漏。
- **触发代码**：
```985:1000:integrations/hermes/open_android_intelligence_gateway/adapter.py
            async def send_loop() -> None:
                while self._running and not ws.closed:
                    try:
                        item = await queue.get()
                    except asyncio.CancelledError:
                        break
                    msg = _ws_message_from_queue_item(item)
```
```458:458:integrations/hermes/open_android_intelligence_gateway/adapter.py
    if "id:" in text or "event:" in text:
```
- **修复方案**：给 WS 单独的事件订阅集合，彻底删掉 SSE 帧逆向解析：
```python
# 订阅时区分通道，WS 队列只放 Mapping
self._active_ws_queues.setdefault(account_id, set()).add(queue)

# send_loop 加超时，保证关闭可退出
try:
    item = await asyncio.wait_for(queue.get(), timeout=SSE_HEARTBEAT_SECONDS)
except asyncio.TimeoutError:
    if not self._running:
        break
    continue
```
删除 `_ws_message_from_queue_item`，广播处对 SSE 投 `_sse_frame(event)`、对 WS 直接投事件 `Mapping`。

### 11. [严重] [未捕获异常 / 资源未清理] `WorkbenchController.kt:279-303,133-135`

- **风险描述**：`addAttachment` 里 `coordinator.prepare(selection)` 与 `coordinator.observe` 的 collect 都**没有异常保护**，`launch` 内抛出即交给 scope 的 CoroutineExceptionHandler（未配置即崩溃）。另外 `close()` 只 `eventJob?.cancel()`，`attachmentJobs`、`creationJob`、`batcher` 全不取消 → 销毁后仍在跑上传与定时任务。
- **触发代码**：
```279:287:apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt
        scope.launch {
            val draft = coordinator.prepare(selection)
            val draftId = draft.id.value
            attachmentSelections[draftId] = selection
```
```133:135:apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt
    override fun close() {
        eventJob?.cancel()
    }
```
- **修复方案**：
```kotlin
override fun close() {
    eventJob?.cancel()
    attachmentJobs.values.forEach { it.cancel() }
    attachmentJobs.clear()
    creationJob?.cancel()
    batcher.close()
}

// addAttachment：
scope.launch {
    val draft = runCatching { coordinator.prepare(selection) }
        .onFailure { update { it.copy(notice = "ATTACHMENT_PREPARE_FAILED:${errorCodeOf(it)}") } }
        .getOrNull() ?: return@launch
    ...
}
```

---

## 三、建议级

### 12. [建议] [重复逻辑] `GatewayWebSocketTransport.kt:314` 与 `GatewayHttpClient.kt:238`

- **风险描述**：`/open-android-intelligence/v2/events` 路径常量在两处各定义一份，改协议基路径时必然漏改一处，导致 WS 与 SSE 打到不同路径（签名目标不一致、鉴权失败且难以定位）。`PROTOCOL_HEADER = "2.0"` 同样重复。
- **修复方案**：在 `gateway-client` 内定义单一常量对象并两处引用：
```kotlin
object GatewayPaths {
    const val EVENTS = "/open-android-intelligence/v2/events"
    const val PROTOCOL = "2.0"
}
```

### 13. [建议] [性能] `GatewayWebSocketTransport.kt:196-215,290-310`

- **风险描述**：`InputStream.read()` **逐字节**调用，握手头与每帧头部解析都是一次 syscall/字节。长连接高频 delta 下是纯粹的 CPU 与 syscall 浪费。
- **修复方案**：`val inputStream = socket.getInputStream().buffered()`（`BufferedInputStream` 的预读不会丢失后续帧数据，握手与帧解析从同一对象读取即可）。

### 14. [建议] [性能/健壮性] `SseParser.kt:59-77`

- **风险描述**：每解析一帧就 `toByteArray()` + `reset()` + 回写剩余字节，单帧 O(n) 拷贝、连续帧 O(n²)；且 `buffer` **无上限**，服务端只发不带空行的数据即可让手机 OOM。
- **修复方案**：改用 `ByteBuffer` / `System.arraycopy`，并设置上限：
```kotlin
if (buffer.size() > MAX_BUFFERED_BYTES) throw IOException("SSE_BUFFER_OVERFLOW: no frame terminator")
```

### 15. [建议] [契约不严谨] `GatewayEventDecoder.kt:138,188-190`

- **风险描述**：`CONVERSATION_ID_KEYS = listOf("conversationId", "conversation_id", "chat_id")` 三选一，是「猜测输入」而非还原契约；`chat_id` 在别的系统里常指账号级会话标识，一旦命中就会把事件错误路由到当前线程。`parseOccurredAt` 缺失时回落 `System.currentTimeMillis()`，用本地时钟冒充服务端时间。
- **修复方案**：只认契约字段 `conversationId`；`occurredAt` 缺失时返回 `null` 由上层忽略排序，而不是造一个时间戳。

### 16. [建议] [重复投递] `GatewayHttpClient.kt:102-103,130-134`

- **风险描述**：同一轮 `while` 里，WS 阶段已把 cursor 推进并写入 `cursorStore`，但本地 `cursor` 变量不更新，紧随其后的 SSE 回退仍用**旧 cursor** 请求 → 服务端从旧点重放，SSE 通道收到一批重复事件。
- **修复方案**：SSE 分支前重新读取：`val sseCursor = cursorStore.load(profile.accountId)?.takeIf { CURSOR_ALPHABET.matches(it) }`。

### 17. [建议] [协议细节] `GatewayWebSocketTransport.kt:252-254`

- **风险描述**：Pong 直接回显 Ping 的完整 payload，而 RFC 6455 规定控制帧 payload ≤ 125 字节；服务端发超限 Ping 时客户端会发出非法帧被断开。
- **修复方案**：`sendFrame(output, OPCODE_PONG, payload.copyOf(minOf(payload.size, 125)))`

### 18. [建议] [内存] `WorkbenchController.kt:96,371-378`

- **风险描述**：`historicalAttachments` 与 `attachmentSelections`（后者持有完整图片字节 `sel?.bytes`）只增不减，会话内反复发图会持续驻留内存。
- **修复方案**：`openThread` 时清理与旧会话无关的条目，或改用带容量上限的 `LinkedHashMap(removeElderEntry)`。

---

## 四、无显式风险的模块

- `apps/android/gateway-client/.../http/GatewayConnectionFactory.kt`：无显式风险。scheme 白名单 + 禁用重定向 + 超时设置完整。
- `apps/android/platform-kernel/.../kernel/PairingGrantState.kt`：无显式风险。`URL` → `URI` 的替换达成了避免隐式联网解析的目的，`runCatching` 兜底与 `isNullOrBlank` 判空正确（唯一副作用是大写 scheme 现在会被拒，属可接受的行为收紧）。
- `apps/android/gateway-client/.../events/SseParser.kt` 的 `findTerminator`：修正后的 CRLF/LF 组合判定与越界检查逻辑正确，无显式风险（性能问题见第 14 条）。

---

## 五、建议修复顺序

1. 阻断 2（WS 正常关闭后不重连）与阻断 1（阻塞读不可取消）—— 直接影响「断线自愈」这一本次提交的核心目标。
2. 阻断 3（WS 缺失 TLS 主机名校验）—— 安全后门，必须先于任何真机验证修复。
3. 阻断 4（SSE 停滞无检测）—— 补看门狗，与 WS 通道共用一套超时语义。
4. 严重 5/6（帧长度与握手校验）—— 补齐 RFC 6455 客户端的协议边界。
5. 严重 7/8/9（并发与状态真伪）—— 收敛到单线程状态所有权，并去掉本地伪造终态。
6. 严重 10/11（Python 侧队列与生命周期、附件异常与清理）。
7. 建议项随后续重构一并处理。
