# Android App 测试 JVM 隔离静态审计

本报告对应问题 #135、PR #136。静态审计基线为 `dev` 的 `f6421dda`，隔离实现分支从主任务检查点 `93ba8a12` 建立；清单与 Suite 首笔提交为 `d73e1751a0d6c3cdc6b4849ef75ee07518d954e7`。报告日期为 2026-11-11。

结论：保留 App 测试的 `forkEvery=1`。可以把经过明确清单审计的 46 个候选类分为四个顶层 JUnit Suite，实验性地复用组内 JVM；其余类继续逐类隔离。此处“候选”只表示源码中没有发现已知宿主污染路径，不能解释为真实 App 重复、乱序或并行回归已经通过。

## 已有隔离的历史证据

- `git log -p -S 'forkEvery' -- apps/android/app/build.gradle.kts` 显示：`17895223c16fc84dfbe7c889943a608f7fbaa095` 于 2026-11-01 加入按测试类隔离，注释指出宿主注册表和 Compose 空闲资源属于进程状态。
- [当时的隔离证据](../reviews/2026-11-01-global-device-authorization.zh-CN.md#L134) 记录全 App 共用一个 JVM 时出现多项 Compose 空闲超时，线程栈反复进入 `ComposeIdlingResource.isIdleNow`；该次 1162 项、7 失败、1 跳过，明确不算通过。
- 同文记录相同会话分享与离线插件授权用例在新 JVM 定向运行 14 秒通过，随后保留断言和空闲检查、设置 `forkEvery=1`。这是保留隔离的直接依据，并不能证明每个纯单测都必须单独启动 JVM。
- 当前 [app/build.gradle.kts](../../apps/android/app/build.gradle.kts#L68) 中仍保留 `forkEvery=1`。本子任务未修改该文件。

## 历史报告只能提供优先级

读取的报告目录为 `/tmp/oai-ci-baseline-42962654289/app/build/test-results/testFullDebugUnitTest`，共 165 个 XML 测试类、1806 个方法，报告时间相加为 1833.727 秒。46 个候选类在这份报告中合计 288 个方法，报告时间相加为 155.488 秒。

这些是 JUnit 报告值，并非整条 Gradle 流水线的墙钟时间。报告没有单独测量 JVM 启动、Runner 构造、Robolectric Sandbox 初始化或进程间重叠，不能据此扣出“fork 耗时”，也不能把大额首方法耗时全部认定为完整 App 初始化。

| 历史慢类 | 报告累计时间 | 静态观察 |
| --- | ---: | --- |
| `ReplyNotificationsTest` | 76.924 秒 | API 31–36 通知矩阵，包含真实运行时、SSE 和轮询。 |
| `NativeConversationNotificationsTest` | 65.108 秒 | API 31–36，Default 调度器及八处固定 `delay(100)`。 |
| `BubbleConversationTest` | 59.826 秒 | Activity/窗口、真实会话运行时与有界轮询。 |
| `GatewaySessionRefreshTest` | 53.004 秒 | 真实 loopback 会话、后台租约、续期、Security Provider 修改。 |
| `ProductionPluginHostTest` | 46.977 秒 | 宿主能力注册表、默认异常处理器、插件运行时。 |
| `DeviceExecutionRecoveryTest` | 46.828 秒 | 请求恢复、真实调度、能力注册表与有界等待。 |
| `SettingsHonestyCopyTest` | 39.381 秒 | Compose 与真实布局/空闲检查。 |

## 46 类明确候选清单

权威条目位于 [app-jvm-reuse.txt](../../apps/android/ci/app-jvm-reuse.txt)，不是依靠类名、目录或 Runner 自动推断。四组固定算法为 `Math.floorMod(完整限定类名.hashCode(), 4)`，当前组 0–3 分别包含 9、17、14、6 个类。随机种子改变组内类和方法顺序，保持组分配稳定。

下面的源文件行号用于定位类；“纯 JVM”只描述测试源码与调用方式，“轻量 Robolectric”只表示没有显式完整 App、Compose、网络/子进程、宿主注册表或未取消 scope 路径。Robolectric 自身仍有 Sandbox、Shadow 和环境初始化，必须由实际重复与交错顺序结果验证其复用。

### 31 个纯 JVM 候选

| 组 | 类 | 源码位置 | 选择证据 |
| ---: | --- | --- | --- |
| 0 | `ArchitectureBoundaryTest` | [ArchitectureBoundaryTest.kt:9](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ArchitectureBoundaryTest.kt#L9) | 只读 Gradle 与导航源码，核对模块边界。 |
| 0 | `ConversationUiCapabilitiesTest` | [ConversationUiCapabilitiesTest.kt:15](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilitiesTest.kt#L15) | 封闭能力集合与三态投影。 |
| 2 | `DistributionVariantTest` | [DistributionVariantTest.kt:18](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/DistributionVariantTest.kt#L18) | 只读 BuildConfig 渠道策略。 |
| 1 | `GatewayLoginSanitizerTest` | [GatewayLoginSanitizerTest.kt:9](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewayLoginSanitizerTest.kt#L9) | URL 净化及 GatewayEndpoint 解析。 |
| 2 | `MaterialTokenGuardTest` | [MaterialTokenGuardTest.kt:13](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/MaterialTokenGuardTest.kt#L13) | 只读界面源码，检查颜色与圆角约束。 |
| 1 | `NativeNotificationNavigationTest` | [NativeNotificationNavigationTest.kt:12](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeNotificationNavigationTest.kt#L12) | 只判断目标工作台是否已发布。 |
| 1 | `NotificationReplyExcerptTest` | [NotificationReplyExcerptTest.kt:6](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NotificationReplyExcerptTest.kt#L6) | 字符串摘要与 Unicode 边界。 |
| 2 | `TemporaryApprovalCopyTest` | [TemporaryApprovalCopyTest.kt:8](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/TemporaryApprovalCopyTest.kt#L8) | 参数摘要与敏感信息净化。 |
| 0 | `WorkbenchNavigationTest` | [WorkbenchNavigationTest.kt:7](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/WorkbenchNavigationTest.kt#L7) | 外观枚举与导航边界。 |
| 1 | `AssistantBridgeServiceTest` | [assistant/AssistantBridgeServiceTest.kt:18](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeServiceTest.kt#L18) | DTO 投影、附件元信息和批次数量校验。 |
| 2 | `AssistantClientScopeTest` | [assistant/AssistantClientScopeTest.kt:6](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/assistant/AssistantClientScopeTest.kt#L6) | 账号绑定状态转换。 |
| 0 | `NativeNotificationBridgeTest` | [conversations/NativeNotificationBridgeTest.kt:23](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeNotificationBridgeTest.kt#L23) | 原生通知事件的结构转换。 |
| 1 | `GatewayTutorialsTest` | [onboarding/GatewayTutorialsTest.kt:16](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/GatewayTutorialsTest.kt#L16) | 只读教程静态数据。 |
| 2 | `OnboardingStateTest` | [onboarding/OnboardingStateTest.kt:13](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingStateTest.kt#L13) | 引导状态机与进度。 |
| 2 | `PermissionCatalogTest` | [onboarding/PermissionCatalogTest.kt:16](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalogTest.kt#L16) | 传入 SDK 数值后生成权限目录。 |
| 1 | `ButtonRevealGeometryTest` | [onboarding/motion/ButtonRevealGeometryTest.kt:14](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/motion/ButtonRevealGeometryTest.kt#L14) | 展开转场的几何纯函数。 |
| 2 | `DeviceCapabilityBlockTest` | [plugins/DeviceCapabilityBlockTest.kt:20](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlockTest.kt#L20) | 失败类型、可恢复性和提示映射。 |
| 3 | `PluginCapabilityRiskTest` | [plugins/PluginCapabilityRiskTest.kt:6](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginCapabilityRiskTest.kt#L6) | 能力风险分类。 |
| 0 | `PluginPackageInputTest` | [plugins/PluginPackageInputTest.kt:8](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginPackageInputTest.kt#L8) | 局部内存输入流长度与分块。 |
| 2 | `ScreenExecutionErrorsTest` | [plugins/ScreenExecutionErrorsTest.kt:8](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/ScreenExecutionErrorsTest.kt#L8) | 异常类型转换与风险分类。 |
| 2 | `PushStateCodecMigrationTest` | [push/PushStateCodecMigrationTest.kt:13](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/PushStateCodecMigrationTest.kt#L13) | 独立字节流与加密样本的版本迁移；没有全局密钥提供器注册。 |
| 0 | `AccessibilityServiceWiringTest` | [screen/ScreenCapabilityPresentationTest.kt:286](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityPresentationTest.kt#L286) | 只读 Manifest 与无障碍声明。 |
| 1 | `DeviceAuthorizationCopyTest` | [screen/DeviceAuthorizationCopyTest.kt:8](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopyTest.kt#L8) | 授权文案与敏感参数摘要。 |
| 1 | `ScreenCapabilityPresentationTest` | [screen/ScreenCapabilityPresentationTest.kt:19](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityPresentationTest.kt#L19) | 屏幕会话状态与剩余时间投影。 |
| 0 | `ScreenCaptureSystemConsentTest` | [screen/ScreenCaptureSystemConsentTest.kt:6](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/screen/ScreenCaptureSystemConsentTest.kt#L6) | 本地系统授权状态与回调栅栏判定。 |
| 3 | `ScreenControlCopyTest` | [screen/ScreenControlCopyTest.kt:18](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopyTest.kt#L18) | 屏幕动作摘要与文本净化。 |
| 1 | `ScreenErrorMessagesTest` | [screen/ScreenCapabilityPresentationTest.kt:178](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityPresentationTest.kt#L178) | 错误码到可读提示的映射。 |
| 2 | `SettingsShortcutsTest` | [settings/SettingsShortcutsTest.kt:6](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/settings/SettingsShortcutsTest.kt#L6) | 设置快捷入口的返回路径。 |
| 1 | `SettingsBackupCodecTest` | [settings/backup/SettingsBackupCodecTest.kt:10](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCodecTest.kt#L10) | 设置 JSON 编解码与未知字段处理。 |
| 1 | `CapabilityCatalogTest` | [settings/capabilities/CapabilityCatalogTest.kt:17](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalogTest.kt#L17) | 已验证声明到分组目录的映射。 |
| 1 | `CapabilityStateTest` | [settings/capabilities/CapabilityStateTest.kt:13](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityStateTest.kt#L13) | 明确输入事实到启用/失败状态的映射。 |

### 15 个轻量 Robolectric 候选

| 组 | 类 | 源码位置 | 选择证据 |
| ---: | --- | --- | --- |
| 1 | `AndroidAccountProfileStoreTest` | [AndroidAccountProfileStoreTest.kt:17](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AndroidAccountProfileStoreTest.kt#L17) | 普通 Application；AccountManager 内账号与绑定读写，没有运行时、网络或宿主注册表。 |
| 2 | `AndroidApiCompatibilityTest` | [AndroidApiCompatibilityTest.kt:20](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AndroidApiCompatibilityTest.kt#L20) | 普通 Application；保留 API 31–36，读取清单、服务类型和 Bundle。 |
| 2 | `ApprovalAuditBridgeTest` | [ApprovalAuditBridgeTest.kt:23](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ApprovalAuditBridgeTest.kt#L23) | 普通 Application；每例创建 InMemoryAuditSink/AndroidAuditStore。 |
| 2 | `ContentResolverAttachmentSourceTest` | [ContentResolverAttachmentSourceTest.kt:20](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ContentResolverAttachmentSourceTest.kt#L20) | 普通 Application；保留 API 31–36，局部 SyntheticStream 通过 use 关闭。 |
| 0 | `GatewayIdentityTrustStoreTest` | [GatewayIdentityTrustStoreTest.kt:17](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewayIdentityTrustStoreTest.kt#L17) | 普通 Application；每例 @Before 清理 gateway-identities 偏好，核对身份可信记录。 |
| 1 | `GatewayRefreshJournalTest` | [GatewayRefreshJournalTest.kt:13](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewayRefreshJournalTest.kt#L13) | 普通 Application；只读写 Android 偏好中的续期事务记录，不启动续期运行时。 |
| 3 | `InputPlatformRegressionTest` | [InputPlatformRegressionTest.kt:20](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/InputPlatformRegressionTest.kt#L20) | 普通 Application；FakeRecorder 和缓存临时文件，录音结束/取消都验证 release。 |
| 1 | `NativeNotificationRoutingTest` | [NativeNotificationRoutingTest.kt:21](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeNotificationRoutingTest.kt#L21) | 普通 Application；内存事件、局部回调、Intent 路由；前两例显式清理通知完成记录。 |
| 0 | `PairingGrantPersistenceTest` | [PairingGrantPersistenceTest.kt:16](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/PairingGrantPersistenceTest.kt#L16) | 普通 Application；授权偏好迁移与提交失败代理，没有宿主能力注册。 |
| 2 | `SettingsStateIntegrityTest` | [SettingsStateIntegrityTest.kt:21](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsStateIntegrityTest.kt#L21) | 普通 Application；偏好失败代理、权限 Shadow 与状态对象。 |
| 3 | `TrustModePersistenceTest` | [TrustModePersistenceTest.kt:23](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/TrustModePersistenceTest.kt#L23) | 普通 Application；每例独立 DeveloperTrustMode 与偏好后端。 |
| 1 | `DeviceCapabilityPermissionsTest` | [plugins/DeviceCapabilityPermissionsTest.kt:26](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissionsTest.kt#L26) | 普通 Application；权限 Shadow 与注入状态的 Inspector，没有系统授权宿主。 |
| 3 | `NotificationAccessSettingsTest` | [plugins/NotificationAccessSettingsTest.kt:17](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/NotificationAccessSettingsTest.kt#L17) | 普通 Application；ContextWrapper 局部捕获 startActivity 或抛 ActivityNotFoundException。 |
| 3 | `ScreenCaptureGrantRoutingTest` | [plugins/ScreenCaptureGrantRoutingTest.kt:25](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/ScreenCaptureGrantRoutingTest.kt#L25) | 普通 Application；能力映射与注入权限状态，不触发投屏或屏幕宿主。 |
| 1 | `InstalledNotificationAppCacheTest` | [notifications/InstalledNotificationAppCacheTest.kt:23](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/notifications/InstalledNotificationAppCacheTest.kt#L23) | 普通 Application；缓存唯一明确静态状态在 @Before 与 @After 都 clear。 |

这些普通 Application 测试依赖 Robolectric 原有的每例 Shadow/Android 状态重置，原测试未新增清理逻辑。比如 `GatewayRefreshJournalTest`、`PairingGrantPersistenceTest` 会改 SharedPreferences，没有显式 `@After`；它们目前能否跨测试类稳定复用，只能通过后续同源码重复、乱序验证确认。任何候选一旦出现污染，应先退出复用清单并保留原隔离，不删测试或放宽断言。

## 明确保留逐类隔离的范围

| 范围 | 精确证据 | 原因与最小后续验证 |
| --- | --- | --- |
| 全部 Compose 类 | [SettingsResponsiveHostRegressionTest.kt:65](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsResponsiveHostRegressionTest.kt#L65)；[NotificationCollectionScreenTest.kt:65](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreenTest.kt#L65) | 原有空闲超时历史直接关联该类资源。不要关闭空闲检查、统一缩短超时或用静态检查替代 UI 回归。 |
| 完整 App 组合根 | [ApplicationCompositionTest.kt:21](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ApplicationCompositionTest.kt#L21)、[PluginManagementGrantUiTest.kt:26](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementGrantUiTest.kt#L26)、[NotificationScopeOwnershipTest.kt:38](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/settings/capabilities/NotificationScopeOwnershipTest.kt#L38)、[SmsBackgroundConsentTest.kt:48](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/settings/capabilities/SmsBackgroundConsentTest.kt#L48) | 完整 App 的 [onCreate](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt#L122) 装配插件宿主、启动 DevicePush 并登记 ProcessLifecycleOwner 观察器。该类重复初始化需要独立生命周期证据。 |
| 方法级完整 App 覆盖 | [ClientCapabilityAuthorizationTest.kt:530](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/ClientCapabilityAuthorizationTest.kt#L530) | 类级是普通 Application，单个方法改为完整 App；只扫描第一个 `@Config` 会误判。该方法 finally 恢复能力提供器，但未自行恢复 App 安装的默认异常处理器。 |
| 宿主注册表 | [AdditionalDeviceConnectionsTest.kt:66](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AdditionalDeviceConnectionsTest.kt#L66)、[NotificationCapabilityHttpTest.kt:320](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/NotificationCapabilityHttpTest.kt#L320)、[ProductionPluginHostTest.kt:79](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHostTest.kt#L79) | 修改 ScreenHostRegistry、RemoteScreenCaptureRegistry、KernelPrimitiveRegistry、NotificationControlRegistry。存在局部恢复不等于所有宿主回调和 install-once 守卫完整复原。 |
| 进程默认异常处理器 | [ProductionPluginHost.attach:97](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt#L97) | `attach` 捕获旧 handler 并替换全局 handler；多个插件测试显式恢复，完整 App 组合根未在测试级统一恢复。继续隔离，不把普通 Application 注解当充分证明。 |
| 未关闭的协程和 ViewModel 登记 | [SettingsViewModelTest.kt:45](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsViewModelTest.kt#L45)、[SettingsViewModel.kt:166](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt#L166) | 测试创建外部 CoroutineScope、没有 @After cancel；ViewModel 的 trust/stop 观察器仅在 onCleared 关闭。未来应由 ViewModelStore.clear 和测试 scope 的 cancel/join 证明清理，不修改业务逻辑。 |
| JCA Security Provider | [AttachmentKeystoreRegressionTest.kt:239](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AttachmentKeystoreRegressionTest.kt#L239)、[GatewaySessionRefreshTest.kt:769](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewaySessionRefreshTest.kt#L769) | 即使 finally 移除 Provider，优先级/原 Provider 恢复仍属于整个 JVM 环境；本轮不共享。 |
| Locale 与角色 Shadow | [InstalledNotificationAppsTest.kt:92](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/notifications/InstalledNotificationAppsTest.kt#L92)、[AssistantRoleTest.kt:40](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/AssistantRoleTest.kt#L40) | 前者 finally 恢复 Locale；后者仅 Before 显式 reset 角色静态表。先保留隔离，避免扩大这次实验的状态边界。 |
| 真实 HTTP 与子进程 | [AndroidAttachmentContentTypeTest.withGateway:123](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AndroidAttachmentContentTypeTest.kt#L123)、[HermesHistoryMediaHttpTest.kt:102](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/HermesHistoryMediaHttpTest.kt#L102)、GatewayRuntime/LoopbackGatewayStub 使用者 | 保留端口、流、子进程与真实鉴权交互语义。`manifest=Config.NONE` 的 AndroidAttachmentContentTypeTest 不应误记为完整 App 初始化。 |
| 缺省 Application 的解析测试 | [AssistantHandoffIntentTest.kt:27](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/assistant/AssistantHandoffIntentTest.kt#L27) | 只见 Intent/Context 解析，但当前未显式替换 Manifest Application。本轮保留原条件，以保证同源码对照；未来可独立验证最小 Application 是否足够。 |

## 固定等待和轮询候选

没有因为它们慢而删测试、缩小 SDK 矩阵、缩短业务超时或更改产品行为。

| 位置 | 已知等待 | 最小可验证方案 |
| --- | --- | --- |
| [NativeConversationNotificationsTest.kt:36](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeConversationNotificationsTest.kt#L36)；60、67、78、141、381、390、510、612 | `Dispatchers.Default` 上八处 `delay(100)`；[coordinator:647](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeConversationNotificationsTest.kt#L647) 已注入 scope、now，节流为 40ms | 可在独立改动中改为 TestScope/StandardTestDispatcher，clock 与 scheduler 同步推进，保留全部“节流、终态刷新、禁止重放”断言。八处固定等待乘完整 API 矩阵上界约 4.8 秒，不能解释整类 65.108 秒。 |
| [GatewaySessionRefreshTest.kt:305](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewaySessionRefreshTest.kt#L305) | 后台网络回调后固定 Thread.sleep(100)，验证不发生新协商 | 未来用注入调度器或明确回调处理完成信号，继续验证禁止发起新请求。负向断言不能只删等待而立即读取计数。 |
| [GatewaySessionRefreshTest.awaitConnected:1348](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewaySessionRefreshTest.kt#L1348)、[awaitCondition:1378](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewaySessionRefreshTest.kt#L1378) | MainLooper 推进 + 20ms sleep；上限 10 秒 | 记录实际等待次数和完成事件，先区分真实 HTTP 与主线程任务；能等待 StateFlow/Deferred 时用有界完成信号，不统一调低超时。 |
| [SettingsViewModelTest.kt:469](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsViewModelTest.kt#L469) | operationNotice 以 20ms 轮询，最多 10 秒 | StateFlow.first 等待目标状态，并确保 ViewModelStore、外部 Scope 清理；仍保留真实刷新被拒到可见文案的链路。 |
| [GatewayWorkbenchLogoutTest.setUp:47](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewayWorkbenchLogoutTest.kt#L47) | 每例真实登录后 MainLooper + 20ms sleep | 该类验证真实工作台退出，重复登录属于测试前提；不能用假已连接状态提速。先测量，若优化等待则仍使用真实连接完成信号。 |
| [ConversationAttachmentPickersTest.kt:203](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickersTest.kt#L203)；245 | 5ms sleep 与 MainLooper 推进，最多 5 秒 | 可等待附件协调器/状态流的明确完成，仍验证 URI 去重、原附件与草稿保留以及目标账号。 |
| [NativeConversationMirrorTest.kt:313](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationMirrorTest.kt#L313)；330 | 锁顺序探测 5ms 轮询 | 保留真实多线程死锁探测；不能用同线程假的已完成替代。可用 latch 明确线程已进临界区，再有界 join。 |
| [NotificationCapabilityHttpTest.kt:194](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/NotificationCapabilityHttpTest.kt#L194) | 每 20ms 请求真实 pending 控制面，最多 5 秒 | 属于真实跨进程业务链；若要减少轮询，应由夹具提供 readiness/完成信号，继续校验 requestId。 |
| [HermesHistoryMediaHttpTest.kt:102](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/HermesHistoryMediaHttpTest.kt#L102) | 50ms 请求媒体元数据，最多 15 秒 | 等待宿主真实 remoteAvailable；固定 SDK 初始化或元数据状态不得替换成 Mock。 |

另有 AccountSwitchPersistenceFailureTest:173、ApprovalDecisionAuditWiringTest:122、AuthSessionLifecycleTest:295、ColdStartConnectionTest:1005、GenerationCancelGateTest:157、ReplyNotificationsTest:336 的 20ms 有界轮询；它们均保持原实现并排除本次共享候选。

## 四组 Suite 的边界和已完成验证

[ReusableAppTestGroups.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ci/ReusableAppTestGroups.kt) 保留子类原有 Runner，并调用 JUnit 4.13 标准 `Ordering.shuffledBy(Random(seed))`。三个 systemProperty 为：

- `oai.testReuseClasses`：本次真实选择的完整限定类名，以逗号分隔。
- `oai.testReuseAllowlist`：清单文件原文，避免猜测工作目录或另建隐式清单。
- `oai.testReuseSeed`：必须显式提供的 Long 种子。

始终严格校验缺属性、错误种子、重复条目、清单外类、不是测试的类、抽象/非公开类和空组。只加载本次选择且属于本组的类，未选择的清单类不做类加载。

已完成的局部验证如下，均不代表真实 Android 候选已经通过：

1. 缓存 Kotlin 2.1.20 编译器和项目 JUnit 4.13.2 对调度器独立编译成功；没有运行 Android 构建。
2. 16 个局部合成 JUnit 类、每类 2 方法，通过四组执行精确覆盖 32 个方法；相同种子顺序相同，不同种子改变顺序，未选中的缺失类不加载。
3. 缺少种子、种子非法、选择重复、清单重复、清单外、通配符、空组、类无 @Test、选中类不存在九类配置均以失败结束。
4. 独立 Gradle 8.12 Java 小工程使用 `forkEvery=1`、两个并发 fork，仅发现四个入口 class，同时传入入口和真实子类过滤，精确执行 32 方法、0 失败、0 错误。该工程不使用 Android 插件。
5. 独立 Gradle 工程仅传入口过滤也实际执行 32 方法；因此不能把“必须保留子类 filter，否则必漏测”写成已证实规律。主任务仍同时保留真实类过滤以便选择集合核对。
6. Gradle 为四个 Suite 另生成 `tests=0` 的 XML；核对覆盖应比较真实 testcase 身份集合，不能把 XML 文件数量当成实际测试类数。

局部验证文件位于忽略范围外的临时实验目录，后续 App 运行证据由主任务收集。此报告未声明 46 类的 Robolectric JVM 共享、App 同源码性能收益、物理设备行为或完整 PR/Full 测试通过。

## 启用前必须完成

- 同一源码的原隔离与复用模式先比较真实方法身份集合、SDK 参数化方法和 skipped/failure/error；必须完全覆盖原门禁。
- 至少重复相同种子并用多个不同种子乱序运行四组，检查共享状态是否随顺序变化；用四个并发 fork 核对并发资源边界。
- 记录 Gradle 墙钟、JVM/类/方法数据以及 Robolectric 初始化度量，先把可观察阶段分开，再计算收益；不能用累计方法时间推断 fork 时间。
- 一旦发现候选污染，保留原测试与断言、恢复逐类隔离；根据具体失败证据补清理后再单独试验。
