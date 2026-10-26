# OAI 统一 Core 与多 Agent 迁移

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

1. 新增可安装的 `oai-core` 项目，`oai serve` 提供现有 v2.1 账号及 Android 设备协议。
   CLI/API/MCP 通过同一个 Core 调用设备能力。账号由服务端验证的会话或受限凭据解析，
   调用者不能通过 accountId 改变授权主体。验证签名、撤销、授权版本、幂等、超时和跨账号拒绝。
2. 增加注册表和原生 Session Adapter。Hermes 保持账号隔离；显式配置的 DSH/Codex
   采用 deployment 共享范围，能力按宿主事实发布。测试历史/新会话发现、原生读写流、
   不支持能力、账号范围和实例边界。不得建立脱离原生 Agent 的影子会话数据库。
3. Android 在同一 Core 地址发现 Agent，显示在线状态和范围，使用原生会话接口。
   切换 Agent 只改变聊天状态，不重新连接手机平台通道；不支持功能禁用。
   原 Gateway 不支持 Agent 扩展时可回到原界面，OpenClaw 实现不改动。
4. 运行 Core 集成测试、协议/签名回归、Android 涉及模块的单元测试与构建检查。
   真实 Hermes/DSH/Codex 宿主和 Android 真机联调分别记录，不能以桩测试替代实际通过。

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
| GET /agents | `{agents:[{id,name,kind,online,sessionScope,capabilities}]}` |
| GET /agents/{agent}/sessions | `{sessions:[{id,title,updatedAt,archived}]}` |
| POST /agents/{agent}/sessions | `{title?}` → `{session}` |
| GET /agents/{agent}/sessions/{session}/history | `{messages:[{id,role,text,createdAt}]}` |
| POST /agents/{agent}/sessions/{session}/messages | `{text}` → SSE `message`，JSON `type: delta/done/error` |
| PATCH /agents/{agent}/sessions/{session} | `{action:rename/archive,value?}` |
| DELETE /agents/{agent}/sessions/{session} | 原生删除，需能力声明 |
| POST /agents/{agent}/sessions/{session}/cancel | 原生取消，需能力声明 |

能力为 list/history/create/send/rename/archive/delete/cancel/stream 的布尔值。
sessionScope 为 account 或 deployment。认证先于 Agent/会话查找；所有写操作服务端再次检查能力。
在线状态来自宿主握手/查询结果，不由配置存在与否推断。流中断不能伪造成功回复。

## 数据兼容与回滚

Core 使用专用 storageRoot。上线前停止旧实例并备份完整账号目录、数据库、主密钥引用和协议版本；
恢复演练必须使用副本。不得让旧 Gateway 与 Core 同时写同一 SQLite/账号目录。
Hermes 迁移须保持原账号标识、绑定和会话路径；不同 Hermes 宿主账号没有自动合并步骤。
共享 DSH/Codex 不迁移历史，不重写原生会话文件，不自动推断 OAI 账号所有权。

灰度时先用独立端口/测试账号和设备验证，再把 App 的服务端地址切换到 Core。
回滚时停止 Core、恢复切换前备份并恢复原 endpoint；新 Core 产生的原生 Agent 会话仍由宿主保留，
不尝试反向导入旧 Gateway 映射。设备授权、密钥及未完成请求必须按原认证与过期规则重新校验。
OpenClaw 继续使用原适配器和原协议，未纳入本次服务装配。

## 安全与未来扩展

共享 Agent 会话仅表示当前部署明确接入的会话对已认证账号可见，绝不共享设备授权。
设备调用凭据绑定账号与有效设备授权；Adapter 的原生会话 ID 或客户端账号字段不是授权来源。
未来可在 Session Adapter 的 Principal/policy 边界增加 account scope，但本阶段不实现历史归属绑定。
日志不得记录密码、令牌、签名私钥或设备正文。
