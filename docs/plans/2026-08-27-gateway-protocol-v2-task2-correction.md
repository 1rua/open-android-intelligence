# Gateway Protocol v2 原迁移计划 Task 2 安全纠偏与实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不进入 OpenClaw/Hermes/Android runtime 的前提下，修正 Gateway Protocol v2 的线协议歧义，补齐动态 Schema 失败关闭接口，并完成原迁移计划 Task 2 的签名、纯状态机和六组可执行黄金向量。

**Architecture:** 先修改唯一权威协议和原迁移计划，使 wire ID、HTTP header、canonical target、签名预像、动态 Schema catalog、附件/设备请求状态矩阵都有唯一语言无关语义；再用 TDD 加深 `gateway-contract` 的 Schema 边界；最后实现不依赖数据库、网络、时钟或宿主的纯 TypeScript canonicalizer/reducer，并让六个 JSON 向量全部被测试真实消费。

**Tech Stack:** Node.js `>=24.18.0 <25`、npm `11.16.0`、TypeScript `7.0.2`、Vitest `4.1.10`、Ajv `8.20.0`、`canonicalize` `3.0.0`、SHA-256。

**Spec:** `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`

## Global Constraints

- `docs/contracts/gateway-protocol-v2.md` 是网络协议唯一权威来源；实现和向量不得创造契约外语义。
- Gateway Protocol v2 不兼容 Bridge Protocol v1；不得导入 v1 wire bytes、身份、配对密钥、队列或数据库状态。
- TypeScript、Python、Kotlin 后续必须从同一语义输入独立重建结果；本补充计划 Task 2 不建立共享运行二进制。
- 动态 Schema 的来源必须在 validator 构造前已受信，运行期请求不得注入 Schema 或 `ValidateFunction`。
- Android 本地授权仍是设备执行最终权威；Schema 与 Gateway 状态机不能冒充授权裁决。
- 本计划只修改协议文档、迁移计划和 `gateway-contract/`；不得新建或修改 `integrations/openclaw/`、`integrations/hermes/`、Android runtime、Bridge v1 或 Tailscale 路径。
- 使用独立 worktree；保留主工作树和 tsnet/P0t 证据；不得使用 `rm`、`rm -rf`、`unlink` 或任何永久删除命令。
- 所有代码路径与模块引用使用相对路径；所有 Git 提交说明使用中文。

---

### 本补充计划 Task 1: 固化 Gateway v2 线协议、动态分派和状态权威

**Files:**
- Create: `docs/superpowers/plans/2026-08-27-gateway-protocol-v2-task2-correction.md`
- Modify: `docs/contracts/gateway-protocol-v2.md`
- Modify: `docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md`

**Interfaces:**
- Produces: 封闭 opaque record ID 集合的 wire ID 正则 `^[A-Za-z0-9._~-]{1,128}$`，不覆盖 digest/key identity
- Produces: canonical request target 的语言无关逐字节算法
- Produces: 10 个 ASCII 字段、9 个 LF、末尾无 LF 的请求签名预像
- Produces: 构造期不可变动态 Schema catalog、`VerifiedSchemaBindingSet` 与无 digest runtime dispatch 规则
- Produces: 完整 `AttachmentState × AttachmentEvent` 与 `DeviceRequestState × DeviceRequestEvent` 矩阵
- Produces: 六组向量的闭合 `1.0.0` meta-schema、`ClaimReceipt` 和本补充计划 Task 3、原迁移计划 Task 3/5/6/9 的消费边界

- [ ] **Step 1: 修正 wire ID、header 和身份来源契约**

在协议第 2、5、6 节写明：

```text
wire-id = 1*128(ALPHA / DIGIT / "." / "_" / "~" / "-")
```

wire ID 只覆盖协议封闭列举的 opaque record IDs，包括 request/correlation/negotiation/installation/deployment/account/device/pairing/session/invitation/conversation/message/attachment/event/device-request/claim IDs。`authorKeyId`、`schemaSha256`、`tlsSpkiSha256`、内容摘要、编码公钥/签名/credential 以及 plugin/capability/version/operation/error code 不属于 wire ID，继续使用各自 Schema；不得静默改变 author-key encoding。以下九个认证 header 必须从 framework 合并前的 raw header 列表证明始终恰好出现一次：

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

