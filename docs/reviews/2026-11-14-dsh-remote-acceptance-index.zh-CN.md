---
status: in_progress
acceptance_status: pending
date: 2026-11-14
tracking_issue: 187
baseline_commit: 12094e684e4bd6d4270513d54b6ef35437536552
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
---

# DSH Remote 迁移验收台账

本台账对应[实施计划](../plans/2026-11-14-dsh-remote-migration-plan.zh-CN.md)、
[总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)与[控制契约](../contracts/oai-control-rpc-v1.md)。
`VERIFIED` 只适用于已记录命令、版本、场景和证据的具体断言；`BLOCKED` 表示已明确的环境/能力阻塞；
`UNVERIFIED` 表示未取得所需证据。文档入库不提升运行状态。

## 当前范围（2026-11-14）

用户取消本客户端后台跨 workspace Agent 委派；MCP 只公开 android_*，目标仅手机
OAI Virtual Host 的 phone-capabilities。#191 按范围取消关闭，target lease 及公开委派目标绑定不再是
P0/P4/P5 前置。正常多 Host、DSH/Codex/ACP workspace 与对话手动切换完整保留，
必须以独立连接/代际、旧流取消排空及迟到结果拒绝实现并验证。
以下历史证据保留其原 SHA、命令和当时结论；其中后台委派阻塞已被本次范围决定取代，
不能继续作为当前阻塞，也不能把旧探针视为新客户端手动切换验收。

## 当前实现候选与验证边界（2026-11-14 更新）

当前继续跟踪 #187/#188/#189 与实现收口 #192，PR #190 保持 Draft。下列为不同隔离工作区的候选证据，
尚未线性整合到同一最终 SHA，不能累加为整条生产链路通过。旧章节保留其当时提交与结论；后台委派的
历史阻塞已被当前范围取消，不能再阻塞本项目。

| 候选 | 已验证范围 | 未验证或后续工作 |
| --- | --- | --- |
| Runtime `1953344f7a21a02813e0461316d083dc3118fdf8` | 原入口 focused 17 项通过（51 秒）：固定 Server 5、Control WS/Noise 3、HTTP 3、RPC 生命周期 4、凭据重启 2；失败/错误/跳过均 0。原 `8d546dc5` 的 15 项为前轮证据 | 新两项 P2 定点确认待进行；真实 Host/手机及 LAN/P2P/TURN/独立 Relay 部署未验 |
| UI `0a3672e7814b2ab56be60433916c0af384a628e1` | 前轮 App 211 项、conversation-data 13 项通过；本候选 App 编译与 RemoteRuntime 4 项最小复验通过（3 分 40 秒，4 项失败/错误/跳过均 0） | 独立 fresh 审查新增 5 项问题待修，不因上述最小复验通过而关闭；多候选整合后按新 SHA 回归；推送、定时、插件管理与旧 Gateway 生产消费尚待迁移 |
| 手机 `931e16d53291fac05936ac3452142e368be70611` | 候选回归 Overlay 3、NativeKernel 3、Bus 7 及 App 编译通过，1 分 50 秒；失败/错误/跳过均 0 | API 31–33 SoM 尚未交付；API 34+ 实现未做真机像素/焦点/锁屏验收 |
| MCP/原生工具 | 最新 MCP 官方客户端/stdio 2 项通过；固定原生工具至 Kotlin facade 的发现、建任务、发送、读回及 18 字节 artifact 下载通过 | 18 字节测试产物不证明真实截图/SoM 下载；实际 DSH 插件加载、原生 MCP 挂载与设备能力未验 |

Runtime `1953344f` 在前轮三项修复上继续关闭两处复审缺陷：旧 onClosed、主动 close 和失败收尾的
代次检查与状态写入统一放在同一 selectionLock；回归控制真实旧 service.close 回调的状态 setter 停点，
与新选择发布 CONNECTED 形成合法交错。Host 收到 Offer/ICE 后本地 RTC_UNAVAILABLE 或确定的候选失败只
禁用该隧道的 RTC 候选，保留 Relay 选择与原握手等待，不虚构 signal.error；固定原 Server 的 RawClient
Offer→JVM Host 原生 RTC 不可用→Relay→Noise→RPC 场景通过。两项尚待原评审定点确认，不先写成审查关闭。

