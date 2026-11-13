# 根因分析与修复：应用从后台返回前台后事件流不再更新

日期：2026-09-22
范围：Android `app` · `conversation-ui` · Hermes 插件宿主事件投递 · Gateway Protocol v2 §9
现象（用户实测，宿主 = 真实 Hermes Agent 内的 `integrations/hermes` 插件，设备 SM-X710 / API 36）：

1. 应用切到后台、隔一段时间再回到前台，界面上**不再出现任何新消息**；
2. 必须把 App 从后台任务里清掉、重新打开，才能看到 Agent 在后台期间产生的消息。

结论先行：**通道没有断，是帧被静默丢弃，而 App 没有任何察觉机制**。宿主的每个订阅者是一个容量 100 的有界队列，队列满即丢帧，补偿方式只有「客户端带游标重放」；而 App 侧只在**通道确实断开**或**健康度从 `LIVE` 掉下来**时才重连/补拉。手机在后台被冻结时心跳照旧，健康度一直停在 `LIVE`，于是重放与补拉都不会发生。

---

## 一、根因链条

### 1. App 完全没有前后台感知

`apps/android` 全仓不存在 `ProcessLifecycleOwner` / `LifecycleEventObserver` / `onStart` / `onStop` 的任何使用（`SmsSyncJobService.onStopJob` 之类的命中与可见性无关）。`MainActivity` 只有 `onCreate` 与启动时的 `restoreSessionIfAvailable()`；`WorkbenchScreen` 也没有在退后台时取消订阅的 `DisposableEffect`。事件订阅是进程级、账号级的，与「用户是否看得见」无关。

### 2. 订阅只有一个入口，且不会在回前台时被重开

```text
private fun observeThreadEvents() {                 // 修复前：WorkbenchController.kt
    if (closed) return
    if (eventJob?.isActive == true) return          // ← 只要订阅还活着，再调用就是空转
    eventJob = scope.launch { repository.observeEvents(scopeFactory()) ... }
}
```

`GatewayRuntime.teardown()` 只在登出时取消会话作用域，因此回到前台没有任何路径去关掉并重开这条订阅（修复后这个入口多了一个 `windingDown: Job?` 参数用于串行交接，见第三节）。

### 3. App 侧唯一的「可能漏事件」补偿只在健康度跃迁时触发

```text
private fun observeStreamHealth() {                 // WorkbenchController.kt:434
    ...
    source.streamHealth.collect { health ->
        val previous = _state.value.streamHealth
        update { it.copy(streamHealth = health) }
        if (previous == StreamHealth.LIVE && health != StreamHealth.LIVE) {
            activeThreadId?.let(::reloadTimeline)   // ← 唯一的时间线补偿入口
        }
        ...
    }
}
```

`reloadTimeline`（权威快照补齐）只在 `LIVE → 非 LIVE` 这一跃迁上发生。健康度若始终是 `LIVE`，一次都不会拉。

### 4. 宿主侧是「有界队列 + 静默丢帧」，且指望客户端重放

```1675:1688:integrations/hermes/open_android_intelligence_gateway/adapter.py
    def _enqueue_frame(self, account_id: str, frame: bytes) -> None:
        """A subscriber whose queue is full is skipped rather than blocking the
        agent turn: every frame carries a durable event id, so the phone resumes
        from its last complete frame and replays the gap instead of losing it."""
        for queue in list(self._event_subscribers.get(account_id, ())):
            try:
                queue.put_nowait(frame)
            except asyncio.QueueFull:
                logger.warning(
                    "[open_android] Event subscriber queue is full; client will resume from cursor"
                )
```

队列容量 `SSE_QUEUE_SIZE = 100`（`adapter.py:422`）。SSE 每 15s 写一次注释心跳 `: ping`、WebSocket 用 `WebSocketResponse(heartbeat=15.0)`，**心跳与事件共用同一个 writer**：手机不消费 → writer 阻塞 → 队列堆满 → 后续帧被丢弃，客户端零信号。

### 5. 客户端只在「一个字节都读不到」时才会重连重放

`GatewayTransport.SSE_IDLE_TIMEOUT_MILLIS = 20_000`、`GatewayWebSocketTransport.READ_TIMEOUT_MILLIS = 20_000`，只有在 20 秒完全没有字节时才判定 `STALLED`；`GatewayHttpClient.events()` 的 `consecutiveFailures` 也只在**收到事件**时归零（`maxConsecutiveFailures = 6`）。