条件 singleton 也从同一 raw 列表验证：已认证 `POST|PUT|DELETE` 的 `Idempotency-Key` 恰好一次而 `GET` 不出现；`Last-Event-ID` 只允许在带 canonical query cursor 的 `GET /events` 出现零或一次；JSON body 的 `Content-Type` 恰好一次；attachment content 的 `Content-Length` 和 `Digest` 各恰好一次，其他 entity-body 的 `Content-Length` 为零或一次。任何重复（包括相同值重复）、逗号合并、trim 后才合法、CR/LF/NUL/其他控制字符全部拒绝；header 名大小写不敏感，值不做 trim、unfold、first/last 选择。token 验证出的账号、设备、会话必须与 header 精确相等，后续业务只能读取一个 `VerifiedRequestContext`。

- [ ] **Step 2: 固化 method、body、幂等和 SSE cursor 规则**

```text
method    = "GET" / "POST" / "PUT" / "DELETE"
timestamp = RFC3339 UTC with exactly milliseconds
nonce     = canonical unpadded base64url of exactly 16 bytes
signature = canonical unpadded base64url of exactly 64 Ed25519 bytes
```

method 在线上区分大小写，不得通过 `toUpperCase()` 接受别名。body digest 覆盖 HTTP transfer framing 解码后的 exact entity-body bytes，并在 JSON decode、字符转换、自动解压或重序列化之前计算；JSON 请求禁止 `Content-Encoding`。零字节 body 的摘要固定为：

```text
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

已认证 `POST|PUT|DELETE` 的 `Idempotency-Key` 必须精确等于已签名的 `X-Open-Android-Intelligence-Request-Id`；重试可以更换 timestamp/nonce，但保持 request ID。认证 SSE 恢复以 canonical query 的 `cursor` 为权威，`Last-Event-ID` 若存在必须与 query 精确相等且其他路由拒绝该 header。

- [ ] **Step 3: 固化 canonical request target 算法**

输入只允许 `/open-android-intelligence/v2` 下的 HTTP origin-form。规范算法按以下顺序执行：

1. 拒绝 absolute-form、authority-form、`*`、fragment、空白和控制字符。
2. 只在第一个字面量 `?` 分离 path/query；query 内字面量 `?` 非规范。
3. path 用字面量 `/` 分段；完整 `%HH` 解码为 byte。
4. `[A-Za-z0-9._~-]` byte 原样输出，其他 byte 输出大写 `%HH`。
5. 拒绝解码后为 `.`、`..` 的 segment；拒绝解码后 `/` 或 `\\`；拒绝 `//` 和非根尾随 `/`。
6. query 用字面量 `&` 分 pair，空 pair 拒绝；在第一个字面量 `=` 分 name/value，空 name 拒绝，canonical 输出始终包含 `=`。
7. name/value 使用同一 byte 编码；`+` 规范为 `%2B`，不解释成空格。
8. 按 canonical encoded name 的 ASCII bytes 排序，再按 value 排序；完全重复 pair 保留数量。
9. 无 query 不输出 `?`，空 query 标记拒绝。
10. 客户端发送的 raw target 与 Gateway 验签读取的 raw target 都必须等于 canonical target；反向代理不能靠未认证 `X-Original-URI` 恢复被重写路径。

- [ ] **Step 4: 固化请求签名预像和独立 oracle**

预像严格为：

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

总共 10 个字段、9 个 `0x0A`，最后无 LF、CR、NUL、空格或 BOM。Ed25519 直接签完整预像，不使用 Ed25519ph。

协议写入以下独立向量：

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

canonical target：

```text
/open-android-intelligence/v2/events?cursor=evt_1&z=last
```

expected preimage hex：

```text
4147454e542d4c4946452d524551554553542d56320a4745540a2f6167656e742d6c6966652f76322f6576656e74733f637572736f723d6576745f31267a3d6c6173740a616363745f310a6465765f310a736573735f310a7265715f310a323032362d30382d32375430303a30303a30302e3030305a0a414141414141414141414141414141414141414141410a65336230633434323938666331633134396166626634633839393666623932343237616534316534363439623933346361343935393931623738353262383535
```

- [ ] **Step 5: 固化动态 Schema catalog 契约**

