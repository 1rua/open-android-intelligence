# Android Agent 回复完成通知修复

关联需求：#132。目标分支：`dev`。

## 目标与范围

排查 Agent 最终回复到 Android 通知发布的完整链路，恢复已有回答完成通知；保留原生消息通知、气泡、当前会话阅读策略、通知文案和 UI。通过明确终态与真实轮次身份判断完成，不使用延迟、轮询或消息文本猜测。

验收覆盖后台、息屏、非当前会话、点击进入对应会话、流式过程不提前通知、同轮最多一次、断线重连和历史同步去重。交付源码根因、最小修复、回归测试及模拟器或真机验证结果。

## 排查与验证记录

- 基线：`4cbe21b8`，从 `origin/dev` 建立独立任务工作区。
- 主工作区现有未提交设备验证资料保持原状。
- 在线模拟器：`emulator-5562`，Android 15 / API 35。设备验收使用独立安装包名与专用 Android 用户，保留原用户的账号、数据和安装包。

## 已复现根因

主工作台使用 `GatewayRuntime` 中的 `nativeRepository` 创建原生会话仓库，但没有传入 `CoreAgentConversationRepository.onEventPublished`。该参数默认是空回调。仓库正常持久化并通过 `_events.emit` 把最终正文和完成状态送到界面，随后调用的通知回调却没有接收者。因此出现“界面已收到最终回复，通知模块仍没有该会话记录”。

系统助理会话的专属仓库存在同样的漏接。气泡仓库已经接入 `publishNativeNotification`，证明现有桥接与发布能力存在，缺口是调用方接线。

可执行反馈来自 `NativeAgentBackgroundWorkTest.mainWorkbenchCompletionPublishesTheExistingReplyNotification`：复用登录和真实 HTTP/SSE，必须通过 `runtime.controller` 的 `openThread`、`editDraft`、`sendDraft` 驱动实际主工作台。不能沿用旧夹具中的 `NativeAgentsController.send`，后者直接调用网络客户端，绕过本次缺陷所在仓库。

修复前，工作台已进入 `GenerationState.COMPLETED`，断言“主工作台收到明确完成事件后必须传递给现有通知模块”失败。只补齐回调后，同一反馈通过，并能从系统通知读取原生消息类别、完成态、气泡元数据及正确的点击目标。

## 事件链路与最小修复

```text
Core sessions.py 校验并转发宿主 done
  → AgentSessionClient 明确区分 delta / done
  → CoreAgentConversationRepository 发布最终正文及成功状态
  → GatewayRuntime.onEventPublished（本次补齐主工作台、系统助理接线）
  → nativeNotificationFrame 保留账号、Agent、会话、轮次与事件身份
  → NativeConversationNotifications / ReplyCompletionObserver
  → AndroidReplyNotificationPublisher
  → 已有系统消息通知、会话 shortcut、气泡与点击入口
```

生产代码只修改 `GatewayRuntime.kt`：将已有通知出口移动到主仓库构造之前，为主工作台与系统助理仓库补齐回调，并核对回调的固定 Agent 身份。旧连接/账号的所有权检查继续复用已有出口。

完成识别、持久去重、合成历史过滤、通知渠道、权限判断、通知内容、气泡结构、点击路由和前台抑制逻辑均复用现有实现。普通流式正文仍是 delta；只有明确成功状态与已完成助手正文齐备才进入既有完成提醒。

## 验证进度

- 已执行：真实 Runtime 主工作台的失败反馈与修复后通过。
- 进行中：通知、去重、历史、气泡、系统助理可见性回归；Fast 与 PR 门禁。
- 进行中：真实 Core、生产 Hermes bridge、受控宿主任务到模拟器系统通知的端到端验收。宿主任务使用确定性测试输出，不调用模型，也不代表线上已部署宿主的验收。
- 未执行的项目不记为通过，最终设备日志、测试统计与限制将在验收后补齐。
