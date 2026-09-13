# Agent 端插件网关问题排查报告（Hermes / OpenClaw）

- 日期：2026-09-12
- 范围：`integrations/hermes`（Python 原生插件）、`integrations/openclaw`（TypeScript 插件）、`integrations/shared`（共享适配器契约）
- 判定依据：`docs/contracts/gateway-protocol-v2.md`（§2/§3/§5/§6/§7/§9/§14/§15/§16）、`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`
- 结论一句话：**Hermes 侧功能面基本齐全但边界处有多处"把不合规输入改造成合规"的伪造行为与未实现的 SSE/宿主兼容语义；OpenClaw 侧只有半个 core，认证、协商、SSE、加密落盘与附件策略均未实现，§16 要求的"三端共同通过语言无关向量"当前不成立。**

> **修复状态（2026-09-13）**：P0-2（Schema 断链）、P0-3（任意密码登录）、P0-4（协商洗数据）、P0-5（SSE 不验签/无游标）与本轮范围内的 P1 已修，双宿主一致性门禁复绿。逐项证据与遗留项见 `docs/superpowers/plans/2026-09-12-agent-side-gateway-fix-plan.md` 的 §1.1 执行进度。
> **OpenClaw（W6，2026-09-13）**：P0-1 的认证、协商与 P1-11 的附件策略已补齐，P0-7 的明文落盘与 P0-6 的 SSE **仍未实现**，但已从清单的虚假声明改为逐项如实标注（`capabilities.sse=false`、`capabilities.encryptionAtRest=false`、`securityBoundary.zeroRetention="not-implemented"`）。因此 OpenClaw 宿主下手机端仍收不到 Agent 回复。
> **仍未处理**：OpenClaw 的 SSE 与 AEAD 宿主接缝、SSE 真机断线续传验证（W4 收尾）、W7 剩余清理项。

---

## 1. 核查方法（可复现）

| 目的 | 命令 | 结果 |
| --- | --- | --- |
| Hermes 测试 | `cd integrations/hermes && /tmp/oai-venv/bin/python -m pytest tests -q` | `2 failed, 92 passed` |
| Hermes 共享向量逐例 | `create_gateway_core(...).run_shared_vectors()` | 28 例，**27 通过，1 失败**：`sse-event-gateway-notice-valid`（`schema.validate_dispatched`） |
| OpenClaw 测试 | `cd integrations/openclaw && ../../tools/run-node24 npx vitest run` | **10 个测试文件失败，17 例失败 / 1 例通过** |
| 共享 Schema 可用性 | vitest 输出 | 全部失败收敛到 `MissingRefError: conversation-snapshot.schema.json → envelope.schema.json#/$defs/positiveInt` |
| 附件/事件/参数落盘方式 | 源码核查 | OpenClaw 全明文；Hermes 全 AEAD 封装 |

> 说明：`MissingRefError` 与 `sse-event-gateway-notice-valid` 属既有失败基线（见 `.codebuddy/memory/MEMORY.md` §9），本报告不把它们计为新增缺陷，但**它们在 OpenClaw 侧造成的后果远比基线记录严重**，见 P0-2。

---

## 2. P0：阻断真实闭环

### P0-1 OpenClaw 无法完成协商与登录，手机端连不上

- `integrations/openclaw/src/core/gateway-core.ts:237-379` 的 `handle()` 只实现了对话/消息/附件/设备请求的写路径，**没有** `POST /negotiate`、`POST /sessions/password`、`POST /sessions/refresh`、`DELETE /sessions/current`、`GET /commands`、`GET /conversations`（列表/单会话/消息分页）任何分支，未命中时统一 `failure(request, "SCHEMA_INVALID")`。
- `integrations/openclaw/src/http/routes.ts:407-418` 的 `routeDefinitions` 也未注册 `sessions/*`、`commands` 路由（只注册 negotiate / events / conversations(+prefix) / attachments(+prefix) / device-requests/）。
- 更糟的是 `handle()` 的类型签名要求 `context: VerifiedRequestContext`，而协商是认证前请求，**结构上不可能被这条路径服务**；即使注册了 `POST /negotiate`，也会先撞上 `runIdempotent`（`gateway-core.ts:165`）的 `idempotencyKey !== requestId → IDEMPOTENCY_CONFLICT`。
- 按契约 §16（`docs/contracts/gateway-protocol-v2.md:672-686`）Hermes 与 OpenClaw 必须共同通过同一套向量，当前 OpenClaw 连协商都跑不通。

