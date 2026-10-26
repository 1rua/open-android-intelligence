# OAI Core 设备核心独立化迁移清单

本次迁移使设备协议、安全认证和请求状态机只由 OAI Core 实现，Hermes 以可选会话适配器接入。Android 仍使用 Gateway Protocol v2.1，不通过本次代码迁移修改公开协议版本。

审查基线：OAI `f2c4a4eab081aa48fc0307c51f0f3c8754a5891d`（`dev`）；Hermes Gateway `b64dda00a0de9ff1a95a375dcf92f62b5a1027b3`（目标仓库 `1rua/hermes-android-gateway` 的 `main`）。测试迁移固定读取这个 Hermes 提交，避免把并行开发中的源码误作基准。

## 原反向依赖

原 Core 包元数据运行依赖 `open-android-intelligence-hermes-gateway`，通过 Git 拉取旧包。以下为 Core 全部旧包导入，包含函数内部的延迟导入：

| Core 文件 | 原旧包接口 | 分类及迁移目标 |
| --- | --- | --- |
| `server.py` | `adapter.AccountPasswordVerifier`、`adapter.OpenAndroidPlatformAdapter`、`adapter._sse_frame`；`admin.HostApiCompatibility`；`core.GatewayCore/GatewayError/_now`；`http._status/create_gateway_exposure`；`local_keys.LocalMasterKeyStore/ensure_master_key_file` | 密码验证、设备 HTTP/SSE/WS 和状态机属于通用核心。分别迁入 `oai_core.gateway.verifier`、`transport`、`admin/core/http/local_keys`；不继承 Hermes 平台生命周期。 |
| `auth.py` | `adapter.GatewayRequestVerifier`；`core.GatewayError/VerifiedRequestContext/_now/_request_context` | 通用设备安全：原始请求字节、Ed25519、身份绑定、会话与 nonce 校验。迁入 Core；外层持久 nonce 台账仍由 Core 持有。 |
| `device_service.py` | `capability_policy.capability_catalog`；`core.GatewayError/_jcs/_now` | 通用能力策略、设备请求提交/查询/取消/ACK；统一引用 Core 的唯一实现。 |
| `sessions.py` | `core.GatewayError`、`http._status` | 公共错误模型与状态码属于通用核心；宿主会话通过 Adapter 接口调用。 |
| `cli.py` | `capability_policy.DeviceCapabilityPolicy`、`core.GatewayError` | 通用本地账号管理与权限策略，迁入 Core。 |
| `hermes_host.py` | `adapter.SendResult/OpenAndroidPlatformAdapter/NATIVE_HERMES_AVAILABLE`；`core.resolve_host_approval/create_gateway_core`；`host_history.HermesHistoryPort`；`local_keys.resolve_local_master_key_store`；`plugin.compose_gateway_services/ensure_contract_ready`；`platform_identity.GATEWAY_PLATFORM_IDS` | 宿主适配和历史兼容。Hermes 原生类型、注册和调度进入可选 `oai_hermes_adapter`；移除在会话入口建立设备 GatewayCore 的做法。 |
| `adapters/hermes.py` | 通过 `core`/`runtime` 对象间接调用旧账号会话、原生历史与 dispatcher | 远程会话桥客户端可留在 Core；原生历史、会话生命周期与宿主 dispatcher 属于可选 Adapter。 |
| `test_core_transport.py`、`test_sessions.py`、`test_cli_mcp.py` | 旧包 `core`、`capability_policy` | 改为 Core 命名空间，原真实 HTTP 与 CLI/MCP 断言保留。 |
| `test_hermes.py`、`native_hermes_contract.py` | 旧包 Adapter、平台 ID、审批 resolver | 随可选会话适配器重构；真实 Hermes SDK 合约单独验证，不进入 Core 启动链。 |

## 完整传递依赖处理

