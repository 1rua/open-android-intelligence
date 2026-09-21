---
status: accepted
date: 2026-08-24
contract: open-android-intelligence-gateway-protocol
version: 2.0.0
---

# open-android-intelligence Gateway Protocol v2 契约

## 1. 适用范围

本契约定义 Android 宿主与一个账号所属逻辑 Gateway 之间的应用层协议。它不定义 Hermes/OpenClaw 内部 API、插件 WASM ABI 或 Companion IPC。

V2 是新协议，不兼容 Bridge Protocol v1。端点的线上语义与 scheme 无关，但传输必须满足：HTTPS 是默认且唯一身份可核验的链路，客户端在协商返回 `tlsSpkiSha256` 时必须固定该身份；只有当用户显式输入 `http://` 地址时才允许明文连接，此时没有可核验的 Gateway 身份，客户端必须持续显示未加密警告，且声明了 TLS 指纹的账号不得降级到明文（见 ADR 0047）。事件流原生支持 RFC 6455 WebSocket 与 SSE (Server-Sent Events) 双通道传输，客户端优先协商 WebSocket 并支持自动降级至 SSE。

## 2. 基础约定

默认基路径：

```text
/open-android-intelligence/v2
```

所有 JSON：

- UTF-8 编码；
- `Content-Type: application/json`；
- 对象拒绝重复键；
- 协议 Schema 默认拒绝未知字段；
- 时间使用 RFC 3339 UTC，且必须精确包含三位毫秒；
- 摘要使用小写十六进制 SHA-256；
- HTTP method 只允许区分大小写的 `GET`、`POST`、`PUT`、`DELETE`，不得通过 `toUpperCase()` 或其他规范化接受别名；
- wire ID 服从 `wire-id = 1*128(ALPHA / DIGIT / "." / "_" / "~" / "-")`，等价正则为 `^[A-Za-z0-9._~-]{1,128}$`；
- wire ID 是封闭的协议记录标识集合：`requestId`、`correlationId`、`negotiationId`、`installationId`、`deploymentId`、`accountId`、`deviceId`、`pairingId`、`sessionId`、邀请 ID、`conversationId`、`clientConversationId`、`messageId`、`clientMessageId`、`attachmentId`、`clientAttachmentId`、SSE event ID/cursor、设备请求 ID 和 `claimId`；这些值不透明，接收方不得解析业务含义；
- digest/key identity 和编码密钥材料不属于 wire ID：`authorKeyId`、`schemaSha256`、`tlsSpkiSha256`、内容 `sha256`、公钥、签名及 refresh/access credential 保持各自 Schema 的编码，不得套用 wire ID 正则；`pluginId`、`capabilityId`、版本、operation 和 error code 也使用各自的封闭语法。

所有成功响应包含：

```json
{
  "requestId": "req_01...",
  "correlationId": "cor_01...",
  "protocol": "2.0",
  "data": {}
}
```

所有失败响应包含：

```json
{
  "requestId": "req_01...",
  "correlationId": "cor_01...",
  "protocol": "2.0",
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "Human-readable localizable fallback",
    "retryable": false,
    "retryAfterSeconds": null,
    "details": {}
  }
}
```

`message` 不能作为客户端逻辑分支；客户端只使用 `code`、`retryable` 和经过 Schema 验证的 `details`。

## 3. TLS

正式连接只能使用：

- 系统信任的公网证书；或
- 用户首次确认后固定的私有 CA、证书或 SPKI SHA-256 指纹。

固定身份变化需要旧身份签署的轮换证明或用户重新确认。连接插件和 Tailscale 不豁免 TLS 验证。

真正忽略证书错误的开发连接必须满足全部条件：目标是 loopback、RFC1918/ULA 或用户明确标记的测试地址；界面持续显示警告；禁用密码、刷新、附件、后台同步、设备插件和敏感正文。该模式不能创建可在正式连接复用的账号或配对状态。

## 4. 协议协商

`POST /negotiate` 在认证前执行，请求不含秘密：

```json
{
  "negotiationId": "neg_01...",
  "protocol": { "major": 2, "minor": 0 },
  "client": {
    "installationId": "install_01...",
    "appVersion": "2.0.0",
    "platform": "android",
    "platformApi": 35
  },
  "features": {
    "auth": ["password", "account-invitation", "refresh", "device-key"],
    "messages": ["chat-v1"],
    "attachments": ["staged-sha256-v1"],
    "events": ["sse-cursor-v1"],
    "deviceRequests": ["risk-queue-v1"],
    "conversationUi": ["agent-command-catalog-v1", "message-batches-v1"]
  },
  "schemaHashes": {
    "core": "sha256:..."
  }
}
```

响应固定当前连接可用交集和有限值：

```json
{
  "protocol": { "major": 2, "minor": 0 },
  "features": {
    "auth": ["password", "refresh"],
    "messages": "chat-v1",
    "attachments": "staged-sha256-v1",
    "events": "sse-cursor-v1",
    "deviceRequests": "risk-queue-v1",
    "conversationUi": ["agent-command-catalog-v1"]
  },
  "limits": {
    "maxSingleAttachmentBytes": 26214400,
    "maxMessageAttachmentBytes": 52428800,
    "allowedMediaTypes": ["image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain", "audio/mp4"],
    "attachmentTtlSeconds": 3600,
    "eventRetentionSeconds": 86400,
    "maxClockSkewSeconds": 120
  },
  "gatewayIdentity": {
    "deploymentId": "deploy_01...",
    "tlsSpkiSha256": "sha256:..."
  }
}
```

协议主版本不同、核心 Schema 不兼容、未知安全字段或未知高风险能力时返回 `PROTOCOL_INCOMPATIBLE`。次版本差异只启用双方声明的交集。协商结果由 `negotiationId` 绑定后续认证会话，客户端不能在单次请求中自行扩大功能。

`messages`、`attachments`、`events` 和 `deviceRequests` 只承载基础会话能力，取值分别为 `chat-v1`、`staged-sha256-v1`、`sse-cursor-v1`、`risk-queue-v1`；会话界面的增强能力放在 `conversationUi` 数组里，客户端只能拿到自己声明且 Gateway 确实实现了的能力。取值闭集为 `agent-command-catalog-v1`、`agent-command-new-v1`、`agent-approval-cards-v1`、`message-batches-v1`、`newline-v1`、`generation-cancel-v1`、`conversation-mirror-v1`、`attachment-status-v1`。Gateway 必须按客户端送来的原文校验请求：不得补齐缺失字段、不得删改超纲取值、不得把请求改造成自己能接受的形状；不能支持就返回错误或在该字段上给出交集结果。

`agent-command-new-v1` 表示该 Gateway 服务 `/new` 命令入口：能接收以普通文本形式送达的 `/new`，由宿主命令层原子创建新会话、保存绑定，并通过 `conversation.command.result` 返回权威 `conversationId`（第 7 节）。未实现该入口的 Gateway **不得**声明该能力，也不得以任何本地构造的会话标识冒充已知会话；客户端在能力缺失时必须明示不可用，而不是退化为自建会话。

声明该能力同时意味着承担第 7.1 节的全部义务：命令入口独占 `/new`（不得再把同一文本作为普通用户回合交给 Agent 运行时的命令解析器）、在创建新会话时为新会话创建宿主自己的 agent 会话并持久化绑定、以及在打开既有会话时复用该会话已绑定的 agent 会话。绑定是账号级事实：同一 `conversationId` 在任何时刻只能绑定一个 agent 会话，且不得因客户端切换、重连或重放而改变。

`agent-approval-cards-v1` 表示该 Gateway 以结构化卡片下发命令执行审批，并提供独立的决策端点（第 7.2 节）。未实现该能力的 Gateway 不得声明它；此时审批只能表现为宿主自己的文本提示，客户端必须明示卡片不可用，不得本地伪造一张无法提交的卡片。

`schemaHashes.core` 是具名 Schema 文档集合的摘要，算法固定为：

```text
core   = "sha256:" + hex(SHA-256(UTF-8(domain + "\n" + 每行 "<文件名>\tsha256:<该文件字节的 SHA-256 十六进制>" + "\n")))
domain = "open-android-intelligence/v2/core-schema-hash"
文件集合按文件名字典序固定为：
  attachment.schema.json, conversation.schema.json, device-request.schema.json,
  envelope.schema.json, event.schema.json, negotiate.schema.json, session.schema.json
```