保留 `schemaFor` / `validateGatewayValue` 的 outer-only 兼容语义。新增原子 dispatched validator：构造时接收并冻结 catalog，运行时按可信 dispatch 完成外壳验证、key 解析、catalog lookup、子值验证。catalog entry 的 Schema identity 为 `sha256:` 加 RFC 8785 JCS UTF-8 bytes 的小写 SHA-256。

四类 key：

```ts
type GatewaySubschemaKey =
  | { kind: "event"; eventType: string; schemaSha256: string }
  | { kind: "device.request"; pluginId: string; authorKeyId: string;
      capabilityId: string; capabilityVersion: string; schemaSha256: string }
  | { kind: "response.success"; operation: string; status: number; schemaSha256: string }
  | { kind: "response.failure"; errorCode: string; schemaSha256: string };
```

运行时 `TrustedGatewayDispatch` 不含 digest：event 只含可信 SSE `event:` type；device provider/capability 从已验证 value 提取；success operation/status 来自本地请求上下文；failure code 从已验证 error envelope 提取。构造器同时接收不可变 `VerifiedSchemaBindingSet`：event/response/error 的逻辑 key 只能从本地 verified core binding 选唯一 digest，device 逻辑 key 只能从 authenticated session/pairing binding 选唯一 digest。请求、payload、body、message 和通用调用方不得传入或覆盖 digest、Schema、binding、resolver 或 `ValidateFunction`。重复逻辑 key、同一逻辑 key 多 digest、binding 指向缺失 catalog、digest 不符均在构造时失败。

catalog 格式 `1.0` 的 root 必须直接声明 `type: "object"` 与 `additionalProperties: false`，拒绝 root `$ref`；每个声明 object/properties 的遍历节点也必须直接 closed。只允许本地 `#/$defs/...` ref；拒绝 external/unresolved ref、`unevaluatedProperties`、`patternProperties`、`$dynamicRef`、`$dynamicAnchor`。构造器只遍历 `$defs`、`properties`、单 Schema `items` 和 `allOf|anyOf|oneOf` arrays，其他 Schema-bearing keyword 在格式 1.0 拒绝，并使用 visited set 防止 ref 循环。

TypeScript diagnostics 固定为 `instancePath + "\\t" + schemaPath + "\\t" + keyword + "\\t" + JCS(params)`；dispatch error 使用两个空 path、keyword `dispatch` 和 JCS params。字符串去重后按 UTF-8 bytes 升序排序，冻结数组和外层失败结果。跨语言 `resultHash` 只覆盖 accepted/rejected normalized result，不覆盖 vendor diagnostics。

- [ ] **Step 6: 固化附件矩阵**

状态和事件：

```ts
type AttachmentState =
  | "created" | "uploading" | "verified" | "delivered"
  | "acknowledged" | "failed" | "expired" | "deleted";

type AttachmentEvent =
  | "begin_upload" | "verify" | "deliver" | "acknowledge"
  | "fail" | "expire" | "cleanup";
```

合法转移：

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

全部其他组合在纯 reducer 内部抛 `INVALID_STATE_TRANSITION`，该 code 不进入 wire error list。精确 HTTP 重试由 reducer 前的幂等账本处理，不给普通事件增加自循环。TTL 到期立即删除 staged bytes 并以 `expire` 记录 `expired`，后续 `cleanup` 才把元数据转为 `deleted`。解除配对和删除账号属于原迁移计划 Task 3 的资源级事务，不伪装成逐附件事件。

- [ ] **Step 7: 固化设备请求 claim 与状态矩阵**

状态和事件：

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

合法转移：

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

全部其他组合在纯 reducer 内部抛 `INVALID_STATE_TRANSITION`，`outcome_unknown` 是不可被普通结果覆盖的终态。HTTP malformed input 映射 `SCHEMA_INVALID`，幂等输入变化映射 `IDEMPOTENCY_CONFLICT`，已 claim 结果不确定映射 `OUTCOME_UNKNOWN`。

