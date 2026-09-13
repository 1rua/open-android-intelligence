# Agent 端插件网关修复方案（Hermes / OpenClaw）

- 日期：2026-09-12
- 输入：`docs/superpowers/reviews/2026-09-12-agent-side-gateway-plugins-review.md`（P0 7 / P1 13 / P2 6）
- 对齐计划：`docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md` 的 Task 4（OpenClaw 适配器）、Task 5（Hermes 原生插件）、Task 6（双宿主一致性门禁）
- 权威顺序：总规格 > `docs/contracts/gateway-protocol-v2.md` > `gateway-contract/schemas` + `gateway-contract/vectors` > ADR / `CONTEXT.md` > 现有代码

---

## 1. 门禁现状（2026-09-12 实测，先于一切修复）

Task 6 在 2026-08-29 记录过 GREEN（两宿主 24/24），**今天两条腿都不绿**：

```text
$ ./tools/run-node24 npx tsx gateway-contract/tools/run-openclaw-conformance.ts
MissingRefError: can't resolve reference .../envelope.schema.json#/$defs/positiveInt
  from id .../conversation-snapshot.schema.json        ← 进程直接崩溃，OpenClaw 腿 0 产出

$ python3 gateway-contract/tools/run-hermes-conformance.py
fail  sse-event-gateway-notice-valid  schema.validate_dispatched  ...
hermes-python: 1 vector case(s) failed                 ← Hermes 腿不通过
```

两个红点各自都有明确、可判定的根因：

| 红点 | 根因 | 判定 |
| --- | --- | --- |
| `positiveInt` 断链 | `conversation-snapshot.schema.json` 引用 `envelope.schema.json#/$defs/positiveInt`，而 envelope 的 `$defs` 里没有它（`schema-registry.ts:90` 全量编译 → 整包不可用） | **Schema 缺陷，修法无歧义**：补 `{"type":"integer","minimum":1}`（与 `utcMillis`/`opaqueId` 同风格；语义为 `snapshotRevision` / `revision`） |
| `sse-event-gateway-notice-valid` | `event.schema.json#/$defs/event` 必填 `["correlationId","occurredAt","type","payload"]`，但契约 §9 的事件帧示例与向量值都**没有 `type`**（类型来自 SSE `event:` 行，见 §4.1「eventType 来自已验证 SSE `event:` 行」） | **契约内部冲突，需裁决**（见 D3） |

> 这条门禁是 `gateway-contract/test/cross-host-conformance.test.ts` 的输入，也是 §16「Android、Hermes 和 OpenClaw 必须共同通过语言无关向量」的唯一机读证据，因此它是本方案的第一验收口径，不是可选项。

---

## 1.1 执行进度（2026-09-13）

### W1 门禁复绿 —— 完成

| 动作 | 依据 |
| --- | --- |
| `envelope.schema.json` 补 `positiveInt`（`{"type":"integer","minimum":1}`） | 解除 `conversation-snapshot` 断链；`schemaFor`/`validateGatewayValue` 恢复可用 |
| `event.schema.json` 的 `type` 不再必填，枚举改为 §9 的 V2 事件类型 | 契约自身测试 `schema-registry.test.ts:156` 与 `sse-events.json` 向量都要求「不带 `type` 的 event 合法」 |
| TS 注册表把 `conversation.commandCatalog` / `conversation.mirrorSync` 指向 `conversation.schema.json` | 与 Python 实现、契约向量、`conversation-ui.test.ts` 的期望一致；此前指向 `command-catalog` / `conversation-snapshot` 是跨宿主分歧的根因 |
| 两个 runner 的共享向量集合收敛到契约 §16 枚举的六文件（24 例） | `conversation-ui.json` 的 `schemaName` 不在 §16 的封闭枚举内，改由 `conversation-ui.test.ts` 覆盖 |

证据：`npm run gateway:v2:conformance` → `openclaw-typescript: 24/24 pass`、`hermes-python: 24/24 pass`、跨宿主哈希比对 3 项通过；`integrations/openclaw` 43 passed（10 文件）；`integrations/hermes` 100 passed。

遗留红（与本工作流无关，需单独立项）：

