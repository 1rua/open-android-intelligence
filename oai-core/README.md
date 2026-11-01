# OAI Core

一个服务监听地址承载 Android 登录、配对、设备事件与原生 Agent 会话。
`oai serve`、CLI 与 MCP 属于同一个 Python 项目；设备协议、安全认证与请求状态机
由 `oai_core.gateway` 提供唯一实现。Core 安装不拉取 Hermes Gateway，启动不需要
Hermes SDK 或 Hermes 插件。可选 Hermes Session Adapter 依赖 Core 的公共库。
Android Platform Kernel、`.alp` 签名、WASM 沙箱与本地确认机制继续承担手机本地安全检查。

> **2026-10-28 授权规则**：Android 客户端是设备能力授权的唯一权威。设备插件安装且启用后能力默认开启，实际执行仍受本地显式开关、Android 系统权限、数据范围和必要敏感确认约束。Core 负责连接认证、设备绑定、能力发现、协议验证及请求路由，不再通过账号能力开关决定设备能力是否可用。规则已在 `feature/android-device-authority-20261028` 分支实施，待合入 `dev` 和真机验收。实现与当前验证见[实施记录](../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)；旧 `dev` 基线见[修复计划](../docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)。

同一个进程对外提供三套接口：Android 设备协议 `/open-android-intelligence/v2`、
设备 CLI/API/MCP `/open-android-intelligence/core/v1`、原生 Agent 会话
`/open-android-intelligence/v2/agents`。每条结论的可核对依据见
[架构事实基线](../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)。

## 安装与启动

### 一键安装脚本（推荐）

使用 curl 命令完成 Core 依赖检查、环境安装、可选 Agent 探测与初始化配置向导：

```sh
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash
```

或在需要单独重新配置端口、账号或 Agent 时运行独立配置脚本：
```sh
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/setup.sh | bash
```

或在仓库根目录直接运行：
```sh
./scripts/install.sh
# 或
./scripts/setup.sh
```

向导只安装 Core；发现 Hermes 不会自动安装插件、停用旧网关或启用会话桥。
选择接入后，Core 生成显式的一对一账号映射；Hermes 宿主包须在宿主自己的 Python
环境另行安装。DSH 和 Codex 的安装、使用均不要求存在 Hermes。

向导安装完成后，会自动将 `~/.local/bin` 注入 PATH 并在其中配置 `oai` 启动包装命令：
直接在终端输入 `oai` 即可一键启动服务；带参数执行时（如 `oai devices`、`oai mcp`）作为 CLI 客户端管理工具。

桥接 Token 生成或复用到 `runtime/secrets/adapter-tokens.env`，文件权限为 `0600`，
父目录为 `0700`；`start-core.sh` 只读取该私密文件，不嵌入凭据值。
重新运行 `setup.sh` 会备份旧配置并保留已有存储路径、主密钥、账号密码、配对和
Agent 实例，不自动重置账号密码或旋转桥接 Token。移除既有 Agent 时显式编辑 `agents`。

### 手动安装与启动

Python 3.11+。在仓库根目录安装，或直接安装构建出的 Core wheel；协议契约随包发布：

```sh
python -m venv .venv
.venv/bin/python -m pip install -e ./oai-core
source .venv/bin/activate
.venv/bin/oai account create --config oai-core/examples/core.json --username alice
.venv/bin/oai serve --config oai-core/examples/core.json
```

账号密码通过交互输入，不出现在命令行参数中。示例默认只监听本机，未注册 Agent 时列表为空。
配置中的路径相对于配置文件；`contractRoot` 可省略，默认使用包内真实协议契约。
从源码构建时，这些资产只取自本仓库的 `gateway-contract`，不维护第二份协议源文件。
运行数据与主密钥分别位于 `runtime/accounts` 和
`runtime/secrets`，均不提交；主密钥不能位于账号备份目录内。
手机远程连接时配置 `listen.host`、`tls.certFile` 和 `tls.keyFile`，使用同一个 HTTPS
地址及端口登录。TLS 终止在反向代理时还须配置与手机看到的证书一致的 `tlsSpkiSha256`；
只在受控开发环境显式使用 `allowInsecureHttp`。