协议增加幂等 claim 操作并返回服务端生成的 `ClaimReceipt { claimId, requestId, accountId, deviceId, pairingGeneration, grantRevision }`，其中 `requestId` 是设备请求 ID；`claimId` 是 wire ID，receipt 原子绑定上述字段并形成最小审计。Android 必须在任何副作用前 claim，result wire body 从 receipt 原样带回同一 `claimId` 与 `grantRevision`，不提交身份字段；Gateway 以服务端 receipt 和 `VerifiedRequestContext` 核对其他绑定，任一不匹配都拒绝。`device.request.cancel.requested` 只表达取消意图：未 claim 的 pending 可直接 `cancelled`，claim 后只能进入 `cancel_requested`，再由可信 result 决定 `cancelled`、其他真实终态或 `outcome_unknown`。claim/result 的 SQLite、CAS、HTTP route 留给原迁移计划 Task 3/5/9。

- [ ] **Step 8: 固化向量格式与后续边界**

六个 JSON 顶层统一为：

```json
{
  "formatVersion": "1.0.0",
  "protocolVersion": "2.0",
  "vectorSet": "request-signatures",
  "cases": []
}
```

本补充计划 Task 3 新建 `gateway-contract/vectors/vector-set-1.0.0.schema.json`。精确 `vectorSet` 为 `request-signatures`、`protocol-negotiation`、`auth-sessions`、`attachments`、`sse-events`、`device-requests`；精确 operation 为 `request.target`、`request.signature`、`schema.validate`、`schema.validate_dispatched`、`attachment.transition`、`device.transition`、`device.maximum_queue_seconds`。meta-schema 以 operation 为 discriminant，闭合验证契约第 16 节逐 operation 的 input、`expected.value` 和 `{ outcome: "error", code }` union；`request.signature` 只使用 `expected.value.preimageHex`，不得使用 `expectedHex`。

顶层和 case 均拒绝未知字段；case 只含 `id`、`operation`、`input`、`expected`；ID 在六文件中全局唯一；每文件至少一个 value 和一个 error；二进制用偶数长度 lowercase hex；时间固定毫秒 UTC；不能出现 `skipped`、`futureOnly` 或无人消费 case。动态 Schema vector 的 `fixtureBindingSetId` 只能是 `gateway-core-fixtures-v1`；runner 必须从本补充计划 Task 2 创建的共享 `dispatched-schema-fixtures.json` 构造 catalog/bindings，不能携带或本地替换 Schema/digest。

原迁移计划 Task 2 增加 `golden-vectors.test.ts`、meta-schema 与 dispatched validator/binding set 依赖；原迁移计划 Task 9 的 `DeviceRequestClient.claim` 返回 `ClaimReceipt`，`submitResult` 接受该 receipt。原迁移计划 Task 3/5 保留数据库、CAS、幂等账本、TTL、SSE store、附件 staging、crash recovery；原迁移计划 Task 6 保留双宿主 runner，并以 `resultHash = "sha256:" + lowercaseHex(SHA-256(JCS_UTF8(normalizedActualResult)))` 哈希 `{vectorId,operation,outcome,value}` 或 `{vectorId,operation,outcome:"error",code}`，排除 implementation/status/vendor diagnostics；原迁移计划 Task 8/9 保留 Keystore、HTTP/SSE parser、真机 TLS 和 claim/result transport。

- [ ] **Step 9: 自审文档并提交**

Run:

```bash
rg -n "T[B]D|T[O]DO|implement[ ]later|类似[ ]Task|适当[ ]错误处理" \
  docs/contracts/gateway-protocol-v2.md \
  docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md \
  docs/superpowers/plans/2026-08-27-gateway-protocol-v2-task2-correction.md
git diff --check
```

Expected: `rg` 无命中，`git diff --check` 退出码 0。

Commit:

```bash
git add docs/contracts/gateway-protocol-v2.md \
  docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md \
  docs/superpowers/plans/2026-08-27-gateway-protocol-v2-task2-correction.md
git commit -m "文档: 固化 Gateway v2 签名与状态契约"
```

### 本补充计划 Task 2: 完成 wire ID 与动态 Schema 二阶段验证

**Files:**
- Modify: `gateway-contract/package.json`
- Modify: `gateway-contract/schemas/envelope.schema.json`
- Modify: `gateway-contract/src/schema-registry.ts`
- Create: `gateway-contract/src/dispatched-schema-validator.ts`
- Create: `gateway-contract/vectors/dispatched-schema-fixtures-1.0.0.schema.json`
- Create: `gateway-contract/vectors/dispatched-schema-fixtures.json`
- Modify: `gateway-contract/test/schema-registry.test.ts`
- Create: `gateway-contract/test/dispatched-schema-validator.test.ts`