客户端与 Gateway 各自从本地契约资产计算该值；不相等即为 `PROTOCOL_INCOMPATIBLE`。任何一方都不得使用占位值，也不得省略该字段。

### 4.1 动态 Schema catalog 与可信分派

`schemaFor` 和 `validateGatewayValue` 保留 outer-only 兼容语义：它们只验证固定协议外壳，不把动态 `payload`、`parameters`、成功 `data` 或失败 `details` 误报为已经完成子 Schema 验证。完整验证必须使用一个原子的 dispatched validator：构造时接收并冻结 catalog；运行时依次完成外壳验证、可信 key 解析、catalog lookup 和子值验证，调用方不能观察或绕过中间的半验证状态。

每个 catalog entry 的 Schema identity 是 `sha256:` 加 RFC 8785 JCS 所得 UTF-8 bytes 的小写 SHA-256。分派 key 只有以下四类：

```ts
type GatewaySubschemaKey =
  | { kind: "event"; eventType: string; schemaSha256: string }
  | { kind: "device.request"; pluginId: string; authorKeyId: string;
      capabilityId: string; capabilityVersion: string; schemaSha256: string }
  | { kind: "response.success"; operation: string; status: number; schemaSha256: string }
  | { kind: "response.failure"; errorCode: string; schemaSha256: string };

type GatewayLogicalSubschemaKey =
  | { kind: "event"; eventType: string }
  | { kind: "device.request"; pluginId: string; authorKeyId: string;
      capabilityId: string; capabilityVersion: string }
  | { kind: "response.success"; operation: string; status: number }
  | { kind: "response.failure"; errorCode: string };
```

运行时 dispatch 不含 `schemaSha256`，封闭类型为：

```ts
type TrustedGatewayDispatch =
  | { kind: "event"; eventType: string }
  | { kind: "device.request" }
  | { kind: "response.success"; operation: string; status: number }
  | { kind: "response.failure" };

type VerifiedSchemaBindingSet = Readonly<{
  core: readonly (
    | { kind: "event"; eventType: string; schemaSha256: string }
    | { kind: "response.success"; operation: string;
        status: number; schemaSha256: string }
    | { kind: "response.failure"; errorCode: string; schemaSha256: string }
  )[];
  device: readonly {
    kind: "device.request";
    pluginId: string;
    authorKeyId: string;
    capabilityId: string;
    capabilityVersion: string;
    schemaSha256: string;
  }[];
}>;
```

构造 dispatched validator 时必须同时传入不可变的 `VerifiedSchemaBindingSet`。其中 event/response/error 的逻辑 key 分别为 `(eventType)`、`(operation, status)`、`(errorCode)`，只能从本地验证的 core binding 选择唯一 catalog digest；device 的逻辑 key 为 `(pluginId, authorKeyId, capabilityId, capabilityVersion)`，只能从当前已认证 session/pairing binding 选择唯一 digest。catalog 或 binding set 中同一逻辑 key 重复、同一逻辑 key 出现多个 digest、binding 指向不存在的 catalog entry，均使构造失败；即使 digest 相同，也不得用重复 binding 表示第二个候选。

可信分派来源固定如下：事件 `eventType` 来自已验证 SSE `event:` 行；device 的 provider/capability 逻辑 key 从已经通过外壳验证的 value 提取；success 的 `operation` 和 `status` 来自本地请求上下文；failure 的 `errorCode` 从已验证 error envelope 提取。随后 validator 只通过 `VerifiedSchemaBindingSet` 解析 digest，再查 catalog。请求、payload、body、message 和通用调用方不得传入或覆盖 digest、Schema、binding、resolver 或 `ValidateFunction`，也不得用自报字段选择另一候选。

catalog 格式 `1.0` 的 Schema 子集必须机械检查：

1. 根 Schema 必须直接声明 `type: "object"` 和 `additionalProperties: false`，根 `$ref` 拒绝。
2. 任一被遍历节点只要声明 `type: "object"` 或 `properties`，就必须在同一节点直接声明 `type: "object"` 和 `additionalProperties: false`。
3. `$ref` 只允许解析到同一根文档内的 `#/$defs/...` JSON Pointer；外部 ref、无法解析的本地 ref 和越出 `$defs` 的 ref 全部拒绝。
4. 任意节点出现 `unevaluatedProperties`、`patternProperties`、`$dynamicRef` 或 `$dynamicAnchor` 都拒绝。
5. Schema 子节点只允许出现在 `$defs` 和 `properties` 的 value、单 Schema `items`，以及 `allOf`、`anyOf`、`oneOf` composition arrays；构造器从根开始深度遍历这些位置，以对象 identity visited set 防止 ref 循环。`not`、`if`/`then`/`else`、`dependentSchemas`、`contains`、`propertyNames`、`prefixItems` 等其他 Schema-bearing keyword 不属于格式 `1.0`，出现即拒绝。

catalog 与 binding set 在构造时 defensive-clone 并冻结。声明 digest 与 Schema identity 不符、上述子集检查失败或 strict 编译失败都使构造失败。运行时先验证外壳；外壳失败不得提取 dispatch。未知/缺失 binding 或子值失败返回冻结结果，不抛出 vendor diagnostics。

TypeScript validation diagnostics 的唯一规范化算法为：每个 Ajv error 生成 `instancePath + "\t" + schemaPath + "\t" + keyword + "\t" + JCS(params)`；非 Ajv dispatch error 使用空 `instancePath`、空 `schemaPath`、`keyword = "dispatch"` 和 JCS 参数对象。完全相同的字符串去重，再按 UTF-8 bytes 升序排列，最后 `Object.freeze` errors 数组并冻结外层 `{ ok: false, errors }`。跨语言一致性只哈希第 16 节定义的 accepted/rejected 规范结果，不哈希 Ajv/Python/Kotlin vendor diagnostics。

## 5. 账号与设备

### 5.1 标识

- `accountId`：Gateway 生成的稳定隔离主键；
- `username`：可变登录名，不作为数据隔离键；
- `installationId`：Android 首次安装生成并由 Keystore 相关状态保护的本地标识；
- `deviceId`：逻辑 Gateway 为该安装实例创建的账号内设备标识；
- `pairingId`：账号与设备之间的信任关系；
- `sessionId`：短期认证会话。

上述 `installationId`、`accountId`、`deviceId`、`pairingId` 和 `sessionId` 属于第 2 节封闭 wire ID 集合；它们仍是不可解析的不透明值，前缀示例不构成业务编码规则。`username` 不属于 wire ID。

不同账号中的相同 `installationId` 不共享 device ID、配对、授权或审计。Gateway 必须在打开账号数据库前完成账号解析，不能先连接共享业务数据库再依赖行过滤。

### 5.2 密码登录

`POST /sessions/password` 只允许在正式验证的 TLS 上调用：

```json
{
  "negotiationId": "neg_01...",
  "username": "alice",
  "password": "user-entered-secret",
  "installation": {
    "installationId": "install_01...",
    "displayName": "Alice's phone",
    "devicePublicKey": "base64url-ed25519-public-key"
  }
}
```

成功后 Gateway 创建或确认该账号下的独立设备记录，返回短期 access token、一次性展示的 refresh credential 和配对摘要。账号密码不由 Android 保存。新设备的核心对话与附件可用，但设备插件授权集合为空。

### 5.3 账号邀请配对

二维码或短码由目标账号生成，内容包含 Gateway 地址、账号提示、邀请 ID、一次性秘密、期限和 Gateway 身份指纹。用户必须填写或确认账号名。

`POST /pairings/exchange` 提交邀请与新的设备公钥。邀请只能使用一次、不能跨账号、默认 5 分钟失效。成功响应与密码登录相同，但审计认证方式为 `account-invitation`。

### 5.4 刷新与设备密钥会话

`POST /sessions/refresh` 使用绑定 `accountId + installationId + deviceId` 的刷新凭据轮换短期 access token，并同时轮换 refresh credential。旧 refresh credential 在成功使用后立即失效；检测到重复使用时撤销该设备全部 refresh credential 并产生安全审计。

