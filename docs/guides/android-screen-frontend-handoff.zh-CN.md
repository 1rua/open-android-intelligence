# Android 屏幕能力：前端开发交接

## 本阶段交付范围

本次交付 Android 服务、系统能力实现、独立受保护插件、既有 Core 调用链的接线和自动化测试。
没有修改会话、消息气泡、设置、权限管理、首次启动引导、布局、主题或已有文案；也没有增加
屏幕控制按钮、页面、弹窗或声明式插件 UI。三个新插件的 `ui.settings`、`ui.cards` 都为空。

**以下前端工作均为待接入**：屏幕会话的本地同意入口、三类权限的状态展示、无障碍设置入口、
远程截图专用的 MediaProjection 授权回调、开始/结束/撤销入口，以及面向其他应用的本地确认体验。
安装、启用插件或在 Core 打开能力开关均不会自动完成这些授权。当前缺少入口时安全不可用是预期结果，
不得通过自动调用 `start`、复用附件授权结果、写系统设置或远程请求中的确认字段来变为可用。

公开远程参数、响应和完整 CLI/API/MCP 示例见[接口说明](android-screen-interfaces.zh-CN.md)。
本文的 Kotlin 代码仅说明将来如何调用已经实现的本地 Interface，不是已接入的界面代码。

## 模块与公开入口

| 位置 | 职责与入口 |
| --- | --- |
| `apps/android/control-ports` | `ScreenHostRegistry.current`、`ScreenHostLifecycle`、`ScreenHostStatus`、`ScreenLocalAuthorization`、`ScreenPermission`、数据和动作类型；不依赖 Android 无障碍服务 |
| `apps/android/screen-control` | `AndroidScreenBackend`、`ScreenAccessibilityService`；真实多窗口读树、哈希、重新定位节点与执行标准 Android 操作 |
| `apps/android/capability-ports` | `RemoteScreenCaptureRegistry`、已有 `ScreenCaptureSource`、`MediaProjectionRuntime`、`MediaProjectionCaptureService`；用户系统授权和投影资源生命周期 |
| `apps/android/device-primitives` | `ScreenPrimitiveInstaller`、`RemoteScreenRuntime`、`ScreenImageStore`；在 App 启动时注册三个内核原语，并把本地许可与真实系统能力相交 |
| `apps/android/app` 的非 UI 装配 | `GatewayRuntime` 传入当前真实账号/配对/连接事实；`ProductionPluginHost` 沿用普通插件授权；`DeviceExecutionDriver` 沿用请求日志、Claim 与本地确认，安装短期参数绑定确认 |
| `plugins/screen-reader`、`screen-capture`、`screen-control` | 各只调用一个内核原语的签名 `.alp`，不带网络实现或 UI 贡献 |
| `oai-core` | 同一设备发现与请求接口供 CLI、HTTP API 和 MCP 使用；不改 Agent 原生会话 |

App 对设备实现维持 `runtimeOnly(device-primitives)`；前端使用 `control-ports` 与
`capability-ports` 的公开入口，不必依赖 `RemoteScreenRuntime` 等内部类，也不必阅读 Relay 源码。

## 查询可用性与权限状态

```kotlin
val host = ScreenHostRegistry.current
val state = host?.status()
// host == null：当前宿主没有安装真实 provider，禁止假装可用。
// state.binding == null：没有可用的已认证设备事件连接。
// state.local?.session：本进程内有效的屏幕许可；不是 Android 系统权限。
// state.accessibilityError：无障碍未连接、锁屏或底层不可用的原因。
// state.captureError：独立截图源未授权、锁屏等原因。
// state.capabilityErrors[READ/CAPTURE/CONTROL]：该类能力当前的本地门禁结果。
```

`status()` 是只读诊断，可能顺便清理已经过期或绑定失效的旧会话，绝不会创建许可。
界面应同时展示系统权限、插件启用/配对授权和本地屏幕会话，避免以一个“已授权”布尔值代替三者。
允许定期刷新或在回到前台、系统设置返回和授权结果返回时刷新；状态不是永久承诺，执行时仍会重查。
Core 侧通过 `oai capabilities` / MCP `oai_capabilities` / `GET .../core/v1/capabilities`
读取 Android 最近发布的库存与绑定。发布有同步间隔，手机许可撤销立即阻止执行，即使远端尚未刷新。

