# Agent Bridge Android 与 Agent 端集成设计规格

> [!WARNING]
> 本文已于 2026-08-24 被 [Open Android Intelligence 模块化插件架构规格](./2026-08-24-modular-plugin-architecture.md) 取代，仅保留历史背景。独立 Device Bridge、Tailscale 默认链路和 App 内建能力不再是当前架构要求。

- 日期：2026-08-08
- 修订日期：2026-08-09
- 状态：规格 v1.1；用户已于 2026-08-08 书面确认，2026-08-09 确认 App 内嵌 Tailscale 且不占系统 VPN 通道的网络修订
- 目标平台：Android 14（API 34）及以上
- 目标 Agent：[NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) 与 [openclaw/openclaw](https://github.com/openclaw/openclaw)
- 分发模式：受控设备私有分发或侧载；Google Play 上架不是首版目标

## 1. 摘要

本项目提供一个 Android App，使多个用户能够把各自手机安全连接到同一个逻辑上的自托管 Agent 服务。Agent 可以在用户授权范围内读取手机数据、接收同步事件、请求写入或修改数据、执行受控命令，并在限时会话中查看或操控屏幕。App 还可以注册为 Android 默认数字助手，通过系统手势或按键唤起文字、语音、图片和文件对话。

Android App 只实现一套统一的设备协议。Agent 端部署 Device Bridge，并分别通过 Hermes 和 OpenClaw 适配器接入各自的 plugin、工具、事件和会话系统。App、Bridge 和 Agent skill 的职责严格分离：

- App 是实时采集和设备操作的最终裁决者；Bridge 已存副本的查询授权由独立的服务端授权状态裁决。
- Bridge 负责设备身份、多用户隔离、消息路由、存储、幂等和审计，并以服务端最小权限 Tailscale credential 签发一次性节点注册凭据、绑定及撤销 Tailnet 节点。
- Agent plugin 负责把手机能力转换为 Agent 工具及事件。
- Agent skill 只指导模型正确使用工具，不承担认证或权限控制。

项目采用分阶段交付。本文是目标产品的总设计规格，后续分别为协议与 Bridge、Android 安全底座、数据采集器、高危能力、数字助手、Hermes 适配器和 OpenClaw 适配器编写子规格与实施计划。第一份实施计划只覆盖 P0a 协议与安全模型。

## 2. 已确认的产品决策

| 主题 | 决策 |
|---|---|
| 部署与权限 | 受控设备模式；标准 Android API、Device Owner、Shizuku/ADB、类型化 Root 动作分层适配 |
| Android 基线 | Android 14（API 34）及以上 |
| 拓扑 | 一个逻辑 Agent/Bridge 服务管理多位用户的多台手机；允许内部使用隔离 workspace、worker 或凭据实现安全边界 |
| App 形态 | 自建统一产品与 Device Bridge，不 fork OpenClaw Android App；主 App 与最小权限 companion APK 作为同一签名产品套件交付 |
| Agent 后端 | 支持 Hermes Agent 和 OpenClaw；通过服务端适配器消除差异 |
| 默认网络 | App 内嵌、仅供本产品连接使用的 Tailscale userspace 节点；不使用 Android `VpnService`、系统 TUN、系统路由或系统 DNS；用户显式启用的公网 HTTPS 直连作为备用 |
| 后台可靠性 | 尽力而为，不维持常驻前台服务，不承诺设备始终在线 |
| 同步方式 | 每个数据源可选按需、事件后上传或定期批量上传 |
| 本机缓存 | 加密缓冲、按来源设置期限和配额 |
| 分析触发 | 同步数据默认仅存储/索引并供 Agent 使用，不自动触发推理；自动化需求完全由用户通过 Agent 自定义 |
| 授权模型 | 风险分级；本机策略裁决实时采集/设备操作，Bridge 独立裁决已存副本查询 |
| 电话 | 仅通话记录和当前通话状态元数据，不含通话控制、录音或转写 |
| 短信 | 可读；正式短信工具发送前必须逐条确认，任何正常或增强后端都不能绕过 |
| 联系人 | 只读 |
| 日历与闹钟 | 可读；创建或修改前必须确认 |
| 屏幕内容 | 无障碍语义树与像素画面分开授权 |
| 传感器 | 快照、低频聚合或限时连续流，按传感器配置 |
| 后台命令 | 类型化动作优先，restricted shell 逐次确认；不向 Agent 暴露任意 Root Shell、脚本或通用命令解释器 |
| 默认数字助手 | 系统手势/按键唤起紧凑面板；文字、按住说话、流式回复、图片和文件 |
| 数据保留 | Agent 原始数据按来源到期；长期记忆使用独立策略并可查看、删除 |
| 模型 API | 采用零保留配置，只做瞬时推理，不形成提供商侧持久副本 |

## 3. 目标与非目标

### 3.1 产品目标

1. 为 Android 手机和自托管 Agent 提供统一、可版本化的双向能力协议。
2. 让用户逐项控制数据源、字段、同步模式、主动读取、写入、Shell 和屏幕控制权限。
3. 在一个 Agent 部署管理多位用户时，隔离设备、数据、会话、长期记忆、密钥和审计记录。
4. 以标准 Android API 为首选，仅在用户明确启用时使用 Device Owner、Shizuku 或类型化 Root 后端。
5. 为 Hermes 和 OpenClaw 提供语义一致的 tools、events、plugin 和 skill。
6. 把 Android 平台限制表达为能力状态和标准错误，不伪造成功或静默提权。
7. 提供可立即生效且不依赖网络的暂停、撤销和紧急停止能力。
8. 在不占用 Android 系统 VPN 通道的前提下，只为本 App 到 Bridge 的流量提供加密 Tailnet 连接，并允许其他 VPN 继续占用系统 VPN 槽位。

### 3.2 非目标

- 不保证 App 被强制停止、设备关机、Doze 或网络断开时能够即时响应。
- 不实现持续监听的自定义语音唤醒词。
- 不录制蜂窝通话音频，不做通话转写，不远程接听、挂断或拨号。
- 不提供专门提取密码、OTP、支付凭据或生物识别内容的工具。平台明确保护的 DRM/`FLAG_SECURE` surface 不捕获；对未受平台保护的非结构化文本或像素只能尽力在本机识别和阻断，不能承诺零误收。
- 不承诺持续后台读取全局剪贴板、枚举所有应用闹钟或完整理解所有第三方 UI。
- 不允许 Agent 远程启用 Root、Shizuku、无障碍、通知访问、MediaProjection 或默认助手角色。
- 不向 Agent 暴露任意 Root Shell、任意脚本、shell 字符串或通用命令解释器；Root 仅可作为类型化动作的受控后端。
- 不让 Agent 静默浏览手机文件系统；图片和文件必须由用户通过系统选择器明确选择。
- 不为其他 App 或系统流量提供 VPN、出口节点、子网路由、SOCKS5/HTTP 代理或通用 Tailnet 拨号服务；不依赖另行安装的 Tailscale Android VPN 客户端。
- 不承诺与每一种第三方 VPN 都能联网；本 App 不占用系统 VPN 槽位，但已激活的其他 VPN 仍可能阻断其底层 UDP/HTTPS 流量。
- 不以 Google Play 政策合规作为首版验收目标。若未来上架，需要单独削减或重构 SMS、Call Log、Accessibility 和远程执行能力。
- 不在本规格中实现 iOS、桌面端管理后台或托管云服务。

## 4. 系统范围与总体架构

```text
多位用户的 Android 14+ 手机
  ├─ 助手/对话界面
  ├─ 本机授权与风险策略引擎
  ├─ 数据采集器与能力适配层
  │    ├─ 标准 Android API
  │    ├─ Device Owner
  │    ├─ Shizuku/ADB
  │    └─ 类型化 Root actions
  ├─ 加密缓冲、临时附件、审计
  └─ 出站 transport
       ├─ 内嵌 Tailscale userspace core（Go → Android AAR）
       │    └─ 仅 App→Bridge 的私有 WSS/HTTPS；direct 或 DERP
       └─ 用户手动启用的公网 HTTPS 备用
             │
       Agent Device Bridge
  ├─ 租户/principal/设备注册与密钥
  ├─ RPC、事件、离线请求与幂等
  ├─ 按用户隔离的数据存储与保留期
  ├─ 审批转发与审计
  └─ Agent 适配器
       ├─ Hermes：plugin / platform adapter / MCP / webhook
       └─ OpenClaw：plugin / Gateway adapter
             │
       Hermes 或 OpenClaw Agent
             │
       本地或远程模型提供方
```

### 4.1 核心边界

- 手机不开放应用层入站服务，只主动连接 Bridge；Tailscale 为直连建立的底层 UDP socket 不得承载任何 App listener。
- 只有本 App 发往当前已配对 Bridge 的 WSS/HTTPS 和 artifact 流量可以进入内嵌 Tailnet；其他 App 和系统流量不经过该节点。
- 本机策略引擎是实时读取和设备操作的最终授权点；已上传副本由 Bridge 的独立查询授权控制。
- Bridge 不解释用户自动化需求，也不运行自己的规则 DSL。
- Agent backend 可以使用自身的对话、任务、记忆、定时器或自动化系统处理同步数据。
- Bridge 可以安装 Hermes 与 OpenClaw 适配器，但一个 deployment 中每种 backend 的命令与事件入口只能有一个权威路径；任何 Agent principal 都必须绑定明确的租户、用户、Agent 实例和 scope。
- 首版每个 App 安装实例只保持一个活动的应用设备配对（一个 `device_id` / `pairing_generation`）；该配对可以绑定默认 Tailnet transport profile 与一个可选公网 transport profile，但任一时刻只能有一个活动 transport connection。一个 Bridge 可以管理多用户、多设备。

### 4.2 信任边界

系统包含以下独立信任边界：

1. Android OS 权限与 App 沙箱。
2. 主 App Kotlin/Java 代码与内嵌 Go/AAR/JNI `tailnet-core`。
3. App 标准进程与 Shizuku/类型化 Root broker。
4. 手机内嵌 Tailscale core、Tailscale coordination/DERP 与公网网络；Tailscale 处理节点和连通性元数据，但不能替代应用层身份、授权或内容保护。
5. Device Bridge 与 Agent backend。
6. Agent 模型输出与确定性的命令策略。
7. 每位用户的数据、会话和长期记忆。
8. 应用管理员与 Agent 主机操作员。
9. Agent 调用的本地模型或满足零保留契约的远程瞬时推理提供方；它是明文处理边界，但不是持久存储边界。

应用层管理员只能管理设备状态、缩小服务端 scope ceiling 和撤销配对，不能通过产品 API 读取用户内容、扩大 scope 或替用户授权。Agent 主机的 OS root 管理员能够接触进程内解密数据，属于受信基础设施；保护数据不受主机 root 读取需要可信执行环境或端到端隐私计算，超出本项目范围。配对时必须向用户说明当前模型是在本地运行还是会把获准数据发送给远程模型提供方，并显示当前零保留 profile/契约 revision。

本项目部署前提是模型 API 的请求、响应、附件和 tool payload 只用于瞬时推理，不进入提供方日志、训练、人工审核、持久缓存或备份；响应结束后提供方不存在需要删除的副本。这是本部署对所选 API/profile 的契约与配置要求，不是对所有模型提供商的普遍断言。Bridge/adapter 在启动和配置变化时校验并记录零保留证据 revision；状态缺失、未知或不再满足时立即停止手机正文外发。

## 5. Android App 组件

### 5.1 模块边界

| 模块 | 职责 | 依赖 |
|---|---|---|
| `assistant-holder` | 独立 APK/UID 的 `VoiceInteractionService`/session，仅负责系统入口与轻量 IPC | 签名权限 IPC；不声明网络、SMS、Call Log、Notification Listener、Accessibility 或增强后端权限 |
| `assistant-ui` | 主 App 内的文字/语音会话、附件和流式回复 | transport、policy-engine |
| `policy-engine` | scope、风险等级、授权版本、限时会话、紧急停止 | encrypted-store、系统认证 |
| `tailnet-core` | App-scoped Tailscale userspace 节点、节点注册、状态与受限 Bridge client transport；通过 gomobile/JNI 打包为 AAR | 固定版本的 Tailscale Go 源码、加密 `StateStore`；不使用 `VpnService` 或系统 TUN |
| `transport` | 配对、设备身份、WSS/HTTPS、签名、重连、协议状态机；只调用 `tailnet-core` 的 Bridge 专用接口或显式启用的公网 HTTPS 路径 | Android Keystore、`tailnet-core`、Android 网络栈 |
| `encrypted-store` | 设置、待同步队列、临时附件、本机审计 | Android Keystore、App 私有存储 |
| `collectors` | 各数据源的采集、过滤、标准化与游标 | Android content/service APIs |
| `accessibility-service` | 活动窗口、语义树和已授权 UI 操作 | AccessibilityService |
| `projection-service` | 用户批准会话内的屏幕捕获 | MediaProjection、前台服务 |
| `dpc` | 独立 APK/UID 的 fully managed Device Owner 能力 | 签名权限 IPC；不声明网络 |
| `capability-backends` | 标准 API、DPC、Shizuku 与类型化 Root 动作 | 各自系统服务或 broker |

模块必须通过稳定接口通信。Collector 不直接发网络请求；transport 不直接调用 Android Provider；Agent 命令必须先经过 policy-engine，再进入具体 capability backend。只有主 App 的 `transport` 模块可以调用 `tailnet-core`；`assistant-holder`、DPC、增强后端 companion、collector 和其他 App 均不能调用。`tailnet-core` 不向 Kotlin、其他 APK 或 loopback socket 暴露通用 `Dial`、`Listen`、SOCKS5、HTTP proxy、LocalAPI、Funnel 或 Web 管理入口；其应用数据目标由当前配对记录固定为唯一 Bridge endpoint 和 TCP 443，底层仅额外访问 Tailscale control、discovery/STUN 与 DERP 等建链基础设施。

### 5.2 分层能力解析

各增强后端能力彼此正交，并不构成严格的权限高低链。每次操作先尝试标准 API，再由能力解析器在用户已启用的后端中选择风险最低且声明支持该能力的一项：

```text
标准 Android API
   ↓ 能力不足
能力解析器
   ├─ Device Owner：只处理公开 DPM/DPC 能力
   ├─ Shizuku/ADB：只处理已授权的 shell/Binder 能力
   └─ 类型化 Root：只处理签名版本内置且本机明确允许的动作
```

要求：

- 标准 API 失败不能触发静默升级。
- Device Owner 使用独立 APK/UID 的 DPC 和 fully managed provisioning，通常要求新机或恢复出厂；Profile Owner 不等价，不能假定普通安装后即可获得。
- Shizuku 授权、服务停止和重启失效必须实时反映到 capability manifest。
- Shizuku restricted-command broker 与类型化 Root broker 使用独立 UID 的 companion 或等价隔离边界，暴露彼此独立的最小 IPC，并且 APK 不声明 `INTERNET`。类型化 Root broker 不提供 generic exec/shell/script 或动态 handler 接口；仅使用同 UID 的 `android:process` 分进程不满足隔离要求，网络隔离还必须通过 OS 级策略实测。
- Agent 协议只能调用类型化 broker 方法或版本化 restricted-command 模板；不接受完整 Shell 字符串、解释器、脚本路径、命令替换、环境注入或 PATH 查找。
- OEM、隐藏 API、shell UID 和类型化 Root handler 行为只作为尽力而为能力，不进入跨设备兼容承诺。

Device Owner 可以管理部分 runtime permission，但不是 system UID 或 Root，也不能通过公开 API 静默启用 Notification Listener、Accessibility、Usage Access、MediaProjection 或 Assistant Role。[DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)

## 6. 设备身份、配对与网络

### 6.1 配对

1. 用户先登录目标 Agent 的可信 Web/CLI 会话。Bridge 从该已认证会话签发一个单次使用、5 分钟有效的 opaque enrollment ticket；服务端记录其 `tenant_id`、`human_principal_id`、`agent_instance_id`、enrollment scope ceiling、随机挑战和 Bridge 身份。App 不能提交、选择或覆盖这些身份字段。
2. 生成 Tailnet 配对码前，Bridge enrollment issuer 使用只保存在服务端秘密存储中的 Tailscale trust credential 创建节点注册 auth key。issuer credential 只授予 deployment-specific 手机 tag 所需的 `auth_keys`；节点回收使用另一个绑定同一 tag、仅授予 `devices:core` 的 lifecycle credential。完整 policy 校验再使用第三个独立 verifier credential，只授予 `policy_file:read` 及 Tailscale 对该 scope 明确要求的 `devices:posture_attributes:read`、`devices:core:read`，不得授予任何写 scope。三套 credential 不得互相复用，也不得获得 `all` 或 `all:read`；运维必须知晓 lifecycle credential 可管理匹配节点，而 verifier 可读取 tailnet-wide policy、设备与 posture 元数据，二者都是独立部署信任边界。auth key 必须为一次性、不可复用、非 ephemeral，并请求不晚于 enrollment ticket 的 5 分钟过期时间；Bridge 必须检查 Tailscale API 实际返回的 expiry，任何超出窗口或无法确认的结果都立即撤销并 fail closed。Bridge 记录 key ID，但不记录明文 key。[Tailscale trust credentials](https://tailscale.com/docs/reference/trust-credentials)、[安全处理 auth key](https://tailscale.com/docs/features/access-control/auth-keys/how-to/secure-auth-keys)
3. 二维码包含 Tailscale control URL/profile、私有 Bridge endpoint、一次性 auth key、key ID、专用 tag、ticket、Bridge 标识、挑战和服务端公钥指纹，并显示可人工核对短码；不包含 OAuth client secret、长期管理员密钥或 Hermes/OpenClaw 凭据。二维码整体按秘密处理，不进入日志、剪贴板、备份或 analytics；成功、失败或超时后 Bridge 立即撤销尚未消费的 auth key。每个 principal/IP 在 10 分钟内最多尝试配对 5 次，成功或失败消耗 ticket。
4. App 在 Android Keystore 生成不可导出的安装实例签名密钥；优先使用硬件或 StrongBox 支持。App 只把一次性 auth key 以进程内参数交给 `tailnet-core`，不得写入磁盘、崩溃报告或网络日志；节点注册成功或失败后立即清除该参数。[Android Keystore](https://developer.android.com/privacy-and-security/keystore)
5. `tailnet-core` 注册一个与本次 App 安装/配对一一对应的持久节点，并把后续重连所需状态写入 Keystore-wrapped、App-private、禁止 Android Backup 的 `StateStore`。auth key 本身不持久化；本机 state 丢失、失效或被清除时必须重新生成配对码，不得下发可复用 key。
6. 默认 Device Approval profile 下，auth key 不预批准，App 明确显示 `waiting_tailnet_approval`，待管理员批准后才能继续；若部署显式选择自动批准，`preauthorized=true` 只能由 Bridge 的服务端策略设置、记录和披露，App/二维码字段不能覆盖。Tailnet Lock 与 Device Approval 互斥；Lock profile 必须由非 Android trusted signing node 预签新 auth key后才能生成可立即使用的二维码，或在节点注册后人工签 node key。Tailnet Lock 私钥不得放入普通 Bridge/issuer 进程；如自动化预签，必须使用隔离 signer、独立批准和审计。Bridge OAuth API、Android App 和 Agent 均不能自行完成或绕过该签名。[Device Approval](https://tailscale.com/docs/features/access-control/device-management/device-approval)、[Tailnet Lock](https://tailscale.com/docs/features/tailnet-lock)
7. Tailnet 节点可运行后，App 通过内嵌 userspace dialer 连接私有 Bridge endpoint。App 与 Bridge 互相签名挑战，并在两端显示相同短码。Bridge 只从 ticket 派生用户归属，并创建不可复用的 `device_id`；Tailnet node ID/tag 只记录为附加风控绑定。
8. Bridge 将设备公钥绑定到 ticket 中的 tenant、human principal、Agent 实例和 scope ceiling；App 固定 Bridge command-signing 公钥。
9. 配对完成后，App→Bridge 由设备安装密钥签名，Bridge→App 由 Bridge command-signing key 签名，Agent adapter→Bridge 使用独立的服务端凭据。Hermes/OpenClaw 的 owner token、API key 或密码不得保存到手机。
10. 上述三类应用凭据各自拥有 `key_id`、序列、轮换、撤销和审计状态；Tailscale OAuth credential、一次性 auth key 与节点状态属于独立的网络凭据生命周期，不能替代应用凭据。auth key 已消费但应用层配对未完成的节点是孤儿节点，Bridge 必须在 ticket 到期后 10 分钟内回收；已完成配对但连续 30 天没有任何有效应用层 heartbeat 的离线节点也由 Bridge 撤销，设备再次使用时需重新配对。
11. 撤销配对同时终止应用会话、推进设备授权纪元、使旧请求/批准/通道票据失效、本机删除 `StateStore`，并由 Bridge 尽快撤销对应的 Tailnet 节点；任一侧失败必须显示 pending 状态而不能伪装完成。

### 6.2 App-scoped Tailscale 默认链路

- Android App 内嵌一个小型 Go 网络核心，首选基于固定 commit/version 的 `tailscale.com/tsnet`，通过 gomobile/JNI 封装为 AAR；如直接绑定 `tsnet.Server` 在 Android 上不可行，只允许在同一固定 Tailscale 源码上封装语义等价的 userspace netstack。`tsnet` 在进程内运行用户态 TCP/IP 栈并提供 Tailnet `Dial`，无需系统级网络配置。[tsnet](https://tailscale.com/docs/features/tsnet)、[tsnet source](https://github.com/tailscale/tailscale/blob/main/tsnet/tsnet.go)
- Android manifest 和运行时均不得声明、绑定或启动 `VpnService`，不得创建系统/内核 TUN，不修改 Android 路由表、系统 DNS、Private DNS 或其他 App 的 socket。实现把 `Tun` 保持为 nil/等价 userspace 模式，`RunWebClient=false`，且不调用或暴露 `Listen`、`Loopback`、LocalAPI、SOCKS5/HTTP proxy、Serve/Funnel、exit node 或 subnet-router 路径。
- Kotlin 侧只能调用版本化的 `startEnrollment`、`connectBridge`、`sendControl`、`transferArtifact`、`observeStatus`、`close` 等高层接口；任意主机、端口、协议、脚本或 raw socket 参数均不进入公共 IPC/API。目标 endpoint 必须与签名 enrollment ticket 和当前 pairing generation 完全相符。每个异步调用必须可取消；消息、附件与实时流使用有界队列和背压；`close` 必须串行化并发调用、拒绝新工作、取消未决 I/O，并返回确定的最终连接状态。
- Agent 主机通过 Tailscale Serve 暴露私有 HTTPS/WSS，Bridge 本身只监听 localhost。Tailnet 数据链路使用 WireGuard 端到端加密；DERP 只中继加密包，不能解密应用 payload。[Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)
- Tailnet policy 为每个 Bridge deployment 使用独立的手机与 Bridge identity，例如 `tag:open-android-intelligence-mobile-<deployment-id>` → `tag:open-android-intelligence-bridge-<deployment-id>` TCP 443；不得使用会让所有手机访问所有 Bridge 的全局共用目的 tag，也不得有其他 ACL/grant 通过同一 tag、autogroup 或更宽来源重新授予无关节点/端口。规则只允许手机发起到 Bridge，不允许 Bridge 发起到手机。因为 Tailscale allow 规则可叠加，隔离的 policy verifier 必须从 Tailscale 当前生效的完整 policy 读取并验证，而不只检查项目片段；它为规范化 policy digest、Tailnet、deployment、允许路径、Tailscale policy revision、签发/过期时间生成签名 attestation。Bridge 只固定 verifier 公钥，不持有 verifier credential；Tailnet subsystem 启动、enrollment、建立新 Tailnet connection 及 attestation 到期前均需校验。缺失、过期、revision/digest 不匹配或 verifier 不可用时只有 Tailnet 路径 fail closed，并关闭无法续期的 Tailnet 应用会话；已绑定公网 profile 仍只能由用户手动选择，不得自动接管。[Tailscale grants](https://tailscale.com/docs/reference/syntax/grants)、[policy read scope](https://tailscale.com/docs/reference/trust-credentials)
- 通过 Bridge OAuth client/auth key 创建的手机节点是 tag-owned service node，而不是带 Tailscale 人类用户身份的终端；此取舍必须在部署时披露。Tailscale node ID、tag、IP、MagicDNS 名称和 Serve 注入头只作为额外风控上下文，不能替代 App 安装密钥、enrollment ticket 或 human principal 绑定。
- Tailscale 优先建立点对点直连；NAT/网络条件不满足时允许经 DERP relay。App 展示 `direct`、`relay`、`control_unreachable`、`approval_required` 等真实状态；direct/relay 只能来自已验证可用的 Tailscale backend/status 信号，不得从一次请求成功或延迟特征推断。direct 与 DERP 使用同一应用层认证和授权，不能把 relay 伪装成故障，也不能把无法判断的路径报告为 direct。[Connection types](https://tailscale.com/docs/reference/connection-types)
- 默认使用 Device Approval；高保证部署可改用 Tailnet Lock。二者互斥，部署必须选择其一；Android 手机不能充当 Tailnet Lock signing node，启用时至少准备两个非 Android signing node 和离线恢复材料。该选择不影响应用层配对。
- 节点状态只保存在 6.1 指定的加密 `StateStore`，每次安装/有效配对只有一个节点身份。Tailscale 依赖、Go toolchain、gomobile、NDK、ABI、license/SBOM 与源码校验和必须锁定并通过供应链审阅；升级需重跑互操作和安全测试。
- 内嵌 core 默认选择 Tailscale 的 no-logs/no-support 模式，后端调试 logger 为空或只进入本机脱敏环形缓冲；不得向 Tailscale 日志服务或本机日志写入 auth key、endpoint ticket、应用 envelope、headers、正文或附件。Tailscale control plane 仍会处理建立 Tailnet 所需的节点与连通性元数据，配对时必须披露。[Tailscale logging](https://tailscale.com/docs/features/logging)
- 该设计不占用 Android 系统 VPN 槽位，因此另一个系统 VPN 可以保持活动；但另一个 VPN 的全隧道路由、DNS、封锁策略或 lockdown 仍可能阻断 Tailscale control、STUN、UDP 或 DERP HTTPS。产品只承诺“不创建系统 VPN”，不承诺绕过第三方 VPN；失败时返回真实网络状态。[Tailscale 与其他 VPN](https://tailscale.com/docs/reference/faq/other-vpns)
- 同一手机安装的官方 Tailscale App 与本产品内嵌 core 是两个独立节点；前者可继续作为系统 VPN 运行，不能共享或接管本产品的 `StateStore`、node ID 或应用层 pairing。
- 官方 `tsnet` 是 Go 库，官方 Android 客户端目前仍以 `VpnService` 为主，Android 客户端的 userspace 模式仍是未完成的功能请求。因此该 AAR 是本项目的定制集成，P0t 必须先作为可行性门槛；门槛失败时暂停并重新评审，不得静默退回系统 VPN。[Tailscale Android](https://github.com/tailscale/tailscale-android)、[Android userspace feature request](https://github.com/tailscale/tailscale/issues/10126)

### 6.3 HTTPS 备用链路

- 首次设置默认只引导内嵌 Tailscale；用户进入“高级连接”并明确确认公网风险后，才能手动添加公网 HTTPS endpoint，并通过 5 分钟单次 `transport_profile_ticket` 完成独立的公网 transport profile 挑战与绑定。该 profile 绑定当前 `device_id`、设备公钥和 `pairing_generation`，不创建第二个应用设备配对、不更换设备密钥，也不递增 `pairing_generation`；该路径使用 Android 普通网络栈，不经过 `tailnet-core`。
- 使用标准 TLS、双向应用消息签名、5 分钟单次挑战、配对限流和可撤销 transport profile binding；endpoint 与证书身份变更必须重新完成该 profile 挑战。
- 不因 Tailscale 暂时不可用、等待设备批准、受第三方 VPN 阻断或 userspace core 故障而自动降级到公网地址；Tailnet auth key、node state 和 OAuth credential 永不发送到公网 Bridge endpoint。
- 用户手动切换 Tailnet 与已绑定公网 profile 时，先关闭旧控制/数据通道，再由 Bridge 分配新的 `connection_generation` 并 fence 旧路径；首版不允许两个 transport profile 同时保持活动控制连接，也不因 Tailnet 故障对公网 endpoint 发起 DNS/TCP 探测。
- 公网入口不得直接暴露 Hermes/OpenClaw 管理 API。

## 7. 身份、授权域与多用户隔离

“一个 Agent”在本规格中指一个由同一运维边界管理、向用户呈现为一个服务的逻辑部署，不要求所有用户共享同一进程、workspace、模型上下文或凭据。为满足隔离，部署可为每位用户建立独立 workspace、worker 或 Agent 实例；这些内部拆分仍属于同一逻辑服务。

### 7.1 规范实体

| 实体 | 含义与绑定 |
|---|---|
| `tenant_id` | 最高数据隔离域；不能由手机或模型选择 |
| `human_principal_id` | 已登录并拥有手机授权的自然人；由 Agent 身份系统认证 |
| `agent_principal_id` | 发起工具调用的服务身份；服务端绑定 tenant、Agent 实例和 scope ceiling |
| `agent_instance_id` / `workspace_id` | Hermes/OpenClaw 的隔离运行域；会话、任务、工具 trace 与记忆均归属于它 |
| `device_id` | 一次 App 安装与一次有效配对创建的设备身份；归属于一个 human principal |
| `tailnet_node_id` / `tailnet_tag` | Bridge 从注册记录和连接上下文取得的网络身份；只绑定一个 active pairing，不产生 human/Agent 授权，同一 tag 下仍依赖应用层 principal 隔离 |
| `transport_profile_id` / `transport_mode` | 绑定当前 `device_id` / `pairing_generation` 的 `TAILNET_EMBEDDED` 或用户单独完成传输 profile 挑战的 `PUBLIC_HTTPS_MANUAL`；由实际 listener/连接上下文确定，客户端声明不能切换路径或产生授权 |
| `tailnet_policy_attestation_revision` | 隔离 verifier 对 Tailscale 当前生效完整 policy 的签名证明 revision/digest；只证明网络路径约束，不产生 human、Agent 或设备授权 |
| `session_id` / `job_id` | 对话或自动化任务上下文；不能改变其绑定 principal |
| `operation_id` | 一个逻辑设备请求的稳定身份；与传输重试分离 |

数据、请求、命令、附件、审批、会话、任务、记忆和审计记录必须携带其适用的服务端绑定身份。并非所有记录都必须有设备字段，例如纯 Agent 会话可以没有 `device_id`；一旦引用手机数据或能力，则必须绑定来源设备。客户端或模型提供的 ID 只可作为一致性声明，不能产生授权。

### 7.2 有效授权判定

所有授权均采用拒绝优先，但“实时设备访问”和“查询 Bridge 已存副本”是两个独立判定域。

实时采集、上传或设备操作的允许条件为：

`allow = active_pairing ∧ tenant/principal/device/session binding 一致 ∧ capability ∈ agent_scope_ceiling ∩ enrollment_scope_ceiling ∩ local_consent ∧ system_permission ∧ backend_available ∧ authorization_revision 匹配`

查询 Bridge 已存副本的允许条件为：

`stored_query_allow = tenant/human/agent/workspace binding 一致 ∧ capability ∈ agent_scope_ceiling ∧ current data_query_grant 有效 ∧ record lineage 属于允许的 user/device/source/filter ∧ record 未过期 ∧ record 未被 tombstone 删除`

当前 Android permission、backend availability、本机 `authorization_epoch` 不参与历史副本查询。`data_query_grant` 以 `tenant_id + human_principal_id + device_id + capability + filter_hash` 为 key，拥有独立单调 `grant_revision`；只有目标 human principal 可通过已认证 Agent 会话或设备签名消息签发/扩大，管理员只能缩小或撤销。App 离线修改只有在 Bridge 签名 ACK 后才改变服务端授权，UI 按 9.3 显示待同步。

扩大 enrollment scope 必须由目标 human principal 重新配对或通过等价的可信用户流程完成。若 Hermes/OpenClaw 不能向 adapter 传播可信 principal，adapter 必须 fail closed；部署必须改为每用户独立实例和凭据，不能用模型传入的 `user_id` 补救。

### 7.3 隔离要求

- 每位用户拥有独立设备集合、加密数据分区、保留策略、会话、任务、tool trace、adapter 缓存、向量索引、长期记忆、密钥和审计视图。
- Agent adapter 从已认证会话取得 principal，只允许从该 principal 的已授权设备集合选择设备；自由文本不能切换用户。
- 默认不存在跨用户检索或共享记忆。显式共享空间不在首版范围。
- 应用管理员可以查看设备在线、版本和错误状态，缩小 ceiling 或撤销设备，但不能读取同步正文或提高 scope。
- 一台设备的序列、授权 revision、审批凭据、通道票据和执行账本不能被另一台设备复用。
- 每次安装/有效配对使用独立 Tailnet `StateStore`，不得复制到另一设备、Android user/profile 或备份恢复。node ID、tag、control URL 或私有 Bridge endpoint 改变时不得静默继承配对，必须重新配对或使用后续子规格定义的受签名恢复流程。
- 同一逻辑服务中的两个用户必须可同时使用；端到端测试证明 prompt/context、tool result、事件、附件、会话、记忆和审计互不可见。

## 8. 数据源与 Android 平台边界

所有数据源默认关闭。用户逐项启用并选择字段、过滤条件、同步模式和保留期。新安装应用默认不进入通知允许列表。

### 8.1 平台能力

| 数据源 | 产品能力 | 公开 API 与授权 | 不承诺的边界 |
|---|---|---|---|
| 通知 | 按应用允许/拒绝；元数据或正文；事件上传 | 用户显式启用 `NotificationListenerService`，[官方 API](https://developer.android.com/reference/android/service/notification/NotificationListenerService) | Android 15+ 可能隐藏 OTP 等敏感内容；不能保证所有通知全文 |
| 短信 | 查询和同步；生成回复；确认后发送或交给系统短信 UI | `READ_SMS`/`SEND_SMS` 属于 hard-restricted：须由 installer-of-record 在安装时 allowlist、承担完整 SMS role，或由受控系统镜像预装；仅有 Device Owner runtime grant 不足。[Manifest permissions](https://developer.android.com/reference/android/Manifest.permission) | 默认 SMS role 要求实现完整短信 handler 并替换现有短信 App；本产品的独立 Assistant holder 不声明这些权限；普通路径使用 `ACTION_SENDTO smsto:` 交给系统 UI，不能把“已交接”报告成“已发送” |
| 电话 | 通话记录、来电/通话状态元数据 | `READ_PHONE_STATE`；`READ_CALL_LOG` 同样是 hard-restricted，[CallLog](https://developer.android.com/reference/android/provider/CallLog.Calls) | 不拨号、接听、挂断、录音、转写或返回录音 URI/控制句柄 |
| 联系人 | 搜索与读取 | `READ_CONTACTS` 或系统选择器，[Contacts Provider](https://developer.android.com/identity/providers/contacts-provider) | 只读；不跨 Android user/profile |
| 剪贴板 | App 前台读取和写入；增强后端尽力而为 | Android 10+ 普通 App 仅在获得焦点或作为默认 IME 时可读取，[隐私变化](https://developer.android.com/about/versions/10/privacy/changes) | 不保证持续后台监听全局剪贴板 |
| 位置 | 单次、事件或定期读取；粗略/精确独立 | 前台/后台位置权限与系统开关，[位置权限](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime) | 不绕过 approximate、后台限制、定位关闭或硬件不可用 |
| 健康 | 按 Health Connect 数据类型读取 | 每种数据类型独立授权；后台与历史访问分别检查 feature status 和权限，[Health Connect 读取](https://developer.android.com/health-and-fitness/health-connect/read-data) | 不写健康数据；work profile 不支持；默认历史窗口和 Health Connect 可用性受平台限制 |
| 传感器 | 快照、低频聚合、限时连续流 | Sensor APIs；活动/身体传感器按版本授权，[传感器概览](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) | 不保证设备存在某传感器；后台不能持续获得所有高频事件 |
| 日历 | 查询；确认后创建或修改事件 | Calendar Provider 或 Calendar Intent，[官方文档](https://developer.android.com/identity/providers/calendar-provider) | 不跨账号、user/profile 或 Provider 权限边界 |
| 闹钟 | 下一次闹钟元数据；确认后创建闹钟或修改 App 自己创建的闹钟 | `AlarmClock` Intent 或 App 自有 AlarmManager，[闹钟文档](https://developer.android.com/develop/background-work/services/alarms) | 不枚举或修改其他应用的完整闹钟数据库 |
| 当前窗口 | 前台应用推断、活动窗口、无障碍语义树 | Usage Access 或用户启用的 AccessibilityService；侧载 App 在 Android 13+ 可能需用户先在 App Info 允许 restricted settings，[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) | `isAccessibilityTool=false`；分屏不存在唯一前台应用；API 34+ 标为 accessibility-data-sensitive、自绘或敏感节点可能为空或不完整 |
| 屏幕画面 | 单应用或整屏截图、限时画面流 | 每次会话由用户批准 MediaProjection；API 34+ 先同意，再启动 `mediaProjection` 类型 FGS，再取得 projection；一个 token/实例只创建一次 VirtualDisplay，并注册 `onStop`，[官方文档](https://developer.android.com/media/grow/media-projection) | 平台保护的 `FLAG_SECURE`、DRM 和受保护 surface 不捕获；未保护画面可能包含敏感像素，本机语义识别只能尽力阻断 |

像素画面统一使用 MediaProjection，不利用 Accessibility 或 Assistant 截图能力规避逐会话提示。屏幕语义树和像素画面是两个独立 scope。

电话元数据使用封闭字段白名单：记录 ID、来电/去电/未接/拒接类型、开始时间、结束时间、时长、号码或已授权联系人标签、可选 subscription ID/标签，以及当前 `idle/ringing/offhook` 状态。schema 增加字段必须走安全审阅和协议版本升级，不能让 adapter 任意透传 Provider 列。

短信的受控直发路径仅在安装 allowlist/角色、runtime permission 和 App 本机策略全部满足时声明可用。否则 `sms.send` 只能返回 `system_ui_handoff` 并打开预填收件人与正文的系统短信 UI，由用户在系统 UI 最终发送；若设备没有处理器则返回 `PLATFORM_UNSUPPORTED`。

### 8.2 每能力规范矩阵

离线行为与风险等级相互独立：

- `WAIT_READ`：仅只读快照可等待设备上线，默认请求 TTL 15 分钟；结果必须附 `captured_at`、数据时间范围和是否来自本机缓存。
- `EVENT_QUEUE`：仅用户已启用的事件/定期同步进入数据队列，遵循来源 TTL 和 gap 规则。
- `FAIL_OFFLINE`：交互流和任何外部副作用在设备离线时立即终止，绝不作为“上线后执行”的任务。
- “限时会话可执行”不等于允许离线、批量批准或跳过不可降低的逐次确认。

| Scope / 动作 | 系统前提与允许后端 | 最低风险 | 离线行为 | 确认或会话规则 |
|---|---|---:|---|---|
| `device.status` | 标准 API | L0 | `WAIT_READ`，结果 5 分钟内有效 | 可持续预授权 |
| `notifications.metadata/content` | 用户启用 Notification Listener；增强后端不得绕过 | L1/L2 | 即时查询 `WAIT_READ`；事件 `EVENT_QUEUE` | 元数据 L1；正文 L2，按应用和字段授权 |
| `sms.read` | SMS role 或 installer allowlist + runtime permission | L2 | `WAIT_READ` / `EVENT_QUEUE` | 按号码、字段和时间范围显式授权，可要求逐次确认 |
| `sms.send` | 受控直发，或系统 `ACTION_SENDTO` fallback | L3 | `FAIL_OFFLINE` | 每条短信不可降低的本机确认；任何会话均不能豁免 |
| `calls.metadata` | Phone State；历史记录另需受限 Call Log 权限 | L1/L2 | `WAIT_READ` / `EVENT_QUEUE` | 仅当前 state 为 L1；号码和历史记录为 L2 |
| `contacts.read` | Contacts permission；选择器路径只读取用户当次选择 | L2 | 有 permission 时 `WAIT_READ`；选择器路径 `FAIL_OFFLINE` | 范围和字段持续授权，或前台逐次选择；只读 |
| `clipboard.read` | App 当前获焦点；增强后端仅在用户单独授权后尽力而为 | L1 | `FAIL_OFFLINE` | 结果只代表请求时刻，不承诺监听 |
| `clipboard.write` | 标准 API；不以 Shell 降级绕过策略 | L2 | `FAIL_OFFLINE` | 逐次或限时会话 |
| `location.coarse/precise` | 相应位置权限、系统开关；后台另行授权 | L1/L2 | `WAIT_READ` / `EVENT_QUEUE` | 粗略 L1、精确 L2；精度、频率和最长数据年龄单独配置 |
| `health.<type>.read` | 每类型 Health Connect 权限 | L2 | `WAIT_READ` / `EVENT_QUEUE` | 类型、时间范围显式授权，可要求逐次确认；只读 |
| `sensors.<type>.snapshot` | 传感器存在及相应 runtime permission | L1 | `WAIT_READ` | 返回采样窗口和精度 |
| `sensors.<type>.stream` | 对应 sensor FGS/可见通知 | L2 | `FAIL_OFFLINE` | 每个限时在线会话 |
| `calendar.read` | Calendar permission/provider | L1 | `WAIT_READ` / `EVENT_QUEUE` | 日历、字段和时间范围授权 |
| `calendar.write` | Provider 或系统 Intent | L3 | `FAIL_OFFLINE` | 每个创建/修改展示完整差异并确认 |
| `alarms.read_next` | 标准 next-alarm metadata | L1 | `WAIT_READ` | 只读 |
| `alarms.create/modify_owned` | `AlarmClock` Intent 或 App 自有 AlarmManager | L3 | `FAIL_OFFLINE` | 每次确认；不能修改其他 App 闹钟 |
| `window.metadata/tree` | Usage Access / Accessibility，用户系统设置启用 | L2 | `FAIL_OFFLINE` | 限时在线会话；tree 与像素分离 |
| `screen.view` | 每会话 MediaProjection 同意 + FGS | L2 | `FAIL_OFFLINE` | 单应用即时查看；可见限时会话，断线不自动恢复 |
| `screen.stream` | 每会话 MediaProjection 同意 + FGS | L3 | `FAIL_OFFLINE` | 整屏或连续流；可见限时会话，断线不自动恢复 |
| `ui.control.typed` | Accessibility + 经审阅的 package/component/window/action adapter | L3 | `FAIL_OFFLINE` | 生物识别限时会话；版本化 allowlist 与高危动作逐次确认 |
| `ui.control.generic` | Accessibility 节点动作；显式 package/window allowlist | L3+ | `FAIL_OFFLINE` | 单独风险开关和限时会话；不提供任意第三方 L4 语义保证 |
| `shell.restricted` | 版本化 command template；标准/Shizuku/ADB 后端 | L3 | `FAIL_OFFLINE` | 固定 executable + argv schema；精确参数逐次确认 |
| `root.actions.<typed-action>` | Root broker 的版本化类型化动作 | L3 | `FAIL_OFFLINE` | 不接受 shell/script；每个动作和参数逐次确认 |
| `device.notify` | 标准通知 API，用户可关闭 channel | L1 | `FAIL_OFFLINE` | 按 Agent/device 限流；不能伪装系统通知 |

任何 Calendar、Alarm 或 SMS 系统 Intent fallback 只返回 `system_ui_handoff`；除非后续能取得可信 Provider/系统结果，不报告外部副作用已经成功。

## 9. 同步、授权修订与数据生命周期

### 9.1 每来源同步模式

每个数据源独立选择一种或多种模式：

- `ON_DEMAND`：Agent 创建即时只读请求；仅矩阵标记 `WAIT_READ` 的能力可以在 Bridge 等待设备上线，默认最长 15 分钟。
- `EVENT_UPLOAD`：系统事件到达后写入本机数据队列并尽力上传。
- `PERIODIC_BATCH`：使用系统调度批量采集或上传，不承诺精确时间。

每个来源支持字段过滤、应用/对象过滤、频率、网络条件、本机 TTL、Bridge TTL、最大数据年龄和数据查询授权。连续屏幕/传感器流、交互操作和所有副作用不进入离线队列。

### 9.2 统一授权修订

实时设备授权只使用以下三层版本，避免“consent/stop/policy version”互相冲突：

- `pairing_generation`：每次新配对递增；旧设备密钥和所有旧对象永久失效。
- `authorization_epoch`：设备级单调计数。暂停、恢复、紧急停止、scope 撤销或重授、Android 系统权限变化、增强后端凭据变化、Bridge command key 变化都会递增。
- `scope_revision[capability]`：具体来源或动作策略变化时递增，用于细粒度失效。

`policy_version` 只用于 UI/cache 同步，不产生授权。operation、批准凭据、事件来源 epoch、artifact/stream ticket 和缓存结果都绑定 `pairing_generation + authorization_epoch + scope_revision`。Bridge 历史副本查询只使用 7.2 定义的独立 `data_query_grant.grant_revision`，不冒充设备 epoch。校验采用精确匹配和拒绝优先；“撤销后重授”不能让旧请求、旧批准或旧 ticket 复活。限时会话使用 Android 单调时钟；远程请求 TTL 由 Bridge 时间裁决并允许最多 60 秒时钟偏差。

Tailnet node ID、tag、control URL 或私有 Bridge endpoint 被替换时视为新应用设备配对并递增 `pairing_generation`。公网 endpoint/证书身份变化只使对应 transport profile binding 失效，必须在当前应用配对下重新完成 profile 挑战。用户在同一应用设备配对所绑定的两个 transport profile 间手动切换不提高授权、也不改变 `pairing_generation`，但必须递增 `connection_generation`、fence 旧 HTTP/WSS/artifact/stream 通道并终止不可恢复的实时流；不能把路径切换当作同一连接的透明重试。

### 9.3 撤销与删除语义

本机撤销即使离线也会立即停止后续采集和设备操作、清除相应未上传数据并推进 revision。它无法在离线状态下瞬间改变 Bridge 已保存副本，因而以下动作在产品中分开显示：

1. **停止来源/设备能力**：本机立即生效。
2. **撤销 Bridge 数据查询授权**：App 上报后，只有收到 Bridge 签名 ACK 才显示“服务端已生效”；离线期间显示“待同步撤销”。用户也可从已认证的 Agent 账户直接发起服务端撤销。
3. **删除 Bridge 原始数据与附件**：生成删除 job，返回 `pending/completed/partially_completed/failed` 和签名 receipt；Bridge 自有存储不允许 `unsupported`。
4. **删除 Agent 副本与记忆**：分别覆盖会话历史、任务输入、tool trace、adapter cache、搜索/向量索引和长期记忆；每个 backend 返回同样的完成状态。
5. **撤销 Tailnet 节点**：本机 `StateStore` 清除立即生效并阻止本机重连；服务端节点删除只有在 Bridge/Tailscale 返回成功后显示“已撤销”。离线或 API 不可用时显示“本机已清除 / 服务端撤销待完成”，用户可从已认证 Agent 会话继续完成远端撤销。

Bridge 收到查询授权撤销后立即阻断新查询；已上传密文可保留到删除 job 完成或 TTL 到期。删除 tombstone 必须阻止备份恢复、重建索引或迟到事件重新生成已删除内容。

Bridge 自有存储、Hermes/OpenClaw adapter 和被启用的 Agent 数据/记忆 backend 必须实现 TTL、tombstone 与删除，不能返回 `unsupported` 后仍宣称符合本规格。模型 API 按 4.2 的零保留部署前提不创建持久对象，因此不参加 deletion job，也不伪造 provider deletion receipt。`partially_completed/failed` 是真实 job 状态，但未最终完成前删除验收不通过。

### 9.4 默认存储与队列

| 数据类别 | 默认策略 |
|---|---|
| 手机同步数据队列 | 认证加密保存 7 天；全局 256 MiB |
| 手机安全执行账本 | 与数据队列物理/逻辑分区，预留 32 MiB；Bridge ACK 前不可清除 |
| Tailnet node state | 安装与 active pairing 专用的 Keystore-wrapped `StateStore`，位于 App-private no-backup 区域；不得导出、迁移或跨设备恢复；撤销配对时本机立即清除 |
| Tailnet enrollment auth key | 仅在扫码注册期间存在于进程内有界缓冲；成功、失败或超时立即清除，绝不持久化 |
| Bridge 原始数据 | 按来源使用独立 tenant/user 数据密钥加密，默认 30 天；可设不保存、立即删除或自定义期限 |
| 屏幕画面和连续传感器流 | App 与 Bridge 均不持久化原始帧；首版不提供录制 |
| 审计元数据 | 默认 90 天，只保存 allowlist 字段 |
| Agent 会话、任务、trace、cache 与索引 | 含来源正文的副本继承来源 lineage，默认不得晚于该来源 Bridge TTL 到期；只保留不含正文的审计元数据 |
| Agent 长期记忆 | 仅用户显式提升后写入独立存储；使用独立期限、查看和删除策略，不因普通会话自动延长 |
| 对话附件 | Bridge 默认 30 天；用户可提前删除或缩短期限；手机只保留到消息提交或失败过期 |

本机加密密钥由 Android Keystore 管理；数据库和附件排除出 Auto Backup、设备间迁移和跨平台迁移。[Android Auto Backup](https://developer.android.com/identity/data/autobackup)

任何来源内容进入对话历史、任务输入、tool trace、adapter cache、搜索/向量索引或派生摘要时都必须携带来源 lineage 与到期时间；除非用户明确把指定内容提升为长期记忆，否则派生副本的到期时间不得晚于最短上游来源 TTL。发送给零保留模型 API 的内容只在请求期间瞬时处理；模型响应或摘要返回后，若被 Agent 自有存储持久化，就重新成为受 lineage、TTL 和 tombstone 管理的 Agent 副本。

数据队列超限时可以按明确优先级丢弃最旧的低优先级传感器或通知事件，但必须写入 loss marker 并向用户显示。安全执行 claim、批准、result receipt 和必要审计使用独立账本；若副作用执行前无法可靠持久化 claim/审计，操作必须 fail closed 为 `SECURITY_LEDGER_FULL`。已被 Bridge 确认且超过 30 天的本机安全记录可压缩为检查点。

### 9.5 事件、游标与丢失恢复

每个来源事件包含：

~~~text
source_epoch
occurrence_id
record_key / record_revision
cursor
captured_at
event_kind: upsert | delete_tombstone | loss_marker
~~~

- `occurrence_id` 标识一次发生，`record_key/revision` 标识同一源对象的版本；两者不能混用。
- ACK 只推进同一 `source_epoch` 的最高连续 cursor。乱序事件先暂存，不能越过缺口确认。
- 队列清理或平台漏报时上传带范围与原因的 `loss_marker`；Agent 必须把该范围标记为不完整，并通过 collector 支持的 snapshot/resync 修复。
- 撤销后重新启用来源会创建新的 `source_epoch`；旧队列和旧 tombstone 不得混入。
- 投递是至少一次，Bridge 持久化后才签名 ACK；App 在收到 ACK 后删除数据队列项。

密码/OTP 专用字段和系统标记的敏感字段不会被有意采集或上传，专门的提取请求始终拒绝。通知、短信、无障碍树和屏幕像素属于非结构化输入，本机检测器会脱敏已识别的 OTP/凭据模式，但可能漏检；用户应默认排除认证器、密码管理器和金融 App。这是明确的残余风险，不使用绝对“绝不会读取”承诺。

### 9.6 Agent 自动化边界

- App 不创建、解释、保存或展示触发规则。
- Bridge 不提供规则 DSL 或规则引擎。
- 用户完全通过 Hermes/OpenClaw 自身的对话、任务、定时器、记忆或自动化机制描述需求。
- plugin 将同步数据和事件提供给 Agent，并把 Agent 产生的读取、修改、写入或操控命令转发给手机。
- 无论命令由手动对话还是 Agent 自动化产生，App 都重新执行 scope、风险等级、有效期和确认检查。
- Agent 端规则不能扩大手机权限。

通知、短信、网页、附件、无障碍树和屏幕 OCR 内容均标记为不可信输入。它们不能更改授权、配对、Agent 系统指令或本机策略。

## 10. 统一设备协议

### 10.1 传输面

- HTTPS：配对、管理、非实时查询、artifact 元数据与分片传输。
- WSS：签名控制 envelope、presence、请求状态、事件、ACK 和对话 token 流。
- 二进制 artifact/stream 通道：使用独立对象 ID 和短时 ticket；不能只靠 operation ID 复用 WSS 权限。
- 三个面在 `TAILNET_EMBEDDED` 下全部经过同一个 Bridge-only userspace dial abstraction，在 `PUBLIC_HTTPS_MANUAL` 下全部经过绑定当前应用设备配对的公网 profile；不能让不同面绕过当前 transport mode。它们共享相同的身份绑定、授权 revision 和审计语义，HTTPS/WSS 断线恢复不能改变 operation 身份。

所有敏感消息同时受 TLS 和应用层双向签名保护：App→Bridge 使用设备安装密钥；Bridge→App 使用固定并可轮换的 command-signing key；Agent adapter→Bridge 使用与 tenant、Agent 实例和 scope ceiling 绑定的独立凭据。adapter 无权访问设备私钥、Bridge command 私钥或核心数据库。

### 10.2 签名 envelope 与连接 fencing

控制协议是带判别字段的消息联合类型，不要求 hello、ping、主动事件携带并不存在的 operation 或 Agent session。所有已配对消息共有的签名 header 为：

```text
protocol_version / message_schema / message_type
message_id
device_id 或 adapter_credential_id
pairing_generation（设备消息）
key_id / direction / sequence
issued_at / expires_at
payload_digest
signature
```

- `connect_hello`：设备 ID、pairing generation、client nonce、支持版本、最后 manifest/event cursor；不含 Agent principal、operation 或尚未分配的新 connection generation。
- `connect_welcome`：Bridge nonce、选定版本、Bridge 时间、command key set 和本次新分配的 `connection_generation`。
- `operation_command/result/approval/cancel`：tenant、human/agent principal、Agent instance/workspace、session/job、device、operation、capability、parameters digest、connection generation、`authorization_epoch`以及相关 `scope_revisions` 显式映射；v1 不提供可替代该映射的 snapshot hash 编码。
- `device_event/event_ack`：device、source epoch、occurrence/cursor、采集时 revision 与 connection generation；主动设备事件不携带或选择 Agent principal，由 Bridge 按服务端 subscription 路由。
- `presence/ping/key_rotation/policy_update`：只携带其 schema 所需的身份、generation/revision 和 challenge，不伪造 operation/capability 字段。

配对前的 enrollment challenge/response 使用 6.1 的独立消息联合类型和 ticket 绑定，不复用已配对 control header。

- `operation_id` 是一个逻辑请求的稳定 ID。Agent 工具重试、Bridge 重投和 adapter 重启必须复用它；同一 ID 配不同 `parameters_digest` 返回 `IDEMPOTENCY_CONFLICT`。
- `message_id` 标识一次具体协议消息；每个传输方向和 key 使用独立单调 sequence。完全重复的已接收消息不再执行，只返回先前的签名 receipt。
- 新 WSS 连接认证成功时 Bridge 分配更高的 `connection_generation` 并 fence 旧连接；旧连接此后不能收命令或推进 ACK。
- App→Bridge 与 Bridge→App 使用独立 key ring、序列空间、轮换宽限期和撤销状态。结果/批准必须绑定原 operation、全部 principal、设备、参数摘要和授权 revision。
- 时间戳只用于过期，不能代替序列和持久账本。Bridge 是远程 TTL 的权威时钟，允许的客户端 wall-clock 偏差为 60 秒。
- Protocol 子规格必须在 P0a 出口前冻结规范化编码、签名算法、域分离、hash、最大 envelope 大小、版本协商/降级拒绝、key 轮换与恢复规则，并生成跨 Android/Bridge/adapter 的 golden vectors。

服务端从已认证连接和凭据取得真实身份；`transport_mode`、Tailnet node ID/tag 或公网 listener profile 由 Bridge 从实际连接上下文派生，不接受客户端自报。envelope 中的身份声明不产生授权。任一绑定不一致都返回 `AUTH_BINDING_MISMATCH` 并写安全审计。

### 10.3 Capability manifest

`connect/hello` 对每个 capability 分别报告：

```text
schema_supported
backend_available
backend_kind
system_permission
local_consent
agent_scope_effective
offline_policy / freshness_limit
constraints_and_limits
pairing_generation / authorization_epoch / scope_revisions
manifest_generation
```

“schema 支持”“后端当前可用”“Android 已授权”“用户本机同意”和“Agent 可调用”是五个不同状态。adapter 只能按其交集暴露工具，不得把 capability manifest 当作授权 token。

### 10.4 异步请求 API 与状态

plugin 必须提供 `mobile.requests.create/get/wait/cancel/reconcile`。具体能力工具只是 `create` 的类型安全包装。创建后立即返回 `operation_id` 和当前状态；`awaiting_approval`、`waiting_device` 是非终态状态，不是错误。adapter 持久保存“上游 tool-call ID → operation ID”，Agent 或 adapter 重启后仍复用原 operation。

请求状态至少包括：

```text
created
├─ waiting_device ──→ dispatching
│       └────────────→ expired
└─ dispatching ──────→ accepted_device
                         ├─ awaiting_approval ──→ approved
                         │          └───────────→ denied | expired
                         └─ approved
                              └─ executing
                                   ├─ succeeded
                                   ├─ failed
                                   └─ result_unknown

任一 execution_claim 前的非终态 ──→ cancelled | expired
```

- `accepted_device` 表示设备已校验身份/revision、把 operation 持久写入本机安全账本并签名 receipt，不只是 socket write 成功。
- 只有矩阵为 `WAIT_READ` 的读取可以进入 `waiting_device`。交互流和所有副作用离线立即以 `DEVICE_OFFLINE` 终止。
- terminal outcome 为 `succeeded/failed/denied/cancelled/expired/result_unknown`；协议错误是 envelope 本身无法接受，二者分开编码。`result_unknown` 是不可重试但可补充核对信息的终态：原始终态保持不变，迟到 receipt 或外部核对只追加 `reconciliation_outcome=succeeded|failed`、证据与时间，不能改写历史。
- `FAIL_OFFLINE` 一律终止为 `failed(reason=DEVICE_OFFLINE)`。首版设备锁定也一律终止为 `failed(reason=DEVICE_LOCKED)`，不引入隐式等待解锁。
- `cancel` 使用 compare-and-set：若执行 claim 尚未取得则变为 `cancelled`；若已执行则返回当前状态。取消不能回滚已完成副作用，也不能覆盖已证明的终态。

### 10.5 副作用幂等与执行账本

Bridge 在首次投递前持久化 operation、参数摘要和状态。设备对任何副作用执行以下顺序：

1. 校验全部身份、scope、revision、TTL、批准凭据和当前前台/解锁条件。
2. 以 `operation_id + parameters_digest` 原子写入 `execution_claim`；若账本不可写则 fail closed。
3. 调用一次外部副作用。
4. 持久写入枚举型 result 和最小证明字段，生成设备签名 receipt。
5. 重传 receipt 直到 Bridge 持久化并签名 ACK；ACK 丢失不使 Bridge 忘记已收到的结果。

如果设备在“副作用可能已经发生、但可信 result 尚未持久化”的窄崩溃窗口重启，状态为 `result_unknown`，不会自动再次调用外部副作用。系统承诺“同一 operation 不自动重复执行”，不声称跨 Android Provider、系统 UI 和类型化 Root handler 的绝对 exactly-once。`reconcile` 只使用可验证的外部状态或迟到 receipt，并追加独立 `reconciliation_outcome`，不让模型猜测成功或改写原终态。

### 10.6 批准凭据与反骚扰

设备批准凭据由安装密钥签名、单次使用，并绑定 tenant、human/agent principal、Agent session/job、device、operation、完整参数摘要、capability、授权 revision 和过期时间。参数、收件人、正文、目标 UI 或 backend 任一变化都需要新批准。

Bridge 和 App 强制而非仅靠 skill 实现：

- 每个 agent/device 默认每分钟最多 60 个已认证控制请求。
- 同一 agent/device 最多 3 个并发待批准请求。
- 等价待批请求折叠为同一个 operation。
- 用户拒绝后，相同 capability 与参数摘要冷却 10 分钟；自动化不能绕过。
- security 子规格可进一步降低限额，但不能放宽以上基线。

### 10.7 Artifacts 与实时流

附件、图片、屏幕和传感器流使用独立的 `artifact_id` 或 `stream_id`。Bridge 签发最长 5 分钟内用于建立通道的短时 ticket；公共绑定字段为 tenant、human/agent principal、Agent instance、session/job、device、operation、方向、capability、connection generation、授权 revision 和上限。ticket 不是 bearer token：打开通道还必须由 ticket 指定的设备安装密钥或 adapter credential 对 channel nonce 完成持有证明。

- artifact ticket 绑定 `artifact_id`、MIME、最终长度、预计算最终 digest、允许的起始 offset 和字节上限。App 在 `copied` 后先完成 hash，再申请上传 ticket。
- live stream ticket 绑定 `stream_id`、随机 `stream_nonce`、codec/config、chunk/帧/字节/时长/速率上限，不要求建立前存在最终 digest；chunk 的顺序与完整性由已认证通道、sequence 和每块认证信息保证。
- “单用途”指一个逻辑 artifact 或一次不可恢复的 live stream。附件断线恢复必须签发绑定同一 artifact、最终 digest 和 Bridge 已确认 offset 的新 resume ticket，不能复用旧 ticket。屏幕与传感器流不签发 resume ticket。

附件生命周期：

```text
selected → copied → uploading → uploaded → message_committed
    └──────────────→ cancelled | expired | deleted
```

- 首版最低互操作集合为 JPEG/PNG/WebP 图片与 PDF/`text/plain` 文件；最多 4 个附件、单文件 25 MiB、单消息合计 50 MiB。Bridge 可以声明更窄但不能宣称低于该集合仍通过 P3 验收。
- 分片有 `chunk_sequence` 和 chunk hash，commit 校验最终 SHA-256、总长度与 MIME。断线后只有取得新 resume ticket 才能从已确认 offset 恢复；partial upload、取消和孤儿对象在 24 小时内回收。
- 只有 artifact 与消息引用都持久化为 `message_committed` 后，App 才清理本机副本。仅 upload 成功但消息提交失败不能清理。
- 下载引用是 tenant/session scoped 且另发短时 ticket；跨会话或跨用户复用必须失败。

实时流状态为 `opening/accepted/streaming/closing/closed/error`，使用按 chunk sequence 的 credit-based backpressure、格式协商和有界内存。锁屏、用户停止、Android 回调、授权 revision 变化、网络断开或 ticket 撤销会关闭屏幕/传感器流并释放资源；不会离线缓冲，也不会自动恢复。聊天 token 流可从最后确认序号恢复，但不得把该语义套用到屏幕或传感器原始流。

## 11. 本机授权与命令模型

### 11.1 Scope

至少拆分以下 scope，不提供单一 `read_phone` 或 `control_all`：

```text
device.status
notifications.metadata / notifications.content
sms.read / sms.send
calls.metadata
contacts.read
clipboard.read / clipboard.write
location.coarse / location.precise
health.<data-type>.read
sensors.<sensor>.snapshot / stream
calendar.read / calendar.write
alarms.read_next / alarms.create / alarms.modify_owned
window.metadata / window.tree
screen.view / screen.stream
ui.control.typed / ui.control.generic
root.actions.<typed-action>
shell.restricted
device.notify
```

采集和传输是不同开关；读取元数据和读取正文是不同 scope；屏幕查看和控制是不同 scope。

### 11.2 风险、数据敏感度与后端修正

风险等级是动作的最低要求，用户可以设得更严格，不能调低。最终审批要求取动作风险、数据敏感度、用户覆盖和后端修正中的最严格者：

| 等级 | 示例 | 不可降低的基线 |
|---|---|---|
| L0 | 在线、电量、版本 | 可持续预授权 |
| L1 | 普通元数据、已启用来源的读取/同步 | 按来源、字段、用途持续授权，可改为逐次 |
| L2 | 短信/通知正文、联系人、健康、精确位置、窗口树、单应用画面、剪贴板写入 | 用户必须显式开启该敏感来源；可选逐次确认或限时会话 |
| L3 | 外部发送/写入、整屏或连续画面、无障碍控制、受限 Shell、类型化 Root 动作 | 展示精确参数；生物识别或设备凭据；除明确会话动作外逐次批准 |
| L4 | 主动提取/操作凭据或 OTP、绕过锁屏/系统确认、远程启用特权、关闭审计/紧急停止 | 类型化工具、restricted shell 与 `ui.control.typed` 始终拒绝 |

标准 API 为 `B0`。Shizuku、ADB shell 或类型化 Root backend 为 `B1`，除动作审批外还要求用户当前开启增强后端会话。风险分类不依赖模型输出或像素分类器。

默认屏幕查看/控制会话为 5 分钟，用户可延长，单次不超过 30 分钟。所有正式会话在锁屏、用户切换、紧急停止、网络身份变化、Android 权限或授权 revision 变化时立即结束。

即使在控制会话内，发送内容、安装/卸载、删除数据、账号变更和其他已识别语义高危动作仍需逐项确认。支付、凭据、权限授予和生物识别确认必须由用户亲自完成；类型化工具、`ui.control.typed`、restricted shell 和类型化 Root 不得注入输入、点击确认或成为替代路径。`ui.control.generic` 对任意第三方语义的不可识别边界按 12.6 明示。

### 11.3 不可降低的短信发送确认

每个 `sms.send` operation 都必须在手机端展示并确认：

- 发起 Agent 和会话/任务；
- 收件人号码或联系人；
- 完整正文；
- 使用的 SIM/subscription；
- operation ID、直发或 system UI handoff 路径及到期时间。

批准 token 绑定以上全部参数。任何字符、收件人、SIM、路径或 revision 变化都产生新 operation 和新确认。持续授权、批量批准、屏幕控制会话、自动化、restricted shell 和类型化 Root action 都不能跳过；重试复用原 operation，不能再次发送。system UI handoff 只报告“已打开系统短信界面”，除非平台提供可信结果，否则不报告“短信已发送”。Agent 不存在可绕过此路径的任意 Root Shell。

### 11.4 命令执行边界

Agent 只可调用两类命令能力：

1. `root.actions.<typed-action>`：Agent 只提交签名版本中已存在的 action ID 与该 action 的封闭字段；App 内置 handler 自行选择固定实现。Root 只是本机 backend，不改变 scope、风险或确认规则。
2. `shell.restricted`：Agent 只提交版本化 command template ID 与模板允许的封闭参数；App 内置模板生成固定 executable 与 argv，只能通过标准、Shizuku 或 ADB shell backend 执行。

两类能力都拒绝 Agent 提供 executable/argv/env/cwd/stdin、shell 字符串、`sh -c`、解释器、脚本正文/路径、命令替换、管道、重定向、环境注入、PATH 查找、动态下载、后台化和动态 action 注册；未知 action/template 或多余字段 fail closed。每个动作在副作用前持久化 execution claim，绑定精确 schema 参数并逐次确认。新增类型化 Root action 只能随签名产品版本和协议 schema 升级发布。App、Bridge、plugin、skill 和 broker IPC 均不定义或暴露 `shell.root`、generic exec、通用 terminal 或等价逃生入口。

### 11.5 紧急停止

App 提供两个本机动作：

- **暂停全部**：推进 `authorization_epoch`，停止采集、同步、重连并关闭 `tailnet-core`、MediaProjection、无障碍注入，拒绝新命令并尽力终止由 App 直接管理的子进程；重启后保持暂停。不能回滚已经发生的外部副作用，也不等于删除服务端 Tailnet 节点。
- **撤销并清除**：在暂停基础上撤销 Bridge 配对、清除本机队列、临时附件和 Tailnet `StateStore`，使设备密钥失效，并提交服务端 Tailnet 节点撤销；远端未完成时按 9.3 显示 pending。

屏幕会话期间，停止入口同时存在于持续通知和醒目浮层。远程管理员可以撤销设备，但不能在本机暂停后重新启用。

## 12. Android 用户体验

### 12.1 首次设置

```text
在已登录的目标用户会话生成包含一次性 Tailnet 注册凭据的配对码
→ App 内嵌节点加入 Tailnet（需要时等待 Device Approval/Tailnet Lock 签名）
→ 通过私有 Bridge endpoint 完成双向挑战并核对短码
→（仅“高级连接”可另行配置并将公网 HTTPS transport profile 绑定到当前设备配对）
→ 显示由 ticket 绑定的用户/Agent 身份与设备名称
→ 检测标准、Device Owner、Shizuku、类型化 Root actions
→ 逐项启用数据源和同步模式
→ 设置 Agent 主动读取与操作权限
→ 可选设为系统默认数字助手
```

每一步说明为何需要系统权限、会读取什么、是否会离开设备、Tailscale control plane 可见的节点/连通性元数据、当前模型提供方、零保留 profile revision 以及如何撤销。网络设置明确说明本 App 使用进程内 userspace 节点而非 Android 系统 VPN。App 不把“已检测到能力”显示成“已授权”。Accessibility 侧载场景明确引导用户先在 App Info 允许 restricted settings，再由用户进入系统设置启用；Agent、DPC 和类型化 Root 后端流程不代为点击。

### 12.2 主界面

- 当前 Bridge/Agent、当前路径（Tailnet 或用户启用的公网 HTTPS）、Tailnet 节点/批准状态、direct/DERP/不可判定状态、上次同步、待同步大小和最近错误；明确显示“未占用系统 VPN”，不得显示为 Android 系统 VPN。
- 数据源列表及其系统权限、采集状态、同步模式、过滤范围和保留期。
- 能力中心，分别展示标准 API、Device Owner、Shizuku 和类型化 Root 后端状态。
- 授权中心，分别管理主动读取、写入、Shell、屏幕查看和屏幕控制。
- 当前请求、审计历史、暂停全部和撤销配对。
- 分开显示“本机来源已停止”“Bridge 查询撤销待同步/已生效”“Bridge 删除”“Agent 副本/记忆删除”的进度与 receipt。

### 12.3 请求确认

确认界面显示：发起 Agent 与会话/任务、目标设备、读取范围或完整的类型化动作参数、选用 backend、风险等级、有效期、潜在影响及是否产生外部副作用。L3 使用 BiometricPrompt 或设备凭据。Agent 提供的“原因”可以展示，但被视为不可信说明，不能影响风险分类。短信确认遵循 11.3。

### 12.4 数字助手

- 主实现为独立 `assistant-holder` APK/UID 的 `VoiceInteractionService` 与 `VoiceInteractionSession`。AOSP 当前把 Assistant role 标为不可由普通 App 直接请求；产品引导用户进入 OEM 的“默认数字助理”设置，不依赖 `createRequestRoleIntent()` 弹窗。返回后必须由 holder 自己在 holder UID 内调用 `RoleManager.isRoleHeld(ROLE_ASSISTANT)`，并用 `VoiceInteractionService.isActiveService(holderComponent)` 确认实际组件；主 App 自己调用预期为 false，只通过绑定预期 package、UID、签名证书和 Android user 的签名权限 IPC 接收 holder 结果。[AOSP role 定义](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)、[VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
- 系统手势或按键唤起紧凑底部面板，支持文字、按住说话、流式回复和展开完整会话。
- 麦克风只在用户按住说话时采集；不做后台监听。
- Assistant role 可能向 role holder 授予其声明的 SMS permission set 和 `READ_CALL_LOG`，因此 holder 必须保持独立且不声明这些权限。即使 OEM 额外授予权限，主 App 的所有数据 scope 仍默认关闭。
- holder 的 `VoiceInteractionService` 在显示任何 session 前调用 `setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT)`；`VoiceInteractionSession` 不请求相应 show flags，并拒绝处理意外到达的 assist structure 或 screenshot。像素画面继续只走 MediaProjection。未来如需 Assistant context，必须新增独立 `assistant.context` scope 和子规格。[VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)、[VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession)
- 系统可常驻绑定 `VoiceInteractionService`，但 holder 不主动联网、录音、查询主 App 数据或维持唤醒锁；只有用户唤起后才通过最小签名权限 IPC 打开会话。
- 用户主动对话时可以临时使用前台连接。离线时明确显示失败和重试，不伪装成已发送。
- OEM 可能没有可用角色入口或不提供指定手势。此时降级为普通 Activity、快捷方式和分享入口，并明确标记“系统默认助手不可用”；参考设备上的系统唤起仍是 P3 必验项。

### 12.5 图片与文件

- 支持 Android Photo Picker、相机、Storage Access Framework 和系统分享菜单。
- 只访问用户本次选择的 URI，不申请广泛文件系统权限。
- 支持多附件、进度、取消、失败重试和流式回复。
- Bridge 在连接能力中声明文件数量、单文件大小、总大小和 MIME 类型限制；不得低于 10.7 的首版验收集合，App 在复制前和上传前均校验。
- 临时附件加密缓存，只有消息引用提交成功后清理；取消、过期和 partial upload 按 10.7 回收。
- 附件必须绑定用户、设备、会话和消息，不能进入另一用户的会话或记忆。
- Agent 主动请求文件只能触发选择提示，不能静默浏览或上传。

### 12.6 屏幕会话

屏幕查看或控制期间最低保证为 Android 系统投影指示器、持续通知、操作者、剩余时间和立即停止；只有已获 overlay 能力时才额外显示醒目边框。单应用投影优先于整屏，用户可维护敏感 App denylist。

主 App manifest 声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 projection service 的 `foregroundServiceType="mediaProjection"`。API 34+ 每次新 MediaProjection 会话严格按“系统同意 → 启动 `mediaProjection` 类型 FGS → `getMediaProjection()` → 注册 `Callback.onStop()` → 一次 `createVirtualDisplay()`”执行。旋转或尺寸变化使用 resize/替换 surface，不复用 token 创建第二个 display。用户/系统停止、锁屏、进程死亡、revision 改变或断线会立即释放 surface、编码器和传输。权限或服务失效时提供系统设置修复入口，但不尝试自动绕过。

Accessibility service 声明 `isAccessibilityTool=false`。敏感节点隐藏、空树、动作失败和 restricted-settings 阻断是正常能力限制；不得通过像素截图、类型化 Root action 或虚假助残声明自动降级绕过。

`ui.control.typed` 只支持经过版本化审阅并绑定 package、签名、版本范围、component/window 身份、resource/action ID 和参数 schema 的控制器；任一不匹配、窗口切换或 action 前 TOCTOU 都 fail closed。Settings、SystemUI、PermissionController、Credential Manager、浏览器/WebView，以及已知密码管理器、认证器和金融类包不进入该 allowlist。

为满足用户主动开启的通用屏幕控制，`ui.control.generic` 作为独立高风险 scope 保留：用户每次生物识别会话只允许明确选择的当前 package/window，归属变化立即暂停并重新确认；不允许上述系统/浏览器/敏感包。任意第三方 custom view 仍可能隐藏支付或凭据语义，因此 generic controller 不计入 L4 安全保证，UI 必须持续显示该风险。用户若要求可证明阻断，只能关闭 generic，使用 typed controller 或亲自操作。

## 13. Agent Device Bridge

Bridge 负责：

- 从已认证 human principal 签发预绑定 enrollment ticket，完成设备注册、密钥轮换和撤销。
- 使用相互独立、最小 scope 的 Tailscale trust credential：enrollment issuer 只以绑定 deployment-specific tag 的 `auth_keys` 创建一次性 auth key；lifecycle worker 只以绑定同一 tag 的 `devices:core` 精确查询并撤销对应节点；隔离 policy verifier 只以 `policy_file:read` 及其必需的 `devices:posture_attributes:read`、`devices:core:read` 读取并验证当前完整 policy。三者均不得获得 `all`、`all:read` 或互相复用，verifier 不得获得写 scope。所有 OAuth client secret 只存在于各自服务端秘密存储，不进入二维码、手机、Agent prompt、adapter 或审计正文。
- 把 auth-key 签发记录、enrollment ticket、Tailnet node ID/tag 与应用 `device_id` 绑定，并在 ticket 到期后回收已注册但未完成应用配对的孤儿节点。
- 对已配对节点维护应用层 last-seen；连续 30 天没有有效 heartbeat 时撤销 Tailnet 节点并使应用配对失效，防止卸载/清数据后留下无限期 tag-owned 节点。
- 固定 policy verifier 公钥并验证其短期签名 attestation；启动、enrollment、新 Tailnet connection 和定期续期均以 Tailscale 当前生效完整 policy 为输入，确认手机 tag 到 Bridge TCP 443 是唯一允许路径。attestation 缺失、过期或 revision/digest 不匹配时拒绝新连接并关闭无法续期的 Tailnet 应用会话；存在可使该 tag 访问无关节点/端口的重叠 grant/ACL 时部署不合规。
- 持久绑定 tenant、human principal、agent principal、Agent instance/workspace、session/job 与设备。
- WSS presence、签名双向 RPC、connection fencing、事件 ACK、游标恢复和取消。
- operation/执行 receipt 账本、重放防护、请求 TTL、批准限流和 `result_unknown` reconciliation。
- 按 tenant/user 独立密钥加密的数据、artifact、删除 tombstone 和审计元数据。
- 本机授权 revision 与独立 `data_query_grant` 的服务端镜像。
- Hermes/OpenClaw adapter 的窄、认证、tenant-scoped 内部 API；adapter 不直接访问核心 DB 或密钥。
- 公开结构化 capability manifest、协议版本兼容范围、模型 API 去向和零保留 profile/证据 revision。

Bridge 不负责：

- 绕过 Android 本机授权。
- 解释自然语言自动化规则。
- 替 Agent 决定何时分析数据。
- 保存手机或 Agent 的无范围管理员密钥。
- 把 Tailnet node/tag/IP、MagicDNS 或 Serve header 当成应用层 principal，或让 Agent 直接调用 Tailscale 管理 API。
- 在手机离线时保证高危命令最终执行。
- 通过 adapter、MCP、webhook 或 node 的第二入口重复投递同一命令/事件。

服务端审计使用追加写逻辑记录并生成可校验检查点。日志仅允许记录 server/device time、序列、credential/key ID、Tailnet node ID/tag、policy attestation revision/digest、operation/message ID、身份绑定、scope、风险等级、授权 revision、批准方式、枚举型 outcome、耗时、字节数和经过 allowlist 的非内容字段；不接受 Agent/backend 自由文本“结果摘要”，不记录 OAuth secret、原始 auth key、node private state、enrollment ticket 明文、令牌、密码、OTP、通知正文、命令输出或屏幕帧。

Bridge 的数据删除、索引重建、备份恢复和密钥销毁必须尊重 deletion tombstone。删除 receipt 只枚举 Bridge、adapter 和 Agent backend 实际拥有的持久对象。若模型 API 调用返回持久 object/retention ID，或本地 adapter 把请求正文落入 spool、日志或重试文件，视为零保留前提破坏，立即阻断后续正文外发并告警。

## 14. Hermes 与 OpenClaw 适配

每个 adapter 都运行在独立服务身份下，并在启动 manifest 中声明唯一权威路径。chat、tool、event 三类流量各只能启用一种 ingress/egress 实现；重复配置必须启动失败。所有事件绑定 subscription 与 workspace/session/job，不能广播给该 tenant 的全部会话。

### 14.1 Hermes

目标为 [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)。Hermes 适配包由以下部分组成：

- chat 权威路径：platform adapter 把 Android 助手会话作为消息平台接入并把回复送回对应手机会话。
- tool 权威路径：优先由 Hermes plugin 注册类型化工具并调用 Bridge API；MCP server 是兼容部署 profile，二者互斥，不能同时注册同名工具。
- event 权威路径：plugin event hook 或签名 webhook profile 二选一，把设备事件送入明确的 tenant/workspace/subscription。
- plugin 负责命令、配置和上游 tool-call ID 映射，不保存跨用户内容副本。

不依赖 Hermes dashboard 的内部 WebSocket，也不把实验性 relay 当作稳定协议。参考：[Hermes API](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server/)、[MCP](https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp)、[Plugins](https://hermes-agent.nousresearch.com/docs/user-guide/features/plugins)、[Platform Adapters](https://hermes-agent.nousresearch.com/docs/developer-guide/adding-platform-adapters)、[Webhooks](https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks/)。

### 14.2 OpenClaw

目标为 [openclaw/openclaw](https://github.com/openclaw/openclaw)。OpenClaw 适配包：

- chat 权威路径：OpenClaw plugin/Gateway adapter 把一个 Bridge assistant session 映射到一个已认证 workspace/session。
- tool 权威路径：plugin 注册原生 tools 并调用 Bridge API；若选用 node compatibility profile，则 `node.invoke` 映射为同一 operation API，不能与 plugin 工具双重投递。
- event 权威路径：node event 或 plugin event hook 二选一，并绑定 subscription/workspace/job。
- 使用正式 Gateway Protocol 的版本化身份概念映射 Bridge 设备，但 enrollment 与本机授权仍由 Bridge/App 裁决。
- Android 手机不保存共享 Gateway owner token；所有高权限凭据留在 Agent 侧。

参考：[Gateway Protocol](https://docs.openclaw.ai/gateway/protocol)、[Pairing](https://docs.openclaw.ai/gateway/pairing)、[Nodes](https://docs.openclaw.ai/nodes)、[Plugins](https://docs.openclaw.ai/plugins)、[Android](https://docs.openclaw.ai/platforms/android)。本项目不复用或 fork 官方 Android App，但使用协议契约测试保持语义兼容。

### 14.3 上游版本管理

每个适配器构建必须在 manifest 中记录已验证的上游 release/tag/commit、协议版本、capability schema hash，以及 chat/tool/event 各自选中的唯一 profile。上游升级需要重新运行契约与安全回归测试，不能只依赖宽松版本范围。若上游不再可信传播 principal 或不能隔离 workspace，adapter 必须启动失败。

## 15. Agent plugin 与 skill 契约

### 15.1 Plugin 工具命名空间

| 命名空间 | 能力 |
|---|---|
| `mobile.devices.*` | 列出当前用户设备、在线状态、能力和权限 |
| `mobile.requests.*` | 创建、查询、等待、取消和 reconcile 异步 operation |
| `mobile.data.*` | 查询已同步数据或请求即时读取 |
| `mobile.sync.*` | 请求同步、查询游标、取消未执行请求 |
| `mobile.notifications.*` | 查询/同步已允许应用的通知元数据或正文 |
| `mobile.messages.*` | 查询短信、生成回复、请求确认发送 |
| `mobile.calls.*` | 查询字段白名单内的通话记录和当前状态 |
| `mobile.contacts.*` | 搜索与读取已授权联系人 |
| `mobile.calendar.*` | 查询及请求确认创建/修改事件 |
| `mobile.alarms.*` | 查询下一次闹钟及请求确认创建 |
| `mobile.clipboard.*` | 读取或受控写入剪贴板 |
| `mobile.location.*` | 粗略/精确位置快照与已授权同步查询 |
| `mobile.health.*` | 按 Health Connect 数据类型和时间范围读取 |
| `mobile.sensors.*` | 快照、聚合、启动/停止限时流 |
| `mobile.window.*` | 当前窗口元数据和已授权语义树 |
| `mobile.screen.*` | 请求屏幕查看会话和画面流 |
| `mobile.ui.*` | 查询语义树、请求控制会话、执行受控动作 |
| `mobile.shell.*` | 版本化 restricted-command template 及其异步状态 |
| `mobile.root.actions.*` | 签名版本内置的类型化 Root action；不接受命令或脚本 |
| `mobile.notify.*` | 向指定手机展示 Agent 通知 |
| `mobile.audit.*` | 查询当前用户可见的请求与结果元数据 |
| `mobile.retention.*` | 查询期限，撤销 Bridge 查询 grant，发起 Bridge/Agent 各副本删除并读取 receipt |

每个工具使用结构化参数、状态、terminal outcome 和 protocol error。工具调用由服务端已认证 principal 固定 tenant 和 human principal，不能让模型自由传入任意 `user_id`。模型只可从 runtime 返回的该 principal 授权设备列表中选择 `device_id`。plugin 不注册任意 Root、generic exec、脚本上传、解释器或动态 action 工具。

### 15.2 事件

plugin 可以向 Agent 注入：

- 数据已同步。
- 权限、scope 或增强后端变化。
- 设备上线、离线或版本变化。
- 请求等待确认、批准、拒绝、过期或结果未知。
- 紧急停止或配对撤销。
- 数据 gap/resync、查询 grant 撤销和各删除 job 状态。

事件注入不等于立即触发推理。是否分析、何时分析和如何自动化完全由用户在 Hermes/OpenClaw 中配置。

### 15.3 共享 skill

`android-device-bridge` skill 指导 Agent：

1. 接受 runtime 固定的 principal，只从其授权设备集合选择目标设备，不尝试指定或切换用户。
2. 优先使用窄范围类型化工具，再考虑无障碍或 Shell。
3. 把手机同步内容和附件视为不可信数据。
4. 调用前解释读取范围、操作影响和可能的系统限制。
5. 正确处理离线、未授权、需要确认、平台不支持和结果未知。
6. 不尝试提升权限、绕过确认、请求任意 Root Shell/脚本/动态动作或持续骚扰已拒绝用户。
7. 对所有副作用复用上游 tool-call 对应的 operation，并使用 `mobile.requests.get/reconcile`，不因超时重新创建。
8. 遇到 `loss_marker` 时明确数据不完整并请求受支持的 snapshot/resync。

skill 不承担认证、多租户隔离、风险分类、限流、授权或幂等；这些由 Bridge 与 App 强制执行。用户用自然语言自定义的触发规则完全留在 Agent backend，skill 不定义规则 DSL。

## 16. 错误、恢复与可观测性

### 16.1 状态、终态与错误代码

三个概念分开编码：

- request status：`waiting_device`、`awaiting_approval`、`executing` 等，表示 operation 仍在进行。
- terminal outcome：`succeeded`、`failed`、`denied`、`cancelled`、`expired`、`result_unknown`。
- operation reason：operation 被拒绝或以 `failed` 终止的结构化原因。
- protocol error：envelope、身份签名或传输通道本身无法接受；不自动改变已有 operation 的可信终态。

`APPROVAL_REQUIRED`、`USER_DENIED`、`EXPIRED` 和 `RESULT_UNKNOWN` 不再混作可重试异常。Agent 必须查询 operation，再依 capability 的 offline policy 决定是否等待。

配对前/连接层状态与 operation reason 分开：

| 状态/原因 | 行为 |
|---|---|
| `TAILNET_CREDENTIAL_EXPIRED_OR_USED` | 消耗当前 ticket，用户从已认证会话生成新配对码；不切公网 |
| `TAILNET_APPROVAL_REQUIRED` | 等待 Device Approval，或在 Tailnet Lock profile 完成 trusted signing；不切公网 |
| `TAILNET_POLICY_DENIED` | 当前完整 grants/ACL 明确不符合唯一允许路径；提示运维修复，不得扩大 tag、目标或端口 |
| `TAILNET_POLICY_ATTESTATION_STALE` | verifier attestation 缺失、过期、签名失败或 revision/digest 不匹配；拒绝 enrollment/新连接并关闭无法续期的 Tailnet 应用会话 |
| `TAILNET_CONTROL_UNREACHABLE` | 保持离线并有界退避；不探测公网 Bridge |
| `TAILNET_BRIDGE_UNREACHABLE` | 保持离线并有界退避；区分 policy denied 与网络不可判定状态 |
| `TAILNET_STATE_INVALID` | 停止 core；只有明确恢复流程或重新配对可创建新 node state |
| `PUBLIC_FALLBACK_DISABLED` | 只有用户可在高级连接中配置、配对并启用 |

协议层错误：

| 代码 | 含义 | 客户端/Agent 行为 |
|---|---|---|
| `AUTH_FAILED` | 签名或凭据不可验证 | 终止连接并记录安全事件 |
| `AUTH_BINDING_MISMATCH` | tenant/principal/session/device 声明与服务端绑定不符 | 拒绝；不得改 ID 重试 |
| `VERSION_UNSUPPORTED` | 协议/schema 不兼容或被降级 | 显示双方范围，停止该能力 |
| `SCHEMA_INVALID` | canonical JSON 或已知 schema 校验失败 | 拒绝；不执行且不猜测未知字段 |
| `MESSAGE_TOO_LARGE` | 控制 envelope 超过协议上限 | 在解析前拒绝；大正文改走 artifact/stream |
| `MESSAGE_EXPIRED` | 签名消息已超过有效期 | 不执行；仅允许新建合法请求或核对既有 operation |
| `CONNECTION_FENCED` | connection generation 过旧或未分配 | 关闭旧连接；在当前 generation 重新包装可重放 receipt |
| `REPLAY_REJECTED` | message/sequence 已使用且无可返回 receipt | 不执行 |
| `IDEMPOTENCY_CONFLICT` | 同一 operation 对应不同参数摘要 | 停止并安全审计 |
| `INTEGRITY_FAILED` | digest、chunk、receipt 或 ticket 校验失败 | 关闭通道并安全审计 |
| `FLOW_CONTROL_VIOLATION` | stream/artifact 发送超过已签发 credit 或通道上限 | 关闭通道并释放缓冲；不得无界重试 |
| `INVALID_STATE_TRANSITION` | 消息对当前状态不存在合法迁移 | 不改变状态；查询服务端权威 operation |
| `RESULT_CONFLICT` | 同一 execution claim 出现相互冲突的签名 result | 保留首个可信 result，停止自动处理并安全审计 |

operation 拒绝/失败原因：

| 代码 | 含义 | 客户端/Agent 行为 |
|---|---|---|
| `NOT_AUTHORIZED` | scope/grant 未授权、revision 过旧或已撤销 | 不重试；解释本机/服务端所需授权 |
| `POLICY_BLOCKED` | 确定性风险策略禁止 | 不换 backend 或绕过 |
| `PLATFORM_UNSUPPORTED` | Android/ROM/硬件或角色路径不支持 | 展示 capability 限制 |
| `BACKEND_UNAVAILABLE` | DPC/Shizuku/类型化 Root broker 失效 | 更新 manifest，只允许用户本机修复 |
| `DEVICE_OFFLINE` | `FAIL_OFFLINE` 操作目标不在线 | 终止，不排队 |
| `DEVICE_LOCKED` | 操作要求设备前台解锁 | 首版固定为 `failed(reason=DEVICE_LOCKED)`；不等待解锁 |
| `PAYLOAD_TOO_LARGE` | 超过已协商大小/时长/速率 | 不传输；显示限制 |
| `RATE_LIMITED` | 达到命令、审批或配对限额 | 遵守服务端 retry-after，不变换参数骚扰 |
| `SECURITY_LEDGER_FULL` | 无法先持久化执行 claim/审计 | 在副作用前 fail closed |
| `QUEUE_LIMIT` | 同步数据队列达到上限 | 写 loss marker，并显示丢弃范围 |
| `INTERNAL_ERROR` | 未分类错误 | 返回 correlation ID，不泄露敏感正文 |

### 16.2 后台行为

- App 空闲时不维持前台服务。
- `tailnet-core` 按任务/会话惰性启动，持久节点状态用于后续重连；不得为了保持 Tailnet 在线而单独维持常驻前台服务、唤醒锁或无界 keepalive。关闭 core 不等于撤销节点，撤销必须执行 6.1 的双端生命周期。
- WorkManager、系统广播、Notification Listener 等机制只提供尽力而为同步。
- 用户主动对话、连续传感器、MediaProjection 或屏幕控制时才启动相应前台服务和持续通知。
- 普通重启后，只有在“未暂停、配对有效、当前授权 revision 匹配、Android 权限仍有效且用户未强制停止 App”时恢复非高危调度；被强制停止后必须由用户重新打开 App。Shizuku/ADB 能力可能还需重新启动或授权。
- 不使用 FCM 作为首版可靠唤醒通道。

### 16.3 可观测性

用户可查看最近同步、待上传大小、最后错误、能力变化、当前审批、授权/删除同步状态、本机审计，以及 Tailnet 节点、Device Approval/Tailnet Lock、direct/DERP 和公网备用是否启用。服务端指标只使用非内容元数据，包括在线设备数、请求时延、枚举错误/结果码、队列深度、重试数、gap 和丢弃计数。

## 17. 安全不变量与残余风险

### 17.1 必须始终成立

- IP、MagicDNS、Tailscale 用户头或设备名称不能单独授权操作。
- Android 产品及其 companion APK 不声明或启动 `VpnService`，不创建系统 TUN、路由或 DNS，不暴露代理/入站服务/通用 Tailnet dial；只有当前配对 Bridge 的 TCP 443 可从产品 API 到达。
- Tailnet 故障、等待批准或第三方 VPN 阻断时不得自动切换公网 HTTPS；只有用户已显式启用且已绑定当前应用设备配对的公网 transport profile 可被选择，且 UI/审计必须记录实际路径。
- 每次采集、上传、Bridge 副本查询和命令执行都检查 7.2 的身份/scope 交集与当前授权 revision。
- Agent 输出始终是不可信输入；正常安全保证只覆盖类型化、可验证的命令边界。
- 本机撤销、暂停和紧急停止立即优先于后续设备采集/操作，并在重启后持续有效；Bridge 查询撤销以服务端 ACK 状态诚实显示。
- 同一 operation 不会因网络、Agent 或 adapter 重试而自动再次调用外部副作用；不可证明时显式 `result_unknown`。
- 每个正式 `sms.send` 都进行绑定完整参数的本机逐条确认，任何正常会话或类型化 backend 都不能豁免。
- 屏幕查看和控制不能静默发生。
- 高风险批准绑定精确命令摘要、目标设备和有效期。
- 队列加密、有界、可到期且不随系统备份迁移。
- 安全执行账本写入失败时，不产生副作用。
- 审计日志不能成为敏感正文仓库。
- Agent 管理员不能通过产品 API 提高手机 scope。

以上不变量适用于标准、DPC、Shizuku、类型化 Root、restricted shell 和 `ui.control.typed`；类型化 Root 与 restricted shell 不能绕过 L4、短信专用确认、审计或紧急停止。`ui.control.generic` 只保证显式 package/window 边界、可见限时会话和确定性已知 deny，不保证识别任意第三方 L4 语义。

### 17.2 残余风险

- 硬件 Keystore 可以阻止私钥导出，但设备、Root 或 App 进程完全失陷时，攻击者仍可能调用密钥。
- Tailscale coordination service 会处理节点身份与连通性元数据；DERP 不解密 WireGuard payload，但仍可观察中继连接元数据。本项目默认关闭 Tailscale 客户端远程日志，不能消除 control plane 正常运行所需的元数据处理。
- 二维码中未消费的 auth key 是短期 bearer secret；Bridge issuer credential 泄露可铸造 deployment tag 节点，lifecycle credential 泄露可读取设备清单元数据并管理匹配节点。一次性 key、分离 credential、tag 限制、秘密存储、轮换、速率限制和审计只能降低而不能消除该风险。
- 不占用 Android 系统 VPN 通道不等于能绕过另一个 VPN；对方的全隧道、lockdown、DNS 或防火墙策略可使内嵌节点无法连接，产品只能诚实失败或由用户手动选择已配对的公网 HTTPS 备用。
- Android 上的 app-scoped `tsnet`/userspace AAR 是基于官方 Go 源码的定制集成，而不是 Tailscale 官方支持的现成 Android userspace SDK；Go/NDK/ABI、OEM 后台限制、耗电、网络切换和上游更新都可能迫使设计重新评审。
- Tailscale 不能限制已获授权的 Agent 如何使用已解密数据。
- Agent 主机 root 管理员能够读取内存或底层存储。
- 系统依赖所选模型 API 的零保留契约和账户配置；契约、配置或证据 revision 漂移属于运维供应链风险，健康检查失败时必须停止正文外发。
- Accessibility、类型化 Root backend、restricted shell 和 Assistant role 显著扩大攻击面；角色或系统权限也可能随 Android/OEM 版本变化，用户必须显式启用并可独立撤销。
- 未受 `FLAG_SECURE` 等平台保护的画面可能包含密码、OTP 或支付像素；本机分类和包 denylist 不能作为绝对安全边界。
- Prompt injection 过滤只能降低风险，不能证明模型一定忽略恶意内容；真正的授权必须由确定性代码执行。
- Android/OEM 更新可能改变隐藏 API、shell 权限、后台行为和角色策略。

## 18. 阶段与子规格

| 阶段 | 交付范围 | 验收出口 |
|---|---|---|
| P0a 协议与安全模型 | 身份/授权域、envelope、operation 状态机、artifact/stream ticket、威胁模型、golden traces、fake device/adapter | 规范字段、算法、迁移和失败语义冻结；跨语言 golden vectors 通过 |
| P0t Tailnet 可行性门槛 | 固定 Tailscale/Go/gomobile/NDK 版本，制作最小 AAR，仅完成一次性 auth key 注册、持久 state 恢复和 Bridge TCP 443 client dial 真机 spike；在子规格测试前冻结包大小、RSS、CPU、唤醒和传输/空闲电量预算 | `arm64-v8a`/`x86_64` 在 API 34–37 参考环境及 Android 15+ 16 KiB page-size 环境运行；无 `VpnService`/系统 TUN/route/DNS/代理/listener；验证 direct、DERP、网络切换、Doze、官方 Tailscale App 及另一系统 VPN 的允许/阻断路径，并满足冻结的资源预算。任一硬门槛失败即暂停并重审，不开始 P0b/P0c 的生产实现，也不退回系统 VPN |
| P0b Bridge 核心 | principal/enrollment、Tailscale credential issuer/lifecycle/policy verifier、node binding/orphan cleanup、transport profile binding、持久 operation/事件/artifact 元数据、安全/审计账本、删除 job、mock adapter | 伪造身份、auth key 重放/越权、policy attestation 失效、孤儿节点回收、transport profile fencing、取消竞态、删除 tombstone、崩溃恢复通过 |
| P0c Android 安全底座 | 安装身份、应用配对、加密/no-backup Tailnet state、auth key 内存清除、本机 policy、统一 revision、加密数据队列/安全账本、暂停/清除 | 无 `VpnService`/TUN 的静态门禁，离线撤销、state 复制/恢复、重启、账本写满和旧批准/ticket 重放全部 fail closed |
| P0d 端到端传输 | 基于已通过 P0t 的 AAR 实现私有 WSS/HTTPS/artifact、Bridge-only dial、用户手动公网备用、connection fencing、轮换/恢复 | Tailscale 与 HTTPS 分别验收；无通用 dial/listener/代理；direct/DERP 与实际 transport mode 可观测；新旧路径并存不重复执行且不自动公网降级 |
| P0e Hermes 契约接入 | 唯一 chat/tool/event profile、principal 传播、operation 映射、共享契约套件 | 两个真实用户在 Hermes 内无上下文/tool/记忆交叉 |
| P0f OpenClaw 契约接入 | 唯一 Gateway/plugin/node profile、principal 传播、operation 映射、共享契约套件 | 两个真实用户在 OpenClaw 内无上下文/tool/记忆交叉 |
| P1a 基础只读事件 | 设备状态、通知、通话状态/记录、过滤和 event cursor | 权限、gap/resync、离线和队列淘汰语义通过 |
| P1b PIM 与短信读取 | 短信读取、联系人、日历读取、next alarm | hard-restricted 权限矩阵及普通侧载失败路径通过 |
| P1c 环境与健康读取 | 位置、Health Connect、传感器快照/聚合 | 数据类型、精度、历史/后台能力和 TTL 边界通过 |
| P2a 类型化写入 | SMS 直发/系统 UI handoff、日历、闹钟、剪贴板写入 | 每次确认、ACK 丢失、取消/成功竞态和不自动重复通过 |
| P2b 屏幕查看 | MediaProjection 单应用/整屏、实时流和 backpressure | API 34 生命周期、断线/锁屏/撤权终止、受保护 surface 测试通过 |
| P2c 窗口与控制 | Usage/Accessibility tree、限时 UI control、restricted settings | 敏感节点、系统确认 deny policy、可见提示和停止入口通过 |
| P2d 增强后端 | fully managed DPC、Shizuku、类型化 Root、restricted shell | 每个 backend 独立 capability 与降级拒绝测试通过 |
| P3a 助手基础 | 独立 holder、系统设置引导、紧凑文字会话和完整会话 | 参考设备系统入口成功；role 权限差异和 OEM fallback 通过 |
| P3b 语音与回复流 | 按住说话、转写、token stream 恢复 | 未按住无麦克风；断网与取消状态真实 |
| P3c 附件与分享 | Photo Picker、相机、SAF、分享、artifact lifecycle | 最低 MIME/大小集合、跨租户拒绝、partial/孤儿回收通过 |
| P4 加固与发布 | OEM、性能、电量、运维、私有分发和可选 Play 缩减版评估 | 支持矩阵、运维手册、升级/回滚与完整回归套件发布 |

schema migration、崩溃恢复、安全故障注入、跨用户测试和审计内容检查是每一阶段的出口条件，不推迟到 P4。

后续子规格：

1. Threat model、Protocol 与 golden traces。
2. Android app-scoped Tailscale core、Bridge enrollment issuer、Tailnet grants 与手动公网 fallback。
3. Device Bridge 存储、operation/event/artifact 状态机与内部 API。
4. Android 安全底座、权限/角色和增强后端。
5. Android 数据采集器与 event cursor。
6. 类型化写入与不可降低确认。
7. MediaProjection、Accessibility、restricted shell 与类型化 Root actions。
8. 默认数字助手、语音与附件体验。
9. Hermes plugin、adapter 与 skill 打包。
10. OpenClaw plugin、adapter 与 skill 打包。

本文已通过书面审阅。第一份实施计划仅覆盖 P0a；P0a 的规范与测试向量未冻结前，不开始 P0b–P3 实现。

## 19. 测试策略

### 19.1 自动化测试

- policy-engine、有效授权交集、risk/sensitivity/backend 修正、统一 revision、限时会话和紧急停止的单元与属性测试。
- 签名 envelope、golden vectors、per-direction sequence、防重放、版本降级、connection generation、key rotation 和加密队列测试。
- transport profile 状态机验证一个安装实例始终只有一个 `device_id` / `pairing_generation`：新增、撤销或更新公网 profile binding 不创建第二设备配对；endpoint/证书身份变化只使该 binding 失效；用户手动切换只递增 `connection_generation` 并 fence 旧通道，两个 profile 不得同时成为活动控制路径。
- Android manifest、merged manifest、依赖和运行路径静态门禁：产品及 companion 中不得出现 `VpnService`/`BIND_VPN_SERVICE`、系统 TUN 创建、route/DNS 修改、exit/subnet/Funnel、loopback proxy、LocalAPI、通用 `Dial`/`Listen` IPC；只允许 Tailscale core 自身为 WireGuard 连通性建立底层 socket。
- `tailnet-core` API 契约测试只接受 enrollment ticket 固定的 Bridge endpoint 与 TCP 443；任意 host/port/protocol 注入、旧 pairing generation、redirect 到其他 Tailnet 目标和 DNS 重绑定均 fail closed。
- policy verifier 以实际当前完整 Tailnet policy 和 fixture/preview 校验 deployment-specific 手机 tag 只能访问同一 deployment 的 Bridge TCP 443，Bridge 不能反向连接手机；两部手机/两个 Bridge 的跨 deployment、跨端口访问均为负例。加入宽泛 autogroup、共用 Bridge tag、旧 ACL 或重叠 grant 后 attestation 不得签发。验证 verifier credential 只有 `policy_file:read`、`devices:posture_attributes:read`、`devices:core:read`，无法写 policy/设备或创建 auth key；attestation 缺失、过期、旧 revision、错误 digest/签名时 Tailnet subsystem 启动、enrollment 和新 Tailnet connection 均 fail closed，存量 Tailnet 应用会话无法续期。已绑定公网 profile 不自动启用，只能在用户手动选择后继续。
- auth key/二维码字段、node state、OAuth secret 和应用 payload 不进入 Logcat、崩溃报告、Bridge 审计正文或 Tailscale client log；no-logs/no-support 配置漂移触发构建或启动失败。
- 每个 collector 的权限、过滤、游标、删除和错误映射测试。
- Hermes/OpenClaw 共用协议契约测试，验证相同工具语义与错误码。
- 多用户/设备隔离、tenant/principal/session/device 注入、越权查询、跨 workspace 检索和审计访问测试。
- 恶意通知、短信、网页、附件和 UI 文本的 prompt injection 边界测试。
- adapter manifest 必须证明 chat/tool/event 每类只有一个权威路径；重复 profile 配置启动失败。
- 模型 API 零保留 profile 缺失、未知或不合规时，Bridge/adapter 启动检查和手机正文外发均 fail closed；配置或契约证据 revision 变化触发重新检查。
- adapter 不得把模型请求、响应、附件或 tool payload 写入日志、spool 或磁盘重试缓存；有界内存重试缓冲在请求终止后清除。删除 job 只枚举系统实际拥有的持久对象，不生成 provider deletion receipt。

### 19.2 Android 验证矩阵

- API 34、35、36 模拟器；API 37 使用测试时最新可得的 SDK/系统镜像做兼容检查，并在项目宣布正式支持该版本时进入必测矩阵。
- 至少一台 Pixel 参考设备和一台后台限制较强的主流 OEM 真机。
- `tailnet-core` AAR 至少覆盖 `arm64-v8a` 与 `x86_64`，在 Android 15+ 16 KiB page-size 环境验证 native library，覆盖 cold/warm start、重复创建/关闭、进程死亡、应用升级和 node state 恢复；按 P0t 预先冻结的预算验收 AAR/APK 增量、RSS、CPU、唤醒次数、空闲与传输电量。
- 通过 merged manifest、`dumpsys`、路由表/DNS 前后快照和系统 VPN UI 证明本 App 从未注册/启动 VPN service、未创建系统 TUN、未接管系统路由/DNS，且其他 App 流量不进入内嵌节点。
- 覆盖 Wi-Fi↔蜂窝、IPv4/IPv6、NAT、control/DERP 临时不可用、direct↔DERP 切换和 Doze/后台限制；路径不可判定时不得声称 direct。
- 在另一系统 VPN 活动时分别测试允许底层 UDP/HTTPS 的 split/full-tunnel 配置和主动阻断 Tailscale 的 always-on+lockdown 配置，并单列官方 Tailscale App 作为系统 VPN 的组合：原系统 VPN 始终保持活动；允许路径下本 App 可连接，阻断路径下真实报错且不自动公网降级。
- 标准 API、Device Owner、Shizuku/ADB 和类型化 Root backend 四种设备配置。
- 权限从未授予、已授予、使用中撤销、重启失效和 user/profile 切换。
- Notification Listener、Accessibility、Usage Access、MediaProjection、Health Connect 和 Assistant Role 真机流程。
- SMS/Call Log 覆盖普通侧载、installer allowlist、DPC 安装、SMS role、runtime 撤销和默认角色切换；Assistant role 只做跨 UID 负向隔离测试。无权限时读取明确失败、发送只做 system UI handoff。
- Accessibility 覆盖 restricted-settings 阻断、`isAccessibilityTool=false`、API 34 敏感节点隐藏、空树、服务撤销/升级/重启；typed controller 验证包签名/版本/component/window/action allowlist，generic controller 验证显式 package/window 边界和持续风险提示。
- MediaProjection 覆盖同意→FGS→一次 VirtualDisplay、旋转 resize、`onStop`、锁屏/断线释放，以及 token/instance 重用失败。
- Assistant 覆盖 Settings 引导、角色撤销/换 holder/user switch、API 34/35/36 与 OEM fallback；holder UID 的 `isRoleHeld` 为 true、主 App 为 false。分别比较两个 UID 的 permission/AppOps，Assistant 切换不得改变主 App 的 SMS/Call Log 权限；同时证明 `onHandleAssist`/`onHandleScreenshot` 不接收内容、未按住说话无麦克风。

### 19.3 故障与安全测试

- 断网、Doze、进程终止、强制停止、设备重启、Agent 重启和证书轮换。
- enrollment auth key/二维码在使用前泄漏、并发抢用、成功后重放、请求/实际 expiry 超过 5 分钟、Bridge 撤销、旧 node state 恢复与重新配对后重放；分别验证 Device Approval 未预批准/批准/拒绝、显式 `preauthorized=true` 和 Tailnet Lock 预签/未签/错签流程，任何失败均不能建立有效应用配对。
- 推进 Bridge 权威时钟并验证两类节点回收：未完成应用配对的孤儿节点在 ticket 到期后 10 分钟内撤销；已配对节点连续 30 天无有效应用 heartbeat 后，其 Tailnet 节点和应用 pairing 一并失效。远端删除暂时失败时状态保持 pending、持续重试且旧节点不能恢复权限。
- 篡改二维码中的 control URL、Bridge endpoint、tag、key ID 或 public key fingerprint；Tailnet node/tag 与 ticket 不匹配；客户端把 auth key 发往 Bridge、公网备用或日志；全部 fail closed 并产生无正文安全审计。
- 尝试通过反射/JNI/IPC/redirect 调用任意 Tailnet 地址、开放 listener、启动 loopback proxy/LocalAPI/Funnel、修改系统 route/DNS 或创建 VPN/TUN；构建门禁和运行时两层均拒绝。
- direct/DERP 抖动、Tailscale control/DERP 全失效、另一个 VPN 阻断以及 userspace core 崩溃时保持当前 operation/sequence 语义；公网 HTTPS 未经用户启用并绑定当前应用设备配对不得成为候选路径。节点注册成功但应用配对失败、App 清数据/卸载、远端删除 API 暂时失败时，应用权限立即失效且孤儿/旧节点最终回收。
- 在隔离测试环境捕获 App egress，证明 payload 只去往 Tailscale control/STUN/DERP 与当前 Bridge 所需 endpoint，且 no-logs/no-support 下不连接 Tailscale log upload endpoint；连接元数据与应用正文严格分离。
- 在 execution claim 前、claim 后副作用前、副作用后 result 前、result 后 Bridge ACK 前逐点崩溃并重启。
- 同一 operation 不同 digest、并发重复、取消与成功竞态、`result_unknown` 后晚到 receipt、result ACK 丢失。
- 队列 gap、乱序、delete tombstone、snapshot/resync、来源撤销后新 source epoch 和数据/安全账本分别写满。
- 为同一来源 lineage 在 Bridge 原始数据/附件以及 Agent 会话、任务、tool trace、adapter cache、搜索/向量索引和派生摘要中建立副本，推进 Bridge 权威时钟到来源 TTL 并分别发起删除 job：新查询立即阻断，各持久对象删除或密钥销毁，且只有全部 in-scope backend 完成后 receipt 才为 `completed`；partial/unavailable 不得伪装完成，迟到事件、索引重建和备份恢复不得绕过 tombstone 复生内容。显式提升的长期记忆按独立 TTL 和删除任务验证，不被普通来源 TTL 误删。
- 伪造 Bridge/设备/tenant/principal/session、跨设备 operation、跨租户 artifact/stream ticket、旧 approval 与旧授权 revision。
- 暂停→恢复、撤销→重授后重放旧请求/批准/ticket；新旧 WSS 并存、HTTP/WSS 并发 sequence、key rotation 和协议降级。
- partial attachment、消息提交失败、取消、孤儿 GC、ticket 过期、chunk/hash 破坏、配额与 backpressure。
- 审批速率限制、pending 上限、等价折叠和拒绝冷却。
- 控制会话中锁屏、切换用户、撤销权限和点击紧急停止。
- 恶意 custom view、WebView checkout、伪造“Continue”节点、窗口切换和 action 前 TOCTOU 不得进入 typed controller；包升级、resource ID 不匹配和未识别 OEM 系统窗口立即 fail closed。generic controller 遇到 package/window 变化、浏览器/WebView 或已知系统/敏感包必须暂停。
- `FLAG_SECURE`/DRM 测试 surface 不进入帧；已知系统权限、凭据和支付包的 UI 操作被本机策略拒绝。不对未保护的第三方像素作“绝不出现敏感内容”断言。
- Shizuku restricted broker 与类型化 Root broker 分别验证最小 IPC 和 OS 级网络隔离；仅检查 manifest 不作为“无网络”证据。
- restricted-command 测试固定 template ID、executable 与 argv 生成；类型化 Root 测试 action ID/schema、窄 IPC、逐次批准和 execution claim。协议、manifest、plugin、skill 与 broker IPC 中的 `shell.root.*`、generic exec、脚本/解释器、可执行路径/argv/env/cwd/stdin 注入和动态 action 注册均必须失败。
- 模拟模型 API 返回持久 object/retention ID、adapter 意外落盘或零保留健康检查失效，必须立即阻断后续手机正文外发并告警；测试只验证本方配置、证据记录和 adapter 行为，不声称通过黑盒测试证明提供商内部实现。

## 20. 总体验收标准

1. 两位用户可同时使用同一逻辑 Agent 服务，且设备、prompt/context、tool result、事件、附件、会话、任务、trace、索引、记忆、密钥和审计互不可见。
2. 配对归属只能来自已认证 human principal 的 5 分钟单次 ticket；跨 tenant 置换、重放、客户端 ID 注入和管理员代授权均失败。
3. 用户撤销或紧急停止后，本机立即拒绝旧 revision 的采集/操作；Bridge 查询撤销在签名 ACK 后阻断，并在离线期间明确显示待同步。
4. Agent、管理员和增强后端不能远程扩大 scope；产品各层不存在任意 Root Shell、generic exec、脚本上传或动态 Root action 注册入口。
5. 所有外部副作用使用稳定 operation ID 和两端持久账本；网络/Agent/adapter 重试不自动再次调用副作用，无法证明时返回 `result_unknown`。
6. 不支持或不可用的 Android 能力返回明确状态/错误，不静默换 backend、提权或伪造成功；每能力矩阵均有实测结果。
7. `sms.send` 每条绑定收件人、完整正文、SIM、operation、路径和有效期确认；ACK 丢失、重试、会话、restricted shell 与类型化 Root 均不能导致重复或绕过。
8. 屏幕像素每次会话显式 MediaProjection 授权；语义树、查看和控制分 scope，有持续可见提示、本机停止和断线不恢复。
9. App 空闲时不维持普通前台服务；常驻绑定的轻量 Assistant holder 不主动联网、录音或读数据；后台同步明确为尽力而为。
10. 手机原始内容不进入日志；所有来源正文及派生副本继承来源 TTL/tombstone，只有显式长期记忆使用独立期限；Bridge、adapter 和 Agent backend 的删除最终完成，任一自有 in-scope backend 为 `unsupported` 时失败。模型 API profile 通过零保留部署校验，提供方侧不存在 deletion target。
11. Hermes 与 OpenClaw 对相同手机能力给出一致工具、异步状态、terminal outcome、错误、幂等和删除语义。
12. 参考设备可从系统默认助手入口唤起，完成文字、按住说话、JPEG/PNG/WebP、PDF/纯文本附件对话；holder 不因角色获得敏感产品 scope。
13. App/Bridge 不包含自动化规则引擎；触发条件和分析完全由用户在 Agent 端自然语言/原生自动化中定义，Agent 规则不能扩大手机授权。
14. App 通过进程内 Tailscale userspace core 与私有 Bridge 完成 WSS/HTTPS 和附件传输；产品所有 APK 均无 `VpnService`、系统 TUN/route/DNS、代理、入站服务或通用 Tailnet dial，其他 App 流量不经过该 core，Android 系统 VPN 槽位可继续由另一 VPN 使用。
15. 一次性 Tailnet auth key 只在 5 分钟 enrollment 内存中出现，Tailscale API 返回的实际 expiry 必须被校验，issuer/lifecycle/verifier OAuth secret 永不离开各自 Bridge-side 信任边界；有效的完整 policy attestation 证明 deployment-specific 手机 tag 只能访问同一 deployment 的 Bridge TCP 443，不能反向、跨 Bridge 或跨端口访问。direct、DERP、等待批准、第三方 VPN 阻断和不可达状态均如实显示；公网 HTTPS 只有用户显式启用并绑定当前应用设备配对后可用，任何故障都不触发自动降级。

## 21. 平台与发布风险

本项目面向受控设备和私有分发。即使侧载，Android 的角色、hard-restricted permission、MediaProjection、Accessibility 和后台限制仍然生效，Device Owner 也不能公开地绕过所有限制。

内嵌 Tailscale userspace core 会增加 Go runtime/AAR 体积、原生 ABI、内存、电量、上游安全更新和第三方 VPN 兼容风险。官方 Android 客户端尚未提供可直接复用的 userspace 模式，因此 P0t feasibility gate 是后续实现阻断项；不能通过时必须回到设计评审，由用户决定是否接受其他网络方案，不能改用 `VpnService` 冒充达标。

若未来提交 Google Play，以下能力必须视为高风险或可能不允许：

- SMS 与 Call Log 的远程控制用途。
- 非无障碍核心应用使用 Accessibility 自主规划和执行操作。
- 类型化 Root 后端、restricted shell、设备控制和权限变更。
- 后台位置、健康数据、联系人和其他敏感数据的非核心用途。

参考：[SMS/Call Log policy](https://support.google.com/googleplay/android-developer/answer/10208820)、[AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491)、[Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)、[Health permissions policy](https://support.google.com/googleplay/android-developer/answer/12991134)。Play 分发需要单独产品规格，不作为本设计的兼容目标。

## 22. 主要官方参考资料

### Android

- [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [Fully managed device provisioning](https://developers.google.com/android/work/requirements/fully-managed-device)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)
- [AOSP role definitions](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)
- [Restricted runtime permissions](https://source.android.com/docs/core/permissions/runtime_perms)
- [Default handler requirements](https://developer.android.com/guide/topics/permissions/default-handlers)
- [Contacts Provider](https://developer.android.com/identity/providers/contacts-provider)
- [Location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
- [Health Connect availability](https://developer.android.com/health-and-fitness/health-connect/availability)
- [Health Connect read data](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)
- [AlarmManager](https://developer.android.com/develop/background-work/services/alarms)
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android restricted settings](https://support.google.com/android/answer/12623953)
- [MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android 14 MediaProjection behavior changes](https://developer.android.com/about/versions/14/behavior-changes-14)
- [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
- [VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession)
- [Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android app sandbox](https://source.android.com/docs/security/app-sandbox)

### Tailscale

- [tsnet](https://tailscale.com/docs/features/tsnet)
- [tsnet.Server API](https://tailscale.com/docs/reference/tsnet-server-api)
- [tsnet source](https://github.com/tailscale/tailscale/blob/main/tsnet/tsnet.go)
- [Tailscale Android source](https://github.com/tailscale/tailscale-android)
- [Android userspace networking feature request](https://github.com/tailscale/tailscale/issues/10126)
- [Userspace networking](https://tailscale.com/docs/concepts/userspace-networking)
- [Connection types](https://tailscale.com/docs/reference/connection-types)
- [DERP servers](https://tailscale.com/docs/reference/derp-servers)
- [Tailscale identity](https://tailscale.com/docs/concepts/tailscale-identity)
- [Tags](https://tailscale.com/docs/features/tags)
- [Grants](https://tailscale.com/docs/reference/syntax/grants)
- [Application capabilities](https://tailscale.com/docs/features/access-control/grants/grants-app-capabilities)
- [OAuth clients](https://tailscale.com/kb/1215/oauth-clients)
- [Trust credentials](https://tailscale.com/docs/reference/trust-credentials)
- [Secure auth keys](https://tailscale.com/docs/features/access-control/auth-keys/how-to/secure-auth-keys)
- [Logging](https://tailscale.com/docs/features/logging)
- [Other VPNs](https://tailscale.com/docs/reference/faq/other-vpns)
- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)
- [Tailnet Lock](https://tailscale.com/docs/features/tailnet-lock)
- [Device Approval](https://tailscale.com/docs/features/access-control/device-management/device-approval)

### Hermes

- [Hermes Agent documentation](https://hermes-agent.nousresearch.com/docs/)
- [API Server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server/)
- [MCP](https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp)
- [Plugins](https://hermes-agent.nousresearch.com/docs/user-guide/features/plugins)
- [Platform Adapters](https://hermes-agent.nousresearch.com/docs/developer-guide/adding-platform-adapters)
- [Webhooks](https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks/)

### OpenClaw

- [OpenClaw documentation](https://docs.openclaw.ai)
- [Gateway Protocol](https://docs.openclaw.ai/gateway/protocol)
- [Pairing](https://docs.openclaw.ai/gateway/pairing)
- [Nodes](https://docs.openclaw.ai/nodes)
- [Plugins](https://docs.openclaw.ai/plugins)
- [Android](https://docs.openclaw.ai/platforms/android)

### 通用安全

- [Shizuku architecture](https://shizuku.rikka.app/introduction/)
- [OWASP MASVS](https://mas.owasp.org/MASVS/)
- [OWASP REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html)
- [OWASP Transaction Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html)
- [RFC 9421 HTTP Message Signatures](https://www.rfc-editor.org/rfc/rfc9421.html)
