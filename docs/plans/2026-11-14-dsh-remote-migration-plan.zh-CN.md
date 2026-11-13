---
status: accepted
implementation_status: in_progress
acceptance_status: pending
date: 2026-11-14
tracking_issue: 187
foundation_issue: 188
transport_issue: 189
baseline_commit: 12094e684e4bd6d4270513d54b6ef35437536552
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
---

# DSH Remote 全量迁移实施计划

本计划实施已接受的[总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)与
[控制契约](../contracts/oai-control-rpc-v1.md)，不将 #185 已关闭或文档 PR #186 已合并解释为运行时完成。
全量跟踪为 #187，P0 为 #188，P1 为 #189。验收逐项记入[验收台账](../reviews/2026-11-14-dsh-remote-acceptance-index.zh-CN.md)。

## 已批准边界

- 固定上游 `ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`；不 fork、不依赖 private hooks，不新开 HTTP/WS 监听端口。
- 单活动账号自动建立独立 Client/Virtual Host 身份；手机权限只由本机内核裁决，不由账号或 Noise 通道授予。
- `control-bus` 可单步调用；独立多步任务真实 create。控制消息、树、截图和结果正文仅内存，重启不恢复、不回放、不续做。
- 最小持久账本仅 owner/request/task/op/参数摘要/execution/state/取消关联；旧任务 owner/失效标记仅防重与查询，不恢复业务 Session。
- 只读 `request.status` 经当前总线查询原最小回执，原回执放本次查询 result；正文未保留时 `resultAvailable:false`。
- MCP 首次读树/截图可 create 并返回 taskSessionId，后续显式携带 taskSessionId/snapshotId；动作缺任务不得暗中新建。
- 正常聊天镜像、主动推送队列与短信定时任务继续按既有功能加密持久。不迁移旧数据，不发布双协议兼容版本。
- 保留现有聊天组件结构和既有功能；旧 Core 服务专属管理随服务退役。旧文件只安全移入忽略的 `.worktrees/trash/`，由用户最终清理。

## 分阶段交付与退出条件

| 阶段 | 实施内容 | 退出条件 |
| --- | --- | --- |
| P0 契约与可行性验证 | 控制契约增量、需求/测试矩阵；成熟 Noise 实现、WebRTC 依赖与固定公开目标绑定探针 | 密码/协议向量、依赖版本/许可/ABI 记录、公开委派目标归属证明；未具备证据不得记通过 |
| P1 原生通信与最小 Virtual Host | Noise、登录双身份、信令、RTC/Relay、分块/背压/撤销/重连、真实 Session V3 门面 | Kotlin Client→桌面 Host 与反向真实握手/RPC；未修改 remote_* 完成发现、建任务、发送、等待、读回、下载及真实已授权只读能力 |
| P2 多后端会话与现有界面接线 | Harness/Codex/ACP 独立数据面；App、助理、通知、附件镜像与最小连接/端点/模型选择 | 既有聊天、附件、审批、助理回归；各后端真实模型/历史/流/附件/审批/中断，不经旧 Core |
| P3 控机安全与完整 Host | 原子最小账本、内核裁决、树/快照/SoM、400ms 静默/3000ms 截止、overlay/急停、WASM 离散能力及真实短信发送 | 并发归属、崩溃/取消不重复；真机锁屏、焦点、撤销、截图排除确认界面与紧急停止 |
| P4 Bridge 与 Stdio MCP | DSH 进程内桥、既有 WebServer 受限 loopback 路由、运行期 bearer/固定操作/委派账本、公开串行工作区委派 | 两种真实控机入口；各真实可用后端目标失效后不误投、不重放，不泄露 Remote 凭据 |
| P5 功能覆盖、旧依赖退出与发布 | 推送确认、定时、离线、默认助理、导出；退出 Core/contract/adapters 及安装/build/CI/fixture/插件工具链消费 | REM-18/19 完整覆盖、干净包无旧依赖、Fast/PR/Full、设备/网络/后端矩阵及独立双轴评审 |

