# OAI 统一 Core 与多 Agent 迁移

**2026-10-28 后续授权修订**：本文保留当时的统一 Core 迁移计划。设备能力规则由 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md) 规定：Android 唯一授权，普通前台能力默认开启，Core 不作账号级能力许可裁决；后台、系统权限与敏感操作仍单独约束。该规则已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`；当前结果见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。连接认证、设备绑定、协议版本栅栏及共享会话不得继承设备凭据的规则继续有效。

> 当前实现基线：分支 `feature/unified-oai-core` @ `316a0f55`。
> 各阶段完成状态以
> [架构事实基线](../architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)
> 第 12 节为准。

## 审查基线与实际能力

本次基于 OAI `dev` 的 `157e7e5b`，使用独立 `feature/unified-oai-core` worktree。
Hermes Gateway 审查基线为 `b64dda00a0de9ff1a95a375dcf92f62b5a1027b3`；
DSH Gateway 为 `26935f9`；Codex 官方接口参考为 `970111f`。这些是源码审查记录，
不是已部署宿主或真机验证的声明。

现有 Android `GatewayRuntime` 已有一个服务地址、账号认证、签名、续期及平台事件通道。
`DeviceExecutionDriver`、Android Platform Kernel、签名 `.alp`、WASM 沙箱与本地权限确认可保留。
现有 Hermes Python GatewayCore 已实现账号目录隔离、设备配对、授权版本、请求去重、
nonce、超时、取消和结果接收；统一服务通过固定版本依赖复用该实现，不重新实现这些状态机。
现有 Gateway 随宿主注册和管理原生会话，但不能据此宣称已支持任意 Agent 的终端会话发现。

Hermes 的原生历史通过 SessionDB 和 AgentSessionBindings 读取，必须保持账号绑定边界。
DSH 的宿主 `sessionQuery.listSessions/readSurface` 与 `sessionController` 是会话接入点；
仅枚举旧 Gateway conversations 会遗漏终端会话。Codex 的 app-server 提供 JSON-RPC
thread/list、thread/read、thread/start、turn/start 和增量通知，应连接明确配置的实例。
禁止默认遍历其他用户 HOME、其他服务或未注册实例。

## 分阶段交付与验收门禁

各阶段当前状态（依据架构事实基线第 12 节）：

1. ✅ 已完成（协议替身测试通过）——新增可安装的 `oai-core` 项目，`oai serve` 提供现有 v2.1 账号及 Android 设备协议。
   CLI/API/MCP 通过同一个 Core 调用设备能力。账号由服务端验证的会话或受限凭据解析，
   调用者不能通过 accountId 改变授权主体。签名、撤销、授权版本、幂等、超时和跨账号拒绝已有测试覆盖。
2. ✅ 已完成（协议替身测试通过）——注册表和原生 Session Adapter。Hermes 保持账号隔离；显式配置的 DSH/Codex
   采用 deployment 共享范围，能力按宿主事实发布。历史/新会话发现、原生读写流、
   不支持能力、账号范围和实例边界已有测试；未建立脱离原生 Agent 的影子会话数据库。
   **真实 Hermes/DSH/Codex 宿主联调未完成。**
3. ✅ 代码完成（Android 单元测试通过）——Android 在同一 Core 地址发现 Agent，显示在线状态和范围，使用原生会话接口。
   切换 Agent 只改变聊天状态，不重新连接手机平台通道；不支持功能禁用。
   原 Gateway 不支持 Agent 扩展时可回到原界面，OpenClaw 实现不改动。
   **Android 真机验收未完成。**
4. ⏳ 部分完成——Core 集成测试、协议/签名回归、Android 涉及模块的单元测试与构建检查已通过
   （Python 63 项、DSH 桥 16 项、Android 选中回归 85 项，详见验证记录）；
   真实 Hermes/DSH/Codex 宿主和 Android 真机联调**尚未执行**，不能以桩测试替代实际通过。

## 协议增量

保留 `/open-android-intelligence/v2` 的认证、设备和平台事件协议，不修改原有 schema hash。
Core 不提供旧 Gateway 的 `/conversations` 聊天路由，不发布对应可选 `conversationUi`
能力。v2 协商中固定的 `messages:chat-v1` 等字段仅作为现有认证/设备协议的兼容基线保留；
会话客户端必须使用支持 `/agents` 的新版 App。回滚到旧 App 时须同时恢复旧 Gateway endpoint，
不能将旧 App 连接新 Core 当作完整聊天兼容路径。
Core 在成功协商响应中用 `X-OAI-Agent-Sessions: 1` 声明原生 Agent 扩展。
新版 App 仅在此标记存在时装配 `/agents` 界面；旧宿主无需处理未知探测路由，OpenClaw 插件保持原样。
新增以下签名或受限凭据保护的扩展：

| 方法和路径（均位于 v2 前缀） | 响应/行为 |
| --- | --- |
| GET /agents | `{agents:[{id,name,kind,online,sessionScope,deviceTools,capabilities}]}` |
| GET /agents/{agent}/sessions | `{sessions:[{id,title,updatedAt,archived}]}` |
| POST /agents/{agent}/sessions | `{title?}` → `{session}` |
| GET /agents/{agent}/sessions/{session}/history | `{messages:[{id,role,text,createdAt}]}` |
| POST /agents/{agent}/sessions/{session}/messages | `{text}` → SSE `message`，JSON `type: delta/done/error` |
| PATCH /agents/{agent}/sessions/{session} | `{action:rename/archive,value?}` |
| DELETE /agents/{agent}/sessions/{session} | 原生删除，需能力声明 |
| POST /agents/{agent}/sessions/{session}/cancel | 原生取消，需能力声明 |

能力为 list/history/create/send/rename/archive/delete/cancel/stream 的布尔值；描述符另含
`deviceTools`（当前均为 false）。sessionScope 为 account 或 deployment。认证先于 Agent/会话查找；
所有写操作服务端再次检查能力。该路由的规范正文见
[Gateway Protocol v2 第 17 节](../contracts/gateway-protocol-v2.md)。
在线状态来自宿主握手/查询结果，不由配置存在与否推断。流中断不能伪造成功回复。

## 数据兼容与回滚

Core 使用专用 storageRoot。上线前停止旧实例并备份完整账号目录、数据库、主密钥引用和协议版本；
恢复演练必须使用副本。不得让旧 Gateway 与 Core 同时写同一 SQLite/账号目录。
Hermes 迁移须保持原账号标识、绑定和会话路径；不同 Hermes 宿主账号没有自动合并步骤。
共享 DSH/Codex 不迁移历史，不重写原生会话文件，不自动推断 OAI 账号所有权。
OpenClaw 未纳入本次 Core 装配，继续使用原适配器和原协议（见架构事实基线第 5 节）。

灰度时先用独立端口/测试账号和设备验证，再把 App 的服务端地址切换到 Core。
回滚时停止 Core、恢复切换前备份并恢复原 endpoint；新 Core 产生的原生 Agent 会话仍由宿主保留，
不尝试反向导入旧 Gateway 映射。设备授权、密钥及未完成请求必须按原认证与过期规则重新校验。

## 安全与未来扩展

共享 Agent 会话仅表示当前部署明确接入的会话对已认证账号可见，绝不共享设备授权。
设备调用凭据绑定账号与有效设备授权；Adapter 的原生会话 ID 或客户端账号字段不是授权来源。
未来可在 Session Adapter 的 Principal/policy 边界增加 account scope，但本阶段不实现历史归属绑定。
日志不得记录密码、令牌、签名私钥或设备正文。
