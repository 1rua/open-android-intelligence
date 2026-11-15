# Remote 重构后的 Android 界面基线审计与恢复

关联：Issue #194、PR #190。界面基线为 `12094e684e4bd6d4270513d54b6ef35437536552`，被审计实现为 `2a011240ec0c17c5038778566cc4845caab323bb`。本单记录源码结论和本轮修复范围；测试、渲染及真实设备结论必须另以修复后的同一提交核对。

## 边界与审计方法

- 固定比较 `app/src/main`、`conversation-ui/src/main`、`ui-resources/src/main`，共 105 个变更路径；逐项查看其中 30 个含 Compose 页面/组合函数的路径、5 个 `conversation-ui` 主源码变化和全部资源变化。剩余路径是运行时、身份、推送、镜像、执行器等接线，不能为视觉恢复退回旧协议。
- [既定架构规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md) §1、§6、§9 已明确单一活动 Remote 账号、旧 Core/Gateway 服务专用管理退役，并要求现有聊天/附件/审批/本机能力保留。因此旧多账号、Core HTTP/SSE 协商诊断、旧设备配对与解除不重新引入；分组视觉、返回层级、退出确认等继续保留。
- 本轮用户授权仅变更登录卡内官方/自部署入口及真实扫码/OAuth 接线、会话顶端 AgentEndpoint/模型数据。主题、原有页面结构、动画、其余文案和既有能力保持基线。登录新增中文逐字来自本轮要求；错误显示复用原中文可读指引，涉及产品名只将 Gateway 替换为 Remote。

## 确认的回归与修复

| 位置 | 被审计实现的问题 | 恢复方式 |
| --- | --- | --- |
| `RemoteWorkbenchRoot.kt`、旧 `MainActivity.kt` | 根 Surface、插件淡化、登录与工作台淡入滑移被硬切换替代 | 复用原 `MaterialTheme.colorScheme.surface`、`MotionSpecs`、`AppTransitions`；保留完整外观偏好传参 |
| 登录与原生插件设置入口 | 用全屏 Surface 覆盖，绕过原抽屉宿主 | 复用 `PlatformSettingsDrawer`，保留手机侧滑、宽屏 320dp 抽屉和原 `SettingsNavHost` 转场 |
| 原 `GatewayLoginScreen.kt`、新 `RemoteLoginScreen.kt` | 品牌、右上状态、限宽、圆角卡、密码显示/清空、键盘动作、安全提示被裸表单替代 | 恢复原 `SignalStitch`、尺寸/形状/字级、IME/系统栏避让、表单校验、Next/Done、阶段淡化和按钮进度；默认官方，卡内切换自部署；不再引用旧 Gateway 类型 |
| 全局提示 | 提示缺少关闭操作，遮挡输入坞；麦克风权限拒绝后缺少系统设置动作 | 恢复可关闭 Snackbar、输入坞与手势条避让、原“前往设置”应用权限页动作 |
| 通知、助手交接、分享附件 | 成功打开会话仍被设置页遮挡 | 仅在真实消费成功后关闭设置；失败保持待消费状态 |
| 连接与账号页 | 原分组卡片被状态文本/裸按钮替代，退出不确认，诊断路由重复主页 | 恢复原分组卡片/设置行/独立诊断页/退出确认；只展示真实 Remote 账号、origin、Client、Virtual Host、Host 与工作区状态；不将离线账号当已建立安全连接 |
| 工作台离线与错误 | 本地只读镜像仍存在，但离线横幅、重连、可关闭重试提示未装配 | 从真实控制器 `isOnline` 呈现原离线说明，重连当前持久工作区；错误码转换原可读指引，不直接展示异常正文/地址；未知错误完整复用原 `CONNECTION_FAILURE_FALLBACK`，仅将 Gateway 替换为 Remote |

## 全部界面差异归属

以下路径以 `apps/android` 为根；`mobile/` 指 `app/src/main/kotlin/com/openandroidintelligence/mobile/`。