P0 先冻结共享接口；P1 后 P2/P3 可以并行；P4 依赖 P3 与 P0 公开接口门禁；P5 等全部必需退出条件完成。
阶段推进不代表发布兼容两套协议的版本。全部必需项完成才可报告全量迁移完成。

## 并行职责与共享文件

| 工作包 | 独占范围 | 交付与依赖 |
| --- | --- | --- |
| 集成与契约 | docs、共享 Gradle/依赖、CI/测试路由；电脑公开目标归属探针 | 汇总 issue/PR、固定版本与验收台账，独占共享文件，整合前 rebase |
| 协议与密码 | gateway-client 的 remote/protocol、remote/crypto 与相关测试/固定向量 | 不修改共享 Gradle；成熟实现通过固定 Noise/协议正反向量，保留许可来源 |
| 会话领域与门面 | conversation-domain 新端点/会话模型与最小控制门面独立代码及测试 | 先提供不依赖旧 Core 的可信身份、任务/请求类型与真实状态投影；不得假造后端能力 |

各工作包独立 worktree/分支，禁止并发修改同一文件。后续屏幕/Bridge/界面工作包在相应前置完成后派发，
不得为了并行先删除旧实现。模块交接以提交 SHA 和公共契约为准。

## P0 依赖与公开接口门禁

- Noise 候选：`org.signal.forks:noise-java:0.1.1`；先记录许可、固定向量、API/ABI 与 Android 兼容验证。不得手写密码状态机。
- WebRTC 候选：`io.github.webrtc-sdk:android:150.7871.04`；先核实产物、许可/传递依赖/ABI，再接原生通道。未使用前不预置运行依赖。
- 电脑桥只通过固定上游公开 `ctx.dshRemoteClient` 与 `ctx.typertGateway`；验证 invoke/stream 捕获目标的时点、切换后的旧流关闭及相同 sessionId 跨 Host 归属。
- 串行委派允许切换桌面工作区；用户改选立即令旧观察失效，不自动抢回或向新目标续发。无法证明目标绑定时安全拒绝，不能访问私有对象绕过。
- 本地隔离 fixture/探针只能证明对应代码边界，不能替代实际 DSH 插件加载、已登录通道和各真实后端验收。

## 状态、取消与安全验证

持久执行栅栏必须在副作用端口前原子落盘；栅栏后崩溃且结果不明记 unknown。重启终止未派发请求，
不恢复审批、执行队列或正文。`request.cancel` 原子核对原请求，优先于普通动作排队；
`session/cancel` 只冻结原任务。MCP EOF、取消等待和超时不等同于取消已受理业务。

树→截图→动作必须共享显式任务和有效快照；旋转、布局、窗口或授权变化令旧引用失效。
动作执行结果与界面收敛分开，3000ms 超时不重试动作。树、截图、控制分别授权；锁屏拒绝屏幕操作。
确认 overlay 不抢焦点、不进入树/截图、不被远端坐标或节点命中；不能可靠排除时拒绝捕获。
Android API 34 窗口截图与较低 API/MediaProjection 分别验收，不能扩大安全声明。

## 执行、评审与真实环境

每阶段按 issue→独立分支/worktree→Draft PR→中文提交→rebase 集成→必要门禁→独立 fresh 双轴评审推进。
评审同时核对 issue 覆盖；修复后才转正式 PR。CI 后台跟进，不前台阻塞轮询；同 PR 三轮不收敛则报告。
禁止 `rm/unlink/reset/clean` 等破坏性清理，不提交凭据、构建产物或设备内容。

用户明确“稍后提供，先不进行真机测试”。本轮不进行真机测试、不占用日常 Host 账号/连接；
专用手机、Host、origin、后端和连接方式待提供。此限制不阻止独立代码和自动化工作，
相关真实握手、四路径、锁屏/overlay、后端挂载及部署验收保持 BLOCKED/UNVERIFIED。