## 无障碍系统设置入口（待前端接入）

前台 Activity 通过标准 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 打开系统无障碍设置。
需要直达服务详情时，可在系统支持的版本使用 `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`
和 `Settings.EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME` 指向
`com.openandroidintelligence.screen.ScreenAccessibilityService`；不支持则退回标准设置页。
服务通过 Manifest 的 `BIND_ACCESSIBILITY_SERVICE` 保护，由系统在用户主动启用后绑定。

前端不得执行 Root、ADB、Shell、`WRITE_SECURE_SETTINGS` 或自动开关权限。返回 Activity
后重新查询 `host.status().accessibilityError`；系统设置页面返回成功不等于服务已连接。
服务尚未连接、被停用或设备锁屏时必须明确显示对应状态。密码控件不会返回正文，也不能被输入或点击。

## 开始本地屏幕会话（待前端接入）

1. 用户先完成普通插件安装、启用和该配对的明确授权；Core 的账号开关另行生效。
2. 从 `host.binding()` 取得当前经过认证的事实，禁止自行构造账号、配对或连接代次。
3. 向用户分别说明 READ（读取其他应用语义树）、CAPTURE（取得像素）和 CONTROL（执行动作），
   只记录用户本次明确同意的集合。读取许可不能带出截图或控制许可。
4. 若包含 CONTROL，系统无障碍服务必须可用，并通过 `host.localTarget()` 读取当时目标窗口；
   目标绑定包含应用、窗口和显示几何。局部内容变化由每次请求的 `expectedRevision` 另外保护。
5. 在真实用户同意后调用如下 Interface。有效期必须在 1 毫秒至 30 分钟之间；建议界面选用更短时间。

```kotlin
val binding = host.binding() ?: return // 显示连接尚不可用
val permissions = setOf(ScreenPermission.READ) // 仅示例；来自真实用户本次选择
val target = if (ScreenPermission.CONTROL in permissions) host.localTarget() else null
val session = host.authorization.start(
    binding = binding,
    permissions = permissions,
    target = target,
    ttlMillis = 5 * 60_000L,
)
```

`start()` 只记录当前进程内的用户决定，不授予配对、插件能力或 Android 权限，不可通过 CLI/MCP/API
调用。没有持久恢复，进程重启后必须重新取得用户同意。单进程只有一个活动屏幕会话，开始新会话会
结束旧会话、取消旧执行、清理旧图像和投影；因此应先开始本地会话，再为该会话取得新的系统投影授权。
不得在重新调用 `start()` 之前先开启新投影，否则旧会话清理会合理地关闭它。

## MediaProjection 系统授权（待前端接入）

远程截图使用 `RemoteScreenCaptureRegistry.source`。附件圈选持有的现有截图来源是另一实例，
不得借用其系统票据或像素。Android 14+ 的系统结果只允许建立一个 VirtualDisplay；旋转通过
调整该显示和 Surface 处理，不保存票据用于后台重建。

准确顺序：

1. 确认本地会话已包含 CAPTURE，且仍绑定当前账号/配对/连接。
2. 前台 Activity 调用 `source.createAuthorizationIntent()`，用平台 Activity Result 流程发起系统授权。
   返回 `null` 表示系统不可用，不能伪造结果。
3. 用户拒绝或结果数据缺失时，调用 `source.release()` 和
   `RemoteScreenCaptureRegistry.stopForeground(context)`，展示拒绝并保留明确不可用状态。
4. 真实 `RESULT_OK` 返回后调用 `RemoteScreenCaptureRegistry.startForeground(context) { ... }`。
   只有回调到来后，服务才已完成 `startForeground`，此时才转交 `source.onAuthorizationResult(code,data)`。
   `startForegroundService()` 返回本身不是服务就绪证明。
5. 授权弹窗可能跨越断连或会话到期。在回调内重新查询当前 binding 与
   `authorization.requirePermission(binding,CAPTURE)`，核对原会话 ID。若已失效，不转交结果，并释放
   source 和远程 FGS owner。建立会话失败同样停止远程 owner。