`POST /sessions/device` 使用已配对设备私钥签名挑战建立短期会话。请求仍必须携带账号名或账号 ID；设备密钥不能在账号之间探测或复用。

密码重置撤销账号的全部 refresh credential。既有配对密钥是否保留由操作员选择；疑似泄露流程撤销全部会话和配对。

### 5.5 会话终止

`DELETE /sessions/current` 结束当前 access session；请求 `revokeRefresh=true` 时同时实现“退出登录”。解除配对和删除账号使用独立管理端点，不能由普通会话删除隐式替代。

## 6. 已认证请求

### 6.1 Raw header 与验证后身份

短期 access token 使用以下 header：

```http
Authorization: Bearer <opaque-access-token>
X-Open-Android-Intelligence-Protocol: 2.0
X-Open-Android-Intelligence-Account: <account-id>
X-Open-Android-Intelligence-Device: <device-id>
X-Open-Android-Intelligence-Session: <session-id>
X-Open-Android-Intelligence-Request-Id: <request-id>
X-Open-Android-Intelligence-Timestamp: <RFC3339 UTC>
X-Open-Android-Intelligence-Nonce: <base64url 16 random bytes>
X-Open-Android-Intelligence-Signature: <base64url Ed25519 signature>
```

HTTP 入口必须从 framework 合并、逗号折叠或 first/last 选择之前的 raw header 列表证明下列九个认证 header 始终各恰好出现一次：

```text
Authorization
X-Open-Android-Intelligence-Protocol
X-Open-Android-Intelligence-Account
X-Open-Android-Intelligence-Device
X-Open-Android-Intelligence-Session
X-Open-Android-Intelligence-Request-Id
X-Open-Android-Intelligence-Timestamp
X-Open-Android-Intelligence-Nonce
X-Open-Android-Intelligence-Signature
```

以下五个条件 header 也必须从同一 raw header 列表执行 singleton 规则：

- `Idempotency-Key`：每个已认证 `POST`、`PUT`、`PATCH`、`DELETE` 请求必须恰好一次，值按第 6.5 节绑定 request ID；已认证 `GET` 必须不出现。
- `Last-Event-ID`：只允许在 `GET /events` 且 canonical query 已含非空 `cursor` 时出现零次或一次；若出现必须逐字节等于 query cursor，其他路由必须不出现。
- `Content-Type`：声明 JSON entity-body 的路由必须恰好一次且值精确为 `application/json`；附件 content 的原始 bytes 路由允许零次或一次，若出现必须精确等于创建附件时已验证的 `mediaType`；无 body 路由不得出现。
- `Content-Length`：`PUT /attachments/{attachmentId}/content` 必须恰好一次；其他带 entity-body 的路由可以不出现或恰好一次，由 HTTP transfer framing 决定；无 body 路由不得出现。
- `Digest`：只在 `PUT /attachments/{attachmentId}/content` 必须恰好一次，其他路由必须不出现。

header 名大小写不敏感；值按收到的原始字段值验证，不做 trim、unfold，也不做 first/last 选择。上述十四个 header 的任何重复都拒绝，包括两个完全相同的字段；库或代理已经产生的逗号合并值、只有 trim 后才合法的值，以及含 CR、LF、NUL 或其他控制字符的值也一律拒绝。条件 header 在不允许的路由出现同样拒绝。若运行框架不能提供足以在组合前证明这些条件的 raw header 列表，该入口必须失败关闭。

token 验证得到的 `accountId`、`deviceId`、`sessionId` 必须分别与三个身份 header 的值逐字节精确相等。后续路由和业务只能接收单一不可变的 `VerifiedRequestContext`，不能再次从 header、query、path 或 JSON 读取身份。请求 JSON 中出现 `accountId`、`deviceId`、`principalId` 等试图覆盖认证身份的字段时返回 `IDENTITY_OVERRIDE_REJECTED`。

### 6.2 Method、时间、nonce、签名与 body bytes

已认证请求字段语法为：

```text
method    = "GET" / "POST" / "PUT" / "PATCH" / "DELETE"
timestamp = RFC3339 UTC with exactly milliseconds
nonce     = canonical unpadded base64url of exactly 16 bytes
signature = canonical unpadded base64url of exactly 64 Ed25519 bytes
```

method 在线上区分大小写；小写或混合大小写一律拒绝，不能先转为大写再验签。nonce 和 signature 解码后必须分别恰好为 16 和 64 bytes，并且以 canonical unpadded base64url 重新编码后必须与原字符串完全相同。

body digest 覆盖 HTTP transfer framing 解码后的 exact entity-body bytes，并且必须在 JSON decode、字符转换、自动解压或重序列化之前计算。JSON 请求禁止 `Content-Encoding`。零字节 body 的 SHA-256 固定为：

```text
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

### 6.3 Canonical request target

签名输入只允许 `/open-android-intelligence/v2` 基路径内的 HTTP origin-form target。语言无关的逐字节算法必须按以下顺序执行：

1. 拒绝 absolute-form、authority-form、`*`、fragment、空白和控制字符；path 必须是 `/open-android-intelligence/v2` 或其下级路径。
2. 只在第一个字面量 `?` 分离 path/query；query 内出现字面量 `?` 属于非规范输入。
3. path 使用字面量 `/` 分段；每个完整 `%HH` 解码为一个 byte，畸形或不完整的 percent escape 拒绝。
4. byte 属于 `[A-Za-z0-9._~-]` 时原样输出，其他 byte 输出大写 `%HH`。
5. 拒绝解码后为 `.` 或 `..` 的 segment；拒绝 segment 解码后包含 `/` 或 `\\`；拒绝 `//` 和非根尾随 `/`。
6. query 使用字面量 `&` 分 pair，空 pair 拒绝；在第一个字面量 `=` 分 name/value，空 name 拒绝，未写 `=` 的输入也规范为带空 value 的 `name=`。
7. query name/value 使用第 3、4 步相同的 byte 解码和编码；`+` 规范为 `%2B`，绝不解释为空格。
8. 先按 canonical encoded name 的 ASCII bytes 排序，再按 canonical encoded value 的 ASCII bytes 排序；完全重复的 pair 保留原数量。
9. 没有 query 时不输出 `?`；字面量空 query 标记 `?` 必须拒绝。
10. 客户端实际发送的 raw target 与 Gateway 验签读取的 raw target 都必须逐字节等于 canonical target；反向代理不能依赖未认证的 `X-Original-URI` 恢复已被重写的路径。

### 6.4 请求签名预像

设备签名预像严格由以下 10 个 ASCII 字段和字段间 9 个 `0x0A` 组成：

```text
ASCII("OPEN-ANDROID-INTELLIGENCE-REQUEST-V2") LF
ASCII(method) LF
ASCII(canonicalTarget) LF
ASCII(accountId) LF
ASCII(deviceId) LF
ASCII(sessionId) LF
ASCII(requestId) LF
ASCII(timestamp) LF
ASCII(nonce) LF
ASCII(lowercaseHex(SHA-256(exactBodyBytes)))
```

最后一个字段后不得出现 LF、CR、NUL、空格或 BOM。Ed25519 直接签完整预像，不使用 Ed25519ph。

独立 oracle 向量输入：

```json
{
  "method": "GET",
  "target": "/open-android-intelligence/v2/events?cursor=evt_1&z=last",
  "accountId": "acct_1",
  "deviceId": "dev_1",
  "sessionId": "sess_1",
  "requestId": "req_1",
  "timestamp": "2026-08-27T00:00:00.000Z",
  "nonce": "AAAAAAAAAAAAAAAAAAAAAA",
  "bodyHex": ""
}
```

其 canonical target 为：

```text
/open-android-intelligence/v2/events?cursor=evt_1&z=last
```

其 expected preimage hex 为：

```text
4147454e542d4c4946452d524551554553542d56320a4745540a2f6167656e742d6c6966652f76322f6576656e74733f637572736f723d6576745f31267a3d6c6173740a616363745f310a6465765f310a736573735f310a7265715f310a323032362d30382d32375430303a30303a30302e3030305a0a414141414141414141414141414141414141414141410a65336230633434323938666331633134396166626634633839393666623932343237616534316534363439623933346361343935393931623738353262383535
```

