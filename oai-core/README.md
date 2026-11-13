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

### 安装和首次使用

在可信检出的仓库根目录运行 `./scripts/install.sh`。全新安装按提示创建首个管理员，离线保管只显示一次的管理员恢复码；已有部署在更新或配置前须验证现有 Core 管理员的账号与 Core 登录密码。旧部署迁移还需先指定现有账号、验证原密码并停止旧写入者。安装入口和远端一键路径仍在完善，本说明不表示整条安装流程已验收。见[安装与恢复](../scripts/README.md)。

管理员在服务端本机创建普通账号，再配置并启动服务：

```sh
oai account create alice
oai setup
# 在安装目录启动，或使用安装时配置的自启动服务
./start-core.sh
```

按提示隐藏输入管理员密码和新账号密码。新账号默认为普通用户；删除或改密会影响该账号会话，操作前务必确认目标和影响。删除或降权最后一个可用管理员会被拒绝；管理员可修改自己的密码，不必先另建管理员。`oai account list`、`oai account delete alice`、`oai account passwd alice`、`oai config` 和 `oai setup` 都在服务端本机验证管理员；有多个本机实例时可通过对应命令的 `--config` 选择配置。管理员身份不赋予读取其他账号设备的权限。

服务端 2FA 默认关闭。在 `oai setup` 菜单输入 `2fa`，可绑定验证器、登记独立的管理员恢复码并按提示开启；开启前须准备绑定及恢复方式。开启后 CLI、Android 和管理入口按提示完成二次验证，未绑定的普通用户可在登录时绑定；有效会话正常刷新不重复要求验证码。关闭 2FA 不清除绑定。账号 MFA 备用码和**管理员本机恢复码**分开保管；后者仅在服务端本机使用：

```sh
oai recover ADMIN_USERNAME
```

恢复命令交互输入预先登记的独立管理员恢复码和新密码，确认目标账号后撤销旧会话；它不是普通账号远程找回入口，也不会自动关闭 2FA。密码、验证码和恢复码不放在命令行、普通环境变量或日志中。详见[独立部署](../docs/deployment/2026-10-26-core-independent.zh-CN.md)。

配置路径相对于配置文件；`contractRoot` 可省略，协议契约随包发布。运行数据和主密钥应分开保管，不提交仓库。手机远程连接时配置 `listen.host`、`tls.certFile` 和 `tls.keyFile`；反向代理终止 TLS 时还须配置与手机看到的证书一致的 `tlsSpkiSha256`。仅在受控开发环境显式使用 `allowInsecureHttp`。

在 Android 登录 Core 并配对，安装并启用设备插件。然后在自己的终端登录，首次跨机器使用时提供可信地址，以后可复用。账号没有设备时也能登录，设备列表为空：

```sh
oai login --url https://core.example
oai devices
oai capabilities
```

CLI 会提示输入账号和密码，登录不需要设备 ID；旧的 `login --device` 仅供兼容旧的设备限定凭据。默认客户端配置保存在 `~/.config/oai/client.json`，以 `0600` 保护；自定义路径时全局选项 `--client-config` 放在子命令前。在线 `oai logout` 撤销当前登录；离线时立即阻止本机继续使用，并提示远端撤销待联网重试，而非声称远端已即时撤销。不要在共享 Agent 宿主身份中保存个人设备调用凭据。

旧 `dev` 基线中的 `policy` 账号能力开关不参与目标设备授权；本地关闭、系统权限不足或敏感确认未完成仍会阻止执行。Android 能力发布版本变化不等同于登录失效，已入队请求仍保留原目标与版本校验。

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

登录只确定账号，不选择设备。Core 按 **显式 `--device` → 可信当前会话来源 → 有效默认设备 → 唯一在线设备** 的顺序选目标；前三者离线时不改投其他设备，按风险排队或拒绝。无设备、无在线设备或多台在线设备时会明确返回相应状态；多台在线时用 `--device DEVICE_ID` 选择本人设备。已撤销的默认设备不再参与选择，普通历史离线设备不会造成多选歧义。

```sh
oai devices --default DEVICE_ID  # 设置自己的默认设备
oai devices --clear-default      # 清除默认设备
oai capabilities --device DEVICE_ID
oai invoke CAPABILITY_ID --version VERSION --parameters '{}' --agent-name my-agent --purpose '用户请求的操作' --device DEVICE_ID
```

能力查询返回本次所选设备及版本，并不锁定后续调用的目标；需要保持一致时显式指定设备。请求入队后及同一幂等键重试始终保留原目标，不因离线或重新配对改投。能力 ID、版本和参数以 `capabilities` 返回的实际插件 schema 为准。
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

接收端使用已认证的 Core 登录凭据；端点归属于稳定登录关系，不因正常刷新转移给新登录。MCP 对应工具为 `oai_register_endpoint`、`oai_endpoints`、
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

已有配置仍保留；新候选只从当前安装用户的受支持位置或明确授权目录发现，不遍历其他用户目录、不执行未知脚本。发现不等于连接：还需核验实例身份、真实握手并确认注册，未接入时显示空状态。
DSH/Codex 的部署共享会话与 Hermes 的账号隔离继续遵循各宿主事实；共享会话不继承个人设备凭据。所有操作读取或修改真实宿主，不保存第二份聊天历史；在线状态来自真实握手/查询。

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
共享 Agent 不自动获得任何个人设备凭据；独立 CLI/MCP 使用个人 Core 账号认证，具体设备在请求时按规则选择。认证不替代 Android 的设备能力决定，也不构成账号级能力开关。
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

Hermes 是可选的账号隔离会话宿主。目标路径是由可信适配器按稳定 Core 账号准备专属空间；已有映射和原账号目录必须保留，关联既有身份需要确认，不能仅凭同名或复制密码关联。自动专属空间仍在集成，未验证通过；旧版显式一对一映射仅作为已有部署的兼容方式，不应当作新用户必需步骤。

先验证独立 Core 并备份，再停止旧写入者、按需安装匹配的 [Hermes Session Adapter](../integrations/hermes-session-adapter/README.md)。新旧进程不能同时写同一账号库。Core 设备库和 Hermes 原账号库保持分离，原主密钥不能自动替换。迁移与回滚见[独立部署](../docs/deployment/2026-10-26-core-independent.zh-CN.md)。
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

固定 Hermes 原生 SDK 的契约回归需要额外设置；将对应版本检出到相对目录 `./hermes-sdk`：

```sh
export HERMES_SDK_ROOT=./hermes-sdk
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