1. `gateway-contract/test/conversation-ui.test.ts > joins members with one U+000A and no trailing LF`。main 的 `joinMessageBatch` 返回 `Uint8Array` 且未做换行规范化，同一测试期望 `"甲\n乙\n丙"`；`.worktrees/android-conversation-ui-refactor` 下有更完整的实现（含 `maxMembers`/`maxBytes`）与配套测试未落地。修法需要先裁决 message-batch 合并语义归属。
2. `plugin-tooling/test/deterministic-package.test.ts > produces identical sha256 for identical inputs`：包本身是确定性的（两次构建哈希一致），但固定哈希常量 `FIXTURE_ALP_SHA256 = 0272109b…` 与当前产物 `73e0b453…` 不符。改名提交 `22fc40d` 把 `.alp` 魔数从 `AGENT-LIFE-PLUGIN-PACKAGE-V1` 改成 `OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1`，该提交重算了 golden 向量却没重算这个常量。修法是重算常量并在提交信息里说明。

全仓 `vitest run` 现状（2026-09-13）：**80 files / 788 tests passed，2 failed**（即上面两条）；本工作开始时是 21 条失败。

### W2 Hermes 认证与凭据 —— 完成

- 新增 `credentials.py`：stdlib scrypt（`scrypt$n$r$p$salt$key` 自描述摘要，`hmac.compare_digest` 比对，依赖列表保持为空）。
- 账号库新增 `account_credentials` 表与 `CredentialStore`（`set_password` / `has_password` / `verify_password`）。
- `AdminService.create_account` 要求口令（缺失返回 `PASSWORD_REQUIRED`）；新增 `delete_account`，按契约 §13 做资源级删除（`GatewayCore.delete_gateway_account`），受同一只读门与本地确认约束。
- `AccountPasswordVerifier` 成为组合默认校验器；`LocalCredentialVerifier` 降级为「必须显式提供的沙箱选项」，不再被自动选中。
- CLI：`--password` 真正生效、`--confirm-local` 必须显式给出（去掉 `default=True`）、`account delete` 不再 `shutil.rmtree` 而是走管理服务；`_check_deps` / `_is_connected` 反映真实可用性。
- 新增 `tests/test_account_credentials.py`（5 例）覆盖：摘要往返与畸形输入、口令必填、本地确认必填、未知账号/无口令账号一律拒绝、删除需确认且真正移除。

### W3 Hermes 边界止血 —— 完成

- 删除协商请求注入与 `features` 过滤（`http.py` 的 `handle` 与 `_handle_raw` 两条路径）。
- 删除 `/agent-life/v2/` 重写（`http.py` 2 处、`adapter.py` 2 处）——签名目标不再被改写成客户端没签过的形状。
- 删除宿主兼容伪装配（原先在 `connect()` 里把路由服务的 `host_version` 改成 `1.0.0`、`host_api` 改成伪造区间）。
- 时间戳统一走 `iso_millis`；`_epoch_millis` 解析失败不再静默回退当前时间。
- 默认监听从 `0.0.0.0` 改为 `127.0.0.1`；去掉硬编码默认账号 `djbd`（未配置账号时 `send()` 返回 `ACCOUNT_NOT_CONFIGURED`）；入站投递改用已验证请求里的账号头，缺失时不臆造。

### W5 协商对齐 —— 服务端与客户端完成，真机联调待做

- 契约 §4 示例改为规范形态（基础能力在 `messages`/`attachments`，增强能力在 `conversationUi`），并写入 core 摘要算法定义。
- Hermes 只声明真实实现的能力：`auth` 收敛为 `password`/`refresh`，`conversationUi` 只回 `agent-command-catalog-v1`。
- 新增 `gateway-contract/src/core-schema-hash.ts` 与 Hermes `ContractRegistry.core_schema_hash`，两端独立算出同一值 `sha256:0a4bcac2ea38d59470dd3e7d73c864c3599126df36b0c31b594fb245bdaf21b5`。
- Android：新增 `SchemaContractHash`（常量 + 从契约目录复算的漂移测试）与 `SchemaContractHashTest`；`NegotiationClient` 改为规范形态、携带真实摘要、只声明已实现能力，并解析响应里的 `conversationUi`。
- **未完成**：真机协商联调（需 `R52X909R9QT`）；`screen-selection-v1` 的归属（客户端已不再请求，未来作为独立契约变更处理）。

### W7 工程卫生 —— 完成

