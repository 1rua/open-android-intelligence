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
[控制契约](../contracts/oai-control-rpc-v1.md)。PR #190 与关联 issue #187/#188/#189/#192/#194/#195 持续跟踪。
`VERIFIED` 只证明列明提交、环境、命令和断言；`BLOCKED` 表示已知环境限制；
`UNVERIFIED` 表示缺少证据。实现、自动化与真实环境验收分别记录。

## 范围与当前检查点

正常多 Host、DSH/Codex/ACP 工作区和会话手动切换必须保留，使用独立连接与代次、旧流取消排空及迟到结果拒绝。
本项目不提供 `remote_agent_delegate` 或其他后台跨工作区委派，#191 已按范围取消；
历史 target lease、公开委派目标绑定观察不再是实现或发布前置。MCP 仅公开手机 `android_*`，
目标限于 OAI Virtual Host 的 `phone-capabilities`，不新增中心服务或监听。

以下首轮历史证据的生产代码为 `7f17831240be467eaaac3fa66cf5550d81cdddab`，迁移基线为
`12094e684e4bd6d4270513d54b6ef35437536552`；已通过远端门禁的 head 为 `69a2a2f3014629c51a7e2ec762f69e1dd5aadda0`。
其间 `40531634` 是文档检查点，`9f8d388` 修两项 CI 工具，`69a2a2f` 修三项测试辅助文件；生产业务逻辑不变。
本索引的证据文档提交 SHA 由提交后回读登记，不在正文自指。同 head 的 CI 15/15 jobs 与 APK 构建成功。
此前 `cd4374f` 的 Full 失败与旧候选 APK 证据不计入本次最终结果。下表只登记实际运行结果；真实 Host、设备、模拟器、网络和发布结论继续单独分层。
首轮证据登记时间为 `2026-11-15 12:05:59 +08:00`（Asia/Shanghai）；后续补充失败归因与已下载 APK，不冒充各流水线的起止时间。

## 2026-11-15 协议互通与安装身份补充

本轮最新生产源码为 `8a297ab9f9ca0d9d32ccad2c4c6cbb32df89c038`，关联 #195。presence 与 Client 常驻控制面、安装身份/名称/本机过滤、官方桌面 `$events`/会话投影、RTC 回调清理及固定兼容来源见[互通记录](2026-11-15-phone-virtual-host-interop.zh-CN.md)。本节仅更新本轮证据；下文旧 SHA 的远端 CI 和 APK 保留历史归属。

| 本轮门禁 | 实际结果 |
| --- | --- |
| Fast 与完整 PR | VERIFIED：两者均对上述源码使用 `--since 7215a9d9e5aa281e4182cc5e0ad9b5b91d0e125b` 完整退出 0；Gateway 35 类/307 项全过；App Full 131 类/1453 项、1450 通过、0 失败/错误、3 项预期渠道条件跳过 |
| Play 必要回归 | VERIFIED：5 类/26 项、18 通过、0 失败/错误、8 项预期渠道条件跳过；设备名 2/2、顶栏 5/5，Full 跳过的三项受限渠道负向用例在 Play 已实际通过。两渠道重复用例不合计为唯一总数 |
| 剩余验收 | UNVERIFIED：本轮远端 CI/APK、真实账号/电脑 Host、物理设备、LAN/P2P/TURN 与用户 `dotnet-experimental` 服务；缺少 fatal 堆栈，不能称真实闪退根因已确认或已解决。旧匿名设备丢失私钥不能安全认领 |

## 2026-11-15 界面与认证补充

本轮冻结源码为 `ea0047e8b708365815557a39006b25998de7df76`，关联 #194；本节更新本轮状态，下文 `7f1783`/`69a2a2f` 的构建、APK 和远端 CI 保留为各自历史证据，不能当作本轮新源码的远端结果。恢复范围与逐文件结论见[界面审计](../reports/2026-11-15-remote-ui-baseline-recovery.zh-CN.md)，OAuth/扫码协议、安全修复和真实环境限制见[认证说明](2026-11-15-remote-login-entry-authentication.zh-CN.md)。

