# Android 屏幕读取与控制：前端界面实施计划

- 状态：已实施（PR #50 → `dev`）
- 日期：2026-10-27
- 依据：[前端交接](../guides/android-screen-frontend-handoff.zh-CN.md)、[接口说明](../guides/android-screen-interfaces.zh-CN.md)
- 范围：`apps/android/app` 新增屏幕能力界面与授权流程；不改动内核原语、传输协议、Core 调用面

## 一、背景与边界

后端已交付（commit `eb5f7f90` + `70f2af20`）：`ScreenHostRegistry.current` 在
`PrimitiveBootstrapProvider.onCreate()` 中经 `ScreenPrimitiveInstaller.install(app)` 装配，
提供 `status()` / `binding()` / `authorization.start(...)` / `revoke()` / `localTarget()` 等
可信本地入口。三个插件 `screen-reader` / `screen-capture` / `screen-control` 的
`ui.settings` 与 `ui.cards` 均为空，界面完全由宿主 App 提供。

本次要补的就是交接文档第 9-12 行列出的「待接入」清单：

1. 屏幕会话的本地同意入口；
2. READ / CAPTURE / CONTROL 三类权限的状态展示；
3. 无障碍设置入口；
4. 远程截图专用的 MediaProjection 授权回调；
5. 开始 / 结束 / 撤销入口；
6. 面向其他应用的本地确认体验。

**不做**：不改 `control-ports`、`capability-ports`、`device-primitives`、`screen-control`
的公开签名；不加 Root/ADB/Shell 或自动开关系统权限；不伪造任何授权状态。

## 二、现状盘点（已核实）

| 事实 | 位置 | 结论 |
| --- | --- | --- |
| 宿主已装配真实 provider | `device-primitives/.../PrimitiveBootstrapProvider.kt` 调用 `ScreenPrimitiveInstaller.install(app)` | `ScreenHostRegistry.current` 在 App 进程中非空，可直接读 |
| `control-ports`、`capability-ports` 已是 app 直接依赖 | `app/build.gradle.kts:284-285` | 无需改依赖 |
| 无障碍服务已声明 | `screen-control/src/main/AndroidManifest.xml`，类名 `com.openandroidintelligence.screen.ScreenAccessibilityService` | 只需 UI 跳转系统设置，无需改 Manifest |
| 本地同意流程完全缺失 | — | 本次实现 |
| 远程截图专用 MediaProjection 授权缺失 | 既有 `MainActivity` 的 `screenCaptureAuthLauncher` 服务的是**附件圈选**来源，不是 `RemoteScreenCaptureRegistry.source` | 本次新增独立授权路径 |
| 写操作确认框已存在但内容不合规 | `MainActivity.kt:165-174` 直接打印 `pluginId` / `capability` / 原始 JSON 参数 | 本次改造成用户可读文案 |
| 设计令牌守卫测试会拦截硬编码 | `MaterialTokenGuardTest.kt` | 必须用 `AppRadius` / `Dimensions` / `MaterialTheme` |

## 三、设计原则

1. **三层授权分开展示**：系统权限（无障碍 / 投影）、插件授权（安装+启用+配对授权）、
   本地屏幕会话，三者独立，绝不用一个「已授权」布尔值代替。
2. **诚实降级**：`ScreenHostRegistry.current == null` 时明说「当前版本未接入屏幕能力」，
   不渲染点了不生效的开关（复用既有 `CapabilityPresentation` 模式）。
3. **本地同意必须真实**：READ / CAPTURE / CONTROL 三项由用户分别勾选，只记录本次同意的
   集合；`start()` 只在用户点「开始」后调用。
4. **不跨会话复用**：切换账号/设备前主动 `revoke()`，丢弃快照与授权状态。

## 四、实现清单

### 4.1 数据层：`ScreenCapabilityViewModel.kt`（新增）

把 `ScreenHostRegistry.current` 的真实状态投影成不可变 UI 状态，供 Composable 纯渲染：

```
data class ScreenCapabilityUiState(
    val providerAvailable: Boolean,          // ScreenHostRegistry.current != null
    val bindingAvailable: Boolean,           // host.binding() != null
    val accessibilityError: String?,         // 无障碍未连接原因
    val captureError: String?,               // 独立截图源未授权原因
    val capabilityErrors: Map<ScreenPermission, String?>,
    val session: ScreenLocalSessionUi?,      // 本地会话（权限集合 + 到期时间）
    val denialCode: String?,
)
```

