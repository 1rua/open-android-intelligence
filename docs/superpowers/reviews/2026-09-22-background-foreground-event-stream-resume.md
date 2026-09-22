# 根因分析与修复：App 从后台返回前台后事件流不再更新

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
private fun observeThreadEvents() {
    if (closed) return
    if (eventJob?.isActive == true) return        // ← 只要订阅还活着，再调用就是空转
    eventJob = scope.launch { repository.observeEvents(scopeFactory()) ... }
}
```

第一道判断就是 `if (eventJob?.isActive == true) return`：只要订阅还活着，再调用也是空转。`GatewayRuntime.teardown()` 只在登出时取消会话作用域，因此回到前台没有任何路径去关掉并重开这条订阅（修复后这个入口多了一个 `windingDown: Job?` 参数用于串行交接，见第三节）。

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

1. 重新拉取当前会话的权威时间线快照（沿用既有 `reloadTimeline`）；
2. 重开账号级事件订阅，让宿主按游标重放后台期间遗漏的帧。

顺序与契约 §9 一致（「客户端先重建资源快照，再使用响应给出的新游标恢复流」）。不新增机制、不改协议 schema、不改宿主、不改 UI 结构与去重规则。

## 三、代码改动点

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `apps/android/conversation-ui/.../state/WorkbenchController.kt` | 新增 `onForegrounded()`（1604）与 `restartEventStream()`（1617）；`observeThreadEvents(windingDown: Job?)`（1642）在开新订阅前 `cancelAndJoin` 被替换的订阅 | 重新同步入口；串行交接守住既有约束「同一账号同一时刻只有一个订阅」，避免旧流与新流重叠 |
| 同上 | `observeStreamHealth()` 在 `health == LIVE` 时清除以 `EVENTS_FAILED:` 开头的提示（452） | 恢复后不得继续谎报「实时通道已断开」；只退休通道自己的提示，重命名失败等提示不受影响 |
| `apps/android/app/.../GatewayRuntime.kt` | 新增 `onAppForegrounded()`（94）：仅在 `_controller` 存在时转发 | 没有会话的工作台不存在，不伪造状态 |
| `apps/android/app/.../OpenAndroidIntelligenceApplication.kt` | `onCreate` 注册 `ProcessLifecycleOwner` 的 `DefaultLifecycleObserver`，`onStart` 转发（107） | 可见性是进程事实，不是单个 Activity 的事实；冷启动的 `ON_START` 到达时 `controller` 仍为 `null`，天然 no-op |
| `apps/android/app/build.gradle.kts` | 新增 `androidx.lifecycle:lifecycle-process:2.8.7`（72） | 平台标准「应用可见性」原语，与既有 lifecycle 2.8.7 同版本 |
| `apps/android/conversation-ui/src/test/.../WorkbenchForegroundResumeTest.kt` | 新增 8 个用例 | 锁住重开订阅、串行交接、权威时间线补齐、重放不重复上屏、恢复后的提示语义、关闭后 no-op |

重开订阅会重放游标之后的帧，但**不会重复上屏**：传输层 `GatewayHttpClient.deliveredEventIds` 在网络边缘丢弃重放帧，会话层 `WorkbenchController.handledEventIds` 再按 event id 只应用一次，流式/确认消息另有 `mirroredRevisions` 单调规则。

## 四、验证

### 本机最小必要测试（全部通过）

```text
:conversation-ui:testDebugUnitTest
  WorkbenchForegroundResumeTest       tests=8  failures=0
  WorkbenchSendRegressionTest         tests=36 failures=0
  WorkbenchTitleRegressionTest        tests=6  failures=0
:app:compileFullDebugKotlin           BUILD SUCCESSFUL   # 新增依赖与 app 层改动可编译
:app:testFullDebugUnitTest            BUILD SUCCESSFUL   # 架构边界守卫仍通过
```

### 真机闭环（R52X909R9QT + Hermes）

1. 前台发一条会触发多帧回复的消息，确认回复正常到达；
2. 切到后台停留 ≥2 分钟，期间让 Agent 产出回复；
3. 回到前台**不杀进程**，时间线应在数秒内自动补齐；
4. logcat 过滤 `GatewayEvents` / `GatewayWs` / `GatewaySse`：应看到回前台时的重连与 `event stream live`；宿主的 `queue is full` warning 仍会出现（那是宿主侧的正常日志），但界面不再停滞；
5. 回归：正常前后台切换不产生重复消息。

## 五、影响面与未纳入本次的观察

- 不改任何 `gateway-contract/schemas/*.schema.json`，core schema 摘要不变，不需要 App 与插件同步升级。
- 成本 = 每次回前台一次握手 + 一次游标重放（重放帧基本被去重丢弃）+ 一次时间线拉取；不引入轮询、定时器、常驻服务或 wake lock，符合「后台可靠性尽力而为、不维持常驻前台服务」的既有边界。
- 宿主侧仍是一个可以单独评估的观察：队列溢出对客户端**完全不可见**（既没有 gap 通知，也没有事件间隔信号），本修复是在客户端承认这一事实并主动补齐，而不是改变宿主的投递语义。
