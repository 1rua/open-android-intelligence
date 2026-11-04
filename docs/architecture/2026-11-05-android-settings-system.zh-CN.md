# Android 设置系统

读者：维护设置页面与新增设备能力的开发者。本文解释模块职责与现行目标；验证进度见[验收记录](../reviews/2026-11-05-android-settings-verification.zh-CN.md)，不能据架构文档推断设备验收已完成。

## 六个分类

首页只列以下入口，顺序固定：

1. 外观与交互：主题、动态配色、真实预设色板、图标配色、减少动画。
2. 连接与账号：当前 Core、独立 HTTP/事件状态、Agent、账号和连接安全。
3. 通知与提醒：OAI 发给用户的任务通知、会话气泡、实时任务状态与 Android 通知权限。
4. 设备能力与授权：能力、被动范围、独立主动推送、临时批准、插件安装与运行配置。
5. 安全与隐私：开发者信任模式、运行策略、审计、紧急停止。
6. 高级设置与关于：全局/Core 消息合并、更新、引导与帮助、构建与许可证。

通知读取不是通知提醒；主动推送不是被动查询。两者的范围、开关与队列按原权威分别保存。

## 模块组织

App 内 `mobile/settings/` 按功能拆包，不是新增 Gradle 模块：

- `SettingsNavHost`：唯一导航图，六分类、旧路由兼容、同级详情快捷跳转及返回。
- `SettingsScaffold`：页面表面、标题、滚动、安全区与键盘约定。
- `appearance/`：设置和引导共用主题编辑内容；场景外壳保留原引导步骤。
- `connection/`、`notifications/`、`capabilities/`、`security/`、`advanced/`：对应分类与真实详情内容。
- 原 `plugins`、`notifications`、`push`、`updates` 包保留原业务实现；Route/Content 接统一外壳。

`SettingsScreen` 与 `SettingsRoutes` 是兼容门面。`PlatformSettingsScreen` 继续转发，不能将未找到生产调用等同对外兼容入口可随意删除。`conversation-ui` 提供无业务依赖的设置行、卡片、主题和动效，不能反向依赖 App。

## 状态和写入

`SettingsViewModel` 由现有 AndroidX ViewModelStore 持有，读取原有流。Content 只接展示状态与明确回调；滚动、搜索、未提交草稿可保存，已保存设置只以原权威为准。

| 领域 | 权威 |
|---|---|
| 外观 | `AppearancePreferences` 原 SharedPreferences 与 LauncherIconManager |
| Core/登录/账号/Agent | `GatewayRuntime`、AndroidAccountProfileStore、Keystore、原 Agent controller |
| 通知提醒偏好 | `ReplyNotificationSettings`；系统许可另从 NotificationManager 查询 |
| 能力策略 | `PairingGrantStateHolder` + `ProductionPluginHost` |
| 被动通知采集范围 | `NotificationPolicyPort` → 原 PersistentNotificationPolicyAuthority |
| 主动推送/队列 | `DevicePushRuntime` + `EncryptedPushStateStore` |
| 临时批准 | 原 `CapabilityAuthorizationController` 的进程内记录 |
| 审计/信任/熔断 | 原 AuditSink、DeveloperTrustMode、PluginKernel |
| 消息合并 | 原 `DebouncePreferences` 全局键及 Core URL 摘要键 |
| 更新/引导 | 原 AppUpdateViewModel/Preferences、OnboardingPreferences |

没有新的授权表或数据库；授权模型仍为 4，存储前缀名称 `installation_capability_policy_v3` 保留。

### 必须分开的事实

- 登录保留不代表 Core 在线，HTTP 可达不代表实时事件连接，Agent 离线不代表 Core 断连。
- 插件安装/启用、能力总开关/模式、系统权限、提供者、临时批准与 Core 同步回执分开显示。
- 通知开关显示用户保存偏好，不以系统是否允许进行 AND 合成。拒绝系统许可不得改写偏好。
- 本机能力 UI 按能力组织，但原策略键仍绑定作者、能力、Schema、风险和原语摘要，不按显示名称合并不同提供者。
- 关闭开发者信任模式只禁止原生插件运行，不禁止符合本机策略的隔离插件通过宿主访问屏幕能力。

### 操作结果

保存、安装、账号切换和解除配对不通过动画结束判断成功。解除配对单独展示 Idle/Running/Completed/Failed，远端已撤销但本机部分清理失败与完全未撤销分开。

信任关闭后写盘失败，不得回滚成重新开启执行；界面需区分当前状态与下次重启恢复是否可靠。通知偏好写盘失败不发布未保存值。

## 设备能力与插件

能力目录来自原已验证声明和应用内置能力清单；清单只是入口，不证明插件已安装或可用。未装、损坏/未知、停用、系统服务断连、策略关闭和 Core 未同步分别展示。

唯一能力编辑内容负责总开关/三级模式和已有被动范围。插件页仅跳该详情，不再内嵌另一套策略表单；插件后台任务许可、声明式运行设置、原生界面、手动调用、文件/网络/内置安装、更新确认和卸载仍在插件管理。

系统权限使用平台现有对话框/设置。屏幕捕获复用 Activity 的原票据：开始时记录策略、插件和停止代次；收到系统结果与前台服务就绪后再次核对。不保存 UI 状态来恢复有效系统同意。

临时批准按同 Controller 的单调时钟显示有效期；逐条撤销与全部撤销只移除真实内存记录。可读参数摘要仅供展示，鉴权始终按原能力/操作/完整参数摘要匹配；进程重启不恢复批准。

### 旧通知自动投递

原 AUTO_SEND 和新主动推送不是同一条链：旧 NotificationRuntime 使用原加密 outbox/可选 Bridge dispatcher，新链使用 NotificationPushSource、DevicePushComposition、DevicePushRuntime 和 GatewayPushTransport。

当前生产没有调用旧 attachBridge，不能把保存 AUTO_SEND 显示成投递成功。设置展示最近运行时的真实装配状态和队列可用性；保留原开关/队列，不自动接通、迁移或清理。

## 导航与响应式

分类直接编辑普通偏好；复杂配置最多一个功能详情。安装来源、应用选择器和风险确认属于当前功能的交互，不增加纯中转页。

旧 11 个路由继续可达。`settings/notifications` 始终是通知读取，旧屏幕路由定位同一屏幕能力编辑内容，不能改成通知提醒或另一套授权界面。同级快捷跳转保存来源详情状态，替换当前详情，不无限堆栈；返回后恢复来源滚动与草稿。

手机设置用既有左入全屏容器，退出向左；平板按宿主 840dp 断点，在原 320dp 侧栏替换，不扩大为全屏。设置页不重新加载右侧会话。跨断点保留同一 movable content/导航及草稿。

SIDEBAR 的系统安全区由宿主消费，功能页不得二次叠加；选择器另有真实窗口边界，键盘仍需可达。动效全部遵循 LocalMotionPolicy，系统关闭动画和应用减少动态均生效。普通子页返回交给现有 NavHost；根面板手势按既有 Activity Compose 处理，不引入新导航框架。

## 验证与新增设置规则

先核实真实能力/原保存 owner，再添加 UI。禁止添加无数据源开关、默认扩权、通知应用数量上限、第二套保存实现或只读伪点击项。

新增页面需覆盖：普通与侧栏、返回/草稿/滚动、保存失败、离线、系统许可返回刷新、大字号、减少动画与旧路由。测试选择器和现有 dev CI 显式过滤需覆盖新测试；自动化、模拟器和真实设备分别记录证据，缺条件标 BLOCKED。