- 已完成：`.gitignore` 补 `integrations/hermes/dist/`、`*.egg-info/`、`.pytest_cache/`，`bridge-runtime` 系列忽略规则跟随迁移改为 `/legacy/bridge-runtime/…`；审计脱敏不再误伤 `context*`（同时保留对 `messageText`/`bodyBytes` 的拒绝）并补回归用例；错误码→HTTP 状态映射补全（404/406/409/410/413/429/503）。
- 陈旧清单与 legacy shim 收口：删除 `integrations/hermes/adapter.ts` 兼容 shim 与陈旧的 `integrations/hermes/plugin-manifest.json`（生产 Hermes 是 Python 插件，权威清单是 `plugin.py` 的 `HERMES_PLUGIN_MANIFEST`，不再保留第二份会漂移的 JSON）；夹具本体与测试迁至 `legacy/integrations/hermes-v1/{adapter.test.ts,plugin-manifest.json}`，夹具清单显式标注 `_status: legacy-fixture`；`integrations/shared/{notification,sms}-contract.test.ts` 改指 legacy 适配器；`mvp-contract/tools/mvp-readiness.ts` 的 WP-06/WP-07 源列表同步到迁移后位置（`auditPackets` 11 包 0 缺失）。
- 存储根去 CWD：Hermes `default_hermes_gateway_root()` 改为「`OPEN_ANDROID_INTELLIGENCE_GATEWAY_ROOT` → Hermes home → 用户 home」，`plugin.py` 与核心共用 `GATEWAY_DIRECTORY_NAME`；OpenClaw `defaultOpenClawGatewayRoot()` 改为「环境变量 → 显式 `STORAGE_ROOT_REQUIRED` 失败」，核心惰性解析（无根时共享向量仍可运行，任何落盘调用显式报错），`accountExistsIn` 不再把缺根吞成「账号不存在」；删除暗示零配置默认值的便捷导出 `openGatewayAccount`。
- 顺带工程卫生：`integrations/tsconfig.json` 补 `"types": ["node"]` 并把 `../legacy/integrations/**` 纳入检查，`npm run typecheck` 从 109 个错误（缺 Node 类型 + shim 断链的连锁反应）降至 **0**；`docs/mvp/mvp-dependency-lock.md` 修复 4 行自完整性失配（证据路径跟随迁移 + 按工具规范重算行哈希）→ `check-lock` PASS (7 rows)。
- 证据：Hermes `pytest -q` → **111 passed**（新增 `test_account_paths.py`：配置根优先生效、默认根不随工作目录、跨目录稳定）；OpenClaw `vitest run` → **51 passed**（新增 `test/storage-root.test.ts`：无根显式失败且不在 CWD 产生目录、配置根生效、宿主数据目录优先于环境变量与配置）；全仓 `vitest run` → 82 通过 / 2 失败（既知遗留红灯：conversation-ui `joinMessageBatch` 期望值、plugin-tooling 陈旧 FIXTURE_ALP_SHA256），迁移后的 `legacy/integrations/hermes-v1/adapter.test.ts` 4 例仍被根配置发现并通过。

### W4 SSE 端到端 —— 完成（真机联调待做）

服务端（Hermes）：

- `http.py` 新增 `GatewayHttpRoute.event_backlog()`：事件流是**普通已认证请求**，验签、游标解析、`Last-Event-ID` 比对与过期判定全部在这里完成，传输层不再自己做安全判断。
- `adapter.py`：`GET /events` 仅在 `Accept: text/event-stream` 时进入流式分支，否则走同一路由返回 JSON（两条路径共用 core 语义）；帧格式改为契约 §9（`id:` + `event:` + 只含 `correlationId`/`occurredAt`/`payload` 的 `data:`），心跳是注释行且不产生事件 ID；响应头去掉了 `Access-Control-Allow-Origin: *`。
- 订阅结构从"全局队列集合"改为**按账号分组**，`_broadcast_sse(account_id, frame)` 只投递给该账号的流。
- `stream_delta` / `complete_message` 改为**先落库再推送**（`_publish_event` → `EventStore.append` → 广播持久化帧）：SSE 的 `id:` 因此成为真实游标，断线重连由事件库重放，不再依赖进程内存。

客户端（Android）：