手机敏感输出交付 P1 修复候选 `cb626070` 的 ContentDelivery 3、Bus 7 及 App 编译通过，10 项失败/错误/跳过
均 0；独立定点确认已关闭该 P1。它属于手机候选，不与 Runtime 或前轮 931e16d5 的计数合并。

Runtime 的三项独立审查问题已形成失败回归后修复：旧非法版本 `oai-android` 的固定 Server 负例
不转发 Relay 选择并会替换旧 Client；现在 hello 与注册 descriptor 共用从本项目 version.properties
生成的真实 `2.2.1+oai.android`。该值表示 OAI 自身版本，不冒充 DSH 版本。独立 Node 版本策略 1 项通过。
旧选择授权迟到曾在真实 AccountRuntime/HTTP 门控场景再次打开 WS；现取消旧 connect，并在每次等待恢复后、
建 WS 和发布身份/状态前核对同一选择代次。坏 Relay counter 曾使另一个 Host Client 超时；先由隔离 WS
复现，再由固定未改 Server 转发 counter=1 的真实 Noise Client 验证只终止该 connectionId。

首次真实 Server 红色结果同时受到版本声明与 JVM 缺少 WebRTC 原生库影响，不能全部归因为 SemVer。
原生加载的 LinkageError 已转为明确 RTC_UNAVAILABLE，进入双方已协商的 Relay 降级；本地 Relay 通过不证明
原生 RTC 或四种网络路径可用。固定服务端源码 11 份 SHA 与 MIT 来源核对通过，不维护上游分叉。

手机前轮 NativeScreen 4、Quiet 3、Backend 16 项通过，但该次共 25 项中 Overlay 有 1 项失败；
候选随后定向复验 Overlay 3 与 Kernel/Bus，未重复执行所有前轮测试，因此不能把两轮拼成同 SHA 全量通过。
原始日志留在临时环境，仓库仅保留本台账与可运行回归。测试复用现有 Node 24.18.0/JDK21/Android SDK/Gradle 缓存，
本轮不做真机、不使用日常 Host；短信发送、可靠推送确认、定时与生产旧依赖退役仍是剩余实现范围。

## 环境与证据分层

用户确认稍后提供专用手机与 Host，先不进行真机测试；不占用日常 Host 账号、会话或连接。
当前未提供测试连接方式、Android/API、Host/后端版本与服务 origin。独立实现和自动化继续推进。

| 层级 | 证明范围 | 当前状态 |
| --- | --- | --- |
| 协议/本地自动化 | 固定密码/协议向量、类型化请求、隔离、状态机和故障注入 | UNVERIFIED |
| 真实 Host/后端 | 固定 DSH 插件加载、双向握手、后端数据面与 MCP 原生挂载 | BLOCKED：专用环境待提供 |
| 模拟器 | APK 装配、系统生命周期与支持场景 | UNVERIFIED |
| 物理设备 | 权限、像素、焦点、锁屏、overlay、急停与网络切换 | BLOCKED：本轮暂不进行真机测试 |
| 发布/部署 | 实际 APK/Host/服务版本、干净安装与线上能力 | UNVERIFIED |

每份新增证据必须记录：REM 编号、阶段、OAI SHA、固定 upstream SHA、APK/Host SHA 或版本、命令、
环境、断言、状态与仓内证据位置；敏感正文、账号、密钥不入库。不能将某层的 VERIFIED 传播到另一层。

## 阶段门禁