Gateway 随后校验账号、设备、配对 generation、时间窗、nonce、防重放和签名；任何一步失败都不得向业务暴露未经验证的上下文。

### 6.5 幂等绑定

读请求重复可以安全重试。所有创建、副作用和附件提交请求必须携带 `Idempotency-Key`，且值必须逐字节精确等于已签名的 `X-Open-Android-Intelligence-Request-Id`。同一账号、设备和 request ID 在保留期内返回同一终态，不重复执行。客户端重试可以更换 timestamp 和 nonce，但必须保持 request ID 不变。

## 7. 对话

端点：

```text
GET  /commands?languageCode=<bcp47>
POST /conversations/{conversationId}/message-batches
POST /conversations/{conversationId}/generations/{generationId}/cancel
GET  /attachments/{attachmentId}
DELETE /attachments/{attachmentId}
GET  /conversations/{conversationId}/messages?clientMessageId=<id>
POST /approvals/{approvalId}/decisions
GET  /conversations
POST /conversations
GET  /conversations/{conversationId}
PATCH /conversations/{conversationId}
POST /conversations/{conversationId}/messages
GET  /conversations/{conversationId}/attachments/{attachmentId}/metadata
POST /conversations/{conversationId}/attachments/{attachmentId}/cache-grant
GET  /conversations/{conversationId}/attachments/{attachmentId}/content
```

创建对话请求包含客户端生成的 `clientConversationId` 和可选显示标题。发送消息请求：

```json
{
  "clientMessageId": "msg_client_01...",
  "text": "Summarize the attached document",
  "attachments": [
    { "attachmentId": "att_01..." }
  ]
}
```

Gateway 返回 `accepted` 及服务端 message ID；Agent 回复通过 SSE 发送 `conversation.message.delta` 和 `conversation.message.completed`。每个 conversation 只属于当前逻辑 Gateway，服务端拒绝跨账号、跨 Gateway 或跨 conversation 附件引用。

重命名请求体是封闭的 `{"title": "<非空显示标题>"}`，成功时返回：

```json
{
  "protocol": "2.0",
  "data": {
    "conversation": {
      "conversationId": "conv_01...",
      "clientConversationId": "cconv_01...",
      "title": "量子计算与经典物理的核心区别"
    }
  }
}
```

Gateway 先原子更新标题并写入审计，再追加 `conversation.title.updated` 事件（`payload` 同时携带 `title` 与 `newTitle`），随后向已连接的设备补齐该事件。会话不存在时返回第 14 节的错误信封，绝不静默创建。手机端生成的自动标题和用户手动重命名走同一端点；一旦用户手动重命名，客户端不再让后续 Agent 标题建议覆盖该标题。

### 7.1 `/new` 与 `conversation.command.result`

`/new` 命令以普通文本形式通过 `POST /conversations/{sourceConversationId}/messages` 送达，Gateway 不得把它当成字段或本地指令解释。声明了 `agent-command-new-v1` 的宿主在命令入口处理它：在**同一个原子操作**内落库来源消息、创建新会话、保存「命令请求 ↔ 新会话」绑定、写入审计，并追加事件：

```json
{
  "protocol": "2.0",
  "event": "conversation.command.result",
  "eventId": "evt_01...",
  "occurredAt": "2026-09-21T09:00:00.000Z",
  "payload": {
    "command": "new",
    "commandId": "new",
    "outcome": "created-conversation",
    "sourceConversationId": "conv_01...",
    "sourceMessageId": "msg_01...",
    "conversationId": "conv_02..."
  }
}
```

`payload` 至少携带来源消息、来源会话、命令 id、`outcome` 与新会话标识。`outcome` 是闭集：

```text
created-conversation | rejected | unsupported | outcome-unknown
```

规则：

- `/new` 保留在来源会话（命令本身不消失、不被改写为新会话的首条消息）；新会话从空上下文开始；旧会话及其未完成的 generation 继续存在、可恢复。
- Gateway 认证 `sessionId` 不因 `/new` 变化。`agentSessionId` 是宿主内部概念，**不向客户端下发**，客户端也不得本地生成或猜测它。
- 幂等按第 6.5 节绑定：同一 `Idempotency-Key` / `requestId` 重放必须回到同一个 `conversationId`，不允许重复创建第二个会话。
- 客户端只有在 `outcome` 为 `created-conversation` 且 `conversationId` 非空时才切换到该会话，并在切换后重新读取该会话的时间线与元数据；拒绝、不支持、未知结果或超时一律停留在来源会话并如实报错，**不得本地构造会话**。
- 未实现该命令入口的宿主不得声明 `agent-command-new-v1`；`/new` 在此类宿主上仅作为普通文本被接受与透传，不得产生创建会话的响应或事件。
- **命令入口独占**：命令入口处理过的 `/new` 不得再作为普通用户回合交给 Agent 运行时。宿主必须避免让 Agent 自己的新开会话语义作用在**来源会话**上——那会清空用户仍在阅读的上下文。
- **新会话必须拥有自己的 agent 会话**：宿主在创建新会话时，为其创建一个不继承任何旧上下文的 agent 会话，并把「`conversationId` ↔ agent 会话」绑定持久化；`agentSessionId` 仍为宿主内部概念，不下发客户端。宿主没有 Agent 运行时能力时，绑定行必须保留为空并如实标注来源，不得编造标识。
- **切换即路由**：客户端打开某个会话、读取其时间线或投递消息时，宿主必须确保该会话已有绑定并复用既有绑定；只有 `/new` 创建的新会话允许获得一个全新的 agent 会话。同一 `conversationId` 不得因重连、重放或切换而更换 agent 会话。
- `conversation.command.result` 的 `payload` 是封闭对象：`outcome` 为 `created-conversation` 时必须携带非空 `conversationId`，其他 `outcome` 不得携带 `conversationId`（共享向量 `sse-events.json` 锁定该形状）。

### 7.2 命令执行审批卡片 (`agent-approval-cards-v1`)

Agent 运行时要执行一条被判定为危险的宿主命令时，宿主通过该 Gateway 向手机下发一张结构化审批卡片，用户点击卡片上的按钮提交决策。审批**不是**会话消息：它既不是用户回合，也不得被改写成 `/approve`、`/deny` 之类的普通文本消息冒充用户输入。

声明 `agent-approval-cards-v1` 的 Gateway 必须在检测到待审批命令后追加事件：

```json
{
  "protocol": "2.0",
  "event": "conversation.approval.requested",
  "eventId": "evt_01...",
  "occurredAt": "2026-09-22T09:00:00.000Z",
  "payload": {
    "approvalId": "apr_01...",
    "conversationId": "conv_01...",
    "command": "python3 -c \"print(1)\"",
    "reason": "内联解释器执行",
    "severity": "elevated",
    "options": [
      { "choice": "once", "label": "允许一次", "style": "primary" },
      { "choice": "session", "label": "本次会话允许", "style": "secondary" },
      { "choice": "always", "label": "始终允许", "style": "secondary" },
      { "choice": "deny", "label": "拒绝", "style": "danger" }
    ],
    "timeoutSeconds": 300,
    "requestedAt": 1780000000000,
    "expiresAt": 1780000300000
  }
}
```

用户决策通过独立端点提交，**不经过消息通道**：

```text
POST /approvals/{approvalId}/decisions
```

```json
{ "decision": "once" }
```

规则：

- `approvalId` 是唯一的交互句柄。宿主的会话键（`sessionKey`）、请求标识等内部概念**一律不下发客户端**，也不得由客户端提交；Gateway 必须自己持久化 `approvalId → 宿主内部标识` 的映射，使宿主重启后迟到的决策仍可解析。客户端不得借决策接口操作任意宿主会话。
- `options` 由 Gateway 下发，客户端**不得**硬编码档位或自行补齐缺省档位。`choice` 闭集为 `once | session | always | deny`；宿主判定为「仅本次可决」（例如 owner 强制拒绝覆盖）时可以只下发其中一部分，客户端必须按下发的集合渲染，没有下发的档位不得出现在界面上。
- `style` 闭集为 `primary | secondary | danger | neutral`，只是呈现提示；`label` 缺省时客户端按 `choice` 映射到本地文案。
- `requestedAt`、`expiresAt`、`decidedAt` 是 epoch 毫秒整数。`timeoutSeconds` 缺省值 300 只是客户端兜底，真正生效的是 Gateway 下发的 `timeoutSeconds`。
- **超时由 Gateway 权威**：倒计时归零而无人决策时，Gateway 必须追加 `conversation.approval.resolved`（`decision = "timeout"`）。客户端可以基于 `expiresAt` 提前把卡片画成「已超时」并禁用按钮，但终态只能由该事件落定，客户端不得本地生成权威结论。无人可答（例如已连接设备都无法渲染卡片）时 Gateway 必须以 `withdrawn` 撤销，不得让卡片悬空等待。
- 决策成功后 Gateway 追加：

