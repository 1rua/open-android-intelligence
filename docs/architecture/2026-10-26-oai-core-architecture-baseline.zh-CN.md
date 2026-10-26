# OAI Core 架构事实基线（2026-10-26）

本文件是仓库内所有面向用户与开发者的文档在描述"统一 Core"时的唯一事实来源。
每条结论都标注了可核对的代码位置。文档更新只允许引用本文件已经写明的内容；
本文件未覆盖的行为，必须先读代码补进本文件，再写进其他文档。

代码基线：分支 `feature/unified-oai-core`，提交 `316a0f55`。

## 1. 交付物与安装形态

- 新增独立可安装 Python 项目 `oai-core/`，发布名 `open-android-intelligence-core`，
  版本 `0.1.0`，`requires-python >= 3.11`
  （[pyproject.toml](../../oai-core/pyproject.toml)）。
- 控制台入口 `oai = oai_core.cli:main`，同一个包里同时提供守护进程命令、CLI 客户端与
  MCP stdio 服务（[cli.py](../../oai-core/src/oai_core/cli.py)、
  [mcp.py](../../oai-core/src/oai_core/mcp.py)）。
- 运行依赖只有 `aiohttp>=3.11,<4`、`mcp>=1.20,<2`，以及固定提交的
  `open-android-intelligence-hermes-gateway @ git+...@b64dda00a0de9ff1a95a375dcf92f62b5a1027b3`。
  设备安全运行时来自这个被固定的上游 GatewayCore，`oai-core` 不重写排队、超时、
  取消、结果状态机（[server.py](../../oai-core/src/oai_core/server.py) 的 `GATEWAY_REVISION`、
  [device_service.py](../../oai-core/src/oai_core/device_service.py)）。

## 2. 单一监听地址承载的三套接口

一个 `oai serve` 进程、一个地址同时提供：

| 接口面 | 路径前缀 | 说明 | 代码 |
| --- | --- | --- | --- |
| Android 设备协议 | `/open-android-intelligence/v2` | 沿用 Gateway Protocol v2 的认证、签名、配对、设备事件 | `CoreTransport.dispatch` |
| 设备 CLI/API/MCP | `/open-android-intelligence/core/v1` | 受限 Bearer token 调用设备能力 | `server.create_app` |
| 原生 Agent 会话 | `/open-android-intelligence/v2/agents` | 多 Agent 原生会话读写 | `sessions.create_agent_routes` |

`/open-android-intelligence/core/v1` 的路由集合（不多不少）：
`POST /tokens`、`DELETE /tokens/current`、`GET /devices`、`GET /capabilities`、
`POST /device-requests`、`GET /device-requests/{id}`、
`POST /device-requests/{id}/cancel`、`POST /device-requests/{id}/ack`。

## 3. 与旧 Gateway 的协议差异

- Core **不提供** `/conversations` 聊天路由：命中
  `/open-android-intelligence/v2/conversations*` 或
  `/conversation-organization*` 抛 `CAPABILITY_UNSUPPORTED`。
- 协商响应剔除可选能力 `conversationUi`（`OAICore._build_negotiation_response`）。
- 协商成功（HTTP 200）时响应头新增 `X-OAI-Agent-Sessions: 1`；新版 App 仅在此标记
  存在时装配原生 `/agents` 界面，旧宿主无需处理未知探测路由。
- v2 的 `messages: chat-v1` 等字段仅作为既有认证/设备协议基线保留，不代表 Core
  提供旧聊天端点。旧 App 聊天需保留或回滚到原 Gateway。

## 4. 原生 Agent 会话面

路由（`sessions.py` `PREFIX`）：

| 方法与路径 | 行为 |
| --- | --- |
| `GET /agents` | `{agents:[{id,name,kind,online,sessionScope,deviceTools,capabilities}]}` |
| `GET /agents/{agent}/sessions` | `{sessions:[{id,title,updatedAt,archived}]}` |
| `POST /agents/{agent}/sessions` | `{title?}` → 回执含 `session` |
| `GET /agents/{agent}/sessions/{session}/history` | `{messages:[{id,role,text,createdAt}]}` |
| `POST /agents/{agent}/sessions/{session}/messages` | `{text}` → SSE `event: message`，`type: delta/done/error` |
| `PATCH /agents/{agent}/sessions/{session}` | `{action:"rename"\|"archive", value?}` |
| `DELETE /agents/{agent}/sessions/{session}` | 原生删除，需能力声明 |
| `POST /agents/{agent}/sessions/{session}/cancel` | 原生取消，需能力声明 |

- 能力枚举固定 9 项：`list`、`history`、`create`、`send`、`rename`、`archive`、
  `delete`、`cancel`、`stream`；值必须是布尔，缺失按 `false`。
