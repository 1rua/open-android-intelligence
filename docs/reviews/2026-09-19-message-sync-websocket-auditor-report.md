# 消息同步与 WebSocket 传输：首席审计师汇总报告 (Chief Auditor Aggregation Report)

- **审查基准**：`docs/superpowers/reviews/2026-09-19-message-sync-websocket-code-review.md` 与 `docs/superpowers/reviews/2026-09-19-message-sync-websocket-transport-code-review.md`
- **工作流阶段**：Phase 2 Chief Auditor Aggregation & Risk Tiering
- **审计日期**：2026-09-19
- **仲裁准则**：`正确性与契约规范 (Spec) > 系统安全性 (Safety) > 平台规范与可读性 (Standards) > 微基准性能 (Micro-perf)`

---

## 一、审计去重与仲裁综述

通过对前期双通道审查输出进行去重合并与多方共识比对，共识别出 18 项具体问题，归纳为 7 个目标文件分卷。
根据风险等级划分：
- **RISKY（高危/破坏性变更）**：0 项（无公共契约破坏或破坏性架构重构）。
- **CAREFUL（需审慎修复并包含严格单测覆盖）**：14 项（涉及协程取消死锁、长连接重连断言、TLS 域名校验、会话隔离与 CAS 原子性等）。
- **SAFE（零行为副作用修复）**：4 项（涉及滚动像素偏移溢出、Pong 帧长度裁剪、IPv6 方括号与缓冲区上限常量等）。

所有 18 项问题均在自动化测试与受控代码修改范围内，批准进入修复计划制定与分卷实施阶段。

---

## 二、去重问题清单与风险分级

### [CAREFUL] [多方共识] 1. 阻塞式 Socket 读取协程取消死锁与物理线程泄漏
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:71-126` 与 `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt:56-78`
- **仲裁归因**：底层原生 Socket 在无限超时或长期无数据下阻塞在 `InputStream.read()`，协程取消无法唤醒阻塞原生线程，导致物理线程与套接字在页面退出或切换会话时永久泄漏。
- **仲裁方案**：利用 `currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { socket.close() } }` 挂接生命周期；SSE 通道引入 45s 读取看门狗超时，发生停滞时抛出异常驱动上层自愈。

### [CAREFUL] [多方共识] 2. 事件流正常关闭导致客户端永久假死 (断线重连逻辑失效)
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt:171-173`
- **仲裁归因**：对端发送 TCP FIN 或 WS CLOSE 正常关闭连接时，客户端正常退出且 `streamFailed == false`。原代码 `if (!autoReconnect || !streamFailed) break` 导致只要通道正常断开就永久退出循环，事件流假死。
- **仲裁方案**：退出条件收敛为仅在 `!autoReconnect` 时 break，通道任何断开只要 `autoReconnect == true` 均依据最新游标与退避重试。

### [CAREFUL] [多方共识] 3. WebSocket 通道缺失 TLS 主机名校验与 SNI
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:62-66`
- **仲裁归因**：无参 `createSocket()` 连接目标未携带 Host 信息，导致 ClientHello 缺失 SNI 且绕过了标准 HTTPS 主机名校验，存在中间人攻击及 Conscrypt 握手崩溃风险。
- **仲裁方案**：采用分层套接字 `(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plainSocket, host, port, true)` 并启用 `sslParameters.endpointIdentificationAlgorithm = "HTTPS"`。

### [CAREFUL] [多方共识] 4. SSE 连接停滞无超时看门狗兜底
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt:56-78`
- **仲裁归因**：SSE 流将 `readTimeout` 设为 0，网络静默半开连接下无法感知对端宕机，上层 catch 无法触发。
- **仲裁方案**：保留套接字层 45s 超时看门狗，捕获 `SocketTimeoutException` 后抛出明确的停滞异常，通知 `GatewayHttpClient` 执行自愈重连。

