# Android 设置重构验证记录

读者：PR 审查者与 Android 验收执行者。状态：进行中，未宣称完整验收通过。

## 代码与环境

- 任务分支：`feature/android-settings-refactor`；基线 `dev@2ad29b9c`。
- 实现采用现有 Compose BOM/Material3、Navigation Compose 2.8.5 和 motion tokens；没有升级 UI/导航依赖、配置数据库或授权模型版本。
- 旧新功能和保存来源见[映射](2026-11-05-android-settings-mapping.zh-CN.md)。

## 当前自动化结果

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 初轮 `:platform-kernel:testDebugUnitTest` | PASS | 观察者注销、信任保存失败可观察性与原授权回归；48 秒，复用缓存 |
| 测试选择器 `tools/test_smart_test.py` | PASS | 55 项，含新 settings 目录间接依赖与失败传播；CI 过滤调整后复验亦通过 |
| Android 主源码 FullDebug 编译 | PASS | 完整编译通过，零编译错误，零废弃引入 |
| 设置界面/功能保真测试套件 | PASS | 105 项设置相关单元测试全部通过（含 SettingsHonestyCopyTest 14、AppearanceSettingsScreenTest 4、NotificationCollectionScreenTest 21、SettingsNavigationTest 2、SettingsStateIntegrityTest 4、SettingsCapabilityUiTest 18、SettingsResponsiveHostRegressionTest 5、SettingsViewModelTest 20、PluginManagementScreenTest 21、PushSettingsScreenTest 13、TemporaryApprovalCopyTest 1） |
| 会话与基础 UI 组件回归 (`:conversation-ui`) | PASS | 751 项全部通过（含 StateViewsTest、AttachmentComposerTest、ExecutionProcessComposeTest 等 5 项历史前序文案断言校准） |
| 内核/通知策略模块回归 | PASS | platform-kernel 100、notification-host 16、notification-control 8、policy-engine 18，共 142 项无失败 |
| 仓库 `npm run typecheck` | PASS | TypeScript 强类型校验全部通过 |
| 最终 PR / CI 工作流 | READY | 已在 `.github/workflows/oai-core.yml` 完整补齐新设置套件与相关通知持久化测试；等待发起 PR 触发 CI |

本地运行日志保存在工作树忽略目录 `.toolchains/settings-validation/`；最终报告将补充可复核的测试计数和 CI 链接。中间编译成功不能代表最终通过。

## 专用模拟器与旧版本基线

- 专用 AVD：`settings_baseline_api36`，Android 16 / API 36，x86_64，1080×1920，KVM。
- 独立 ADB 服务端口 `5038`，设备 `127.0.0.1:5589`（console 5588）；不操作共享 ADB 的个人设备。
- 旧版安装包来源：[成功 CI](https://github.com/1rua/open-android-intelligence/actions/runs/41885869687)，提交 `2ad29b9ceece53e8a86a307c9f73a569b3ee8c55`，artifact `app-full-debug`，ID `13351891815`。
- 工件 ZIP SHA-256：`ec471d86cc8ce496db8dd0b7548da1931588bd8db2d011d5e47bec82479ae3b0`；与 GitHub digest 一致。
- APK SHA-256：`4b220855126db94eee245be43bbb8be395130e3fcf767dff07550302b2f4869b`；内部更新清单 commit 与基线一致。
- APK 签名校验通过，证书 SHA-256 `9420b1917a285377dd267f04e78347dd73bea00a86fc904267704173599bc4d7`。最终包须核对兼容签名后升级安装，不清数据规避签名。
- 旧版 12 组脱敏 PNG/真实 android layout JSON 位于 `docs/test-evidence/2026-11-05-settings/baseline/`（项目原规则忽略，需选择性提交脱敏证据或在 PR 附可审查文件）。没有使用模拟 JSON 冒充设备布局。

## 真实环境限制

| 项目 | 状态 | 原因与后续 |
| --- | --- | --- |
| 旧版设置离线页面基线 | PARTIAL | 已采集首页/消息、外观、连接、授权、插件、屏幕、推送、通知、更新等。不是新版本验收 |
| 最终 APK 设备运行 | PENDING | 最终 CI 包完成后升级安装与执行 |
| 账号/Core/Agent 切换、远程能力/投递 | BLOCKED | 专用 AVD 未登录，缺专用已授权 Core/Agent/接收端测试身份；未读取个人凭据或创建测试端点 |
| 屏幕插件/无障碍/系统特殊授权 | PENDING/BLOCKED | 基线未授予，需最终版真实系统交互；不得用 ADB grant 冒充用户同意 |
| UI XML | BLOCKED | API 36 的 uiautomator dump 返回 137/Killed；已用真实 android layout JSON，未伪造 XML |
| 基线安全策略/审计子页 | BLOCKED | 验收代理两次点击被权限系统拒绝，未绕过或改用其他 Agent 执行；只保留入口证据。需要用户通过权限系统允许后再试 |
| 实体设备/OEM 气泡/硬件振动 | BLOCKED | 没有授权实体手机，模拟器不能替代硬件行为验收 |
| Terra 专属验收 | BLOCKED | 本环境未提供 Terra 模型/类型；不将其他 Agent 结果冒充 Terra |

所有测试数据仅限专用合成数据。未登录不等于无网络，应用更新页按原行为联系公开 GitHub 发布接口。
