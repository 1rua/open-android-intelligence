---
status: in_progress
acceptance_status: pending
date: 2026-11-15
tracking_issue: 187
baseline_commit: 12094e684e4bd6d4270513d54b6ef35437536552
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
---

# DSH Remote 迁移验收索引

本索引对应[总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)、
[实施计划](../plans/2026-11-14-dsh-remote-migration-plan.zh-CN.md)与
[控制契约](../contracts/oai-control-rpc-v1.md)。PR #190 与 #187/#188/#189/#192 持续跟踪。
`VERIFIED` 只证明列明提交、环境、命令和断言；`BLOCKED` 表示已知环境限制；
`UNVERIFIED` 表示缺少证据。实现、自动化与真实环境验收分别记录。

## 范围与当前检查点

正常多 Host、DSH/Codex/ACP 工作区和会话手动切换必须保留，使用独立连接与代次、旧流取消排空及迟到结果拒绝。
本项目不提供 `remote_agent_delegate` 或其他后台跨工作区委派，#191 已按范围取消；
历史 target lease、公开委派目标绑定观察不再是实现或发布前置。MCP 仅公开手机 `android_*`，
目标限于 OAI Virtual Host 的 `phone-capabilities`，不新增中心服务或监听。

最终被测代码固定为 `7f17831240be467eaaac3fa66cf5550d81cdddab`，迁移基线为
`12094e684e4bd6d4270513d54b6ef35437536552`。本次证据提交只更新本索引；代码、依赖和测试清单仍对应上述被测 SHA。
此前 `cd4374f` 的 Full 失败与旧候选 APK 证据不计入本次最终结果。下表只登记实际运行结果；真实 Host、设备、模拟器、网络和发布结论继续单独分层。
证据登记时间为 `2026-11-15 12:05:59 +08:00`（Asia/Shanghai）；这是本地证据汇总时间，不冒充各流水线的起止时间。

## 实现与自动化入口

下表记录仓内实现及可运行验收入口；“入口已接入”不等于测试已经在最终提交执行。

| 范围 / REM | 当前实现与自动化入口 | 最终候选状态 |
| --- | --- | --- |
| 账号、双身份、传输 / 01–03 | `npm run test:full` 同时执行固定来源锁定、Remote Server、MCP 与原工具互通入口；`lock:check` 报告 58 份原文与许可完整 | VERIFIED：本地自动化；真实路径另验 |
| 端点、会话、模型与聊天 / 04–07 | conversation-domain/data/ui、RemoteRuntime、组织、镜像、模型和手动切换纳入 Full 128 类 App 回归 | VERIFIED：本地自动化；真实 Host 另验 |
| 附件与离线媒体 / 08 | conversation-data 媒体回归、历史媒体缓存及两项 Remote 附件恢复测试纳入同一 Full；未知结果保持不重放边界 | VERIFIED：本地自动化；真实后端另验 |
| 手机 Session V3、总线与请求 / 09–10、16 | Gateway、控制端口、取消状态机和手机 MCP 互通纳入 Full；本地原工具 probe 返回 `nativeTools:PASS`、3 条记录及 18 字节测试 artifact 下载 | VERIFIED：本地自动化；真实手机控制另验 |
| 本机权限与屏幕 / 11–15 | Kotlin 原生内核、SoM、overlay、急停及后台授权由模块 Full 回归覆盖 | VERIFIED：自动化；物理设备另验 |
| Bridge / MCP / 17 | 官方 SDK、Stdio/进程内桥和当前源码互通由 Full 流程执行 | VERIFIED：本地自动化；真实插件挂载另验 |
| 旧依赖退役 / 18 | 固定来源、依赖许可、规划器与 CI 消费检查纳入 Fast/Full；未授权远端 CI 日志保持单独未验证 | VERIFIED：本地门禁；远端复核另验 |
| 功能保真 / 19 | 默认助理、附件、审批、短信发送/定时、通知/短信主动推送、本地镜像与导出纳入两 App 变体回归 | VERIFIED：以本地计数为限；真实链路另验 |
| 证据分层 / 20 | 本索引区分最终代码的 Fast/Full 与上一候选 PR，并记录编译、APK 和源码审查；远端 CI 失败日志无法认证时不扩大结论 | VERIFIED：文档证据分层；真实环境未闭合 |

