# Open Android Intelligence 模块化插件架构迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 Tailscale 默认 Android + 独立 Bridge 架构迁移为 HTTP/SSE 核心、可安装设备插件和 Hermes/OpenClaw 宿主内 Gateway。

**Architecture:** 先建立语言无关 Gateway Protocol v2 与插件包契约向量，再分别实现 TypeScript OpenClaw Core、Python Hermes Core 和 Kotlin Android 平台内核。现有通知、短信、通话记录与 tsnet 代码只作为迁移来源，所有切换都在新路径通过测试后发生。

**Tech Stack:** Node.js `>=24.18.0 <25`、TypeScript `7.0.2`、Vitest `4.1.10`、Python 3.12+、pytest、SQLite、Android Gradle Plugin `8.9.2`、Kotlin `2.1.20`、Java 17、compile/target SDK 35、min SDK 34、Chicory `1.7.5`、Rust `1.88.0` + `wasm32-unknown-unknown`、Go/tsnet 现有锁定工具链。

**Spec:** `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`

## Global Constraints

- `docs/contracts/gateway-protocol-v2.md` 是网络协议唯一权威来源。
- `docs/contracts/device-plugin-package-v1.md` 是 `.alp` 产物唯一权威来源。
- Android 主导航只包含账号/Gateway、对话和附件；平台内核管理入口位于设置。
- 直接 HTTPS + SSE 是默认路径；Tailscale 默认不安装、不启用。
- 受保护插件只能使用 `open_android_intelligence_kernel_v1`，不得获得 WASI、原始 socket 或真实文件路径。
- 一个 Gateway 账号使用独立 SQLite、主密钥、附件、队列和审计目录。
- Bridge Protocol v1 的密钥、队列和数据库身份不迁移，设备必须重新配对。
- 保留当前未提交 tsnet/P0t 文件和证据；执行时使用独立 worktree，不在当前脏工作树直接开发。
- 每个任务只提交自身文件，提交信息使用中文；任何文件移除都移入 `/tmp/Open Android Intelligence-trash/` 或仓库 `legacy/`，不得永久删除。

---

## 目标文件结构

```text
gateway-contract/                 # JSON Schema、黄金向量、跨语言一致性输入
integrations/openclaw/            # OpenClaw TS Gateway Core + 宿主适配器
integrations/hermes/              # Hermes Python Gateway Core + 宿主适配器
apps/android/gateway-client/      # HTTPS、SSE、账号会话、附件
apps/android/platform-kernel/     # 身份、授权、审计、插件生命周期
apps/android/plugin-package/      # .alp 解析、签名、更新事务
apps/android/plugin-runtime-wasm/ # Chicory adapter + kernel ABI
apps/android/plugin-ui/           # 声明式 UI 验证与渲染模型
apps/android/companion-bridge/    # Companion 身份和单用途 IPC token
apps/android/plugins/             # 官方参考插件源码与构建输入
apps/android/tailscale-companion/ # 独立可选 APK
legacy/bridge-runtime/            # 切换完成后的冻结旧实现
```

`bridge-contract/`、`artifact-contract/`、`bridge-runtime/` 和现有 Android collector 在迁移阶段保持可测试；只有对应 v2 替代路径通过验收后才停止被 App/发布产物引用。

### Task 1: 建立 Gateway Protocol v2 Schema 包

**Files:**
- Create: `gateway-contract/package.json`
- Create: `gateway-contract/tsconfig.json`
- Create: `gateway-contract/src/schema-registry.ts`
- Create: `gateway-contract/schemas/envelope.schema.json`
- Create: `gateway-contract/schemas/negotiate.schema.json`
- Create: `gateway-contract/schemas/session.schema.json`
- Create: `gateway-contract/schemas/conversation.schema.json`
- Create: `gateway-contract/schemas/attachment.schema.json`
- Create: `gateway-contract/schemas/event.schema.json`
- Create: `gateway-contract/schemas/device-request.schema.json`
- Test: `gateway-contract/test/schema-registry.test.ts`

**Interfaces:**
- Produces: `schemaFor(name: GatewaySchemaName): object`
- Produces: `validateGatewayValue(name, value): { ok: true } | { ok: false; errors: readonly string[] }`

- [ ] **Step 1: 写拒绝未知字段和重复身份字段的失败测试**

```ts
it("rejects unknown and caller-supplied identity fields", () => {
  const value = { clientMessageId: "m1", text: "hi", attachments: [], accountId: "attacker" };
  expect(validateGatewayValue("message.create", value)).toMatchObject({ ok: false });
});
```

- [ ] **Step 2: 运行测试并确认红灯**

Run: `npm --prefix gateway-contract test -- schema-registry.test.ts`

Expected: FAIL，原因是 `schemaFor` / `validateGatewayValue` 尚不存在。

- [ ] **Step 3: 添加严格 Schema registry**

```ts
export type GatewaySchemaName =
  | "negotiate.request" | "negotiate.response"
  | "session.password" | "session.refresh" | "session.device"
  | "conversation.create" | "message.create"
  | "attachment.create" | "event" | "device.request";

export const validateGatewayValue = (name: GatewaySchemaName, value: unknown): ValidationResult => {
  const validate = validators.get(name);
  if (validate === undefined) throw new Error(`SCHEMA_NOT_REGISTERED:${name}`);
  return validate(value) ? { ok: true } : { ok: false, errors: normalizeAjvErrors(validate.errors) };
};
```

每个对象 Schema 设置 `additionalProperties: false`；认证身份只存在于验证后的请求上下文，不出现在业务请求 Schema。

- [ ] **Step 4: 运行 Schema 与类型检查**

Run: `npm --prefix gateway-contract test`

Expected: PASS，恶意身份覆盖、未知安全字段、非法 ID/时间/摘要向量全部拒绝。

Run: `npm --prefix gateway-contract run typecheck`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add gateway-contract
git commit -m "新增: 建立 Gateway Protocol v2 严格 Schema"
```

### Task 2: 建立签名、状态机和黄金向量

**Files:**
- Create: `gateway-contract/src/request-signature.ts`
- Create: `gateway-contract/src/state-machines.ts`
- Create: `gateway-contract/vectors/request-signatures.json`
- Create: `gateway-contract/vectors/vector-set-1.0.0.schema.json`
- Create: `gateway-contract/vectors/protocol-negotiation.json`
- Create: `gateway-contract/vectors/auth-sessions.json`
- Create: `gateway-contract/vectors/attachments.json`
- Create: `gateway-contract/vectors/sse-events.json`
- Create: `gateway-contract/vectors/device-requests.json`
- Test: `gateway-contract/test/request-signature.test.ts`
- Test: `gateway-contract/test/state-machines.test.ts`
- Test: `gateway-contract/test/golden-vectors.test.ts`

**Interfaces:**
- Requires: `2026-08-27-gateway-protocol-v2-task2-correction.md` 中“本补充计划 Task 2”新建并完成不可变 catalog、`VerifiedSchemaBindingSet`、dispatched validator，以及 `gateway-contract/vectors/dispatched-schema-fixtures-1.0.0.schema.json` / `gateway-contract/vectors/dispatched-schema-fixtures.json`
- Consumes: `schemaFor` / `validateGatewayValue` outer-only validator、`VerifiedSchemaBindingSet`、`GatewayDispatchedValidator` 与唯一共享 binding set `gateway-core-fixtures-v1`
- Produces: `canonicalRequestTarget(target: string): string`
- Produces: `canonicalRequestSignatureInput(input: SignedRequestInput): Uint8Array`
- Produces: `nextAttachmentState(current, event): AttachmentState`
- Produces: `nextDeviceRequestState(current, event): DeviceRequestState`
- Produces: `maximumDeviceRequestQueueSeconds(risk): 86400 | 900 | 0`

- [ ] **Step 1: 写协议文档中的逐字节签名、target 与动态分派向量测试**

```ts
expect(Buffer.from(canonicalRequestSignatureInput(vector.input)).toString("hex"))
  .toBe(vector.expected.value.preimageHex);
