# App 设置功能缺口审计报告（三类问题分类）

- 日期：2026-09-22
- 范围：Android App 设置界面本体 + 其宿主实现（GatewayRuntime / PluginKernel / 审计 / 授权 / 外观）+ 插件侧设置 + Hermes 与 OpenClaw 双宿主后端与接口契约
- 验证强度：**静态分析为主 + 现有单测/契约测试佐证**。未修改任何源码或契约，未启动服务、未连接真机、未联机实测
- 结论性质：全部条目基于代码/契约/Schema 的静态核对，证据落到 `文件路径:行号` 或符号名；证据不足者一律标注「未确认」

## 0. 审查方法与判定口径

采用「UI 驱动枚举 + 契约反向对账」双向核对法：先以设置界面的 6 个概览模块、5 个子页、4 个二次确认弹窗为骨架登记每个可交互项的绑定与回调链，再沿调用链追到宿主落点，最后从契约/Schema/ADR/规格反向检查实现是否对等。

三类问题的可操作判定口径：

| 类别 | 含义 | 典型证据形态 |
|---|---|---|
| 类别 1 完全未实现 | 有明确需求、入口或声明承载，但全仓不存在实现符号或无任何消费方 | 仅有解析器无渲染器；`build.gradle.kts` 不含该模块；`register/enable` 无生产调用点；无 Manifest 组件 |
| 类别 2 仅前端展示、缺后端逻辑或数据支持 | 界面与本地状态存在，但缺数据源、缺消费方或效果不落地 | 按钮点击必然 no-op；开关只写本地 holder；展示值来自本地自增；纯静态 `SettingsListItem`；无 UI 消费的 notice |
| 类别 3 契约已定义但未实现或与契约不符 | 契约/Schema/ADR/规格有明确要求，实现缺失、只实现一端或语义相反 | 契约端点三端皆无；能力位一端声明另一端未声明；同名字段语义不同；字段名不符导致解析退化 |

裁决顺序（冲突时）：`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md` > `docs/contracts/gateway-protocol-v2.md` > `docs/contracts/device-plugin-package-v1.md` > `docs/adr/` > `CONTEXT.md` > UI 规格。

**结论摘要**：类别 1 共 5 项；类别 2 共 12 项；类别 3 共 19 项；另附「已验证正常」12 项与「未确认」4 项。

设置界面本体结构（供对照）：`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt`，概览模块见 `:375/432/496/541/568/588`，子页见 `:641/732/836/933/1035`，弹窗见 `:220/239/262/286`；ViewModel 见 `SettingsViewModel.kt`；环境对象见 `PlatformSettingsScreen.kt:18-27`。

**修复方案指针**：`docs/superpowers/plans/2026-09-22-app-settings-gap-remediation-plan.md`。该方案给出三类问题的可操作判定口径（三问判定法 + 证据分级 + 归类争议裁决 + 重分类回执）、36 项条目的优先级评分看板（R = 2S + 2U + 1.5C + 1.5B − 0.5D，阈值 P0/P1/P2）、8 个 Agent 的职责边界与文件所有权矩阵、协作接口契约（四条约定接口 + 接线只加一行）、Wave 0–3 依赖与并行策略、集成验证流程、进度跟踪机制、12 项风险控制、分阶段交付物与最终验收清单。**条目 ID 与本文档一一对应**；同根因条目的 Cluster 分组见方案 §1.5；本文档第 6 节的四项「未确认」对应方案 §2.3 与 A0 的裁决 D1–D4。

---

## 1. 类别 1：完全未实现的功能

### 1-1 设置中的插件管理区域（插件清单 / 安装 / 启用 / 卸载 / 状态可见性）

- **问题类别**：类别 1 完全未实现
- **判断依据**：
  - 需求明确：ADR 0034 要求「插件、权限、安全、审计与开发者信任模式统一位于设置中的平台管理区域」（`docs/adr/0034-keep-main-navigation-minimal-and-plugin-management-in-settings.md:8`）；现行规格逐字继承该要求（`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:102`）。ADR 0034 虽被 0042 取代，但 0042 保留声明式界面路径，故**不构成该项缺失的豁免理由**（`docs/adr/0042-allow-developer-trust-native-ui-takeover.md:11`）。
  - 实现缺失：设置界面的环境对象**不含任何插件服务**（`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsScreen.kt:18-27` 仅 trustMode/audit/auditSink/allowDeveloperTrustMode/kernel/pairingGrants/appearance）；`SettingsScreen.kt` 全文无 `plugin` 相关 import 与条目；内核生产装配为 `runtimes = emptyMap()`（`OpenAndroidIntelligenceApplication.kt:94`）；`PluginInstaller` 在 `app/src/main` 零引用。
  - 无任何界面可触发安装/更新/回滚/卸载（契约要求见 `docs/contracts/device-plugin-package-v1.md:249-274`）；`apps/android/app/build.gradle.kts:49` 虽依赖 `:plugin-package`，但 `app/src/main` 对 `plugin.pkg` 零引用，插件身份校验/安装/更新策略整包在宿主侧空转（另见 2-12）。
- **旁证**：`AppDestination.PluginManagement` 仅声明未接入，见 3-18。

### 1-2 插件声明式设置项与状态卡片的宿主渲染

