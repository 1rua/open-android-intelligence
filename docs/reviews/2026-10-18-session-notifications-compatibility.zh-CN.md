# Android Live Updates 与 Samsung Now Bar 兼容性验收

核查日期：2026-10-18。本文只记录平台兼容边界与手工验收计划；当前没有真实 Samsung 设备验收结果。

## 平台边界

- Android 原生 Live Updates 从 Android 16（API 36）开始提供。符合资格的通知须使用 Standard、`BigTextStyle`、`CallStyle`、`ProgressStyle` 或 `MetricStyle`，声明 `POST_PROMOTED_NOTIFICATIONS`、请求 promotion、标记为 ongoing、设置标题，并避开 `RemoteViews`、group summary、`setColorized(true)` 和 `IMPORTANCE_MIN` channel。框架提供的资格检查不等同于系统一定会展示：`hasPromotableCharacteristics()` 不反映用户设置；`canPostPromotedNotifications()` 才查询 promotion 设置。用户可暂时关闭或降级 Live Update，OEM 也可增加资格条件。[Android Live Updates 指南](https://developer.android.com/develop/ui/views/notifications/live-update)
- Live Update 用于用户发起、正在进行且需要持续关注、有明确结束点的活动。普通聊天消息不属于合适用途；`MessagingStyle` 也不在可 promotion 样式清单内。本次实现对正在执行的 Agent 任务优先发布一张 `ProgressStyle` Live Update；保留打开会话窗口的操作及会话快捷方式。仅当系统确认用户选择了该会话气泡时，保留一个禁止在通知栏重复显示的气泡伴随通知。完整正文及附件在会话中查看。任务完成、失败、取消或状态失去可信度时清理进行中卡片；终态使用普通会话通知。
- Live Update 是摘要，不是长回复容器。Android Live Updates 指南未规定 `contentText` 的跨设备固定字符上限；系统和 OEM 表面可以裁切。短状态芯片的 `setShortCriticalText` 建议最多 7 个字符，且文档不保证实际能显示多少。保持标题、摘要和芯片文案简短；完整正文及附件应可从会话打开。[`Notification.Builder.setShortCriticalText`](https://developer.android.com/reference/android/app/Notification.Builder#setShortCriticalText(java.lang.String))
- Android 14/15 没有 API 36 Live Updates。该版本保留普通会话通知及符合条件的 conversation bubble；bubble 仍受用户设置控制，关闭 bubble 后通知应作为普通通知显示。Android 13+ 还受 `POST_NOTIFICATIONS` 运行时权限与 app/channel 设置控制。[通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission) · [Conversation bubbles](https://developer.android.com/develop/ui/compose/notifications/bubbles)

## Samsung Now Bar 边界

Samsung 用户指南将 Now Bar 描述为 Galaxy 系统表面，显示其支持的活动类别；需要适配机型与软件版本，锁屏需启用，并由用户在「设置 → 锁定屏幕和 AOD → Now bar」中配置。Samsung 还说明 Live notifications 与 app 通知设置相互关联；支持的 app 及类别会因设备和版本而异。[Samsung Now Bar 设置说明](https://www.samsung.com/us/support/answer/ANS10004605/) · [Samsung Now Bar 可见性说明](https://www.samsung.com/ae/support/mobile-devices/the-live-notifications-capsule-disappeared-after-changing-live-notifications-options-in-one-ui-7-now-bar-menu/) · [Samsung 支持的 app / 设置说明](https://www.samsung.com/ae/support/mobile-devices/why-is-the-now-bar-not-appearing-in-one-ui-7/)

在本次查阅的 Samsung 公共开发者资料中，没有找到供任意第三方 app 接入 Now Bar 的公开 API 或兼容承诺。Android 官方也明确提醒 OEM 可以额外限制 Live Update 资格。故实现目标应是符合 Android 公共 Live Updates API，并在真实 Galaxy 上实测；不得把 API 36 promotion 等同于 Now Bar 必显，也不依赖未公开 extra、隐藏 SDK 或 Samsung 内部白名单。Samsung 设置项缺失时，应记录机型、地区固件、Android / One UI 版本与通知设置，不能据此认定应用通知代码失效。

## 手工验收矩阵

以下项目均为**未执行**。API 36 项需 Android 16 设备或模拟器；Samsung 项必须在实际 Galaxy 设备上完成，模拟器结果不能代替 Now Bar 验证。

| 场景 | 操作 | 预期结果 | 状态 |
| --- | --- | --- | --- |
| 会话开始与状态更新 | Android 16 上由用户发起任务，观察 RUNNING / QUEUED 状态，再切换到新进度 | 同一会话只有一个持续更新的任务卡片；状态变更更新原卡片，点击打开对应会话。Android 14/15 只检查普通会话通知 / bubble 回退 | 未执行 |
| 流式回复与附件 | 在进行任务中发送多段流式回复，并加入图片或文件附件；在 bubble 和会话中查看 | 任务卡片不随 token 或附件增加；回复文本与附件仍在会话中可读、可打开，通知栏没有重复展示同一任务的普通通知 | 未执行 |
| 最终回复 | 分别结束一次成功、失败、取消任务 | 进行中 Live Update 被清理；终态回复 / 状态在会话通知中呈现，不能遗留仍运行的卡片 | 未执行 |
| 重复事件与重连回放 | 重复投递同一进度 / 完成事件，重连后回放最后事件 | 不产生重复任务卡片或重复最终提醒；已终结的任务不会因回放重新变成进行中 | 未执行 |
| 终态与断连清理 | 完成后再开始同一会话的新任务；另测任务进行中 Gateway 断开 | 前一任务卡片已消失；新任务可更新自己的活动卡片。无法确认远端仍运行时不遗留旧 Live Update | 未执行 |
| 用户拒绝或降级 | 关闭 app 通知、Live Updates promotion 或对应 channel；再尝试发起任务 | 用户降级 / 关闭的 Live Update 不被 App 反复重发；通知总权限关闭时不声称会显示通知。恢复用户设置后再验证正常路径 | 未执行 |
| Samsung Now Bar | 在支持的 Galaxy 真机上启用锁屏、app 通知、锁屏通知及可用的 Live notifications 类别，运行任务 | 记录 Now Bar 是否显示、是否跟随更新、终态是否清理，并记录机型 / OS / One UI / 地区固件。只把观察到的设备组合标为已验证；其他机型结果保持未知 | 未执行 |
