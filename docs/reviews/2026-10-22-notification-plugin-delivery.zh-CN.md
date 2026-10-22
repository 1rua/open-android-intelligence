# 通知插件交付与未安装误判修复

## 定位与范围

基线 App 为 `4142decffcc13f1ed1a2f48eea0ba6c39a2540fe`。截图出现的
`open_android_capabilities` 对应 Hermes 设备工具链；配套修复为
[Hermes PR #5](https://github.com/1rua/hermes-android-gateway/pull/5)，提交
`190441415652ae34fbd1cb8cc485f6208fff944b`。

本轮审查 Android 采集模块、插件安装与内核、能力发布器、Hermes Setup、设备能力发现、
签名请求路由和 Agent 工具注册。未连接用户的手机或生产 Gateway，也未取得截图对应的版本和
完整工具返回值；以下结论来自源码复现与受控测试，不能据截图断言生产设备究竟缺哪项授权。

## 两条可复现的原因

1. **App 发布没有提供能力插件。** 原 APK 流程只构建 Android 代码；通知 Rust/WASM 只在
   CI 测试中编译，真实 `.alp` 只由测试或开发者手动生成。`ProductionPluginHost.reload()`
   只注册已安装且验签成功的 `.alp`，所以新装 Full APK 即使连接 Gateway、授予通知使用权，
   仍没有 `org.openandroidintelligence.notifications.query`。在完整空 inventory 下，
   `PLUGIN_NOT_INSTALLED` 是真实状态，修改提示或直接调用宿主原语不能补出插件。
2. **加载失败被当成完整清单。** 已安装包验签、清单解析或运行期装配失败后，其能力被移除，
   但原发布器仍发送完整 inventory。Gateway 因而可能把损坏安装误报成未安装。
   安装目录名与未验证 manifest ID 不一致时也必须视为失败，不能借另一目录中的合法包完成验证。

现有 Hermes 基线已包含按账号保存的通知 Setup 开关、独立权限状态和配对代次恢复；本轮保留并
回归这些实现。系统通知访问、插件启用、配对授权、本机采集策略、监听连接和 Gateway 开关分别
控制不同门控，连接成功不代表通知可读。此前注册给 Agent 的工具描述也缺少明确的通知路由和
终端回退限制；配套 PR 将这项说明放入实际工具描述。

## 实现

- Full APK 随附使用现有 Ed25519 包格式签名的真实通知 Rust/WASM，插件管理提供显式安装入口。
  安装继续走 `AlpVerifier` / `PluginInstaller`，固定通知插件 ID，默认停用、无授权，拒绝覆盖
  任何已有同 ID 安装。Play 不包含该包。旧版或无附带包的本机构建仍可导入外部签名包。
- 通知配对授权后引导现有采集设置、应用白名单、字段范围与系统通知使用权；系统设置打不开时
  显示可操作提示。系统设置 action 原有值与平台常量一致，不是根因。
- `loadFailures` 只在本机提供受限诊断。清单不完整时省略**既有的可选** inventory 字段，Gateway
  沿用 `ANDROID_STATE_UNKNOWN`，仍可保留其他已验证的真实 bindings；没有新协议版本、伪造
  capability 或放宽授权。故障包修复/移除后恢复完整清单。
- 损坏或清单 ID 与目录不一致的包只显示确认卸载入口，按实际目录处理，不能操作伪装清单指向的
  另一插件。卸载清理该插件的作者授权；同作者重新安装也须再授权，共享原语及其他插件授权保留。
- Hermes 实际注册的两项工具明确：当前手机通知先发现能力，仅在 `READY` 后按实际 ID、版本和
  Schema 调用 `open_android_device`；未就绪时按原状态引导设置，禁止回退宿主 `terminal` / `adb`。
- 两个发布工作流编译真实 WASM、私钥签包、强制要求 bundle，并逐字节验证最终 APK 的资产。
  CI 使用随机隔离测试作者，缺资产时端到端测试直接失败，不会回退到测试包掩盖打包遗漏。

## 验证

- Node/协议/集成/tooling：61 个测试文件、716 项通过；根工程与 plugin-tooling 类型检查通过。
  运行时排除独立插件检出的嵌套套件，避免把其缓存契约当成本仓测试再次收集。
- 跨宿主冻结向量：Hermes、OpenClaw 各 70/70；结果哈希比对 3 项通过。
- Rust：真实 `wasm32-unknown-unknown --release` 编译成功，39 项测试通过；既有 1 项文档例子忽略。
- Hermes：能力/工具/实际注册/Setup/Manifest/宿主接口专项 46 项通过，主线程独立重跑通过。
  Manifest 检查、配套契约 pin 检查通过；配套 PR 的 GitHub CI 通过。
- Android 采集器/宿主/原语/内核：114 项通过，`noVpnSurfaceCheck` 通过。
- Android App：71 项通过，0 失败、0 跳过；两组 Android 合计 185 项通过。包含真实 APK asset →
  验签安装 → 显式启用/配对 → 系统权限/策略/监听 → 能力发现 → 签名 HTTP 请求 → WASM → 当前通知
  的端到端测试；覆盖权限撤销、开关、重连、发布器重建、损坏包未知状态与恢复、卸载重装重新授权。
- SDK-free 通知运行期与端口边界：17 项通过；发布元数据 19 项、APK 包字节校验 1 项通过。
  工作流 YAML 与 shell 语法检查通过。
- Gradle 资源阶段实测：输入包与生成资产一致、移除输入清理旧资产、必需包缺失时明确失败，三项通过。
- `assembleFullDebug`、`assemblePlayDebug` 成功；最终 Full APK 资产与签名包逐字节一致，固定调试
  签名校验通过；最终 Play APK 不包含 `assets/bundled-plugins/`。

测试运行中最初缺少 Robolectric 依赖的代理配置；给测试 JVM 配置当前环境代理和 Maven Central 后
依赖下载及库测试通过。生产源码没有因测试环境而更改权限或网络校验。

Android 复现时先按 Android README 构建真实签名包，配置 `HERMES_PLUGIN_ROOT`、
`OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` 与可运行插件夹具的 Python，再运行：

```sh
cd apps/android
OAI_BUNDLED_NOTIFICATIONS_ALP=/absolute/path/notifications.alp ./gradlew \
  -Poai.requireBundledNotifications=true :app:testFullDebugUnitTest \
  --tests '*CapabilityPublisherTest' --tests '*NotificationCapabilityHttpTest' \
  --tests '*ProductionPluginHostTest' --tests '*PluginInstallStoreTest' \
  --tests '*PluginManagementScreenTest' --tests '*NotificationAccessSettingsTest' \
  --tests '*NotificationCollectionScreenTest' --tests '*ArchitectureBoundaryTest' \
  :app:assembleFullDebug :app:assemblePlayDebug
```

## 发布前置条件与验收边界

官方预览版和正式版发布需要仓库 secret `OAI_PLUGIN_SIGNING_KEY_BASE64`，值为私有 32 字节
Ed25519 seed 的 base64 编码；同一发行者应持续使用相同密钥。缺失或使用公开 fixture seed 时
发布失败。配置信息见 [Android README](../../apps/android/README.md#bundled-notification-plugin)。
本轮没有创建生产私钥、写入仓库 secrets、发布 APK 或修改生产 Gateway；当前凭据也不能读取
Actions secrets 配置，因此未宣称该配置已经就绪。

需要同时升级配套 Hermes 插件和 Full App。用户在手机显式安装、启用、授权并配置应用范围后，
再通过 `hermes gateway setup` 开启账号通知能力。关闭开关或撤销权限仍必须拒绝后续读取。
Robolectric 的通知和系统权限是合成测试场景；真实厂商通知设置、系统后台行为与生产 Agent
模型的工具选择仍需真机验收。本轮不宣称给 OpenClaw 增加 Hermes 特有的 Setup/inventory 扩展；
其冻结协议兼容性另外通过跨宿主向量验证。
