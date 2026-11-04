# Android 设置系统：旧新位置与状态来源

- 读者：维护 Android 设置界面和执行回归的开发者。
- 审查基线：`dev@2ad29b9c`（初始审查为 `8f5a6370`，期间仅追加文案调整）。
- 状态：审查完成，实施与验证中；表内目标位置不是验收通过声明。
- 原实现位于 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/`，下表路径以此为基准。原路由集中在 `SettingsScreen.kt:127–141`。

## 导航和操作映射

性质：设置、只读、跳转、危险操作。重复项改为同一个编辑实现的快捷入口，不创建第二份持久状态。

| 旧入口/功能 | 新位置 | 原数据源/保存位置 | 性质与重复处理 |
|---|---|---|---|
| 外观子页主题模式 | 外观与交互/主题 | `AppearancePreferences.kt:61`，`open_android_intelligence_appearance/theme` | 设置；与引导共用编辑内容 |
| 动态取色/预设色板 | 外观与交互/主题色 | 同文件 `dynamic_color/palette` | 设置；真实默认动态色关闭，保留全部预设 |
| 图标动态取色 | 外观与交互/桌面图标 | `dynamic_icon_color`、`LauncherIconManager` | 设置；不代替桌面系统授权 |
| 减少动画 | 外观与交互/动画与无障碍 | `reduce_motion`、`LocalMotionPolicy` | 设置；观察系统缩放，不写系统设置 |
| OAI Core 管理/地址 | 连接与账号/当前连接 | `GatewayRuntime.phase/localSessionState`；原运行时 prefs、AccountManager | 只读；不新增单独地址数据库 |
| HTTP/实时通道/登录状态 | 连接与账号/当前连接 | `coreHttpHealth/connectionStatus/loginStatus` | 只读；未知、断连、退出分开 |
| Agent 选择/刷新/在线 | 连接与账号/Agent 管理 | `NativeAgentsController`；账号 `selectedAgentId` | 设置/操作；不清镜像和草稿 |
| 当前/保存账号、切换 | 连接与账号/账号管理 | `AndroidAccountProfileStore`、`GatewayRuntime.savedProfiles` | 设置/操作；复用原账号流程 |
| 添加账号、邀请、设备密钥登录 | 连接与账号/账号管理，既有登录页 | `GatewayLoginScreen`、`GatewayRuntime.login` | 操作；保留原邀请入口 |
| 凭据续期 | 连接与账号/账号管理 | `refreshSession`、Keystore/续期日志 | 操作；失败不能报成功 |
| 退出当前账号 | 连接与账号/账号管理 | `logout`、原退出屏障 | 危险操作；保留私有镜像和配置 |
| 移除本机账号（原登录页） | 连接与账号/账号管理 | `removeLocalAccount`、AccountManager/账号私有文件 | 危险操作；Connected 时先进入原账号管理，不能调用被拒绝的操作冒充完成 |
| 加密/Core 身份/设备配对 | 连接与账号/连接安全 | `TransportSecurity`、`GatewayIdentityTrustStore`、实际 binding | 只读；历史信任不代表本次在线 |
| 首页/Core 页解除配对 | 连接与账号/连接安全 | `PairingRevocationClient`、`unpair` | 唯一危险入口；远端确认后才清当前私有范围 |
| 协商功能/设备任务诊断 | 连接与账号/连接详情与诊断 | `NegotiatedCapabilitiesGroup`、`deviceRequests` | 只读；三态与未接入状态保留 |
| 系统通知推送 | 通知与提醒/Agent 任务通知 | `ReplyNotificationSettings`，`agent-reply-alert-settings/enabled` | 设置；checked 只看偏好，系统权限单列 |
| 通知权限/渠道 | 通知与提醒/系统通知设置 | Android NotificationManager、POST_NOTIFICATIONS | 只读/系统跳转 |
| 会话气泡 | 通知与提醒/会话气泡 | 原渠道、shortcut、系统气泡偏好 | 只读/系统跳转；无假开关 |
| Android 16 实时活动 | 通知与提醒/实时任务状态 | `LiveUpdateNotifications`、promotion 权限 | 只读/系统跳转 |
| 授权管理→全局设备能力→插件 | 设备能力与授权/设备能力 | `PairingGrantStateHolder`、`ProductionPluginHost` | 按能力编辑；取消纯中转页 |
| 屏幕读取/截图/控制两处编辑 | 对应能力详情 | 同一 `CapabilityPolicy`、`ScreenHostRegistry` | 唯一策略编辑内容；系统状态独立 |
| 无障碍/投屏授权/停止屏幕请求 | 屏幕能力详情 | `ScreenCapabilityController`、Activity 回调 | 只读/系统操作；保留捕获票据双重复核 |
| 短信读取/三级模式 | 短信能力详情 | 原安装实例能力策略 | 设置；不改默认每次手动 |
| 短信范围/正文 | 短信能力详情 | `SmsPassiveScopeSettings`：`smsScopeMode/smsKeywords/allowedSources/includeContent` | 设置；全部/关键词/号码、正文独立 |
| 短信定时/状态查询/取消 | 对应实际短信能力 | 已验证插件声明及原策略 | 设置/只读；不丢失高级能力 |
| 通知读取总开关/模式 | 通知能力详情 | 安装实例能力策略 | 设置；与通知采集许可不是同一位 |
| 允许读取/应用范围/标题正文 | 同一通知能力详情 | `NotificationPolicyPort`→`notification-policy-authority.bin` | 设置；原采集门控保留 |
| 通知使用权 | 同一通知能力详情 | `NotificationBindingPort`、系统绑定 | 只读/系统跳转 |
| 自动把通知发送给 Gateway | 同一通知能力详情独立投递设置 | 原 `NotificationDeliveryMode`、`notification-outbox.bin` | 保留设置/队列；显示真实通道状态，不改接线 |
| 通话记录 | 通话能力详情 | 实际插件策略、READ_CALL_LOG、`PrimitiveBootstrapProvider` | 设置/只读；不接入旧 collector 的无效编辑项 |
| 第三方能力/提供者 | 对应能力详情 | 实际 `VerifiedCapability`、`CapabilityProviderSelector` | 设置/只读；保留身份/Schema 绑定 |
| 短信主动推送/范围/正文 | 设备能力与授权/主动推送 | `DevicePushRuntime`、`EncryptedPushStateStore` | 设置；独立于被动查询，默认关闭 |
| 通知主动推送/应用范围/正文 | 同一主动推送详情 | 加密推送配置 notifications | 设置；完整应用选择器复用 |
| 接收端白名单/刷新 | 同一主动推送详情 | `DevicePushComposition.endpoints`、原稳定路由身份 | 设置/操作；不自动替换过期端点 |
| 队列保留期/容量/待发/撤销/刷新 | 同一主动推送详情 | 原 `PushConfiguration/PushSnapshot` | 设置/只读；原规则不变，不新增限制 |
| 撤销临时授权 | 设备能力与授权/临时授权 | `authorization.activeApprovals(revision)/changes` | 列表只读/撤销危险操作；同一 Controller |
| 插件安装/更新/校验/损坏包 | 设备能力与授权/插件管理 | `PluginInstallStore`、原验签/审批 | 操作/危险操作；文件/HTTPS/内置来源保留 |
| 插件启停/卸载/安全恢复 | 同一插件管理详情 | 原 host/kernel/安装与恢复 prefs | 设置/危险操作；恢复保持停用 |
| 后台任务/声明式设置/插件界面/手动调用 | 同一插件管理详情 | 原 `setBackgroundEnabled/setting/selectNativeUi/invoke` | 设置/操作；不因拆包删除 |
| 插件能力配置与提供者按钮 | 跳对应唯一能力详情 | 原 host/grants | 快捷跳转；不再维护另一份授权表单 |
| 两处开发者信任模式 | 安全与隐私 | `DeveloperTrustMode`、`open_android_intelligence_platform/developer_trust_mode_enabled` | 唯一设置；确认/渠道门控保留 |
| 安全策略说明 | 安全与隐私/运行策略 | 实际运行时/渠道与明确标注的说明 | 只读；不假称关闭信任就禁止宿主屏幕控制 |
| 授权/调用/敏感记录 | 安全与隐私/授权与调用记录 | `ObservableAuditSink/AuditEvent`、原持久审计 | 只读；不记录敏感正文 |
| 两处紧急停止 | 安全与隐私/紧急停止所有插件 | `PluginKernel.emergencyStop`、Application 计数 | 唯一危险入口；需重启恢复 |
| 首页消息防抖/等待/延长 | 高级设置与关于/消息处理 | `conversation-debounce-v1`，`global`/Core URL 摘要键 | 设置；0～10 秒，0 立即发送 |
| 全局默认/当前 Core 覆盖 | 同一消息处理组 | `DebouncePreferences`、原 `updateDebouncePolicy` | 设置；不新增 enabled 键或改变 Local-First |
| 应用更新/渠道/检查/Release | 高级设置与关于/应用维护 | 原 `AppUpdateViewModel/Preferences/Checker` | 设置/操作；原检查触发时机保留 |
| 引导重看/部署教程 | 高级设置与关于/帮助与反馈 | `OnboardingPreferences.startReplay/endReplay` | 跳转；不重置 finished/账号/授权 |
| 文档/Issues | 同一帮助组 | 真实项目文档与 Issues 地址 | 外部跳转；浏览器不可用可见 |
| 版本/渠道/Commit/构建详情 | 高级设置与关于/关于 OAI | `installedAppBuild/BuildConfig`、实际安装包 | 只读新增展示，不伪造字段 |
| 许可证/项目地址 | 同一关于组 | 实际 LICENSE/notices/项目地址 | 只读/跳转，离线查看真实许可文本 |

## 不能合并的状态与数据源

- 授权模型版本是 4，旧名称存储前缀仍是 `installation_capability_policy_v3`；本次不改变版本/键名。
- 插件安装/启用、本机策略、Android 权限、本机临时批准、Core 同步回执各自独立。
- 通知被动查询仍使用原策略权威的 granted/应用范围/字段访问；不能把它们移入新主动推送配置。
- 旧 AUTO_SEND 可进入旧加密 outbox，但当前生产源码没有 `NotificationHostComposition.attachBridge()` 调用，dispatcher 未装配。新 `NotificationPushSource → DevicePushComposition → DevicePushRuntime → GatewayPushTransport` 独立工作。保留两条路径及原队列；不自动启用旧出口或迁移旧开关。
- 能力 UI 按名称组织，但策略键包含作者、能力、Schema/风险/原语摘要；不同提供者不能合并保存。
- 生产 Agent 切换使用 `GatewayRuntime.updateNativeWorkbench()` 和 `closeAndAwait()` 持久交接；遗留无调用的 `NativeAgentSessionsScreen` 内存草稿行为不能代表主会话链路。
- 通知 preference 已分开存储但旧 UI checked 使用 enabled AND allowed，导致误显示；本次改 UI 与真实保存失败反馈，不改用户已有值。

## 原路由兼容

保留 `settings/overview`、`appearance`、`gateway`、`pairing`、`security`、`audit`、`plugins`、`device-push`、`notifications`、`app-updates`、`screen-capability`。其中 pairing 直接成为设备能力分类，gateway 成为连接与账号，notifications 仍是通知读取，不能误导至提醒。所有入口复用同一导航图和页面实现；重定向不创建空菜单层或循环栈。

## 回归要求

导航、保存失败、账号/Agent 分区、系统授权返回刷新、插件高级操作、应用选择器完整行为、推送范围/端点/队列、消息合并、通知组合、重看引导分别验证。自动化、模拟器和实体设备证据分开记录；无运行条件项标 BLOCKED。本表将在实施验证后补充具体测试与结果链接，当前未宣称通过。