- `refresh()` 调用 `host.status()`（只读诊断，绝不创建许可）；
- 生命周期：`ON_RESUME`、授权结果返回、系统设置返回时刷新；
- 会话到期用倒计时文本呈现（来自 `expiresAtEpochMs`），到期后刷新。

### 4.2 本地同意：`ScreenSessionConsentDialog`（新增）

M3 `AlertDialog`，三行 `Checkbox`：

- 「读取屏幕内容」READ：读取其他应用的界面结构（文字、按钮位置）；
- 「截取屏幕画面」CAPTURE：取得屏幕像素，单独的系统投屏授权；
- 「操作其他应用」CONTROL：点击、输入、滑动。勾选此项时必须先有可用无障碍服务，
  并调用 `host.localTarget()` 绑定当前目标窗口；缺失时该行禁用并说明原因。

有效期选择：`5 分钟` / `10 分钟` / `30 分钟`（不超过 `MAX_SCREEN_SESSION_TTL_MS`），
默认 5 分钟。确认按钮文案「开始屏幕会话」，禁用条件为 `permissions.isEmpty()`。

确认后：`host.authorization.start(binding, permissions, target, ttlMillis)`。
捕获 `ScreenAuthorizationException` / `IllegalArgumentException`，把 `code` 映射成
用户可读文案，**不显示原始错误码**。

### 4.3 权限状态卡：`ScreenCapabilitySection`（新增）

放到「授权管理」子页（`SettingsRoutes.PAIRING`）的一个新分组「屏幕读取与控制」：

- 一行总述：供应商可用性 + 当前会话状态（未开始 / 进行中 剩余 N 分 M 秒）；
- 三个 `SettingsListItem` 分别呈现 READ / CAPTURE / CONTROL 的当前门禁结果；
- 无障碍入口：`SettingsNavigationItem` → `Settings.ACTION_ACCESSIBILITY_SETTINGS`
  （API 支持时优先 `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` + `EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME`，
  不支持则退回标准页；`ActivityNotFoundException` 时提示「系统未提供无障碍设置入口」）；
- 截图授权入口：仅在本地会话已含 CAPTURE 时可用，点击走 MediaProjection 授权流程；
- 两个动作按钮：「开始屏幕会话」（打开同意弹层）/「结束并撤销」（`host.revoke()`）。

### 4.4 MediaProjection 授权接线

在 `MainActivity` 新增一个 `rememberLauncherForActivityResult(StartActivityForResult())`，
专门服务 `RemoteScreenCaptureRegistry.source`（与既有圈选来源**不是**同一个实例）：

严格顺序按交接文档 4.1-4.6：

1. 确认本地会话含 CAPTURE 且 binding 仍有效；
2. `source.createAuthorizationIntent()`，为 null 则提示系统不可用；
3. 拒绝/数据缺失：`source.release()` + `RemoteScreenCaptureRegistry.stopForeground(context)`；
4. `RESULT_OK`：先 `RemoteScreenCaptureRegistry.startForeground(context) { ... }`，
   在就绪回调内再 `authorization.requirePermission(binding, CAPTURE)` 核对原会话 ID，
   失效则不转交结果并释放 source 与 FGS owner；有效才 `source.onAuthorizationResult(...)`；
5. 建立失败同样 `stopForeground`；成功/失败都给出明确提示并刷新状态。

### 4.5 本地确认体验改造

把 `MainActivity.kt:165-174` 的原始 JSON 弹窗换成 `ScreenControlConfirmationDialog`：

- 标题：「允许这次屏幕操作」；
- 正文：用人话解释即将做什么。从 `DeviceConfirmation.capability` 识别是否为
  `screen-control`，解析 `parameters` 中的 `action` 字段映射成
  「点击 / 长按 / 输入文字 / 滑动 / 拖动 / 滚动 / 返回 / 主页 / 打开应用」，
  并显示目标包名（如有 `packageName`）或节点文本摘要（如有 `nodeId` 则只说「界面上的某个控件」，
  绝不展示 nodeId 原文）；
- 非屏幕能力（短信、日历等）保持既有行为但同样去掉 `pluginId` 原始字符串，
  改为插件可读名；