| 阶段 | 跟踪 | 必需门禁 | 状态 |
| --- | --- | --- | --- |
| P0 | #188 | 契约增量、Noise/协议向量、依赖许可/ABI | UNVERIFIED |
| P1 | #189 | 双身份、原生通道、最小真实 Session V3 与未改 remote_* 双向链路 | UNVERIFIED；真实环境 BLOCKED |
| P2 | #187 后续子项 | Harness/Codex/ACP 数据面及现有聊天/附件/审批/助理回归 | UNVERIFIED |
| P3 | #187 后续子项 | 最小账本、原生执行、SoM、收敛、overlay、锁屏与敏感确认 | UNVERIFIED；物理设备 BLOCKED |
| P4 | #187 后续子项 | 进程内 Bridge/Stdio MCP、手机双入口、android_* 白名单与任务无误投 | UNVERIFIED；真实 Host BLOCKED |
| P5 | #187 后续子项 | 功能覆盖、旧依赖退出、Fast/PR/Full、独立评审与发布矩阵 | UNVERIFIED |

## REM 需求矩阵

| 需求 | 阶段 | 必需场景与证据 | 状态 |
| --- | --- | --- | --- |
| REM-01 | P1/P2 | 单账号、官方/兼容服务真实能力缺失诊断 | UNVERIFIED |
| REM-02 | P1 | Client/Host 独立身份、凭据、退出和撤销 | UNVERIFIED |
| REM-03 | P0/P1 | Noise 向量、LAN/P2P/TURN/Relay、重连未知写入不重放 | UNVERIFIED |
| REM-04 | P1/P2 | 完整端点隔离、同名原生 Session/消息不串线 | UNVERIFIED |
| REM-05 | P2 | Harness/Codex/各 ACP 实际数据面与能力矩阵 | UNVERIFIED |
| REM-06 | P2 | 当前 Session 模型/effort 确认及旧代际拒绝 | UNVERIFIED |
| REM-07 | P2 | 现有聊天组件/交互保留与连接接线回归 | UNVERIFIED |
| REM-08 | P2/P5 | 分块附件、真实提交、未知发送与离线媒体身份 | UNVERIFIED |
| REM-09 | P1 | 真实最小 Session V3、受限文件面、未改原生工具互通 | UNVERIFIED |
| REM-10 | P0/P1/P3 | 总线单步/独立任务、JSON/CLI 同义解析和请求状态机 | UNVERIFIED |
| REM-11 | P3 | Kotlin 屏幕/WASM 数据分离、共同内核与沙箱授权 | UNVERIFIED |
| REM-12 | P3 | 原生/WebView/Flutter/图像界面树与 SoM，快照绑定不伪造节点 | UNVERIFIED |
| REM-13 | P3 | 400ms 静默/3000ms 截止、事件风暴、执行与收敛分开 | UNVERIFIED |
| REM-14 | P3 | overlay 无焦点冲突/截图泄漏/远程自批准，锁屏与急停 | UNVERIFIED；设备 BLOCKED |
| REM-15 | P1/P3 | 默认手动、范围只读、后台独立、敏感强制确认与撤销 | UNVERIFIED |
| REM-16 | P0/P1/P3 | 原子最小账本、崩溃/取消/交付竞态、旧任务不恢复、status 查询 | UNVERIFIED |
| REM-17 | P0/P4 | Stdio/进程内桥、已有路由无新监听、仅手机 android_* 工具 | UNVERIFIED |
| REM-18 | P5 | 生产/构建/安装/CI/测试/插件工具链退出旧 Core/契约/adapter | UNVERIFIED |
| REM-19 | P2/P3/P5 | 聊天/助理/附件/通知/定时/主动推送/发送/插件/镜像/导出覆盖 | UNVERIFIED |
| REM-20 | P0–P5 | 需求、实现、本地自动化、模拟器、设备、部署证据独立可追溯 | UNVERIFIED |

## 定向测试矩阵

