# 内建官方设备能力与授权页重构：实施记录

日期：2026-11-08。分支：`feature/builtin-capabilities-20261108`，目标分支 `dev`。
规格：[内建设备能力与「设备能力与授权」页面重构](../superpowers/specs/2026-11-08-builtin-capability-consolidation.md)。
Issue：[#94](https://github.com/1rua/open-android-intelligence/issues/94)。

## 一、根因

1. **官方能力走的是第三方插件的安装链路。** 六个签名 `.alp` 随 APK 打包，但必须由用户在插件管理里
   逐个点击「安装」再点「启用」；`installBundledPlugin` 固定 `requireNew=true`，装完仍停在
   `INSTALLED_DISABLED`。结果：全新安装后 Agent 无法调用任何官方能力，而用户被要求管理实现细节。
2. **截图的系统权限映射与底层取帧路径相反。** 底层 `AccessibilityFirstScreenCaptureSource` 早已是
   “无障碍优先、MediaProjection 备用”，但 `androidPermissionRequirements` 把
   `kernel.screen.capture` 无条件映射成 `MEDIA_PROJECTION`，且无障碍需求只在声明
   `kernel.screen.read`/`kernel.screen.control` 时生成。截图详情页因此没有任何无障碍入口。
3. **状态判定分散。** 主页面把「安装态 / 宿主连接 / 插件启用 / 本机策略」压成一个字符串并拼进
   pluginId；「安装状态」短路在「本机授权」之前；Core 断连被表述成插件缺失。
4. **通知应用范围二次打开闪烁。** 每次进入详情都把 `installedApps` 置空重新枚举，枚举完成前
   已保存选择没有名称可显示，于是先显示「应用信息暂不可用」。

## 二、改动

### 2.1 内建能力装配（新增）

`plugins/BuiltInCapabilities.kt` + `ProductionPluginHost.registerBuiltInCapabilities()`：

- 每次 `attach`/`reload` 从 APK 资源读取六个包，走**同一个** `AlpVerifier`（清单形状、Ed25519 签名、
  容器限额、ABI 与入口点、能力原语收窄），校验通过后注册并 `enable`。
- **不落盘**：校验后读出所需字段与 WASM 载荷即删除暂存目录；不写
  `verified-plugin-enablement-v1`，不产生 `filesDir/plugins/<id>`。
- 因此升级后不可能继续加载旧版本残留：每次启动都用当前 APK 内的包。
- 单项失败只影响该项：`builtInFailures[pluginId] = 稳定原因码`，主页面显示 `不可用`，
  详情页给出可执行说明；其余五项照常可用。
- 磁盘扫描按「已成功加载的内建 ID」过滤：旧版本留下的官方目录不再被加载，目录本身不动，
  用户已有的插件私有数据仍留在原位置。
- `ProductionPluginHost.uninstall` 对官方 ID 抛 `BUILT_IN_CAPABILITY_NOT_REMOVABLE`。
- 安全声明随注册保存在内存里（`builtInSurfaces`），私有存储限额与网络代理沿用同一份声明；
  官方包不声明网络，因此内建路径实际是 deny-all。

**默认授权未被扩大**：`CapabilityPolicy` 默认仍是「开关开启、每次手动」，主动推送默认关闭；
内建能力“随应用就绪”只是让能力可被发现，请求仍按本机策略与系统权限逐次裁决。

### 2.2 授权页面（重写）

- `CapabilityCatalog.kt`：目录从 9 条缩到 6 条并重排为「屏幕 / 通讯与通知 / 更多」；
  短信一条入口通过 `members` 管理四项能力；第三方能力不再进入这个页面。
- `CapabilityState.kt`（新增）：`CapabilityFacts` → `CapabilityState` 的纯函数，判定顺序固定为
  不可用 → 已关闭 → 需授权 → 已开启；`isReady(facts)` 与 `已开启` 严格等价。
- `DeviceCapabilitiesScreen.kt`：三组六项 + 插件管理；每行只有名称、状态词、箭头与图标；
  删除临时授权区、「短信与通知主动推送」入口、技术说明与 pluginId。运行状态改为同步读取
  （`capabilitySnapshot()` 在调用线程即时返回），避免首帧显示错误状态再跳变。
- `CapabilitySettingsScreen.kt`：统一为「能力 → 短信任务 → Android 系统权限 → 运行状态 →
  通知采集范围 → 主动推送 → 停止屏幕请求」；`entries` 为空与宿主未连接分开表述，
  避免把加载失败说成“未安装插件”。

### 2.3 屏幕截图权限引导

`DeviceCapabilityPermissions.kt`：

- 新增 `accessibilityScreenshotSupported` 参数；API 30+ 时 `kernel.screen.capture` 的首选需求是
  `ACCESSIBILITY_SERVICE`，投屏以 `isFallback = true` 紧随其后。低于 API 30 时投屏才是唯一路径。
- 新增 `needsFallbackConsent(requirements, stateOf)`：首选入口已就绪时不再提示备用方案，
  这是“无障碍可用时不要求屏幕共享授权”的可执行形式。
- 无障碍需求由 `kernel.screen.read` / `kernel.screen.control` / API 30+ 的
  `kernel.screen.capture` 合并为一条，不重复出现。
- `screen/AccessibilityServiceState.kt`（新增）：`accessibilityServiceReady`（设置已开 + 系统已连接）、
  `accessibilityServiceEnabledInSettings`、`accessibilityServiceDisconnected`；详情页在断连时
  显示「服务已开启但当前未连接」而不是笼统的「需开启」。

### 2.4 主动推送与临时授权归位

- `PushSettingsScreen.kt` 拆出 `NotificationPushSection` 与 `SmsPushSection`，分别挂在
  「通知读取」与「短信」详情；两处仍写同一份安装实例级配置。
- 主页面移除入口，`settings/device-push` 旧路由保留但只做重定向；加密队列的容量/保留时长
  不再作为设置项，底层队列与已保存值照旧运行。
- 新增 `CapabilityTemporaryApprovals`：只列出**该项能力**生效的临时批准，支持逐条与全部撤销；
  主页面那张混合清单（`TemporaryApprovalsSection`）已无调用方，移出源码树。
  `TemporaryApprovalsSection.kt` 备份在
  `/tmp/open-android-intelligence-trash/20261108-temporary-approvals/`。

### 2.5 应用名称闪烁

`InstalledNotificationApps.kt` 增加进程内目录缓存：设置页先用缓存渲染已保存选择，后台刷新，
刷新失败保留旧值；占位文案从「应用信息暂不可用」改为「正在读取应用信息」，并且只在真正读不到时
才显示不可用语义。

## 三、测试

新增与改写的测试（全部 JVM/Robolectric，无真机依赖）：

| 层 | 文件 | 断言要点 |
| --- | --- | --- |
| 纯函数 | `CapabilityCatalogTest` | 三组六项、短信四合一、第三方能力不上主页、提供者不被合并 |
| 纯函数 | `CapabilityStateTest` | 四态判定顺序、`已开启 ⇔ 已就绪`、读不到策略按关闭 |
| 纯函数 | `ScreenCaptureGrantRoutingTest` | 截图首选无障碍、投屏仅备用、API<30 投屏唯一、无障碍就绪后不再要求投屏 |
| 装配 | `BuiltInCapabilitiesTest` | 六项从资源加载、不落盘、重复加载无副作用、缺包/篡改/冒名各自失败、单点失败不牵连、
全新安装即启用、关闭的策略在重载后仍关闭、官方 ID 不可卸载 |
| 页面 | `SettingsHonestyCopyTest` / `SettingsCapabilityUiTest` / `PluginManagementScreenTest` /
`SettingsResponsiveHostRegressionTest` / `PushSettingsScreenTest` / `CapabilitySettingsTest` | 主页结构、
无 pluginId 与技术说明、官方能力不在插件管理、临时授权按能力呈现、推送草稿跨断点保留 |

既有第三方插件测试改为经 `thirdPartyOnlyHost(...)` 构造宿主（内建包一律读不到），
使它们继续验证“安装/启用/卸载”链路，而不再依赖“本机恰好没有打包内置资产”这一偶然条件。

### CI 运行结果

PR #97 全部 7 项检查通过：`android`、`apk`、`core (3.11)`、`core (3.12)`、`dsh`、`hermes-native`、
`sign-plugin-packages`。

首轮 CI 曾让 `android` job 失败，原因是 Play 变体下 `ALLOW_RUNTIME_PLUGINS = false`，宿主在
`attach()` 开头直接返回、不注册内建能力，四个 full-only 用例因此失败。修复同时纠正了一个真实缺陷：
Play 渠道下能力页原先显示「看不到这项能力的提供者」，是一条用户在该渠道无法执行的指引；现在
逐项报告 `BUILT_IN_CHANNEL_UNSUPPORTED` 并说明「当前安装渠道不支持设备能力，请使用完整版本」。
用例按变体隔离（`assumeTrue`），并新增一条反向用例覆盖 Play 渠道。

### 本地运行结果

```
:app:testFullDebugUnitTest  1583 tests completed, 5 failed
```

5 项失败全部是需要外部 Hermes 环境的既有测试：

- `AndroidAttachmentContentTypeTest`（4 项）：`HERMES_FIXTURE_MISSING` — 需要 `HERMES_PLUGIN_ROOT`
  指向 `.hermes-gateway-plugin` 的 fixture。
- `HermesHistoryMediaHttpTest`（1 项）：`HERMES_SDK_ROOT_REQUIRED` — 需要 `HERMES_SDK_ROOT`。

两者都在 CI 里由 `HERMES_PLUGIN_ROOT` / `HERMES_SDK_ROOT` 环境变量提供，本地未配置，与本次改动无关。
另有一次中途出现的 `NativeLocalSnapshotReadTest` `NoClassDefFoundError` 单跑复现为通过，属增量编译残留。

测试环境：JDK 21（`~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`；Robolectric SDK 36 要求 ≥21，
本机默认 JAVA_HOME 是 17），`gradle --no-watch-fs`（内核 7.1.6 下 watchfs 初始化会 NPE）。

## 四、评审意见的处理

PR #97 的自动评审（Codex）提出三条 P1，逐条核实后**全部属实**，均已修复并补测试：

| 评审意见 | 核实 | 修复 |
| --- | --- | --- |
| 临时授权按 `id@version` 过滤，与 `policyKey` 永不相等，真实批准被全部隐藏、撤销入口消失 | 属实。`approveRequest()` 存的是 `policyKey(entry)`（`plugin:<id>:<author>:capability:<cap>:<schema>:<risk>:<prims>`），传入的是 `entry.key`（`id@version`） | 改为用 `host.authorizationScope(entry, …).capability` 构造过滤集合；短信详情把三项兄弟能力的作用域一并纳入 |
| 内建短信从插件管理移走后，唯一的「允许后台任务」入口消失，定时任务会一直 `BACKGROUND_GRANT_REQUIRED`，已有许可也无法撤销 | 属实。`setCapabilityPolicy` 显式排除 `kernel.background.run`，而 `PluginJobScheduler.schedule` 同时要求全局与身份作用域两项许可 | 在能力详情新增「后台任务」节，开关直连 `setBackgroundEnabled` |
| 连续两次推送编辑都基于同一份旧快照，第二次会覆盖第一次（例如正文开关被恢复成开启） | 属实。两个回调都用 `snap.configuration` 构造，丢弃 `configure()` 的返回值，且 `saving` 恒为 `false` | 改为串行保存：回写权威快照、以下一次 `expectedConfiguration` 防冲突、保存期间禁用控件、区分 `PUSH_CONFIGURATION_CHANGED` |

第二轮评审又提出两条，同样核实属实：

| 评审意见 | 核实 | 修复 |
| --- | --- | --- |
| 通知采集范围的条件收窄成只看官方插件 ID，第三方通知提供者失去范围编辑入口 | 属实。`NotificationsReadPrimitive` 执行的是全安装共享的采集策略，按 ID 判定会让第三方提供者无法配置真正生效的范围 | 条件改回按原语判定（`entries.any { "kernel.notifications.read" in it.primitives }`），刷新副作用同步改；推送设置仍按“官方插件 + 官方能力”双条件，不挂到第三方 |
| 磁盘旧副本的过滤只看“本次加载成功的内建能力”，资产加载失败时旧实现会顶上来执行 | 属实。这正是规格承诺的反面：内核跑旧实现，界面报加载失败、插件管理又不显示它 | 改为按固定的 `BundledPlugin.entries` ID 集合过滤；`isBuiltIn` 也基于固定的 `reservedBuiltInIds`，因此卸载同样被拒绝 |

对应测试：`NotificationScopeOwnershipTest`（第三方通知提供者保留范围入口、不出现官方推送设置）、
`LegacyOfficialCopyIsNeverLoadedTest`（资产缺失时旧副本仍不注册、不启用）。两条都做过**回退验证**：
把修复改回旧行为，对应用例确实失败。

对应测试：`CapabilityTemporaryApprovalsTest`（含“旧键永不匹配”的反向用例）、
`SmsBackgroundConsentTest`（用声明了后台任务的第三方短信包验证同一条装配）、
`PushConfigurationSerializationTest`（在 `DevicePushRuntime` 上复现覆盖链路）。

## 五、未完成与风险

- **真机验收未做**：无障碍截图可用时的真实取帧、服务断连后的恢复入口、升级覆盖安装后的装配，
  都还需要 Android 设备验证；本次只完成自动化与编译门禁。
- **Play 变体**：内建资产仍只随 `full` 变体打包。该变体不装配插件运行时，宿主现在逐项记录
  `BUILT_IN_CHANNEL_UNSUPPORTED`，能力页显示「当前安装渠道不支持设备能力，请使用完整版本」，
  而不是笼统的“找不到提供者”。相关测试按变体跳过或用专门用例覆盖（`assumeTrue`）。
- **策略键**：既有键不含版本号，内建版本升级不会重置用户选择；但若未来 Schema 或原语声明变化，
  键会变化并按新能力处理，需在那次改动里单独设计迁移。
- CI 新增测试类已加入 `.github/workflows/oai-core.yml` 的 full 与 play 两个过滤列表。