### [CAREFUL] [多方共识] 5. 64 位 WebSocket Payload 长度符号溢出与 RSV 标志位未校验
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:211-232`
- **仲裁归因**：符号位为 1 时拼装出负数长度，绕过上限检查后在 `ByteArray` 分配时抛出不可预期的 `NegativeArraySizeException`。且未按 RFC 6455 校验 RSV1-3。
- **仲裁方案**：增加 `(b0 and 0x70) != 0` 拒绝非零 RSV，并在 64 位长度解析后增加 `payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES` 范围校验。

### [CAREFUL] [多方共识] 6. WebSocket 握手响应未校验 Upgrade 与 Sec-WebSocket-Accept 签名及游标注入风险
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:58, 183-193`
- **仲裁归因**：握手仅判定状态行含 101 后丢弃所有响应头，未证明真正升级为 WS。游标未进行 URL 转义存在请求行注入隐患。
- **仲裁方案**：游标参数显式 `URLEncoder.encode`；严格解析响应头并校验 `Upgrade: websocket` 及由 `secWebSocketKey` 派生的 SHA-1 Base64 签名。

### [CAREFUL] [多方共识] 7. 会话切换异步覆盖与 StateFlow CAS 状态更新丢失
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:180-218, 724-726`
- **仲裁归因**：快速切会话时旧异步请求未取消且回调无会话一致性守卫；`update` 使用 `_state.value = transform(_state.value)` 非原子 CAS。
- **仲裁方案**：维护 `timelineJob` 并在切换时主动 cancel 前序加载，回调中断言 `activeThreadId == threadId`；`update` 方法升级为 `_state.update(transform)`。

### [CAREFUL] [多方共识] 8. 空会话 ID 事件穿透与跨会话串门破坏数据隔离
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:555-642`
- **仲裁归因**：无主事件（`eventConvId == null`）绕过会话校验直接修改当前时间线或执行清空。
- **仲裁方案**：收紧隔离逻辑：无活跃会话时直接拦截；仅当 `eventConvId == currentActiveId` 时才允许对时间线状态生效。

### [CAREFUL] [多方共识] 9. 消息乱序覆盖 (Revision 守卫缺失)
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:580`
- **仲裁归因**：`mirrored[message.id] = message` 盲目覆盖，迟到的流式增量会回退已完成的消息状态。
- **仲裁方案**：仅在 `previous == null || message.revision >= previous.revision` 时执行写入。

### [CAREFUL] [多方共识] 10. 待发送消息模糊匹配批量误删
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:559-564`
- **仲裁归因**：`endsWith(event.correlationId)` 在 correlationId 为空字符串时匹配所有条目，造成本地排队消息全量误清空。
- **仲裁方案**：要求 `correlationId.isNotBlank()`，并使用严格等值比对（`it.key == "local_$correlationId" || it.key == correlationId`）。

### [CAREFUL] [多方共识] 11. stopGeneration 缺少 generationId 时本地伪造 CANCELLED 终态
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:487-500`
- **仲裁归因**：无 generationId 时本地强行将 STREAMING 设为 CANCELLED，服务端仍继续推送，违反“取消意图不是结果”与“严禁伪造实现”铁律。
- **仲裁方案**：如实呈现状态，设置 `GenerationState.UNSUPPORTED` 与提示信息，严禁在本地强行抹除消息真实状态。

### [CAREFUL] [多方共识] 12. 流式更新到达导致未决本地发送消息瞬时消失
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:440-449, 665-706`
- **仲裁归因**：`renderTimeline()` 仅依据 `mirrored.values` 渲染，未将 `pendingBatch` 中未被确认的本地条目合并显示，导致网络延迟时发送的消息瞬时闪退消失。
- **仲裁方案**：在 `renderTimeline()` 中合并尚未被服务端镜像的本地 `pendingBatch` 消息。