| 组别 | 必需测试 |
| --- | --- |
| 请求与任务 | JSON/CLI 等价、未知字段/Shell 拒绝、总线 owner/request 隔离、多任务 create、显式 task/snapshot 跨工具 |
| 最小账本 | 同 ID 同摘要只回执、改参数/任务冲突、受理/栅栏/端口/结果发布崩溃点、账本损坏安全拒绝 |
| 无历史恢复 | Prompt/短信正文/树/图像/结果正文不持久；重启不回放/续做；旧 task 只查最小回执、resultAvailable=false |
| 取消 | 待确认请求可立即精确取消、迟到取消 A 不误停 B、整任务冻结、MCP 断开仅停等待、未知结果不重放 |
| 屏幕 | 陈旧/跨任务快照、旋转/缩放/窗口变化、SoM 同源、无节点图像、400/3000ms、捕获失败不改执行事实 |
| 本机门禁 | READ/CAPTURE/CONTROL 分开、排队后/派发前/交付前复核、权限撤销、锁屏、急停与 Shizuku 不绕过 |
| overlay | 第三方输入焦点保留、树/截图无确认内容、节点/坐标拒绝命中胶囊；API34 窗口截图与较低 API/MediaProjection 分验 |
| Bridge/MCP | 错误 bearer/Host/Origin、登出/切 origin/卸载失效、无新监听、Stdio stdout 纯协议、重启查原回执 |
| 手动切换 | 正常多 Host/DSH/Codex/ACP workspace/对话切换；独立连接与代际、旧流取消排空、迟到结果拒绝、未知写入不重投 |
| 功能保真 | 默认助理生命周期、附件迟到交接、模型拒绝/切换、推送接收确认、定时恢复、离线媒体、导出与旧服务管理退役 |

## 证据记录

启动时完成需求/计划登记；5 份变更文档的相对链接、3 个 JSON 示例、REM-01～20 与 P0～P5 覆盖及 `git diff --check` 检查通过。该检查只证明文档结构，不证明运行行为。

初始提交为 `8d2bde75`，Draft PR #190；#187/#188/#189 已回帖认领。尚无新增运行验证。后续记录必须按具体提交和场景追加，不能把待执行命令记为通过。


### P0 公开目标绑定隔离探针

2026-11-14 使用 Node 24.18.0 执行：

```sh
node --experimental-vm-modules --test tools/remote/target-binding.test.mjs tools/remote/projection-scope.test.mjs
```

11 项通过（目标绑定 8 项、原始 ACP 投影 3 项）。证据源码与 SHA 锁见
`tools/remote/fixtures/dsh-remote/source.json`，复现范围见[探针说明](../../tools/remote/README.md)。
真实网络与后端投影由隔离测试替身提供；固定上游 Runtime、Control、GatewaySwitch 与 ACP
Virtual Harness/Event Carrier 原文参与执行，没有实际账号、Host 或手机连接。

- VERIFIED（隔离自动化）：invoke/stream 同步捕获目标，已派发调用不随之后切 Host 重定向。
- VERIFIED（反例）：status 后存在异步间隙时可把同 Session ID 写入误发给新 Host；远端切本机后还会回落本机 Agent。
- VERIFIED（限制）：同 Host 同后端换工作区并消费临时选择后，公开 status 前后可相同。
- VERIFIED（原始 ACP 投影）：`session/prompt` 可按当前选中 workspace 注册未知 Session，忽略请求内旧 workspaceId；
  同名 Session ID 可在当前 workspace 接收。workspace follow 关闭后仍可读出关闭前缓冲的 baseline/upsert，关闭只结束后续等待。
- UNVERIFIED：真实投影关闭/缓冲事件排空与公开流所有者租用；真实 DSH 加载与后端互操作。
- BLOCKED（P0 委派放行）：尚无足够公开的 workspace/选择代际派发前证明；原始 ACP 投影
  不能以请求内 workspaceId 提供归属校验，不能只凭 status、sessionId 或后验检查放行生产跨后端写入，
  不使用 private hooks 绕过。

