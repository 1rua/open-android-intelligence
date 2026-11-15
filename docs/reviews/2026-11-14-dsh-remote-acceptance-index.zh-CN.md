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

最终候选 SHA 与同一 HEAD 的实际执行结果在下表登记；尚未生成的提交、通过计数和设备结论保持未验收。
若最后提交仅更新证据文档，分别列明被测代码 SHA 与证据文档 SHA，说明两者之间的文件差异。
此前各切片通过不能相加为最终候选通过。

## 实现与自动化入口

下表记录仓内实现及可运行验收入口；“入口已接入”不等于测试已经在最终提交执行。

| 范围 / REM | 当前实现与自动化入口 | 最终候选状态 |
| --- | --- | --- |
| 账号、双身份、传输 / 01–03 | gateway-client 的账号、角色隔离、固定 Server、Noise、RPC 生命周期回归；固定向量与许可来源门禁 | UNVERIFIED：需最终同 HEAD 执行；真实路径另验 |
| 端点、会话、模型与聊天 / 04–07 | conversation-domain/data/ui 与 App RemoteRuntime、组织、镜像、模型、手动切换回归 | UNVERIFIED：需最终同 HEAD 执行 |
| 附件与离线媒体 / 08 | 媒体原文四项真实文件系统探针、conversation-data 媒体回归、App RemoteHistoryMediaCacheTest；待整合的未知发送持久恢复由 RemoteAttachmentMessageRecoveryTest、EncryptedRemoteAttachmentRecoveryTest 回归 | UNVERIFIED：需最终同 HEAD 执行；真实后端另验 |
| 手机 Session V3、总线与请求 / 09–10、16 | PhoneVirtualHostProbe 运行未修改 remote_* 原工具至真实 Kotlin facade/Bus；平台执行器使用隔离替身；控制请求及取消状态机回归 | UNVERIFIED：需最终同 HEAD 执行；真实手机控制另验 |
| 本机权限与屏幕 / 11–15 | Kotlin 原生内核、SoM、收敛、overlay、急停及后台授权回归 | UNVERIFIED：自动化与物理设备分验 |
| Bridge / MCP / 17 | 官方 SDK Stdio/进程内桥单元回归与原工具互通 | UNVERIFIED：需最终同 HEAD 执行；真实插件挂载另验 |
| 旧依赖退役 / 18 | 生产、构建、工具链、CI 消费退出检查；固定来源门禁及规划回归 | UNVERIFIED：最终协议切片整合后复核 |
| 功能保真 / 19 | 默认助理、附件、审批、短信发送/定时、通知/短信主动推送、本地镜像与导出回归 | UNVERIFIED：按最终提交逐项记录，不能只凭编译宣称保留 |
| 证据分层 / 20 | 本索引与固定来源、独立审查、同 HEAD Fast/PR/Full 结果 | UNVERIFIED：最终验收未闭合 |

媒体来源事实与边界见[媒体来源说明](2026-11-14-remote-history-media-source.zh-CN.md)，
原生依赖和分发许可见[依赖说明](2026-11-14-dsh-remote-native-dependencies.zh-CN.md)。
固定上游为 `ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，本仓库不修改原文、不维护上游分叉。

## 最终门禁与证据登记

| 命令 / 通道 | 必需断言 | 当前证据 |
| --- | --- | --- |
| `npm run lock:check`、`node --test tools/check-remote-sources.test.mjs` | 原文、全部媒体清单、SHA-256、Git blob、协议向量与许可完整；篡改、缺文件、漏登记均拒绝 | UNVERIFIED：本门禁切片尚待实际执行 |
| `npm run test:remote` | 固定 ACP 三项观察、媒体四项原文/fs 回归、互通准备与失败传播；不使用真实账号 | UNVERIFIED：本门禁切片尚待实际执行 |
| `npm run test:phone-mcp:unit` | 官方 MCP SDK、stdio 与进程内桥；只证明 Node 单元回归 | UNVERIFIED：本门禁切片尚待实际执行 |
| `npm run test:phone-mcp` | 单元回归与当前 Kotlin 编译后原工具互通；缺类/依赖、编译失败、JVM 退出或超时均失败 | UNVERIFIED：本门禁切片尚待实际执行 |
| `npm run typecheck`、Python CI/智能测试规划回归 | 仅排除固定上游 TS 原文；探针、门禁、来源元数据与实际 CI 顺序受检 | UNVERIFIED：本门禁切片尚待实际执行 |
| `npm run test:fast` | 关联轻量测试与来源门禁；手机只跑 Node 单元回归 | UNVERIFIED：最终整合后执行 |
| `npm run test:pr` / Remote CI | 关联 Android 回归、当前源码 JVM 编译与原工具互通、媒体探针 | UNVERIFIED：最终整合后执行 |
| `npm run test:full` / 完整 CI | 全模块、两个 App 变体及同 checkout 原工具互通 | UNVERIFIED：最终发布前执行 |
| APK、Lint 与 AndroidTest 编译 | 分别记录 APK 装配、静态检查、仪器测试源码编译；编译不代表模拟器或设备执行 | UNVERIFIED：需最终同 HEAD 单独登记 |
| 独立 Standards / Spec 审查 | 固定最终基准与 HEAD，核对关联 issue；成立问题修复后定点复审 | UNVERIFIED：等待最终代码与实际测试 |

执行环境复用 Node 24.18.0、JDK 21、Android SDK 与现有 Gradle 缓存。
每次登记需写 OAI SHA、命令、具体测试计数与失败/错误/跳过数、环境、实际断言及结果。
Gradle 的 `UP-TO-DATE` / `FROM-CACHE` 必须与当轮重新执行测试区分，不把陈旧 XML 计作当轮新增结果。
完整与增量 CI 均使用当前 checkout；原工具互通前先由 Gradle 核对当前源码，不允许缺产物时跳过而返回成功。

## 必须保留的行为边界

- P1 的传输恢复不等于业务重放：发送结果未知时保留最小未知事实；附件待提交与已知发送结果分别恢复，不自动重发原消息。
- Harness/ACP 的原生 `session/cancel` 仅表示整个会话取消，不具备手机 `request.cancel` 的按请求原子核对语义；不能据此宣称精确取消原请求或已停止副作用。
- Push 首次取件须经手机本机确认，将实际认证收件主体与精确目标绑定；同账号、公开引用及首次来访均不产生授权。

## 真实环境边界

| 层级 | 当前状态与所需证据 |
| --- | --- |
| 本地隔离自动化 | UNVERIFIED：最终整合 HEAD 的真实执行待补；旧候选证据仅证明原 SHA 的局部断言 |
| 真实 Host / 后端 | BLOCKED：用户稍后提供专用环境；需要固定 DSH 插件加载、Harness/Codex/ACP 数据面、原生 MCP 挂载与双向握手 |
| 模拟器 | UNVERIFIED：本轮不运行模拟器；APK 装配与系统生命周期须独立验收 |
| 物理设备 | BLOCKED：本轮不做真机；权限、焦点、锁屏、overlay、急停、SoM 像素及 Android/API 差异需专机验收 |
| 网络与发布 | UNVERIFIED：LAN/P2P/TURN/Relay、网络切换、实际服务/Host/APK 版本、干净安装与分发完整性另验 |

不连接日常 Host、账号或会话，不将隔离替身、18 字节测试 artifact、源码检查、APK 编译或单个绿色任务扩大为真实链路通过。
P0–P5 及 REM-01–20 的完整验收仍 pending；PR 合并、worktree 清理与 issue 收尾由用户操作。