先在 Android 登录 Core 并配对，再安装、启用设备插件。当前 feature 分支规则无需 Core 或 Agent Gateway 再授予账号级设备能力；本地关闭、系统权限不足和敏感确认未完成仍会阻止执行。调用方单独取得绑定目标设备的 Core 凭据，例如：

```sh
oai login --url https://core.example --username alice --device DEVICE_ID
oai capabilities
```

`account` 是服务端本机身份管理命令。旧 `dev` 基线中的 `policy` 账号能力开关不再参与本分支的设备授权裁决；它不是目标设计的操作步骤。出现 `GATEWAY_DISABLED` 时，应先确认客户端与 Core 是否完成新授权模型协商；不要另建服务端授权入口。

远程 CLI/API/MCP 请求不接受账号或设备身份覆盖。登录凭据绑定该账号下已配对设备、配对代次和过期时间，默认有效期 86400 秒；凭据撤销或配对失效会终止访问。Android 能力发布的 `grantRevision` 变化不使 Token 或端点 route 失效；新请求在事务内绑定当前发布版本，已入队请求仍绑定原版本，旧 GET/Claim/Result/幂等重放继续被 `GRANT_STALE` 或历史请求 fence 拒绝。
客户端配置默认写入 `~/.config/oai/client.json`，以 `0600` 保存，退出使用 `oai ... logout`
撤销凭据。上述登录和后续命令使用同一个默认客户端配置。需要自定义路径时，每条客户端命令均加
`--client-config`。`--device` 取 `oai devices` 返回的设备 ID。在操作人的独立终端环境登录，
避免给共享原生 Agent 的运行身份保存设备凭据。

## 设备 CLI / API / MCP

```sh
oai devices
oai capabilities
oai invoke org.openandroidintelligence.notifications.query --version 1.0.0 --parameters '{"limit":1}' --agent-name Hermes --purpose '读取用户指定范围内的通知' --idempotency-key read-once --wait 360
oai status REQUEST_ID
oai cancel REQUEST_ID
oai ack REQUEST_ID
oai mcp
```

能力 ID、版本和参数以 `capabilities` 返回的实际插件 schema 为准。
`--wait` 只等待服务端状态，不创建另一套任务状态；等待结束后可用 `status` 继续读取原请求。
相同幂等键及参数返回同一请求，参数不同返回冲突。高风险操作仍需手机本地确认。
`ack` 在消费设备结果后清理临时结果正文。MCP 使用官方 SDK 的 stdio transport，工具均调用同一 API。
新 REST 请求必须携带 `caller: {agentName, purpose}`；CLI 使用 `--agent-name` 和 `--purpose`，
MCP `oai_invoke` 使用 `agent_name` 和 `purpose`。两项必须非空，缺失或仅含空白返回
`CALLER_DECLARATION_REQUIRED`，不会替旧调用者编造声明。名称与目的仅用于展示和审计；
账号、设备和配对仍来自认证上下文。已持久化的旧请求可以保持无声明，不能因此重新执行或绕过旧请求 fence。
协议扩展通过现有 Schema hash 协商；新旧不匹配返回 `PROTOCOL_INCOMPATIBLE`。

Android 使用签名 `POST /open-android-intelligence/v2/device-requests/{id}/authorization`，
提交 `{"state":"awaiting_confirmation"}` 或 `{"state":"executing"}`。
已 Claim 的未终止请求可更新可选 `localAuthorizationState`，CLI/API/MCP 状态读取均可见。
该字段不批准操作、不改变请求状态或期限。在线临时高权限请求默认 360 秒，覆盖 300 秒手动确认及有限执行；
离线仍不可入队，调用者显式更短的内部 deadline 继续生效。HTTP 单次连接超时不等于请求有效期；
`--wait` 默认为不等待，主动设置的等待时间到期后不会取消 Core 请求。
结果未产生时 ACK 返回 `RESULT_NOT_READY`；消费后可附 `resultDigest` 确认，重复 ACK 返回原持久回执。

直接 `invoke` 会解释不可用原因，不要求先查询能力目录。未匹配到可调用 binding 时，Core 只使用同设备、
同配对代次和当前发布 revision 的 Android inventory 生成拒绝诊断；inventory 不会创建 binding、
批准请求或替代 Android 执行前的权限复核。CLI JSON 错误与 MCP 工具错误均保留 `details`。

