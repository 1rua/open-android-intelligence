# 原生消息气泡与回复完成提醒恢复

读者：PR 审查者与 Android 验收执行者。状态：实现完成，自动化测试通过；模拟器端到端验收尚未执行。

## 评审意见处理（2026-11-08 第二轮）

PR #99 的自动评审提出 5 条问题，逐条核实后全部成立并已修复，每条都补了对应测试。

| 评审意见 | 核实结论 | 修复 | 新增测试 |
| --- | --- | --- | --- |
| 原生气泡在 session 未就绪时先绑定一次，之后就永远停在列表转圈 | 成立。`firstBind` 用 `savedState[CONVERSATION] == null` 判断，第一次绑定（session 为 null）就把它写掉了 | 改为独立的 `bubbleLaunchSelectionPending` 标记（存在 saved state 里），只在真正拿到 session 的那次绑定消费 | `BubbleConversationTest.nativeBubbleOpensTheNotificationConversationWhenItsSessionArrivesLate` |
| 事件同步路径把宿主正文帧全丢掉，只发布 `native-history:` 快照，导致别的客户端发起的轮次永远不提醒 | 成立。`NativeEventSynchronizer` 对 delta/completed 直接 `Unit`，正文只来自 `recoverySnapshot` 的合成事件 | 事件页上的 delta/completed 解码后随页一起发布（`liveBody`），落盘仍只写权威历史一次以避免重复行；历史已判删除的消息不转发 | `NativeEventSynchronizerTest.hostBodyFramesArePublishedOnceForTheNotificationLayerWhileHistoryStaysSynthetic` |
| 修订围栏没带 Agent，另一个 Agent 同名消息会被判成过期 | 成立。`revisionIdentity` 只拼 `conversationId + messageId` | 改用 `target.identityKey()`；平台旧帧没有 Agent，摘要不变 | `NativeConversationNotificationsTest.anEqualOrHigherRevisionUnderAnotherAgentIsNotStale` |
| 切 Agent 期间点通知，会在旧工作台上打开并误报成功 | 成立。`selectedAgentId` 同步更新，`_controller` 仍是上一台 | 新增 `activeWorkbenchAgentId`（只有真正发布的工作台才更新），并用 `nativeNavigationWaitsForWorkbench` 判定；未完成则排队，由工作台发布处补做 | `NativeNotificationNavigationTest`（2 项） |
| 重连后 Agent 尚未发现时核对返回 null/空，被当成“权威空闲” | 成立。`currentGeneration` 的 null 语义是宿主回答空闲，未发现 Agent 时并没有读到任何事实 | 新增 `requireDiscoveredAgent`，未发现即抛 `AGENT_UNAVAILABLE`，核对器按失败处理并保留重试 | `NativeNotificationReconcilerTest.anUndiscoveredAgentFailsTheReadInsteadOfProvingIdleness`、`aFailedReadKeepsTheTaskRunningAndRetryable` |

## 结论

原生 `/agents` 会话（OAI Core 的当前唯一会话入口）从来没有把会话事件接进提醒层，导致：

1. 气泡没有会话列表可读（旧 `/conversations` 路由在 Core 上已不存在）；
2. 任务完成后不会产生系统通知（提醒层只订阅平台 `/v2/events`，而原生正文与状态只存在于宿主账本）；
3. 终态核对仍调用旧 `generations/current` 与时间线接口，必然失败。

本次不新建第二套消息或通知系统：把同一份已经校验、已经落盘的原生事件翻译成提醒层认识的平台帧，并让气泡复用同一套会话 UI 组件，只额外持有自己的控制器、草稿与事件读取身份。

## 根因与修改

| 根因 | 证据 | 修改 |
| --- | --- | --- |
| 原生事件从不进入 `NativeConversationNotifications` | `CoreAgentConversationRepository.publishEvent` 只写 `_events`；全仓无其他调用方 | 新增 `NativeNotificationBridge.nativeNotificationFrame`，把 `TimelineUpsert`（助手正文）、`MessageStatus`、`GenerationCancelled`、`TitleUpdated`、会话删除翻译为平台帧，并新增 `onEventPublished` 出口 |
| 原生会话 ID 只在单个 Agent 内唯一 | `ReplyNotificationTarget` 只有三要素；`NativeConversationNotifications` 的去重键是 `conversationId(+generation)` | 目标增加可选 `agentId`；通知摘要、Live Update 摘要、已删除与终态持久键统一改用 `identityKey()`；UI 路由由 3 段扩展为可选 4 段，旧 3 段格式与摘要完全不变 |
| 气泡在原生模式下拿不到工作台 | `BubbleConversationWorkspace` 只按会话 ID 建 `createBubbleWorkbench`（旧 HTTP 仓库） | 原生目标改走 `buildNativeBubbleWorkbench`：同一镜像、同一套 `WorkbenchController`/`ComposerBar`/`TimelineDisplayRow`，独立草稿与导航 |
| 主界面与气泡会争用同一个原生事件读取者 | 读取游标按 `observer+conversation` 存储，ACK 校验归属，冲突时报 `NATIVE_EVENT_OWNER_CHANGED` | 新增 `NativeConversationStoreView`：复用同一镜像写入，只切换到气泡自己的读取者身份 |
| 终态核对用旧路由 | `NotificationConversationReconciler` 调 `/conversations/{id}/generations/current` | 原生模式改用 `NativeNotificationReconciler`（`/agents/.../generation` + `/history`），旧链路保持原实现 |
| 冷启动重放或回执回读会重复提醒 | 仓库会合成 `native-history:` / `native-read:` / `native-receipt:` / `native-status:` / `native-cancel:` / `history:` 事件 | 桥接层只放行宿主真实发出的帧 |
| 点击通知不切 Agent | `openReplyNotification` 只 `openThread` | 目标带 Agent 时先切到该 Agent，切换完成后补做一次导航；气泡则用自己的控制器直接打开，不打断主界面 |

