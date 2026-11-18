---
status: accepted
date: 2026-11-13
implementation_status: not_started
acceptance_status: pending
issue: 185
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
---

# DSH Remote 固定事实与 OAI 适配边界

本文件为[架构重构规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)提供可复核的
上游事实。`accepted` 表示本次目标设计已接受；OAI 新通信、Virtual Host、Bridge 与控制
能力均 `not_started`，目标平台和真实设备验收均 `pending`。不能把上游已有源码、文档中的
实验记录或本次文档审查当作 OAI 已完成实现。

## 1. 固定版本与证据层级

核实日期为 2026-11-13，上游固定为
`liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，
提交时间为当日北京时间 17:45:16；插件声明版本为
[0.6.5](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/package.json#L1-L4)。
这些是固定快照，不能推断未来 main、已发布 npm 包或官方线上服务已经同步。
仓库一级源码与中文技术规范共同使用；存在历史章节或证据不一致时明确记录差异，
不因示例类型存在就宣布互操作通过。Context7 检索没有收录该 Remote 仓库，
因此未用 DeepSeek Harness 主仓库的文档替代 Remote 私有扩展事实。

本文区分三种结论：**上游已有**指固定提交的实现/规范，**OAI 目标新增**指本次需要实现的
适配，**待验收**指尚无本项目目标平台证据。旧 OAI Core/Gateway 的现存实现属于迁移前事实，
不能作为新架构的长期兼容要求；本次已确认不做旧账号、密钥、配对或历史迁移。

## 2. 身份、账号与通信

| 项目 | 上游固定事实 | OAI 目标与限制 |
| --- | --- | --- |
| 密码套件 | 固定 `Noise_IK_25519_ChaChaPoly_SHA256`，prologue 绑定协议版本、connectionId、双方设备 ID；禁止自行实现 Noise 状态机。[协议 §12](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L558-L576) | 各路径使用相同安全通道；原生实现/绑定必须通过上游向量与真实对端互操作，不改变密码组合 |
| 角色身份 | Host/Client 分开 deviceId、X25519 私钥和设备 token，按 Server origin 与角色隔离；相反角色可继承 owner，不能复用密钥/凭据。[角色注册](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L273-L286) | 手机登录即创建独立 Client 和 Virtual Host；Host 默认仅被控，Client 独立休眠。不能把 Host 的被控限制误作 Client 不可主控 |
| 同账号发现 | Server 校验同账号 membership，设备列表只包含可授权 Host，详细公钥另查；Host 必须交叉校验 Client descriptor 并固定身份。[账号授权](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L342-L365) | 一个活动账号，默认官方服务并允许兼容自托管 origin；不设计多账号并行。手机仍是设备能力授权权威 |
| 首次信任 | 同账号首次连接自动 pin；已知 ID 换钥拒绝，首次公钥分发仍依赖 Server，不构成独立带外身份核验。[安全说明](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/end-to-end-encryption.md#L37-L50) | E2EE 不等于操作许可；保留本机 Protected Mode、风险策略和敏感确认，不能把同账号当作允许控机 |
| 四级路径 | 默认 `LAN → P2P → TURN → Relay`；LAN/P2P 为 WebRTC 路径分类，Relay 可为独立数据 WSS，也有旧 Control WSS 载体。[网络模式](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/network.md#L118-L157) | 替换 Tailscale 核心依赖，按协商使用真实可用路径；不新增 OAI 穿透或 Relay 服务 |
| 多连接 | Host 按 connectionId 隔离不同 Client 的 RPC、stream 与清理，同 clientDeviceId 新连接可替换旧连接。[连接隔离](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L542-L554) | connectionId 不是永久任务/设备身份；禁止跨连接误领审批，不通过复制设备 token 启动竞争连接 |
| 恢复 | 四级回退主要覆盖建立阶段；默认断线重新认证、选路和 Noise，不静默重发未知 mutation。[建立与恢复](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/network.md#L147-L157) | 不承诺无缝切网；依靠手机持久 request_id 回执避免重放，传输恢复与业务恢复分别验收 |

协议默认连接完整官方服务，但仍有外部服务依赖：账号/设备管理、presence、signaling、
TURN 凭据与 Relay。删除 OAI 自建服务端不等于删除这些职责。
随上游仓库的 `apps/server` 只是最小单账号子集，完整服务另有实现；设备配对能力明确
不在最小 Server 内，不能用最小 Server 启动成功代表所有授权方式可用。
依据为[协议服务范围](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L15-L27)
与[设备配对边界](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L2192-L2200)。

可选 `transport.webrtc.resume.v1` 的源码与设计允许健康、已认证的 RTC/Noise 数据面在有限
授权租约内恢复 Control；需要 Server、Host、Client 三方协商，排除端点进程重启、ICE restart、
自动切路与业务请求重放。设计自述未发布/部署，且接受租约内撤销延迟；不能扩写为四级传输
已经无缝恢复。见[恢复设计及待验收项](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/design/webrtc-control-recovery.md#L3-L25)。

## 3. 会话投影、后端与用户界面

Harness 使用官方 Typert / Session V3 carrier；Codex 使用独立 `codex.app.*`，ACP 使用独立
`agent.acp.*`。统一的是客户端内存展示投影，不是把所有后端伪装成 Host 上第二份 Harness
数据库。Remote 不复制后端权威状态；OAI 的离线加密历史缓存只作为可读快照，不取得会话、
审批、模型或发送权威。依据为[RPC 边界](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L742-L758)
与[Codex 投影](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/codex-remote.md#L6-L26)。

| 对象 | 可确认的上游事实 | OAI 文档必须保留的区别 |
| --- | --- | --- |
| AgentEndpoint | 工具发现已分别报告 backend、workspaceId、path；backend 有 Harness、Codex 和明确 ACP ID。[发现实现](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/client-runtime.ts#L503-L545) | 扁平 `AgentEndpoint(hostId,backend,workspaceId)` 是 OAI 领域适配；显示名称不是身份，Session 仍绑定完整端点与原生 ID |
| 可用性 | capability 表示实现与启用，`workspaceTypes.available` 表示实际就绪；不可用不得偷偷换后端。[能力规则](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L953-L963) | 保留现有聊天/审批/附件组件，按真实能力开放数据源；不能静态宣称八种 Agent 的功能相同 |
| 模型与 effort | ACP catalog 来自 Session configOptions 或 availableModels；effort 仅适用于当前模型，切换需要上游确认。[模型状态实现](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/acp/adapters/session-models.ts#L18-L65) | 顶部选择器绑定当前 Session 和后端代际，Endpoint 缓存仅作提示，不能冒充当前选项 |
| 审批 | ACP 仅映射真实单次允许/拒绝/取消，句柄绑定后端实例、会话与拥有者连接。[审批边界](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L1469-L1485) | 复用现有审批卡片；Agent Approval 与手机敏感操作确认是两套许可，不相互替代 |
| 大文件 | `workspace.upload.v1` 独立于业务 carrier/图片，必须先协商并按会话真实 cwd 校验，不接受客户端任意目录。[上传范围](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/protocol.md#L1616-L1638) | 保留附件面板，但上传成功后引用 Host 确认路径；缺少能力不能伪造已注入，不能突破本机文件选择授权 |

后端支持与验证按来源分别记录，不能把文字链路通过扩写成图片/审批/中断全部通过：

- **Harness**：Session V3 为当前 carrier，早期 ApiProxy 已从当前 Host/Android 移除；
  [兼容说明](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/compatibility.zh.md#L3-L19)
  记录基线与本地验证，同时明确跨设备/原生验收边界。
- **Codex**：独立 App Server，文档记载跨机、Android 真机、审批、中断和图片验证，
  仍以实验性功能发布，不能据此认定本项目已验收。
  见[Codex 状态](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/codex-remote.md#L89-L93)。
- **Hermes**：`hermes acp`，默认关闭，使用本机 provider/凭据与标准会话/模型；
  已有本机 Gateway 文字/工具/历史验收，图片真实链路仍待验。无需旧 OAI 服务端 adapter。
  见[Hermes 接入](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/acp-integration.md#L65-L88)。
- **Claude**：`claude-agent-acp`，默认关闭，不是 `claude acp`；当前文字、历史、模型/effort、
  审批与中断各有记录，但图片不支持、跨设备/原生验收待完成。
  见[Claude 接入](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/acp-integration.md#L32-L63)。
- **Cursor、OpenCode、Pi、Antigravity**：分别使用明确适配器与能力标识；文字、图片、
  Thinking、归档和工具审批有差异。尤其 Pi 默认不逐工具审批；不能凭“ACP”推断相同权限。
  见[适配矩阵](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/acp-integration.md#L6-L30)
  和[后端表](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/acp-integration.md#L100-L111)。

## 4. 原生工具、Virtual Host 与本机 Bridge

`remote_*` 是本机 Harness tools 注入，默认开启，但仍受已登录账号、设备信任、权限与回执
约束。上游已有发现与会话工具，不等于已有手机 Host 或通用控机 RPC。
见[工具注入说明](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/remote-tools.md#L1-L9)。

| 核实点 | 固定上游依据 | 本次新增适配 |
| --- | --- | --- |
| 原生会话工具只接受 Harness | `remoteSessionTools` 的 backend 枚举只有 `harness`，发现可列其他后端。[工具定义](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/remote-tools.ts#L85-L129) | 手机实现受限 Session V3 控制门面；不能在网络中等待不存在的 `remote_send_message` RPC |
| 工具实际调用 Typert 端点 | 检查 workspace baseline、session catalog/follow/page；发送转为 `session/prompt` 的 queue 文本。[校验与发送](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/remote-session-tools.ts#L157-L229) | `phone-capabilities/control-bus` 与每任务独立 Session 必须满足真实 catalog、运行/待输入/结果投影 |
| 下载不是任意 URL | 按 Session scope 调 `workspaceFiles/stat/readBytes`，核对版本、大小、offset/eof。[下载实现](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/remote-file-download.ts#L7-L46) | 截图/导出保存在私有虚拟根，按任务 owner 暴露受限 artifact，不开放手机任意目录 |
| 上游回执在内存 | 工具说明重启/登出后 not_found 不能证明没有执行。[回执语义](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/remote-tools.ts#L111-L129) | 手机新增持久 request_id 账本；崩溃或断线结果未知时禁止重放 |
| 本机 Remote 服务 | `runSessionTool` 是进程内服务；已登录 raw client、连接和工作区投影为 private。[运行时边界](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/client-runtime.ts#L62-L132) | 新增进程内 Bridge 与受限 loopback API，Stdio MCP 复用它；不是现成独立官方 SDK |
| 领域客户端可导入 | client-core 公开重导出 Typert、Harness、Codex、ACP 客户端。[包导出](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/client-core/src/index.ts#L237-L241) | 缺口是现有已登录通道的公开租用接口，不能误写成领域类不存在，也不能私取 raw client |
| 公开工作区选择会改目标 | Codex 与 ACP 打开工作区会替换全局 target、关闭旧投影并设置 pending selection。[选择实现](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/client-runtime.ts#L613-L767) | 后台跨 workspace 委派不属于本客户端；正常手动切换保留，以独立连接/代际及旧流取消排空拒绝迟到事件，不访问 private hooks |
| 本机路由已有校验入口 | WebServer 注册受限 namespace，复用 `Connection.requestRejection` 和 loopback authority。[路由实现](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/control-route.ts#L47-L89) | Bridge 增加运行期 bearer、origin/账号代际绑定和固定操作白名单，不另建中心鉴权服务 |
| ACP 不能远程注入 MCP | session/new 的 mcpServers 只允许空数组，Gateway 强制使用空数组。[参数策略](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/acp/method-policy.ts#L41-L45) | 由电脑端 Agent 原生配置挂载 Stdio MCP，各后端实际加载另验 |

Session facade、确定性 RPC、CLI、持久回执、屏幕快照、SoM、收敛等待、overlay、Kill Switch
和 Bridge 的规范要求统一见[控制 RPC](../contracts/oai-control-rpc-v1.md)，不在此重复创造第二套接口。
本次能力是 OAI 自有控制 Session，不复制其他 Agent 权威会话；当前上游规范明确不提供
远程桌面或通用工具 RPC，不能把这部分新增工作宣称为上游已有能力。
见[上游控制边界](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/docs/end-to-end-encryption.md#L146-L156)。

## 5. Android 原生实现与复用方式

上游 Android 是 Expo/React Native + TypeScript，不是原生 Kotlin 通信 SDK。
`apps/android/package.json` 依赖 `@dsh-remote/client-core/crypto/protocol/webrtc` 与
`react-native-webrtc`；入口 `registerGlobals()` 后共享 WebRTC 包才使用其原生桥。
`SecureTransport` 直接调用 TypeScript 的 `NoiseIkSession`，crypto 包依赖
`@lukeburns/clatterjs@1.0.0` 和 noble。依据为
[Android 依赖](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/apps/android/package.json#L6-L43)、
[RN 入口](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/apps/android/index.ts#L1-L13)、
[安全通道](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/apps/android/src/services/secure-transport.ts#L1-L48)
和[密码依赖](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/crypto/package.json#L18-L22)。

OAI 保留原生 Material 3 界面，复用的是上游协议、状态机行为与互操作向量；
不能将 TypeScript clatterjs 包写成已经可用的 Kotlin 依赖。原生通信适配需要选择成熟
Noise 实现/绑定与合适的 WebRTC 原生接入，先通过
[Noise 固定向量](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/fixtures/crypto/v1/README.md#L3-L25)
及协议正反向量，再证明主动 Client 与被控 Host 均可同固定上游互操作；依赖选型、产物与真机
互操作尚未完成。本设计不以手写密码状态机或重做穿透算法填补这一适配。

上游手机目前只按 `role:client` 注册，单个活动状态包含 config/identity/account，
凭据读取必须匹配 Server URL 和设备 ID；登录账号 token 仅用于设备注册，不长期保存。
依据为[Android 注册](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/apps/android/src/services/api.ts#L234-L271)、
[状态结构](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/apps/android/src/state/store-state.ts#L55-L62)
和[注册与凭据](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/apps/android/src/services/server-session-manager.ts#L27-L70)。
OAI 的双角色、Host 后台待命及锁屏数据裁决均为目标新增能力，不是复制这套单 Client 状态即可获得。

现有 OAI 屏幕端口已经具有带快照修订的点击、长按、输入、滚动、滑动、拖动、导航和启动应用；
现有短信 `.alp` 只有读取及定时读取相关能力，没有发送。相关本地依据为
[屏幕契约](../../apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/ScreenContracts.kt)
和[短信声明](../../plugins/sms/manifest.json)。新架构应保留这些原能力，另实现用户要求的发送，
不能因控制面重写而降级或把尚未接通的发送端口声明可用。

## 6. 旧决策的目标替代范围

下表只规定新目标版本采用什么，不追溯改写旧版本的实现与验收记录；文档接受后，
运行代码仍待实施。旧 ADR 的安全原则不因服务拓扑退役而一并失效。

| 旧决策 | 被替代的范围 | 新目标与继续保留的原则 |
| --- | --- | --- |
| [ADR-0001](../adr/0001-keep-a-minimal-android-host-and-trusted-platform-kernel.md) | 只通过 HTTPS Gateway 对话、所有设备控制一律作为插件的旧入口表述 | ADR-0063/0065：Remote 双角色；不可插件化的身份、权限、隔离与紧急停止继续由本机内核掌握 |
| [ADR-0002](../adr/0002-replace-the-standalone-bridge-with-agent-hosted-gateways.md)、[0006](../adr/0006-share-gateway-contracts-not-a-cross-host-binary.md)、[0007](../adr/0007-introduce-gateway-protocol-v2.md)、[0013](../adr/0013-use-https-requests-and-sse-for-gateway-protocol-v2.md) | Agent 内嵌 Gateway、Gateway Protocol v2、各宿主实现 Gateway Core、HTTPS/SSE 业务载体 | ADR-0063：固定 Remote v1、Noise、Typert 与后端独立数据面；不保留旧兼容旁路，类型化命令、防重放与真实身份原则保留 |
| [ADR-0023](../adr/0023-use-sqlite-with-explicit-gateway-secret-management.md)、[0030](../adr/0030-host-multiple-account-isolated-logical-gateways-per-adapter-deployment.md)、[0048](../adr/0048-provision-the-gateway-master-key-on-install.md)、[0061](../adr/0061-plugin-provisions-its-own-key-source.md) | OAI 服务端账号库、主密钥来源/自动生成、每账号逻辑 Gateway 与部署约定 | ADR-0063：单一活动 Remote 账号与分角色设备凭据；OAI 旧账号/密钥管理退役，秘密不得进入日志、普通配置或 Agent 上下文的原则保留 |
| [ADR-0035](../adr/0035-provide-equivalent-host-ui-and-cli-gateway-management.md)、[0037](../adr/0037-supersede-standalone-bridge-design-and-freeze-legacy-runtime.md)、[0047](../adr/0047-allow-plaintext-gateway-pairing-with-explicit-warning.md) | 旧 Gateway 管理 UI/CLI、以旧运行时作为迁移来源、旧明文配对策略 | ADR-0063：不要求旧数据迁移，不继承旧明文许可；Remote 注册/授权及 origin 策略按上游和新规格执行，连接身份与失败状态必须如实呈现 |
| [ADR-0039](../adr/0039-run-tailscale-as-an-optional-companion-plugin.md) | 可选 companion 连向同一 HTTPS Gateway、故障回到直接 HTTPS 的旧网络路径 | ADR-0063：核心选路由 Remote 接管；原本可选的工具继续独立解耦，不承担 Noise 身份或本机授权 |
| [ADR-0040](../adr/0040-ship-device-capabilities-as-unprivileged-reference-plugins.md) | 离散参考插件声明中的旧配对授权绑定 | ADR-0065 继续保留短信/通知/通话记录的签名、沙箱、权限与脱敏；屏幕原生边界由新决策补充，不能声称旧 ADR 已规定高频控机通过 WASM |
| [ADR-0054](../adr/0054-unify-device-authority-and-native-sessions-in-one-oai-core.md)、[0055](../adr/0055-reuse-pinned-upstream-gateway-core-state-machines.md)、[0056](../adr/0056-native-agents-surface-is-the-only-chat-entrance.md) | 统一 Core 进程、旧 Core/Gateway 请求权威、`/agents` 唯一会话入口与旧协议 pin | ADR-0063/0064：Host 原生会话、Remote 载体与手机持久控制账本；不复制其他 Agent 权威会话，不让服务端重新授权手机能力 |
| [ADR-0057](../adr/0057-split-session-scope-by-host-facts-and-never-share-device-credentials.md)、[0058](../adr/0058-config-is-the-agent-discovery-boundary.md)、[0060](../adr/0060-authenticated-core-management-and-account-routing.md) | 旧 account/deployment 分类、Core 管理员/2FA/设备默认选路、旧宿主候选发现流程 | ADR-0063/0064：Remote membership 与明确 AgentEndpoint、任务 owner；不向共享 Agent/MCP 复制设备身份或 token，不改投已受理目标 |
| [ADR-0044](../adr/0044-keep-an-encrypted-local-conversation-mirror.md)、[0046](../adr/0046-arm-submission-until-attachments-are-verified.md)、[0051](../adr/0051-agent-owned-attachment-media-and-streaming.md)、[0053](../adr/0053-persist-conversation-media-and-export-user-downloads.md)、[0062](../adr/0062-own-message-merging-in-the-android-client.md) | 其中依赖旧 Gateway/账号路由的接口绑定 | 新规格重新绑定到 Remote Session 与明确端点；加密离线可读、真实附件确认、Agent 内容权威、显式导出和客户端合并能力继续保留，不迁移旧数据 |
| [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md) | Core 路由与 `android-v1` 协商绑定、旧数据迁移与降级兼容要求 | 本机唯一授权权威及现行每次手动、范围内只读许可、敏感确认与后台独立授权继续有效；新架构不做旧数据迁移，不因同账号或加密放宽许可 |

新决策的依据与取舍分别由 ADR-0063（Remote 与独立身份）、ADR-0064（共享控制 Session 与本机
MCP Bridge）、ADR-0065（Kotlin 屏幕/WASM 数据边界）记录；本表不把安全原则移成第二套运行规则。

## 7. 验收证据的使用范围

本次只完成文档与固定源码核实，没有执行上游测试、构建 OAI 新版 APK、部署 Remote Server，
也没有对真实手机或八种后端逐项完成新链路验收。后续实现必须分别记录：

1. **协议与本地自动化**：Noise/协议向量、typed RPC、Session V3 facade、任务/连接隔离、
   持久回执、状态机、既有能力回归；不能由类型检查代替真实承载互操作。
2. **真实 Host 与后端**：固定插件版本下每个已启用后端的发现、会话、模型/effort、
   流、附件、审批、中断与手动 workspace/对话切换；能力未实现/未就绪时显式禁用，不报空成功。
3. **Android 与网络**：目标 APK/真机的双角色、LAN/P2P/TURN/Relay、切网、前后台、
   锁屏、恢复、overlay 焦点、SoM 和 Kill Switch；上游 RN 真机记录不能替代 OAI Kotlin 验收。
4. **交付与部署**：实际发布包、使用的服务 origin、支持的自托管能力及真实版本；
   源码通过不等于部署完成，官方服务“可登录”不等于所有协议扩展已上线。

每项记录采用“已验证 / 阻塞 / 未验证”，附实际版本和场景。具体行为断言以
[主规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)与
[控制契约](../contracts/oai-control-rpc-v1.md)为准，不用“整体已完成”覆盖未验证项。