**Interfaces:**
- Produces: `GatewaySchemaName` 新增 `response.success` / `response.failure`
- Produces: `gatewaySubschemaSha256(schema: object): string`
- Produces: `VerifiedSchemaBindingSet`
- Produces: `createGatewayDispatchedValidator(entries, bindings): GatewayDispatchedValidator`
- Produces: format `1.0.0` 的共享 dispatched Schema fixture registry 与唯一 binding-set ID `gateway-core-fixtures-v1`
- Preserves: `schemaFor(name)` / `validateGatewayValue(name, value)` outer-only 行为

- [ ] **Step 1: 写 wire ID 和公开 response 外壳失败测试**

只把契约封闭列举的 opaque record ID Schema 改为接受：

```text
A-Z a-z 0-9 . _ ~ -
```

并拒绝 space、HTAB、LF、CR、NUL、DEL、逗号、斜杠、百分号和 Unicode。保留长度 0/1/128/129 边界。`authorKeyId`、`schemaSha256`、`tlsSpkiSha256`、公钥/签名和 plugin/capability identity 继续通过各自 Schema，不套用 opaque ID。为 `response.success` 和 `response.failure` 加合法外壳、未知顶层字段和错误字段测试。

- [ ] **Step 2: 写不可变 catalog 与原子验证失败测试**

测试至少覆盖：

- event：可信 event type 经本地 core binding 选择唯一 digest，合法 payload 通过；未知 type、binding 缺失、payload 未知字段拒绝；payload 自报 type/digest 不参与分派。
- device：外壳通过后从 provider/capability 提取逻辑 key，经 authenticated session/pairing binding 选择唯一 digest；author/plugin/capability/version 任一不匹配拒绝；合法嵌套 parameters 通过。
- success：可信 operation/status 经本地 core binding 选择唯一 digest 和 data Schema；错误 operation/status 拒绝。
- failure：从已验证 `error.code` 选择 details Schema；修改 message 不改变分派；未知 code 拒绝。
- catalog/bindings：重复逻辑 key、同逻辑 key 多 digest、binding 缺失 entry、digest 不符、无法 strict 编译构造失败；runtime API 无 digest 参数。
- Schema subset：root `$ref`、非 closed root/object node、external/unresolved/非 `$defs` ref、`unevaluatedProperties`、`patternProperties`、`$dynamicRef`、`$dynamicAnchor` 和格式 1.0 不支持的 Schema-bearing keyword 构造失败；遍历 `$defs`、properties、items、composition arrays 和循环 ref。
- 防御性：构造后修改输入 Schema/binding 或 getter 返回值不影响 validator；错误字符串按 `instancePath<TAB>schemaPath<TAB>keyword<TAB>JCS(params)` 去重并以 UTF-8 bytes 排序，数组和结果冻结。
- 顺序：外壳失败时返回外壳错误，不进入动态 key 提取。
- fixture registry：meta-schema 关闭顶层/catalog entry/binding set/binding 未知字段；registry 恰好包含契约四个 `fixtureId`、逐字 JCS bytes/digest/logical key 和唯一 `gateway-core-fixtures-v1` binding set；缺失、额外、重排、digest 变化、binding 不完整或 vector 使用其他 binding-set ID 均拒绝。
- 共享消费：TypeScript test 从 `dispatched-schema-fixtures.json` 构造 catalog/bindings；后续 Python、Kotlin runner 也只能读取同一 JSON 资产。三种语言均禁止内联、复制或本地替换任一 Schema/digest/binding。