```json
{
  "protocol": "2.0",
  "event": "conversation.approval.resolved",
  "eventId": "evt_02...",
  "occurredAt": "2026-09-22T09:00:05.000Z",
  "payload": {
    "approvalId": "apr_01...",
    "conversationId": "conv_01...",
    "decision": "once",
    "decidedAt": 1780000005000
  }
}
```

  `decision` 闭集为 `once | session | always | deny | timeout | withdrawn`；后两项只能由 Gateway 产生，客户端只能提交前四项。
- 幂等按第 6.5 节绑定：同一 `Idempotency-Key` 重放必须回到同一个决策终态，不得重复解析宿主侧等待中的请求。对一个已落定的 `approvalId` 再次提交不同决策返回 `APPROVAL_ALREADY_RESOLVED`，提交相同决策返回原成功终态。
- 第 9 节的「提交后即时投递」义务同样适用于本节的事件：决策一旦落库，必须立即投递给该账号全部在线订阅者，使同一账号的其它设备同时看到卡片落定。
- 客户端在收到 `resolved` 之前不得把卡片画成「已允许」；提交请求失败时必须如实回到可重试的等待态并给出结构化提示，不得把失败标记为成功。
- 未声明该能力的 Gateway 不得产生本节事件，也不得接受决策端点；此时审批只表现为宿主的文本提示，客户端必须明示卡片不可用。

宿主拥有长期对话与 Agent 记忆。Gateway 只保存完成可靠交付所需映射、幂等结果和短期暂存，不复制长期对话正文。宿主自身的会话绑定属于「完成可靠交付所需映射」，必须持久化，不得只存在于进程内存。

## 8. 附件

上传分三步：

1. `POST /attachments` 创建暂存记录；
2. `PUT /attachments/{attachmentId}/content` 上传字节；
3. `POST /attachments/{attachmentId}/commit` 校验并提交。

创建请求：

```json
{
  "clientAttachmentId": "att_client_01...",
  "filename": "report.pdf",
  "mediaType": "application/pdf",
  "sizeBytes": 102400,
  "sha256": "64-lowercase-hex-chars"
}
```

Gateway 在读取内容前检查协商限制和本账号配额。content 请求必须携带 `Content-Length` 与 `Digest: sha-256=<base64>`，不允许未协商的压缩或媒体类型。commit 只有在字节数和摘要完全匹配时成功。

附件状态和事件是封闭集合：

```ts
type AttachmentState =
  | "created" | "uploading" | "verified" | "delivered"
  | "acknowledged" | "failed" | "expired" | "deleted";

type AttachmentEvent =
  | "begin_upload" | "verify" | "deliver" | "acknowledge"
  | "fail" | "expire" | "cleanup";
```

唯一合法转移为：

| 当前状态 | 事件 | 下一状态 |
|---|---|---|
| `created` | `begin_upload` | `uploading` |
| `created` | `fail` | `failed` |
| `created` | `expire` | `expired` |
| `uploading` | `verify` | `verified` |
| `uploading` | `fail` | `failed` |
| `uploading` | `expire` | `expired` |
| `verified` | `deliver` | `delivered` |
| `verified` | `fail` | `failed` |
| `verified` | `expire` | `expired` |
| `delivered` | `acknowledge` | `acknowledged` |
| `delivered` | `expire` | `expired` |
| `acknowledged` | `cleanup` | `deleted` |
| `failed` | `cleanup` | `deleted` |
| `expired` | `cleanup` | `deleted` |

全部其他 `AttachmentState × AttachmentEvent` 组合在纯 reducer 内部抛 `INVALID_STATE_TRANSITION`。精确 HTTP 重试由 reducer 前的幂等账本处理，不给普通事件增加自循环。解除配对和删除账号是资源级事务，不伪装成逐附件事件。

只有 `verified` 附件可被消息引用。宿主确认接收后 Gateway 删除暂存字节并保留无正文终态。TTL 到期必须立即删除 staged bytes，并通过 `expire` 把协议元数据记录为 `expired`；后续单独的 `cleanup` 才把该无正文元数据转为 `deleted`。解除配对和账号删除仍以资源级事务立即移除对应 bytes 和元数据，不伪造 reducer 的逐附件跳转。

## 9. 事件流传输 (WebSocket 与 SSE)

端点 `GET /events?cursor=<opaque>` 提供全链路事件订阅与断点续传能力，支持双通道传输：

1. **WebSocket 传输 (RFC 6455)**：
   - 客户端请求携带 `Upgrade: websocket` 与 `Connection: Upgrade`；
   - 服务端校验认证与握手签名后完成协议升级，事件以 JSON 文本帧下发（包含 `id`、`event` 与 `data` 载荷）；
   - 支持 PING/PONG 帧心跳保活；客户端以 WebSocket 为优先通道，异常时自动平滑降级至 SSE。

2. **SSE (Server-Sent Events) 传输**：
   - 使用 `Accept: text/event-stream`；
   - 认证 SSE 恢复以 canonical query 中的 `cursor` 为权威；`Last-Event-ID` 若存在，必须与 query cursor 逐字节精确相等，否则返回 `CURSOR_CONFLICT`。只有 header、没有 query cursor 不能选择恢复位置；
   - 心跳使用 SSE 注释行（`: ping`），不创建事件 ID。

事件格式：

```text
id: evt_01...
event: conversation.message.completed
data: {"correlationId":"cor_01...","occurredAt":"2026-08-24T12:00:00.000Z","payload":{}}

```

V2 事件类型：

- `conversation.message.delta`
- `conversation.message.completed`
- `conversation.command.result`
- `conversation.approval.requested`
- `conversation.approval.resolved`
- `conversation.title.updated`
- `device.requested`
- `device.request.cancel.requested`
- `pairing.grant.changed`
- `session.revoked`
- `attachment.acknowledged`
- `gateway.notice`

事件 ID 同时是恢复游标。Gateway 保留时间不得短于协商值；游标过旧返回 `CURSOR_EXPIRED` 以及可安全重建的资源类型，不静默跳到最新事件。客户端先重建资源快照，再使用响应给出的新游标恢复流。

**提交后即时投递**：任何已提交到账号事件流的事件，必须在提交成功后立即投递给该账号的全部在线订阅者（SSE 与 WebSocket 同等义务），不得只落库等待下一次订阅。事件由哪个代码路径追加（协议层、命令入口或 Agent 运行时）不改变该义务。`cursor` 重放只承担断线续传，不能作为常规投递手段：一个只在重连之后才出现的命令结果，等于用户从未收到它。投递按账号隔离，且投递失败不得回滚、也不得重复提交已经提交的写入。

该义务适用于提供 SSE 或 WebSocket 长连接的宿主。只以游标文档提供事件读取、不提供长连接的宿主不承担本节的长连接投递义务，但必须如实标注自身实现，不得让客户端以为已经建立了实时通道；此类宿主的事件对客户端始终表现为「按游标读取」。

心跳使用 SSE 注释行，不创建事件 ID。Android 以指数退避重连，不假定流永久在线。

## 10. 设备插件请求

Agent 宿主通过 Gateway Core 创建结构化设备请求；模型不能提供账号、设备、会话、授权版本或风险级别。Gateway 从已验证能力 Schema 和宿主认证上下文补齐这些字段。

`device.requested` 事件载荷：

```json
{
  "requestId": "device_req_01...",
  "capability": {
    "id": "org.openandroidintelligence.sms.query",
    "version": "1.0.0"
  },
  "provider": {
    "pluginId": "org.openandroidintelligence.sms",
    "authorKeyId": "sha256:..."
  },
  "parameters": {},
  "risk": "read",
  "grantRevision": 7,
  "createdAt": "2026-08-24T12:00:00.000Z",
  "expiresAt": "2026-08-25T12:00:00.000Z",
  "requiresForegroundConfirmation": false
}
```

