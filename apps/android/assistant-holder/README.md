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

## 系统助理会话界面（2026-10-21 M3 Compose 重构）

助理浮层只有在**其他 App 之上被系统唤起**时才有意义，因此这里的界面就是系统助理
会话本身，而不是"只能在 App 内打开的面板"。

结构：

| 文件 | 职责 |
| --- | --- |
| `AssistantSessionService.kt` | `VoiceInteractionSession`：窗口尺寸、停靠态、圈选态、返回键与生命周期 |
| `AssistantActivity.kt` | 未设为默认助理时的 `action.ASSIST` 兜底页 |
| `AssistantComposeHost.kt` | 轻量 Compose 宿主：`ComposeView` + `LifecycleOwner` + `SavedStateRegistryOwner` |
| `AssistantSessionContent.kt` | 主题根、停靠球、会话浮层卡片、兜底页、圈选层 |
| `AssistantConversationBody.kt` | 共享内容层：标题、消息、空态引导、输入坞、动作行、提示条 |
| `AssistantBridgeClient.kt` | 只依赖 `Context` 的桥接客户端：`StateFlow<AssistantState>` + 命令方法 |
| `AssistantState.kt` | 桥接 JSON 的只读映射，以及"Gateway 状态/禁用原因"的文案规则 |
| `AssistantScreenCrop.kt` | 圈选画布坐标 → 截图像素的裁剪换算 |
| `AssistantScreenshotEncoder.kt` | 原有 PNG 编码工作线程（未改动） |

约束：

1. **跨进程协议不变**：`version=1` 的 `connect` / `consent` / `thread` / `send` /
   `cancel` / `draft` / `screen` / `disconnect` 与 JSON 字段一字未改；本进程不初始化
   Gateway、不持有凭据，只做呈现。
2. **不伪造状态**：未绑定主进程、未连接 Gateway、未同意当前对话时分别显示对应引导，
   消息与线程列表全部来自主进程发布的状态，没有演示数据。
3. **窗口即卡片**：会话用 `setLayout` 在 72dp 停靠球与展开卡片之间整窗切换，窗口背景
   透明，圆角与品牌环由 Compose 绘制；兜底页复用 App 内同一个 `AssistantSurface`。
4. **圈选来自系统截图**：只有 `onHandleScreenshot` 提供的截图可被圈选；没有截图时给出
   明确提示，不降级到任何后台采集。确认后才裁剪并通过 `screen` 命令以管道提交，
   10 秒看门狗与 8MB 上限保持不变。
5. **无障碍与减少动态**：图标按钮均有 `contentDescription`，停靠球有
   `stateDescription`，动画令牌来自 `conversation-ui` 的 `MotionSpecs` 并随系统动画
   缩放退化。

旧的 `AssistantPanel`（手搓 `LinearLayout` + 系统 `Button`）与原生 `AssistSelectionView`
已整体替换为上述 Compose 实现；被替换的文件在任务记录中说明（已移入
`/tmp/open-android-intelligence-trash/`，未做永久删除）。
