# 主会话与默认数字助手共用语音录制界面

日期：2026-11-10。状态：已实现。
目标分支：`dev`（开发分支 `feature/unified-voice-recording-ui`）。
关联：[App 界面与动效重构](2026-10-21-app-ui-motion-refactor.md)、
[对话与助理界面设计](2026-08-29-android-conversation-assistant-ui-design.md)、
[助理会话说明](../../apps/android/assistant-holder/README.md)。

## 一、问题

语音录制在产品里存在两套互不相干的实现：

1. **主会话（`MainActivity` + `WorkbenchScreen`）**：点麦克风后弹出一个屏幕中央的 `AlertDialog`
   「正在录音」，确认按钮是「结束并发送」。对话框遮住会话内容，用户看不到自己正在说什么；
   录音期间只有静态文案，没有音量反馈；录音状态由 Activity 里散落的 `remember` 变量持有。
2. **默认数字助手（`assistant-holder`）**：输入栏原地替换为「× 取消 / 波形 + 计时 / 发送」，
   波形随真实音量滚动，录音状态由独立的状态机持有。

结果是同一个动作在两处长得完全不一样，而且波形动画、录音状态机与计时格式各维护一份，
后续任何视觉或行为调整都要改两遍，稍有遗漏就会漂移。

## 二、需求与边界

- 移除主会话的中央录音对话框，改为输入坞原地呈现录音态；
- 复用数字助手已经实现的波形动画（视觉样式、动态反馈、录制状态交互）；
- 优先抽取共享组件与状态逻辑，**不允许复制一份新的波形动画**；
- 不引入语音转文字/语音识别；不改变「原始音频直接作为附件透传给 Agent」的机制；
- 不修改无关的消息发送、附件处理与会话逻辑；
- 保留原有麦克风权限申请、录制、取消、结束与发送行为；
- 录制过程中不遮挡会话内容，不影响时间线滚动、键盘与附件操作；
- 遵循现有 MD3 Expressive 动效与主题令牌，深浅色模式一致。

## 三、实现

### 3.1 共享层下沉到 `conversation-ui`

`conversation-ui` 是主会话与助理（`assistant-holder` 依赖它）共同的下层模块，
因此共享实现放在 `com.openandroidintelligence.conversation.voice`：

| 文件 | 职责 |
| --- | --- |
| `VoiceRecorder.kt` | 录音状态机（`Idle` / `Recording` / `Sending`）：计时、电平采样、取消、结束、防重复提交、切会话丢弃、失败清理。原先的 `AssistantVoiceRecorder` 原样迁移并公开为通用 API；拒绝原因与可渲染的 `ActiveVoiceRecording` 也在这里。 |
| `RecordingWaveform.kt` | 波形窗口与 Canvas 绘制：24 帧滚动窗口、右侧最新、无样本时画静态基线、最右一列平滑落位、减少动态直接落位。 |
| `VoiceRecordingRow.kt` | 录音态输入行：`× 取消 / 波形 + 计时 / 发送语音`，含无障碍合并朗读。 |
| `VoiceDuration.kt` | `分:秒` 格式，录音计时与时间线语音附件共用同一个函数。 |

`assistant-holder` 中原有的 `AssistantVoiceRecorder.kt`、`AssistantVoiceWaveform.kt`
以及 `AssistantCompactBar` 里的 `CompactRecordingRow` 与计时格式化已删除（迁移到上述共享文件），
`AssistantSessionContent` 直接使用共享的 `VoiceRecorder` 与 `activeRecording()`。

### 3.2 主会话接线

- `MainActivity` 改为持有一个 `VoiceRecorder`（`remember(app)`，`DisposableEffect` 关闭），
  替换原先散落的 `recording` / `sendingVoice` / `voiceSendJob` 变量与两个对话框
  （「正在录音」与「正在发送语音」）；
- 录音结束后的发送仍走 `WorkbenchController.sendVoiceAttachment()`，音频仍是原始文件附件；
- 记录录音开始时所属的对话，交付前后校验会话身份，切换对话则丢弃；
- `LaunchedEffect(controller)` 观察活动会话变化，主动调用 `recorder.onSessionChanged()`；
- `Activity` 离屏（`ON_STOP`）仍在生命周期观察者里取消录音并清掉待发送目标；
- 发送阶段（`Sending`）与数字助手保持一致：输入坞回到可输入形态，而不是再弹一个
  阻塞式「正在发送」模态；成功与否由时间线里的语音附件与失败提示如实反馈。
  录音期间助理面板可见时，只由面板呈现录音态，工作台不再重复画一份。