### [CAREFUL] [多方共识] 13. 历史附件无界驻留 OOM 隐患与关闭清理
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:96, 133-135, 371-378, 420`
- **仲裁归因**：`historicalAttachments` 无界常驻原始字节，切会话不清理导致 OOM；`close()` 未取消附件和批处理任务；第 420 行试图在 `removeAttachment` 之后访问 Map 导致引用恒空。
- **仲裁方案**：`historicalAttachments` 设置 LRU 淘汰（上限 30 条）；在 `close()` 中清理所有协程 Job 与批处理器；复用已构建的附件集合修复恒空死代码。

### [CAREFUL] [多方共识] 14. 网关适配器握手与广播队列注册时间窗丢消息 & WebSocket 处理健壮性
- **文件与行号**：`integrations/hermes/open_android_intelligence_gateway/adapter.py:444-479, 912-1055`
- **仲裁归因**：在握手与 `prepare()` 网络 I/O 期间新产生的广播事件由于未在广播集合中而丢失；多行 SSE 数据解析覆盖与空白被 strip；`send_loop` 无退出检测。
- **仲裁方案**：在查询历史积压与握手前提前注册队列；多行 SSE 数据行按标准格式追加拼接；`send_loop` 增加生命周期退出检测。

### [CAREFUL] [多方共识] 15. 降级状态无条件复位与游标重连
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt:102-105, 166-168`
- **仲裁归因**：SSE 收到事件将 `preferWebSocket` 强行复位为 true，导致不支持 WS 的网络环境在每次重连时反复经历超时震荡；SSE 降级未刷新本地游标。
- **仲裁方案**：仅在 WebSocket 通道实际成功收到数据时保持 WebSocket 偏好；降级至 SSE 时从 store 重新加载最新游标。

### [SAFE] 16. 滚动像素偏移整型溢出
- **文件与行号**：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:85`
- **仲裁归因**：`listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)` 将像素偏移传为 `Int.MAX_VALUE` 触发 32 位有符号运算溢出。
- **仲裁方案**：将像素偏移改为 `0`。

### [SAFE] 17. WebSocket 协议细节与边缘增强 (Pong 长度裁剪与 IPv6 方括号)
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:56, 252-254`
- **仲裁归因**：Pong 控制帧载荷未限制在 125 字节以内；IPv6 地址未包裹方括号导致 Host 头格式不合法。
- **仲裁方案**：Pong 载荷裁剪至 `minOf(payload.size, 125)`；IPv6 Host 增加方括号格式化。

### [SAFE] 18. SseParser 内存溢出防御与上限保护
- **文件与行号**：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt:40-75`
- **仲裁归因**：缓冲区缺乏最大尺寸保护，恶意或异常流在缺失换行终结符时将耗尽内存。
- **仲裁方案**：增加 `MAX_BUFFERED_BYTES = 10 * 1024 * 1024` 上限防护，超出立即抛出明确异常。

---

## 三、文件分卷与修复规划 (File-Level Seams)

1. **分卷一 (Android 网关 WebSocket 传输)**：
   `apps/android/gateway-client/.../ws/GatewayWebSocketTransport.kt`
   覆盖问题：1, 3, 5, 6, 17
2. **分卷二 (Android 网关 HTTP/SSE 传输与看门狗)**：
   `apps/android/gateway-client/.../http/GatewayTransport.kt`
   覆盖问题：1, 4
3. **分卷三 (Android 网关事件客户端重连自愈与降级)**：
   `apps/android/gateway-client/.../http/GatewayHttpClient.kt`
   覆盖问题：2, 15
4. **分卷四 (Android SSE 帧解析器防御保护)**：
   `apps/android/gateway-client/.../events/SseParser.kt`
   覆盖问题：18
5. **分卷五 (Android 工作台状态控制器生命周期、隔离与渲染)**：
   `apps/android/conversation-ui/.../state/WorkbenchController.kt`
   覆盖问题：7, 8, 9, 10, 11, 12, 13
6. **分卷六 (Android 工作台界面滚动)**：
   `apps/android/conversation-ui/.../workbench/WorkbenchScreen.kt`
   覆盖问题：16
7. **分卷七 (Python 网关适配层握手时序与帧转换)**：
   `integrations/hermes/open_android_intelligence_gateway/adapter.py`
   覆盖问题：14