原 `adapter.py` 把请求签名验证、设备事件流与 Hermes 平台放在同一模块；即使 Core 绕开父类构造函数，导入模块仍会尝试加载 Hermes SDK，并暴露平台全套方法。原 `core.py` 也有函数内部宿主导入。只替换顶层导入不能完成独立化。

| 原模块或函数 | 归属 | 处理依据 |
| --- | --- | --- |
| `core.py` 的协议协商、`ContractRegistry`、账号存储、设备身份、会话凭据、请求队列和状态转换 | 通用核心 | 保留原算法、表结构和语义，迁入 `oai_core.gateway.core`。所有设备消费者引用同一实现。 |
| `adapter.py` 的原始 HTTP 头解析、单值头检查、Ed25519 签名与流式摘要验证、密码验证 | 通用核心 | 拆为 `oai_core.gateway.verifier`，不加载宿主 SDK。 |
| `adapter.py` 的 SSE/WS 帧、事件订阅、游标恢复、认证与会话失效、附件流式传输 | 通用核心 | 拆为 `oai_core.gateway.transport`，设备服务负责连接生命周期；没有 Hermes 平台父类。 |
| `http.py`、`admin.py` | 通用核心与历史兼容 | 路由、安全状态码、账号/配对/授权管理进入 Core；原宿主兼容元数据不能解释为已加载 Hermes。 |
| `capability_bindings.py`、`capability_policy.py` | 通用核心 | Schema 验证、schemaSha256、能力授权与能力路由必须与请求状态机保持同一权威来源。 |
| `credentials.py`、`local_keys.py`、`audit.py`、`account_paths.py` | 通用核心 | 保留凭据格式、认证加密和账号目录约束；Core 默认目录不再查询 `hermes_constants` 或 Hermes HOME。旧显式路径和密钥格式用于迁移兼容。 |
| `backup.py`、`identity_rotation.py`、`pairing_invites.py` | 通用核心 | 依赖上述账号存储和设备身份，无需 Hermes SDK。 |
| `conversation_management.py`、`conversation_workflow.py`、`history_media.py` 中持久化部分 | 历史兼容公共库 | 旧账号 DB 的会话绑定、压缩链关联、操作状态和附件存储格式需要保留；这不代表 Core 承担 Hermes 原生调度。 |
| `core.resolve_host_approval` → `tools.approval` | 宿主适配 | Core 不再导入 Hermes 审批运行时；宿主 resolver 显式注入，无 resolver 时不能宣传或处理审批。 |
| `core.py` → `host_commands.command_catalog` → Hermes 原生命令目录 | 宿主适配 | Core 默认无宿主命令；命令目录由可选宿主显式提供。 |
| `core.py` → `host_history.HermesHistoryPort` | 宿主适配 | 原生 SessionStore/SessionDB 适配移至可选包；公共持久化库通过已注入接口工作。 |
| `history_media.py` 中 `gateway.platforms.base` 附件接收接口 | 宿主适配 | 原生媒体加载移至可选包；Core 不反向加载宿主平台类型。 |
| `platform_identity.py`、`host_history.py`、`host_commands.py`、`plugin.py` 的平台/插件注册、原生 dispatcher 与生命周期 | 宿主适配 | 留在可选 Hermes Session Adapter。保留既有 canonical/legacy 平台 ID 及原生会话标识，避免静默退回 LOCAL。 |
| `device_tools.py` 中 Hermes 工具注册、trusted turn 和 host hooks | 宿主适配 | 不作为 Core 设备运行前提。Agent 设备操作使用 Core CLI/MCP/API，桥 Token 仅认证内部会话。 |
| `contract_assets.py` 的源码 pin、缓存与下载逻辑 | 历史安装兼容 | Core 构建从仓库唯一 `gateway-contract` 来源打包所需资产；运行时优先显式路径或随包资产，不能通过下载旧包恢复依赖。 |