6. 再次查询可用性，等待普通能力发布生效后允许调用截图。后续每次请求仍独立重查本地与系统授权。

前台服务的现有通知及文案未改。服务为本地圈选和远程截图分别记录 owner；结束远程来源只释放其 owner，
不会停止仍由本地圈选使用的前台服务。所有权包含启动代次，过期排队回调不能开启另一轮授权。
系统停止投影、屏幕会话到期、撤销或断连时会清理远程投影及其图像缓存。显式用户取消整个会话应调用
`host.revoke()`，无需让前端重复掌握 VirtualDisplay、ImageReader 等底层对象。

## 读取、截图和动作调用

远程使用三个能力 ID 和各自已发现的 Schema。前端若需要本地只读预览，可通过
`host.localSnapshot()` 取得真实系统树；它是可信本地代码入口，不是远程读取许可。
远程调用一律通过 Core 通用设备接口，不能直接暴露原生 backend 或借前端身份代替受限设备凭据。

读取返回 `revision`、`hash`、`display`、前台包名、活动窗口、多窗口元信息和扁平节点。
节点 ID 只有与该次 `revision` 一起才有意义；系统事件、节点变化、窗口切换或旋转使旧引用失效。
`operation=state` 不返回节点，`previousHash` 比较可观察内容；不会启动常驻截图或第二条事件通道。
超过节点、深度或字节预算的结果显式 `truncated`，禁止把它当作整屏的完整证据。

截图分三步：`capture` 得到 imageId 和摘要/长度/尺寸/期限；按 `chunkSize=24576` 从 offset 0
逐块 `read`；消费后 `release`。PNG 内存缓存最多两帧、每帧最多 8 MiB、最长 60 秒且不超过本地
会话期限。每次取块都验证原账号、配对、授权代次和会话 ID；一个新会话不能读取旧会话的句柄。
调用者需校验字节总数和 SHA-256，再显示图像；收到过期、撤权或取消后丢弃未完成的拼接数据。
Core 已返回的分块结果仍受既有请求结果保留规则管理，消费后调用 `ack` 清理临时正文；本地图像
`release` 不等于确认所有 Core 请求，也不能回收已经交付给调用者的副本。

截图的像素尺寸不自动等于全屏物理坐标：`maxWidth` 会缩放 PNG，Android 14+ 的单应用共享还可能
只包含用户选定应用。不能直接把 PNG 中的像素点当作控制坐标；应优先使用读取结果的节点及其
物理 `bounds`，只有已明确确认裁剪范围和缩放关系时才换算坐标。本阶段不承诺多显示器控制或
为单应用投影推断未经系统提供的全屏映射。

控制包括语义点击/长按/滚动/输入、物理像素坐标点击与滑动/拖动、返回/主页等白名单全局操作、
通过系统 launcher Intent 启动明确包名。优先选择节点 ID；坐标须对应最近读取的分辨率和旋转。
`display.rotation` 是 Android Surface 的 0/1/2/3，分别对应 0/90/180/270 度。
启动应用只使用 PackageManager 与系统 Intent；系统后台启动限制、不可见或无启动入口均是可报告失败，
不得追加 Shell 启动路径。

## 本地确认与执行结果

控制插件仍被归为 `write`。现有 `DeviceExecutionDriver` 仅在 OAI 处于前台且用户通过既有确认
流程同意后，才把原 requestId、规范参数摘要和期限安装到 `ScreenLocalAuthorization`。
`DefaultControlAuthorizer` 再次核对完整代次、窗口、本地会话和该确认。远程 JSON 中不存在可接受的
confirmation、账号、配对或 start-session 字段。`installConfirmation`、`connect`、
`connectionChanged` 等公开方法仅供宿主装配代码，前端不得将其包装为远程可调用接口。

当前已有确认交互依赖 OAI 前台。**跨应用控制所需的本地确认和会话入口仍待前端设计接入**，
不能声称切到任意目标应用后已经能够无交互控制。确认期间如果窗口/显示或快照发生变化，旧请求
安全返回过期引用或窗口变化；应重新读屏并创建新的参数和幂等键，重新取得本地确认。不得篡改旧请求
参数、自动重放控制动作或把过期确认迁移到新请求。必要的新前端交互由后续独立任务实现。

