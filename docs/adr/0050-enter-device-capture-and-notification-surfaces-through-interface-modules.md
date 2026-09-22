# 0050 · 设备采集面（屏幕截图 / 通知）经接口模块进入 App

- 状态：已接受
- 日期：2026-09-23
- 关联：0034（插件管理入设置）、0040（设备能力以无特权参考插件发布）、`docs/superpowers/plans/2026-09-22-app-settings-gap-remediation-plan.md` §3.6 裁决 D6

## 背景

App 设置页需要集成两类设备采集能力：屏幕圈选的截图来源（MediaProjection）与通知采集（NotificationListenerService）。而 `ArchitectureBoundaryTest.kt:22-29` 明文禁止 `app` 依赖 `:notification-collector`、`:sms-collector`、`:call-log-collector`、`:transport`、`:tailnet-core`、`:capability-sync-runtime`。

## 决策

**不放宽上述断言**（它是 `docs/mvp/plugin-architecture-migration-evidence.md` 验收证据 #3 的机器可检证据），采集面改走「接口模块 + 运行期装配」进入 App：

1. **屏幕截图**：`ScreenCaptureSource` 接口与 MediaProjection 实现同在 `:capability-ports`（该模块不在禁依赖列表）；app 以 `implementation(project(":capability-ports"))` 依赖，采集编排不出模块。
2. **通知**：
   - 新增 **`:notification-control`**（纯接口模块）：`NotificationPolicyPort` / `NotificationBindingPort` / `NotificationControlRegistry`（deny-first 默认）。app 以 `implementation` 依赖，只见接口。
   - 新增 **`:notification-host`**（装配模块）：依赖 `:notification-collector` 与 `:policy-engine`（两者均不在禁依赖列表），实现端口并经库内 `ContentProvider`（`exported="false"`）在进程启动时自注册。app 以 **`runtimeOnly`** 依赖：Manifest 合并与运行期类加载照常发生，但 host 符号对 app **编译期不可见**，宿主代码无法 import 任何采集实现。
3. 未装配 / 未授权时端口一律 deny-first，设置页如实呈现「未装配 / 不可用」，不得渲染假开关。

## 后果

- `ArchitectureBoundaryTest` 原样保留，MVP 验收证据不失效；ADR 0040 的「设备能力以独立签名插件发布」不与本决策冲突——本决策只解决**宿主侧设置面与采集通路**的装配边界，插件分发仍走 `:plugin-package` 工具链。
- 后续任何人若想把 collector 直接写进 `app/build.gradle.kts`，会同时撞上架构测试与本 ADR。
- `runtimeOnly` + ContentProvider 是隐式装配：play 变体若省略该行，UI 必须仍能如实显示「未装配」（由 registry 的 deny-first 默认保证）。
