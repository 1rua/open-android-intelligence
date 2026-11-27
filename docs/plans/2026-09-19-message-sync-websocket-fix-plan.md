# 消息同步与 WebSocket 传输缺陷修复执行计划 (Bug Fix Implementation Plan)

- **日期**：2026-09-19
- **依赖输入**：`docs/superpowers/reviews/2026-09-19-message-sync-websocket-auditor-report.md`
- **实施原则**：单文件原子修复、零伪造实现、完备异常保护、单测严格闭环验证。

---

## 一、实施分卷与任务分解

### 任务 1：网关 WebSocket 传输层健壮性与安全增强 (`GatewayWebSocketTransport.kt`)
- **改动目标**：
  1. 协程取消绑定：通过 `currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { socket.close() } }` 确保协程取消时打断阻塞读取。
  2. TLS 安全：采用分层套接字 `(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plainSocket, host, port, true) as SSLSocket`，设置 `endpointIdentificationAlgorithm = "HTTPS"` 启用域名与 SNI 校验。
  3. IPv6 格式化：若 `host` 包含冒号且未包裹中括号，自动包裹 `[$host]`。
  4. 握手与响应头校验：
     - 对 `cursor` 参数执行 URL 编码。
     - 解析 HTTP 响应头，断言 `Upgrade: websocket` 并且 `Sec-WebSocket-Accept` 签名匹配 SHA-1 派生值。
  5. 协议防御：
     - 校验 RSV 位：`(b0 and 0x70) != 0` 时抛出 `IOException("WEBSOCKET_PROTOCOL_ERROR: RSV bits must be 0")`。
     - 64 位长度符号位检查：`payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES` 时抛出异常。
     - Pong 控制帧载荷：截断至 `minOf(payload.size, 125)`。

### 任务 2：HTTP/SSE 传输层阻塞打断与看门狗超时 (`GatewayTransport.kt`)
- **改动目标**：
  1. 协程取消绑定：通过 `currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { connection.disconnect() } }` 在协程取消时关闭底层物理连接。
  2. 看门狗超时：将 `eventStream` 的 `readTimeout` 设为 45 秒（远高于服务端 15 秒的心跳频率），当遭遇 TCP 半开静默挂死时，捕获 `SocketTimeoutException` 并转换为 `IOException("EVENT_STREAM_STALLED: no bytes received for 45000ms")` 触发上层重连。

### 任务 3：网关事件流自愈重连与降级震荡修复 (`GatewayHttpClient.kt`)
- **改动目标**：
  1. 正常关闭自愈：移除 `!streamFailed` 的退出判定，当 `autoReconnect == true` 时，通道正常断开（如空闲超时、网关重启）同样执行带游标的指数退避重连。
  2. 偏好状态维护：新增 `receivedWsEventInAttempt` 标记，仅当 WebSocket 通道真正收到事件时才保留 `preferWebSocket = true`；降级到 SSE 时不因 SSE 收到数据而盲目恢复 WebSocket 偏好。
  3. 降级游标刷新：在回退至 SSE 通道前，从 `cursorStore` 重新拉取最新游标，避免旧游标重放导致事件重复。

### 任务 4：SSE 解析器缓冲区安全上限保护 (`SseParser.kt`)
- **改动目标**：
  1. 设置 `MAX_BUFFERED_BYTES = 10 * 1024 * 1024` (10MB)。
  2. 在 `feedBytes` 前或缓冲区累加时，若超过上限立即抛出 `IOException("SSE_BUFFER_OVERFLOW: frame terminator not found within $MAX_BUFFERED_BYTES bytes")`，防止畸形流引发 OOM。

### 任务 5：会话控制器并发一致性、隔离与流式渲染 (`WorkbenchController.kt`)
- **改动目标**：
  1. 状态 CAS 更新：将私有 `update` 方法修改为 `_state.update(transform)`。
  2. 会话切换任务管理：引入 `timelineJob: Job?`，在 `openThread` 时取消前序任务，并在回调中校验 `if (activeThreadId != threadId) return@launch`。
  3. 严格会话隔离：在 `observeThreadEvents` 中：
     - 若当前无活跃会话（`activeThreadId == null`），刷新会话列表后直接返回。
     - 事件的 `conversationId` 必须严格等于当前 `activeThreadId` 才允许操作当前会话的时间线。
  4. 排队消息精准删除：过滤 `pendingBatch` 时要求 `correlationId.isNotBlank()`，并使用严格匹配 `it.key == "local_$correlationId" || it.key == correlationId`。
  5. 乱序覆盖守卫：更新 `mirrored` 时校验 `message.revision >= (previous?.revision ?: 0L)`。
  6. 停止生成如实呈现：`generationId == null` 时不强行在本地伪造 CANCELLED 消息，而是更新状态为 `GenerationState.UNSUPPORTED` 并提示。
  7. 流式更新合并未决消息：在 `renderTimeline()` 中将 `mirrored` 消息与未被镜像确认的本地 `pendingBatch` 消息按时间戳合并显示，消除发送闪退感。
  8. 附件缓存 LRU 与死引用修复：
     - `historicalAttachments` 改为带容量限制（30条）的 LRU 结构。
     - 修复 `submitWhenAttachmentsVerified` 中 `removeAttachment` 导致 `sel` 恒空的死代码，直接复用已构建的 `submittedAttachments`。
     - 在 `close()` 中清理 `timelineJob`、`attachmentJobs`、`creationJob` 及 `batcher.close()`。

### 任务 6：Compose 滚动像素偏移整型溢出修复 (`WorkbenchScreen.kt`)
- **改动目标**：
  1. 将 `listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)` 修改为 `listState.scrollToItem(totalItems - 1, 0)`。

### 任务 7：Python 网关适配层握手时序与 WebSocket 队列健壮性 (`adapter.py`)
- **改动目标**：
  1. 提前注册广播队列：在 `_handle_ws_stream` 与 `_handle_sse_stream` 中，将广播队列在查询 `event_backlog` 与 `prepare()` 网络握手之前预先注册到广播池中，彻底杜绝握手窗口内的消息漏投。
  2. 多行 SSE 格式解析：修复 `_ws_message_from_queue_item` 中对连续多行 `data:` 的覆盖问题，并遵循 SSE 协议规范仅剥离首个前导空格。
  3. 优雅停止检测：在 WebSocket `send_loop` 中引入带超时的 `queue.get()`，并在 `not self._running` 时主动退出。

---

## 二、验证策略

1. **Gradle 单元测试**：运行 `./gradlew testDebugUnitTest`，确保 `apps/android` 下所有模块（尤其 `gateway-client`、`conversation-ui`、`conversation-data`、`platform-kernel`）100% 通过。
2. **Python 网关测试**：运行 `/home/djbd/.venvs/oai-gateway/bin/pytest`，确保 `integrations/hermes` 下 145 项集成测试全量通过。
3. **针对性边界单测与断言**：
   - 验证协程取消下 Socket 能被正确中断。
   - 验证 TLS 域名校验与 SNI 设置。
   - 验证 64 位 payload 负数抛出 IOException。
   - 验证 WebSocket 握手校验 Upgrade 与 Sec-WebSocket-Accept。
   - 验证 correlationId 为空时不误删本地消息。
   - 验证切会话时旧请求返回不覆盖新会话。