- **问题类别**：类别 1 完全未实现
- **判断依据**：
  - 契约与规格要求宿主「验证并渲染插件提交的声明式设置项与状态卡片」：`docs/contracts/device-plugin-package-v1.md:145-148`（`ui.settings` / `ui.cards`）、`:243-247`（八类白名单组件）、规格 `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:112`（内核职责含「声明式 UI 渲染」）、`:102`。
  - 现状只有**解析器**、没有**渲染器**、也**没有消费者**：`apps/android/plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/DeclarativeUiSchema.kt:154`（`object DeclarativeUiSchema`）、`:148-152`（`UiContribution`）、`:37-48`（含 `SET_SETTING`）；该模块主源码只有这一个文件。
  - 全仓检索未命中任何渲染实现：`SettingsContribution`、`settingsItems`、`PluginSettings`、`StatusCard`、`SET_SETTING` 派发均为 0 命中（`SET_SETTING` 仅出现在 Schema 白名单与自身测试中）；`app` 虽依赖 `:plugin-ui`，但 app 源码零引用 `plugin.ui` 符号。

### 1-3 屏幕圈选的截图采集实现（Assist 截图 / MediaProjection / AccessibilityService）

- **问题类别**：类别 1 完全未实现
- **判断依据**：
  - UI 规格要求「圈选画框必须有外部真实截图，否则明确不可用；确认后才能回调」（`docs/superpowers/specs/2026-09-12-app-material-ui-and-motion.md:55`、`:75`）。
  - 唯一调用点硬编码无截图并让确认回调直接抛错：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:70-75`（`screenshot = null`，`onCropConfirmed = { ... -> error("SCREENSHOT_SOURCE_UNAVAILABLE") }`）。
  - 全目录无采集实现：`apps/android` 下无 `MediaProjection` / `AccessibilityService` 生产代码（`capability-ports/README.md` 自述这些 adapter「remain separate readiness items」）；宿主 Manifest 未声明相关组件（`apps/android/app/src/main/AndroidManifest.xml:1-28` 仅 INTERNET 与 MainActivity）。
  - 圈选事件无派发点：`ConversationEvent.StartScreenSelection` 仅定义（`conversation-domain/.../ConversationEvent.kt:9`）与 reducer 处理，生产无 dispatch。
- **说明**：overlay 侧的降级行为本身是正确的（无截图即不可用），因此该缺陷是「能力未实现」而非「降级缺失」；但设置页对该能力的宣传文案不符，另见 3-16。

### 1-4 通知采集的本地设置界面（包名白名单 / 元数据与内容访问 / ON_DEMAND 与 AUTO_SEND / 打开系统监听设置）

- **问题类别**：类别 1 完全未实现
- **判断依据**：
  - 文档自述该界面存在：`apps/android/README.md:25-31`「The local settings page can configure package IDs, metadata versus content access, and `ON_DEMAND` versus `AUTO_SEND`. It also opens Android's notification-listener settings」。
  - 源码中不存在：`apps/android/app/src/main` 搜 `NotificationPolicy|deliveryMode|AUTO_SEND|packageIds` 零匹配；App 不依赖 `:notification-collector` / `:policy-engine`（`apps/android/app/build.gradle.kts:45-57`），`ArchitectureBoundaryTest.kt:22-36` 明文断言禁止 app 依赖 `:notification-collector`（`:policy-engine` 不在该断言内，但 app 同样不依赖）。
  - 采集侧在模块 main 内确有 deny-first 默认工厂与一个未被声明的服务：`NotificationRuntime.kt:151-162` 的 `NotificationRuntimeFactoryRegistry.defaultFactory` 构造 `NotificationRuntime(initialCollector = AndroidNotificationCollector(...))`，`AndroidNotificationCollector.kt:219` 定义 `OpenAndroidIntelligenceNotificationListenerService`；但全仓 XML 中 `NotificationListenerService` / `BIND_NOTIFICATION_LISTENER_SERVICE` 零命中（该模块无 `AndroidManifest.xml`），系统不会绑定该服务；`NotificationAgentQueryGateway` / `PersistentNotificationPolicyAuthority` 等仍是零宿主装配。

### 1-5 ADR 0042 的原生插件版本化界面扩展点

- **问题类别**：类别 1 完全未实现
- **判断依据**：ADR 0042 允许原生插件「通过版本化扩展接管主导航、业务页面、对话、附件、助理界面、主题和动画」（`docs/adr/0042-allow-developer-trust-native-ui-takeover.md:11`）；但 `apps/android/app/src/main` 下不存在任何插件界面文件或扩展注册点（模块内唯一命名为扩展的工具类是无关的 `ContentResolverExtensions.kt`），设置界面也没有相应的扩展授权入口。**说明**：ADR 0042 未规定扩展点的标识符，故本项判定以「宿主没有任何插件 UI 面与扩展 API」为据，而非以特定符号名检索。

---

## 2. 类别 2：仅在前端界面展示、缺少后端逻辑或数据支持

### 2-1 「刷新网关凭据」按钮（Gateway 子页）

- **问题类别**：类别 2
- **判断依据**：
  - 该条目**只在已连接时渲染**（`SettingsScreen.kt:801` `if (uiState.isGatewayConnected)`，条目定义 `:805-810`，文案「向 Gateway 请求刷新短期访问令牌」）。
  - 回调链为 `onRefreshSession` → `SettingsViewModel.refreshSession()` → `runtime.restoreSessionIfAvailable()`（`SettingsViewModel.kt:198-200`）；而后者首行在非 Disconnected 状态直接返回：`GatewayRuntime.kt:206-207` `if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.Disconnected) return`。**在已连接状态下点击该按钮必然 no-op，且不发任何请求**。
  - 无用户可见反馈：`_operationNotice` 在全仓无 UI 消费点（定义与写入均在 `GatewayRuntime.kt:84-86/150/191/197/275`），配套的 `dismissOperationNotice()`（`:86`）也无任何调用方，因此点击后界面无任何变化或提示。
  - 真实「刷新凭据」能力**存在但未被设置页调用**：`GatewayAuthClient.refresh` → `POST /open-android-intelligence/v2/sessions/refresh`（`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/GatewayAuthClient.kt:91-106`），调用点在 `GatewayRuntime.kt:249-255`，仅由冷启动恢复路径触发（`MainActivity.kt:110-112`）。

### 2-2 「读取与发送短信」授权开关

- **问题类别**：类别 2
- **判断依据**：
  - 开关只写本地授权 holder：`SettingsViewModel.kt:163-165` → `PairingGrantState.kt:117-122`（`updatePrimitive(SMS)`），行为是 SharedPreferences 持久化 + 本地审计 + revision 自增（`:145-169`），**无上行同步、无回调、无网络依赖**（构造参数仅 store 与 audit，`:101`）。
  - 唯一生产消费方是内核裁决链（`OpenAndroidIntelligenceApplication.kt:99` → `PluginKernel.kt:173/235`），但生产装配 `runtimes = emptyMap()`、`phoneLimits = PhoneLimits(primitives = emptySet())`（`OpenAndroidIntelligenceApplication.kt:85-100`），而生效条件为多方交集（`CapabilityGrant.kt:68-93`）⇒ **结果集恒为空**；`PluginKernel.register/enable/invoke` 在生产源码中无调用点。
  - 文案宣称「限制：每次交互须经手机确认」（`SettingsScreen.kt:512-513`），但不存在可被确认的交互通路。

### 2-3 「屏幕上下文分析与圈选」授权开关

- **问题类别**：类别 2
- **判断依据**：
  - 与 2-2 同源：`updateScreenSelection`（`PairingGrantState.kt:124-126`）只改本地授权状态（含本地持久化与本地审计），无上行同步。
  - 更进一步，该字段**不进内核**：转换为内核授权时只映射 `pairingId/granted/revision`（`PairingGrantState.kt:57-61`），`screenSelectionEnabled` 被丢弃；全仓 `screenSelectionEnabled` 仅出现在定义/转换/持久化/UI 四处，**无内核消费方**。
  - 截图来源不存在（见 1-3），故开关即使被读取也无效果。

### 2-4 「系统通知推送」开关（文案「后台低功耗推送服务」）

- **问题类别**：类别 2
- **判断依据**：
  - 只写 `PairingGrantCapabilities.NOTIFICATIONS`（`SettingsViewModel.kt:171-173`，文案 `SettingsScreen.kt:529-536` / `:899-906`）。
  - 通知查询的实际前置门读的是**另一套**授权：`NotificationAgentQueryGateway.kt:122` 依据策略权威快照的 `granted` 判定（`LOCAL_GRANT_REQUIRED`），二者之间**没有任何同步代码**。
  - 采集模块未装配（见 1-4），因此该开关在设备上不产生任何可观察行为。

### 2-5 开发者信任模式（开关本身真实，效果不落地）

- **问题类别**：类别 2
- **判断依据**：
  - 模型与门控代码是真实的：`DeveloperTrustMode.enable/disable/onChange`（`apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/DeveloperTrustMode.kt:33-59`）、`PluginKernel.enable` 对 `developer-native` 抛 `NativePluginRejected("TRUST_MODE_DISABLED")`（`PluginKernel.kt:135-137`）、`NativePluginLoader` 在 trust 关闭时 `unloadAll()`（`NativePluginLoader.kt:37`）。
  - 但状态**不持久化**（`@Volatile private var enabled`，`DeveloperTrustMode.kt:25-26`），且生产无任何插件可加载/可拒绝 ⇒ 打开开关后**无任何可观察效果**。
  - 渠道策略真实：`full=true` / `play=false`（`apps/android/app/build.gradle.kts:17-29` → `OpenAndroidIntelligenceApplication.kt:63`）。

### 2-6 「本机授权版本 r{N}」显示值

- **问题类别**：类别 2
- **判断依据**：该值来自本地自增计数器 `PairingGrantState.revision`（每次本地变更 `+1`，`PairingGrantState.kt:152`；读取 `SettingsViewModel.kt:47-48`，展示 `SettingsScreen.kt:500`、`:915`），**与网关的 `grantRevision` 无任何代码连接**。网关侧该字段为固定默认值（Hermes `core.py:1044-1049`、OpenClaw `session-service.ts:254-263` 写死 1），唯一能读它的客户端 `DeviceRequestClient` 未装配（见 3-7）。同名语义不符另见 3-10。

### 2-7 「配对身份标识」显示值

- **问题类别**：类别 2
- **判断依据**：展示值为 `pairingGrants?.pairingId`（`SettingsScreen.kt:919-923`），而该 ID 由本机合成：`"pairing_" + sha256Hex(storageKey).take(32)`，源码注释自称「not a Gateway wire identity」（`PairingGrantState.kt:42`）；双宿主均不下发任何 `pairingId` 字段。语义不符另见 3-11。

### 2-8 「配对摘要」显示值

- **问题类别**：类别 2
- **判断依据**：App 读取 `session.pairingSummary`（`GatewayAuthClient.kt:196`）并以「配对摘要」展示（`SettingsScreen.kt:793-797`、`SettingsViewModel.kt:44-45`），但**双宿主均不返回该字段**（`integrations/` 全仓零匹配；Hermes 会话响应字段见 `core.py:2757-2761`，OpenClaw 见 `session-service.ts:311`）⇒ 界面恒显示「未返回配对摘要」。契约亦无该字段 Schema（见 3-12）。

### 2-9 「不可篡改审计规范」条目与「不可篡改」表述

- **问题类别**：类别 2
- **判断依据**：
  - 该条目 supporting 为写死字符串、无数据绑定（`SettingsScreen.kt:578-583`）；概览条目文案亦称「本地不可篡改操作流水」（`:573`）。
  - 实现侧无任何防篡改技术支撑：审计为**整文件重写**（`PersistentAuditSink` 每次 `_events + event` 后 `persist(next)`，`AndroidAuditStore.kt:80-87`，落盘 `filesDir/platform-kernel/audit-events.log`），无哈希链、无前序摘要、无签名、非只追加；实现只做字段清洗与内容防泄漏（`:193-212`、`:229-237`）。
  - 文案宣称记录「插件原语调用、授权裁决与**用户确认**」，但：`invoke` 审计路径在生产不可达（见 2-2）；**「用户确认」从未写入**——审批模块（`gateway-client/.../approvals`）与 `conversation-data` 全模块对 audit 零引用，UI 审批决策（`FloatingConversationPanel.kt:45`）不经过 `AndroidAuditStore`。实际写入点只有 `emergency.stop`、`invoke` 系列、`pairing.grant.changed`（`PluginKernel.kt:96-99/199-273`、`PairingGrantState.kt:155-162`）。

### 2-10 「平台内核隔离原语」「安全原语硬上限」静态条目

- **问题类别**：类别 2
- **判断依据**：两条目的 supporting 均为固定字面量、无 `onClick`、无数据参数（`SettingsScreen.kt:997-1001`、`:1003-1007`）；内核侧虽存在资源预算与交集裁决代码（`PluginKernel.kt:14-20/119-123`、`CapabilityGrant.kt:68-93`），但**没有可被 UI 读取的静态上限表**，界面无数据源可绑定。

### 2-11 熔断计数的显示值（`emergencyStoppedCount`）

- **问题类别**：类别 2
- **判断依据**：`_stoppedCount` 仅在 `emergencyStop()` 内被赋值（`SettingsViewModel.kt:92` 定义、`:192` 赋值），初始为 0（`:145`）；设置面板由 `AnimatedVisibility` 整体挂载/卸载（`MainActivity.kt:234-244`），关闭时 `remember(environment, runtime)` 创建的 ViewModel 随子树一并销毁（`SettingsScreen.kt:114-116`；注意 `PlatformSettingsEnvironment` 是 data class、前后 equals 相等，单靠 key 变化不会重建），重开时新建的 ViewModel 把计数固定在 0，而内核 `isEmergencyStopped` 仍为 true ⇒ **重开面板后界面会显示「已触发紧急停用，共隔离 0 个插件」**（文案 `SettingsScreen.kt:593-597`、`:1016-1020`），展示值缺少持久来源。核验边界：该行为依赖 Compose 子树销毁路径，未做设备验证，故 UI 实际显示值**未确认**（见第 6 节）。

### 2-12 渠道策略字段与插件安装能力在宿主侧无消费

- **问题类别**：类别 2
- **判断依据**：
  - `apps/android/app/build.gradle.kts:21/26` 声明了 `BuildConfig.ALLOW_RUNTIME_PLUGINS`，但整个 Kotlin 源码**零读取**；`DistributionPolicy`（`PlatformSettingsScreen.kt:12-15`）在生产代码中**零构造**（只有 `DistributionVariantTest.kt:11/21` 使用）⇒ 界面与宿主都没有依据分发渠道限制「运行时插件」的路径，界面文案对渠道策略的呈现只能依赖 `ALLOW_DEVELOPER_TRUST_MODE` 一半。
  - `apps/android/app/build.gradle.kts:49` 依赖 `:plugin-package`，但 `app/src/main` 对 `plugin.pkg` 零引用 ⇒ 插件包身份校验、安装器与更新策略整套能力在宿主侧空转（与 1-1 的插件管理缺失同源）。

---

## 3. 类别 3：已定义接口契约但未实现或与契约不符

### 3-1 `revokeRefresh` 语义与契约不符（双宿主一致地把「退出登录」实现成「解除配对」）

- **判断依据**：契约明确定义「退出登录：撤销目标 refresh credential，**不删除配对**」（`docs/contracts/gateway-protocol-v2.md:796`），且 `DELETE /sessions/current` 的 `revokeRefresh=true` 只表示「同时实现退出登录」（`:280`）。双宿主实现均在撤销 refresh 的同时**删除设备密钥**（等同解除配对）：Hermes `core.py:2703-2713`（`:2713` `DELETE FROM device_keys WHERE device_id = ?`，docstring 自述 "Ends the pairing"）、OpenClaw `session-service.ts:214-231`（`:222` 同样删 `device_keys`）。两端行为彼此一致，但**与契约相反**。
- **影响**：App 正是用 `revokeRefresh=true` 作为「解除设备配对」（`SettingsScreen.kt:274`）。

### 3-2 「解除配对」独立管理端点未实现（三端）

- **判断依据**：契约要求「解除配对和删除账号使用**独立管理端点**，不能由普通会话删除隐式替代」（`gateway-protocol-v2.md:280`），解除配对须撤销「设备密钥、refresh credential、授权、队列和未确认附件」（`:798`）。本仓未找到任何解除配对端点：Android 只用 `DELETE /sessions/current`（`SettingsScreen.kt:274`、`GatewayAuthClient.kt:108-137`）；Hermes/OpenClaw 只有账号删除管理函数（`core.py:3105`、`integrations/openclaw/src/admin/service.ts:95`），无配对解除管理操作。契约本身**未定义该端点的路径与请求/响应形状**（归类疑难见第 6 节）。

### 3-3 `POST /pairings/exchange`（账号邀请配对）未实现（三端）

- **判断依据**：契约定义见 `gateway-protocol-v2.md:268`；现状：Hermes 登录只接受 `{"password","refresh"}`（`core.py:3149-3150`）、OpenClaw 只接受 `["password","refresh"]`（`gateway-core.ts:105`）并在 manifest 显式声明 `"invitationPairing": false`（`integrations/openclaw/plugin-manifest.json:27`、`adapter.ts:91-92`）；Android 协商只声明 `["password","refresh"]`（`NegotiationClient.kt:128`）。全仓 `pairings/exchange` 仅命中 docs。

### 3-4 `POST /sessions/device`（设备密钥会话）未实现（三端）

- **判断依据**：契约定义见 `gateway-protocol-v2.md:274`；现状与 3-3 同源（OpenClaw manifest `"deviceKeySessions": false`，`plugin-manifest.json:28`、`adapter.ts:92`）。

### 3-5 `session.revoked` 事件未实现（双宿主）

- **判断依据**：契约 `gateway-protocol-v2.md:671` 与 `gateway-contract/schemas/event.schema.json:29` 将其列为 V2 事件；两端只在审计中写入同名字符串（`session-service.ts:205`、`core.py:2741`），**不经事件流产生**。

### 3-6 设备授权变更契约未落地（Android 本地确认 + Gateway 保存签名授权摘要 + `pairing.grant.changed` 事件）

- **判断依据**：契约要求「授权变更必须由 Android 本地确认、Gateway 保存签名授权摘要并发送 `pairing.grant.changed`、旧 revision 返回 `GRANT_STALE`」（`gateway-protocol-v2.md:778-784`）。现状只有前半部分：本地持久化 + 本地审计（`PairingGrantState.kt:145-169`、`PairingGrantPersistence.kt:29-39`）；`integrations/` 中 `pairing.grant.changed` **零匹配**；双宿主无任何授权 revision 提升入口（`GRANT_STALE` 只在 device-request 读写路径被当比较值）。

### 3-7 App 设备请求执行通路半实现（无生产传输、无运行时接线）

- **判断依据**：契约要求 Android 执行前重新验证并提交结果（`gateway-protocol-v2.md:709`）。客户端逻辑与收据校验已存在（`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/device/DeviceRequestClient.kt:26-30`、`:39-102`），但**唯一实现是测试替身** `RecordingDeviceRequestTransport.kt:4`；`GatewayRuntime.kt` 全文无 device request 接线；协商结果中的 `deviceRequests` 被解析（`NegotiationClient.kt:28/111`）但**无任何消费方**。
- **双宿主侧**：状态机与 claim/result 已实现且与契约一致（Hermes `core.py:2046-2300`、OpenClaw `device-request-store.ts:94-234`），故这是**单端半实现**。

### 3-8 设备请求 `result` body 形状三端不一致

- **判断依据**：契约只固定字段名 `result`（`gateway-protocol-v2.md:709`，无 result Schema）；双宿主期望对象 `{ outcome, data? }`（`device-request-store.ts:200/216`、`core.py:2182-2183` 读 `result.get("outcome")`），而 Android 发送的是字符串 `"result_succeeded"` 加同级 `payload`（`DeviceRequestClient.kt:70-71`）⇒ 三端形状不一致。

### 3-9 错误码解析与契约不符（`GatewayAuthClient.authError` 读顶层 `errorCode`）

- **判断依据**：契约规定失败响应固定为顶层 `error.code` / `error.retryable` / `error.details`，客户端只使用 `code`（`gateway-protocol-v2.md:50-65`）；`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/GatewayAuthClient.kt:159-163` 读的是顶层 `errorCode`（`:161`），取不到即退化为 HTTP 状态码（`:162`）⇒ 登录错误码退化为状态码。同类解析在别处是**正确**的（`GatewayResponseData.kt:11-13`、`ApprovalClient.kt:179-183` 读 `error.code`），说明这是孤立缺陷。

### 3-10 `grantRevision` 同名字段语义不符

- **判断依据**：契约定义为配对级、Gateway 参与校验的授权版本（`gateway-protocol-v2.md:702/780`、`gateway-contract/schemas/device-request.schema.json:50`）；App 设置页展示的是本机自增计数器（见 2-6），双宿主 DB 中固定为 1。同名不同义。

### 3-11 `pairingId` 同名字段语义不符

- **判断依据**：契约中 `pairingId` 指账号与设备之间的信任关系标识（`gateway-protocol-v2.md:238`、`:241` 属封闭 wire ID 集合）；App 展示的是本机合成 ID（`PairingGrantState.kt:42`，注释自述非 Gateway wire identity），并以「配对身份标识」呈现（`SettingsScreen.kt:919-923`）。双宿主不下发该字段。

### 3-12 `pairingSummary` 字段无契约定义、双宿主均不下发

- **判断依据**：契约仅有自然语言「返回…配对摘要」（`gateway-protocol-v2.md:262`），**无字段名与响应 Schema**（`gateway-contract/schemas/session.schema.json` 只有 password/refresh/device 三个请求定义）；App 读 `pairingSummary` 并展示（见 2-8）。

### 3-13 `message-batches-v1` / `generation-cancel-v1` 能力位半实现（仅 App 声明）

- **判断依据**：两能力位在契约闭集中（`gateway-contract/schemas/negotiate.schema.json:76/78`，共 8 位，见 `:72-81`）。Android 声明两位（`NegotiationClient.kt:148-149`），其中**只有 `message-batches-v1` 被门控**（`GatewayRuntime.kt:379`），`generation-cancel-v1` 声明后未被门控（`ConversationClient.cancelGeneration`、`WorkbenchController.stopGeneration` 均无条件调用）；**双宿主均未声明**（Hermes `core.py:3155`；OpenClaw `plugin-manifest.json:24-25` 为 false）⇒ 按「客户端只能拿到自己声明且 Gateway 确实实现了的能力」（`gateway-protocol-v2.md:136`），该位在三端协商中恒为空。

### 3-14 `newline-v1` / `conversation-mirror-v1` / `attachment-status-v1` 能力位三端均未声明

- **判断依据**：三位在契约闭集中（`negotiate.schema.json:77/79/80`），语义依据见 ADR 0045（`newline-v1`）、ADR 0044（`conversation-mirror-v1`）、ADR 0046（`attachment-status-v1`）；Android（`NegotiationClient.kt:144-150`）、Hermes（`core.py:3155`）、OpenClaw（`gateway-core.ts:115`）三方声明集合中**均无**这三位。

### 3-15 设置界面无法呈现协商能力位

- **判断依据**：契约要求客户端只使用双方声明且对端确实实现的能力（`gateway-protocol-v2.md:136-142`）；但 `SettingsUiState`（`SettingsViewModel.kt:21-58`）**没有任何能力位字段**；协商得到的特性串只在 `GatewayRuntime.establish()` 内被消费为 `WorkbenchController` 的门控布尔（`GatewayRuntime.kt:379-384`），**既不进入 `ConnectionPhase.Connected`（`:60-68`，只承载 limits/pairingSummary/tlsSpkiSha256/transportSecurity），也未透传到设置界面** ⇒ 用户无法在设置页得知本连接实际支持/不支持哪些能力，界面对相关条目（如通知推送、圈选）的呈现也无法如实降级。

### 3-16 屏幕圈选的设置页声明与实现不符

- **判断依据**：UI 规格要求无真实截图即明确不可用（`docs/superpowers/specs/2026-09-12-app-material-ui-and-motion.md:55/75`）；设置页两处声明该能力可用：「支持数字助理 Assist 选区截图」（`SettingsScreen.kt:520-527`）、「允许数字助理获取当前屏幕快照并进行多模态分析」（`:890-897`），而实现中截图来源恒为 `null`（见 1-3）。

### 3-17 设置呈现形式与 UI 规格不符（规格要求 M3 底部弹层，实现为全屏浮层；包装器无调用方）

- **判断依据**：UI 规格 M03 要求「抽屉、附件/设置底部弹层使用标准 M3 Drawer/Sheet」（`docs/superpowers/specs/2026-09-12-app-material-ui-and-motion.md:67`）；实现是 `MainActivity.kt:234-244` 的 `AnimatedVisibility` 全屏渲染 `SettingsScreen`（由 `showSettingsSheet` 布尔状态驱动，入口 `:184`、`:199`）。而符合规格的底部弹层包装器 `PlatformSettingsBottomSheet.kt:23` 与 `PlatformSettingsScreen.kt:34` **全仓无调用方**（仅定义）。

### 3-18 `AppDestination` 类型声明未接入（含 `PlatformSettings` / `PluginManagement`）

- **判断依据**：`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/navigation/AppDestination.kt:1-10` 声明 6 个目的地，但**除定义文件外全仓无任何引用**（仅两处 docs 提及）；`PlatformSettings`/`PluginManagement`/`GatewayManagement`/`AttachmentAndMedia` 在 `apps/` 下 0 命中。实际导航由 `SettingsRoutes`（`SettingsScreen.kt:88-95`）+ 内部 NavHost（`:138-218`）与 MainActivity 布尔状态实现。

### 3-19 通知参考插件 Manifest 与 `device-plugin-package-v1` §5 不符

- **判断依据**：契约要求 `schemaVersion`、`author.algorithm: "Ed25519"`、`runtime.type: "protected-wasm"`、`capabilities.provides` 为**对象数组**（含 `id/version/schema`）、`capabilities.kernelPrimitives`、以及 `compatibility`/`security`/`ui`/`state` 节（`docs/contracts/device-plugin-package-v1.md:89-153`，并在 `:156` 规定「所有对象默认拒绝未知字段」）。`plugins/notifications/manifest.json:2/8-11/14/20/23` 实际使用 `manifestVersion`、`plugin.author.name + publicKey`、`runtime.type: "wasm"`、`provides` 为**字符串数组**、`kernelPrimitives` 置于**顶层**，且缺 `compatibility`/`security`/`ui`/`state`；`plugins/sms/manifest.json` 与 `plugins/call-log/manifest.json` 同形。`plugins/dist/*.alp` 包名（`org.agentlife.*`）与 manifest 的 `org.openandroidintelligence.*` 也不一致。

---

## 4. 已验证正常的项（作为基线，避免误判）

| # | 功能项 | 证据 |
|---|---|---|
| 1 | 外观与动效三项偏好（主题 / 动态取色 / 减弱动态） | 持久化 `commit()` 成功后才发布（`AppearancePreferences.kt:64-73`）；全链路消费：`MainActivity.kt:68-78` → `Theme.kt:126-149`（动态取色回落品牌配色）→ `MotionPolicy.kt:20-32`、`MotionSpecs.kt:39-48`、`AppTransitions`（设置页导航转场 `SettingsScreen.kt:142-145`） |
| 2 | 一键系统级安全熔断（内核行为真实） | `PluginKernel.kt:86-104` 真隔离 `ENABLED→QUARANTINED`、关闭信任、写审计、通知监听者；调用期再拦截 `:198-204`；不持久化与文案「须重启恢复」一致；`_stoppedCount` 真来自内核返回值（`SettingsViewModel.kt:190-196`） |
| 3 | 退出登录 / 解除配对主链路（副作用真实） | 客户端 `DELETE /sessions/current?revokeRefresh=` + 本地清理 refresh 文件与 last profile + `teardown()`（`GatewayRuntime.kt:181-202`）；服务端撤销 refresh 并删设备密钥；语义与契约不符部分见 3-1/3-2 |
| 4 | 审计存储与展示 | 真实落盘 `filesDir/platform-kernel/audit-events.log`（临时文件 + `fd.sync()` + `ATOMIC_MOVE`，`AndroidAuditStore.kt:101-123`）、30 天保留（`:73`）、字段清洗、不含正文（`:29-37`）；设置页逐行渲染并对 DENIED/FAILED 标红（`SettingsScreen.kt:1078-1096`） |
| 5 | 「传输安全拓扑」「账号主体」「Gateway 节点地址」「传输安全模式」 | 真实数据绑定（`SettingsScreen.kt:454-491`、`:770-791`；数据源 `GatewayRuntime.kt:388-395`），未连接时如实显示未连接 |
| 6 | 屏幕圈选 overlay 的降级行为 | 无截图即渲染不可用态且不触发确认回调（`conversation-ui/.../selection/ScreenSelectionOverlay.kt:91-96`、`:211-219`），符合规格 `:55/:75` |
| 7 | 审批卡片能力位双端自洽 | Hermes 条件声明（`core.py:2933-2940/3156-3159`）、OpenClaw 显式不声明并说明无长连接（`gateway-core.ts:111-114`），均符合「未实现不得声明」（`gateway-protocol-v2.md:142`） |
| 8 | 双宿主设备请求 claim/result 状态机 | 与契约一致（Hermes `core.py:2046-2300`、OpenClaw `device-request-store.ts:94-234`；含 `GRANT_STALE`/`PAIRING_GENERATION_STALE` 绑定校验） |
| 9 | 收据六字段与 TLS 指纹字段命名 | `DeviceRequestClient.kt:9-16` 与 `gateway-protocol-v2.md:714-721` 逐字一致；`tlsSpkiSha256` 三端一致（`NegotiationClient.kt:107`、`core.py:3200`） |
| 10 | 基础能力位三端一致 | `auth`/`messages`/`attachments`/`events`/`deviceRequests` 与 Schema `const` 相符（`negotiate.schema.json:116-119`） |
| 11 | 三端本地审计保留期与不含正文 | Hermes `audit.py:102-140`、OpenClaw `audit-store.ts:21-58`、Android `AndroidAuditStore.kt:70-127` |
| 12 | 设置组件库与设计令牌守卫 | `SettingsComponents.kt:118-124/164/189`（点击门控语义，源码级保证）、`SettingsComponentsTest.kt`（覆盖 `enabled=true` 的点击路径，禁用分支未覆盖）、`MaterialTokenGuardTest.kt`、`ThemeContrastTest` |

---

## 5. 建议修复优先级

**P0（用户可见错误或安全语义误导，建议优先处理）**

1. **3-1 + 3-2**：`revokeRefresh=true` 语义与「解除配对」不完整（授权、队列、未确认附件未撤销）——这是「设置页危险操作」的核心正确性问题。
2. **2-1**：「刷新网关凭据」在已连接时必然 no-op 且无反馈；同时 `_operationNotice` 全仓无 UI 消费，导致登出/恢复失败也静默。
3. **2-2 / 2-3 / 2-4**：三个设备能力开关无任何生效通路，界面却给出可操作暗示（安全语义误导）。
4. **2-9**：「不可篡改」与「记录用户确认」的表述与实现不符（安全声明夸大）。
5. **3-9**：`error.code` 解析退化导致登录失败原因不可读。

**P1（能力缺口，影响产品完整性）**

6. **3-6 + 3-7 + 3-8**：设备授权变更契约与设备请求执行通路（含 result 形状三方对齐）。
7. **1-1 + 1-2**：设置内的插件管理区域与插件声明式设置项/状态卡片渲染（ADR 0034/0042 与插件包契约的核心要求）。
8. **1-3 + 3-16**：屏幕圈选截图来源，或先修正设置页文案为如实降级。
9. **3-3 / 3-4 / 3-5**：邀请配对、设备密钥会话与 `session.revoked` 事件。

**P2（一致性与清理）**

10. **2-6 / 2-7 / 2-8 / 3-10 / 3-11 / 3-12**：显示字段的来源与语义对齐（本地计数 vs 网关权威、字段是否存在）。
11. **3-13 / 3-14 / 3-15**：能力位补齐或声明收敛，并把能力位透传到设置界面如实降级。
12. **3-17 / 3-18**：设置呈现形式对齐 UI 规格；清理未接入的 `AppDestination` 与无调用方的包装器。
13. **2-10 / 2-11**：静态条目改为真实数据绑定或明确为说明文本；熔断计数补持久来源。
14. **2-12**：清理无消费方的渠道策略字段（`ALLOW_RUNTIME_PLUGINS`、`DistributionPolicy`）与宿主侧空转的 `:plugin-package` 依赖。
15. **3-19**：参考插件 Manifest 与插件包契约对齐（含 `plugins/call-log/manifest.json`）。

---

## 6. 未确认项与证据边界（缺哪一步证据）

1. **契约缺口（不是实现缺陷）**：「解除配对」独立管理端点、`pairingSummary` 字段、device `result` 形状、`DELETE /sessions/current` 的响应/错误码，契约本身只有自然语言或字段名而无路径与 Schema（`gateway-protocol-v2.md:262/280/709`），因此无法裁决「谁为权威」。
2. **登出端点签名强度**：契约 §6.1 只对「已认证请求」定义九 header 与 Ed25519 规则（`gateway-protocol-v2.md:300-322`），未明文豁免 `DELETE /sessions/current`；三端一致地只用 bearer + 三个身份 header，故无法判定是否偏离。
3. **审计「用户可以清除」**：契约 `gateway-protocol-v2.md:801` 未指明约束对象；Hermes 有 `purge`（`audit.py:135-140`）但无端点/UI 暴露证据，OpenClaw 未见 purge 实现，Android 无清除入口。
4. **UI 层面的两处未确认**：熔断后重开面板的计数显示值（见 2-11，未做设备验证）；`plugins/` 与 `plugins/dist/` 的构建一致性（以哪一方为源未核实）。

另需说明：本次为静态审计，未运行测试与联机验证；第 4 节的「已验证正常」指**代码路径与守卫有实现且相互一致**，不等价于真机联调通过。

---

## 7. 复核记录（独立反向核验）

本报告在初稿完成后另派独立子代理做过一轮反向核验：逐条打开被引用的文件与行号，核对结论、行号与推断强度，并对第 4 节抽查 8 项。核验结论为：**类别 1/2/3 共 36 条中，无一条被反证推翻**；以下表述在本轮被修正并已回写正文：

| 修正项 | 原表述问题 | 修正后 |
|---|---|---|
| 1-4 | 「采集侧构造点全部只在各模块 `src/test`」不成立 | 采集侧 main 内确有 deny-first 默认工厂与一个未被 Manifest 声明的 `NotificationListenerService`；缺的是宿主装配与 Manifest 声明 |
| 1-4 | 「`ArchitectureBoundaryTest` 禁止该依赖」指代不清 | 该断言只禁止 `:notification-collector`，`:policy-engine` 不在断言内（app 同样不依赖） |
| 1-5 | 用 ADR 未规定的符号名检索作为证据 | 改为以「宿主无任何插件 UI 面与扩展 API」为据，并说明 ADR 未定义扩展点标识符 |
| 2-1 | 未点出配套公开 API 也是死代码 | 补 `dismissOperationNotice()` 零调用方 |
| 2-3 | 「只落本地」忽略持久化与审计 | 改为「只改本地授权状态（含持久化与本地审计），不进内核授权集」 |
| 2-11 | 把重建原因归给 `remember` key | 改为 `AnimatedVisibility` 挂载/卸载销毁 ViewModel（环境对象 equals 相等，key 变化不会重建） |
| 3-13 | 「据能力位门控」范围过宽 | 只有 `message-batches-v1` 被门控，`generation-cancel-v1` 声明后未门控 |
| 3-15 | 称能力位存在于 `ConnectionPhase.Connected` | 能力位只在 `establish()` 内被消费为控制器门控布尔，不进入 `Connected` |
| 3-16 | 行号 `:521-527` | 更正为 `:520-527` |
| 3-19 | 只举两个插件清单 | 补 `plugins/call-log/manifest.json` 同形 |
| 4-12 | 称组件测试覆盖点击门控语义 | 实际只覆盖 `enabled=true` 路径，禁用分支仅源码保证 |

核验还发现三条初稿未覆盖的问题，已作为 1-1 旁证与 2-12 补入：`BuildConfig.ALLOW_RUNTIME_PLUGINS` 全仓零读取且 `DistributionPolicy` 生产零构造；`:plugin-package` 依赖在宿主侧空转；`plugins/call-log/manifest.json` 同样与插件包契约不符。