### P0-2 共享 Schema 注册表整体不可用，OpenClaw 全部写接口退化为 INTERNAL_ERROR

- 根因：`gateway-contract/schemas/conversation-snapshot.schema.json` 引用了 `envelope.schema.json#/$defs/positiveInt`，但 envelope 的 `$defs` 只有 `opaqueId / utcMillis / bareSha256 / prefixedSha256 / nonEmptyString / base64urlEd25519PublicKey / base64urlEd25519Signature / success / failure`，**没有 `positiveInt`**。
- `gateway-contract/src/schema-registry.ts:90` 会把全部文档交给 AJV 编译，因此该断链会让 `validateGatewayValue` 整体不可用。
- 后果：`gateway-core.ts:112-115` 的 `assertSchema` 抛出的是 `MissingRefError`（不是 `SCHEMA_INVALID`），被 `handle()` 的兜底 catch 转成 `INTERNAL_ERROR`（`gateway-core.ts:135-138`）。即 **OpenClaw 的创建对话 / 发消息 / 建附件三条主写路径今天必然返回 INTERNAL_ERROR**。
- Hermes 不受影响：其 `ContractRegistry`（`core.py:404-671`）自带校验器，未把 `conversation-snapshot` 纳入 `schema_definitions`。

### P0-3 Hermes 默认凭据校验允许"任意非空密码"

- `integrations/hermes/open_android_intelligence_gateway/adapter.py:120-134` 的 `LocalCredentialVerifier.verify()` 只判 `username == account_id` 且密码非空。
- `plugin.py:156-158`：宿主未注入 `credential_verifier` 时，**默认就用这个宽松校验器**。
- 叠加 `http.py:208-209` 的"账号必须已存在"检查后，实际效果是：**任何知道账号名的人，任意密码即可换取 access/refresh 会话**（因为账号名同时在 header 与登录体里出现）。这是与"手机端是最终授权者"定位直接冲突的 P0。

### P0-4 Hermes 在 HTTP 边界篡改协商请求，schema 哈希是死控制

- `http.py:144-154`（`handle()`）与 `http.py:270-278`（`_handle_raw()`）在把请求交给 core 之前改写报文：
  - 客户端没给 `schemaHashes` 就注入 `{"core": "sha256:" + "a"*64}`；
  - `features.messages` / `features.attachments` 被过滤为只保留 `chat-v1` / `staged-sha256-v1`。
- 而 `core.py:33` 的 `SHARED_CORE_SCHEMA_HASH` 就是同一个 `sha256:aaa…a` 占位常量，`core.py:2310` 用它做协商比对 —— 与任何真实 schema 集合都不对应。
- 契约要求 `negotiate.request.schemaHashes` 为 **required**，且 `features.messages` 的枚举只有 `["chat-v1"]`、`features.attachments` 只有 `["staged-sha256-v1"]`。真实客户端 `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/negotiation/NegotiationClient.kt:46-62` **既不发送 `schemaHashes`，又请求了 `message-batches-v1`、`screen-selection-v1`**。
- 因此当前不是"客户端合规、服务端校验"，而是**服务端替客户端伪造必需字段、并把超纲请求项静默删除后返回成功**：既掩盖了客户端不合规，也掩盖了契约枚举落后于产品需求（批消息、屏幕圈选）这两处真实缺陷。

### P0-5 Hermes `GET /events` 的 SSE 通道绕过认证与游标语义