```

测试必须覆盖区分大小写的 method、wire ID、固定毫秒 UTC、canonical 16-byte nonce、exact body bytes、10 字段/9 LF/末尾无 LF，以及 canonical origin-form target 的拒绝和排序边界。SSE/device 动态 case 的 `fixtureBindingSetId` 必须恒等于 `gateway-core-fixtures-v1`，runner 必须先验证并读取共享 `dispatched-schema-fixtures.json`、重算四个规范 JCS digest，再构造 `GatewayDispatchedValidator`；禁止内联或本地替换 Schema/digest/binding，请求仍不能携带 Schema、resolver 或 `ValidateFunction`。

- [ ] **Step 2: 运行并确认签名向量红灯**

Run: `npm --prefix gateway-contract test -- request-signature.test.ts`

Expected: FAIL，canonicalizer 尚不存在。

- [ ] **Step 3: 实现唯一 canonicalizer 和有限状态机**

```ts
export const canonicalRequestSignatureInput = (v: SignedRequestInput): Uint8Array => {
  assertExactSignedRequestFields(v);
  const target = canonicalRequestTarget(v.target);
  if (target !== v.target) throw new Error("NON_CANONICAL_TARGET");
  return textEncoder.encode([
    "OPEN-ANDROID-INTELLIGENCE-REQUEST-V2", v.method, target, v.accountId, v.deviceId,
    v.sessionId, v.requestId, v.timestamp, v.nonce, sha256Hex(v.body),
  ].join("\n"));
};
```

`body` 必须是未经 parse/stringify 的 `Uint8Array`。状态机按协议完整矩阵实现纯 reducer，对未列出的状态/事件组合抛内部 `INVALID_STATE_TRANSITION`；HTTP 不公开该 code，不提供普通事件自循环或“尽力恢复”分支。

- [ ] **Step 4: 建立闭合六文件格式并运行全部向量**

六文件统一使用 `formatVersion: "1.0.0"`、`protocolVersion: "2.0"`、契约精确枚举的 `vectorSet` 和 `cases`，并全部通过 `vector-set-1.0.0.schema.json`；顶层及 case 拒绝未知字段，case 只含 `id`、`operation`、`input`、`expected`。`gateway-contract/test/golden-vectors.test.ts` 必须证明文件名/vectorSet/operation 归属、ID 全局唯一、每文件同时包含 value/error、每个 operation 的闭合 input/expected union、二进制/时间格式规范且每个 operation 都有生产消费者；`schema.validate_dispatched` 还必须证明只引用共享 `gateway-core-fixtures-v1`，不得只检查 JSON 存在或 grep 文本。

Run: `npm --prefix gateway-contract test`

Expected: PASS；每个向量都同时包含成功与失败样例。

- [ ] **Step 5: 提交**

```bash
git add gateway-contract
git commit -m "新增: 固化 v2 签名与状态机向量"
```

### Task 3: 实现 OpenClaw 账号隔离 Gateway Core

**Files:**
- Create: `integrations/openclaw/package.json`
- Create: `integrations/openclaw/src/core/account-paths.ts`
- Create: `integrations/openclaw/src/core/account-store.ts`
- Create: `integrations/openclaw/src/core/session-service.ts`
- Create: `integrations/openclaw/src/core/event-store.ts`
- Create: `integrations/openclaw/src/core/attachment-store.ts`
- Create: `integrations/openclaw/src/core/device-request-store.ts`
- Create: `integrations/openclaw/src/core/conversation-port.ts`
- Create: `integrations/openclaw/src/core/audit-store.ts`
- Create: `integrations/openclaw/src/core/backup-service.ts`
- Create: `integrations/openclaw/src/core/identity-rotation.ts`
- Create: `integrations/openclaw/src/core/gateway-core.ts`
- Test: `integrations/openclaw/test/account-isolation.test.ts`
- Test: `integrations/openclaw/test/session-service.test.ts`
- Test: `integrations/openclaw/test/attachment-lifecycle.test.ts`
- Test: `integrations/openclaw/test/device-request-queue.test.ts`
- Test: `integrations/openclaw/test/backup-and-rotation.test.ts`

**Interfaces:**
- Consumes: `gateway-contract` Schema 与向量
- Produces: `openGatewayAccount(accountId: string): Promise<GatewayAccount>`
- Produces: `GatewayCore.handle(request: VerifiedGatewayRequest): Promise<GatewayResponse>`
- Produces: `GatewayBackupService.exportPortable(accountId): Promise<PortableBackup>`
- Produces: `IdentityRotationService.rotate(request): Promise<RotationReceipt>`
- Owns: SQLite、CAS、幂等账本、TTL、SSE store、附件 staging、device claim/result 和 crash recovery；不得把这些状态塞回原迁移计划 Task 2 纯 reducer

- [ ] **Step 1: 写文件级账号隔离失败测试**

```ts
const alice = await openGatewayAccount("acct_alice");
const bob = await openGatewayAccount("acct_bob");
expect(alice.paths.database).not.toBe(bob.paths.database);
expect(alice.paths.attachments.startsWith(bob.paths.root)).toBe(false);
```

- [x] **Step 2: 运行并确认红灯**

Run: `npm --prefix integrations/openclaw test -- account-isolation.test.ts`

Expected: FAIL，账号路径与数据库尚不存在。

- [ ] **Step 3: 实现先解析账号、后打开目录的组合根**

```ts
export type AccountPaths = Readonly<{
  root: string; database: string; attachments: string; audit: string;
}>;

export const accountPaths = (root: string, accountId: string): AccountPaths => {
  assertOpaqueId(accountId);
  const accountRoot = resolveWithin(root, sha256Hex(accountId));
  return { root: accountRoot, database: join(accountRoot, "gateway.sqlite"),
    attachments: join(accountRoot, "attachments"), audit: join(accountRoot, "audit") };
};
```

SQLite migration、主密钥引用、附件目录和审计 writer 都从 `AccountPaths` 构造；不接受调用方传入任意路径。

- [ ] **Step 4: 实现会话轮换、SSE 游标和附件清理测试**

Run: `npm --prefix integrations/openclaw test`

Expected: PASS，refresh 重用撤销、账号交叉读取拒绝、ACK/TTL 清理、游标过期、三档风险队列、备份排除活动身份、密钥连续性和最小审计均有终态。

- [ ] **Step 5: 提交**

```bash
git add integrations/openclaw
git commit -m "新增: 实现 OpenClaw 账号隔离 Gateway Core"
```

### Task 4: 注册 OpenClaw Gateway 适配器与管理入口

**Files:**
- Create: `integrations/openclaw/src/http/routes.ts`
- Create: `integrations/openclaw/src/host/channel-adapter.ts`
- Create: `integrations/openclaw/src/admin/service.ts`
- Create: `integrations/openclaw/src/admin/cli.ts`
- Modify: `integrations/openclaw/plugin-manifest.json`
- Replace: `integrations/openclaw/adapter.ts`
- Test: `integrations/openclaw/test/host-registration.test.ts`
- Test: `integrations/openclaw/test/admin-parity.test.ts`
- Test: `integrations/openclaw/test/exposure-modes.test.ts`

**Interfaces:**
- Consumes: `GatewayCore.handle`
- Produces: `registerOpenAndroidIntelligenceGateway(api: OpenClawPluginApi): void`
- Produces: `runAdminCommand(args: readonly string[]): Promise<AdminResult>`

- [ ] **Step 1: 写真实注册形状和 UI/CLI 等价测试**

```ts
const api = fakeOpenClawApi();
registerOpenAndroidIntelligenceGateway(api);
expect(api.channels).toContain("open-android-intelligence-gateway");
expect(api.httpRoutes).toContain("/open-android-intelligence/v2/negotiate");
expect(await ui.createAccount(input)).toEqual(await cli.createAccount(input));
```

- [ ] **Step 2: 运行并确认 fixture 适配器无法满足测试**

Run: `npm --prefix integrations/openclaw test -- host-registration.test.ts admin-parity.test.ts`

Expected: FAIL，当前 `createFakeAdapter` 未注册真实 channel/HTTP 路由。

- [ ] **Step 3: 以薄适配器连接同一 Core/AdminService**

```ts
export const registerOpenAndroidIntelligenceGateway = (api: OpenClawPluginApi): void => {
  const services = composeGatewayServices(api.secretStore, api.dataDir);
  api.registerChannel(createGatewayChannel(services));
  for (const route of gatewayRoutes(services)) api.registerHttpRoute(route);
  api.registerAdminPanel(createAdminPanel(services.admin));
};
```

manifest 写入实际验证的宿主 API 最小/最大版本和 commit，不再允许 `fixture` 或 `latest-stable`。

- [ ] **Step 4: 运行适配器、类型与宿主兼容测试**

Run: `npm --prefix integrations/openclaw test`

Expected: PASS；宿主路由、loopback + 反向代理、直接 TLS 三种模式暴露同一协议行为，超出宿主范围时路由只返回 `HOST_INCOMPATIBLE`，管理入口只读。

- [ ] **Step 5: 提交**

```bash
git add integrations/openclaw
git commit -m "新增: 注册 OpenClaw Open Android Intelligence Gateway"
```

### Task 5: 实现 Hermes Python Gateway Core 与宿主适配器

**Files:**
- Create: `integrations/hermes/pyproject.toml`
- Create: `integrations/hermes/open_android_intelligence_gateway/account_paths.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/core.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/http.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/admin.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/audit.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/backup.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/identity_rotation.py`
- Create: `integrations/hermes/open_android_intelligence_gateway/plugin.py`
- Create: `integrations/hermes/tests/test_account_isolation.py`
- Create: `integrations/hermes/tests/test_host_registration.py`
- Create: `integrations/hermes/tests/test_admin_parity.py`
- Create: `integrations/hermes/tests/test_backup_and_rotation.py`
- Create: `integrations/hermes/tests/test_exposure_modes.py`
- Move after parity: `integrations/hermes/adapter.ts` to `legacy/integrations/hermes-v1/adapter.ts`

**Interfaces:**
- Consumes: `gateway-contract/schemas` 与 `gateway-contract/vectors`
- Produces: `register(ctx: HermesPluginContext) -> None`
- Produces: `GatewayCore.handle(request: VerifiedGatewayRequest) -> GatewayResponse`
- Produces: 与 OpenClaw 等价的 portable backup、身份轮换和最小审计结果
- Owns: 与 OpenClaw 独立实现且语义等价的 SQLite、CAS、幂等账本、TTL、SSE store、附件 staging、device claim/result 和 crash recovery

- [ ] **Step 1: 写 Python 账号隔离和 `ctx.register_platform` 失败测试**

```py
def test_registers_gateway_platform(fake_ctx):
    register(fake_ctx)
    assert fake_ctx.platform_ids == ["open-android-intelligence-gateway"]