- `sessionScope` 只有 `account` 与 `deployment` 两种；`kind == "hermes"` 必须是
  `account`（`AgentRegistry.register`）。
- 配置即发现边界：只有 `agents` 数组显式声明的实例会被发现，不遍历其他用户 HOME、
  其他服务或未注册实例（`build_registry`）。
- 在线状态来自宿主握手/查询（`adapter.status()`，5 秒超时），不由配置存在与否推断。
- 认证先于 Agent/会话查找；每个写操作服务端再次检查能力声明。
- 流中断不伪造成功：SSE 期间每 5 秒写 `: heartbeat` 以观察断连与凭据撤销，
  未收到终态事件时明确发出 `AGENT_STREAM_INTERRUPTED`。

## 5. 三类适配器

| 类型 | 接入方式 | sessionScope | 能力事实 |
| --- | --- | --- | --- |
| `codex` | 显式 `command` 数组 + 显式 `codexHome`，走原生 app-server JSON-RPC `thread/*`、`turn/*` | `deployment` | `deviceTools=false`；除 `delete` 外全部为真 |
| `dsh` | HTTP 会话桥（`integrations/dsh-session-adapter`），默认 `127.0.0.1:8791` | `deployment` | 能力按桥 `/status` 实际返回逐项链制 |
| `hermes` | HTTP 会话桥（`integrations/hermes-session-adapter`），默认 `127.0.0.1:8792`，需一对一账号映射 | `account` | 能力按宿主真实 SessionStore/dispatcher/title 接口逐项判定 |

- Codex `delete` 明确不支持；原生审批请求被显式 `decline` 并报告
  `NATIVE_APPROVAL_UNSUPPORTED`，不伪造用户批准。
- Codex 凭据只能经 `envFrom` 显式映射到模型提供方变量；子进程环境只继承
  `RUNTIME_ENV`/`LC_*` 白名单，不继承 Core 完整环境。
- Codex 启动前检测 `~/.config/oai/client.json` 是否存在、以及 `codexHome/config.toml`
  中是否配置了调用 `oai`/`oai_core.cli`/`oai_core.mcp` 或带 `OAI_` 变量的 MCP server，
  命中即 `AGENT_UNSAFE_DEVICE_CREDENTIALS`。
- DSH 的 `delete`/`archive` 因尚未核实可完整读取的原生生命周期接口而禁用。
- OpenClaw 不注册到本次 Core 装配；`build_registry` 对其类型抛
  `Unsupported Agent type; OpenClaw is not part of this migration`。

## 6. 会话请求预算

- Core 与两条会话桥统一要求紧凑 UTF-8 JSON 请求体**严格小于** 1 MiB
  （`SESSION_REQUEST_MAX_BYTES = 1024 * 1024`）。
- 消息文本另有 262144 字符上限；标题上限 1000 字符。
- Core 在记录写操作回执或开启 SSE **之前**检查实际 UTF-8 字节数，超限返回
  HTTP 413 `MESSAGE_TOO_LARGE`，不会留下结果未知的操作回执。
- 桥传输使用 `ensure_ascii=False` 紧凑 JSON，避免多字节文本被转成更大的转义序列。

## 7. 认证与凭据

- CLI/API 使用受限 Bearer token，绑定 `account + device + pairingGeneration +
  grantRevision`，默认有效期 86400 秒；token 只存 SHA-256 摘要。
- 撤销、改密或授权变更立即使旧凭据失效（`GRANT_STALE` / `SESSION_REVOKED`）。
- 登录限流：每 `(remote, username)` 在 60 秒窗口最多 10 次；并发密码校验最多
  4 个后台任务（`Authentication.MAX_PASSWORD_CHECKS`）。
- 签名请求的 nonce 进入持久台账 `signed_nonces`，窗口为 `2 * maxClockSkewSeconds + 1`。
- 密码校验完成后、签发 token 前在账号写锁内重查凭据摘要与配对行，避免与改密/撤销竞争。
- 客户端配置默认 `~/.config/oai/client.json`，以 `0600` 原子写入；读取时校验为普通文件、
  属主为当前用户且无组/其他权限位，否则 `CLIENT_CONFIG_PERMISSIONS_INVALID`。
- 远程请求不接受账号或设备身份覆盖：Agent 请求体字段必须是指定白名单子集。
- 客户端拒绝重定向，避免把部署凭据转发到别处；响应上限 2 MiB。

## 8. 幂等与回执

- 设备请求幂等账本 key 为 `core-device-idempotency:` + SHA-256(device, generation, key)，
  只存参数摘要与 `requestId`；相同键相同参数返回同一请求，参数不同 `IDEMPOTENCY_CONFLICT`。