以上局部证据不改变 P0 整体状态，也不证明全量委派安全。后续必须在公开接口边界内补足证明，
否则保持该能力明确不可用并报告精确阻塞。

### 集成 focused 验证（集成 SHA `761c0c63`）

以下命令均使用仓库现有 JDK 21、Android SDK 与 Gradle 用户缓存；没有使用 `--no-daemon`，也没有连接真实账号或设备。

```sh
cd apps/android
./gradlew :gateway-client:testDebugUnitTest --tests 'com.openandroidintelligence.gateway.remote.*'
./gradlew :conversation-domain:testDebugUnitTest
cd ../..
node --experimental-vm-modules --test tools/remote/target-binding.test.mjs tools/remote/projection-scope.test.mjs
```

- `gateway-client`：BUILD SUCCESSFUL，16 actionable tasks，远程包测试 XML 共 137 项（Noise 9、协议固定 fixture 117、wire 5、DSHF 6），失败/错误/跳过均为 0。
- `conversation-domain`：BUILD SUCCESSFUL，64 项模块测试（其中 Remote 新增 17 项），失败/错误/跳过均为 0；本次大部分任务来自缓存。
- 固定上游目标/ACP 隔离探针：11 项通过。证据仅覆盖固定源码与测试替身，不覆盖真实 DSH、Host、后端或手机。
- 构建日志有既有 `DispatchedSchemaRegistry` nullable 警告、旧 `GatewayEventStream` ExperimentalCoroutines 警告，以及当前命令环境不可写 `/home/djbd/.android/analytics.settings` 的提示；没有因此放宽门禁。

### 独立 P0 目标归属审计

独立审计固定上游 `ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7` 的原文后确认：

- `packages/plugin/src/acp/virtual-harness.ts:468-489` 的 `ensureSessionRegistered` 可将未知的 `hermes:<id>` 按当前 selected workspace 注册；
  `:492-531` 的 `prompt` 不核验请求内 `workspaceId`。因此同名 Session ID 可能在当前 workspace 接收，而不是证明原 workspace 归属。
- `packages/plugin/src/acp/virtual-live.ts:59-123` 在等待 `openStream` 返回前就注册 follow；关闭期间可能留下 pending-open 窗口。
  `AcpVirtualHarness.close` 的清理不能被解释为派发前的原子租约。
- Typert Gateway 的公开 status 只能证明 Host/mode；workspace selection 被消费后，同 Host/backend 换 workspace 可出现等价 status。

因此 P0 的跨后端/ACP 委派保持 `BLOCKED`。安全可行的局部范围只能是不可切换目标、已登记 Session、同 Host/backend/session 的同步调用；
ACP/Codex 跨 workspace 委派和依赖后验状态检查的写入必须拒绝，不能用 private hooks、隐式全局目标或后验关闭信号绕过。

### P1 控制账本身份范围阻塞

独立 Spec 审查指出，当前 `conversation-domain` Remote 类型只能表达聊天端点/活跃连接：`EndpointKey`、`SessionOwner` 与 `RemoteFileRef` 尚未共同表达控制契约要求的 Remote Server origin、双方设备身份、本机授权代际、task/request 及 artifact scope。

因此现有聊天类型不得直接复用于控制账本、`request.status` 或文件读取授权。P1/P3 必须新增或明确独立的 `RemotePrincipal`、`TaskScope`、`ArtifactScope`（或等价不可混淆类型），并以 owner+request_id 做持久防重键；重连后同一可信 owner 才能查询原 task，其他同账号设备、旧连接或缺少授权代际的请求一律拒绝。未补齐前，未绑定 owner 的控制请求、旧 task 查询和 artifact 读取保持安全拒绝，不能把当前 domain 首片标成控制面完成。


### Fast/PR 首次运行与原生依赖静态补证