- `GatewayHttpClient.events()` 现在走与其它请求相同的签名路径（抽出 `signedInput` / `signatureOf` 复用），携带九头与 `Accept: text/event-stream`，且不带 `Idempotency-Key`（GET 不允许出现）；签名覆盖**含 cursor query 的 canonical target**。
- 本地游标若不是 wire ID 字母表内的值则不发送（避免产生非 canonical target），退化为从保留窗口重放——事件是幂等 upsert。

证据：Hermes `pytest -q` → **108 passed**（新增 `test_event_stream.py` 6 例 + `test_event_stream_transport.py` 2 例，后者用真实 aiohttp 起服务验证「先重放积压 → 再实时投递新事件」「跨账号不串流」「无通配 CORS」「无验证器时 HTTP 401」）；Android `:gateway-client:testDebugUnitTest` 新增 `GatewayEventStreamTest`（4 例：九头与无 Idempotency-Key、签名预像含 cursor 与请求身份、完整帧推进游标、非法游标不发送）并通过；全仓 `vitest run` 与一致性门禁保持绿。

**未完成**：真机 `R52X909R9QT` 上的断线续传验证（断开 → 游标续传不丢事件；游标过期 → 重建快照后用响应游标恢复）。

### W6 OpenClaw 能力补齐与清单诚实降级 —— 完成（SSE 与加密落盘如实标注为未实现）

按 D5 的建议执行：能补齐的补齐，补不了的明确声明，不再让清单替实现说话。

补齐（与 Hermes 对齐）：

- **协商**：core 新增认证前分支 `POST /negotiate`，用 `gateway-contract/src/core-schema-hash.ts` 的真实摘要校验 `schemaHashes.core`，按契约 §4 的能力分层返回交集（基础能力在 `messages`/`attachments`，增强能力在 `conversationUi`），只声明已实现的能力（`auth` 收敛为 `password`/`refresh`，`conversationUi` 只回 `agent-command-catalog-v1`）。
- **账号与会话**：新增 `credentials.ts`（stdlib `scryptSync` + `timingSafeEqual`）与 `credential-store.ts`；`SessionService` 改为校验账号口令摘要（无摘要即拒），登录时登记 `device_keys` 公钥、写入 `access_sessions.access_token_hash`，并新增 `resolveSession()` 作为宿主 Ed25519 验签器所需的事实来源；新增 `revokeSession` / `revokeRefreshCredentials`。
- **会话端点与读取**：`POST /sessions/password`（必须引用本机签发的 `negotiationId`，且账号必须已注册——登录不再创建账号）、`POST /sessions/refresh`、`DELETE /sessions/current`、`GET /commands`、`GET /conversations`、`GET /conversations/{id}`；`routes.ts` 用 `PRE_AUTH_PATHS` 区分认证前端点（不走验证器），并补全错误码→HTTP 状态映射。
- **附件策略**：新增 `attachment-policy.ts`（单件 26 MiB / 单消息 52 MiB / 允许媒体类型 / TTL 3600 / 事件保留 86400 / 时钟偏差 120，与 Hermes 同值），创建与消息引用都会强制校验。
- **幂等与身份**：幂等输入哈希从 `JSON.stringify` 改为 JCS（`canonicalize`），与 Hermes 一致；身份覆盖检测改为**结构化**递归查键（原先的正则会漏 snake_case，还会把正文里含 `"deviceId":` 的用户消息误判为身份覆盖）。
- **管理面**：`createAccount` 要求口令（`PASSWORD_REQUIRED`），新增 `deleteAccount`（资源级删除整个账号目录，受只读与本地确认约束），CLI 增加 `--password <password>` 且解析器支持显式 `--confirm-local`。

诚实降级（清单不再声明未实现的控制）：

- `plugin-manifest.json` 与 `OPENCLAW_PLUGIN_MANIFEST` 新增逐项 `capabilities`，并删除 `zeroRetention.required=true` / `bodyEgress:"fail_closed"` / `providerObjectRetention:"none"` 等与实现不符的声明；`securityBoundary` 改为 `ed25519:"host-supplied-verifier"`、`encryptionAtRest:"not-implemented"`、`zeroRetention:"not-implemented"`；`capabilitySchemaHash` 从字面量 `"gateway-protocol-v2"` 改为真实 Schema 摘要。
- 明确标注为未实现：`sse`（`GET /events` 目前返回 JSON，不是 SSE 流）、`encryptionAtRest`（附件字节、事件负载、设备请求参数仍是明文落盘）、`messageBatches`、`generationCancel`、`mirrorSync`、`invitationPairing`、`deviceKeySessions`。