| 情形 | 直接调用错误或请求状态 |
| --- | --- |
| 缺少 Android 系统权限 | HTTP 403 `ANDROID_PERMISSION_REQUIRED`；`details.reason` 保留 `READ_SMS_REQUIRED`、`READ_CALL_LOG_REQUIRED`、`NOTIFICATION_ACCESS_REQUIRED` 等 Android 原因 |
| 本地能力或插件关闭 | HTTP 403 `LOCAL_CAPABILITY_DISABLED`；`details.androidStatus/reason` 区分本地能力关闭和插件关闭 |
| 完整 inventory 中未安装相应插件 | HTTP 404 `CAPABILITY_NOT_INSTALLED`，`details.reason=NOT_IN_ANDROID_INVENTORY` |
| 提供者未就绪、未选定，或能力/版本不存在 | HTTP 409 `CAPABILITY_UNAVAILABLE`；保留具体 provider 原因，或 `CAPABILITY_NOT_IMPLEMENTED` / `CAPABILITY_VERSION_UNAVAILABLE` |
| 发布过时或缺少完整 inventory | HTTP 409 `CAPABILITY_UNAVAILABLE`，`details.reason=SYNC_REQUIRED` 或 `ANDROID_STATE_UNKNOWN`，不推断已安装或系统权限已授予 |
| 高权限请求要求在线设备 | HTTP 503 `DEVICE_OFFLINE`，`details.reason=LIVE_CONNECTION_REQUIRED`；可重试，不创建请求 |
| read/sync/write 允许的离线队列 | 请求保持 `pending`，`details={reason:DEVICE_OFFLINE,queued:true,source:core-connection}`；既有风险队列及期限不变 |
| 名称或目的缺失、空白 | HTTP 400 `CALLER_DECLARATION_REQUIRED`，不编造声明 |
| 正在等待 Android 用户确认 | `state=claimed`、`localAuthorizationState=awaiting_confirmation`、`details.reason=awaiting_confirmation` |
| Android 用户拒绝 | Android 原始 `result` 保留，`details.reason=user_rejected`、`androidReason=LOCAL_USER_REJECTED` |
| Android 确认或执行超时 | Android 原始 `result` 保留，`details.reason=timeout`，`details.phase=confirmation` 或 `execution` |
| Core 请求期限已到 | 保留 `expired` 或 `outcome_unknown`；`details.reason=timeout`、`source=core-deadline`，已 Claim 的结果仍可能未知，不推断副作用已停止 |

诊断 `source` 区分 `android-inventory`、`android-publication`、`android-progress`、`android-result` 与
`core-connection/core-deadline`。未收到 Android 确认时不推断用户拒绝、超时原因或操作成功；
ACK/期限清理后不再保留的结果正文也不会被重建。认证、配对/revision/fence 拒绝继续使用原错误。

设备 REST 前缀为 `/open-android-intelligence/core/v1`：
`POST /tokens`、`DELETE /tokens/current`、`GET /devices`、`GET /capabilities`、
`POST /device-requests`、`GET /device-requests/{id}`、`POST /device-requests/{id}/cancel|ack`。
除登录外使用受限 Bearer token；写请求需要 `Idempotency-Key`。
Android 继续使用原 `/open-android-intelligence/v2` 的签名、配对及设备事件协议。
会话使用本次新版 App 的 `/agents` 接口；旧 `/conversations` 聊天 API 在 Core 不可用，
对应可选会话能力不会宣告。旧 App 聊天需保留或回滚到原 Gateway。
成功协商响应头 `X-OAI-Agent-Sessions: 1` 用于新版 App 选择原生会话界面，旧宿主无需接收 `/agents` 探测。

### 主动推送接收端点

```sh
oai endpoint register --registration-key receiver-main --label Hermes --ttl-seconds 3600
oai endpoint list
oai endpoint receive ENDPOINT_ID
oai endpoint ack ENDPOINT_ID DELIVERY_ID EVENT_ID
oai endpoint unregister ENDPOINT_ID
```