- [ ] **Step 3: 运行 RED**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- schema-registry.test.ts dispatched-schema-validator.test.ts
```

Expected: FAIL，原因是 ID Schema 仍接受控制字符、response 名称不存在、`dispatched-schema-validator.ts` 与两份共享 fixture registry 资产不存在。

- [ ] **Step 4: 最小实现 wire ID 与 response 外壳**

`opaqueId` 使用：

```json
{
  "type": "string",
  "minLength": 1,
  "maxLength": 128,
  "pattern": "^[A-Za-z0-9._~-]+$"
}
```

`definitions` 增加：

```ts
"response.success": [envelopeDocument, "success"],
"response.failure": [envelopeDocument, "failure"],
```

旧接口继续只做外壳验证。

- [ ] **Step 5: 最小实现 dispatched validator**

`gatewaySubschemaSha256` 对 self-contained Schema 做 RFC 8785 JCS、UTF-8、SHA-256，返回 `sha256:<64 lowercase hex>`。catalog entry 结构：

```ts
type GatewaySubschemaCatalogEntry = Readonly<{
  key: GatewaySubschemaKey;
  schema: object;
}>;
```

构造器 defensive-clone catalog 与 `VerifiedSchemaBindingSet`，机械检查契约格式 1.0 Schema subset，核对 key digest、logical key 唯一绑定并 strict Ajv 编译，再存入不可变内部 map。运行期 API 不接受 digest/Schema/binding/resolver，只接受不含 digest 的 `TrustedGatewayDispatch` 与 value；完整验证一次性返回冻结 `ValidationResult`。

测试 fixture loader 必须先以 `dispatched-schema-fixtures-1.0.0.schema.json` 验证共享 registry，再逐项重算四个 Schema 的 JCS digest，并只用 `gateway-core-fixtures-v1` 构造 validator。fixture registry 不导出给 runtime request，也不能成为 runtime Schema resolver。

- [ ] **Step 6: 运行 GREEN、回归和类型检查**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- schema-registry.test.ts dispatched-schema-validator.test.ts
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract run typecheck
```

Expected: 全部 PASS，输出无 warning/error。

- [ ] **Step 7: 自审并提交**

检查所有 `validateGatewayValue` 调用方仍只有测试，catalog 无请求级 Schema 输入，`git diff --check` 通过。

Commit:

```bash
git add gateway-contract/package.json \
  gateway-contract/schemas/envelope.schema.json \
  gateway-contract/src/schema-registry.ts \
  gateway-contract/src/dispatched-schema-validator.ts \
  gateway-contract/vectors/dispatched-schema-fixtures-1.0.0.schema.json \
  gateway-contract/vectors/dispatched-schema-fixtures.json \
  gateway-contract/test/schema-registry.test.ts \
  gateway-contract/test/dispatched-schema-validator.test.ts
git commit -m "修复: 完善 Gateway v2 动态 Schema 验证"
```

### 本补充计划 Task 3: 实现原迁移计划 Task 2 的签名、状态机与六组向量

**Files:**
- Create: `gateway-contract/src/request-signature.ts`
- Create: `gateway-contract/src/state-machines.ts`
- Create: `gateway-contract/vectors/vector-set-1.0.0.schema.json`
- Create: `gateway-contract/vectors/request-signatures.json`
- Create: `gateway-contract/vectors/protocol-negotiation.json`
- Create: `gateway-contract/vectors/auth-sessions.json`
- Create: `gateway-contract/vectors/attachments.json`
- Create: `gateway-contract/vectors/sse-events.json`
- Create: `gateway-contract/vectors/device-requests.json`
- Create: `gateway-contract/test/request-signature.test.ts`
- Create: `gateway-contract/test/state-machines.test.ts`
- Create: `gateway-contract/test/golden-vectors.test.ts`

**Interfaces:**
- Produces: `canonicalRequestTarget(target: string): string`
- Produces: `canonicalRequestSignatureInput(input: SignedRequestInput): Uint8Array`
- Produces: `nextAttachmentState(current, event): AttachmentState`
- Produces: `nextDeviceRequestState(current, event): DeviceRequestState`
- Produces: `maximumDeviceRequestQueueSeconds(risk): 86400 | 900 | 0`

- [ ] **Step 1: 写逐字节签名和 target RED 测试**

测试加载 `request-signatures.json`，从 `bodyHex` 独立解码 bytes，断言 canonical target、完整 `expected.value.preimageHex`、末尾无 LF。加入：method/wire-ID/timestamp/nonce/body 任一变化改变预像；小写 method、非法 wire ID、非 canonical nonce/timestamp 拒绝；digest/key identity 不误套 wire ID；absolute target、fragment、dot segment、`//`、尾随 `/`、编码 slash/backslash、畸形 `%`、空 query 拒绝；query percent 编码、重复 pair 与排序按契约执行。