- `adapter.py:600-602`：`GET .../events` 一律走 `_handle_sse_stream`，**在任何验签与路由判定之前**返回 200。
- `adapter.py:688-724`：不校验九头、不解析 `cursor` / `Last-Event-ID`、不读 `core.EventStore`，只推送 `_broadcast_sse` 的进程内队列；`_broadcast_sse`（`adapter.py:726-732`）也不按账号/设备分流。
- 契约 §9 要求 cursor 为恢复权威、`Last-Event-ID` 必须与 query cursor 逐字节相等、游标过期返回 `CURSOR_EXPIRED` 并给出 `recoverableResources`。这些语义在 SSE 通道上全部缺失，且 core 里已实现的正式 `events` 分支（`core.py:2563-2577`）被 SSE 处理遮蔽，**永远走不到**。
- 附带：响应头写死 `Access-Control-Allow-Origin: *`（`adapter.py:697`），任何网页都能读取这条未认证的事件流。

### P0-6 OpenClaw 完全没有 SSE，Agent 回复无法到达手机

- `integrations/openclaw/src/` 全目录无 `text/event-stream` / SSE 相关实现（已用 grep 确认）。
- `GET /events` 返回的是 JSON 数组（`gateway-core.ts:242-256` → `{events: [...]}`），与契约 §9 的 SSE 帧格式不符；Agent 侧也没有任何 `conversation.message.delta` / `conversation.message.completed` 的生产者。**OpenClaw 宿主下手机端不可能收到回复。**

### P0-7 OpenClaw 全部敏感数据明文落盘，且缺少设备密钥/会话令牌校验基础

- 无 AEAD：`integrations/openclaw/src/` 中不存在 encrypt/seal/aead/policy 任何实现（grep 确认）。
- 具体明文列：事件负载 `event-store.ts:45`（`JSON.stringify(event.payload)`）、幂等结果 `gateway-core.ts:201`、设备请求参数 `device-request-store.ts:75-83`、附件字节 `attachment-store.ts:82-100`（`writeFileSync(contentPath, bytes, {mode:0o600})`，无封装）。
- 缺表/缺列（对照 Hermes `core.py:829-928`）：无 `device_keys`（无 Ed25519 公钥登记）、`access_sessions` 无 `access_token_hash`（`account-store.ts:36-43`）、无 `uncertain_outcomes` / `negotiations` / `negotiation_bindings`，`device_requests` 无 `requires_foreground_confirmation`。
- `account-store.ts:171` 把 `master_key_ref` 写成 `host-secret:<hash>` 的**假引用**，永不解析到真实密钥。
- 但 `plugin-manifest.json:14,17` 与 `adapter.ts:61,65-69` 对外声明 `zeroRetention: {required:true, providerObjectRetention:"none", bodyEgress:"fail_closed"}`、`rawHeaders:"delegated-to-verified-request-seam"` —— **声明与实现不符**。
- 另：`adapter.ts:67` 把 `ed25519: "not-implemented-in-task-4"` 直接写进交付清单，属把未实现的安全控制固化进产物元数据。

---