Core 直接声明 `aiohttp`、`mcp`、`jsonschema` 和 `cryptography`。标准库的 SQLite、哈希、文件路径与异步原语不需要额外 Python 包。公开 Gateway Protocol 的 Schema、共享向量、签名 domain、Core Schema Hash 算法不因包命名空间变更而改变。共享向量里的历史 `implementation: hermes-python` 标签是兼容元数据，不代表 Core 运行了 Hermes 宿主。

## 数据保留与切换约束

本次迁移不把 Hermes 原生账号库合并到 Core 设备库，也不自动重新建账号或配对。Core 设备库与可选 Adapter 的旧账号目录由不同配置明确指定。连接关系为 `OAI Account → Hermes Account → Native Session`，映射必须一对一；用户请求不能提供原生会话路径或替换认证账号。

原账号路径仍通过不透明账号 ID 的 SHA-256 映射到账号目录，原 `gateway.sqlite`、原生 SessionStore/SessionDB、会话 ID、压缩链及 AEAD 密钥引用保持可读。密码摘要、配对 generation、grantRevision、请求状态、幂等台账、事件 sequence/cursor、未知结果和结果 ACK 的语义保持原样。既有数据库的必要初始化/历史格式迁移由原存储流程处理；这次拆包不增加新的表格式版本。Core 的 API Token 与 nonce 台账仍位于自己的 `core-tokens.sqlite`。

切换前必须停掉原独立 Gateway，再对账号目录、原生 Hermes 会话目录和主密钥做一致备份。数据库应在服务停止后使用 SQLite 备份接口或完整目录快照，不能只复制正在写入的主 `.sqlite` 而遗漏 WAL。账号备份与主密钥分开保存，主密钥保持当前用户私有权限，不能写入公开启动脚本。

先安装并验证独立 Core，再安装可选 Hermes Adapter。Adapter 应取得旧账号目录的独占写锁；锁不能取得时拒绝启动。启动 Core 设备服务不要求访问旧 Hermes 目录或 SDK。切换期间不要同时启动旧 Gateway 与新 Adapter，也不要让两个进程以不同路径指向同一账号目录。

回滚时先停新 Adapter/Core，保留切换后的目录快照，再恢复切换前对应版本、完整账号/原生会话快照和原密钥引用，最后只启动一套旧服务。若切换后已有新写入，不直接用旧备份覆盖；先确认需保留的数据及恢复时间点。仅回滚 Python 包不等于回滚持久状态。

## 自动化证据范围

迁移测试位于 `oai-core/tests/gateway_runtime/`，固定继承原提交的通用测试和共享协议向量，仅修改包导入、移除一项 Hermes 插件注册断言。覆盖账号隔离、密码/撤销、配对与授权版本、签名基础向量、Schema、幂等冲突、Claim/Result/ACK、取消/超时/未知结果、重启恢复、事件游标、附件策略、加密、审计、备份和身份轮换。

`test_independence.py` 在新 Python 进程中，先拒绝旧 Gateway 与 Hermes SDK 的所有导入，再启动真实 HTTP Core，执行协商、密码登录、Ed25519 签名、能力发布、Core Token、设备请求、Claim、Result 和 ACK；同时检查 Core 包元数据与所有 Python AST（含函数内部）不存在旧命名空间导入。源码 editable 场景允许使用仓库唯一契约来源，干净 wheel 安装检查必须证明资产来自包内且不要求源码仓库。

原有 `test_core_transport.py` 保留真实 SSE/WS、nonce 防重放、跨账号/设备认证、API 与手机共享队列、取消与恢复；新增原始 HTTP 字节篡改、重复安全头、SSE/WS 按游标断线恢复与凭据撤销后关闭流的检查；CLI/MCP、DSH/Codex 回归分别保留。可选 Hermes 原生 SessionStore、SessionDB、压缩链、消息 dispatcher 和管理操作应使用明确固定的 Hermes SDK 验证，并与无 SDK Core 检查分开。

自动化协议测试不能证明 Android 真机已连接，也不能证明实际用户账号的 Hermes 宿主部署已经验收。最终测试次数、命令、失败项与远程 CI 状态由实施报告按提交 SHA 记录。
