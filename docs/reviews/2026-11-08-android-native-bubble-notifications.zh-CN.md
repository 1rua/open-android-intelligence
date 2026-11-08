# 原生消息气泡与回复完成提醒恢复

读者：PR 审查者与 Android 验收执行者。状态：实现完成，自动化测试通过；模拟器端到端验收尚未执行。

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
| `NativeConversationStoreViewTest`（2 项） | 通过：两读取者游标互不覆盖，内容仍共享 |
| `NativeNotificationReconcilerTest`（3 项） | 通过：空闲只判定结束且不提醒；仍在运行时不误判结束；读期间换轮不采信 |
| `BubbleConversationTest` / `BubbleAttachmentDraftCoordinatorTest` / `ConversationAttachmentPickersTest` | 通过（工作区构造签名变更已同步） |
| `NativeConversationNotificationsTest` / `ReplyNotificationsTest` / `LiveUpdateNotificationsTest` | 通过（Robolectric SDK 36 用例需 JDK 21） |
| `:app:testFullDebugUnitTest` 全量 | 1566 项中 5 项失败，全部为缺少 Hermes 夹具/依赖；补齐 `HERMES_PLUGIN_ROOT`、`HERMES_SDK_ROOT`、`OAI_HERMES_PYTHON=.toolchains/pr64-python/bin/python`、`PYTHONPATH=.toolchains/py-test-deps` 后逐个复跑通过 |

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