## 3. P1：契约/安全/一致性缺陷

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| P1-1 | 宿主兼容门被绕过 | `adapter.py:426-438` | 宿主版本不兼容时不是返回 `HOST_INCOMPATIBLE`，而是把路由服务对象里的 `host_version` 改成 `"1.0.0"`、`host_api` 改成伪造的 `HostApiCompatibility("0.1.0","99.0.0","0"*40)`，随后照常对外服务。契约 §15 要求 host-incompatible 时管理入口只读、外部端点返回 `HOST_INCOMPATIBLE`。 |
| P1-2 | Hermes 以明文 HTTP 监听全网卡 | `adapter.py:116-117,440-451`；`http.py:479-484` | `DEFAULT_HOST = "0.0.0.0"`，aiohttp 直接 `TCPSite` 无 TLS；`create_gateway_exposure` 只在 `listener` 字典里声明 `"protocol":"https"`。契约 §3 要求 `POST /sessions/password` 只能在正式 TLS 上调用。Bearer 令牌与签名（虽不含 body 明文）会以明文过网。 |
| P1-3 | 硬编码默认账号 | `adapter.py:407` | `self._account_id` 默认 `"djbd"`，`send()` 恒写这个账号（`adapter.py:487-498`），与真实会话账号无关。 |
| P1-4 | 协议时间戳格式违约 | `adapter.py:488,559` | 用 `datetime.now(timezone.utc).isoformat()`（微秒 + `+00:00`）写入 `accept_message` 的 `now` 与 SSE `occurredAt`，违反契约 §2 的三位毫秒 `Z`。`_epoch_millis`（`adapter.py:196-205`）解析失败时静默回退当前时间。 |
| P1-5 | CLI 宣告了未实现的操作 | `admin.py:238-241,264-269`、`admin/service.ts:98`、`admin/cli.ts:125-134` | 两端 CLI 都注册 `account delete`，`execute()` 恒返回 `ADMIN_OPERATION_NOT_IMPLEMENTED`。 |
| P1-6 | Hermes CLI 绕过管理服务与本地确认 | `plugin.py:252-344` | `--password` 被解析但从不使用；`--confirm-local` 的 `default=True`（本地确认形同虚设，与 AGENTS.md「账号注册必须携带显式本地确认标志」冲突）；`account delete` 直接 `shutil.rmtree` 删账号目录，绕过 `AdminService`、审计与 `LOCAL_CONFIRMATION_REQUIRED`。 |
| P1-7 | 平台健康状态恒为真 | `plugin.py:203-207` | `_check_deps()` 与 `_is_connected()` 无条件下 `return True`，宿主的连接/依赖检查是死状态。 |
| P1-8 | 存储根依赖进程 CWD | `account_paths.py:61-62`；`openclaw/src/core/account-paths.ts:36-37` | `default_*GatewayRoot()` 用 `Path.cwd()` / `process.cwd()`，换启动目录即"账号集体消失"，并可能产生分叉数据。 |
| P1-9 | 幂等输入哈希非规范化 | `gateway-core.ts:107-110` | 用 `JSON.stringify` 而非 JCS 排序；Hermes 用 `_json(..., sort_keys=True)`（`core.py:690-705`）。同一请求换 key 顺序即 `IDEMPOTENCY_CONFLICT`，且两端哈希不可比。 |
| P1-10 | 身份覆盖检测既漏又误伤 | `gateway-core.ts:145-152` | 正则只匹配 `accountId|deviceId|principalId|pairingGeneration`，漏 `sessionId`/`installationId`/snake_case；且对整个 body 字符串化后正则搜索，**用户消息正文里出现 `"deviceId":` 就会被判为身份覆盖**。 |
| P1-11 | OpenClaw 无附件策略 | `openclaw/src/core/attachment-store.ts` | 不做单件大小、媒体类型、单消息总量校验（Hermes `core.py:54-72,1163-1168,1804`，共享常量 `integrations/shared/adapter.ts:28-34` 为 26 MiB / 52 MiB 等），合约声明的 limits 未落地。 |
| P1-12 | Hermes HTTP 边界双份实现 | `http.py:129-243` vs `250-374` | `handle()`（已验证请求对象）与 `_handle_raw()`（原始请求）各自复刻了一套 pre-auth 分支（negotiate / password / refresh / current），登录逻辑已出现"一处绑定 negotiation、一处不绑定"的漂移风险。 |
| P1-13 | 陈旧清单与构建产物 | `integrations/hermes/plugin-manifest.json`、`integrations/hermes/adapter.ts`、`integrations/hermes/dist/`、`integrations/hermes/agent_life_hermes_gateway.egg-info/` | 清单仍写 `protocolVersion:"mobile-bridge-v1"`、`upstream:"latest-stable"/"fixture"`，与 `plugin.py:27-46` 的真实清单（`gateway-protocol-v2`）矛盾，且仍被 `mvp-contract/tools/mvp-readiness.ts:165` 引用；`adapter.ts` 只是指向 `legacy/integrations/hermes-v1` 的兼容 shim。`integrations/hermes/dist/` 与 `*.egg-info` 未被 `.gitignore` 覆盖（`.gitignore` 仅忽略 `/bridge-runtime/dist/`、`plugins/dist/`）。 |