| 本轮证据 | 实际结果与归属 |
| --- | --- |
| `npm run test:fast -- --since 2a011240ec0c17c5038778566cc4845caab323bb` | VERIFIED：`ea0047e8` 完整退出 0；精确 CI 规划 `python3 -m pytest -q -p no:cacheprovider tools/test_ci_planning.py` 104/104 |
| `npm run test:pr -- --since 2a011240ec0c17c5038778566cc4845caab323bb` | VERIFIED：`ea0047e8` 完整退出 0，包含 24 模块与 App Full 130 类/1451 项，0 失败、0 错误、3 跳过；Gateway 29 类/283 项全部通过，覆盖认证树 `a496acbf52144e404a7977334c48e69ff7a58e30` 的 8 类/28 项 |
| Play 补验 | VERIFIED：同源码 6 类/35 项，27 通过、0 失败、0 错误、8 跳过；Full 条件跳过的三项 Play 负向场景在此实际执行通过 |
| 界面与渲染 | VERIFIED：`4fac21c644edd4eb19e0c1d0019a92d7bff91263` 定向 16/16 通过，官方/自部署浅深四图由主 Agent 目视，精确字段及 tonal 按钮像素断言通过；到 `ea0047e8` 仅新增 CI 清单两行，无界面源码变化 |
| 独立审查与真实环境 | VERIFIED：本轮界面与认证独立源码审查闭合；UNVERIFIED：真实 OAuth 成功、扫码/电脑确认、动态壁纸取色、真实 Host/设备/网络。未启动模拟器或 ADB，PR #190 保持 Draft |

PR 首次运行受 Maven HTTP 429 阻断后中止（退出 130）；复用已有 Robolectric jar 与离线 init 后按同一命令完整重跑退出 0，不能把首次失败隐藏为通过。完整测试日志保留在临时目录，不入库；四图保留为构建报告产物。

本轮 Full 的 3 项跳过是 `BuiltInCapabilitiesTest.aChannelWithoutPluginRuntimesReportsEveryCapabilityAsUnavailableWithAReason`、`SmsSendPluginHostTest.aRestrictedChannelCannotRegisterOrEnableAnInstalledSignedSmsPackage`、`SmsSendOutcomeHostBusTest.aRestrictedChannelDeniesRemoteSmsBeforeConfirmationOrSystemSend`，均为 Full 不适用的受限渠道负向路径。Play 的 8 项需 `ALLOW_RUNTIME_PLUGINS`，该渠道不装配插件运行时，按条件跳过，不计作通过。Full/Play 合并 Manifest 的相机、前置、对焦和闪光均 `required=false`。这些本地证据不替代新提交的远端 CI、APK 或真实链路验收。

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
| 旧依赖退役 / 18 | 固定来源、依赖许可、规划器与 CI 消费检查纳入 Fast/Full；摘要门禁与夹具修复后远端 15/15 jobs 成功 | VERIFIED：本地与远端门禁 |
| 功能保真 / 19 | 默认助理、附件、审批、短信发送/定时、通知/短信主动推送、本地镜像与导出纳入两 App 变体回归 | VERIFIED：以本地计数为限；真实链路另验 |
| 证据分层 / 20 | 本索引区分生产代码、CI 门禁和证据文档提交，并保留旧失败、新运行与本地证据的各自归属 | VERIFIED：文档证据分层；真实环境未闭合 |

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
| APK、Lint 与 AndroidTest 编译 | `7f1783` 的 `lintFullDebug` 成功且 0 errors；`:app:compileFullDebugAndroidTestKotlin` 成功，19 秒，26 executed/303 up-to-date；`69a2a2f` APK run `43677436680` 已下载并完成包内容、签名与插件核验 | VERIFIED：各 SHA 的构建与核验分别登记 |
| 独立审查与远端 CI | 原基线至 `7f1783` 的源码 finding、`9f8d388` 门禁和 `69a2a2f` 三文件夹具修复独立审查均闭合；CI `43677440869` 同 head 15/15 jobs SUCCESS | VERIFIED：含 App Regressions、Play Variant、CI Gate |

执行环境复用 Node 24.18.0、JDK 21、Android SDK 与现有 Gradle 缓存。
Full 的 App 两变体任务本轮实际执行；其 Gradle 调用为 475 actionable tasks：42 executed、8 from cache、425 up-to-date。
模块任务复用现有缓存，不把缓存 XML 计作当轮新增执行。AndroidTest 编译的 26 executed/303 up-to-date 只证明编译，不证明设备执行。
完整与增量 CI 均使用当前 checkout；原工具互通前先由 Gradle 核对当前源码，不允许缺产物时跳过而返回成功。

Full 的 3 项跳过来自 `BuiltInCapabilitiesTest`、`SmsSendPluginHostTest`、`SmsSendOutcomeHostBusTest` 各 1 项，
均为受限渠道负向路径在 Full 渠道的条件跳过。Play 的 50 项跳过按 XML 为：`BuiltInCapabilitiesTest` 4、
`ClientCapabilityAuthorizationTest` 15、`LegacyOfficialCopyIsNeverLoadedTest` 2、`NotificationScopeOwnershipTest` 2、
`SmsBackgroundConsentTest` 3、`SmsSendPluginHostTest` 2、`ProductionPluginHostTest` 20、`SmsSendOutcomeHostBusTest` 2；
对应插件运行时/渠道门控假设，不计作通过。Play 两项签名安装与 RemoteBus 短信拒绝负向测试实际执行，断言不触发本机确认或系统发送。