## 边界与兼容

- 同一消息不会因为主界面与气泡同时订阅而重复提醒：提醒来源是仓储的单一 `onEventPublished` 出口，气泡与主界面共用同一个 Agent 仓储；提醒层仍保留原有持久围栏。
- 平台旧链路（`GatewayConversationRepository` + `/v2/events`）的全部键、摘要与 Intent 形状保持不变：3 段路由、`conversationId` 级去重都只在没有 Agent 身份时使用。
- 通知绑定账号：`bind(profileId, accountId)` 与 `eraseAccount` 语义未变；Agent 身份只作为附加维度。
- 底层消息系统未改动：气泡和主界面共享同一份加密镜像、同一份会话数据与渲染组件。

## 自动化结果

| 检查 | 结果 |
| --- | --- |
| `:app:compileFullDebugKotlin` | 通过（JDK 17 编译） |
| `NativeNotificationBridgeTest`（6 项） | 通过：帧形状、Agent 身份、错误码、删除、附件、合成事件不提醒 |
| `NativeNotificationRoutingTest`（3 项） | 通过：同会话 ID 跨 Agent 各自提醒；重放不顶替；旧 3 段路由不变 |
| `NativeNotificationNavigationTest`（2 项，本轮新增） | 通过：选中但未发布的工作台仍需等待；目标 Agent 的工作台发布后才算交接完成 |
| `NativeConversationStoreViewTest`（2 项） | 通过：两读取者游标互不覆盖，内容仍共享 |
| `NativeNotificationReconcilerTest`（5 项，本轮新增 2 项） | 通过：空闲只判定结束且不提醒；仍在运行时不误判结束；读期间换轮不采信；未发现 Agent 按读失败处理；读失败保留运行状态与重试 |
| `NativeEventSynchronizerTest`（21 项，本轮新增 1 项） | 通过：宿主正文帧随页发布、落盘仍只写权威历史一次；删除、回执、游标与所有权用例不变 |
| `BubbleConversationTest` / `BubbleAttachmentDraftCoordinatorTest` / `ConversationAttachmentPickersTest` | 通过（新增“session 迟到仍进入通知会话”用例） |
| `NativeConversationNotificationsTest` / `ReplyNotificationsTest` / `LiveUpdateNotificationsTest` | 通过（新增“跨 Agent 同修订不判过期”用例；Robolectric SDK 36 用例需 JDK 21） |
| `:app:testFullDebugUnitTest` 全量 | 第二轮改动后重跑，结果见 PR 评论；缺 Hermes 夹具时会出现环境性失败，补齐 `HERMES_PLUGIN_ROOT`、`HERMES_SDK_ROOT`、`OAI_HERMES_PYTHON=.toolchains/pr64-python/bin/python`、`PYTHONPATH=.toolchains/py-test-deps` 后复跑 |

注意两点环境前提，都不是本次改动引入：

1. 本机默认 JDK 17 无法创建 SDK 36 的 Robolectric sandbox，需设
   `JAVA_HOME=~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`。
2. 工作树不含 `plugins/target/**/*.wasm`（被 `.gitignore` 排除，属于构建产物），
   整包测试跑 `DeviceExecutionRecoveryTest` 一类用例前需要从主检出复制这些
   `.wasm`，否则失败原因是 `FileNotFoundException` 而不是断言。

## 未覆盖

- 未在模拟器或真机执行：系统气泡授权后的展开、切换、后台收发、锁屏通知、Android 16 Live Updates 降级、账号切换与重启后的去重。
- 未用真实 Core + 真实 Agent 宿主完成一轮“发送 → 完成 → 通知 → 点击进入原生气泡”的端到端验收。
- 物理手机、厂商省电策略与真实 Live Updates 展示仍未验收。
