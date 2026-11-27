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

`GatewayRuntime.kt` 将已有通知出口移动到主仓库构造之前，为主工作台与系统助理仓库补齐回调，并核对回调的固定 Agent 身份。旧连接/账号的所有权检查继续复用已有出口。

完成识别、持久去重、合成历史过滤、通知渠道、权限判断、通知内容、气泡结构、点击路由和前台抑制逻辑均复用现有实现。普通流式正文仍是 delta；只有明确成功状态与已完成助手正文齐备才进入既有完成提醒。

## 连续设备回归发现的去重缺口

发送流与重连事件页可能使用不同事件 ID 转发同一轮次、同一消息、同一正文修订的终稿。现有 `ReplyCompletionObserver` 的持久完成记录能防止再次响铃，但 `NativeConversationNotifications` 在调用观察器前会强制刷新正文通知；因此用户已经点掉的通知仍可能静默重新出现。

连续设备回归的息屏场景捕捉了这一行为。新增 `anotherReaderCannotReviveAnIdenticalCompletedReplyAfterDismissal` 通过真实通知发布器复现：先完成并撤回通知，再用新事件 ID 投递相同终稿，修复前系统通知重新出现，断言失败。

最小修复仅跳过已经处于终态且消息 ID、正文修订、文本均相同的已完成正文重复投影；事件仍送入完成观察器。状态先到正文后到、正文新修订、其他轮次以及仍有排队任务的既有规则保持可执行回归覆盖。

## 设备验证方法与边界

设备为 `emulator-5562`，Android 15 / API 35 / x86_64。通过仅本地的 Gradle 初始化配置，将验收 APK 安装为 `com.openandroidintelligence.mobile.replyvalidation`，使用新建的 Android 测试用户 10、11。原用户 0 的业务包、通知权限、账号、密钥和会话数据不作修改。测试包的通知权限由 `adb pm grant --user` 授予，权限拒绝分支由已有回归测试覆盖。

端到端路径是生产 Core、生产 Hermes session bridge、受控宿主任务、实际安装 APK 的主工作台、系统 `NotificationManager`。宿主输出为固定测试内容，不调用模型。控制口只控制任务结束与隔离 Core 的启停，不能生成 Android 通知。

设备夹具显式开启测试历史与投递状态：用户消息和轮次身份来自真实受理结果，最终正文来自实际执行的受控任务，投递状态通过生产 `claim_dispatch` / `update_dispatch_status` 入口产生，保持正文修订与投递状态修订独立。原有通信测试默认不启用该模式。

点击自动验收调用系统实际通知所持有的 `PendingIntent.send()`，验证 Android 分发到 Activity 后进入对应 Agent 与会话；这不等同于验证通知栏折叠分组的触摸命中。设备曾人工展开并点击分组卡片，最终验收以自动化的通知 Intent 与会话状态断言为准。气泡验证包含系统通知的 `BubbleMetadata`、有效会话 shortcut 及目标映射，不把这些证据扩大为悬浮气泡展开操作已验收。

## 验证记录

- 真实 Runtime 主工作台：修复前通知模块无完成记录，修复后系统消息通知、气泡信息与点击目标通过。
- 最终业务提交 `44ee5155`：10 类、353 项 Android 回归全部通过，0 失败、0 错误、0 跳过；包括系统助理可见性、去重、历史、跨 Agent、导航与气泡。新增静默重发回归在 API 31 至 36 六个版本运行。
- 受控宿主与进程恢复：13 项通过；最终设备模式增加的完成状态使用真实投递入口。
- 本地静态门禁 `noVpnSurfaceCheck`、验收 APK 和 instrumentation APK 构建通过。
- `npm run test:fast` 首轮使用系统 Python，部分子进程缺少依赖；换成项目已有 Python 后，`npm run test:pr` 得到 1210 项通过、13 项跳过，仅剩 `test_probe_succeeds_when_invoked_via_parent_relative_venv_python` 创建 `venv/lib64` 符号链接时的 `ENOENT`。单独重跑仍复现此环境限制，未绕过或禁用用例；因此本地 Fast/PR 整体不记为通过。
- 远端 `6f344ac2`：Core Python 3.11/3.12、App 回归、Play 回归、Gateway Client 与 APK 通过；Safety Gate 失败于设备测试绕过统一 HTTP 入口，已改为 `GatewayTransport` 且本地门禁通过。后续提交的最终 CI 另行记录，不借用旧提交的结果。
- 最终业务提交 `44ee5155` 在 2026-11-11 连续完成四种设备场景，全部通过：后台、息屏、其他会话、断线期间完成后恢复。各场景还包含完成后再启停 Core、恢复游标和历史、已经处理的通知不重新出现。
- 设备原始测试回执、会话身份与 APK 哈希保存在相邻目录 [验证汇总](2026-11-10-agent-reply-completion-notifications/验证汇总.json)；该目录包含四份 `.txt` instrumentation 回执，不含凭据或真实消息。
- `44ee5155` 的远端 CI 已触发：[Core、App 与门禁](https://github.com/1rua/open-android-intelligence/actions/runs/42947960623)、[APK 构建](https://github.com/1rua/open-android-intelligence/actions/runs/42947954172)。归档时仍在运行，采用后台只读跟进，不记为已通过。

## 独立评审

按固定基线 `4cbe21b8`，由无开发上下文的规范轴与需求轴分别只读评审。初版接线无生产代码缺陷；需求轴指出缺少“断线期间完成”的设备场景，已补齐。设备验收过程发现的夹具前置、统一传输入口、真实历史与投递修订分别修正。

- 规范轴最终复核 `44ee5155`：明确规范违规 0 项，有价值的判断性味道 0 项。
- 需求轴最终复核 `44ee5155`：无阻塞代码问题；确认重复终稿只跳过投影、完成观察器继续执行，迟到正文、新修订、跨轮次和后续任务规则保持。
- 两轴都是静态独立评审；设备与回归通过结论来自上述固定业务提交的实际执行记录。

## 复验入口

在独立模拟器或专用验收包中运行，不清除或覆盖既有业务数据。APK 可通过本地 Gradle 初始化配置覆盖 `applicationId`，该配置不进入产品源码。

1. 用已具备 Core、适配器及固定 Hermes SDK 依赖的 Python 运行 `oai-core/tests/agent_reply_notification_device_fixture.py --state-dir <新的空目录> --ready-file <元数据文件>`。
2. 将元数据中的 Core 与控制口分别通过 `adb reverse tcp:<端口> tcp:<端口>` 提供给模拟器。
3. 为专用 Android 用户安装业务 APK 和测试 APK，授予测试包通知权限。
4. 定向执行 `AgentReplyNotificationE2eTest`，传入 `oaiReplyNotificationE2e=isolated`、`oaiCoreUrl`、`oaiControlUrl`；`oaiNotificationScenario` 分别取 `background`、`screenOff`、`otherConversation`、`reconnectDuringCompletion`。各场景使用新夹具目录。
5. 验收后调用控制口 `POST /stop`，撤回本次端口转发并切回原 Android 用户；状态和证据目录保留人工复核。