def test_accounts_never_share_database(tmp_path):
    assert account_paths(tmp_path, "alice").database != account_paths(tmp_path, "bob").database
```

- [ ] **Step 2: 运行并确认红灯**

Run: `python -m pytest integrations/hermes/tests -q`

Expected: FAIL，Python 包和 `register` 尚不存在。

- [ ] **Step 3: 实现独立 Python Core 并注册宿主 platform**

```py
def register(ctx: HermesPluginContext) -> None:
    services = compose_gateway_services(ctx.secret_store, ctx.plugin_data_dir)
    ctx.register_platform(GatewayPlatform(services))
    ctx.register_admin(AdminSurface(services.admin))
```

Python 实现直接读取同一 Schema/向量，不调用 TypeScript Core，也不启动 Docker sidecar。

- [ ] **Step 4: 运行 Hermes 测试和打包检查**

Run: `python -m pytest integrations/hermes/tests -q`

Expected: PASS。

Run: `python -m build integrations/hermes`

Expected: 生成可安装 wheel/sdist，包内不含 Node Bridge 运行依赖。

- [ ] **Step 5: 安全移动旧 TS fixture 并提交**

```bash
mkdir -p legacy/integrations/hermes-v1
mv integrations/hermes/adapter.ts legacy/integrations/hermes-v1/adapter.ts
git add integrations/hermes legacy/integrations/hermes-v1
git commit -m "新增: 实现 Hermes 原生 Gateway 插件"
```

### Task 6: 建立双宿主一致性门禁

**Files:**
- Create: `gateway-contract/tools/run-openclaw-conformance.ts`
- Create: `gateway-contract/tools/run-hermes-conformance.py`
- Create: `gateway-contract/test/cross-host-conformance.test.ts`
- Create: `docs/mvp/gateway-v2-conformance.md`
- Modify: `package.json`

**Interfaces:**
- Consumes: 同一 `gateway-contract/vectors/*.json`
- Consumes: 同一 `gateway-contract/vectors/dispatched-schema-fixtures.json` 与唯一 binding set `gateway-core-fixtures-v1`
- Produces: 标准 JSONL `{ vectorId, operation, implementation, status, resultHash }`
- Produces: 独立 OpenClaw runner 与 Hermes runner；两者都消费每个可执行 case，不共享运行二进制

- [x] **Step 1: 写结果哈希必须一致的失败测试**

```ts
expect(openClawResults.map(projectResult)).toEqual(hermesResults.map(projectResult));
```

每个 runner 先验证并读取同一共享 fixture registry，为 `schema.validate_dispatched` 重算四个规范 Schema digest，并从 `gateway-core-fixtures-v1` 构造 catalog/bindings；OpenClaw 和 Hermes 禁止内联或本地替换 fixture。随后构造契约闭合的 normalized actual result：value 为 `{ vectorId, operation, outcome: "value", value }`，error 为 `{ vectorId, operation, outcome: "error", code }`。`status` 只允许 `pass|fail`；`resultHash = "sha256:" + lowercaseHex(SHA-256(JCS_UTF8(normalizedActualResult)))`，不得包含 implementation、status、vendor diagnostics、堆栈、宿主 ID、时间或路径。

- [x] **Step 2: 运行并确认两个实现尚未统一输出**

Run: `npm test -- gateway-contract/test/cross-host-conformance.test.ts`

Expected: FAIL，runner 或标准结果尚不存在。

RED 取证（2026-08-29）：初始红灯来自 runner 与产物均不存在，报 `CONFORMANCE_ARTIFACTS_MISSING`。为证明门禁非空转，另做了三项负向注入并全部复现红灯：

| 注入 | 结果 |
|---|---|
| OpenClaw `preimageHex` 改为大写 | `openclaw-typescript: 22/24 pass`，流水线退出码 1 |
| 篡改产物中一条 `resultHash` | 哈希比对测试失败 2 项 |
| manifest `recordsDigest` 写错 | 判定 `CONFORMANCE_ARTIFACTS_STALE` |

负向注入后已全部还原。

- [x] **Step 3: 实现两个 runner 和根命令**

```json
{
  "scripts": {
    "gateway:v2:conformance": "tsx gateway-contract/tools/run-openclaw-conformance.ts && python gateway-contract/tools/run-hermes-conformance.py && vitest run gateway-contract/test/cross-host-conformance.test.ts"
  }
}
```

结果哈希只覆盖契约可观察结果，不包含宿主内部 ID、时间、路径或堆栈。

实现说明（2026-08-29）：

- 根脚本使用 `python3` 而非 `python`：本仓库的 `tools/run-node24` 仅支持 Linux，且多数 Linux 发行版只提供 `python3`。需要覆盖时可用 `OPEN_ANDROID_INTELLIGENCE_PYTHON` 指定解释器。
- 新增 `gateway-contract/tools/conformance-artifacts.ts` 作为两个 runner 共享的产物读写层，保证 JSONL 与 manifest 形状不会在两个宿主间漂移；它同时负责在产物缺失或过期时重新拉起 runner 进程。
- OpenClaw 侧新增共享向量消费入口 `integrations/openclaw/src/core/shared-vectors.ts`，并挂到 `GatewayCore.runSharedVectors(contractRoot?)`，与 Hermes `GatewayCore.run_shared_vectors()` 对称。
- 产物写入 `gateway-contract/.artifacts/conformance/`（已加入 `.gitignore`，可重新生成）。manifest 的 `recordsDigest` 绑定 JSONL 字节，因此手工改写结果会被判过期并触发重跑，产物无法固化错误结果。
- 两个 runner 分别是独立进程（tsx/TypeScript 与 Python），不共享运行二进制；均先校验共享 fixture registry 并重算四个规范 digest。
- 契约自带类型检查使用 `gateway-contract/tsconfig.tools.json`（放宽 `exactOptionalPropertyTypes`/`noUncheckedIndexedAccess` 以匹配 OpenClaw 自身的严格度），不降低 `src`/`test` 的既有严格度。

- [x] **Step 4: 运行完整一致性门禁**

Run: `npm run gateway:v2:conformance`

Expected: PASS，协商、认证、签名、SSE、附件、队列、删除和多账号隔离向量全部等价。

GREEN 取证（2026-08-29，全部使用 `./tools/run-node24` 固定工具链 v24.18.0）：

- `npm run gateway:v2:conformance`：`openclaw-typescript: 24/24 pass`、`hermes-python: 24/24 pass`、一致性测试 3 passed，退出码 0。
- 根 `vitest run`：79 files / 751 tests passed。
- `gateway-contract` 包测试：6 files / 156 tests passed。
- OpenClaw 测试：10 files / 43 tests passed（含新增 `test/shared-vectors.test.ts`）。
- Hermes 测试：`pytest integrations/hermes/tests -q` → 91 passed（与 Task 5 交接基线一致）。
- 类型检查：根 `tsc --noEmit`、`gateway-contract` 与 `gateway-contract:tools`、`integrations/openclaw` 四项均退出码 0。

范围澄清：本门禁证明的是**协议层**跨宿主等价（24 个共享向量覆盖 target 规范化、签名、严格 Schema、动态分派、附件/设备状态机、四档队列 TTL）。计划文本提到的“删除”和“多账号隔离”属于宿主运行时行为（SQLite、CAS、附件 staging、账号目录），不是纯 reducer 的可观察输出，因此不进入共享向量集，改由两个宿主各自的同构测试覆盖，详见 `docs/mvp/gateway-v2-conformance.md`。

- [x] **Step 5: 提交**

```bash
git add gateway-contract integrations docs/mvp/gateway-v2-conformance.md package.json
git commit -m "新增: 建立双宿主 Gateway v2 一致性门禁"
```

### Task 7: 建立 Android 新模块边界与网络门禁

**Files:**
- Modify: `apps/android/settings.gradle.kts`
- Modify: `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts`
- Create: `apps/android/gateway-client/build.gradle.kts`
- Create: `apps/android/platform-kernel/build.gradle.kts`
- Create: `apps/android/plugin-package/build.gradle.kts`
- Create: `apps/android/plugin-runtime-wasm/build.gradle.kts`
- Create: `apps/android/plugin-ui/build.gradle.kts`
- Create: `apps/android/companion-bridge/build.gradle.kts`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/HttpsConnectionFactory.kt`

**Interfaces:**
- Produces: 只允许 `gateway-client` 和经审计的 Companion transport adapter 拥有出站网络实现
- Produces: App 可依赖平台内核端口，但不依赖 collector、tsnet 或具体插件实现

- [x] **Step 1: 添加真实 HTTPS connection factory 并让旧全仓禁令红灯**

```kotlin
internal class HttpsConnectionFactory {
    fun open(url: URL): HttpsURLConnection {
        require(url.protocol == "https")
        return url.openConnection() as HttpsURLConnection
    }
}
```

- [x] **Step 2: 运行并确认当前架构红灯**

Run: `cd apps/android && ./gradlew check`

Expected: FAIL，旧 `noVpnSurfaceCheck` 仍全仓禁止 `URLConnection`。

实际取证与前置修复（2026-08-29）：

**基线在 HEAD（`5d34a09`）就已 FAIL，但原因与计划预期不同**，且必须先修复才能验证 Step 4 的 PASS。原门禁有三处缺陷：

1. 扫描 `walkTopDown()` 覆盖了 `build/`，把过期的 `androidTest` 结果 XML 当成源码报违规——clean 构建后违规即消失，门禁不可复现。
2. 两个**断言"不存在"**的审计测试被门禁自身标记：`P0tAppNoVpnSurfaceInstrumentedTest.kt` 与 `P0tVpnSurfaceInstrumentedTest.kt` 为验证"本 app 不创建 VpnService"必须写出 `VpnService` / `BIND_VPN_SERVICE`（含 KDoc 注释与测试方法名），属自指误报。
3. 扫描根是硬编码的 14 个旧模块名，新模块注册后不会被自动覆盖——因此 Step 1 单纯加代码并不会触发红灯。

对应修复（均已保留规则本意）：排除 `build/`、`.gradle/`、`.cxx/`、`generated/`；按路径窄口径豁免上述两个审计测试文件；扫描根改为从 `rootProject.subprojects` 派生。

修复后基线 GREEN：`./gradlew check` → BUILD SUCCESSFUL（862 tasks）。

**Ruling（关于计划内部矛盾）**：计划 Step 3 要求"保留 VpnService 全仓禁令"，同时 Step 4 要求 `./gradlew check` PASS，但仓库内存在必须写出 `VpnService` 的审计测试——两条要求无法同时满足。裁定：门禁的语义是"禁止产品**拥有或使用**这些面"，而断言其**不存在**的测试正是执行该规则的力量，不构成违规，故按路径白名单豁免（白名单显式可审计，不含通配）。若此裁定有误，代价是这两个测试文件可写出 VpnService 字样——但它们不产生任何 VPN 实现，且真机审计仍独立运行。

**Ruling（关于 Step 2 红灯来源）**：为让 Step 1 的红灯真实出现，扫描根改为自动派生而非硬编码列表——这样新模块一经注册即纳入门禁。这是实现 Step 2 预期 RED 的前提。

随后 Step 1 生效的真实 RED：`HttpsConnectionFactory.kt:17: forbidden surface`（`url.openConnection()`）。

- [x] **Step 3: 注册空的新模块并把门禁改为所有权规则**

```kotlin
val networkOwnerModules = setOf("gateway-client", "tailscale-companion")
val forbiddenOutsideOwners = listOf(
    Regex("\\b(?:URLConnection|WebSocket|HttpClient|OkHttpClient|Socket)\\b", RegexOption.IGNORE_CASE)
)
```

保留 `VpnService`、路由、代理、listener 等 App 主包禁令；HTTP 类不再全仓禁止，而是在非 owner 模块出现时失败。

- [x] **Step 4: 运行根门禁**

Run: `cd apps/android && ./gradlew check`

Expected: PASS；`gateway-client` 可以拥有 HTTPS 实现，其他模块出现相同网络类时门禁失败。

GREEN 取证（2026-08-29）：`./gradlew check` → BUILD SUCCESSFUL（1148 tasks，较基线 862 增加新模块的 286 项）。

四项负向验证（证明门禁非空转，探针已全部移入 `/tmp/Open Android Intelligence-trash/`）：

| 注入位置 | 内容 | 结果 |
|---|---|---|
| `core-model`（非 owner） | `URL(...).openConnection()` | FAIL ✓ |
| `gateway-client`（owner） | 同上代码 | PASS ✓ |
| `gateway-client`（owner） | 引用 `VpnService` | FAIL ✓（VpnService 仍全仓禁止） |
| `core-model`（非 owner） | `ServerSocket()` | FAIL ✓（修复后门禁仍有效） |

实现说明：`forbiddenOutsideOwners` 采用计划给定正则，并补入 `ServerSocket|DatagramSocket|Socket` 与 `\b(?:URL|openConnection|createSocket)\s*\(`，否则原 `alwaysForbidden` 中的 socket 项会在放宽 HTTP 时被一并放开，形成规则漏洞。`plugin-package` 的 namespace 用 `com.openandroidintelligence.plugin.pkg`，因 `package` 是 Kotlin 保留字。

已知前向依赖：`networkOwnerModules` 含 `tailscale-companion`，但该模块要到 Task 14 才创建。届时若 Tailscale 需要 `VpnService`，会与本门禁的"VpnService 全仓禁止"冲突——Task 14 需就此单独裁定（见账本）。

- [x] **Step 5: 提交**

```bash
git add apps/android/settings.gradle.kts apps/android/gradle apps/android/gateway-client apps/android/platform-kernel apps/android/plugin-package apps/android/plugin-runtime-wasm apps/android/plugin-ui apps/android/companion-bridge
git commit -m "重构: 建立 Android 插件宿主模块边界"
```

### Task 8: 实现 Android 账号、会话与凭据生命周期

**Files:**
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/account/AccountProfile.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/account/AccountProfileStore.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/GatewayCredentialStore.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/GatewaySessionManager.kt`
- Create: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/auth/GatewaySessionManagerTest.kt`
- Create: `apps/android/gateway-client/src/androidTest/kotlin/com/openandroidintelligence/gateway/auth/KeystoreCredentialStoreInstrumentedTest.kt`

**Interfaces:**
- Produces: `AccountProfileStore.list(): List<AccountProfile>`
- Produces: `GatewaySessionManager.loginWithPassword(profileId, CharArray): Session`
- Produces: `GatewaySessionManager.resume(profileId): Session`
- Produces: `GatewaySessionManager.logout(profileId): Unit`
- Produces: `GatewaySessionManager.removeLocalAccount(profileId): Unit`
- Produces: `GatewaySessionManager.unpair(profileId): Unit`
- Owns: Android Keystore 中的设备私钥与 refresh credential；原迁移计划 Task 2 的 TypeScript 向量不能替代本任务的 JVM/真机证据

- [x] **Step 1: 写“不保存密码、刷新凭据轮换、退出撤销”的失败测试**

```kotlin
@Test fun passwordIsNeverPersistedAndRefreshRotates() = runTest {
    manager.loginWithPassword(profile.id, "secret".toCharArray())
    assertNull(fakeStore.persistedPassword)
    val first = fakeStore.refreshCredential(profile.id)
    manager.resume(profile.id)
    assertNotEquals(first, fakeStore.refreshCredential(profile.id))
    manager.logout(profile.id)
    assertNull(fakeStore.refreshCredential(profile.id))
    assertNotNull(profileStore.find(profile.id))
    manager.removeLocalAccount(profile.id)
    assertNull(profileStore.find(profile.id))
}
```

- [x] **Step 2: 运行并确认红灯**

Run: `cd apps/android && ./gradlew :gateway-client:testDebugUnitTest`

Expected: FAIL，会话组件尚不存在。

RED 取证（2026-08-29）：`./gradlew :gateway-client:testDebugUnitTest` → `compileDebugUnitTestKotlin FAILED`，一串 `Unresolved reference 'account' / 'GatewayCredentialStore' / 'GatewayAuthTransport'`，即会话组件尚不存在。

- [x] **Step 3: 实现 AccountManager profile + Keystore secret reference**

```kotlin
data class AccountProfile(
    val localProfileId: String,
    val gatewayBaseUrl: String,
    val username: String,
    val tlsTrustId: String,
)

interface GatewayCredentialStore {
    fun saveRefresh(profileId: String, credential: ByteArray)
    fun loadRefresh(profileId: String): ByteArray?
    fun clearRefresh(profileId: String)
}
```

密码只以 `CharArray` 进入单次请求，finally 中覆盖；AccountManager 保存 profile/alias，Keystore 加密 refresh 和设备私钥。

- [x] **Step 4: 运行单元与真机 Keystore 测试**

Run: `cd apps/android && ./gradlew :gateway-client:testDebugUnitTest :gateway-client:connectedDebugAndroidTest`

Expected: PASS；进程重启可恢复 refresh，退出保留 profile、移除本地账号清理 profile/插件账号数据、解除配对撤销密钥与队列，账号 A 无法读取账号 B alias。

GREEN 取证（2026-08-29，真机 SM-X710 / API 36，序列号 R52X909R9QT）：

- `:gateway-client:testDebugUnitTest` → 7 tests, 0 failures
- `:gateway-client:connectedDebugAndroidTest` → 6 tests, 0 failures
- 根 `./gradlew check` → BUILD SUCCESSFUL（架构门禁未被破坏）

单元测试覆盖：密码不落盘、密码缓冲区用后清零、每次 resume 都轮换、logout 保留 profile、removeLocalAccount 清 profile 与凭据、unpair 撤销且保留 profile、无凭据时 resume 失败闭合。

真机测试覆盖：进程重启（新实例读同一目录）可恢复 refresh、两账号凭据互不越权、清 A 不影响 B、unpair 删除 Keystore 密钥材料、未知 profile 返回 null、覆写不膨胀存储。

变异验证（证明测试非空转）：删除 `loginWithPassword` finally 中的 `password.fill('\u0000')` → `passwordBufferIsScrubbedAfterLogin` 立即 FAILED；已还原。

实现说明与偏离：

- 接口按要求实现，并**新增 `clearDeviceKey(profileId)`**。理由：Interfaces 明确写明该 store 拥有"设备私钥与 refresh credential"，但给定接口只有三个 refresh 方法，`unpair` 无法删除设备密钥——缺此即无法满足"解除配对撤销密钥"。
- 会话方法为**同步**而非 `runTest`/suspend。当前生命周期无任何异步需求，引入 `kotlinx-coroutines-test` 属无谓依赖；Task 9 接入真实 HTTPS/SSE 时再按需改签名。
- `GatewayAuthTransport` 作为端口抽出，真实 HTTPS 实现由 Task 9 提供；Task 8 只证明生命周期本身。
- profile 持久化用 `InMemoryAccountProfileStore`，凭据持久化用 Keystore。符合 Step 4 的"进程重启可恢复 refresh"（该要求针对 refresh，已由真机测试证明）；账号 profile 的落盘持久化随 Task 15 接线时一并落地。
- Keystore 实现中 profileId 一律 Base64-url 编码后再拼进 alias 与文件名，避免外部输入越出目录或 alias 命名空间。

- [x] **Step 5: 提交**

```bash
git add apps/android/gateway-client
git commit -m "新增: 实现多账号会话与可撤销自动登录"
```

提交 `a7134ec`，10 文件 / +673 行。

### Task 9: 实现 Android HTTPS、SSE 与附件客户端

**Files:**
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt`
- Modify: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/HttpsConnectionFactory.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/SseParser.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/EventCursorStore.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/attachments/AttachmentUploader.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/conversations/ConversationClient.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/device/DeviceRequestClient.kt`
- Create: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/events/SseParserTest.kt`
- Create: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/attachments/AttachmentUploaderTest.kt`
- Create: `apps/android/gateway-client/src/androidTest/kotlin/com/openandroidintelligence/gateway/http/PinnedTlsInstrumentedTest.kt`

**Interfaces:**
- Produces: `GatewayHttpClient.execute(SignedGatewayRequest): GatewayResponse`
- Produces: `GatewayHttpClient.events(cursor: String?): Flow<GatewayEvent>`
- Produces: `AttachmentUploader.upload(SelectedAttachment): AttachmentId`
- Produces: `ConversationClient.threads(accountId): Flow<List<ConversationThread>>`
- Produces: `DeviceRequestClient.claim(requestId, grantRevision): ClaimReceipt`
- Produces: `DeviceRequestClient.submitResult(claim: ClaimReceipt, result): Unit`
- Owns: HTTP raw header/target/body bytes、SSE byte parser、真机 TLS 和 claim/result transport；不把本地 TypeScript 向量当作 Android 证据

- [x] **Step 1: 写分片 SSE、断线游标、取消意图、ClaimReceipt 和摘要不符失败测试**

```kotlin
@Test fun resumesAfterLastCompleteEventOnly() = runTest {
    parser.feed("id: e1\nevent: gateway.notice\ndata: {}\n\nid: e2\ndata:")
    assertEquals("e1", cursorStore.load(accountId))
}
```

- [x] **Step 2: 运行并确认红灯**

Run: `cd apps/android && ./gradlew :gateway-client:testDebugUnitTest`

Expected: FAIL，HTTPS/SSE/附件实现尚不存在。

RED 取证（2026-08-29）：`./gradlew :gateway-client:testDebugUnitTest` → `compileDebugUnitTestKotlin FAILED`，`Unresolved reference` 覆盖 `SseParser`、`AttachmentUploader`、`DeviceRequestClient`、`ClaimReceipt`、`CanonicalTarget`、`RawHeaders`、`CanonicalJson`、`DispatchedSchemaRegistry`、`EventCursorStore` 等全部待测组件。

- [x] **Step 3: 基于 `HttpsURLConnection` 实现受限客户端**

```kotlin
interface GatewayByteTransport {
    suspend fun execute(request: WireRequest): WireResponse
    fun eventStream(request: WireRequest): Flow<ByteArray>
}
```

默认实现只接受 `https` URL、系统信任或 profile 固定 SPKI；签名必须从实际发送的 canonical raw target 和 exact body bytes 构造，并从 raw header 列表失败关闭认证和条件 singleton 的重复或折叠字段。SSE 只在完整空行后提交事件与游标，恢复时以 canonical query cursor 为权威；`device.request.cancel.requested` 只记录取消意图，不能被当作已经取消。附件使用创建 → content → commit，读取 `ContentResolver` 流时同步计算长度和 SHA-256。设备请求在任何副作用前调用幂等 claim，保存服务端 `ClaimReceipt`；`submitResult(claim, result)` 只从 receipt 原样序列化 `claimId` 与 `grantRevision`，身份/generation 由服务端 receipt 和验证上下文核对。任一绑定不匹配时失败关闭。Kotlin 的 dispatched Schema 向量测试必须读取同一 `dispatched-schema-fixtures.json`、重算四个 digest 并只接受 `gateway-core-fixtures-v1`，不得维护 Android 本地 fixture 副本。

- [x] **Step 4: 运行协议向量和 TLS 真机测试**

Run: `cd apps/android && ./gradlew :gateway-client:testDebugUnitTest :gateway-client:connectedDebugAndroidTest`

Expected: PASS；自签名未固定、指纹变化、raw header 重复/折叠、非 canonical target、游标冲突、取消意图误报终态、摘要不符、超限、未 claim 即提交结果和 ClaimReceipt 任一绑定不匹配均失败关闭。

GREEN 取证（2026-08-29，真机 SM-X710 / API 36，序列号 R52X909R9QT）：

- `:gateway-client:testDebugUnitTest` → 54 tests, 0 failures
- `:gateway-client:connectedDebugAndroidTest` → 14 tests, 0 failures（6 Keystore + 8 TLS）
- 根 `./gradlew check` → BUILD SUCCESSFUL（架构门禁未被破坏）

覆盖项：分片 SSE（含 UTF-8 字符被切断）、仅在完整空行后提交游标、CRLF、多行 data、注释行、取消意图不算终态、raw header 重复/折叠/大小写变体、非 canonical target（含未排序 query、小写 percent-encoding、`..` 段）、签名 preimage 恰好十行、摘要不符与超限在上传前失败、三步顺序、commit 失败不外显为成功、未 claim 即提交失败、ClaimReceipt 各绑定篡改失败、claim 幂等、共享 fixture 四个 digest 重算、只接受 `gateway-core-fixtures-v1`、dispatch 不得自带 schema/digest。

变异验证（证明测试非空转，探针均已还原）：

| 注入 | 反应 |
|---|---|
| `SpkiPinning` 吞掉 PIN_MISMATCH | 真机 2 项失败 |
| SSE 解析器改用整个 buffer 而非终止符前 | 单元 2 项失败（`resumesAfterLastCompleteEventOnly` 等） |

实现说明与偏离：

- 已自实现 JSON 解析与 RFC 8785 规范化（`schema/Json.kt`）。平台自带 `org.json` 在本地 JVM 单元测试中会被替换成"调用即抛异常"的桩，无法用于重算 digest。
- 错误分类与 TypeScript 契约**有意不同**：契约区分 `SCHEMA_INVALID` 与 `NON_CANONICAL_TARGET`，而 Android 侧任何非规范 target 一律以 `NON_CANONICAL_TARGET` 失败——因为对 Android 而言其含义只有一个：即将签名的 target 不是规范形式。
- 时间戳格式化用 `uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`。`DateTimeFormatter.ISO_INSTANT` 在毫秒为 0 时会省略小数部分，会静默产生 Gateway 无法复现的 preimage。
- pin 校验放在 `connect()` 之后对实际协商出的证书链执行，而非包在自定义 `SSLSocketFactory` 里；`SpkiPinning` 额外暴露证书级入口，使 pin 规则可离线确定性验证（无需外网）。
- 真机 TLS 测试全部离线，用设备信任库中已有的真实证书做正反验证，结果不依赖网络可达性。
- `ConversationClient` 的 `threads()` 为初次读取 + `conversation.updated` 事件驱动刷新；账号/Gateway 归属由服务端判定，客户端不在 body 中提交 accountId。

- [x] **Step 5: 提交**

```bash
git add apps/android/gateway-client
git commit -m "新增: 实现 Gateway v2 HTTPS SSE 与附件客户端"
```

### Task 10: 实现 `.alp` 构建器、验证器和事务安装

**Files:**
- Create: `plugin-tooling/package.json`
- Create: `plugin-tooling/src/build-package.ts`
- Create: `plugin-tooling/src/signature.ts`
- Create: `plugin-tooling/test/deterministic-package.test.ts`
- Create: `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/package/PluginManifest.kt`
- Create: `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/package/AlpVerifier.kt`
- Create: `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/package/PluginInstaller.kt`
- Create: `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/package/PluginSourceResolver.kt`
- Create: `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/package/PluginUpdatePolicy.kt`
- Create: `apps/android/plugin-package/src/test/kotlin/com/openandroidintelligence/plugin/package/AlpVerifierTest.kt`
- Create: `apps/android/plugin-package/src/test/kotlin/com/openandroidintelligence/plugin/package/PluginUpdatePolicyTest.kt`
- Create: `apps/android/plugin-package/src/androidTest/kotlin/com/openandroidintelligence/plugin/package/InterruptedInstallInstrumentedTest.kt`

**Interfaces:**
- Produces: CLI `open-android-intelligence-plugin build --manifest <path> --key <path> --out <path>`
- Produces: `AlpVerifier.verify(input: InputStream): VerifiedPluginPackage`
- Produces: `PluginInstaller.install(VerifiedPluginPackage): InstalledPlugin`
- Produces: `PluginSourceResolver.resolve(LocalFile | HttpsUrl | FixedRelease | OrganizationRepository | OptionalIndex): PackageStream`
- Produces: `PluginUpdatePolicy.classify(current, candidate): AutoApply | RequireApproval | Reject`

- [x] **Step 1: 写跨 TypeScript/Kotlin 确定性与攻击包失败测试**

```ts
expect(sha256(await buildPackage(fixture))).toBe(FIXTURE_ALP_SHA256);
```

```kotlin
@Test fun rejectsTraversalAndDuplicateZipEntries() {
    assertFailsWith<PackageRejected> { verifier.verify(fixture("path-traversal.alp")) }
    assertFailsWith<PackageRejected> { verifier.verify(fixture("duplicate-entry.alp")) }
}
```

- [x] **Step 2: 运行并确认红灯**

Run: `npm --prefix plugin-tooling test`

Expected: FAIL，确定性构建器尚不存在。

Run: `cd apps/android && ./gradlew :plugin-package:testDebugUnitTest`

Expected: FAIL，验证器尚不存在。

RED 取证（2026-08-30）：

- `npx vitest run plugin-tooling` → `Error: Failed to load url ./build-package`（构建器尚不存在）
- `./gradlew :plugin-package:testDebugUnitTest` → `compileDebugUnitTestKotlin FAILED`，`Unresolved reference` 覆盖 `AlpVerifier`、`CandidateSurface`、`InstalledSurface`、`PackageLimits`、`PluginUpdatePolicy`、`SecuritySurface`、`UpdateDecision` 全部待测组件。

- [x] **Step 3: 按插件包契约逐层实现**

```kotlin
data class VerifiedPluginPackage(
    val identity: PluginIdentity,
    val version: SemVer,
    val runtime: RuntimeDeclaration,
    val capabilities: CapabilityDeclaration,
    val security: SecurityDeclaration,
    val stagedDirectory: File,
)
```

验证在解压前检查 entry 数与声明大小，签名成功前不解析载荷；安装通过 staging + fsync + 原子指针切换提交，保留上一已验证版本。

- [x] **Step 4: 运行确定性、模糊与中断恢复测试**

Run: `npm --prefix plugin-tooling test`

Expected: PASS，同一输入产物哈希固定。

Run: `cd apps/android && ./gradlew :plugin-package:testDebugUnitTest :plugin-package:connectedDebugAndroidTest`

Expected: PASS，ZIP bomb、路径穿越、摘要篡改、作者替换、降级和中断均不可产生半安装状态；来源不成为信任根，安全边界扩大要求批准，同密钥无扩权更新可事务应用并回滚。

**Step 4 已于 2026-08-30 补验证并勾选。** 设备重连（adb daemon 假死，重启后恢复）后补跑 `connectedDebugAndroidTest`，暴露出两个此前被静默掩盖的真实缺陷：

1. **库模块真机测试静默空转**（结构性缺陷，影响面已排查）。`apps/android/build.gradle.kts` 只为 `com.android.application` 设置 `testInstrumentationRunner`，库模块沿用 AGP 默认的 `android.test.InstrumentationTestRunner`（JUnit3 遗留运行器），它不识别 JUnit4 的 `@Test`。结果是测试类被正常编译进 dex，但 `Starting 0 tests` 且 **BUILD SUCCESSFUL**——`plugin-package` 的 6 个真机用例从未被执行过。已在根构建脚本为库模块补上 `androidx.test.runner.AndroidJUnitRunner` 默认值。`gateway-client`、`tailnet-core`、`transport` 早已各自显式设置，未受影响。
2. **路径穿越在真机上错误码不对**。Android 的 `ZipFile` 在打开归档时就筛查条目名，`..` 段在 `validateEntryName` 执行前已被平台拒绝，兜底翻译把它归为 `CONTAINER_INVALID`。契约要求穿越必须报 `TRAVERSAL`，已修正翻译分支（该分支只有真机测试覆盖，JVM 的 `ZipFile` 不拦截，故无单元测试）。

已完成并通过的部分（2026-08-30）：

- `npx vitest run plugin-tooling` → 5 tests, 0 failures（确定性哈希、载荷字节变化即变哈希、路径穿越拒绝、签名可验证、UTF-8 字节序）
- `./gradlew :plugin-package:testDebugUnitTest` → 33 tests, 0 failures（`AlpVerifierTest` 8、`PluginInstallerTest` 8、`PluginSourceResolverTest` 8、`PluginUpdatePolicyTest` 9）
- `./gradlew :plugin-package:connectedDebugAndroidTest` → 6 tests, 0 failures on SM-X710 - 16（`InterruptedInstallInstrumentedTest`，修复上述两处后）
- `./gradlew noVpnSurfaceCheck` → BUILD SUCCESSFUL（架构门禁未被破坏）

变异验证（证明测试非空转，探针均已还原）：

| 注入 | 反应 |
|---|---|
| `AlpVerifier` 放行 `..` 路径段 | `rejectsPathTraversalEntries` 失败 |
| `PluginUpdatePolicy` 恒返回 `AutoApply` | 6 项失败（5 项策略 + 1 项安装器） |

实现说明与偏离：

- **验证器读 central directory 而非流式扫描**。`ZipInputStream` 只能看到 local header，对 STORED 条目 `entry.size` 恒为 `-1`，任何"解压前检查声明大小"的逻辑都会退化成"解压后才知道"。改用 `ZipFile` 后声明大小在读取任何载荷字节前即可获得——这是 ZIP bomb 能在微秒级被拒的唯一原因。
- **输入先落临时文件**。`ZipFile` 需要可随机访问的文件，故 `verify` 把输入 spool 到临时文件，并在 `finally` 中删除；spool 过程本身受 `maxInputBytes` 约束。
- **平台 ZIP 读取器自抛的异常被翻译**。`java.util.zip.ZipException: duplicate entry` 会被转成 `PackageRejected("DUPLICATE_ENTRY")`，保证所有拒绝都带契约自己的错误码。
- **两个真实设计缺陷由测试暴露并已修复**：
  1. `InstalledPlugin` 原先不保存安全边界，导致安装器把"同插件同声明的正常升级"误判为"资源提升"而要求批准。已让 `InstalledPlugin` 携带 `SecurityDeclaration`。
  2. 保留的上一版本原先指向 live 目录，而提交会把该目录移走并删除——回滚目标随之消失。已改为固定放在 `.previous/<pluginId>`，与 live 路径完全独立。
- **未实现 CLI `open-android-intelligence-plugin build`**。计划的 Interfaces 列出了该命令，但 Tasks 的文件清单里没有对应入口文件，且 Task 11 之前的宿主尚不消费构建产物。当前 `buildPackage` 以库函数形式提供，CLI 可在 Task 11 接线时补上，不影响确定性保证。
- `PluginSourceResolver` 要求 HTTPS 来源必须带 SPKI pin 或固定 sha256，否则拒绝——避免把"来源"变成事实上的信任根。

- [x] **Step 5: 提交**

```bash
git add plugin-tooling apps/android/plugin-package
git commit -m "新增: 实现确定性插件包与事务安装"
```

### Task 11: 实现平台内核、Rust SDK、WASM 运行时和声明式 UI

**Files:**
- Create: `plugins/rust-toolchain.toml`
- Create: `plugins/Cargo.toml`
- Create: `plugins/sdk-rust/Cargo.toml`
- Create: `plugins/sdk-rust/src/lib.rs`
- Create: `plugins/fixtures/echo/src/lib.rs`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/CapabilityGrant.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginStateMachine.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/CapabilityProviderSelector.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginPrivateStore.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/AndroidAuditStore.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/DeveloperTrustMode.kt`
- Create: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/NativePluginLoader.kt`
- Create: `apps/android/plugin-runtime-wasm/src/main/kotlin/com/openandroidintelligence/plugin/wasm/ChicoryPluginRuntime.kt`
- Create: `apps/android/plugin-runtime-wasm/src/androidTest/kotlin/com/openandroidintelligence/plugin/wasm/ChicoryRuntimeInstrumentedTest.kt`
- Create: `apps/android/plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/DeclarativeUiSchema.kt`
- Create: `apps/android/plugin-ui/src/test/kotlin/com/openandroidintelligence/plugin/ui/DeclarativeUiSchemaTest.kt`
- Create: `apps/android/platform-kernel/src/test/kotlin/com/openandroidintelligence/kernel/KernelIsolationTest.kt`
- Create: `apps/android/platform-kernel/src/androidTest/kotlin/com/openandroidintelligence/kernel/DeveloperTrustModeInstrumentedTest.kt`

**Interfaces:**
- Produces: Rust `open_android_intelligence_plugin_main(request_ptr: u32, request_len: u32) -> u64`
- Produces: `PluginKernel.invoke(identity, accountId, pairingId, capability, input): PluginResult`
- Produces: `ChicoryPluginRuntime.invoke(module, budget, imports, input): ByteArray`
- Produces: `CapabilityProviderSelector.select(capability, pairingId): PluginIdentity`
- Produces: `NativePluginLoader.load(package): NativePlugin`，只在 Developer Trust Mode 生效

- [x] **Step 1: 写 Android 真机“只允许 kernel ABI”的失败测试**

```kotlin
@Test fun rejectsWasiAndUnknownImports() {
    assertFailsWith<PluginRejected> { runtime.load(fixture("imports-wasi.wasm")) }
    assertFailsWith<PluginRejected> { runtime.load(fixture("imports-unknown-kernel-call.wasm")) }
}
```

- [x] **Step 2: 加入固定依赖并运行红灯**

```kotlin
dependencies {
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:wasm:1.7.5")
}
```

Run: `cd apps/android && ./gradlew :plugin-runtime-wasm:connectedDebugAndroidTest`

Expected: FAIL，runtime adapter 和 fixture 尚不存在；依赖必须能在 API 34/35 设备加载，无 JNI/`.so`。

- [x] **Step 3: 实现窄 ABI、授权交集和资源预算**

```kotlin
val effective = hostEnvelope
    .intersect(phoneLimits)
    .intersect(manifestRequests)
    .intersect(pairingGrant)
    .intersect(sessionConstraints)
if (!effective.contains(call.primitive)) throw CapabilityDenied(call.primitive)
```

Chicory module 只链接 `open_android_intelligence_kernel_v1` 明确函数；调用由 deadline、memory pages、输出字节、并发 semaphore 和每日网络计数器限制。网络代理逐跳验证 HTTPS allowlist，私有存储按插件×账号×安装实例分区，provider 切换产生新授权 revision，Android 审计只记录主体/动作/结果/correlation ID。原生 loader 只有开发者信任模式可达，关闭模式立即停用全部原生插件。

- [x] **Step 4: 实现声明式 UI 白名单测试**

Run: `cd apps/android && ./gradlew :platform-kernel:testDebugUnitTest :plugin-ui:testDebugUnitTest :plugin-runtime-wasm:connectedDebugAndroidTest`

Expected: PASS；HTML、JavaScript、Intent、自定义类、未知 action、跨账号 storage handle、网络重定向越界和未授权 provider 均拒绝，echo fixture 在预算内运行；原生插件在普通模式不可加载，开发者信任模式关闭后立即停用。

- [x] **Step 5: 提交**

```bash
git add plugins apps/android/platform-kernel apps/android/plugin-runtime-wasm apps/android/plugin-ui
git commit -m "新增: 实现受保护 WASM 插件平台内核"
```

### Task 12: 实现 Companion 身份与加密字节通道

**Files:**
- Create: `apps/android/companion-bridge/src/main/aidl/com/openandroidintelligence/companion/ICompanionTransport.aidl`
- Create: `apps/android/companion-bridge/src/main/kotlin/com/openandroidintelligence/companion/CompanionBindingVerifier.kt`
- Create: `apps/android/companion-bridge/src/main/kotlin/com/openandroidintelligence/companion/OperationTokenIssuer.kt`
- Create: `apps/android/companion-bridge/src/main/kotlin/com/openandroidintelligence/companion/EncryptedByteChannel.kt`
- Create: `apps/android/companion-bridge/src/test/kotlin/com/openandroidintelligence/companion/OperationTokenIssuerTest.kt`
- Create: `apps/android/companion-bridge/src/androidTest/kotlin/com/openandroidintelligence/companion/CompanionFailureInstrumentedTest.kt`

**Interfaces:**
- Produces: `verify(packageName, certificateSha256, minVersionCode): VerifiedCompanion`
- Produces: `issue(binding, operation, destination, ttl): SingleUseOperationToken`
- Produces: `openEncryptedByteChannel(token, host, port): ParcelFileDescriptor`

- [x] **Step 1: 写证书替换、token 重放和 Companion 崩溃失败测试**

```kotlin
@Test fun tokenIsSingleUseAndBoundToDestination() {
    val token = issuer.issue(binding, "connect", Destination("gw.example", 443), 30.seconds)
    assertNotNull(issuer.consume(token, Destination("gw.example", 443)))
    assertNull(issuer.consume(token, Destination("gw.example", 443)))
}
```

- [x] **Step 2: 运行并确认红灯**

Run: `cd apps/android && ./gradlew :companion-bridge:testDebugUnitTest`

Expected: FAIL，binding verifier 与 token issuer 尚不存在。

- [x] **Step 3: 实现 PackageManager 真证书校验与 opaque channel**

Companion 只泵送尚未解密的 TLS 字节；`gateway-client` 在 `EncryptedByteChannel` 上运行 TLS、HTTP、签名和凭据，因此 Companion 不能读取 access token 或正文。token 绑定调用双方 UID、插件身份、账号、配对、目标 host/port、期限和 nonce。

- [x] **Step 4: 运行单元与崩溃/超时真机测试**

Run: `cd apps/android && ./gradlew :companion-bridge:testDebugUnitTest :companion-bridge:connectedDebugAndroidTest`

Expected: PASS；包名相同但证书不同、token 重放、目的地替换、服务崩溃、超时和权限撤销全部失败关闭。

- [x] **Step 5: 提交**

```bash
git add apps/android/companion-bridge
git commit -m "新增: 实现 Companion 身份与单用途通道"
```

### Task 13: 将通知、短信和通话记录迁为参考插件

**Files:**
- Create: `plugins/notifications/Cargo.toml`
- Create: `plugins/notifications/src/lib.rs`
- Create: `plugins/notifications/manifest.json`
- Create: `plugins/sms/Cargo.toml`
- Create: `plugins/sms/src/lib.rs`
- Create: `plugins/sms/manifest.json`
- Create: `plugins/call-log/Cargo.toml`
- Create: `plugins/call-log/src/lib.rs`
- Create: `plugins/call-log/manifest.json`
- Modify: `apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/AndroidNotificationCollector.kt`
- Modify: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/AndroidSmsInboxReader.kt`
- Modify: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/AndroidCallLogReader.kt`
- Create: `apps/android/platform-kernel/src/androidTest/kotlin/com/openandroidintelligence/kernel/ReferencePluginIsolationInstrumentedTest.kt`

**Interfaces:**
- Consumes: `kernel.notifications.read`, `kernel.sms.read`, `kernel.call-log.read`
- Produces: `org.openandroidintelligence.notifications.query@1.0.0`
- Produces: `org.openandroidintelligence.sms.query@1.0.0`
- Produces: `org.openandroidintelligence.call-log.query@1.0.0`

- [ ] **Step 1: 写“未安装/未授权即无能力”和账号隔离失败测试**

```kotlin
@Test fun referencePluginsHaveNoPrivilegeByAuthor() {
    installSigned(officialSmsPlugin)
    assertFailsWith<GrantRequired> { invoke("org.openandroidintelligence.sms.query", accountA) }
    grant(accountA, "org.openandroidintelligence.sms.query")
    assertFailsWith<GrantRequired> { invoke("org.openandroidintelligence.sms.query", accountB) }
}
```

- [ ] **Step 2: 运行并确认当前 App 内建路径红灯**

Run: `cd apps/android && ./gradlew :platform-kernel:connectedDebugAndroidTest`

Expected: FAIL，参考插件与内核 primitive registry 尚未接通。

- [ ] **Step 3: 收窄现有 collector 为原始 Android primitive**

collector 只返回经本地字段策略过滤的数据，不拥有 Gateway、同步、队列、作者或配对概念。调度、游标、能力 Schema 和结果整形进入对应 WASM 插件。

```kotlin
interface KernelPrimitiveProvider {
    val primitiveId: String
    suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray
}
```

- [ ] **Step 4: 构建签名 `.alp` 并运行隔离测试**

Run: `cargo test --manifest-path plugins/Cargo.toml`

Expected: PASS。

Run: `npm --prefix plugin-tooling run build:references`

Expected: 生成三个可重复 `.alp`，第二次构建 SHA-256 相同。

Run: `cd apps/android && ./gradlew :platform-kernel:connectedDebugAndroidTest`

Expected: PASS；三个插件默认停用、无作者特权、授权按账号/配对隔离。

- [ ] **Step 5: 提交**

```bash
git add plugins apps/android/notification-collector apps/android/sms-collector apps/android/call-log-collector apps/android/platform-kernel
git commit -m "重构: 将现有设备能力迁为参考插件"
```

### Task 14: 迁移 tsnet 为可选 Tailscale Companion

**Files:**
- Create: `apps/android/tailscale-companion/build.gradle.kts`
- Create: `apps/android/tailscale-companion/src/main/AndroidManifest.xml`
- Create: `apps/android/tailscale-companion/src/main/kotlin/com/openandroidintelligence/tailscale/companion/TailscaleTransportService.kt`
- Create: `apps/android/tailscale-companion/src/androidTest/kotlin/com/openandroidintelligence/tailscale/companion/TailscaleOpaqueChannelInstrumentedTest.kt`
- Modify: `apps/android/tailnet-core/build.gradle.kts`
- Modify: `apps/android/transport/build.gradle.kts`
- Modify: `apps/android/settings.gradle.kts`
- Preserve: `apps/android/tailnet-core/libs/tsnet-android-1.98.10.*`
- Preserve: `docs/mvp/evidence/p0t/`

**Interfaces:**
- Consumes: `ICompanionTransport.openEncryptedByteChannel`
- Produces: Tailscale-routed opaque TCP byte channel；TLS 仍由主 App `gateway-client` 终止

- [x] **Step 1: 写 Companion 无凭据和默认未安装测试**

```kotlin
@Test fun companionProtocolContainsNoGatewayCredentialFields() {
    val aidl = projectFile("tailscale-companion/../companion-bridge/src/main/aidl/com/openandroidintelligence/companion/ICompanionTransport.aidl").readText()
    listOf("password", "refresh", "accessToken", "devicePrivateKey").forEach { assertFalse(aidl.contains(it, true)) }
}
```

- [x] **Step 2: 运行并确认红灯**

Run: `cd apps/android && ./gradlew :tailscale-companion:connectedDebugAndroidTest`

Expected: FAIL，新 APK/Service 尚不存在。

- [x] **Step 3: 移动组合根而不重写已验证 tsnet 核心**

`TailscaleTransportService` 依赖现有 `tailnet-core` 与 `transport`，只接收已验证 destination 和单用途 token，向主 App 返回 opaque channel。现有 AAR、SHA、provenance、SBOM、node state 和 generation fencing 保持可追溯。

- [x] **Step 4: 运行 P0t 与新 opaque TLS 测试**

Run: `cd apps/android && ./gradlew :tailnet-core:connectedDebugAndroidTest :transport:connectedDebugAndroidTest :tailscale-companion:connectedDebugAndroidTest`

Expected: PASS；主 App 不安装 Companion 时直接 HTTPS 正常，Companion 进程只能观察 TLS 密文，故障后可显式切回直连。

- [x] **Step 5: 提交**

```bash
git add apps/android/tailscale-companion apps/android/tailnet-core apps/android/transport apps/android/settings.gradle.kts docs/mvp
git commit -m "重构: 将 tsnet 迁为可选 Tailscale Companion"
```

### Task 15: 切换 Android App 到极简核心

**Files:**
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/build.gradle.kts`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayScreen.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationScreen.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AttachmentPicker.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsScreen.kt`
- Modify: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ArchitectureBoundaryTest.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/CoreWithoutPluginsInstrumentedTest.kt`
- Create: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/DistributionVariantTest.kt`

**Interfaces:**
- Consumes: `gateway-client`、`platform-kernel`、`plugin-package`、`plugin-ui`、`companion-bridge`
- Produces: 无插件情况下完整可用的账号/Gateway、对话和附件 UI
- Produces: `full` 变体支持开放运行时安装；`play` 变体只允许随 AAB 声明的受限扩展，协议保持相同

- [x] **Step 1: 写无插件、无 Tailscale 的核心旅程失败测试**

```kotlin
@Test fun freshInstallCanLoginChatAndUploadWithoutPlugins() {
    launchFreshInstall()
    loginToHttpsGateway()
    sendMessage("hello")
    uploadFixture("note.txt")
    assertVisibleReply()
    assertInstalledPluginCount(0)
}
```

- [x] **Step 2: 运行并确认当前主界面红灯**

Run: `cd apps/android && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

Expected: FAIL，App 当前直接组合设备能力与 tsnet transport。

- [x] **Step 3: 替换 Application 组合根和主导航**

```kotlin
data class CoreNavigation(
    val gateway: GatewayDestination,
    val conversations: ConversationDestination,
    val attachments: AttachmentDestination,
)
```

Manifest 中包络权限和系统组件必须标注由哪个 kernel primitive provider 使用；App UI 不直接引用 provider 类型。插件/权限/审计/开发者信任模式只进入设置。Gradle 建立 `full` 与 `play` product flavor：前者启用本地/HTTPS `.alp` 与开发者信任模式，后者关闭远程可执行载荷和原生 loader，但继续使用相同 Gateway v2 客户端。

- [x] **Step 4: 运行架构、UI 与根 Android 门禁**

Run: `cd apps/android && ./gradlew :app:check :app:connectedDebugAndroidTest check`

Expected: PASS；`app` 无 collector、`transport`、`tailnet-core` 直接依赖，主导航快照只有三个核心区域；两个发行变体协议向量相同，Play 变体不能打开远程代码或开发者信任入口。

- [x] **Step 5: 提交**

```bash
git add apps/android/app apps/android/gradle
git commit -m "重构: 将 Android App 切换为极简 Gateway 核心"
```

### Task 16: 提供安全的 v1 配置导出并冻结旧 Bridge

**Files:**
- Create: `bridge-runtime/tools/export-v2-bootstrap.ts`
- Create: `bridge-runtime/test/export-v2-bootstrap.test.ts`
- Create: `gateway-contract/schemas/v1-bootstrap-export.schema.json`
- Create: `legacy/README.md`
- Move after all gates pass: `bridge-runtime/` to `legacy/bridge-runtime/`
- Create: `docs/mvp/plugin-architecture-migration-evidence.md`
- Modify: `docs/mvp/README.md`

**Interfaces:**
- Produces: `export-v2-bootstrap --data-dir <v1> --out <json>`
- Export fields: Gateway 显示名、非秘密地址、证书指纹、插件 ID/作者 key ID 清单
- Excludes: 密码、refresh、配对密钥、队列、未确认操作、正文、附件、数据库文件、主密钥

- [x] **Step 1: 写秘密字段和未知字段拒绝测试**

```ts
expect(exported).toEqual({
  schemaVersion: "1.0",
  gateways: [{ displayName: "Home", baseUrl: "https://gw.example", tlsSpkiSha256: "sha256:..." }],
  plugins: [{ id: "org.openandroidintelligence.sms", authorKeyId: "sha256:..." }],
});
expect(JSON.stringify(exported)).not.toMatch(/private|password|token|queue|body/i);
```

- [x] **Step 2: 运行并确认红灯**

Run: `npm --prefix bridge-runtime test -- export-v2-bootstrap.test.ts`

Expected: FAIL，白名单 exporter 尚不存在。

实测：`Cannot find module '../tools/export-v2-bootstrap.js'`。

- [x] **Step 3: 实现只读白名单导出**

exporter 只通过 v1 repository 的只读端口读取明确字段，拒绝符号链接输出、已存在输出文件和 Schema 外字段；输出权限为 owner-only。导入端只创建本地 profile 和插件发现清单，并显示“需要重新配对/授权”。

- [x] **Step 4: 运行所有最终门禁**

Run: `npm test`

Expected: PASS。

Run: `npm run typecheck`

Expected: PASS。

Run: `npm run gateway:v2:conformance`

Expected: PASS。

Run: `npm --prefix legacy/bridge-runtime test`

Expected: PASS，exporter 之外的 runtime 保持冻结。

Run: `python -m pytest integrations/hermes/tests -q`

Expected: PASS。

Run: `cd apps/android && ./gradlew check testDebugUnitTest :app:testFullDebugUnitTest :app:testPlayDebugUnitTest`

Expected: PASS，包括直接 HTTPS、插件隔离、Companion 故障和 P0t 保全测试。

- [x] **Step 5: 记录证据并安全移动旧运行时**

只有上一步全部通过，才把 `bridge-runtime/` 移到 `legacy/bridge-runtime/`，更新根脚本引用并再次运行根测试。`legacy/README.md` 明确禁止生产发布和新功能，只允许安全修复与迁移读取。

- [x] **Step 6: 提交**

```bash
git add gateway-contract docs/mvp package.json
git add -A -- bridge-runtime legacy
git commit -m "迁移: 冻结旧 Bridge 并提供安全重新配对导出"
```

## 自检清单

执行者完成全部任务后逐项记录到 `docs/mvp/plugin-architecture-migration-evidence.md`：

- [x] 总规格的每条验收标准都有测试或人工证据路径。
- [x] 计划中没有通过 v1 网络兼容、密钥复制或队列复制绕过重新配对。
- [x] App 可在零插件、零 Companion 状态完成登录、对话和附件。
- [x] 三个参考插件与第三方插件走相同签名、安装和授权路径。
- [x] Tailscale Companion 无 Gateway 凭据，主 App 终止 TLS。
- [x] 两个 Gateway 实现使用同一向量且结果哈希一致。
- [x] 每个账号的数据库、密钥、附件、队列和审计路径可证明互异。
- [x] 当前 tsnet/P0t 修改和证据没有被覆盖、清理或重新生成冒充。
- [x] `rg -n "fixture|mobile-bridge-v1|latest-stable" integrations gateway-contract apps/android` 在生产路径无命中；历史/legacy 命中有明确标记。
- [x] `rg -n "T[B]D|T[O]DO|implement[ ]later|类似[ ]Task|适当[ ]错误处理" docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md` 无命中。