固定提交 `6e8f9706` 的 Fast/PR 均在源码中文扫描处阻断：`RemoteControlCodec.toString` 的内部脱敏
占位“已隐藏”被识别为 UI 文案。依赖锁检查已通过，后续阶段未执行。修复只对该内部诊断行添加
带中文理由的精确 `i18n-ignore`，不豁免目录、不改用户文案、不关闭门禁；修改后中文扫描通过。
后续门禁先 Fast，成功后才进入 PR，结果按新提交单独追加。

WebRTC 缓存产物已补核对，详见[原生依赖记录](2026-11-14-dsh-remote-native-dependencies.zh-CN.md)：
AAR/POM 摘要、minAPI 21、四 ABI、64 位 `0x4000` / 32 位 `0x1000` LOAD 段对齐，以及包装 MIT/原生 BSD
许可分层可记为静态证据；APK、native 加载、16 KiB 运行与真实通道仍未验证。精确运行依赖用于后续
真实 DataChannel adapter，不解除 P0 整体阻塞。


### ce0cccbf 正常权限副本门禁

原工作区私有目录安全测试失败后，实测新建并 chmod 0700 的目录在 FUSE 下显示 0777，而 `/tmp`
同样操作显示 0700。未修改安全校验；从本地 Git 建立 tracked clean、HEAD 相同的 ce0cccbf 验证副本，
复用既有 Node 24.18/JDK21/SDK/Gradle/npm 缓存执行：

```sh
npm run test:fast -- --since 12094e684e4bd6d4270513d54b6ef35437536552
npm run test:pr -- --since 12094e684e4bd6d4270513d54b6ef35437536552
```

Fast 通过（51.86 秒）。PR 运行至 Android 模块后失败（302.23 秒），唯一报告失败 task 为
`conversation-data:testDebugUnitTest`，其中 5 个 Hermes HTTP 测试因副本未指定已有
`HERMES_PLUGIN_ROOT` 而报 `HERMES_FIXTURE_MISSING`；这是验证环境漏接，不能归因于产品回归。
PR 前序的 Core 配置关联测试 266 项、DSH 旧适配器 147 项与 Remote 双探针通过。
已写 XML 的 Android 模块中，domain 64、capability-ports 67、control-ports 38、screen-control 41 均无失败。
后续先补齐既有固定插件/SDK/contract 环境，只复测失败模块，再决定最终提交完整 PR 门禁。
该记录只属于 ce0cccbf，不代替后续身份/传输集成后的验证。


### 身份与传输候选线性集成（9d5af960）

唯一写者停写后，由集成者对已测试分支执行 rebase 与 ff-only，未并发修改 worker 源码：

| 工作包 | 验证旧头 | rebase 后候选头 | patch-id |
| --- | --- | --- | --- |
| 身份 | `7db2924d9f68b96a4d8c2999ca2f13af70090ad5` | `c120678db0e3ea04d4f74171f7c1d738c192b193` | `1e382275d46b9f6dab17b1a32ff6670fdc3f9257` 前后相同 |
| 传输 | `9a4ff53c25e7c8f9abbf3a41c7f6469b50dc0c02` | `9d5af96052d70610cd50e65ba54bd77dd945669a` | `9ebffc5370fc60718bfe3fae32143d89a82a4776` 前后相同 |

集成候选 `9d5af960` 在既有正常权限副本执行关联验证，不使用新依赖缓存：

```sh
cd apps/android
./gradlew :gateway-client:testDebugUnitTest --tests 'com.openandroidintelligence.gateway.remote.*'
./gradlew :conversation-domain:testDebugUnitTest
cd ../..
python tools/check_hardcoded_chinese.py
python -m pytest -q tools/test_smart_test.py tools/test_ci_planning.py
node --experimental-vm-modules --test tools/remote/target-binding.test.mjs tools/remote/projection-scope.test.mjs
```