Android 执行前重新验证插件身份、当前提供者、能力版本、授权 revision、本地权限、资源状态和期限。结果提交到 `POST /device-requests/{requestId}/result`，必须幂等；request body 只提交 claim 时取得的同一个 `claimId`、同一个 `grantRevision` 和 result，不提交 `accountId`、`deviceId` 或 `pairingGeneration` 来覆盖身份。Gateway 通过服务端 receipt 记录和 `VerifiedRequestContext` 核对全部绑定，不能只凭 request ID 接受结果。

Android 必须先调用幂等 claim 操作 `POST /device-requests/{requestId}/claim`，并在任何副作用前取得服务端生成的 claim。claim 成功响应的 `data` 是：

```ts
type ClaimReceipt = Readonly<{
  claimId: string;
  requestId: string;
  accountId: string;
  deviceId: string;
  pairingGeneration: number;
  grantRevision: number;
}>;
```

`claimId` 是第 2 节 wire ID；receipt 内的 `requestId` 是 route 指向的设备请求 ID，不是成功 envelope 顶层的已签名 HTTP request ID。Gateway 必须把 receipt 原子绑定到已验证 `accountId`、`deviceId`、当前 `pairingGeneration`、当前 `grantRevision` 和目标设备请求，并写入最小审计；同一 claim 幂等重试返回同一 receipt。Android 不能自选或改写 receipt 字段。result 必须从 receipt 原样带回 `claimId` 和 `grantRevision`；Gateway 用服务端 receipt 记录检查账号、设备、generation、revision、设备 request 和 claim，任一不匹配均拒绝，未 claim 的 `pending` 请求不能直接提交普通结果。claim 与 result 的持久化、SQLite/CAS 和崩溃恢复由 Gateway Core 实现，线上调用仍遵守第 6 节的签名与幂等绑定。

`device.request.cancel.requested` 只表示取消意图，不表示设备已经停止。只有尚未 claim 的 `pending` 请求收到 cancel 时可以立即成为 `cancelled`；claim 后收到 cancel 只进入 `cancel_requested`，随后必须由可信 result 决定 `cancelled`、实际发生的其他终态或 `outcome_unknown`。Gateway 和 Android 不得因看到取消 SSE 就伪造 `result_cancelled`。

设备请求状态和事件是封闭集合：

```ts
type DeviceRequestState =
  | "pending" | "claimed" | "cancel_requested"
  | "succeeded" | "failed" | "denied" | "cancelled"
  | "expired" | "outcome_unknown";

type DeviceRequestEvent =
  | "claim" | "cancel" | "expire"
  | "result_succeeded" | "result_failed" | "result_denied"
  | "result_cancelled" | "result_outcome_unknown"
  | "recover_outcome_unknown";
```

唯一合法转移为：

| 当前状态 | 事件 | 下一状态 |
|---|---|---|
| `pending` | `claim` | `claimed` |
| `pending` | `cancel` | `cancelled` |
| `pending` | `expire` | `expired` |
| `claimed` | `cancel` | `cancel_requested` |
| `claimed` | `expire` | `outcome_unknown` |
| `claimed` | `result_succeeded` | `succeeded` |
| `claimed` | `result_failed` | `failed` |
| `claimed` | `result_denied` | `denied` |
| `claimed` | `result_cancelled` | `cancelled` |
| `claimed` | `result_outcome_unknown` | `outcome_unknown` |
| `claimed` | `recover_outcome_unknown` | `outcome_unknown` |
| `cancel_requested` | `expire` | `outcome_unknown` |
| `cancel_requested` | `result_succeeded` | `succeeded` |
| `cancel_requested` | `result_failed` | `failed` |
| `cancel_requested` | `result_denied` | `denied` |
| `cancel_requested` | `result_cancelled` | `cancelled` |
| `cancel_requested` | `result_outcome_unknown` | `outcome_unknown` |
| `cancel_requested` | `recover_outcome_unknown` | `outcome_unknown` |

全部其他 `DeviceRequestState × DeviceRequestEvent` 组合在纯 reducer 内部抛 `INVALID_STATE_TRANSITION`。`outcome_unknown` 是不可被普通结果覆盖的终态；准确 HTTP 重试仍由 reducer 前的幂等账本处理，不增加自循环。

队列上限：

| 风险 | 默认最长等待 | 恢复后处理 |
|---|---:|---|
| read / sync | 24 小时 | 重新验证后执行 |
| write | 15 分钟 | 重新验证，必要时再次确认 |
| high-privilege ephemeral | 0 | 离线即失败，不排队 |

Gateway 可以声明更短期限，不能超过上表。Android 可以按本地策略进一步缩短或拒绝。

## 11. 配对授权

每个配对的授权以单调递增 `grantRevision` 表示，键为插件身份和能力版本范围。安装或启用插件不会创建授权；新配对授权为空。

授权变更必须由 Android 本地确认。Gateway 保存签名授权摘要并发送 `pairing.grant.changed`，但不能扩大 Android 本地权威记录。请求携带旧 revision 时返回 `GRANT_STALE`，Android 不按旧授权执行。

能力提供者切换产生新 revision 并要求重新授权，不把旧插件授权转移给新插件。

## 12. 离线、幂等与 fencing

每次重新配对、密钥轮换或恢复后生成更高 `pairingGeneration`。所有设备请求、结果、附件和授权都绑定 generation；旧 generation 请求返回 `PAIRING_GENERATION_STALE`。

Gateway 对副作用保存幂等终态直到请求最大 TTL 与审计窗口两者中的较长者。崩溃恢复后，相同 idempotency key 返回原终态或明确 `OUTCOME_UNKNOWN`，不能猜测重试高风险动作。

## 13. 数据保留与删除

Gateway 长期保存协议元数据，不长期保存设备正文。通知、短信、通话记录和附件正文只在排队、交付和确认期间加密暂存；宿主 ACK 或 TTL 到期后删除。

- 退出登录：撤销目标 refresh credential，不删除配对；
- 移除本地账号：是 Android 本地动作，Gateway 只看到退出；
- 解除配对：撤销设备密钥、refresh credential、授权、队列和未确认附件；
- 删除账号：删除该逻辑 Gateway 全部数据库、密钥、设备、队列、附件和审计，允许先导出可迁移备份。

最小审计默认保留 30 天，不含正文；用户可以清除。清除审计不能复活已删除正文或凭据。

## 14. 错误代码

V2 至少实现：

```text
PROTOCOL_INCOMPATIBLE
SCHEMA_INVALID
TLS_IDENTITY_REQUIRED
ACCOUNT_NOT_FOUND
AUTHENTICATION_REQUIRED
AUTHENTICATION_FAILED
REFRESH_REUSED
SESSION_EXPIRED
SESSION_REVOKED
IDENTITY_OVERRIDE_REJECTED
SIGNATURE_INVALID
NON_CANONICAL_TARGET
REQUEST_REPLAYED
CLOCK_SKEWED
PAIRING_REQUIRED
PAIRING_INVITATION_EXPIRED
PAIRING_GENERATION_STALE
GRANT_REQUIRED
GRANT_STALE
CAPABILITY_UNAVAILABLE
PROVIDER_CHANGED
IDEMPOTENCY_CONFLICT
OUTCOME_UNKNOWN
ATTACHMENT_LIMIT_EXCEEDED
ATTACHMENT_DIGEST_MISMATCH
ATTACHMENT_EXPIRED
CURSOR_CONFLICT
CURSOR_EXPIRED
APPROVAL_NOT_FOUND
APPROVAL_EXPIRED
APPROVAL_ALREADY_RESOLVED
APPROVAL_DECISION_INVALID
RATE_LIMITED
HOST_INCOMPATIBLE
ACCOUNT_DELETING
INTERNAL_ERROR
```

`INVALID_STATE_TRANSITION` 只属于纯 reducer 和黄金向量的内部错误，不是 Gateway wire error code。HTTP endpoint 对 malformed input 返回 `SCHEMA_INVALID`，对同一幂等键绑定不同输入返回 `IDEMPOTENCY_CONFLICT`，对已 claim 操作无法确定真实终态返回 `OUTCOME_UNKNOWN`；若未来需要公开其他状态机映射，必须单独修改本契约。

