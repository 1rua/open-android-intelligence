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

## `13821` 真机对照

Note20 Ultra（Android 33）安装签名 APK `13821`，签名与 APK 内记录的源码提交 `9ed072ae` 均已核对。状态栏网速显示开启。以下测试使用持续 LAN 连接，审批经过真实的本机确认窗口。

| 能力 | DSH 原生 | Terra 经 MCP |
| --- | --- | --- |
| `phone.capabilities` | 完成，10 项，0.78 秒 | 完成，10 项，2.75 秒 |
| `notifications.query` | 完成 0 条，1.98 秒 | 完成 0 条，4.92 秒 |
| `call-log.query` | 完成 1 条，1.75 秒 | 完成 1 条，4.74 秒 |
| `sms.query` | 完成 1 条，1.68 秒 | 完成 1 条，4.95 秒 |
| `ui.dump_tree` | 返回计算器 16 个节点，3.79 秒含结果文件回读 | 返回计算器 16 个节点，7.73 秒 |
| `ui.capture_som` | 取得 90,680 字节 WebP，取回结果耗时 6.60 秒 | 取得 90,680 字节 WebP，结果耗时 18.47 秒，随后下载 |
| `ui.global_action`（Home） | 动作完成，`performed:true`、`settled:true`，3.90 秒 | 该轮批准后快照过期，动作未执行，6.06 秒 |

两条路径都验证了通知查询的本机拒绝，业务执行未开始。截图一栏为暂时关闭三星侧屏面板后的对照结果，WebP 文件长度、格式及 SHA-256 均通过校验。原生工具此前在侧屏面板开启时已完成计算器 UI 树和 Home。持续连接的上述样本中未遇到 `REMOTE_CONNECTION_CLOSED`。

侧屏面板开启时，Android 33 的全显示器 SoM 在取帧前返回 `SCREEN_REDACTION_UNPROVABLE`。设备上的调试栈显示三星侧屏把手报告了子节点，但对父节点调用 `refresh()` 成功后，同一索引仍返回空。窗口管理器记录的绘制帧为 `[1013,439][1080,719]`，无障碍窗口的可触范围为 `[1038,439][1080,719]`。缺失节点的窗口无法凭可触区域保证像素遮蔽，仍由手机安全拒绝。此场景的 UI 树读取和已确认的 Home 控制可独立使用。[缺失节点处理](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/AndroidAccessibilityRuntime.kt#L308)

## 三档本机授权

DSH 手机的数据插件和原生屏幕曾只识别「允许只读」的免确认模式，把「自动允许」降为「每次手动」。数据插件的进入判断与执行复核同时使用同一策略。两种免确认模式均只作用于已授权的读取，截图还要求读取与图像采集各自获准。屏幕操控与短信发送继续本机确认。[数据能力策略](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt#L652) · [屏幕授权](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/NativePhoneControlKernel.kt#L22)

签名 APK `13831`（`5607b98b`）的通知读取在三档均完成。「每次手动」实际出现一张确认卡片并经本机批准，耗时 2.53 秒。「允许只读」和「自动允许」均没有 `approval/asked`，分别耗时 1.50 秒、0.95 秒。测试后恢复「自动允许」。原生与 Terra MCP 在自动允许档均成功读取通知、通话记录、短信和 UI 树。Terra 的 Home 请求保持本机确认，批准后实际离开计算器，返回 `performed:true`、`settled:true`。普通侧屏设置下，SoM 路径已跳过 OAI 确认并按遮蔽检查拒绝取帧。

窗口事件补采样显示，三星状态栏先报告网速叶节点的纯文本事件，也会另发以 `status_bar` 为源的子树变化。该变化使正在采集的截图过期。操控在新旧窗口和节点均完整、前后一致时可继续，否则仍拒绝旧引用。系统声明却无法取得的子节点会明确标记为截断。[窗口事件复核](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/ScreenAccessibilityService.kt#L198)

## 主线程开销

屏幕树曾为选入的每个节点重新编码整份结果。Java 21 下用含中文、转义字符和表情的同一份 512 节点样本测量了 65 次回执选点。改用逐节点字节计数后，中位耗时从约 17.8 毫秒降到 0.84 毫秒，每次中位分配量从约 66.2 MB 降到 2.3 MB。两份回执均为 115 节点及 47,723 字节，SHA-256 一致。带包名的外部应用内容事件直接使快照失效。若没有其他待复核的浮层窗口 ID，可省去主线程逐窗查询根节点。两条路径共享这一实现。[回执选点](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/ScreenTreeReader.kt#L109) · [窗口事件](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/ScreenAccessibilityService.kt#L198)

样本计时来自本机 JVM。新包的 Note20 Ultra 帧表现和双路径操控仍待验证。调试端口拒绝连接，已登录的 DSH 设备目录也未返回该手机为在线主机。

## 操控派发回执

`13861` 仍出现长按以 `outcome_unknown/SNAPSHOT_STALE` 结束的样本。自动化在原生端口复现了相同错误码，且该样本在 Android 接收动作前便写了一次派发记录。现在由原生控制租约紧邻系统调用写入派发记录。回归分别验证了派发前目标失效时不写记录，以及确认派发时写入一次记录。真实长按的后续复测需要恢复真机连接。[原生端口](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/NativePhoneScreen.kt#L48) · [派发检查](../../apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/ScreenControlLease.kt#L30)

## MCP 并发审批关联

同一任务的第一个请求待审批时，上游会话返回 `needs_user_input`。旧桥接将同一状态用于排队请求。桥接现在按 `request_id` 匹配手机本机确认事件，并在同一页确认仍处于等待状态。旧页中的确认记录通过同一请求的当前账本复核。手机在受理 `request.status` 时同步写出回执，桥接可直接读取。[桥接实现](../../integrations/oai-android-mcp/src/bridge.mjs#L360)

用固定版本上游工具和 Kotlin 手机门面验证了本机确认、同任务排队和按请求取消。首个请求保留待确认，第二个请求保持已受理，精确取消首个请求后第二个请求完成。该探针使用隔离的系统能力替身。真机延迟与能力矩阵仍需复测。[互通探针](../../integrations/oai-android-mcp/test/original-tools-probe.mjs#L131)
