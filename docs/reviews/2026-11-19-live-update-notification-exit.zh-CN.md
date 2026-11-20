# Android 实时进度通知（Live Update）终态退出与防复活修复说明

复现基线为 `dev@82c9e342`，对应 Issue #210。

## 根因定位

- **终态事件与 `generationId` 不一致**：`RemoteTimelineProjection.terminal(id)` 未通过 `generationAliases` 解析别名；`RemoteConversationRepository.submitMessage` 在 `turn/start` 早于空 `turn.id` 的 `prompt` 回执到达时未解析已有别名；ACP `prompt_completed` / `prompt_failed` 省略 `generationId` 时未回退记录终态；非本机发起或重连观测到的轮次结束后未向通知宿补发终态状态事件。
- **通知状态机轮次键分裂与幽灵空轮次**：`DELIVERED`、`REPLY_DELTA`、`REPLY_COMPLETED` 与 `COMPLETED` 若 `generationId` 不一致或缺失，或前后服务端 `messageId` 变更导致 `legacy` 摘要漂移，`activeRounds` 会保留空轮次，使 `withRemainingRounds` 将状态回落为 `RUNNING` 并清空回复正文，进而触发 Live Update 默认占位文案（`notif_live_update_preview_running`）。此外，`ReplyCompletionObserver.acceptLegacy` 在 `status(COMPLETED)` 先于 `REPLY_COMPLETED` 到达时会过早移除 `legacy.current` 槽位。
- **滞后对账与断线回放复活进度通知**：`NativeConversationNotifications.reconcile` 与 `accept` 缺少单调终态屏障（Terminal Barrier），对账窗口或重连回放的旧 `running` / `delta` / `queued` 帧会反向覆写已终结会话，且工作区切换或断网时未隔离并标记相关端点轮次为断连。
- **Live Update 退出与普通回复通知交接**：`AndroidReplyNotificationPublisher.update` 在退出进行态时需先彻底取消 Live Update 渠道常驻通知，再按前后台抑制策略和用户通知设置决定是否发布可滑动清除的普通回复通知，防止前后台切换或权限状态变更导致常驻通知残留。

## 客户端收敛方案

- **多后端事件归一化**（[RemoteTimelineProjection.kt](../../apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/remote/RemoteTimelineProjection.kt)、[RemoteConversationRepository.kt](../../apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/remote/RemoteConversationRepository.kt)）：支持 Harness 别名终态反查、ACP 外源多轮流式与无 `generationId` 终态兜底、非本机轮次补发 `COMPLETED`/`FAILED`/`CANCELLED` 终态事件，并在 Codex/Harness 首页时间线权威快照返回时触发 `onTimelineSnapshot` 对账收口（含本地仍停在 `RUNNING`/`QUEUED` 的恢复场景）。
- **单调终态屏障与跨 key 对齐**（[NativeNotificationInput.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NativeNotificationInput.kt)、[NativeConversationNotifications.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt)、[ReplyNotifications.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt)）：引入 `clientMessages` / `roundAliases` 将跨 `generationId`、`message.id` 与 `clientMessageId` 事件收敛到单一规范轮次（覆盖流式后先终态再跨 key 补正文场景）；清理已消费或更早空占位轮次并避免同毫秒或时钟偏差误杀并发新轮；在 `reconcile` 与 `accept` 建立单调终态屏障屏蔽滞后 `running` 快照与旧帧重放；在 `ReplyCompletionObserver.acceptLegacy` 增加 `legacy.awaiting` 槽位并以 `clientMessageId` 优先生成稳定摘要，支持乱序完成。
- **原子退出交接与保活/对账收敛**（[ReplyNotifications.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt)、[RemoteRuntime.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteRuntime.kt)）：非 `QUEUED`/`RUNNING` 状态优先撤销 Live Update，随后按抑制规则与开关策略互斥投递可滑动清除的普通通知（终态早于正文到达时不闪烁空占位通知，断连通知不锁死为常驻 `ongoing`）；工作区退休与断网时通过 `disconnectEndpoint` 标记活跃轮次断连并停止保活，重连或周期轮询拉取权威快照对账收敛至 `ENDED`。

## 验证范围

- 单元测试与状态机时序回归覆盖：`NativeConversationNotificationsTest`、`LiveUpdateNotificationsTest`（API 36 Robolectric）、`ReplyNotificationsTest`、`RemoteCompletionNotificationTest`（Hermes/ACP、Harness、Codex）、`RemoteRuntimeTest` 及 `RemoteConversationRepositoryTest`。
- 覆盖场景包括：标准双事件顺序与乱序到达、`generationId` 缺失或不一致、终态后滞后快照对账与重连回放、同会话多轮连续提问、前后台可见态切换互斥交接以及断网重连保活收敛。
- 实机系统级通知栏视觉展示与多厂商 ROM Live Update 样式仍建议结合真机联调确认。
