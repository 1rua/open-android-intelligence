# Assistant holder attachment boundary

This APK is only the Android default-assistant entry point. It accepts
user-granted `content://` streams from the explicit Photo Picker or Storage
Access Framework actions and emits a bounded typed payload:

- at most four `AssistantAttachmentSelection` records;
- each record carries only an opaque selection ID, source (`PHOTO_PICKER` or
  `SAF`), read-grant ID, and optional MIME hint;
- no provider location, URL, filesystem handle, bytes, network, shell, or
  upload implementation crosses this APK boundary.

The holder does not mint fake grants.  Without an installed
`AssistantAttachmentGrantIssuer` it emits no attachment selections; a reviewed
local adapter must bind the private provider handle and return the real opaque
selection before the main app can enter the digest/ticket flow.

The main app is responsible for digesting the granted selection and entering
the `artifact-ports` ticket/scratch lifecycle. This module does not claim a
real Photo Picker/SAF adapter, encryption implementation, or upload path.

## 系统助理会话界面（底部悬浮输入栏重构）

助理浮层只有在**其他 App 之上被系统唤起**时才有意义，因此这里的界面就是系统助理
会话本身，而不是"只能在 App 内打开的面板"。

结构：

| 文件 | 职责 |
| --- | --- |
| `AssistantSessionService.kt` | `VoiceInteractionSession`：测量驱动窗口高度、状态机驱动形态、圈选全屏、返回键与生命周期 |
| `AssistantSessionRoot.kt` | 宿主无关的根组合：紧凑输入 / 展开会话两态、高度回传、状态机持有者 |
| `AssistantCompactBar.kt` | 底部悬浮输入栏（附件条 + 多行输入 + 麦克风/圈选/发送）与录音态 |
| `AssistantExpandedPanel.kt` | 可展开会话框容器：高度上限、内部滚动、智能跟随与「回到最新」 |
| `AssistantTimelineView.kt` | 复用工作台时间线渲染（Markdown/执行段/审批卡/附件）与跟随策略 |
| `AssistantSelectionStage.kt` | 全屏圈选舞台：截图 → 圈选 → 后台裁剪编码 → PNG 附件 |
| `AssistantVoiceRecorder.kt` / `AssistantVoiceWaveform.kt` | 录音状态机（防重复提交、切会话丢弃）与真实音量波形 |
| `AssistantSessionWindowPolicy.kt` | 高度上限与跟随判定的纯函数（0.7 比例、键盘可用区） |
| `AssistantUiStateMachine.kt` | 界面状态 reducer：唤起重置、发送成功才展开、返回键优先级 |
| `AssistantLocalPreviews.kt` | `localKey → 缩略图/语音时长` 的会话内存存储 |
| `AssistantActivity.kt` | 未设为默认助理时的 `action.ASSIST` 兜底页（同一棵根组合） |
| `AssistantComposeHost.kt` | 轻量 Compose 宿主：`ComposeView` + `LifecycleOwner` + `SavedStateRegistryOwner` + 高度回传 |
| `AssistantSessionContent.kt` | 主题根、圈选舞台切换、根组合装配 |
| `AssistantConversationBody.kt` | 仅保留共享提示条 `AssistantNoticeBar` |
| `AssistantBridgeClient.kt` | 只依赖 `Context` 的桥接客户端：`StateFlow<AssistantState>` + 命令方法 |
| `AssistantState.kt` | 桥接 JSON 的只读映射，以及"Gateway 状态/禁用原因"的文案规则 |
| `AssistantScreenCrop.kt` | 圈选画布坐标 → 截图像素的裁剪换算 |
| `AssistantScreenshotEncoder.kt` | 原有 PNG 编码工作线程（未改动） |

约束：

1. **跨进程协议不变**：`version=1` 的 `connect` / `consent` / `thread` / `send` /
   `cancel` / `draft` / `screen` / `disconnect` 与 JSON 字段一字未改；本进程不初始化
   Gateway、不持有凭据，只做呈现。
2. **不伪造状态**：未绑定主进程、未连接 Gateway 时分别显示对应引导，消息全部来自
   主进程发布的状态，没有演示数据。
3. **窗口由内容测量驱动**：窗口常驻 `MATCH_PARENT` 宽、`Gravity.BOTTOM`、背景透明；
   Compose 根节点把自身高度回传会话，会话用 `window.setLayout(MATCH_PARENT, heightPx)`
   精确对齐。没有停靠球，也不再在"球 / 卡片"之间整窗切换。
4. **状态机驱动形态**：唤起重置为紧凑输入；只有主进程真正接收的发送才展开会话；
   返回键先退出覆盖层（圈选），否则结束会话 —— 不中断生成、不折叠对话。
5. **圈选来自系统截图**：只有 `onHandleScreenshot` 提供的截图可被圈选；圈选态窗口临时
   铺满屏幕（`TOP|START`），没有截图时给出明确提示，不降级到任何后台采集。确认后才裁剪
   并通过 `screen` 命令以管道提交，10 秒看门狗与 8MB 上限保持不变。
6. **键盘与系统栏**：`SOFT_INPUT_ADJUST_NOTHING` + `setDecorFitsSystemWindows(false)`，
   键盘留白由 Compose 的 `imePadding()` 承担，避免系统缩放与内容让位叠加。
7. **无障碍与减少动态**：图标按钮均有 `contentDescription`，动画令牌来自
   `conversation-ui` 的 `MotionSpecs` 并随系统动画缩放退化。

旧的停靠球（`AssistantDockedBall` / `BrandRing`）、面板内标题/线程列表/绑定确认/动作行
已随本次重构删除：这些能力要么由输入栏本身承担（圈选、录音），要么在新交互里不再需要
（手动绑定对话、切换线程）。