- 原始参数只在「查看技术详情」折叠区里以等宽字体可选展示，默认折叠。

确认按钮「允许这次操作」/ 拒绝「不允许」。超时（`DeviceExecutionDriver` 内部 30 秒）
后弹窗自动关闭，由既有逻辑返回 `LOCAL_CONFIRMATION_REQUIRED` 拒绝。

### 4.6 路由接线

`SettingsRoutes` 新增 `SCREEN_CAPABILITY = "settings/screen-capability"`，
在 `NavHost` 注册，`SettingsOverviewScreen` 的「设备权限授权」区与
`PairingSubScreen` 各放一个 `SettingsNavigationItem` 入口。

## 五、文案原则

沿用 `docs/superpowers/specs/2026-10-24-settings-information-architecture.md`：

1. 不出现内部术语：`SCREEN_SESSION_REQUIRED`、`r{N}`、`binding`、`provider`
   一律不出现在用户可见文案里，映射成「需要先开始屏幕会话」「还没有连接到已配对的设备」等；
2. 陈述可执行事实：能不能用、为什么不能用、怎么才能用；
3. 颜色不是唯一信号：每个状态都同时有文字；
4. 诚实降级不变量：未装配 / 未授权时明说，绝不渲染假开关。

## 六、验证

1. `:app:testFullDebugUnitTest` — 含 `MaterialTokenGuardTest`、`ArchitectureBoundaryTest`；
2. 新增单元测试：
   - 状态映射：provider 缺失 / binding 缺失 / 三类错误的纯函数投影；
   - 错误码 → 用户文案映射覆盖交接文档「错误处理」章节列出的全部码；
   - 同意集合的边界：空集合不能开始；CONTROL 无 target 时必须被拒绝；
   - 确认文案解析：各 `action` 映射正确，且不泄露 nodeId 原文；
3. `:app:assembleFullDebug` 编译通过；
4. 真机项（无障碍授权、系统投影弹窗、旋转、锁屏、撤权）**无设备时不标记为已通过**，
   在交付说明中如实列为待真机验收。

## 七、实施结果（2026-10-27）

### 实际验证证据

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| Kotlin 编译 | `:app:compileFullDebugKotlin` | 通过 |
| APK 打包 | `:app:assembleFullDebug` | 通过（`app-full-debug.apk`，约 64 MB） |
| 新增测试 | `--tests "com.openandroidintelligence.mobile.screen.*"` | 32 个全部通过 |
| 令牌守卫 | `MaterialTokenGuardTest` | 通过（无硬编码色值 / 离栅格圆角） |
| 架构边界 | `ArchitectureBoundaryTest` | 通过 |

新增测试类：`ScreenCapabilityPresentationTest`（10）、`ScreenErrorMessagesTest`（7）、
`ScreenControlCopyTest`（12）、`AccessibilityServiceWiringTest`（1）。

### 环境说明（复现构建需要）

1. `local.properties` 在 worktree 中需自行创建（已被 `.gitignore` 忽略）；本机
   `sdk.dir` 指向 `.toolchains/android-sdk`；
2. 单元测试需要 **JDK 21**：Robolectric 对 compileSdk 36 会校验
   `Android SDK 36 requires Java 21`，JDK 17 下大量既有测试直接报
   `UnsupportedOperationException`，与本次改动无关；
3. Robolectric 的 `android-all-*.jar` 由 `testOptions` 固定到
   `<project>/.gradle/robolectric-home`，worktree 需先补齐该缓存。

### 与交接文档的一处偏离

文档提到可用 `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` +
`Settings.EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME` 直达服务详情页。核实
`android.jar`（compileSdk 36）后确认**这两个常量不在公开 SDK**，只有
`Settings.ACTION_ACCESSIBILITY_SETTINGS`。硬拼裸字符串属于依赖非公开 API，会在不同
厂商 ROM 上行为不一致，因此采用文档允许的退让路径（只用标准设置页），并在代码注释中写明原因。

### 未能验证的部分

本环境 `adb devices` 无可用设备，以下项**未真机验证，不得视为已通过**：
无障碍系统授权、MediaProjection 系统弹窗、分屏/旋转、节点失效、锁屏、
系统撤销、断网、取消、切换账号、紧急停止、附件圈选回归。
