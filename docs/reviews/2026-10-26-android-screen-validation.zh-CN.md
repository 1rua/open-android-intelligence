# Android 屏幕能力验证记录

开发基线：`dev` 的 `736e458c481aa7a79b5e2d984efda7bcaf4c1287`。
2026-10-27 提交前已更新到最新 `dev` 的 `5028c1f553f4b4b299e2955a35edc43aab4a1118`，
保留上游会话界面恢复和命令目录改动；本次屏幕补丁相对该集成基线仍无 UI 差异。
成果提交与远端 CI 以 PR 的 head SHA 为准，不能把本地工作区结果替代远端结论。
本次没有向设备安装 App、读取用户实际屏幕、改变账号资料或操作现有部署。

## 已确认的架构与复用

基线已有 `control-ports` 的闭合控制动作和 `DefaultControlAuthorizer`、`capability-ports`
中的 MediaProjection、设备请求 Claim/日志和普通受保护插件注册机制，以及 Core 的通用 CLI/API/MCP。
基线没有把真实 Accessibility 执行器和远程截图接到这些入口。

本次增加 `screen-control` 原生实现、三个独立受保护参考插件和内核原语，并在原请求执行器内
接入本地许可、确认、取消和错误。没有改 `gateway-contract` 的现有协议 Schema、版本或核心哈希，
没有改 `oai-core` 的生产设备路由或任何 Agent 会话适配器。

Relay 固定来源和 MIT 逐文件说明见[原生审查](2026-10-26-android-screen-native-review.zh-CN.md)。
公开接口见[接口文档](../guides/android-screen-interfaces.zh-CN.md)，
未来前端的调用顺序和未接入状态见[前端交接](../guides/android-screen-frontend-handoff.zh-CN.md)。

## Core、插件与协议自动化

| 检查 | 实际结果与证据边界 |
| --- | --- |
| Core 全部非固定 Hermes SDK 用例 | 238 passed；含 17 项新屏幕测试。真实 HTTP、CLI/MCP 和签名设备协议替身，不是 Android 真机 |
| 新屏幕及原 HTTP/CLI/MCP 聚焦回归 | 44 passed；相同幂等键从 CLI/MCP 产生同一请求，重复 Claim、结果回传、ack、取消、过期和撤权均验证 |
| 插件打包工具 | 86 passed，TypeScript 检查通过；真实 WASM、确定性打包和签名验证 |
| 新增 Rust 插件 | 6 项单元测试通过；全部六个参考插件 WASM 编译和测试作者签名 `.alp` 构建通过 |
| APK 插件资产验证器 | 7 passed；验证映射已包含六项 |
| 根项目 TypeScript 检查 | `npm run typecheck` 通过，包含协议、工具与插件工具链 |
| 通用 Gateway 契约 | 171 项通过；3 项跨旧宿主用例未通过，因为缺少固定的 `.openclaw-gateway-plugin` checkout，不能声称旧双宿主互通已验证 |
| 与本次直接相关的 Python 静态检查 | 36 项通过，覆盖控制/采集端口、包资产和通信限制 |

Core 复现命令（先按项目文档安装 Core 与测试依赖）：

```sh
python -m pytest oai-core/tests -q \
  --ignore=oai-core/tests/test_hermes.py \
  --ignore=oai-core/tests/test_hermes_native.py
```

两个固定 Hermes SDK 测试文件未纳入这次本地 Core 运行；本次没有修改其会话适配器。
协议替身验证的是调用链、身份、状态和结果，不证明手机无障碍权限或投影系统弹窗可用。

## Android 自动化与构建

工具链为 Gradle 8.12、Android compile SDK 35/36、Java 源码级别 17；框架测试使用
Robolectric 4.14.1/4.16，App 的 API 36 矩阵由 JDK 21 运行。
Full/Play 的最低系统版本仍为 API 31，target SDK 仍为 35。