| 文件或完整子组 | 源码结论 |
| --- | --- |
| `mobile/MainActivity.kt`、`RemoteWorkbenchRoot.kt`、`WorkbenchSettingsHost.kt` | Activity 改为 Remote 装配；本轮恢复上述根容器/转场/设置与导航。工作台设置呈现仍取宿主宽度 |
| `mobile/GatewayLoginScreen.kt` → `RemoteLoginScreen.kt`、`RemoteConnectionFeedback.kt` | 恢复旧视觉与表单行为，只替换已授权登录入口；认证桥由独立 Remote 认证实现接入。状态点须同时有在线工作台、真实已连接 Client、非切换状态，保存的端点或残留连接状态不计为已连接 |
| `mobile/PlatformSettingsBottomSheet.kt`、`PlatformSettingsScreen.kt`、`SettingsScreen.kt`、`settings/SettingsNavHost.kt` | 原容器和页面动画未变，数据注入改为本机设置/Remote；本轮恢复正确调用容器和独立诊断路由 |
| `mobile/SettingsViewModel.kt`、`SettingsCapabilityUi.kt` | 设置状态接线/旧协议引用退役；通用设置行、形状反馈、操作横幅本体未变 |
| `mobile/settings/connection/ConnectionSettingsScreen.kt` → `RemoteConnectionSettingsScreen.kt` | 旧协议数据模型退出；恢复原分组视觉、确认与返回层级，以真实 Remote 数据替换 |
| `mobile/AccountAvatarPicker.kt` | 系统相册、图片编码与界面形状未变；回调绑定 Remote 账号归属，避免切账号后写错头像 |
| `mobile/BubbleConversationActivity.kt`、`BubbleConversationWorkspace.kt`、`assistant/AssistantConversationWorkspace.kt` | 气泡/助理继续使用原聊天组件与主题；替换 Remote 控制器所有者和必要连接提示，移除旧工厂 |
| `mobile/ReplyNotifications.kt`、`assistant/AssistantBridgeService.kt`、`AssistantHandoffTarget.kt` | 通知与助手入口使用 Remote 会话身份；无页面结构重写 |
| `mobile/NativeAgentSessionsScreen.kt` | 基线只声明、生产无调用；删除不改变活跃界面，不复活重复会话页面 |
| `mobile/onboarding/OnboardingFlow.kt`、`OnboardingState.kt`、`screens/WelcomeScreen.kt` | 保留原引导模板、揭示动画和导航；仅 Remote 通知接线、注释或无用导入变化 |
| `mobile/onboarding/data/GatewayTutorials.kt` → `RemoteTutorials.kt`、`screens/DeploymentChoiceScreen.kt` → `RemoteConnectionChoiceScreen.kt`、`GatewayGuideScreen.kt` → `RemoteGuideScreen.kt` | 旧部署教程按既定规格退役，Remote 教程数据仍进入原步骤卡片/Tab/按钮模板，不新建页面设计 |
| `mobile/plugins/PluginManagementScreen.kt`、`settings/capabilities/CapabilitySettingsScreen.kt`、`DeviceCapabilitiesScreen.kt` | 只替换权限/插件/连接状态来源；原本机开关、授权、急停、卡片和系统权限恢复行为保留 |
| `mobile/plugins/DeviceExecutionDriver.kt` → `screen/ScreenCaptureAuthorizationLauncher.kt` | 旧执行器退役，新 Kotlin 屏幕授权接线继续拉起真实系统授权；不伪造已授权状态 |
| `mobile/push/PushSettingsScreen.kt` | 推送目标改为真实 Remote Host/backend/workspace/session 身份；原选择/保存表单及新推送能力保留 |
| `mobile/settings/advanced/AdvancedSettingsScreen.kt`、`MessageProcessingSettings.kt` | 原页面、文案和保存交互未变；保存后通知 Remote 重新加载防抖设置 |
| `mobile/settings/backup/SettingsBackupScreen.kt`、`SettingsBackupCoordinator.kt`、`SettingsBackupModel.kt` | 原导入导出界面保留，只替换本机配置/Remote 地址与工作区刷新；不复活旧账号配置 |
| `conversation-ui/.../assistant/AssistantBridgeProtocol.kt`、`state/WorkbenchController.kt` | Reasoning DTO、代次/终态、可靠提交持久化和 Remote 错误类型接线，无全局设计替换 |
| `conversation-ui/.../workbench/ExecutionViews.kt`、`StreamingPresentationHost.kt`、`TimelineDisplay.kt` | 结构化 reasoning 映射原 ThoughtBlock/执行卡与正文来源投影，原 Markdown、气泡、触觉与输入坞结构保留 |
| `ui-resources/.../strings_connection.xml`、`strings_settings.xml` | 变化限既定旧部署教程到 Remote 指引；非教程中文未改。本轮连接设置恢复原“连接与账号”标题 |
| `ui-resources/.../strings_permissions.xml`、`strings_remote.xml` | 短信发送/SIM 核验必要授权及 Remote 状态/引导资源；原语音提示逐字保留。本轮只追加明确要求的登录文案 |
| `app/src/main/AndroidManifest.xml`、旧 `res/xml/gateway_account_authenticator.xml` | 旧账号认证组件退出；新认证/扫码入口另由 Remote 认证实现补齐，不能作为界面恢复回引旧依赖 |

静态确认无视觉差异：`conversation-ui` 的 Theme、配色角色、Typography、Shapes、DesignTokens、MotionSpecs、SettingsComponents、WorkbenchScreen、抽屉、消息卡、输入坞、附件选择/图片查看/审批；`app` 的外观、安全、通知提醒、审计页面。它们无需重写，也未因本轮恢复新增全局 Token。

## 验证状态

- **VERIFIED（仅源码静态核对）**：固定基线差异盘点、旧活跃调用链、既定退役边界与 `git diff --check`。本轮独立评审指出的连接状态误判与兜底文案缩短已在源码修正；尚未运行任何新增或既有测试，不能据此标为行为验证通过。
- **UNVERIFIED（待集成运行）**：`RemoteLoginPresentationTest` 的 9 项测试源码包括默认官方入口、认证动作回调、卡切换不认证/清密码、眼睛开关、校验与 Done、忙碌禁用、真实“前往设置”往返、完整原文错误/重试、离线重连和浅深主题 View 绘制；这 9 项不覆盖全部 Root 行为。渲染 PNG 仅写构建报告目录，不入库。
- **UNVERIFIED（根页面交互待运行）**：新增 `RemoteRootInteractionTest` 的 5 项源码，使用生产根页面提取的导航副作用、原 `PlatformSettingsDrawer`、`ReceivePendingConversationShares`、实际 `RemoteConnectionSettingsScreen` 验证：缺少控制器/离线/切换/残留 Client 时状态点未连接；麦克风拒绝提示动作打开本应用系统权限页；通知与助手消费失败保留抽屉和待处理请求、成功后关闭抽屉；附件等历史完成才导入并关闭抽屉一次；退出取消保留账号且确认后才调用真实 Runtime 退出。网络消费者在测试边界可控，不能将这些用例解释为真实通知/助手跨协议验收。
- **UNVERIFIED（待同一提交回归）**：既有设置导航/响应式宿主、设置行形变、工作台布局、主题对比度以及认证桥测试；应由统一验证任务记录实际结果后更新本节。
- **UNVERIFIED（真实环境）**：官方 OAuth/扫码登录、动态壁纸取色、真实设备页面和物理触觉。Robolectric 或静态复用不能证明这些验收。