认证失败不泄露账号是否存在。安全、身份、摘要、授权和 generation 错误默认不可自动重试；网络中断和明确 `retryable: true` 的限流错误按退避策略处理。

## 15. Gateway 暴露与宿主兼容

Gateway 可以使用宿主已有路由、loopback 加反向代理或显式证书直接 TLS 监听。三种模式必须运行相同路由、认证、限制、审计和一致性测试。

适配器声明经过验证的宿主 API 最小与最大版本。宿主版本超出范围时 Gateway 进入 `host-incompatible`：管理入口只读、数据不迁移、外部端点返回 `HOST_INCOMPATIBLE`。

## 16. 一致性套件

Android、Hermes 和 OpenClaw 实现必须共同通过语言无关向量：

- 协商成功、次版本交集、主版本拒绝和未知风险拒绝；
- 密码、邀请、刷新、设备密钥四种账号绑定流程；
- refresh 轮换与重复使用撤销；
- 请求签名、防重放、时钟偏差、身份覆盖拒绝和 generation fencing；
- 对话严格账号隔离与多线程路由；
- 附件长度/摘要、TTL、ACK 和崩溃清理；
- SSE 有序游标、断线恢复、过期重建和重复事件幂等；
- 审批卡片的结构化下发、独立决策端点、档位闭集与超时/撤回终态（第 7.2 节）；
- 三档离线队列期限与执行前重新授权；
- 提供者切换、授权 revision 和 Companion 故障关闭；
- 多账号文件级隔离、备份不含活动身份、账号删除；
- Android/Gateway correlation ID 对应且审计不含正文。

六个 JSON 向量文件必须由版本化 meta-schema `gateway-contract/vectors/vector-set-1.0.0.schema.json` 验证，顶层结构固定为：

```json
{
  "formatVersion": "1.0.0",
  "protocolVersion": "2.0",
  "vectorSet": "request-signatures",
  "cases": []
}
```

`vectorSet` 的精确枚举与文件/operation 归属为：

| 文件 | `vectorSet` | 允许的 `operation` |
|---|---|---|
| `request-signatures.json` | `request-signatures` | `request.target`, `request.signature` |
| `protocol-negotiation.json` | `protocol-negotiation` | `schema.validate` |
| `auth-sessions.json` | `auth-sessions` | `schema.validate` |
| `attachments.json` | `attachments` | `schema.validate`, `attachment.transition` |
| `sse-events.json` | `sse-events` | `schema.validate_dispatched` |
| `device-requests.json` | `device-requests` | `schema.validate_dispatched`, `device.transition`, `device.maximum_queue_seconds` |

operation 的完整枚举只有：

```text
request.target
request.signature
schema.validate
schema.validate_dispatched
attachment.transition
device.transition
device.maximum_queue_seconds
```

顶层对象和每个 case 都拒绝未知字段。case 只能包含 `id`、`operation`、`input`、`expected`，并以 `operation` 作为 discriminant；case ID 满足 wire ID 且在六文件中全局唯一。每个文件至少包含一个 value 和一个 error case，不得出现 `skipped`、`futureOnly` 或没有生产消费者的 case。

每个 operation 的 `input` 和 value 结果为以下闭合对象；表中未列字段全部拒绝：

| `operation` | `input` | `expected.value` | 允许的 `expected.error.code` |
|---|---|---|---|
| `request.target` | `{ target: string }` | `{ canonicalTarget: string }` | `SCHEMA_INVALID` |
| `request.signature` | `{ method, target, accountId, deviceId, sessionId, requestId, timestamp, nonce, bodyHex }` | `{ preimageHex: string }` | `SCHEMA_INVALID`, `NON_CANONICAL_TARGET` |
| `schema.validate` | `{ schemaName, value }` | `{ valid: true }` | `SCHEMA_INVALID` |
| `schema.validate_dispatched` | `{ fixtureBindingSetId, dispatch, value }` | `{ valid: true }` | `SCHEMA_INVALID` |
| `attachment.transition` | `{ current: AttachmentState, event: AttachmentEvent }` | `{ nextState: AttachmentState }` | `INVALID_STATE_TRANSITION` |
| `device.transition` | `{ current: DeviceRequestState, event: DeviceRequestEvent }` | `{ nextState: DeviceRequestState }` | `INVALID_STATE_TRANSITION` |
| `device.maximum_queue_seconds` | `{ risk: "read" | "sync" | "write" | "high-privilege-ephemeral" }` | `{ seconds: 86400 | 900 | 0 }` | `SCHEMA_INVALID` |

`schemaName` 的格式 `1.0.0` 枚举为 `negotiate.request`、`negotiate.response`、`session.password`、`session.refresh`、`session.device`、`conversation.create`、`message.create`、`attachment.create`、`event`、`device.request`、`response.success`、`response.failure`。`schema.validate_dispatched.input.fixtureBindingSetId` 在格式 `1.0.0` 中只有一个合法值 `gateway-core-fixtures-v1`；`dispatch` 必须满足第 4.1 节不含 digest 的 `TrustedGatewayDispatch`。`value` 是待验证 JSON value，不改变 binding。

本补充计划 Task 2 必须新建以下共享、版本化测试资产：

```text
gateway-contract/vectors/dispatched-schema-fixtures-1.0.0.schema.json
gateway-contract/vectors/dispatched-schema-fixtures.json
```

`dispatched-schema-fixtures-1.0.0.schema.json` 是 registry meta-schema；`dispatched-schema-fixtures.json` 是 TypeScript、Python、Kotlin runner 共同读取的唯一 format `1.0.0` registry。registry 顶层只允许：

```json
{
  "formatVersion": "1.0.0",
  "catalogEntries": [],
  "bindingSets": []
}
```

每个 `catalogEntries` 元素只允许 `fixtureId`、完整 `GatewaySubschemaKey`（含 `schemaSha256`）和 `schema`；每个 `bindingSets` 元素只允许 `id`、`bindings`；每个 binding 只允许不含 digest 的 `GatewayLogicalSubschemaKey` 和单独的 `schemaSha256`。所有对象拒绝未知字段。format `1.0.0` registry 必须恰好包含下列七个 catalog entry、按下列顺序排列，并且恰好包含一个 `id = "gateway-core-fixtures-v1"` 的 binding set；该 binding set 的七个 binding 按同一顺序将 logical key 绑定到对应 digest。

```ts
type DispatchedSchemaFixtureCatalogEntry = Readonly<{
  fixtureId: string;
  key: GatewaySubschemaKey;
  schema: object;
}>;

type DispatchedSchemaFixtureBinding = Readonly<{
  key: GatewayLogicalSubschemaKey;
  schemaSha256: string;
}>;

type DispatchedSchemaFixtureBindingSet = Readonly<{
  id: "gateway-core-fixtures-v1";
  bindings: readonly DispatchedSchemaFixtureBinding[];
}>;
```

#### `event.gateway-notice.v1`

Logical key：`{ kind:"event", eventType:"gateway.notice" }`

规范 JCS bytes：

```json
{"additionalProperties":false,"properties":{"noticeCode":{"minLength":1,"type":"string"}},"required":["noticeCode"],"type":"object"}
```

```text
sha256:597cd548512a66963ae944e75af529fddd0c40cdf8fc59b3fe3cab5287b6c725
```

#### `device.sms-query.v1`

Logical key：`{ kind:"device.request", pluginId:"org.openandroidintelligence.sms", authorKeyId:"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", capabilityId:"org.openandroidintelligence.sms.query", capabilityVersion:"1.0.0" }`

规范 JCS bytes：

```json
{"additionalProperties":false,"properties":{"query":{"minLength":1,"type":"string"},"receivedAfter":{"format":"date-time","type":"string"},"senders":{"items":{"type":"string"},"type":"array"}},"required":["query"],"type":"object"}
```

```text
sha256:2b97b44496ebe4e20884dcf12391bc272783513c0ed5a5f459ab062eca6c37ac
```

#### `response.conversation-create.v1`

Logical key：`{ kind:"response.success", operation:"conversation.create", status:201 }`