证据：`integrations/openclaw` vitest **48 passed（11 文件）**，含新增 `test/gateway-capabilities.test.ts` 5 例（真实摘要协商与被拒、口令登录与 `resolveSession`、附件策略在线上生效、身份覆盖按结构判定而正文含 `"deviceId":` 仍可发送、清单声明与注册路由逐条对齐）；`tsc --noEmit`（含测试文件）通过；一致性门禁两宿主 24/24；全仓 `vitest run` **793 passed / 2 failed**（仍是那两条既有红灯）。

**仍未完成**：OpenClaw 的 SSE 与加密落盘——两项都需要新的宿主接缝（流式响应与 AEAD 提供者），属独立设计决策，当前由清单如实标注为未实现。因此 **OpenClaw 宿主下手机端仍收不到 Agent 回复**。

---

## 2. 需要先裁决的 5 个点

### D1 — `features` 的权威版本与键位（阻塞 W5、W1 之外的协商修复）

现状是**三方互不一致**：

| 来源 | messages | attachments | 其它 |
| --- | --- | --- | --- |
| 契约 §4 示例（`gateway-protocol-v2.md:92-116`） | `["chat-v1","message-batches-v1"]`，响应 `"message-batches-v1"` | `["staged-sha256-v1","screen-selection-v1"]`，响应 `"screen-selection-v1"` | 无 `conversationUi` |
| `negotiate.schema.json` | 枚举仅 `["chat-v1"]`，响应 `const "chat-v1"` | 枚举仅 `["staged-sha256-v1"]`，响应 `const` | 有 `conversationUi` 数组（含 `message-batches-v1`、`newline-v1`、`generation-cancel-v1`、`conversation-mirror-v1`、`attachment-status-v1`、`agent-command-catalog-v1`） |
| Android `NegotiationClient.kt:55-61` | `["chat-v1","message-batches-v1"]` | `["staged-sha256-v1","screen-selection-v1"]` | 无 `conversationUi` |

**建议**：以 Schema 为准把「基础会话能力」与「会话级增强能力」分开——`messages`/`attachments` 保持基础契约（`chat-v1`、`staged-sha256-v1`），`message-batches-v1` 等增强项统一走 `conversationUi`；同时给 `screen-selection-v1` 一个明确归属（新增到 `conversationUi` 枚举，或从客户端撤回）。随后同步修订契约 §4 示例，使文档、Schema、客户端三者一致。
**影响面**：`gateway-contract/schemas/negotiate.schema.json`、契约 §4、`NegotiationClient.kt`、双宿主协商实现、协商向量。

### D2 — `schemaHashes.core` 的定义（阻塞 W5）

契约只在 §4 示例写了 `"core": "sha256:..."`，**没有任何计算规则**；Hermes 目前用占位常量 `sha256:aaa…a`（`core.py:33`），Android 客户端根本不发送这个字段。结果是这项「Schema 一致性」控制是空的。

**建议**：在契约 §4 里补一条定义，并让 Task 6 的两个 runner 都按同一定义重算，例如「core 摘要 = `sha256:JCS(sorted([{key, schemaSha256} …]))`」，其中每个 entry 的 `schemaSha256` 沿用 §4.1 已有的 `sha256:JCS(schema)` 规则。定义落地后客户端与服务端都必须使用真实值，占位常量与边界注入同时删除。

### D3 — `event.schema.json#/$defs/event` 的 `type` 字段（阻塞 W1）

**建议**：按 §9 修正 Schema（`type` 不再是必填、或者从 event 外壳中移除），保持向量不动；同时核对 `additionalProperties` 与 Hermes `EventStore._map` 实际产出的字段（现为 `eventId/eventType/correlationId/occurredAt/payload/expiresAt`），把「内部存储信封」与「SSE `data:` 帧」两个形状在契约里写清楚：帧 = `{correlationId, occurredAt, payload}` + `event:` 行携带类型。

### D4 — Hermes 宿主 API 真实版本区间（阻塞 W3 的宿主兼容修复）

