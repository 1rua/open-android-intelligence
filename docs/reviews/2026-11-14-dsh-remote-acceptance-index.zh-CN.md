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
| P0 | #188 | 契约增量、Noise/协议向量、依赖许可/ABI、公开目标绑定探针 | UNVERIFIED |
| P1 | #189 | 双身份、原生通道、最小真实 Session V3 与未改 remote_* 双向链路 | UNVERIFIED；真实环境 BLOCKED |
| P2 | #187 后续子项 | Harness/Codex/ACP 数据面及现有聊天/附件/审批/助理回归 | UNVERIFIED |
| P3 | #187 后续子项 | 最小账本、原生执行、SoM、收敛、overlay、锁屏与敏感确认 | UNVERIFIED；物理设备 BLOCKED |
| P4 | #187 后续子项 | 进程内 Bridge/Stdio MCP、双入口和跨后端无误投 | UNVERIFIED；真实 Host BLOCKED |
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
| REM-17 | P0/P4 | Stdio/进程内桥、已有路由无新监听、全部真实后端串行委派 | UNVERIFIED |
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
| 公开委派 | 同 sessionId 跨 Host、选择后/调用前切换、在途流关闭、目标代际与结果归属、断线不重投、不抢回工作区 |
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