### 失败链条（合起来就是用户看到的现象）

手机进后台被冻结/降级（进程冻结、Doze、App Standby）→ socket 一般不断开，但手机不再消费 → 宿主 writer 阻塞 → 100 帧队列溢出 → 后台期间的事件被静默丢弃（客户端零信号）→ 回到前台后手机继续读到心跳与已缓冲帧，`streamHealth` 始终停在 `LIVE`：

- 不重连 ⇒ **不会**带游标重放被丢弃的帧；
- 不触发 `LIVE → 非 LIVE` 跃迁 ⇒ **不会** `reloadTimeline` 拉权威快照。

时间线于是永久停在旧状态，直到进程被杀重开——重开时 `restoreSessionIfAvailable()` 重建会话与订阅，从游标把积压一次读回。

---

## 二、修复方案

**把「回到前台」当作一次与「健康度从 LIVE 掉下来」等价的、可检测的缺口事件**，执行与既有补偿完全同构的两个动作：

1. 从游标重开账号级事件订阅，让宿主重放后台期间遗漏的帧；
2. 重新拉取当前会话的权威时间线快照（沿用既有 `retryTimeline()`）。

**通道先于快照**：快照在替代订阅已被请求之后才发起，是两次读取里更晚的那一次，因此也覆盖「交接本身丢了一帧」的情形。不新增机制、不改协议 schema、不改宿主、不改 UI 结构与去重规则。

## 三、代码改动点

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `apps/android/conversation-ui/.../state/WorkbenchController.kt` | 新增 `onForegrounded()`（1597）：先 `restartEventStream()`，再复用既有 `retryTimeline()`（494） | 重新同步入口；不新造第二条快照拉取路径 |
| 同上 | 新增 `restartEventStream()`（1610）与 `observeThreadEvents(windingDown: Job?)`（1638）：开新订阅前取消并等待被替换的订阅 | 守住既有约束「同一账号同一时刻只有一个订阅」（旧流与新流重叠曾是重复事件来源） |
| 同上 | 常量 `HANDOVER_TIMEOUT_MILLIS = 2_000L`（2269）：交接等待**有界** | 取消经 flow 自身的完成回调关 socket，实测等待是毫秒级；万一传输层未遵守取消，也不能因此永久失去通道（超时后的短暂重叠由传输层与会话层去重覆盖） |
| `apps/android/app/.../GatewayRuntime.kt` | 新增 `onAppForegrounded()`（94）：仅在 `_controller` 存在时转发 | 没有会话的工作台不存在，不伪造状态 |
| `apps/android/app/.../OpenAndroidIntelligenceApplication.kt` | `onCreate` 注册 `ProcessLifecycleOwner` 的 `DefaultLifecycleObserver`，`onStart` 转发（107） | 可见性是进程事实，不是单个 Activity 的事实；冷启动的 `ON_START` 派发发生在 Activity 启动阶段，早于 `LaunchedEffect(Unit)` 触发的会话恢复，因此那时 `_controller` 必为 `null`，天然 no-op |
| `apps/android/app/build.gradle.kts` | 新增 `androidx.lifecycle:lifecycle-process:2.8.7`（72） | 平台标准「应用可见性」原语，与既有 lifecycle 2.8.7 同版本 |
| `apps/android/conversation-ui/src/test/.../WorkbenchForegroundResumeTest.kt` | 新增 6 个用例 | 锁住重开订阅、串行交接（永不重叠）、权威时间线补齐、重放不重复上屏、无活动会话时的退化路径、关闭后 no-op |

重开订阅会重放游标之后的帧，但**不会重复上屏**：传输层 `GatewayHttpClient.deliveredEventIds` 在网络边缘丢弃重放帧，会话层 `WorkbenchController.handledEventIds` 再按 event id 只应用一次，流式/确认消息另有 `mirroredRevisions` 单调规则。

## 四、验证

### 已执行：本机最小必要测试（全部通过）