接收端复用现有设备绑定 Bearer 凭据；MCP 对应工具为 `oai_register_endpoint`、`oai_endpoints`、
`oai_receive_deliveries`、`oai_ack_delivery`、`oai_unregister_endpoint`，没有独立 Agent 身份体系。
注册本身不授予设备数据订阅权限，Android 用户仍需选择接收 route。

Core v1 接口为 `POST|GET /endpoints`、`DELETE /endpoints/{endpointId}`、
`GET /endpoints/{endpointId}/deliveries`、`POST /endpoints/{endpointId}/deliveries/{deliveryId}/ack`。
注册 JSON 为 `{"registrationKey":"receiver-main","label":"Hermes","ttlSeconds":3600}`。
发现响应为 `{coreInstanceId,accountScope,endpoints}`，即使端点列表为空也保留 Core 与账号范围。
稳定 route 为 `{coreInstanceId, accountScope, endpointId, generation}`：Core 实例身份持久化，账号范围不透明，
端点 ID 由服务端生成，重建或更换接收凭据推进代次；label 可重命名但不参与身份判断。
只有拥有端点的当前合法凭据可以接收、确认和注销；发现及 Android 发送仍受账号、设备和配对绑定约束。
跨 Core/账号目标由 Android 使用各条已认证连接分别发现和发送，不开放会话、历史或附件的跨账号读取。

Android 签名接口为 `GET /open-android-intelligence/v2/push/endpoints`、
`POST /open-android-intelligence/v2/push/deliveries`、`GET /open-android-intelligence/v2/push/deliveries/{deliveryId}`、
`POST /open-android-intelligence/v2/push/deliveries/revoke`。
提交 JSON 为 `{eventId, route, eventType, occurredAt, expiresAt, payload}`，时间使用 UTC 毫秒格式，
撤销 JSON 为 `{route,eventIds:[...]}`，按稳定事件 ID 精确撤销，批量为 1–128 个且不能重复；
未知或尚未投递的事件 ID 幂等忽略，响应不泄露匹配数量。只有明确移除接收端点时才省略 `eventIds`，
使用兼容的 `{route}` 撤销该 route 全部未完成投递。两种格式均返回 `{route,revoked:true}`，
复用现有 Ed25519/session/nonce/negotiation 校验和 v2 envelope。
回执为 `{deliveryId,eventId,route,state,payloadExpiresAt,acknowledgedAt}`，时间缺失时为 null。

Android 保存长期加密队列；Core 仅保存 endpoint、摘要去重与 ACK 等元数据，正文加密暂存最多 60 秒。
每条正文最多 64 KiB，每账号最多 128 条、合计 1 MiB 活跃正文；超限明确返回 `PUSH_BUFFER_FULL`，
不会静默丢弃新事件。租约为 60 秒至 24 小时，默认 1 小时，事件截止最多 7 天。
`pending` 只代表已暂存；`payload_expired` 要求 Android 使用原 eventId 与原内容补投；
`acknowledged` 才表示接收端确认消费。另有 `revoked`、`expired` 终态。
逐端 ACK 请求为 `{"eventId":"..."}`；读取不自动 ACK，未读取即 ACK 返回 `DELIVERY_NOT_RECEIVED`。
ACK 后重复投递或 ACK 返回原回执且不恢复正文，同一投递身份内容改变返回 `IDEMPOTENCY_CONFLICT`。
撤销会清理相应待投递正文并保留终态去重事实；Android 同时取消本地发送并重新核对当前授权后才能发送新事件。

## 会话接口

原生会话路由位于 `/open-android-intelligence/v2/agents`：
`GET /agents`、`GET|POST /agents/{agent}/sessions`、
`GET /agents/{agent}/sessions/{id}/history`、
`POST /agents/{agent}/sessions/{id}/messages`（SSE，`type: delta/done/error`）、
`PATCH /agents/{agent}/sessions/{id}`（`action: rename|archive`）、
`DELETE /agents/{agent}/sessions/{id}`、
`POST /agents/{agent}/sessions/{id}/cancel`。

能力为 `list`、`history`、`create`、`send`、`rename`、`archive`、`delete`、`cancel`、
`stream` 九项布尔值，缺失按 false；`sessionScope` 取 `account` 或 `deployment`。
认证先于 Agent 与会话查找，每个写操作服务端再次检查能力声明。流中断不伪造成功：
未收到终态事件时明确发出 `AGENT_STREAM_INTERRUPTED`。