第一轮整合实际执行并通过 `control-ports` 13 项、`capability-ports` 51 项、
`screen-control` 17 项、`device-primitives` 21 项，以及 Full/Play Kotlin 编译。
随后补充了无障碍中断语义、授权和图像有效期不被系统时钟调整延长的回归，最终结果见下表；
新增用例只有实际执行通过后才纳入最终数量。

最终 Android XML 报告与构建结果：

| 范围 | 结果 |
| --- | --- |
| `control-ports` | 14 项通过，0 失败，0 跳过 |
| `capability-ports` | 51 项通过，0 失败，0 跳过 |
| `screen-control` | 18 项通过，0 失败，0 跳过 |
| `device-primitives` | 22 项通过，0 失败，0 跳过 |
| Full App 最新 `dev` 整合回归 | 42 项通过，0 失败，0 跳过；含 API 31–36、组合根、请求恢复、能力发布、屏幕错误和上游新会话仓储 |
| Play App 最新 `dev` 同组回归 | 41 项通过，1 项既有运行时插件前提不成立而跳过，0 失败；屏幕及 API 31–36 用例执行通过 |
| Full/Play Debug APK | 均成功构建；先前离线缓存缺少 Guava 的尝试未成功，补齐真实依赖后最终构建通过 |
| APK 内容 | Full 六个 `.alp` 与本次签出包逐字节一致；Play 无 `.alp`；两种 APK 的 MIT 文本均与源码原文一致 |
| `noVpnSurfaceCheck` | 通过，没有增加网络控制面 |

App 回归使用 `ScreenExecutionErrorsTest`、`PluginCapabilityRiskTest`、
`DeviceExecutionRecoveryTest`、`CapabilityPublisherTest`、`ApplicationCompositionTest` 和
`AndroidApiCompatibilityTest` 的同组筛选。更新到最新 `dev` 后加入
`CoreAgentConversationRepositoryTest`，Full/Play 分别重新运行并重建 APK；Core 的 238 项也在该
集成基线上重新运行通过。未把跳过项计为通过；跳过项为 Play 禁止运行时插件时的
`unreadableInstallationOmitsInventoryAndLostAckRecoveryDoesNotClaimAbsence`。

框架与设备原语复现命令：

```sh
cd apps/android
./gradlew :control-ports:testDebugUnitTest :capability-ports:testDebugUnitTest \
  :screen-control:testDebugUnitTest :device-primitives:testDebugUnitTest \
  --no-build-cache
```

APK 验证必须分别确认 Full 内的六个 `.alp` 与本次签出包逐字节一致，Play 未附带这些动态包，
两种 APK 都携带完整 `assets/licenses/hermes-relay-MIT.txt`。合并 Manifest 应保留系统
`BIND_ACCESSIBILITY_SERVICE`、MediaProjection 的权限与前台服务类型。

## 既有失败与不具备的验证条件

全套 Python 静态检查为 132 项，其中 11 个断言失败和 1 个异常。在干净固定 `dev` worktree
逐项重跑这 12 项，复现完全相同的失败集合；没有通过改无关功能或放宽断言掩盖它们。
具体方法名和浅克隆导致发行工具拒绝的证据见[静态基线对照](2026-10-26-android-screen-static-baseline.zh-CN.md)。

本地没有可用的 Android 设备。真实系统授权、截图像素、实际跨应用点击、输入法、厂商限制、
分屏/旋转和长期资源占用尚未验收。远程入口缺少本地用户许可或系统授权时保持拒绝；
不得将测试替身或编译成功描述为已具备用户设备授权。

## 前端范围

已自动比较 `conversation-ui`、`plugin-ui`、App 资源和所有既有会话/设置/权限/引导页面，
相对开发基线没有差异。新增 XML 仅为无障碍服务配置，新增资产为 MIT 许可。
App 修改限于非 UI 生命周期、能力诊断和设备请求执行，所有待前端工作均在交接文档中明确标出。