- `gateway-client` XML：198 项 = 协议/Noise/wire/DSHF 137 + Identity 24 + Transport 37，失败/错误/跳过均 0；36.62 秒。
- `conversation-domain` XML：64 项，失败/错误/跳过均 0；3.35 秒。
- 中文扫描 0 新违规；路由测试 194 项、固定上游双探针 11 项通过；`git diff --check` 通过。
- 先前 ce0cccbf 的 conversation-data 漏夹具环境已只复测该模块：设置固定历史插件 ebe527da、SDK75b083…、
  contract 与既有夹具 Python 环境后，61 项全部通过，18.78 秒。它属于原 SHA 的修复环境证据，不与新候选重复合计。

身份 fresh 安全复审关闭了“挂起注册后退出仍写入”和“重复注册覆盖已有对端 pin”两项缺陷。
当前仍只有 Store 接口、内存/逻辑协调与注册边界，生产 Keystore/AtomicFile/跨进程锁/真实 HTTP 注册尚未接入；
同一 Store 的进程内撤销不能被扩写为跨重启完整撤销保证。P1 不是实现完成。
传输只提供已协商 PeerConnection 的真实 DataChannel 窄适配、RTCH/串行/背压/关闭与加密归属，
尚无登录信令、ICE/TURN/Relay 选路和 App 生产装配，真实双向握手及设备互操作保持未验证/环境阻塞。


### 当前上游刷新不能解除 P0

只读核对 main `25a81da11b667be8f61a8d3f676615d7ff38fd18`，相对固定上游 ahead 15；
目标选择、Gateway 契约/分发和 ACP 投影/流五个关键文件的 blob 均与固定版本相同。
详细时间、blob 与最小公开接口需求见[上游缺口记录](2026-11-14-dsh-remote-upstream-target-binding-gap.zh-CN.md)。
本次不改 pin、不建上游 issue。允许升级也不表示当前存在已安全解决的版本，P0 与全量任务仍保持阻塞/未完成。


## 本批基础切片最终收口

最终被测源码为 `9a76e5c8e2688854e319ba48478ed1b3682f82e0`，比较基线为
`12094e684e4bd6d4270513d54b6ef35437536552`。随后提交只追加文档，不更改运行源码，
不因文档 HEAD 不同重复全量编译。机器可读结果见[最终验证清单](2026-11-14-dsh-remote-foundation-validation.json)。

新增身份修复拒绝孤立 UTF-16 代理项，并使用 REPORT 模式 UTF-8 编码 AAD/存储键；规范 origin 保持
ASCII、Locale.ROOT 和端口上界。`RemoteIdentityTest` 最终 26 项通过，包含合法代理对保留与不同账号键隔离。
Host 首片仅纯控制主体/任务/请求/产物作用域、类型化操作和严格 JSON/CLI 解析；精确大数摘要与 Unicode
回归 11 项通过。没有实现 Session V3 服务、任务路由器、持久执行账本或生产执行器装配。

### 最终实际门禁

在同 SHA、已跟踪源码无差异的正常权限副本，复用现有 Node 24.18.0、JDK21、Android SDK、npm/Gradle
缓存和固定历史夹具，执行原入口：

```sh
npm run test:fast -- --since 12094e684e4bd6d4270513d54b6ef35437536552
npm run test:pr -- --since 12094e684e4bd6d4270513d54b6ef35437536552
```

Fast `PASS`（40.77 秒）。PR 在纠正测试 bundle 输入后 `PASS`（275.29 秒）。App 使用项目已有
`oai.testParallelForks=4`，保持 `forkEvery=1`、原 heap/JIT、独立类进程及完全相同测试集合；
没有改测试源码、减少覆盖或跳过失败用例。PR 最后 App 调用 443 actionable tasks 中 25 执行、418 up-to-date；
测试任务实际执行，编译大量复用缓存。Fast 的 Gradle 只含 noVpnSurfaceCheck。

| 最终 Android XML 套件 | 测试数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| control-ports | 41 | 0 | 0 | 0 |
| capability-ports | 67 | 0 | 0 | 0 |
| screen-control | 41 | 0 | 0 | 0 |
| device-primitives | 55 | 0 | 0 | 0 |
| conversation-domain | 64 | 0 | 0 | 0 |
| conversation-ui | 926 | 0 | 0 | 0 |
| conversation-data | 61 | 0 | 0 | 0 |
| gateway-client | 618 | 0 | 0 | 0 |
| app FullDebug | 1890 | 0 | 0 | 1 |
| 合计 | 3763 | 0 | 0 | 1 |