最终 [APK run 43677436680](https://github.com/1rua/open-android-intelligence/actions/runs/43677436680) 的 metadata commit 为 `69a2a2f3014629c51a7e2ec762f69e1dd5aadda0`；
artifact ZIP 为 43,866,011 bytes，解压 APK 为 118,944,569 bytes，APK SHA-256 为 `b456cd1c69cef798d3e2dcf144bd86b679f25e758924fc25f520eb93d249393f`。
包名 `com.openandroidintelligence.mobile`，版本 `2.2.1-dev`/11951，minSdk 31、targetSdk 35；ABI 为 arm64-v8a、armeabi-v7a、x86、x86_64；同 run 6 个 signed `.alp` 逐字节核验通过，
manifest 的 telephony `required=false`、签名核验通过，证书 SHA-256 为 `9420b1917a285377dd267f04e78347dd73bea00a86fc904267704173599bc4d7`。
这些证据属于 `69a2a2f` 的实际构建，不构成二进制可重现构建证明。

旧 [Remote CI run 43673476871](https://github.com/1rua/open-android-intelligence/actions/runs/43673476871) 的 App 1437 项/0 失败/3 跳过且 BUILD SUCCESSFUL，
摘要失败源于遗漏两项仅 Play 负向测试在 Full 的条件跳过，并非性能超时。`9f8d388` 补精确三项类名/方法名允许清单及任务/结果归属校验，
缺少、多出或不匹配仍拒绝、性能门槛不变；209 项 pytest 通过，原 XML/profile/metrics 不改的 v3 重算 summary、measured、class selection 均 passed，unexpected skip 为空。
`69a2a2f` 修复测试夹具 `Date.now()` 命名碰撞：两项文件系统回归先复现失败，改用 `mkdtemp` 与唯一回收路径后，插件 89 项、typecheck 和普通环境 Fast 实际通过；独立审查无问题。
最终 [CI run 43677440869](https://github.com/1rua/open-android-intelligence/actions/runs/43677440869) 与
[APK run 43677436680](https://github.com/1rua/open-android-intelligence/actions/runs/43677436680) 均以 `69a2a2f` completed/SUCCESS；北京时间 `2026-11-15 12:43:31 +08:00` 回读确认，前者 15/15 jobs 成功，旧摘要及夹具问题已闭合。
实现、本地自动化与远端 CI 已验；真实环境仍待验收。PR #190 保持 Draft，未转正式或合并；证据文档后续提交不改变已验证业务源码。

## 必须保留的行为边界

- P1 的传输恢复不等于业务重放：发送结果未知时保留最小未知事实；附件待提交与已知发送结果分别恢复，不自动重发原消息。
- Harness/ACP 的原生 `session/cancel` 仅表示整个会话取消，不具备手机 `request.cancel` 的按请求原子核对语义；不能据此宣称精确取消原请求或已停止副作用。
- Push 首次取件须经手机本机确认，将实际认证收件主体与精确目标绑定；同账号、公开引用及首次来访均不产生授权。

## 真实环境边界

| 层级 | 当前状态与所需证据 |
| --- | --- |
| 本地隔离自动化与远端 CI | VERIFIED：`7f1783` 本地 Full/Lint/AndroidTest/原工具 probe 与 `69a2a2f` 门禁 Fast、远端 15/15 jobs；各 SHA 分别列明 |
| 真实 Host / 后端 | UNVERIFIED：待用户提供专用环境并登录，核对 DSH 插件加载、Harness/Codex/ACP 数据面、原生 MCP 挂载与双向握手 |
| 模拟器 | UNVERIFIED：待用户确认启动并手动登录；APK 装配不代替系统生命周期与真实连接验收 |
| 物理设备 | BLOCKED：本轮不做真机；权限、焦点、锁屏、overlay、急停、SoM 像素及 Android/API 差异需专机验收 |
| 网络与发布 | UNVERIFIED：LAN/P2P/TURN/Relay、网络切换、实际服务/Host/APK 版本、干净安装与分发完整性另验 |

不连接日常 Host、账号或会话，不将隔离替身、18 字节测试 artifact、源码检查、APK 编译或单个绿色任务扩大为真实链路通过。
P0–P5 及 REM-01–20 的完整验收仍 pending；PR 合并、worktree 清理与 issue 收尾由用户操作。

## 2026-11-15 会话链路修复补充

关联 #196，当前业务源码为 `8d083fae4ab9b129750ef1ebbc68c14bf8fbb502`；五项反馈的根因、先红后绿证据与范围见[会话链路修复记录](2026-11-15-remote-session-flow-repair.zh-CN.md)。同源码 Fast、PR 与 Play 定向验证已通过；本轮远端 CI、新 APK 和真实设备复测仍待推送及验收，不能沿用上文旧 SHA 的绿色结果。同轮 STEER 唯一性仍为期望 2 条、实际 3 条，安全回归通过不等于功能闭合；用户可见性策略待确认，保留正文，PR #190 继续 Draft。