`HERMES_HOST_API = HostApiCompatibility("unverified","unverified","")`（`admin.py:36`）使 `is_host_api_compatible` 恒 False → 管理入口恒只读、外部端点恒 `HOST_INCOMPATIBLE`。`adapter.py:426-438` 的伪装配正是为了绕过它才出现的。
**建议**：登记真实的 Hermes 宿主 API 版本区间与 `verifiedCommit`，通过插件配置注入；默认保持 fail-closed。**若短期拿不到真实区间**，则本次修复只允许「提供显式配置入口 + 默认拒绝」，不得恢复任何形式的伪造放行——这会让当前真机 E2E 需要通过配置显式放行，需要确认。

### D5 — OpenClaw 本期是补齐还是诚实降级（决定 W6 范围）

OpenClaw 缺协商/会话/SSE/AEAD/附件策略（见审查报告 P0-1/2/6/7）。**建议**：本期至少补齐「协商 + 密码登录 + SSE + 加密落盘 + 附件策略」并撤掉清单里的 false claim；其余（设备密钥、邀请配对、镜像同步）明确登记为未实现，不得在 manifest / 宿主面板声明。

---

## 3. 修复工作项

### W1 — 门禁复绿（前置，无依赖）

- **目标**：`npm run gateway:v2:conformance` 三条腿全绿，`integrations/openclaw` 与 `integrations/hermes` 测试回到可解释状态。
- **内容**
  1. 补 `envelope.schema.json#/$defs/positiveInt`。
  2. 按 D3 结论修 `event.schema.json` 与 Hermes `EventStore` 的形状对齐（含 `additionalProperties` 核对）。
  3. 重跑门禁并**登记新基线**（哪些用例、多少条、失败原因），替换记忆中的旧基线描述。
- **完成判据**
  ```bash
  npm run gateway:v2:conformance                      # 两 runner + 跨宿主哈希比对全绿
  cd integrations/openclaw && ../../tools/run-node24 npx vitest run
  cd integrations/hermes && /tmp/oai-venv/bin/python -m pytest tests -q
  ```
- **风险**：改 Schema 会牵动 golden 向量内嵌的 `schemaSha256`（`dispatched-schema-fixtures.json` 的 catalog digest 与 binding 必须重算，规则见 §4.1 与仓库既有做法）。

### W2 — Hermes 认证与凭据（P0-3、P1-5/6/7）

- **目标**：密码登录成为真实认证，而不是「账号存在即可登录」。
- **内容**：账号级口令散列（建议 stdlib `hashlib.scrypt`，`pyproject.toml` 的 `dependencies = []` 不应被打破）；`AdminService.create_account` 接受口令并写入账号库；CLI 的 `--password` 真正生效；默认凭据校验器改为读库校验，**宿主未注入可信 verifier 时不再回退到宽松实现**；`--confirm-local` 必须显式给出（去掉 `default=True`）；`account delete` 二选一（走 AdminService + 审计 + 本地确认实现，或从 CLI 下线）；`_check_deps` / `_is_connected` 返回真实状态。
- **完成判据**：新增 pytest 覆盖——错误口令拒绝、未知账号拒绝、未注入 verifier 时拒绝、缺本地确认时拒绝、删除账号产生审计且不绕过服务层。
- **依赖**：无（可与 W1 并行，但合入前需 W1 的 locale/venv 前置说明）。

### W3 — Hermes 边界止血（P0-4、P1-1/2/3/4/12）

- **目标**：HTTP 边界只做「校验 → 通过 / 拒绝」，不再改写客户端报文，不再伪造宿主状态。
- **内容**：删除协商请求注入与 features 过滤（改为按契约校验，失败返回 `PROTOCOL_INCOMPATIBLE`，或按 D1 的降级语义显式声明交集）；删除 `/agent-life/v2/` 兼容重写（ADR 0038 不允许协议兼容层）；删除 `adapter.py:426-438` 的宿主兼容伪装配（接 D4 的配置入口）；时间戳统一走 `iso_millis`，`_epoch_millis` 不再静默回退当前时间；默认监听改 loopback（或明确要求前置 TLS 终止），去掉硬编码默认账号 `"djbd"`；把 `handle` / `_handle_raw` 两份 pre-auth 逻辑收敛为单一决策点。
- **完成判据**：新增 pytest——`/agent-life/v2/*` 必须被拒；协商请求缺 `schemaHashes` / 含超纲 feature 时**返回明确错误而非被服务端补齐**；宿主不兼容时返回 `HOST_INCOMPATIBLE` 且管理入口只读；时间戳格式为三位毫秒 `Z`。
- **依赖**：D4。