实际执行通过 3762 项；唯一跳过是 `BuiltInCapabilitiesTest.aChannelWithoutPluginRuntimesReportsEveryCapabilityAsUnavailableWithAReason`，
该方法用 `assumeTrue(!BuildConfig.ALLOW_RUNTIME_PLUGINS)` 限定 Play 渠道，在 Full 中不适用。
本批没有执行 Play 全套或 Full 发布门禁。Python 配置/安装/卸载/CoreConfig/smart-test 合并套件 275 项通过；
Remote Node 双探针 11 项通过。196 项 CI/路由 targeted 检查另有通过记录，不与上述含重叠的 275 项再相加；
同样不重复累计早期 focused、固定向量内部轮次或失败后重跑。

### 环境纠错与证据限制

旧 ca9c1ee4 PR 已取消，不能当作通过；9a76e5c8 的第一次完整 PR 为 1890 App 项中 1 失败/1 跳过，
具体为 `NotificationCapabilityHttpTest` 的 `Configured notification bundle missing from APK assets`。
原因是 OAI_BUNDLED_PLUGINS_DIR 指向带版本名的旧 .alp 文件，而 StageBundledPlugins 要求
notifications.alp 等六个固定名称。使用现有 `build:android-plugins` 流程、同源码/现有真实 WASM
和隔离随机测试作者生成正确六包，逐包核对 ID 与 WASM 字节；临时签名私钥未输出/入库并安全移入回收区。
先定向复测通知 HTTP 类 2 项通过（17.12 秒），再跑最终完整 PR。这个修正只改变测试输入，不改变业务实现。
另一个副本输入 plugins/target 缺失已经相对链接到既有产物；先定向 AdditionalDeviceConnectionsTest 14 项通过。

验证副本的 main assets 合并目录已包含六个 bundle 和七份 Remote 许可证原文，后者 SHA 逐份一致；
这属于资产合并证明，未构建/安装正式 APK，不替代发布通知与 ABI/16KiB 设备验证。
日志保留现有 nullable/deprecated 与 Robolectric SQLite CloseGuard 警告；它们没有导致最终门禁失败，
也不据此宣称已完成资源泄漏专项审查。

### 最终独立评审及后续入口

`final_standards_review` 与 `final_spec_review` 分别以不继承开发上下文的方式只读审查
`12094e68...9a76e5c8`，最终均 PASS，无剩余具体 P1/P2。身份、传输与控制解析的局部 fresh 安全复审
已关闭各自已复现缺陷；这些审查不替代真实设备、Host、部署或后续功能实现。

当前交付只是 P0/P1 基础切片可评审，PR #190 保持 Draft，#187/#188/#189 保持 open，阶段大项不勾为完成。
应用生产入口仍使用旧 Core 链路；新 Noise/身份/DataChannel/控制类型/解析基础尚未接入生产会话/控制装配。
P0 上游目标绑定硬阻塞仍在；生产 Keystore/AtomicFile/跨进程锁/HTTP、信令/RTC 选路、Session V3、
执行账本、屏幕 SoM/收敛/overlay/急停、现有 UI 接线、Bridge/Stdio MCP 与旧依赖退出均未完成。
用户明确稍后提供专用环境，本轮不进行真机测试、不占日常 Host 连接；全量迁移与设备/后端/部署验收未完成。
后续按 #187 分阶段继续；上游缺口及最小公开接口需求见[缺口记录](2026-11-14-dsh-remote-upstream-target-binding-gap.zh-CN.md)，
本次不改 pin、不向上游发送 issue 或消息。所有 worktree 保留，PR 合并后的清理由后续按安全规则处理。
