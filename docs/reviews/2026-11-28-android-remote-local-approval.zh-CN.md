# 安卓远程设备能力的本机审批

## 真机复现

2026 年 11 月 28 日，Note20 Ultra 上的 OAI `13721` 保持亮屏、解锁和前台。使用 DSH Remote `v0.6.5` 的公开 Client API 发送 `ui.dump_tree`。临时连接的发送回执为 `accepted`。请求在 `05:28:03.530`（北京时间）发出 `approval/asked`，42 毫秒后写入 `cancelled / not_started / REMOTE_CONNECTION_CLOSED`。使用同一客户端选中手机并保持 LAN 连接，同一能力进入 `needs_user_input`，直到测试精确取消请求。两次实验的 OAI 进程相同。

固定上游 `packages/plugin/src/client-runtime.ts:548` 的 `withToolRemote` 优先复用当前选中的目标，单次临时连接则在 `finally` 关闭。`packages/plugin/src/remote-session-tools.ts:224` 在 `session/prompt` 收到受理响应后返回。手机的 [PhoneControlBus](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/facade/PhoneControlBus.kt#L324) 随连接关闭结束未执行的请求。仅回传能力清单的 `phone.capabilities` 可以在临时连接内完成。

ADB 的另一轮临时连接在 `05:25:49.706` 关闭，窗口于 `05:25:49.760` 完成首帧。之后 `dumpsys window` 仍能看到这个 `TYPE_ACCESSIBILITY_OVERLAY`。原浮窗在 `addView` 后登记，但清理依赖 `isAttachedToWindow`，导致首次遍历前取消时窗口失去所有者。无障碍事件曾对每个窗口同步索取根节点。没有可继续使用的快照时，读取这些根节点也不能改变事件处理结果。短连接实验区间没有 OAI 进程崩溃记录。

## 验证方法

- 通过 DSH 原生工具和 MCP 分别发现工作区、读取能力清单，以及在持续连接下保持 `awaiting_confirmation`。在手机本机决定后，按原请求 ID 读回终态。
- 窗口回归检查首帧前取消、同一窗口从确认切换到执行、真实触摸门禁，以及窗口变化对屏幕快照的失效。关闭传输仍会取消待授权请求。
- 真机记录 APK 构建身份、窗口增删和帧时间。仅上传回执状态、耗时、计数或产物类型。截图、UI 树、短信、通知和通话记录正文留在本机验证进程。

## 本机点按后的故障定位

在用户授权下，使用 ADB 点按 Note20 Ultra 的实际审批窗。配合持续 LAN 连接，DSH 原生与 Terra 的 MCP 调用均完成通知和短信读取。本机拒绝会返回 `approval/decided` 与 `LOCAL_USER_REJECTED`，业务执行状态为 `not_started`。

`13781` 上，两条链路的通话查询均在批准后返回 `CONTROL_EXECUTION_FAILED`。三星通话记录 Provider 的零行查询明确拒绝 `number_presentation`，接受 SDK 定义的 `presentation`。读取投影与游标取列改用 `CallLog.Calls.NUMBER_PRESENTATION`。真实查询在 `13801` 上返回一条记录，两条链路的结果一致。[通话记录读取器](../../apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/AndroidCallLogReader.kt#L143)

屏幕测试在三星计算器进行。原控制快照把已卸审批窗的迟到事件当成业务界面变化。ADB 确认该浮窗的窗口号后，修复通过已登记的本机窗口身份处理卸载事件。测试还发现，交互控件和窗口均相同的两份快照可因状态栏网速文字变化而失效。暂时隐藏网速显示后，Home 动作成功执行，随后恢复手机原设置。操控校验仅归一化已确认的系统状态栏网速标签文字，语义树仍返回真实文本。[窗口事件判定](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/ScreenAccessibilityService.kt#L215) · [状态栏标签识别](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/ScreenStatusPresentation.kt#L12)

旧版 Android 的 SoM 整屏取帧须在采集前确认遮蔽所需的窗口根与子节点可读。身份明确且已完整遮蔽的本机审批浮窗可剔除。取帧后再做一次严格校验。Android 34 起使用指定窗口截图。[SoM 校验](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/NativePhoneScreen.kt#L88)