## 接入原生 Agent

只有 `agents` 显式声明的实例会被发现，不遍历其他用户目录或服务。
所有账号登录后能读取该部署的 DSH/Codex 共享会话，包括终端创建的原生历史。
Hermes 单独保留账号绑定；所有操作均读取或修改真实宿主，不保存第二份聊天历史。
在线状态由宿主握手/查询得出；宿主不支持的能力在服务端和 App 同时禁用。

原生会话请求在 Core 与 Hermes/DSH 会话桥均须小于 1 MiB；消息另保留 262144 字符上限。
Core 在记录写操作或开启 SSE 前检查紧凑 JSON 的实际 UTF-8 字节数，过大请求返回
HTTP 413 `MESSAGE_TOO_LARGE`，不会创建未知结果的操作回执。桥传输使用紧凑 UTF-8 JSON，
避免把多字节文本转成更大的 ASCII 转义序列。

Codex 使用明确指定的 app-server 和实例目录：

```json
{
  "type": "codex",
  "id": "codex",
  "name": "Codex",
  "command": ["codex", "app-server"],
  "codexHome": "../runtime/codex"
}
```

把条目加入配置的 `agents` 数组，并为该目录配置可用的原生 Codex 实例。
原生 app-server 的真实 thread API 提供列表、历史、创建、发送、重命名、归档和取消；
删除暂不支持。原生审批请求会明确拒绝并报告，不伪造用户批准。
模型提供方的凭据通过 `envFrom` 显式引用，例如
`"envFrom": {"OPENAI_API_KEY": "CODEX_MODEL_API_KEY"}`；不会继承 Core 的完整环境。
部署执行身份与凭据检测见 [Codex 设备隔离边界](docs/codex-device-isolation.zh-CN.md)。
共享 Agent 不自动获得任何 Android 设备凭据；独立 CLI/MCP 需使用经 Core 认证并绑定目标设备的调用凭据。凭据只界定调用身份和设备范围，不替代 Android 的设备能力决定，也不构成账号级能力开关。
共享原生 Agent 应运行在独立服务身份下，不得配置任意个人账号的常驻 OAI CLI/MCP 凭据。

DSH 接入宿主的轻量 Session Adapter：

```json
{
  "type": "dsh", "id": "dsh", "name": "DSH",
  "url": "http://127.0.0.1:8791", "tokenEnv": "OAI_DSH_ADAPTER_TOKEN"
}
```

宿主安装方式见 [DSH Session Adapter](../integrations/dsh-session-adapter/README.md)。
它使用原生 `sessionQuery.listSessions/readSurface` 与 `sessionController`，不处理设备调用。
删除与归档因尚未核实可完整读取的原生生命周期接口而不宣告，App 侧对应操作禁用。
环境变量引用的适配器令牌用于 Core 到宿主的内部认证，不能作为 Core 账号或设备凭据。DSH 会话适配器不提供额外设备能力授权；Agent 需要设备工具时统一通过 Core CLI/MCP/API，并由 Android 裁决实际执行。

Hermes 通过独立可选包 `oai-hermes-session-adapter` 保留账号映射。
在 Hermes 自身的 Python 环境安装当前配套版本，再通过其原生插件加载器安装入口：

```sh
python -m pip install ./oai-core ./integrations/hermes-session-adapter
hermes plugins install ./integrations/hermes-session-adapter --enable
```

宿主环境须先配置好明确的原账号目录、账号清单、原主密钥与会话桥 Token。
新 Adapter 只提供原生会话，不运行设备监听器或设备请求调度器。

Core 配置条目为：

```json
{
  "type": "hermes", "id": "hermes", "name": "Hermes",
  "url": "http://127.0.0.1:8792", "tokenEnv": "OAI_HERMES_SESSION_TOKEN",
  "accounts": {"alice": "alice", "bob": "bob"}
}
```