媒体来源事实与边界见[媒体来源说明](2026-11-14-remote-history-media-source.zh-CN.md)，
原生依赖和分发许可见[依赖说明](2026-11-14-dsh-remote-native-dependencies.zh-CN.md)。
固定上游为 `ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，本仓库不修改原文、不维护上游分叉。

## 最终门禁与证据登记

| 命令 / 通道 | 必需断言 | 当前证据 |
| --- | --- | --- |
| `npm run lock:check`、固定来源检查 | `7f1783` 日志报告 58 份原文与许可完整；Fast 另执行 Python 门禁 280 项通过 | VERIFIED：本地日志 |
| `npm run test:remote`、Remote Server | Full 日志确认 Remote 9 项全通过、0 失败/0 跳过；Remote Server 命令成功；插件工具 87 项通过 | VERIFIED：本地实际执行 |
| `npm run test:phone-mcp` | 官方 SDK/桥 4 项全通过；当前源码 JVM 编译后原工具返回 `nativeTools:PASS`；Python 规划器等门禁 280 项通过 | VERIFIED：本地自动化；真实 Host 另验 |
| `npm run test:fast -- --since cd4374fcd71774d1c877117ad316b33d228580d7` | 退出码 0；58 份来源、源码扫描、Python 280 项和 `noVpnSurfaceCheck` 通过 | VERIFIED：本地实际执行 |
| 上一候选 `cd4374f` 的 `npm run test:pr -- --since effa5db64f6ca69733068a69f8ee839b6a2e28cb` | 退出码 0；App 128 类/1435 项（1434 通过、1 项预期 Play 专属跳过），Gateway 24 类/265 项全通过；最终 `7f1783` 以以下 Full 覆盖 | VERIFIED：仅上一候选，不冒充最终 PR 重跑 |
| `npm run test:full -- --since 12094e684e4bd6d4270513d54b6ef35437536552` | BUILD SUCCESSFUL，10 分 40 秒；Full 128 类/1437 项/0 失败/0 错误/3 跳过，Play 128 类/1437 项/0 失败/0 错误/50 跳过 | VERIFIED：本地全量回归 |
| APK、Lint 与 AndroidTest 编译 | `lintFullDebug` 成功且 0 errors；`:app:compileFullDebugAndroidTestKotlin` 成功，19 秒，26 executed/303 up-to-date；APK run `43673470976` 成功，artifact 43,865,982 bytes，未取得 7f 本地 SHA | Lint/AndroidTest VERIFIED；APK 构建 VERIFIED、哈希 UNVERIFIED |
| 独立审查与远端 CI | 基线 `12094e68…`→`dafa…`、后续窄差分至 `effa…` 与 `7f1783…` 的 finding 均关闭；Remote CI run `43673476871` 观察到 App Regressions/CI Gate failure，其余 jobs 成功，但失败日志因未认证无法读取和归因 | 审查 VERIFIED；远端失败原因 UNVERIFIED/UNATTRIBUTED |

执行环境复用 Node 24.18.0、JDK 21、Android SDK 与现有 Gradle 缓存。
Full 的 App 两变体任务本轮实际执行；其 Gradle 调用为 475 actionable tasks：42 executed、8 from cache、425 up-to-date。
模块任务复用现有缓存，不把缓存 XML 计作当轮新增执行。AndroidTest 编译的 26 executed/303 up-to-date 只证明编译，不证明设备执行。
完整与增量 CI 均使用当前 checkout；原工具互通前先由 Gradle 核对当前源码，不允许缺产物时跳过而返回成功。

Full 的 3 项跳过来自 `BuiltInCapabilitiesTest`、`SmsSendPluginHostTest`、`SmsSendOutcomeHostBusTest` 各 1 项，
均为受限渠道负向路径在 Full 渠道的条件跳过。Play 的 50 项跳过按 XML 为：`BuiltInCapabilitiesTest` 4、
`ClientCapabilityAuthorizationTest` 15、`LegacyOfficialCopyIsNeverLoadedTest` 2、`NotificationScopeOwnershipTest` 2、
`SmsBackgroundConsentTest` 3、`SmsSendPluginHostTest` 2、`ProductionPluginHostTest` 20、`SmsSendOutcomeHostBusTest` 2；
对应插件运行时/渠道门控假设，不计作通过。Play 两项签名安装与 RemoteBus 短信拒绝负向测试实际执行，断言不触发本机确认或系统发送。

同一代码的 [APK run 43673470976](https://github.com/1rua/open-android-intelligence/actions/runs/43673470976) 成功，
`app-full-debug` artifact 大小为 43,865,982 bytes；下载未完成，最终 APK SHA-256、包内容与签名本地复核均 UNVERIFIED，不引用旧 `cd4374f` APK 哈希。
[Remote CI run 43673476871](https://github.com/1rua/open-android-intelligence/actions/runs/43673476871) 的失败状态保留，不能以本地 Full 成功覆盖。
源码独立审查与自动化测试为两类证据；PR #190 保持 Draft，未转正式或合并。

## 必须保留的行为边界

- P1 的传输恢复不等于业务重放：发送结果未知时保留最小未知事实；附件待提交与已知发送结果分别恢复，不自动重发原消息。
- Harness/ACP 的原生 `session/cancel` 仅表示整个会话取消，不具备手机 `request.cancel` 的按请求原子核对语义；不能据此宣称精确取消原请求或已停止副作用。
- Push 首次取件须经手机本机确认，将实际认证收件主体与精确目标绑定；同账号、公开引用及首次来访均不产生授权。

## 真实环境边界

| 层级 | 当前状态与所需证据 |
| --- | --- |
| 本地隔离自动化 | VERIFIED：限上述 `7f1783` 的本地 Fast/Full、Lint、AndroidTest 编译与原工具 probe；上一候选 PR 和远端 CI 分别列明 |
| 真实 Host / 后端 | BLOCKED：用户稍后提供专用环境；需要固定 DSH 插件加载、Harness/Codex/ACP 数据面、原生 MCP 挂载与双向握手 |
| 模拟器 | UNVERIFIED：本轮不运行模拟器；APK 装配与系统生命周期须独立验收 |
| 物理设备 | BLOCKED：本轮不做真机；权限、焦点、锁屏、overlay、急停、SoM 像素及 Android/API 差异需专机验收 |
| 网络与发布 | UNVERIFIED：LAN/P2P/TURN/Relay、网络切换、实际服务/Host/APK 版本、干净安装与分发完整性另验 |

不连接日常 Host、账号或会话，不将隔离替身、18 字节测试 artifact、源码检查、APK 编译或单个绿色任务扩大为真实链路通过。
P0–P5 及 REM-01–20 的完整验收仍 pending；PR 合并、worktree 清理与 issue 收尾由用户操作。