规范 JCS bytes：

```json
{"additionalProperties":false,"properties":{"conversationId":{"pattern":"^[A-Za-z0-9._~-]{1,128}$","type":"string"}},"required":["conversationId"],"type":"object"}
```

```text
sha256:2719284ed50fba05b945c58eb5146dd53becdcc48b0a57228d8e97a407a0b87e
```

#### `error.cursor-expired.v1`

Logical key：`{ kind:"response.failure", errorCode:"CURSOR_EXPIRED" }`

规范 JCS bytes：

```json
{"additionalProperties":false,"properties":{"recoverableResources":{"items":{"enum":["conversations","attachments","device-requests"]},"type":"array","uniqueItems":true}},"required":["recoverableResources"],"type":"object"}
```

```text
sha256:7cb06a94b19b83c6aba2ed82832bcfd3c103345f4fb6b5f106739cfe40d5fce9
```

#### `event.conversation-command-result.v1`

Logical key：`{ kind:"event", eventType:"conversation.command.result" }`

规范 JCS bytes：

```json
{"additionalProperties":false,"oneOf":[{"additionalProperties":false,"properties":{"command":{"const":"new"},"commandId":{"const":"new"},"conversationId":{"minLength":1,"type":"string"},"outcome":{"const":"created-conversation"},"sourceConversationId":{"minLength":1,"type":"string"},"sourceMessageId":{"minLength":1,"type":"string"}},"required":["command","commandId","outcome","sourceConversationId","sourceMessageId","conversationId"],"type":"object"},{"additionalProperties":false,"properties":{"command":{"const":"new"},"commandId":{"const":"new"},"outcome":{"enum":["rejected","unsupported","outcome-unknown"]},"sourceConversationId":{"minLength":1,"type":"string"},"sourceMessageId":{"minLength":1,"type":"string"}},"required":["command","commandId","outcome","sourceConversationId","sourceMessageId"],"type":"object"}],"properties":{"command":{"const":"new"},"commandId":{"const":"new"},"conversationId":{"minLength":1,"type":"string"},"outcome":{"enum":["created-conversation","rejected","unsupported","outcome-unknown"]},"sourceConversationId":{"minLength":1,"type":"string"},"sourceMessageId":{"minLength":1,"type":"string"}},"required":["command","commandId","outcome","sourceConversationId","sourceMessageId"],"type":"object"}
```

```text
sha256:df7548ff7d994373e2a2f204b389145c6040196be309df0f322ef418be48b1ce
```

该 Schema 用 `oneOf` 双分支表达第 7.1 节的规则：`outcome` 为 `created-conversation` 时必须携带 `conversationId`，其他 outcome 不得携带。共享 dispatched-schema 子集禁用 `if`/`then`/`else`/`not`，因此条件只能这样写。

#### `event.conversation-approval-requested.v1`

Logical key：`{ kind:"event", eventType:"conversation.approval.requested" }`

规范 JCS bytes：

```json
{"additionalProperties":false,"properties":{"approvalId":{"minLength":1,"type":"string"},"command":{"type":"string"},"conversationId":{"minLength":1,"type":"string"},"expiresAt":{"minimum":0,"type":"integer"},"options":{"items":{"additionalProperties":false,"properties":{"choice":{"enum":["once","session","always","deny"]},"label":{"minLength":1,"type":"string"},"style":{"enum":["primary","secondary","danger","neutral"]}},"required":["choice"],"type":"object"},"maxItems":4,"minItems":1,"type":"array"},"reason":{"type":"string"},"requestedAt":{"minimum":0,"type":"integer"},"severity":{"enum":["info","elevated","critical"]},"timeoutSeconds":{"minimum":1,"type":"integer"}},"required":["approvalId","conversationId","command","reason","options","timeoutSeconds","requestedAt"],"type":"object"}
```

```text
sha256:bce36a217163c4626595ffef4b0ac4456ae95bee57646b0a586ea4413b59d663
```

#### `event.conversation-approval-resolved.v1`

Logical key：`{ kind:"event", eventType:"conversation.approval.resolved" }`

规范 JCS bytes：

```json
{"additionalProperties":false,"properties":{"approvalId":{"minLength":1,"type":"string"},"conversationId":{"minLength":1,"type":"string"},"decidedAt":{"minimum":0,"type":"integer"},"decision":{"enum":["once","session","always","deny","timeout","withdrawn"]}},"required":["approvalId","conversationId","decision"],"type":"object"}
```

```text
sha256:2f455f22f1e50d730a80e22a2e9f93e1259a6920747f527041942451bd377916
```

两个形状共同锁定第 7.2 节：档位闭集只有 `once | session | always | deny`，终态闭集追加 `timeout | withdrawn`，且 `approvalId` 是唯一交互句柄——宿主内部会话标识不出现在任何事件载荷里。

每个 catalog entry 的完整 key 等于相应 logical key 加上该段 `schemaSha256`。registry 构造器仍必须独立执行第 4.1 节的 JCS digest 核对、logical key 唯一性、binding/catalog 完整性和 Schema subset 检查，不能只信任文件内 digest。`schema.validate_dispatched` vector 只能引用 `gateway-core-fixtures-v1`；所有 runner 必须从共享 `dispatched-schema-fixtures.json` 构造 catalog 和 bindings，禁止内联、复制或本地替换 Schema/digest/binding。该 registry 只属于一致性测试资产，不进入 runtime 请求，也不改变“请求不可注入 Schema、digest 或 binding”的规则。

`expected` 是精确 tagged union：

```ts
type VectorOperation =
  | "request.target" | "request.signature"
  | "schema.validate" | "schema.validate_dispatched"
  | "attachment.transition" | "device.transition"
  | "device.maximum_queue_seconds";

type VectorErrorCode =
  | "SCHEMA_INVALID" | "NON_CANONICAL_TARGET"
  | "INVALID_STATE_TRANSITION";

type VectorExpected<V> =
  | { outcome: "value"; value: V }
  | { outcome: "error"; code: VectorErrorCode };
```

`INVALID_STATE_TRANSITION` 在这里只是 reducer 内部预期错误，不是第 14 节 wire error。`bodyHex`、`preimageHex` 和其他二进制必须是偶数长度 lowercase hex；timestamp 必须是固定三位毫秒 UTC。`request.signature` 的 oracle 必须写为 `case.expected.value.preimageHex`，不得再使用顶层 `expectedHex`。

原迁移计划 Task 2 的 TypeScript 门禁必须先用 meta-schema 验证六文件，再逐 case 调用生产 canonicalizer、纯 reducer、outer validator 或 dispatched validator，不能只 grep/parse 向量。本补充计划 Task 3 只实现这些纯函数和向量；原迁移计划 Task 3/5 各自实现数据库、CAS、幂等账本、TTL、SSE store、附件 staging 和 crash recovery；原迁移计划 Task 6 使用独立 OpenClaw/Hermes runner；原迁移计划 Task 8/9 保留 Android Keystore、HTTP/SSE byte parser、真机 TLS 以及 claim/result transport 的独立实现和证据。本补充计划 Task 3 的本地向量通过不等于双宿主、Android 或真机一致性已完成。

原迁移计划 Task 6 的每个 runner 必须先构造以下 normalized actual result，value/error 对象都拒绝额外字段：

```ts
type NormalizedActualResult =
  | { vectorId: string; operation: VectorOperation;
      outcome: "value"; value: unknown }
  | { vectorId: string; operation: VectorOperation;
      outcome: "error"; code: VectorErrorCode };
```

`status` 只有 `pass` 或 `fail`：normalized actual result 与 case expected 投影按 JCS bytes 完全相等时为 `pass`，否则为 `fail`。`resultHash = "sha256:" + lowercaseHex(SHA-256(JCS_UTF8(normalizedActualResult)))`。runner JSONL 输出 `{ vectorId, operation, implementation, status, resultHash }`；`implementation`、`status`、vendor diagnostics、堆栈、宿主 ID、时间和路径都不进入 hash。两个实现只比较相同 vector 的 normalized accepted/rejected outcome 与 `resultHash`，不比较 Ajv/Python/Kotlin 错误文本。

任一实现只有在同一向量版本全部通过后才能声明 `Gateway Protocol 2.0` 兼容。