- 原生会话写操作用 `native-operations.sqlite`：`begin` 记录摘要，完成时写回执；
  重复相同请求返回已存回执，`send` 命中已执行返回 `REQUEST_ALREADY_EXECUTED`，
  结果未知返回 `OUTCOME_UNKNOWN`（409）。回执只记幂等摘要与管理结果，不存聊天正文。
- 签名写操作绑定原 request ID；CLI 无签名时用 `Idempotency-Key`，格式
  `[A-Za-z0-9._~-]{1,128}`。

## 9. 配置与部署约束

必需字段：`storageRoot`、`masterKeyFile`、`contractRoot`；配置内路径相对配置文件目录解析。

- `masterKeyFile` 必须位于 `storageRoot` 之外，否则启动即失败，以保证账号备份不含主密钥。
- 非 loopback 监听必须提供 TLS 证书与私钥，或显式 `allowInsecureHttp: true`。
- 存在 `tls.certFile` 时自动从证书计算 `tlsSpkiSha256`；TLS 终结在反代之后时须手工配置
  与手机实际所见证书一致的指纹。
- 账号能力开关与手机授权同时生效：`oai policy set --capability ... --enabled|--disabled`。
- `account`、`policy` 是服务端本机管理命令，不进入远程调用面。
- 会话桥 token 至少 32 字符，仅用于 Core 到宿主的内部认证，不能作为 Core 账号或设备凭据。
- 维护循环每秒扫描各账号：按原生到期迁移过期请求、清理终态正文、清理事件；
  单账号失败只告警并下一轮重试。

## 10. Hermes 宿主侧"仅会话"平台插件

`oai_core/hermes_host.py` 由 Hermes 标准 plugin loader 安装，注册相同 canonical/legacy
platform ID 以保留原生会话来源键：

- 只绑定 `127.0.0.1`，前缀 `/oai/session-adapter/v1`，事件流为 `application/x-ndjson`。
- 环境变量：`OAI_HERMES_SESSION_TOKEN`（≥32 字符）、`OAI_HERMES_GATEWAY_ROOT`
  （原 Gateway accounts 目录本身）、`OAI_HERMES_ACCOUNTS`（显式 JSON allowlist）、
  `OAI_HERMES_SESSION_PORT`（默认 8792）；沿用原
  `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE`。
- 不注册旧设备工具，不启动旧 Gateway v2 设备端口；禁止旧 Gateway 与插件同时写同一目录。
- 原生审批立即 `deny` 并发出 `core.approval.unsupported`，流侧报告 `UNSUPPORTED_APPROVAL`。
- 会话标题通过账号绑定后的 SessionDB `get/set_session_title` 读写，按压缩链当前 tip 定位，
  终端修改同样可见；缺少真实标题接口时不宣称支持重命名。
- 会话列表排除尚未绑定宿主的 Gateway 占位行。

## 11. Android 侧

原生会话界面装配相关文件：`AgentSessionClient.kt`、`NativeAgentsController.kt`、
`NativeAgentSessionsScreen.kt`、`GatewayRuntime.kt`；引导教程默认部署项为 OAI Core
（`GatewayTutorials.CORE`，chip 为 `Python 3.11+` / `Protocol v2.1` / `Core v0.1.0`），
OpenClaw 保留旧 Gateway 兼容教程。切换 Agent 只改变聊天状态，不重连手机平台通道。

## 12. 本版已验证与未验证

已运行并通过（本机，2026-10-26）：

```sh
python -m pytest oai-core/tests -q                                # 63 passed, 0 skipped
npx vitest run integrations/dsh-session-adapter/adapter.test.js   # 16 passed, 0 skipped
```

其中 1 项 `test_hermes_native.py` 依赖 `HERMES_SDK_ROOT` 指向固定 checkout
`75b083e9399115b12513385252811f09c3503c90`，并需 `PyYAML==6.0.3`、`python-dotenv==1.2.4`、
`psutil==7.2.2`、`httpx==0.28.1`；未设置时该项明确跳过，**不得把跳过写成已通过**。
本机已用 `.toolchains/hermes-native-clean-75b083e` 实测通过。

CI 见 `.github/workflows/oai-core.yml`（Python 3.12 + Node 24.18.0 + JDK 21 + Gradle 8.12，
Android 侧选中 `NativeAgentsControllerTest`、`NativeAgentBackgroundWorkTest`、
`GatewayTutorialsTest`、`GatewaySessionRefreshTest`、`GatewayWorkbenchLogoutTest`、
`DeviceExecutionRecoveryTest`、`PlatformSnapshotRecoveryTest`）。

未验证，文档不得声称通过：真实 Hermes/DSH/Codex 宿主部署联调、真机本地确认、
真实模型输出、服务端网络可达性。测试中的模型与宿主是原生协议替身。