- [ ] **Step 2: 运行签名 RED**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- request-signature.test.ts
```

Expected: FAIL，原因是 `request-signature.ts` 与向量不存在。

- [ ] **Step 3: 实现 canonical target 与签名预像**

`SignedRequestInput.body` 必须是 `Uint8Array`；不 parse/stringify body。method 是封闭大写 union；wire ID、timestamp、nonce 逐项 runtime 校验。使用 `node:crypto` 对 exact bytes 做 SHA-256。`canonicalRequestTarget` 把可规范化输入变为本补充计划 Task 1 契约的 ASCII origin-form；`canonicalRequestSignatureInput` 必须先确认 `input.target === canonicalRequestTarget(input.target)`，非 canonical target 抛 `NON_CANONICAL_TARGET`，再使用固定 10 行 framing。这样客户端先构造并发送 canonical target，Gateway 从 raw request-target 验证同一字符串。

- [ ] **Step 4: 写状态机 RED 测试**

测试完整遍历本补充计划 Task 1 的状态/事件笛卡尔积：合法表得到精确下一状态；全部其他组合抛内部 `INVALID_STATE_TRANSITION`；输入字符串无可变状态。测试 `maximumDeviceRequestQueueSeconds`：read/sync `86400`、write `900`、high-privilege-ephemeral `0`。

- [ ] **Step 5: 运行状态机 RED**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- state-machines.test.ts
```

Expected: FAIL，原因是 `state-machines.ts` 不存在。

- [ ] **Step 6: 实现纯 reducer**

使用只读 transition table；函数不接受 clock、数据库、重试次数、账号目录或授权对象。未知 runtime cast 的 state/event 也抛 `INVALID_STATE_TRANSITION`。`outcome_unknown` 和其他终态没有普通出边。

- [ ] **Step 7: 创建六组闭合向量并写消费门禁**

每个文件必须先通过 `vector-set-1.0.0.schema.json`，并使用本补充计划 Task 1 的 `1.0.0` 顶层结构。operation 精确覆盖：

```text
request.target
request.signature
schema.validate
schema.validate_dispatched
attachment.transition
device.transition
device.maximum_queue_seconds
```

`golden-vectors.test.ts` 验证六文件存在且通过 meta-schema、顶层/case 闭合、六个 vectorSet 与文件名一致、operation/file 归属、case ID 全局唯一、每文件有 value/error、逐 operation input/expected union、lowercase hex/毫秒 UTC、每个 operation 有明确消费者；不得仅 grep 文本。`protocol-negotiation` 和 `auth-sessions` 的 Schema cases 调用 `validateGatewayValue`；SSE/device 动态 cases 的 `fixtureBindingSetId` 必须恒等于 `gateway-core-fixtures-v1`，并只从共享 `dispatched-schema-fixtures.json` 构造 `GatewayDispatchedValidator`，不能内联或本地替换 Schema/digest/binding；状态与签名 cases 分别调用生产函数。

- [ ] **Step 8: 运行本补充计划 Task 2/3 完整验证**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract run typecheck
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npx vitest run --exclude '.worktrees/**'
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npx tsc --noEmit
git diff --check
```

Expected: 全部 PASS；根测试不得扫描其他 worktree；不把 TypeScript 本地向量宣称为 Python/Kotlin/Android 一致性或真机证据。

- [ ] **Step 9: 自审并提交**

确认只改本补充计划 Task 3 列出的 `gateway-contract` 新文件，未进入 `integrations/openclaw`、Hermes、Android、Bridge 或 Tailscale。

Commit:

```bash
git add gateway-contract/src/request-signature.ts \
  gateway-contract/src/state-machines.ts \
  gateway-contract/vectors \
  gateway-contract/test/request-signature.test.ts \
  gateway-contract/test/state-machines.test.ts \
  gateway-contract/test/golden-vectors.test.ts
git commit -m "新增: 固化 v2 签名与状态机向量"
```

## 本计划完成边界

完成本补充计划三个 Task、逐 Task 独立审查和最终范围审查后停止。不得创建 `integrations/openclaw/` 文件，不得声称原迁移计划 Task 3 runtime、双宿主一致性、Android HTTP/SSE、真机 TLS 或生产部署已经完成。