成功的 `performed=true` 表示 Android 接受语义/全局操作，或手势完成回调已到达；不保证业务流程
已经完成。返回的是实际立即观察到的屏幕，不等待或伪造“页面稳定”。控制许可不附带 READ 许可；
未获 READ 时返回 `screen=null` 和 `SCREEN_READ_PERMISSION_REQUIRED`。
已操作后读树失败可保留 `performed=true` 并给出 `stateError`。已派发手势通常不能从 Android 撤回；
拖动保持阶段可能已触发长按，取消、超时或撤权后无法确认最终副作用时返回 `outcome_unknown`。
前端应显示结果待核实并重新读取屏幕，禁止自动重试。

## 结束、撤销、切换账号/设备与断连

用户主动停止：调用 `host.revoke()`。它撤销本地许可、清确认、取消运行中的屏幕请求、清除像素缓存，
停止远程投影及其 FGS owner。后续调用会安全拒绝；重新开始需要新的明确本地同意。

会话到期由单线程定时器主动清理，即使没有新的远程请求也会停止投影。系统无障碍服务断开会撤销
屏幕会话；MediaProjection 系统撤销会立即清理截图缓存、停止投影并取消截图请求，保留独立的
READ/CONTROL 本地许可，但 CAPTURE 因系统授权失效仍不可用。锁屏时不能读屏或操作，截图在采集/交付前后
都复查锁屏与投影可用性。设备事件连接丢失会撤销内存许可，重连仅恢复认证连接，不恢复屏幕许可。

`GatewayRuntime` 已在登录会话建立、事件流状态变化、退出/换账号和授权变更处连接这些生命周期；
内核紧急停止也清理屏幕会话。前端切换账号/设备前仍应主动调用 `revoke()`，丢弃本地展示的快照、
节点引用、imageId 和未完成操作；严禁把一个账号的许可、确认、图片或结果带到另一账号或设备。
不要从前端自行调用 `connect()` 来“恢复”一个旧 binding。

## 错误处理与后续验收

区分 Core 请求终态 `denied`、`failed`、`cancelled` 和 `outcome_unknown`。具体安全错误码和封装路径
见[接口错误表](android-screen-interfaces.zh-CN.md)。常见情况：

- `SCREEN_CONNECTION_REQUIRED` / `SCREEN_SESSION_REQUIRED`：连接或本地会话尚未建立，等待真实用户流程。
- `ACCESSIBILITY_SERVICE_UNAVAILABLE` / `SCREEN_CAPTURE_AUTHORIZATION_REQUIRED`：提示对应系统授权尚不可用。
- `SCREEN_PERMISSION_REQUIRED` / `SCREEN_SESSION_EXPIRED` / `SCREEN_SESSION_BINDING_CHANGED`：丢弃旧授权状态，重新展示用户许可流程。
- `SCREEN_STALE_REFERENCE` / `SCREEN_WINDOW_CHANGED`：重新读屏，让用户确认新的目标后创建新请求。
- `SCREEN_DEVICE_LOCKED` / `SCREEN_TARGET_NOT_INTERACTABLE`：显示明确原因，不自动改用坐标绕过。
- `SCREEN_IMAGE_UNAVAILABLE`：图像已过期、已释放或属于其他会话，丢弃本地拼接缓存。
- `outcome_unknown`：先读取状态/核对设备，不自动重放点击、输入、拖动或启动。

本次自动化证据包括原生树/动作测试、投影编排和资源所有权测试、内核原语授权/清理测试，以及
Core 的真实 HTTP/CLI/MCP 与签名设备协议替身。它们不能证明真实设备的无障碍、系统投影弹窗、
多厂商后台限制或跨应用业务结果。后续前端验收必须在实际 Android 12+ 设备上覆盖授权拒绝/允许、
弹窗和分屏、旋转/单应用共享、节点失效、锁屏、系统撤销、断网、取消、切换账号、紧急停止和现有
附件圈选回归。没有可用设备时不得把这些项标记为已通过。