映射由管理员配置，必须一对一；不会依据 App 传入的账号选择身份。
宿主部署与原账号目录复用见 [Hermes Session Adapter](../integrations/hermes-session-adapter/README.md)。
先验证独立 Core 并备份，再停止原 Gateway、启动新 Adapter；保留旧安装用于回滚。
新 Adapter 对原账号库持有独占锁，禁止新旧进程同时写入。
Core 设备数据库另设目录，不会与 Hermes 的原账号库合并；主密钥也不自动复制或替换。
完整步骤见[独立部署与安全切换](../docs/deployment/2026-10-26-core-independent.zh-CN.md)。
OpenClaw 不注册到本次新 Core 装配，继续沿用旧接入方式；其外部实现与旧 DSH/Hermes 独立 Gateway 未在本次修订中验证，不能据此宣称已支持新的授权规则。

## 协议、迁移与验证

新增 Agent API、数据兼容、备份和回滚步骤见
[迁移方案](../docs/plans/2026-10-25-unified-core-migration.zh-CN.md)。
签名写操作绑定原 request ID；原生操作回执只记录幂等摘要和管理结果，不存聊天正文。
流中断或进程重启后未知的写操作不会自动重发，先读取原生历史确认结果。

```sh
python -m pip install ./oai-core pytest pytest-asyncio
python -m pytest oai-core/tests tests/test_install_script.py -q \
  --ignore=oai-core/tests/test_hermes.py --ignore=oai-core/tests/test_hermes_native.py
npm ci
npx vitest run integrations/dsh-session-adapter/adapter.test.js
```

固定 Hermes 原生 SDK 的契约回归需要额外设置：

```sh
export HERMES_SDK_ROOT=/按固定 checkout 75b083e9 检出的 Hermes 源码目录
python -m pip install ./oai-core ./integrations/hermes-session-adapter PyYAML==6.0.3 python-dotenv==1.2.4 psutil==7.2.2 httpx==0.28.1
python -m pytest oai-core/tests/test_hermes.py oai-core/tests/test_hermes_native.py -q
```

未设置 `HERMES_SDK_ROOT` 时该项明确跳过，不能把跳过当作宿主兼容性已通过。
此前版本的 2026-10-25 结果见[历史验证记录](../docs/reviews/2026-10-25-unified-core-validation.zh-CN.md)，
不代表本次迁移提交的验证结果。本次 CI 把干净 Core wheel 安装、协议与安全回归、
固定 Hermes SDK 集成、DSH 桥和 Android 检查分别运行。

测试中的模型/宿主为原生协议替身；实际部署还需对应 Hermes/DSH/Codex 宿主与 Android
设备进行联调。真机本地确认、真实模型输出和服务端网络可达性不能由单元测试替代。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [设备能力授权修复计划](../docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md) | e5 `dev` 基线的历史拒绝链路、兼容迁移与安全验收 |
| [Android 唯一授权权威（ADR-0059）](../docs/adr/0059-android-is-the-sole-device-capability-authority.md) | 已接受的授权决策；分支实现和当前验证见[实施记录](../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md) |
| [第三方 Agent 适配器开发指南](../docs/guides/agent-adapter-development-guide.zh-CN.md) | 统一会话协议、能力声明、通用 Remote 适配器与插件模板 |
| [架构事实基线](../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md) | 每条行为对应的代码位置，文档更新的事实来源 |
| [独立部署与安全切换](../docs/deployment/2026-10-26-core-independent.zh-CN.md) | 干净安装、私密配置、Hermes 备份切换与回滚 |
| [统一 Core 迁移方案](../docs/plans/2026-10-25-unified-core-migration.zh-CN.md) | 协议增量、分阶段交付、数据兼容与回滚 |
| [原生会话适配器部署](../docs/plans/2026-10-25-native-session-adapters.zh-CN.md) | Hermes/DSH 宿主接入、环境变量、验证与回滚 |
| [Codex 设备隔离边界](docs/codex-device-isolation.zh-CN.md) | 共享会话与设备授权的边界与拒绝条件 |
| [DSH Session Adapter](../integrations/dsh-session-adapter/README.md) | DSH 宿主侧 Cordis bundle 安装 |
| [Hermes Session Adapter](../integrations/hermes-session-adapter/README.md) | Hermes 宿主侧仅会话插件安装 |
| [Gateway Protocol v2](../docs/contracts/gateway-protocol-v2.md) | 设备协议与本次扩展的规范正文 |