### W4 — SSE 端到端（P0-5、P0-6；跨 Android + Hermes）

- **目标**：`GET /events` 成为一条**认证的、游标权威的**事件流。
- **内容（服务端）**：`GET /events` 走验签（九头 + 含 query 的 canonical target）后进入 core 的 `EventStore.read_after`；SSE 帧按 §9（`id:` / `event:` / `data:`，心跳为注释行且不产生 ID）；`Last-Event-ID` 必须逐字节等于 query cursor，否则 `CURSOR_CONFLICT`；游标过期返回 `CURSOR_EXPIRED` 与 `recoverableResources`；按账号/设备隔离推送；删除 `Access-Control-Allow-Origin: *`。
- **内容（客户端）**：`GatewayHttpClient.events()` 目前**只带 `Accept`，不带九头与签名**（`HttpsGatewayTransport.eventStream` 直连，`open()` 也不签名），需改为签名请求；`SseParser` 已按完整帧推进游标，保持不变。
- **完成判据**：真机连通后验证——断线重连从游标续传不丢事件、游标过期走「重建快照 → 用响应新游标恢复」、未签名请求返回 401、跨账号不串流。
- **依赖**：W1（事件 Schema 形状定稿）、D1/D2（协商决定事件与 features）。

### W5 — 协商能力对齐（P0-4 的契约侧）

- **目标**：文档、Schema、Android、双宿主对「协商出什么」给同一个答案。
- **内容**：按 D1 统一 features 键位与枚举；按 D2 定义并实现 `schemaHashes.core`；Android 补齐 `schemaHashes`（并使用真实摘要）；双宿主各自实现同一「交集 + 降级」算法，且**边界不得改写请求**；补协商向量覆盖「未知 feature 必须显式降级/拒绝」。
- **完成判据**：协商向量在双宿主同结果；真机协商成功且响应中的能力与实际实现一致（不得声明未实现的能力）。
- **依赖**：D1、D2、W1。

### W6 — OpenClaw 补齐或诚实降级（P0-1、P0-2 后果、P0-6、P0-7、P1-9/10/11）

- **目标**：OpenClaw 不再是一个「能编译但连不上」的宿主实现。
- **内容（按 D5 结论取其一）**
  - 补齐：`POST /negotiate`、`POST /sessions/password`、`POST /sessions/refresh`、`DELETE /sessions/current`、`GET /commands` 与对话查询路由；`GET /events` 的 SSE；`device_keys` + `access_sessions.access_token_hash`；AEAD 落盘（事件、幂等结果、设备请求参数、附件字节）；附件大小/媒体类型/总量策略（复用 `integrations/shared/adapter.ts:28-34` 的共享常量）；幂等输入哈希改 JCS（与 Hermes 一致）；身份覆盖检测改为结构化检查（不再正则扫 JSON 串，避免正文含 `"deviceId":` 被误拒）。
  - 降级：manifest 与宿主面板如实标注未实现项，撤掉 `zeroRetention.required=true` / `bodyEgress:"fail_closed"` 等 false claim，并删除 `securityBoundary.ed25519:"not-implemented-in-task-4"` 这类把未实现控制写进产物的表述。
- **完成判据**：OpenClaw 自身测试与 Task 6 门禁全绿；产物清单声明与实现逐条可核对。
- **依赖**：W1、D5。

### W7 — 工程卫生（P1-8/13、P2）

- **内容**：两端 `default*GatewayRoot()` 不再以 `process.cwd()` 作为持久化根（改宿主数据目录，或显式配置 + 缺省报错）；`.gitignore` 补 `integrations/hermes/dist/`、`*.egg-info/`；陈旧 `integrations/hermes/plugin-manifest.json`（仍写 `mobile-bridge-v1`）与 `integrations/hermes/adapter.ts` 按 Task 5 要求收口到 `legacy/`（注意 `mvp-contract/tools/mvp-readiness.ts:165` 仍引用该清单，需同步）；审计脱敏修正 `"text"` 子串误伤 `context*`；错误码 → HTTP 状态映射补全（404/409/410 等）。
- **完成判据**：`mvp-readiness` 与相关门禁仍绿；`git status` 不再出现未忽略的构建产物。