```text
:conversation-ui:testDebugUnitTest
  WorkbenchForegroundResumeTest       tests=6  failures=0   # 本次新增
  WorkbenchSendRegressionTest         tests=36 failures=0
  WorkbenchTitleRegressionTest        tests=6  failures=0
:app:compileFullDebugKotlin           BUILD SUCCESSFUL      # 新增依赖与 app 层改动可编译
:app:testFullDebugUnitTest            BUILD SUCCESSFUL      # 架构边界守卫仍通过
```

### 待执行：真机回归清单（**尚未执行**，需真机 + Hermes 才能完成）

1. 前台发一条会触发多帧回复的消息，确认回复正常到达；
2. 切到后台停留 ≥2 分钟，期间让 Agent 产出回复；
3. 回到前台**不杀进程**，时间线应在数秒内自动补齐；
4. logcat 过滤 `GatewayEvents` / `GatewayWs` / `GatewaySse`：应看到回前台时的重连与 `event stream live`；宿主的 `queue is full` warning 仍会出现（那是宿主侧的正常日志），但界面不再停滞；
5. 回归：连续多次前后台切换不产生重复消息。

> 以下两条本次也**没有**自动化覆盖，只做了代码审查与编译验证：`ProcessLifecycleOwner` 的观察者注册（Android 框架行为，app 模块没有对应的 JVM 测试）；`HANDOVER_TIMEOUT_MILLIS` 的超时分支（需要模拟一个不响应取消的传输层）。

## 五、未纳入本次的观察与已知限制

- **交接窗口（毫秒级、可自愈）**：传输层先推进游标再 emit（`GatewayHttpClient.kt` 的 `cursorStore.save` → `markEventDelivered` → `emit`），其 KDoc 明说取消发生在 emit 中途会丢该帧。本修复把快照排在重开之后以缩小该窗口，但没有彻底消除：一个恰在取消瞬间提交、且此前已推进游标的事件，只能靠下一次快照补齐。
- **游标过期（`CURSOR_EXPIRED`）路径未改动**：若后台间隔超过宿主的事件保留期，重开的流会拿到 410，现有行为（6 次重试后 `FAILED` + 1s 重试循环）保持不变；此时用户仍能通过快照看到权威内容，但实时通道不会自动重建。契约 §9 要求「先重建资源快照，再使用响应给出的新游标恢复流」，这条完整链路属独立课题。
- **宿主侧的静默丢帧仍是零信号**：队列溢出对客户端完全不可见（既没有 gap 通知，也没有事件间隔信号）。本修复是客户端承认这一事实并主动补齐，而不是改变宿主的投递语义。
- **不改任何 `gateway-contract/schemas/*.schema.json`**：core schema 摘要不变，不需要 App 与插件同步升级。
- 成本 = 每次回前台一次握手 + 一次游标重放（重放帧基本被去重丢弃）+ 一次时间线拉取；不引入轮询、定时器、常驻服务或 wake lock，符合「后台可靠性尽力而为、不维持常驻前台服务」的既有边界。

## 六、两轴复审后的修订

本改动在首次提交（`39ac4af`）后跑了一次独立的两轴复审（标准轴 = 仓库规范 + smell 基线；规格轴 = 用户需求 + 契约 §9）：

- **采纳**：`onForegrounded()` 改为复用既有 `retryTimeline()`（消除与它逐字重复的快照拉取表达式）；重开订阅排在快照之前（缩小交接窗口）；交接等待加上 `HANDOVER_TIMEOUT_MILLIS` 上界（原实现无界等待，若传输层不遵守取消会永久失去通道）；去掉 `eventJob = null` 的中间态，避免出现「无 `windingDown` 的第二订阅窗口」。
- **撤销**：先前在 `observeStreamHealth()` 中新增的「健康度回到 `LIVE` 时清除 `EVENTS_FAILED:` 提示」被整体撤回。提示在界面上本就是一次性消费（`WorkbenchScreen` 的 `LaunchedEffect(state.notice)` 会 `dismissNotice()` 并转成 snackbar），而 `streamHealth` 本身在恢复时就会被刷新，因此该改动既非本 bug 必需，又改变了既有提示语义，与用户要求 3「不改变现有功能设计」相抵。
- **保留为已知限制**：游标过期路径、交接窗口的残余竞态、超时分支与 app 层装配的无自动化覆盖，已如实写进第五节与第四节的「待执行」说明。