- 每次点击麦克风前仍先检查 `canSendVoiceAttachment()` 与 `RECORD_AUDIO` 权限，
  未授权时走系统权限对话框，拒绝后给出「去设置」入口。提示文案保持原样。

### 3.3 界面参数

`ComposerBar`、`WorkbenchScreen`、`FloatingConversationPanel`、`AssistantCompactBar`
均新增三个可选参数：

```kotlin
recording: ActiveVoiceRecording? = null,
onCancelRecording: () -> Unit = {},
onFinishRecording: () -> Unit = {},
```

`recording == null` 时渲染原有输入行，行为完全不变；非 null 时用 `AnimatedContent`
（`contentKey = { it != null }`，避免每秒计时变化重播转场）原地替换主行。
录音行不覆盖时间线、不拦截滚动，附件条与生成状态行仍在其上方/下方正常显示。

录音期间输入框、麦克风与发送让位给录音行，但「+ 添加附件」保留在同一行左侧：
需求明确要求录制过程不影响附件操作，语音也是一条独立的附件型消息，
原草稿与已添加的附件都原样保留。助理的紧凑输入栏没有独立的附件入口，
它保持原有「整条替换」的形态，附件条仍显示在录音行上方。

### 3.4 视觉与动效

- 波形颜色取 `primary`（有音量）/ `outlineVariant`（无样本基线），柱高 = 最小比例 + 真实音量；
- 仅最右一列用 `MotionSpecs.snappy(reduced)` 平滑落位，减少动态时 `snap`；
- 录音行容器沿用 `surfaceContainerHigh` + `AppRadius.Bubble` + 发丝描边；
- 取消为 44dp 圆形次要操作（触摸目标 48dp），发送为与输入态同尺寸的 48dp 主操作；
- 无障碍：波形与计时合并为一个朗读节点（「正在录音 / 已录制 N 秒」），
  取消与发送各自独立可聚焦。

## 四、验收对照

| 验收项 | 状态 | 证据 |
| --- | --- | --- |
| 点击语音后不再弹中央卡片 | 通过 | `MainActivity` 中两个 `AlertDialog` 已删除；`VoiceRecordingRowTest.composerReplacesTheInputRowWhileRecordingInsteadOfAddingACardAboveIt` |
| 底部输入区显示与数字助手一致的波形 | 通过 | 两处都渲染 `VoiceRecordingRow` → `RecordingWaveform` |
| 波形随实际音量变化 | 通过 | `VoiceRecorderTest.theLevelFollowsTheDeviceAmplitude`；`RecordingWaveformTest` |
| 录制/取消/结束/发送状态正确 | 通过 | `VoiceRecorderTest` 全量（取消、重复发送、切会话、失败清理、关闭） |
| 发送后生成正常语音附件 | 通过 | 仍在 `sendVoiceAttachment()` 路径；`InputPlatformRegressionTest` |
| 数字助手录音功能不受影响 | 通过 | `AssistantCompactBarTest`、`AssistantVoiceDeliveryTest`（录音行控件、取消/发送回调、交付字节与时长） |
| 两处复用相同波形实现 | 通过 | 无第二份波形代码；`AssistantCompactBarTest.recordingModeReplacesTheWholeInputRow` 断言共享行的控件 |
| Android 构建与回归测试 | 通过 | `:app:compileFullDebugKotlin`；`:conversation-ui:testDebugUnitTest`（832 项）、`:assistant-holder:testDebugUnitTest`（149 项）全绿；`:app` 的相关回归类同样通过。注：`:conversation-data` 的 `HermesHttpInteropTest`（1 项）在本机报 `HERMES_HISTORY_FIXTURE_START_FAILED`：它用 PATH 上的 `python3` 启动 Hermes 夹具，而本机 `python3` 解析到系统解释器（缺少 SDK 依赖），属于既有环境缺口；CI 上由专用的 legacy-hermes-fixture 解释器提供。`:app` 中同类夹具用例（`HermesHistoryMediaHttpTest`、`AndroidAttachmentContentTypeTest`）在指定 `OAI_HERMES_PYTHON` 后通过 |