---

## 4. 顺序与里程碑

```text
M1  W1                ✅ 门禁复绿（两宿主 24/24，唯一前置）
M2  W2 + W3           ✅ Hermes 可安全对外暴露（认证真实 + 边界不再伪造）
M3  W4 + W5           ✅ SSE 与协商闭环（服务端与客户端完成，真机联调待做）
M4  W6                ✅ 双宿主等价（SSE 与加密落盘如实标注未实现）
M5  W7                ✅ 工程卫生与产物收口
```

- W1 与 W2 可并行；W3 依赖 D4；W4/W5 依赖 D1/D2 与 W1；W6 依赖 D5。
- 每个里程碑以第 5 节的命令作为「完成」的唯一机读证据，不以色块描述或口头结论代替。

---

## 5. 验收口径

```bash
# 契约与跨宿主一致性（最高优先级）
npm run gateway:v2:conformance

# 两端插件测试
cd integrations/openclaw && ../../tools/run-node24 npx vitest run
cd integrations/hermes   && /tmp/oai-venv/bin/python -m pytest tests -q

# Android 编译（口径固定为 app 模块）
export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
export ANDROID_HOME=$PWD/.toolchains/android-sdk
export GRADLE_USER_HOME=$PWD/.toolchains/gradle-home
export ANDROID_USER_HOME=$PWD/.toolchains/android-user-home
./gradlew :app:assembleDebug

# 真机（涉及 Keystore / SSE / 全链路）
adb devices && ./gradlew :app:connectedDebugAndroidTest
```

真机 E2E 至少覆盖：登录（含错误口令被拒）→ 建会话 → 发消息 → SSE 收到 `conversation.message.completed` → 断线重连游标续传 → 游标过期后重建 → 附件三步上传与 ACK。

---

## 6. 回归守则（修复过程中不得重新引入的反模式）

1. 不得在 HTTP 边界**改写**客户端报文；只允许「校验通过」或「按契约拒绝/显式降级」。
2. 不得伪造 `schemaHashes`、宿主兼容区间、宿主健康状态或凭据校验结果。
3. 产物清单（manifest）不得声明未实现的安全控制或能力。
4. 不得以 `process.cwd()` 作为持久化根；不得用静默回退替代解析失败（时间戳、状态、游标）。
5. 不得让同一份协议逻辑在同一个文件里存在两份实现（`handle` / `_handle_raw` 类分叉）。

---

## 7. 不在本次范围

- `docs/mvp/` 下的 MVP 产物与 `mvp-contract/tools/mvp-readiness.ts` 的门禁口径调整（仅在 W7 同步引用路径时触碰）。
- `legacy/` 与旧 Bridge 的任何行为变更。
- Android 端与本次修复无关的 UI/动效收敛（见 UI 设计规格与既有重构计划）。
- 配对邀请（`/pairings/exchange`）、设备密钥会话（`/sessions/device`）、历史媒体三接口的**新增**实现——除非 D5 判定 OpenClaw 补齐时必须一并覆盖。

---

## 8. 风险与回滚

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| 修 Schema 致 golden 向量 digest 失配 | Task 6 门禁报 `CONFORMANCE_ARTIFACTS_STALE` 或哈希不一致 | 按 §4.1 规则重算 catalog/binding digest，产物目录可重新生成（已在 `.gitignore`） |
| D4 短期拿不到真实宿主区间 | 真机 E2E 会被 `HOST_INCOMPATIBLE` 挡住 | 用显式配置放行并记录在案，禁止恢复伪造装配 |
| W4 客户端签名改动牵动 SSE 连接生命周期 | 可能引入重连风暴或游标倒退 | 保持「仅完整帧推进游标」的既有约定，补断线重连测试后再上真机 |
| OpenClaw 补齐工作量超预期 | 里程碑 M4 延期 | 按 D5 走降级分支：先保证清单诚实 + 门禁绿，再排期补齐 |

回滚策略：所有变更按工作项独立提交（中文提交说明，格式 `<类型>: <简要描述>`），Schema 与协商相关改动优先以「先加约束、后改实现」的小步提交，任何一步导致门禁变红即回退该步而非整体回滚。