---

## 4. P2：细节与可维护性

1. 审计脱敏过宽：`audit.py:16-31` 的 `"text"` 标记因子串匹配会命中 `context*`（`"context"` 包含 `"text"`），误删正常审计字段。
2. 错误码 → HTTP 状态映射不全：`http.py:97-105` 除 503/401/413 外一律 400，缺 404/409/410 等（如 `CURSOR_EXPIRED`、`OUTCOME_UNKNOWN`）；OpenClaw `routes.ts:181-187` 同构。
3. 协商状态不持久：Hermes `_pending_negotiations` 是进程内字典（`core.py:2174`），重启后 5 分钟窗口内 `bind_negotiation` 必然失败（`core.py:2442-2464`）。
4. 死分支：`core.py:2586-2587` 的 `_negotiate(account, …)`（会写 `negotiations` 表）在 pre-auth 分支已拦截的情况下不可达。
5. `identity_rotation.py:91-92` 默认 `proof_verifier=None` 时恒抛 `IDENTITY_ROTATION_PROOF_UNVERIFIED`（fail-closed 合理），但因此 `rotate` 在生产默认配置下不可用，且未被任何文档标注。
6. OpenClaw `assertNoIdentityOverride` 对 GET 不生效、对 `Uint8Array` body 直接跳过（`gateway-core.ts:145-152`）。

---

## 5. 既有基线（已在记忆中登记，不计为本次新增）

1. `conversation-snapshot.schema.json → envelope#/$defs/positiveInt` 断链（P0-2 的根因本身）。
2. `sse-event-gateway-notice-valid` 向量失败（`event.schema.json` 校验 value 不过）。
3. Python `ContractRegistry` 不支持跨文档 `$ref`；`command-catalog.schema.json` 的 `catalog` 自相矛盾。
4. `integrations/openclaw` 中 `account-isolation / attachment-lifecycle / backup-and-rotation / device-request-queue / session-service / shared-vectors` 6 个测试文件因同一 Schema 断链在加载期失败。

---

## 6. 建议处置优先级（按依赖顺序，不含执行步骤约束）

1. **先修共享 Schema 断链**：它同时是 OpenClaw 测试全红与写路径全 `INTERNAL_ERROR` 的单一根因，修完才能评估 OpenClaw 的真实缺陷面。
2. **Hermes 边界止血**：去掉协商请求改写（改为按契约返回 `PROTOCOL_INCOMPATIBLE` / 明确降级语义）、让 `POST /sessions/password` 走真正的凭据校验 seam、把 `GET /events` 收回 core 的游标语义并加认证、去掉宿主兼容伪装配。
3. **客户端/契约对齐**：让 Android 端补齐 `schemaHashes`（并换成真实的 schema 集合摘要），同时决定 `message-batches-v1`、`screen-selection-v1` 是进契约枚举还是撤回请求 —— 当前靠服务端洗数据掩盖。
4. **OpenClaw 补齐或明示降级**：认证/协商/SSE/加密落盘/附件策略按契约实现，短期做不到时应在产物清单与宿主面板中如实标注"未实现"，而不是声明 `zeroRetention.required=true`。
5. **清理死状态与陈旧产物**：CLI `delete` 二选一（实现或下线）、`_check_deps/_is_connected` 反映真实状态、存储根改用宿主数据目录、陈旧 `plugin-manifest.json` 与 `legacy` shim 收口、补 `.gitignore`。

---

## 7. 未覆盖

- 未做真机联调：本报告全部结论来自源码核查与自动化测试，未在真机上验证 Hermes 宿主下的完整"登录 → 发消息 → SSE 回复"闭环。
- `integrations/openclaw/src/core/identity-rotation.ts`、`backup-service.ts`、`conversation-port.ts`、`audit-store.ts` 与 `integrations/hermes/open_android_intelligence_gateway/backup.py` 仅做交叉核对，未逐行审查。
- 未评估 `legacy/` 与 `mvp-contract/tools/mvp-readiness.ts` 对上述清单内容的门禁影响。
