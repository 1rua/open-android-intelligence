# P0a Protocol and Security Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 冻结并实现 open-android-intelligence v1.0 的语言中立设备协议与安全参考模型，使 Android、Bridge 和 Agent adapter 能用同一组 schema、状态机与 golden vectors 做一致性验证。

**Architecture:** JSON Schema Draft 2020-12 是 wire contract 的唯一结构化来源；RFC 8785 JCS、SHA-256 与 P-256/ES256 定义字节级签名语义。TypeScript 参考核心实现确定性授权、重放防护、状态机与 fake actors，独立 Kotlin/JVM runner 消费同一固定向量，避免只验证单语言 encode/decode 自洽。P0a 不实现生产数据库、Android Keystore、真实 WSS/HTTPS、Hermes/OpenClaw 接入或任何手机数据采集。

**Tech Stack:** Node.js 24.18.0 LTS、TypeScript 7.0.2、JSON Schema 2020-12、Ajv 8.20.0、Vitest 4.1.10、fast-check 4.9.0、Kotlin 2.4.10、Eclipse Temurin JDK 21.0.11+10、Gradle 9.6.1、kotlinx.serialization 1.11.0、JUnit 5、RFC 8785 JCS、SHA-256、ES256。

## Global Constraints

- 已批准基线是 `docs/superpowers/specs/2026-08-08-agent-bridge-android-design.md`；实施中发现冲突时立即停止相关 task，先在独立文档提交中同步修订 spec 与 plan 并经用户确认，不能由代码或 P0a 子规格暗自选择另一语义。
- 执行前提是当前 spec 与本 plan 已位于同一个可追溯 commit 且工作树干净；该 commit 就是 `p0a-acceptance.md` 记录的 approved design/plan baseline。
- 目标 Android 基线保持 API 34；P0a Kotlin 模块不得依赖 Android framework，以便 JVM CI 与未来 Android 复用。
- v1 wire JSON 必须是无 BOM、无尾随换行的 RFC 8785 canonical UTF-8；对象重复键、非 canonical 字节、`-0`、NaN、Infinity 和 lone surrogate 全部拒绝。
- v1 所有计数器、generation、revision、sequence、offset、长度和毫秒 duration 在 JSON 中使用无前导零的十进制字符串；绝对时间固定为 UTC RFC 3339、恰好三位小数毫秒且以 `Z` 结尾。
- v1 的 `message_id`、`operation_id`、`approval_id`、`artifact_id`、`stream_id`、`source_epoch` 和通道 `ticket_id` 固定为小写 UUIDv4；enrollment ticket 与 Bridge 分配的 tenant/principal/device/session ID 仅按不透明服务端值校验。
- v1 签名固定为 P-256 `SHA256withECDSA`；wire signature 使用 64-byte IEEE-P1363 `r || s`、base64url 无填充、low-S 规范化，验证器拒绝 high-S。
- 签名前像固定为 UTF-8 域前缀、NUL、四字节大端 canonical payload 长度和 JCS 字节；每个 digest/hash 必须冻结精确 preimage，不允许实现自选。JSON 对象使用其 JCS UTF-8 字节；明确为 byte-valued 的 chunk/artifact、解码后的 enrollment ticket/channel nonce 与 `*_wire_b64` 使用对应原始字节；任何其他例外必须在命名字段处写明并进入跨语言向量。
- control envelope canonical UTF-8 总大小上限固定为 262,144 bytes；正文附件、屏幕帧和连续传感器数据必须走独立 artifact/stream 通道。
- 所有 v1 wire/model 语义 SHA-256 摘要字段均为 43 字符 base64url 无填充字符串（供应链下载校验和不受此编码规则影响）。`payload_digest = b64u(SHA-256(JCS_UTF8(payload)))`；`parameters_digest = b64u(SHA-256(JCS_UTF8(parameters)))`，其中 `parameters` 是通过 capability 对应闭合 schema 后的原样 JSON 值；`filter_hash = b64u(SHA-256(JCS_UTF8(filter)))`，其中 `filter` 是 grant 的闭合 filter 对象，缺失字段保持缺失且除 JCS 外不做语义重写；chunk hash 使用该 chunk 的原始内容字节；最终 artifact digest 使用按 chunk sequence 拼接还原出的完整文件原始字节；stream `config_digest` 使用闭合 `config` 对象的 JCS UTF-8 字节。固定向量必须覆盖空对象、字段重排、缺失与显式 `null`、Unicode/数字规范化及单字节内容变化。
- 每个 credential、`pairing_generation`、方向和 key 的 sequence 空间在 HTTPS/WSS 间共享；receiver 使用 1,024 位持久滑动窗口容忍跨通道乱序，同时拒绝已见或窗口外旧序列。
- key rotation 只有旧可信 key 签名、接收方 ACK 后才能激活；旧/新 key 最长并存 15 分钟。设备安装 key 或 Bridge command trust 丢失时只能重新配对并推进 `pairing_generation`；adapter 凭据由服务端运维独立重签，推进 adapter credential generation 并 fence 旧会话，不改写设备 epoch。
- wire 唯一携带显式 `scope_revisions` map，不保留“或 authorization snapshot hash”的第二种编码。
- 主动 `device_event` 使用设备/source 授权谓词，不携带或选择 Agent principal/session；Bridge 只按服务端 subscription 路由。
- 任意 Root Shell、generic exec、脚本、解释器、动态 Root action 和自动化规则 DSL 不得出现在 schema、registry、fake actor 或测试辅助接口中。
- 模型 API 只按已批准规格作为零保留瞬时推理边界；P0a 仅定义 profile evidence/fail-closed 状态，不实现 provider 删除目标。
- 测试私钥必须位于明确标记的 `test-only` fixture 目录；生产接口只接收抽象 `Signer`/`Verifier`，不得加载 fixture 私钥。
- 所有纯逻辑使用注入的 `Clock`、`NonceSource` 和 store 接口；测试不得依赖真实时间、随机数、网络或 sleep。
- 每个 schema object 默认 `unevaluatedProperties: false`；旧 schema 永不原地放宽。新增字段、消息或算法必须创建新 schema/version 并更新锁文件。
- Linux x64 开发机与 CI 不信任 ambient Node/JDK/Gradle。Task 1 后所有 Node 命令必须经 `tools/run-node24`，Task 12 后所有 Gradle 命令必须经 `conformance/kotlin/run-gradle`；两个固定 launcher 都在每次执行前验证版本/平台及已批准下载物的 SHA-256。

## Planned Repository Map

```text
package.json / package-lock.json       Node 工具链、精确依赖和统一检查命令
tsconfig.json / vitest.config.ts       严格 TypeScript 与确定性测试配置
protocol/
  profile/v1.json                       算法、域、大小、时钟、重放与轮换常量的机器权威来源
  schemas/v1/                          不可变 JSON Schema 2020-12 contract
  registries/v1/                       message、error、capability、audit-field registry
  model/v1/                            状态迁移表与错误优先级
  src/                                 TypeScript 参考算法与纯状态机
  test/                                单元、属性、负向和 trace 测试
  vectors/v1/                          固定 wire bytes、签名、决策与 trace 结果
  test-only/keys/                      明确不可用于生产的固定 P-256 测试密钥
  spec-lock.json                       profile/schema/registry/model/vector/ABI 的 SHA-256 锁
conformance/SUT.md                     各语言统一的 NDJSON conformance ABI
conformance/kotlin/                    独立 Kotlin/JVM vector consumer/producer
docs/protocol/v1/                      规范性 wire、授权、状态机、威胁与迁移文档
.github/workflows/protocol.yml         Node 与 Kotlin 的 P0a 总门禁
```

---

### Task 1: Bootstrap the deterministic protocol workspace

**Files:**
- Modify: `.gitignore`
- Create: `.nvmrc`
- Create: `package.json`
- Create: `package-lock.json`
- Create: `tsconfig.json`
- Create: `vitest.config.ts`
- Create: `tools/run-node24`
- Create: `protocol/src/version.ts`
- Create: `protocol/test/version.test.ts`
- Create: `protocol/README.md`

**Interfaces:**
- Produces: `PROTOCOL_VERSION: "1.0"`, `SCHEMA_LINE: "v1"`, root `npm test` and `npm run typecheck` commands.
- Consumes: none.

- [ ] **Step 1: Add the pinned test harness and failing workspace smoke test**

Set `.nvmrc` to `24.18.0`. Add executable `tools/run-node24`, a Linux x64-only fixed launcher that downloads `https://nodejs.org/dist/v24.18.0/node-v24.18.0-linux-x64.tar.xz` into ignored `/.toolchains/`, verifies SHA-256 `55aa7153f9d88f28d765fcdad5ae6945b5c0f98a36881703817e4c450fa76742`, extracts atomically, prepends only that distribution's `bin` to `PATH`, asserts `node --version` is exactly `v24.18.0` and bundled `npm --version` is exactly `11.16.0`, then accepts only `node`, `npm` or `npx` as its first argument and `exec`s that fixed binary. Unsupported OS/architecture, checksum, version or command fails closed; it never falls back to ambient tools.

Create `package.json` with `"packageManager": "npm@11.16.0"` plus these exact direct versions and scripts:

```json
{
  "name": "open-android-intelligence-protocol",
  "private": true,
  "type": "module",
  "packageManager": "npm@11.16.0",
  "engines": { "node": ">=24.18.0 <25" },
  "scripts": {
    "test": "vitest run",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@noble/curves": "2.2.0",
    "ajv": "8.20.0",
    "ajv-formats": "3.0.1",
    "canonicalize": "3.0.0"
  },
  "devDependencies": {
    "@types/node": "24.13.3",
    "fast-check": "4.9.0",
    "tsx": "4.23.1",
    "typescript": "7.0.2",
    "vitest": "4.1.10"
  }
}
```

Configure `tsconfig.json` with `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `resolveJsonModule`, `module`/`moduleResolution` set to `NodeNext`, target `ES2024`, and include `protocol/**/*.ts`. Configure Vitest for Node, UTC, one deterministic worker, and a 5-second per-test timeout. Preserve the already committed `/.worktrees/` entry and add only generated local surfaces: `/.toolchains/`, `/node_modules/`, `/.gradle/`, `**/.gradle/`, `**/build/`, test reports, coverage and editor/OS files. It must not ignore protocol vectors, locks, wrappers or dependency-verification metadata.

```ts
// protocol/test/version.test.ts
import { describe, expect, it } from "vitest";
import { PROTOCOL_VERSION, SCHEMA_LINE } from "../src/version.js";

describe("protocol baseline", () => {
  it("freezes the approved P0a line", () => {
    expect(PROTOCOL_VERSION).toBe("1.0");
    expect(SCHEMA_LINE).toBe("v1");
  });
});
```

- [ ] **Step 2: Install the locked harness, then verify the missing module failure**

Run: `tools/run-node24 npm install && tools/run-node24 npm test -- protocol/test/version.test.ts`

Expected: `package-lock.json` is created, then Vitest runs and FAILS because `protocol/src/version.ts` does not exist.

- [ ] **Step 3: Add the minimal protocol version implementation**

```ts
// protocol/src/version.ts
export const PROTOCOL_VERSION = "1.0" as const;
export const SCHEMA_LINE = "v1" as const;
```

- [ ] **Step 4: Run the baseline checks**

Run: `tools/run-node24 npm test -- protocol/test/version.test.ts && tools/run-node24 npm run typecheck`

Expected: one passing test and TypeScript exit 0.

- [ ] **Step 5: Commit the workspace baseline**

```bash
git add .gitignore .nvmrc package.json package-lock.json tsconfig.json vitest.config.ts tools/run-node24 protocol
git commit -m "chore(protocol): bootstrap P0a workspace"
```

### Task 2: Make the threat model and security invariants executable

**Files:**
- Create: `docs/protocol/v1/threat-model.md`
- Create: `protocol/registries/v1/p0a-requirements.json`
- Create: `protocol/registries/v1/security-properties.json`
- Create: `protocol/registries/v1/audit-fields.json`
- Create: `protocol/test/security-properties.test.ts`

**Interfaces:**
- Produces: stable P0a requirement IDs `P0A-001` through `P0A-022`, security property IDs `SEC-001` through `SEC-016`, and `allowed_audit_fields`/`forbidden_audit_fields` registries.
- Consumes: `PROTOCOL_VERSION` from Task 1.

- [ ] **Step 1: Write the failing invariant-registry test**

```ts
import { describe, expect, it } from "vitest";
import requirements from "../registries/v1/p0a-requirements.json" with { type: "json" };
import properties from "../registries/v1/security-properties.json" with { type: "json" };
import audit from "../registries/v1/audit-fields.json" with { type: "json" };

const expectedRequirementBindings = [
  { id: "P0A-001", spec_refs: ["§10.1", "§10.2"], artifacts: ["protocol/profile/v1.json", "protocol/src/encoding.ts", "protocol/src/crypto.ts"], evidence: ["p0a.v1.canonical.profile", "p0a.v1.signature.dev2br", "p0a.v1.signature.br2dev"] },
  { id: "P0A-002", spec_refs: ["§6.1"], artifacts: ["protocol/src/enrollment.ts", "protocol/src/pairing-transcript.ts"], evidence: ["p0a.v1.enrollment.ticket-transcript"] },
  { id: "P0A-003", spec_refs: ["§10.2"], artifacts: ["protocol/registries/v1/versions.json", "protocol/src/version-negotiation.ts"], evidence: ["p0a.v1.version.negotiate-downgrade"] },
  { id: "P0A-004", spec_refs: ["§10.2"], artifacts: ["protocol/registries/v1/messages.json", "protocol/schemas/v1/control-envelope.schema.json"], evidence: ["p0a.v1.binding.auth-context-matrix"] },
  { id: "P0A-005", spec_refs: ["§10.2"], artifacts: ["protocol/src/replay-window.ts", "protocol/src/connection-fence.ts", "protocol/src/key-ring.ts"], evidence: ["p0a.v1.replay.sequence-ledger", "p0a.v1.connection.fence-reconnect", "p0a.v1.key-rotation.lifecycle"] },
  { id: "P0A-006", spec_refs: ["§7.2"], artifacts: ["protocol/src/authorization.ts"], evidence: ["p0a.v1.authorization.revision-lifecycle"] },
  { id: "P0A-007", spec_refs: ["§7.2", "§9.3"], artifacts: ["protocol/src/data-query-grant.ts"], evidence: ["p0a.v1.authorization.stored-query-domain"] },
  { id: "P0A-008", spec_refs: ["§9.2"], artifacts: ["protocol/src/authorization-revision.ts", "protocol/model/v1/revision-events.json"], evidence: ["p0a.v1.authorization.revision-lifecycle"] },
  { id: "P0A-009", spec_refs: ["§7.3"], artifacts: ["protocol/src/authorization.ts"], evidence: ["p0a.v1.isolation.same-tenant-principal-matrix"] },
  { id: "P0A-010", spec_refs: ["§7.2", "§10.3"], artifacts: ["protocol/src/capability-manifest.ts", "protocol/src/adapter-admission.ts"], evidence: ["p0a.v1.adapter.admission-fail-closed"] },
  { id: "P0A-011", spec_refs: ["§11.1", "§11.2", "§11.3"], artifacts: ["protocol/src/risk-policy.ts", "protocol/registries/v1/capabilities.json"], evidence: ["p0a.v1.policy.sms-send-always-confirms", "p0a.v1.policy.l4-backend-deny-matrix"] },
  { id: "P0A-012", spec_refs: ["§4.2", "§9.4"], artifacts: ["protocol/src/egress-policy.ts"], evidence: ["p0a.v1.egress.zero-retention-fail-closed"] },
  { id: "P0A-013", spec_refs: ["§10.4", "§16.1"], artifacts: ["protocol/schemas/v1/operation.schema.json", "protocol/src/operation-machine.ts"], evidence: ["p0a.v1.operation.transition-matrix"] },
  { id: "P0A-014", spec_refs: ["§10.4", "§10.5"], artifacts: ["protocol/src/operation-machine.ts"], evidence: ["p0a.v1.operation.cancel-race", "p0a.v1.operation.reconcile"] },
  { id: "P0A-015", spec_refs: ["§10.5"], artifacts: ["protocol/src/execution-ledger.ts"], evidence: ["p0a.v1.operation.idempotency", "p0a.v1.operation.crash-cuts"] },
  { id: "P0A-016", spec_refs: ["§10.6"], artifacts: ["protocol/src/approval.ts", "protocol/src/request-limiter.ts"], evidence: ["p0a.v1.approval.single-use-atomic-claim"] },
  { id: "P0A-017", spec_refs: ["§9.5", "§10.2"], artifacts: ["protocol/src/event-contract.ts"], evidence: ["p0a.v1.event-envelope.binding-matrix"] },
  { id: "P0A-018", spec_refs: ["§10.7"], artifacts: ["protocol/src/channel-ticket.ts", "protocol/src/artifact-machine.ts"], evidence: ["p0a.v1.artifact.upload-commit", "p0a.v1.artifact.resume-abuse"] },
  { id: "P0A-019", spec_refs: ["§10.7"], artifacts: ["protocol/src/channel-ticket.ts", "protocol/src/stream-machine.ts"], evidence: ["p0a.v1.stream.lifecycle-abuse"] },
  { id: "P0A-020", spec_refs: ["§16.1", "§16.3"], artifacts: ["protocol/registries/v1/errors.json", "protocol/src/audit.ts"], evidence: ["p0a.v1.error.precedence-matrix", "p0a.v1.audit.forbidden-content-rejected"] },
  { id: "P0A-021", spec_refs: ["§11.4", "§17.1"], artifacts: ["protocol/registries/v1/capabilities.json", "protocol/tools/check-forbidden-surfaces.ts"], evidence: ["p0a.v1.schema.root-shell-absent"] },
  { id: "P0A-022", spec_refs: ["§10.2", "§19.1"], artifacts: ["protocol/src/migration.ts", "conformance/SUT.md"], evidence: ["p0a.v1.version.schema-preserving-migration", "conformance.kotlin.all-vectors"] },
] as const;

const expectedSecurityEvidence = [
  ["SEC-001", "auth.network-metadata-denied"],
  ["SEC-002", "auth.principal-injection-denied"],
  ["SEC-003", "auth.stored-query-independent"],
  ["SEC-004", "auth.stale-revision-denied"],
  ["SEC-005", "operation.ledger-full-fails-closed"],
  ["SEC-006", "operation.ack-loss-executes-once"],
  ["SEC-007", "approval.parameter-mutation-denied"],
  ["SEC-008", "isolation.cross-tenant-denied"],
  ["SEC-009", "connection.old-generation-fenced"],
  ["SEC-010", "audit.forbidden-content-rejected"],
  ["SEC-011", "egress.zero-retention-unknown-denied"],
  ["SEC-012", "schema.root-shell-absent"],
  ["SEC-013", "auth.adapter-principal-missing"],
  ["SEC-014", "auth.manifest-is-not-token"],
  ["SEC-015", "policy.sms-send-always-confirms"],
  ["SEC-016", "policy.l4-always-denied"],
] as const;

const expectedAllowedAuditFields = [
  "server_time", "device_time", "sequence", "credential_id", "key_id",
  "tailnet_node_id", "tailnet_tag", "policy_attestation_revision", "policy_attestation_digest",
  "operation_id", "message_id",
  "tenant_id", "human_principal_id", "agent_principal_id", "agent_instance_id",
  "workspace_id", "session_id", "job_id", "device_id", "scope", "capability", "risk_level",
  "pairing_generation", "authorization_epoch", "scope_revisions", "grant_revision",
  "approval_method", "outcome", "duration_ms", "byte_count", "correlation_id",
] as const;

const expectedForbiddenAuditFields = [
  "auth_key", "body", "command_output", "enrollment_ticket", "node_private_state",
  "notification_body", "oauth_secret", "otp", "password", "private_key",
  "screen_frame", "token",
] as const;

describe("P0a security registry", () => {
  it("maps every in-scope requirement to artifacts and evidence", () => {
    expect(requirements.map(({ id, spec_refs, artifacts, evidence }) => ({ id, spec_refs, artifacts, evidence })))
      .toEqual(expectedRequirementBindings);
    expect(requirements.every((r) => r.phase === "P0a")).toBe(true);
  });

  it("freezes every security property to its required evidence ID", () => {
    expect(properties.map((p) => [p.id, p.tests[0]])).toEqual(expectedSecurityEvidence);
    expect(properties.every((p) => p.tests.length === 1)).toBe(true);
  });

  it("freezes disjoint audit allow and deny registries", () => {
    expect(audit.allowed_audit_fields).toEqual(expectedAllowedAuditFields);
    expect(audit.forbidden_audit_fields).toEqual(expectedForbiddenAuditFields);
    expect(audit.allowed_audit_fields.filter((field) => audit.forbidden_audit_fields.includes(field))).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test and verify missing registries fail**

Run: `tools/run-node24 npm test -- protocol/test/security-properties.test.ts`

Expected: FAIL because both registry files are absent.

- [ ] **Step 3: Write the threat model and exact invariant matrix**

`threat-model.md` must enumerate assets, actors, trust boundaries, entry points, assumptions, abuse cases, controls, residual risks and out-of-scope compromises. Enter this exact registry mapping; every `tests` item is a stable test/vector identifier used by later tasks:

| ID | Required property | Primary evidence ID |
|---|---|---|
| SEC-001 | IP、MagicDNS、Tailscale identity/header 不产生授权 | `auth.network-metadata-denied` |
| SEC-002 | 服务端认证上下文是 principal/device binding 权威 | `auth.principal-injection-denied` |
| SEC-003 | 实时设备授权与 stored-query grant 是独立判定域 | `auth.stored-query-independent` |
| SEC-004 | pairing/epoch/scope revision 精确匹配，撤销重授不复活旧对象 | `auth.stale-revision-denied` |
| SEC-005 | execution claim 未持久化前不调用副作用 | `operation.ledger-full-fails-closed` |
| SEC-006 | 同一 operation 不因 transport/adapter 重试自动重复副作用 | `operation.ack-loss-executes-once` |
| SEC-007 | approval 单次使用并绑定完整参数、身份、设备和 revision | `approval.parameter-mutation-denied` |
| SEC-008 | tenant/user/device/session/artifact/stream 不能交叉复用 | `isolation.cross-tenant-denied` |
| SEC-009 | 新 connection generation fence 旧连接 | `connection.old-generation-fenced` |
| SEC-010 | audit 只含 allowlist 元数据且不记录正文 | `audit.forbidden-content-rejected` |
| SEC-011 | 零保留 profile 缺失、未知或漂移时正文外发 fail closed | `egress.zero-retention-unknown-denied` |
| SEC-012 | schema/registry/fake 中不存在任意 Root Shell 或 generic exec | `schema.root-shell-absent` |
| SEC-013 | adapter 无可信 principal 传播时启动失败 | `auth.adapter-principal-missing` |
| SEC-014 | capability manifest 只描述状态，不能充当授权 token | `auth.manifest-is-not-token` |
| SEC-015 | 每个正式 `sms.send` 均绑定完整参数并在手机逐条批准 | `policy.sms-send-always-confirms` |
| SEC-016 | L4 操作在所有类型化/restricted backend 上无条件拒绝 | `policy.l4-always-denied` |

`p0a-requirements.json` uses entries `{ id, spec_refs, phase, requirement, artifacts, evidence }` and freezes this exact scope map; event cursor persistence/gap recovery is intentionally assigned to P0b/P1a and therefore absent:

| ID | P0a normative requirement |
|---|---|
| P0A-001 | canonical profile, bounds, digest and ES256 bytes |
| P0A-002 | ticket-owned enrollment, mutual transcript and human short code |
| P0A-003 | closed version registry, highest-common selection and downgrade refusal |
| P0A-004 | message-specific signed unions and server-authoritative identity binding |
| P0A-005 | shared replay space, connection fence and authenticated key rotation |
| P0A-006 | real-time device authorization conjunction |
| P0A-007 | independent stored-query grant, signed ACK, lineage, TTL and tombstone |
| P0A-008 | pairing/authorization/scope revision transition and stale-object invalidation |
| P0A-009 | tenant plus same-tenant principal/device/session isolation |
| P0A-010 | descriptive-only capability manifest and adapter principal fail-closed |
| P0A-011 | risk/sensitivity/backend policy, SMS per-item approval and L4 denial |
| P0A-012 | zero-retention evidence and body-egress fail-closed decision |
| P0A-013 | separate request status, terminal outcome, operation reason and protocol error |
| P0A-014 | offline, expiry, cancel-race and immutable reconciliation semantics |
| P0A-015 | durable execution claim, idempotency, crash cuts and receipt replay |
| P0A-016 | approval binding, atomic consumption and anti-harassment limits |
| P0A-017 | device-event/event-ACK wire identity contract only |
| P0A-018 | artifact ticket, PoP, integrity, resume and lifecycle contract |
| P0A-019 | non-resumable live-stream ticket, credit and close contract |
| P0A-020 | closed error precedence and content-free audit contract |
| P0A-021 | no arbitrary Root Shell, generic exec, scripts or automation DSL in accepted surfaces |
| P0A-022 | immutable migration rules, deterministic fakes and independent cross-language evidence |

`audit-fields.json` is also normative rather than illustrative. Its `allowed_audit_fields` and `forbidden_audit_fields` arrays must equal the two literal arrays in Step 1, remain disjoint and reject unregistered fields; a `field_contracts` object gives every allowed field a closed primitive/map kind, format or enum reference and maximum encoded length. No allowlisted field accepts unconstrained free text. Adding or widening an audit field requires a versioned registry change rather than silently widening the allowlist. Task 11 implements the sanitizer and executable `SEC-010` evidence after all referenced enums exist.

`audit-fields.json` 的 allowlist 固定为 Step 1 的完整字面数组，包括 credential/key ID、Tailnet node ID/tag、policy-attestation revision/digest 与 scope；这些 Tailnet 字段只用于审计，不参与授权。`policy_attestation_digest` 只接受隔离 verifier 产生的 `b64u(SHA-256(JCS_UTF8(full signed attestation {header,payload,signature})))`，P0a 不生成该 attestation，也绝不从该 digest 推导 principal/scope。自由文本结果摘要不在 allowlist。

- [ ] **Step 4: Run the registry tests**

Run: `tools/run-node24 npm test -- protocol/test/security-properties.test.ts`

Expected: three passing tests.

- [ ] **Step 5: Commit the threat baseline**

```bash
git add docs/protocol/v1/threat-model.md protocol/registries/v1 protocol/test/security-properties.test.ts
git commit -m "docs(protocol): define P0a threat model"
```

### Task 3: Freeze canonical wire bytes, hashes and signatures

**Files:**
- Create: `docs/protocol/v1/wire-format.md`
- Create: `protocol/profile/v1.json`
- Create: `protocol/schemas/v1/profile.schema.json`
- Create: `protocol/src/encoding.ts`
- Create: `protocol/src/crypto.ts`
- Create: `protocol/src/profile.ts`
- Create: `protocol/src/schema-validator.ts`
- Create: `protocol/src/ports.ts`
- Create: `protocol/test/encoding.test.ts`
- Create: `protocol/test/crypto.test.ts`
- Create: `protocol/test/schema-validator.test.ts`
- Create: `protocol/test-only/keys/device-a-private.jwk.json`
- Create: `protocol/test-only/keys/device-a-public.jwk.json`
- Create: `protocol/test-only/keys/device-a-next-private.jwk.json`
- Create: `protocol/test-only/keys/device-a-next-public.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-private.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-public.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-next-private.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-next-public.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-private.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-public.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-next-private.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-next-public.jwk.json`
- Create: `protocol/test-only/keys/test-signer-keyring.json`

**Interfaces:**
- Produces: `loadProtocolProfile()`, `validateSchema(schemaId,value)`, `parseCanonicalJson(raw)`, `canonicalBytes(value)`, `sha256B64Url(bytes)`, `signingPreimage(domain,value)`, `signTestOnly(privateJwk,preimage)`, `verifyEs256(publicJwk,preimage,signature)`, `isLowS(signature)`, and abstract `Signer`/`Verifier`/`Clock`/`NonceSource` ports.
- Consumes: P-256 key material only from `protocol/test-only/keys` in tests/vector generation.

- [ ] **Step 1: Write failing canonicalization and signature tests**

```ts
it("rejects valid but non-canonical JSON bytes", () => {
  expect(() => parseCanonicalJson(new TextEncoder().encode('{ "b":2,"a":1}')))
    .toThrowError("NON_CANONICAL_JSON");
});

it("domain-separates and verifies a low-S ES256 signature", () => {
  const value = { message_id: "018f4f9a-4444-4444-8444-444444444444", sequence: "1" };
  const preimage = signingPreimage("control/app-to-bridge", value);
  const signature = signTestOnly(devicePrivateJwk, preimage);
  expect(verifyEs256(devicePublicJwk, preimage, signature)).toBe(true);
  expect(verifyEs256(devicePublicJwk, signingPreimage("approval/device", value), signature)).toBe(false);
  expect(isLowS(signature)).toBe(true);
});

it("derives every runtime constant from the machine-readable profile", () => {
  expect(loadProtocolProfile()).toMatchObject({
    profile_id: "open-android-intelligence-json-es256/1.0",
    max_envelope_bytes: "262144",
    replay_window_size: "1024",
    key_rotation_grace_seconds: "900",
    wait_read_max_seconds: "900",
  });
});
```

Add cases for duplicate keys, `-0`, lone surrogate, payload mutation, high-S, invalid base64url, wrong curve, current/next key confusion, cross-role device/Bridge/adapter key substitution and 262,145-byte wire input.

Add `schema-validator.test.ts` before implementation. It registers Draft 2020-12 plus the mandatory custom formats, accepts the exact valid profile fixture, rejects an unknown schema ID, and rejects a profile whose `max_envelope_bytes` is a JSON number instead of the required decimal string. The production change it catches is loading an unvalidated or wrong-version profile.

- [ ] **Step 2: Run the tests and verify missing APIs fail**

Run: `tools/run-node24 npm test -- protocol/test/encoding.test.ts protocol/test/crypto.test.ts protocol/test/schema-validator.test.ts`

Expected: FAIL with missing `encoding.js`/`crypto.js`/`schema-validator.js` exports.

- [ ] **Step 3: Implement the frozen profile**

```ts
const profile = loadProtocolProfile();

export const CRYPTO_PROFILE = {
  profileId: profile.profile_id,
  canonicalization: profile.canonicalization,
  digest: profile.digest,
  signature: profile.signature,
  curve: profile.curve,
  signatureEncoding: profile.signature_encoding,
  maxEnvelopeBytes: Number(profile.max_envelope_bytes),
} as const;

export type SignatureDomain =
  | "enrollment/app-to-bridge"
  | "enrollment/bridge-to-app"
  | "control/app-to-bridge"
  | "control/bridge-to-app"
  | "adapter/adapter-to-bridge"
  | "adapter/bridge-to-adapter"
  | "key-rotation/app-to-bridge"
  | "key-rotation/bridge-to-app"
  | "key-rotation/adapter-to-bridge"
  | "key-rotation/bridge-to-adapter"
  | "migration/bridge"
  | "approval/device"
  | "ticket/bridge"
  | "receipt/device"
  | "channel-pop/device"
  | "channel-pop/adapter";

export function signingPreimage(domain: SignatureDomain, value: unknown): Uint8Array {
  const prefix = new TextEncoder().encode(`open-android-intelligence/v1/${domain}\0`);
  const canonical = canonicalBytes(value);
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, canonical.byteLength, false);
  return Uint8Array.from([...prefix, ...length, ...canonical]);
}
```

`protocol/profile/v1.json` is the machine authority for profile ID, JCS version, signature preimage layout, domains, digest/signature profile, base64url policy, timestamp/ID formats, 262,144-byte envelope limit, 60-second clock skew, 1,024-slot replay window, 15-minute key grace, 15-minute `WAIT_READ` ceiling, five-minute channel ticket lifetime, artifact chunk/file/message limits and orphan lifetime. Task 3 implements the generic Draft 2020-12 `validateSchema` primitive and registers `profile.schema.json` before `profile.ts` loads the profile; schema startup fails on an unknown schema or missing mandatory custom format. `profile.ts` validates the profile at startup, and TypeScript constants are typed views over that object rather than a second hand-maintained source.

`parseCanonicalJson` first enforces byte size and strict UTF-8, parses JSON, canonicalizes the parsed value, then compares the complete raw byte array with the canonical result. This comparison is what rejects whitespace, duplicate-key collapse and `-0`. `verifyEs256` decodes exactly 64 raw bytes, checks `1 <= r,s < n` and `s <= n/2`, converts to verifier format, then verifies `SHA256withECDSA`; test signing uses deterministic RFC 6979 from `@noble/curves`, never production key loading.

`ports.ts` defines production-safe interfaces only: `Clock` exposes injected wall and monotonic time; `NonceSource` returns a requested number of unpredictable bytes; `Signer` exposes key ID/role and signs an already constructed preimage; `Verifier` resolves an authenticated role/key ID and verifies without accepting private material. Fakes implement these ports in Task 11; no production port loads `test-only` keys.

Document the exact domain bytes, four-byte big-endian length, JWK fields (`kty=EC`, `crv=P-256`, 32-byte `x/y`, `alg=ES256`, `use=sig`, `kid`), base64url rules and full-wire canonical requirement in `wire-format.md`. For envelopes, the signable value is exactly canonical `{header,payload}` with the `signature` member excluded; `payload_digest` is `base64url(SHA-256(JCS(payload)))`, and the selected message-class/direction domain plus length-prefix wraps that complete signable value. `test-signer-keyring.json` is a closed test-only map from approved signer key IDs/roles to the committed private/public fixture paths; production APIs cannot load it or accept caller-supplied private JWKs.

- [ ] **Step 4: Run focused and property tests**

Run: `tools/run-node24 npm test -- protocol/test/encoding.test.ts protocol/test/crypto.test.ts protocol/test/schema-validator.test.ts && tools/run-node24 npm run typecheck`

Expected: all canonicalization, mutation and crypto cases pass; typecheck exits 0.

- [ ] **Step 5: Commit the cryptographic profile**

```bash
git add docs/protocol/v1/wire-format.md protocol/profile protocol/schemas/v1/profile.schema.json protocol/src protocol/test protocol/test-only/keys
git commit -m "feat(protocol): freeze canonical wire signatures"
```

### Task 4: Define common types, enrollment and version negotiation

**Files:**
- Create: `docs/protocol/v1/enrollment.md`
- Create: `docs/protocol/v1/versioning-and-migration.md`
- Create: `protocol/schemas/v1/common.schema.json`
- Create: `protocol/schemas/v1/enrollment.schema.json`
- Create: `protocol/schemas/v1/connect.schema.json`
- Create: `protocol/schemas/v1/messages-registry.schema.json`
- Create: `protocol/schemas/v1/versions-registry.schema.json`
- Create: `protocol/registries/v1/messages.json`
- Create: `protocol/registries/v1/versions.json`
- Create: `protocol/src/schema-catalog.ts`
- Create: `protocol/src/message-registry.ts`
- Create: `protocol/src/enrollment.ts`
- Create: `protocol/src/pairing-transcript.ts`
- Create: `protocol/src/version-negotiation.ts`
- Modify: `protocol/src/schema-validator.ts`
- Modify: `protocol/test/schema-validator.test.ts`
- Create: `protocol/test/enrollment.test.ts`
- Create: `protocol/test/version-negotiation.test.ts`
- Create: `protocol/test/messages-registry.test.ts`

**Interfaces:**
- Produces: `consumeEnrollmentAttempt(input,store,limiter,clock)`, `pairingShortCode(transcript)`, `loadMessageRegistry()`, `verifyEnrollmentBridgeMessage(wire,context)`, `verifyConnectMessage(wire,expectedType,context)`, `parseProtocolVersion(input)`, `compareProtocolVersions(a,b)`, `loadVersionRegistry()`, `selectHighestCommonVersion(hello,lockedRegistry)`, `verifyWelcome(welcome,hello,lockedRegistry)`, `TrustedEnrollmentIntake`, `EnrollmentTicketRecord`, `EnrollmentTicketStore`, `EnrollmentAttemptLimiter`, `EnrollmentAttemptResult`, `LimitDecision`, `EnrollmentBridgeAdmissionContext`, `ConnectMessageAdmissionContext`, `LockedMessageRegistry`, `VerifiedEnrollmentChallenge`, `VerifiedEnrollmentComplete`, `VerifiedEnrollmentError`, `VerifiedConnectHello`, `VerifiedConnectWelcome`, `LockedVersionRegistry`.
- Consumes: canonical/hash/signature APIs, `Clock`, `Verifier`, `SignerRole` and `validateSchema(schemaId,value)` from Task 3. `verifyWelcome` is negotiation-only; envelope schema/registry/binding/signature admission creates its branded inputs first.

- [ ] **Step 1: Write failing enrollment and downgrade tests**

```ts
it("derives ownership only from the consumed server ticket", async () => {
  const result = await consumeEnrollmentAttempt(
    { wire: signedAttemptBytes({ ticket: VALID_TICKET, claimed_tenant_id: "tenant-b" }), remoteIp },
    ticketStoreBoundToTenantA,
    limiter,
    clock,
  );
  expect(result).toEqual({ ok: false, error: "SCHEMA_INVALID" });
  expect(ticketStoreBoundToTenantA.isConsumed(VALID_TICKET)).toBe(true);
});

it("selects the highest common version and binds the offer digest", () => {
  expect(selectHighestCommonVersion(verifiedHelloOffering09And10, lockedVersionRegistry))
    .toMatchObject({ selected: "1.0" });
  expect(() => verifyWelcome(verifiedDowngradedWelcomeSelecting09, verifiedHelloOffering10, lockedVersionRegistry))
    .toThrowError("VERSION_UNSUPPORTED");
});

it("derives the same human short code only from the complete mutual transcript", () => {
  expect(pairingShortCode(appTranscript)).toBe(pairingShortCode(bridgeTranscript));
  expect(pairingShortCode({ ...appTranscript, bridge_nonce: OTHER_NONCE }))
    .not.toBe(pairingShortCode(bridgeTranscript));
});

```

Also test the exact intake order below: every received request invokes `admitIp` exactly once, but only an allowed decision appends one admitted timestamp; size/canonical-parse errors keep their earlier public precedence even when the retained IP decision denied; after a successful canonical parse the sixth IP request returns `RATE_LIMITED` without ticket-store/full-schema/signature work. A known active ticket is consumed before one `admitPrincipal` invocation; its decision is retained while full validation continues, so schema/signature/integrity/expiry/binding/version errors win over a principal denial and the consumed ticket remains consumed. Two concurrent consumes yield exactly one `consumed` result and one indistinguishable replay failure. Test five-minute expiry, ticket replay, challenge mismatch, QR-pinned Bridge fingerprint/key mismatch, cross-ticket reuse under the same Bridge key and enrollment/control union confusion. Version tests parse canonical numeric components and reject `01.0`, `1.00`, `+1.0`, `1`, `1.0.0` and components above decimal-u64; the conformance-only `0.9` entry parses but is never selected because `negotiable=false`.

- [ ] **Step 2: Run tests and verify missing schemas/APIs fail**

Run: `tools/run-node24 npm test -- protocol/test/schema-validator.test.ts protocol/test/enrollment.test.ts protocol/test/version-negotiation.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL because enrollment and negotiation implementations are absent.

- [ ] **Step 3: Implement closed common and enrollment contracts**

`schema-catalog.ts` is pure data: it imports only JSON schema documents and exports immutable `PROTOCOL_SCHEMA_DOCUMENTS` plus `REQUIRED_PROTOCOL_SCHEMA_IDS`. It imports no validator, profile, registry or runtime module and exposes no registration function. `schema-validator.ts` alone owns Ajv: it registers mandatory formats, adds every catalog document, resolves every required ID, and only then exports `validateSchema`. The dependency is intentionally acyclic: `profile.ts → schema-validator.ts → schema-catalog.ts → JSON schemas`; the catalog never imports back upward. The catalog includes the existing profile plus these exact IDs:

```text
urn:open-android-intelligence:protocol:v1:common
urn:open-android-intelligence:protocol:v1:enrollment
urn:open-android-intelligence:protocol:v1:connect
urn:open-android-intelligence:protocol:v1:messages-registry
urn:open-android-intelligence:protocol:v1:versions-registry
urn:open-android-intelligence:protocol:v1:message:enrollment_challenge
urn:open-android-intelligence:protocol:v1:message:enrollment_response
urn:open-android-intelligence:protocol:v1:message:enrollment_complete
urn:open-android-intelligence:protocol:v1:message:enrollment_error
urn:open-android-intelligence:protocol:v1:message:connect_hello
urn:open-android-intelligence:protocol:v1:message:connect_welcome
urn:open-android-intelligence:protocol:v1:header:enrollment_app_to_bridge
urn:open-android-intelligence:protocol:v1:header:enrollment_bridge_to_app
urn:open-android-intelligence:protocol:v1:header:connect_hello
urn:open-android-intelligence:protocol:v1:header:connect_welcome
urn:open-android-intelligence:protocol:v1:envelope:enrollment_app_to_bridge
urn:open-android-intelligence:protocol:v1:envelope:enrollment_bridge_to_app
urn:open-android-intelligence:protocol:v1:envelope:connect_hello
urn:open-android-intelligence:protocol:v1:envelope:connect_welcome
```

`schema-validator.ts` retains the Task 3 public contract: unknown ID throws exactly `UNKNOWN_SCHEMA_ID`, invalid value throws with prefix `SCHEMA_INVALID: `, valid value returns `void`. Ajv and Kotlin must register `decimal-u64`, `lowercase-uuid-v4` and `rfc3339-utc-milliseconds` as mandatory formats. Lowercase UUIDv4 is exactly `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`; the timestamp format accepts only a real UTC instant rendered `YYYY-MM-DDTHH:mm:ss.SSSZ`. `protocol/test/schema-validator.test.ts` proves the catalog is data-only, startup resolves every required ID, unknown IDs fail, and every custom-format boundary is enforced.

`common.schema.json` defines lowercase UUIDv4, opaque server ID (`^[A-Za-z0-9._~-]{1,128}$`), canonical unpadded base64url, a 43-character decoded-32-byte value, an 86-character decoded-64-byte P1363 signature, SHA-256 digest, RFC3339 millisecond timestamp, decimal-u64 and explicit revision snapshot:

```json
{
  "$defs": {
    "decimal_u64": {
      "type": "string",
      "pattern": "^(0|[1-9][0-9]{0,19})$",
      "format": "decimal-u64"
    },
    "sha256_b64u": { "type": "string", "pattern": "^[A-Za-z0-9_-]{43}$" },
    "revision_snapshot": {
      "type": "object",
      "required": ["pairing_generation", "authorization_epoch", "scope_revisions"],
      "properties": {
        "pairing_generation": { "$ref": "#/$defs/decimal_u64" },
        "authorization_epoch": { "$ref": "#/$defs/decimal_u64" },
        "scope_revisions": {
          "type": "object",
          "propertyNames": { "pattern": "^[a-z][a-z0-9._-]{0,127}$" },
          "additionalProperties": { "$ref": "#/$defs/decimal_u64" }
        }
      },
      "unevaluatedProperties": false
    }
  }
}
```

`decimal-u64` parses arbitrary precision and accepts only `0..18446744073709551615`. Fixed cross-language cases accept `0` and `18446744073709551615`, reject `18446744073709551616`, negative values and leading zeroes, and prove replay/generation arithmetic rejects exhaustion rather than wrapping to zero. Fixed-length base64url schemas are backed by canonical decode-and-length checks. `p256_public_jwk` is the exact closed object `{ alg:"ES256", crv:"P-256", kid:opaque_server_id, kty:"EC", use:"sig", x:b64u32, y:b64u32 }`; Task 3 remains authoritative for point validity. JWK thumbprints project exactly `{crv,kty,x,y}` before JCS hashing.

`common.schema.json` also freezes `signed_header_base` as a non-instantiable reusable Draft 2020-12 applicator with these required properties: `protocol_version`, `message_schema`, `message_type`, `message_id`, `key_id`, `direction`, `sequence`, `issued_at`, `expires_at`, `payload_digest`. It deliberately does not set `additionalProperties` or `unevaluatedProperties`; every concrete leaf that composes it with `$ref`/`allOf` is the only closure point and sets `unevaluatedProperties: false`. Tests prove the leaf accepts the evaluated base plus leaf fields and rejects every other property. `protocol_version` is literal `1.0`; `message_schema` is an absolute registered payload `$id`; `message_id` is lowercase UUIDv4; sequence is decimal-u64; timestamps and digest use Task 3 formats. No header carries `signature_domain`; the registry selects it.

Task 4 defines four closed header/envelope families before Task 5's post-connect form exists:

| family | header required fields beyond constants | exact message branch(es) |
|---|---|---|
| enrollment App→Bridge | `protocol_version,message_schema,message_type,message_id,key_id,direction,issued_at,expires_at,payload_digest,enrollment_ticket_digest`; no sequence or paired identity/generation | `enrollment_response` |
| enrollment Bridge→App | same separate enrollment header; no sequence or paired identity/generation | `enrollment_challenge`, `enrollment_complete`, `enrollment_error` |
| connect hello | open `signed_header_base` plus exactly `device_id,pairing_generation`, closed at leaf; forbids `connection_generation` | `connect_hello` |
| connect welcome | same closed connect leaf; forbids `connection_generation` | `connect_welcome` |

Every family has the absolute header/envelope IDs listed in the catalog. Each message-specific envelope branch is exactly `{ header, payload, signature }`, closes with `unevaluatedProperties:false`, couples `message_type`, payload `message_schema` and registry `direction`, and requires an unpadded canonical 64-byte low-S P1363 signature. No header carries `signature_domain`; the registry selects it. Tests mutate every required leaf, inject every forbidden device/adapter/connection field, and swap every registered type/schema/direction combination before signature acceptance.

```ts
export interface ProtocolVersion {
  readonly major: bigint;
  readonly minor: bigint;
  readonly canonical: string;
}

export function parseProtocolVersion(input: string): ProtocolVersion;
export function compareProtocolVersions(a: ProtocolVersion, b: ProtocolVersion): -1 | 0 | 1;
export function loadVersionRegistry(): LockedVersionRegistry;
export function selectHighestCommonVersion(
  hello: VerifiedConnectHello,
  productionRegistry: LockedVersionRegistry,
): VersionSelection;
export function verifyWelcome(
  welcome: VerifiedConnectWelcome,
  hello: VerifiedConnectHello,
  productionRegistry: LockedVersionRegistry,
): VerifiedNegotiation;
```

Enrollment is a separate discriminated union: `enrollment_challenge`, `enrollment_response`, `enrollment_complete`, `enrollment_error`. Ticket, challenge, `client_nonce` and `bridge_nonce` are each exactly 32 random bytes carried as 43-character canonical unpadded base64url. `enrollment_ticket_digest = b64u(SHA-256(base64url_decode(ticket)))`; any other decoded length fails before lookup. The six payload branches are closed and exactly:

| payload `$id` suffix | required payload |
|---|---|
| `enrollment_challenge` | `{ challenge, bridge_nonce, bridge_fingerprint, bridge_command_public_jwk, supported_versions }` |
| `enrollment_response` | `{ ticket, challenge_response, device_public_jwk, client_nonce, supported_versions }` |
| `enrollment_complete` | `{ device_id, pairing_generation, tenant_id, human_principal_id, agent_instance_id, enrollment_scope_ceiling, selected_protocol, client_nonce, bridge_nonce, bridge_fingerprint, device_jwk_thumbprint }` |
| `enrollment_error` | closed union `{ code }` or `{ code:"RATE_LIMITED", retry_after_seconds:decimal_u64 }` over the coarse external errors below |
| `connect_hello` | `{ client_nonce, supported_versions, last_manifest_generation:decimal_u64|null, last_event_cursor:opaque_server_id|null }` |
| `connect_welcome` | `{ client_offer_digest, client_nonce, bridge_nonce, selected_protocol, bridge_time, command_key_set:{ current:p256_public_jwk, next:p256_public_jwk|null }, connection_generation }` |

Version arrays are non-empty and unique but preserve signed caller order. `enrollment_scope_ceiling` is sorted and unique but may be empty; scope names match `^[a-z][a-z0-9._-]{0,127}$`. `enrollment_complete` ownership/scope fields are copied only from the consumed Bridge record, are server-signed/display-only, and are forbidden in `enrollment_response`. `command_key_set.current.kid` equals the welcome header key ID; non-null `next` has a distinct key ID. Hello carries no Agent principal, operation/session, transport-mode claim or connection generation.

The trusted server intake and storage boundaries are:

```ts
declare const canonicalIp: unique symbol;
export type CanonicalIpAddress = string & { readonly [canonicalIp]: true };
export type LimitDecision =
  | { readonly allowed: true }
  | { readonly allowed: false; readonly retryAfterSeconds: string }; // decimal-u64 seconds
export interface TrustedEnrollmentIntake {
  readonly wire: Uint8Array;
  readonly remoteIp: CanonicalIpAddress; // trusted normalized listener metadata, never wire input
}
export interface EnrollmentTicketRecord {
  readonly ticketDigest: string;
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly agentInstanceId: string;
  readonly enrollmentScopeCeiling: readonly string[];
  readonly challenge: string;
  readonly bridgeId: string;
  readonly bridgeFingerprint: string;
  readonly bridgeCommandPublicJwk: P256PublicJwk;
  readonly bridgeNonce: string;
  readonly expiresAt: string;
}
export type EnrollmentTicketConsumeResult =
  | { readonly kind: "consumed"; readonly record: EnrollmentTicketRecord }
  | { readonly kind: "unknown" | "expired" | "already_consumed" };
export interface EnrollmentTicketStore {
  consume(ticketDigest: string, wallNow: Date): Promise<EnrollmentTicketConsumeResult>; // durable atomic CAS
}
export interface EnrollmentAttemptLimiter {
  admitIp(remoteIp: CanonicalIpAddress, monotonicNowMs: bigint): LimitDecision;
  admitPrincipal(humanPrincipalId: string, monotonicNowMs: bigint): LimitDecision;
}
export interface PendingEnrollment {
  readonly ticketDigest: string;
  readonly ownership: Readonly<{
    tenantId: string;
    humanPrincipalId: string;
    agentInstanceId: string;
    enrollmentScopeCeiling: readonly string[];
  }>;
  readonly devicePublicJwk: P256PublicJwk;
  readonly selectedProtocol: string;
  readonly transcript: PairingTranscript;
  readonly shortCode: string;
}
export type EnrollmentAdmissionError =
  | "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED"
  | "MESSAGE_EXPIRED" | "AUTH_BINDING_MISMATCH" | "RATE_LIMITED" | "VERSION_UNSUPPORTED";
export type NonRateEnrollmentAdmissionError = Exclude<EnrollmentAdmissionError, "RATE_LIMITED">;
export type EnrollmentAttemptResult =
  | { readonly ok: true; readonly pending: PendingEnrollment }
  | { readonly ok: false; readonly error: "RATE_LIMITED"; readonly retryAfterSeconds: string }
  | { readonly ok: false; readonly error: NonRateEnrollmentAdmissionError; readonly retryAfterSeconds?: never };
export function consumeEnrollmentAttempt(
  input: TrustedEnrollmentIntake,
  store: EnrollmentTicketStore,
  limiter: EnrollmentAttemptLimiter,
  clock: Clock,
): Promise<EnrollmentAttemptResult>;
```

Each applicable limiter method is invoked exactly once. In one atomic action it prunes retained admitted timestamps whose `timestamp + 600000 <= monotonicNowMs`; if fewer than five remain it appends the current timestamp exactly once and returns `{ allowed:true }`. Otherwise it appends nothing and returns `{ allowed:false, retryAfterSeconds }`, where `retryAfterSeconds = ceil((oldestRetainedTimestamp + 600000 - monotonicNowMs) / 1000)` encoded as a canonical decimal-u64 string. Denied calls never move or extend the retry horizon.

`consumeEnrollmentAttempt` is asynchronous and samples `Clock.wallNow()` and `Clock.monotonicNowMs()` once: wall time is used only for ticket/message expiry and monotonic time only for rate windows. It invokes `admitIp` exactly once before parsing and retains that decision, then enforces size and strict UTF-8/canonical JSON parsing; oversized/malformed inputs still invoke the limiter, an allowed decision appends once, a denied decision appends nothing, and `MESSAGE_TOO_LARGE` or `SCHEMA_INVALID` wins over either decision. Only after a successful canonical parse does a retained IP denial return `RATE_LIMITED`, without ticket-store, full-schema or signature work. An IP-allowed request then performs the sole bounded pre-schema exception: extract `payload.ticket`, canonical-decode exactly 32 bytes, derive its digest and await the store's durable atomic consume. Unknown, expired and replayed tickets are externally indistinguishable and create no successful consume; an active-but-expired record transitions atomically to terminal expired. A concurrency test must hold two distinct signed contenders at a store barrier, release both consume calls together, and prove the CAS linearizes to exactly one `consumed` winner and one `already_consumed` loser; sequential calls alone are insufficient evidence.

After a winning ticket CAS, intake invokes `admitPrincipal` exactly once and retains its decision, but does not return it yet. It continues full closed schema → locked registry/key/signature → constant-time payload digest → expiry → ticket/challenge/Bridge fingerprint/JWK/version binding validation. Any validation error at those stages wins; only when every stage succeeds may a retained principal denial return `RATE_LIMITED`. The ticket remains consumed in either case, and denied principal calls append no timestamp. Thus “success/failure consumes the ticket” means every attempt that reaches and wins the known-active-ticket CAS; earlier size/parse results, post-parse IP-rate rejection and failures before a known ticket is consumed are the explicit exceptions. No rejected attempt creates a device binding or invokes an external system.

External enrollment/admission errors are limited to `MESSAGE_TOO_LARGE`, `SCHEMA_INVALID`, `AUTH_FAILED`, `INTEGRITY_FAILED`, `MESSAGE_EXPIRED`, `AUTH_BINDING_MISMATCH`, `RATE_LIMITED`, `VERSION_UNSUPPORTED`; malformed UTF-8/JSON/noncanonical JSON map to `SCHEMA_INVALID`, and ticket unknown/expired/replay all map to `AUTH_FAILED`. Detailed parser/ticket disposition remains internal/test-only. `enrollment_error` is an exact closed `oneOf`: each non-rate code is exactly `{ code }` and forbids `retry_after_seconds`; `RATE_LIMITED` is exactly `{ code:"RATE_LIMITED", retry_after_seconds:decimal_u64 }`. TypeScript `retryAfterSeconds` and wire `retry_after_seconds` are always canonical decimal-u64 strings.

Bridge assigns both `device_id` and `pairing_generation`; the App cannot claim either. Re-pairing the same still-installed identity creates a new non-reusable `device_id` and increments the Bridge-held generation before revoking the old binding. Reinstalling creates a new installation identity and new device record; the old record remains revoked and no client-provided name/key can reclaim it.

`message-registry.ts` statically imports `messages.json`; `loadMessageRegistry()` takes no argument, validates it with `messages-registry.schema.json`, recursively deep-freezes it and returns opaque `LockedMessageRegistry`. The module's enrollment/connect admission functions close over that locked value and expose no registry parameter or fallback for unknown types:

```ts
declare const lockedMessageRegistry: unique symbol;
export type LockedMessageRegistry = Readonly<MessageRegistry> & {
  readonly [lockedMessageRegistry]: true;
};
export function loadMessageRegistry(): LockedMessageRegistry;
```

An unregistered message always returns `SCHEMA_INVALID`; it is never interpreted by payload resemblance or caller policy. Compile-time tests prove neither admission signature accepts a registry argument, and runtime tests prove an attacker-created/mutated registry cannot replace or extend the internally loaded frozen registry.

For `enrollment_challenge`, the payload JWK becomes usable only after its computed thumbprint constant-time matches the separately QR-pinned `bridge_fingerprint`; verifying a challenge with a merely self-supplied/self-signed payload key is forbidden. The admission boundary is exact and accepts raw wire only:

```ts
export type EnrollmentBridgeAdmissionContext =
  | {
      readonly phase: "challenge";
      readonly expectedTicketDigest: string;
      readonly expectedChallenge: string;
      readonly qrPinnedBridgeFingerprint: string;
      readonly clock: Clock;
    }
  | {
      readonly phase: "pinned";
      readonly expectedTicketDigest: string;
      readonly pendingTranscript: PairingTranscript;
      readonly verifier: Verifier;
      readonly expectedKeyId: string;
      readonly clock: Clock;
    };
export function verifyEnrollmentBridgeMessage(
  wire: Uint8Array,
  context: EnrollmentBridgeAdmissionContext,
): Promise<VerifiedEnrollmentChallenge | VerifiedEnrollmentComplete | VerifiedEnrollmentError>;
```

The challenge phase resolves trust only after the closed schema and locked registry tuple: it computes the payload `bridge_command_public_jwk` thumbprint, constant-time compares the QR pin, requires header `key_id` to equal that JWK's key ID, then uses Task 3 ES256 verification. The pinned phase applies only to `enrollment_complete`/`enrollment_error`, fixes signer role to `bridge-command`, resolves only `expectedKeyId` through `Verifier` and ignores any wire attempt to substitute a key. After expiry validation, every Bridge enrollment branch must match `header.enrollment_ticket_digest` to `expectedTicketDigest`; challenge must also match `expectedChallenge`, while complete must match the pending transcript's `client_nonce`, `bridge_nonce`, `bridge_fingerprint`, `device_jwk_thumbprint` and `selected_protocol`. Reusing a valid same-Bridge-key message across tickets therefore fails `AUTH_BINDING_MISMATCH`. After both enrollment signatures verify, each side independently canonicalizes `{ ticket_digest, bridge_fingerprint, challenge, client_nonce, bridge_nonce, device_jwk_thumbprint, selected_protocol }`, where `ticket_digest` is exactly the `enrollment_ticket_digest` above and both thumbprints are `b64u(SHA-256(JCS_UTF8({crv,kty,x,y})))`. It hashes the `open-android-intelligence/v1/pairing-short-code` length-prefixed domain preimage with SHA-256, takes the first 50 bits and renders ten uppercase Crockford Base32 characters as `XXXXX-XXXXX`. No ambiguous `I/L/O/U` characters are used. Enrollment completes only after the user confirms the two displayed codes match; every transcript field mutation has a fixed negative vector.

`message-registry.ts` admits connect messages before negotiation and returns opaque `VerifiedConnectHello`/`VerifiedConnectWelcome` brands that have no public constructor:

```ts
export interface ConnectMessageAdmissionContext {
  readonly verifier: Verifier;
  readonly expectedSignerRole: "device" | "bridge-command";
  readonly expectedKeyId: string;
  readonly expectedDeviceId: string;
  readonly expectedPairingGeneration: string;
  readonly clock: Clock;
}
export function verifyConnectMessage(
  wire: Uint8Array,
  expectedType: "connect_hello",
  context: ConnectMessageAdmissionContext,
): Promise<VerifiedConnectHello>;
export function verifyConnectMessage(
  wire: Uint8Array,
  expectedType: "connect_welcome",
  context: ConnectMessageAdmissionContext,
): Promise<VerifiedConnectWelcome>;
```

The hello overload requires signer role `device`; welcome requires `bridge-command`. Both enrollment-Bridge and connect admission use the fixed public order: size → strict UTF-8/canonical parse plus closed envelope/payload schema → internally locked registry type/schema/direction/domain → trusted key resolution and signature → constant-time payload digest → expiry → ticket or device/pairing/transcript binding. They expose no already-parsed public admission path. Only enrollment-response intake retains its explicitly bounded pre-full-schema ticket-extraction/CAS exception. Welcome verification uses only the already-pinned Bridge command key through Task 3 `Verifier`; it must never trust `welcome.payload.command_key_set` to verify the same welcome.

`connect_hello` and `connect_welcome` are the only canonical names. Protocol versions use canonical `major.minor` decimal components with no leading zeroes and numeric comparison; the server list comes only from `loadVersionRegistry()`. `client_offer_digest = b64u(SHA-256(JCS_UTF8(connect_hello.payload)))` over the entire validated closed hello payload, excluding header and signature, so version order and both resume fields are signed/digest-bound. `selectHighestCommonVersion` accepts only a branded hello and selects the numerically highest exact negotiable intersection. `verifyWelcome` is pure, accepts only branded hello/welcome plus `LockedVersionRegistry`, recomputes the digest from the retained hello, constant-time compares it, checks repeated client nonce and requires the same highest version. It returns `{ selected, clientOfferDigest, clientNonce, bridgeNonce, bridgeTime, commandKeySet, connectionGeneration }` as `VerifiedNegotiation`, mutates no state, and exposes only `INTEGRITY_FAILED` for offer-digest mismatch, `AUTH_BINDING_MISMATCH` for client-nonce mismatch, or `VERSION_UNSUPPORTED` for an invalid/non-highest selection.

`messages-registry.schema.json` defines the registry as `{ $schema, registry_id, protocol_version, messages }`; every message entry is exactly `{ message_type, direction, signature_domain, schema_id }`, with `unevaluatedProperties: false` at the top level and entry level. `schema_id` is the absolute `$id` of one closed message-specific payload branch, never a union root, header or envelope container. The Task 4 delta is exactly:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `enrollment_challenge` | `bridge-to-app` | `enrollment/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:enrollment_challenge` |
| `enrollment_response` | `app-to-bridge` | `enrollment/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:enrollment_response` |
| `enrollment_complete` | `bridge-to-app` | `enrollment/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:enrollment_complete` |
| `enrollment_error` | `bridge-to-app` | `enrollment/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:enrollment_error` |
| `connect_hello` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:connect_hello` |
| `connect_welcome` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:connect_welcome` |

`protocol/test/messages-registry.test.ts` owns a cumulative `expectedByTask` constant. At every Task 4–10 checkpoint it validates the registry schema, exact cumulative entry set, global message-type uniqueness, direction/domain signer-role matrix, profile-domain existence, and one-to-one resolution between every production payload-branch `$id` and registry entry. Each payload branch accepts its fixture and rejects every removed/retagged required field, wrong scalar/null type and unknown field; changing the outer header to any other registered `message_type`, `message_schema` or direction fails registry resolution before signature acceptance even when payload shapes match. It also rejects container/reducer/store/channel-frame names; requires proposal/ACK rotation pairs; checks `device_event` Agent/session-field injection; and, after Task 7, proves the current-generation `receipt_replay` outer signature preserves the inner receipt bytes/signature. Later tasks modify this same test before their RED run. The registry is frozen as immutable v1 only by Task 13's spec lock; until then any unregistered type remains rejected.

`versions-registry.schema.json` closes the exact registry shape, and `versions.json` is exactly:

```json
{
  "$schema": "urn:open-android-intelligence:protocol:v1:versions-registry",
  "registry_id": "urn:open-android-intelligence:protocol:v1:registry:versions",
  "protocol_version": "1.0",
  "versions": [
    { "version": "1.0", "negotiable": true },
    { "version": "0.9", "negotiable": false, "fixture_owner": "Task7" }
  ]
}
```

```ts
export interface VersionRegistryEntry {
  readonly version: string;
  readonly negotiable: boolean;
  readonly fixture_owner?: "Task7";
}
declare const lockedVersionRegistry: unique symbol;
export type LockedVersionRegistry = Readonly<{
  readonly $schema: "urn:open-android-intelligence:protocol:v1:versions-registry";
  readonly registry_id: "urn:open-android-intelligence:protocol:v1:registry:versions";
  readonly protocol_version: "1.0";
  readonly versions: readonly VersionRegistryEntry[];
  readonly [lockedVersionRegistry]: true;
}>;
export interface VersionSelection { readonly selected: string; readonly clientOfferDigest: string }
export interface VerifiedNegotiation {
  readonly selected: string;
  readonly clientOfferDigest: string;
  readonly clientNonce: string;
  readonly bridgeNonce: string;
  readonly bridgeTime: string;
  readonly commandKeySet: Readonly<{ current: P256PublicJwk; next: P256PublicJwk | null }>;
  readonly connectionGeneration: string;
}
```

`loadVersionRegistry()` takes no caller value, validates this static file, recursively deep-freezes it and returns opaque `LockedVersionRegistry`; production negotiation functions cannot accept arbitrary registry-shaped objects. No Task 4 code may migrate an operation before the v1 operation contract exists. `versioning-and-migration.md` freezes: old schema files/signatures are immutable; patch changes cannot alter accepted bytes/state semantics; additive negotiated messages require a new minor line; canonicalization, signature, identity, revision, state or replay changes require a major line; migrations create a new record plus signed migration receipt while retaining original schema/digest/signature; and rollback never lowers pairing generation, authorization/scope/grant revisions, revocation or tombstone state. Task 7 alone owns the executable `0.9` fixture and migration reducer.

Negative vectors must additionally prove: concurrent ticket CAS has one winner; every request invokes `admitIp` once, allowed calls append once and denied calls append nothing; a sixth valid canonical request parses then returns `RATE_LIMITED` without ticket-store/full-schema/signature work, while a sixth oversized or malformed request still returns `MESSAGE_TOO_LARGE` or `SCHEMA_INVALID`; repeated denied calls leave both retained-timestamp count and `retryAfterSeconds` unchanged at a fixed monotonic instant and never extend the horizon, and admission succeeds when the oldest original admitted timestamp expires despite continuous denied calls. After a winning CAS, principal admission is invoked once; principal-denied plus invalid schema/signature returns the earlier validation error while the ticket remains consumed, and only a fully valid attempt exposes the retained principal `RATE_LIMITED`. Also prove unknown/expired/replayed tickets share one external result; wall-clock changes do not alter rate windows and monotonic changes do not expire tickets; every payload/header/envelope leaf is required and closed; type/schema/direction swaps fail before signature acceptance; compile-time and runtime callers cannot inject an attacker-created registry; QR-pinned/pinned Bridge keys defeat a self-supplied challenge or welcome `command_key_set`; same-Bridge-key messages cannot cross ticket/transcript bindings; non-rate enrollment errors reject `retry_after_seconds` while rate errors require a decimal string; and changes to either nonce, offer ordering, `last_manifest_generation`, `last_event_cursor`, offer digest or selected downgrade fail deterministically with only the public errors fixed above.

- [ ] **Step 4: Run enrollment/schema tests**

Run: `tools/run-node24 npm test -- protocol/test/schema-validator.test.ts protocol/test/enrollment.test.ts protocol/test/version-negotiation.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: all ownership, short-code, rate-limit, expiry, union, canonical-version, downgrade and registry cases pass.

- [ ] **Step 5: Commit enrollment and negotiation**

```bash
git add docs/protocol/v1/enrollment.md docs/protocol/v1/versioning-and-migration.md protocol/schemas/v1 protocol/registries/v1 protocol/src protocol/test
git commit -m "feat(protocol): define enrollment and negotiation"
```

### Task 5: Implement control envelopes, replay windows, fencing and key rotation

**Files:**
- Create: `docs/protocol/v1/control-envelope.md`
- Create: `protocol/schemas/v1/control-envelope.schema.json`
- Create: `protocol/schemas/v1/key-rotation.schema.json`
- Modify: `protocol/registries/v1/messages.json`
- Modify: `protocol/src/schema-catalog.ts`
- Create: `protocol/src/control-envelope.ts`
- Create: `protocol/src/outbound-envelope.ts`
- Create: `protocol/src/replay-window.ts`
- Create: `protocol/src/connection-fence.ts`
- Create: `protocol/src/key-ring.ts`
- Create: `protocol/src/adapter-admission.ts`
- Modify: `protocol/test/schema-validator.test.ts`
- Create: `protocol/test/control-envelope.test.ts`
- Create: `protocol/test/outbound-envelope.test.ts`
- Create: `protocol/test/replay-window.test.ts`
- Create: `protocol/test/connection-fence.test.ts`
- Create: `protocol/test/key-ring.test.ts`
- Create: `protocol/test/adapter-admission.test.ts`
- Modify: `protocol/test/messages-registry.test.ts`

**Interfaces:**
- Produces: `prepareSignedEnvelope`, `prepareRotationEnvelope`, `verifySignedEnvelope`, `verifyTransportFrame`, `verifyAuthenticatedBinding`, `verifyAdapterAdmission`, `ExactWireBytes`, `SendableOutboundEnvelope`, `PreparedRotationHandle`, `AcceptedTransportFrame`, `AuthenticatedIngressHandle`, `DeviceCredentialRecord`, `AuthenticatedBindingContext`, `AuthenticatedAdapterPrincipal`, `AdapterCredentialRecord`, `AdapterCredentialLease`, `EnvelopeAuthorizationGate`, `OutboundEnvelopeStore`, `TransportSender`, `ReplayStore`, `ReplaySpace`, `ReplayClaim`, `ReplayRecoveryIntent`, `LockedReplayRegistryIdentity`, `LockedReplayPolicy`, `REPLAY_POLICY_LITERALS`, `LOCKED_REPLAY_POLICY_DESCRIPTORS`, `ReplayLeasePersistenceId`, `PersistedReplayIntentMetadata`, `ReplayIntentMetadataAuthority`, `projectPersistedReplayIntentMetadata`, `canonicalReplayIntentMetadataBytes`, `TrustedReplayReconciler`, `DeviceReplayAdmissionStore`, `AdapterReplayAdmissionStore`, `acceptSequence`, `ConnectionFenceStore`, `ConnectionLease`, `allocateConnectionGeneration`, `fenceConnection`, `KeyRingStore`, `DeviceRotationStore`, `AdapterRotationStore`, `applyKeyRotation`, `RotationActivationFact`.
- Consumes: Task 3 crypto and Task 4 schema/version/identity/revision primitives; the concrete authorization oracle remains an injected `EnvelopeAuthorizationGate` until Task 6.

- [ ] **Step 1: Write failing replay/fencing/rotation tests**

```ts
it("shares a 1024-slot replay window across trusted HTTPS and WSS metadata", async () => {
  const first = await verifyTransportFrame(devicePing("1024"), trustedDeviceTransport("https"), dependencies());
  const second = await verifyTransportFrame(devicePing("1023"), trustedDeviceTransport("wss"), dependencies());
  const replay = await verifyTransportFrame(devicePing("1023"), trustedDeviceTransport("https"), dependencies());
  expect(first).toMatchObject({ ok: true, kind: "accepted" });
  expect(second).toMatchObject({ ok: true, kind: "accepted" });
  expect(replay).toEqual({ ok: false, error: "REPLAY_REJECTED" });
});

it("fences old and unallocated future connection generations", async () => {
  await expect(verifyTransportFrame(bridgePingAtGeneration(7n), trustedConnectionAt(8n), dependencies()))
    .resolves.toEqual({ ok: false, error: "CONNECTION_FENCED" });
  await expect(verifyTransportFrame(bridgePingAtGeneration(9n), trustedConnectionAt(8n), dependencies()))
    .resolves.toEqual({ ok: false, error: "CONNECTION_FENCED" });
});
```

Before implementation, add closed-schema matrices for all nine payload, all nine named header and all nine named envelope leaves: accept one exact fixture, then independently remove every required field, replace every non-null value with `null` and a wrong non-null JSON type, inject every forbidden identity/transport/operation field, and swap every registered type/schema/direction tuple before signature work. Extend the static-catalog test with both document roots plus exactly 27 leaf `$id`s (nine message + nine header + nine envelope), a valid fixture for each, no `$id` on the internal family shells, and exact catalog cardinality/import-count assertions. For each type `T`, prove `envelope:T` references both `header:T` and `message:T`; stage 2 accepts only the trusted-family internal shell, stages 3/4 lock the registry tuple, and stage 5 rejects the complete wrong `envelope:T` before key lookup.

Put barriers inside `DeviceReplayAdmissionStore.admitDevice` and `AdapterReplayAdmissionStore.admitAdapter` immediately before their serializable commits. For each branch, two identical contenders must produce exactly one `AcceptedTransportFrame`/stable claim and one `PENDING`/`REPLAY_REJECTED`; the loser is never re-executed. Two different admissible sequences both succeed in serialization order without caller snapshot/CAS logic. The winning transaction must atomically persist the `pending` row and its mandatory recovery intent; no committed pending row may exist without that intent. Crash immediately after stage 11 commits but before any rotation journal call, restart without supplying retry bytes, and prove a branded reconciler can reconstruct the same opaque `AcceptedTransportFrame` solely from the intent's immutable exact input bytes, accepted binding snapshot, device connection lease or adapter credential lease, `admittedAt`, `claimId`, locked registry/schema identity and byte-identical closed `PersistedReplayIntentMetadata`, then finish the journal. Ordinary ingress still sees `PENDING` and cannot infer permission for an external side effect. After `store.finalize(frame.claim, receipt)`, the exact canonical wire returns byte-identical defensive receipt bytes even after its sequence bit leaves the 1,024-slot window; the same `message_id` with any different full-envelope byte returns `INTEGRITY_FAILED`. Add bitmap boundary (`delta/offset` 1023/1024), huge-bigint jump without `Number` overflow, decimal-u64 exhaustion, direction/key/pairing-generation isolation and HTTPS/WSS reorder cases.

Fill a space to the exact 4,096-row and 67,108,864-retained-byte limits. Prove admission first removes only finalized/abandoned, retention-expired, outside-window, unreferenced rows; never removes pending/current-window/journal/outbox/history-referenced rows; charges every retained inbound `ExactWireBytes`, the exact `canonicalReplayIntentMetadataBytes(persistedMetadata).byteLength`, and a 16,384-byte receipt reservation per accepted Task 5 claim; rejects empty/oversize finalize; and returns `CAPACITY_EXHAUSTED` internally/`REPLAY_REJECTED` publicly with zero mutation when safe compaction cannot make room. Check row `expiresAt`, `retentionUntil`, status, raw-input bytes, intent metadata bytes, receipt reservation/actual bytes and total retained-byte accounting across restart and every admit/finalize/abandon/compact crash cut.

Add a second barrier test for two concurrent authenticated reconnects from current generation 7: atomic allocation returns opaque leases for 8/revision 8 and 9/revision 9, persisted current is bound to generation 9's exact connection/profile, and lease 8 is fenced before authorization/replay. Then pause a generation-8 frame after a successful stage-8 `inspect`, allocate generation 9, resume stage 11, and require the atomic device-admission transaction to return `CONNECTION_FENCED` with no replay lookup/window/row delta. Cover Tailnet/public-profile and HTTPS/WSS use of the same fence key, connection/profile lease substitution, lost welcome/reconnect, restart persistence and max-u64 exhaustion without wrap. Add the equivalent adapter barrier: load an opaque adapter lease from the credential/principal snapshot, pause after stage 8, commit authoritative adapter generation rotation, then require stage 11 to return `CONNECTION_FENCED` before replay lookup and with zero replay delta; the opposite serialization order admits exactly once and leaves a recoverable accepted intent. Compile-time tests prove neither specialized store exposes a union `admit`, `admitDevice` rejects an adapter request, `admitAdapter` rejects a device request, and `verifyTransportFrame` cannot pair a branch handle with another backend or inject a separate loader. Runtime capability-substitution tests use two valid backend instances and prove a binding/lease minted by one is fenced by the other before replay lookup.

Add outbound crash barriers before reservation, after reservation, after signing, after same-`KeyRecord.publicJwk` self-verification, after durable byte retention and immediately before the rotation-journal commit. Prove no uncommitted bytes escape, retry either consumes a permitted gap or reloads exact immutable bytes, the same message ID never re-signs, byte-buffer mutation cannot change the store, wrong-key signer output fails self-verification with no row, and max-u64 returns `SEQUENCE_EXHAUSTED`. Ordinary non-rotation preparation returns exact-byte `SendableOutboundEnvelope`; rotation preparation returns only an opaque `PreparedRotationHandle` without byte-copy access. Compile-time and runtime tests prove the transport sender accepts only `SendableOutboundEnvelope`, and that a rotation envelope becomes sendable only after the matching `DeviceRotationStore` or `AdapterRotationStore` journal commit. Inbound verification tests provide an alternate same-ID resolver/JWK and prove only the lifecycle-authorizing `KeyRecord.publicJwk` is used.

Add rotation transition/concurrency/crash-cut tests for local prepare, remote proposal, durable ACK, activation, grace retirement, revocation and trusted recovery. Mutate every transcript field and all three ACK `proposal_digest`s, including digesting payload-only, signable bytes or Base64 text instead of the exact full signed proposal. Use proposal/ACK frames from the wrong credential/direction/role/domain; compile-time tests reject raw bytes, bare `VerifiedSignedEnvelope`, structural brands, a device `KeyRotationInput` passed to `AdapterRotationStore`, and an adapter input/frame passed to `DeviceRotationStore`. Race two device rotations across a stage-8/commit barrier and prove lease recheck + replay finalization + journal/outbox/ring mutation are one device-backend transaction with zero partial state on fencing or crash. Race two adapter next-generation commits under the equivalent adapter transaction, replay exact proposal/ACK, and crash before/after commit. Prove exact ACK recovery, stable claims, one fact/unique adapter generation, new-key signing only after ACK, old-key verify-only grace for at most 900 seconds, archived historical verification and lost device/Bridge key re-pair versus adapter reissue. Adapter tests also reject missing principals, payload authority injection and old/future generation before authorization/replay.

For every admission-stage pair, inject two simultaneous faults and assert the earlier public result from the fixed precedence below. Assert all failures through authorization call invoke neither branch replay-admission method nor cache/compaction, transport metadata is never encoded, `Clock.wallNow()` is sampled exactly once per admission and reused for key grace/expiry, and no dependency later than the winning stage is called. Test future-skew and lifetime boundaries at -1/0/+1 millisecond for 60-second ping/presence and 300-second rotation limits. Mutate the source binding/scope arrays after load and prove the sorted, copied, frozen scope ceiling and opaque brands retain authority. Task 5 fixes TypeScript canonical-byte/preimage fixtures only; Task 11 promotes them into the manifest and Task 12 is the first task that runs the independent Kotlin consumer.

- [ ] **Step 2: Run tests and verify missing implementations fail**

Run: `tools/run-node24 npm test -- protocol/test/schema-validator.test.ts protocol/test/control-envelope.test.ts protocol/test/outbound-envelope.test.ts protocol/test/replay-window.test.ts protocol/test/connection-fence.test.ts protocol/test/key-ring.test.ts protocol/test/adapter-admission.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL on missing control/replay/key-ring modules.

- [ ] **Step 3: Implement the control union and durable-state interfaces**

```ts
export type Task5MessageType =
  | "device_ping" | "bridge_ping" | "device_presence"
  | "device_key_rotation" | "device_key_rotation_ack"
  | "bridge_key_rotation" | "bridge_key_rotation_ack"
  | "adapter_key_rotation" | "adapter_key_rotation_ack";

export const TASK5_MAX_LIFETIME_SECONDS: Readonly<Record<Task5MessageType, 60 | 300>> = Object.freeze({
  device_ping: 60,
  bridge_ping: 60,
  device_presence: 60,
  device_key_rotation: 300,
  device_key_rotation_ack: 300,
  bridge_key_rotation: 300,
  bridge_key_rotation_ack: 300,
  adapter_key_rotation: 300,
  adapter_key_rotation_ack: 300,
});

export type Task5AdmissionError =
  | "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED"
  | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "CONNECTION_FENCED"
  | "AUTH_BINDING_MISMATCH" | "ADAPTER_PRINCIPAL_MISSING"
  | "NOT_AUTHORIZED" | "REPLAY_REJECTED";

export interface DeviceCredentialRecord {
  readonly credentialId: string;
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly pairingGeneration: bigint;
  readonly active: boolean;
}

export interface AdapterCredentialRecord {
  readonly credentialId: string;
  readonly generation: bigint;
  readonly tenantId: string;
  readonly agentPrincipalId: string | null;
  readonly agentInstanceId: string;
  readonly workspaceId: string;
  readonly scopeCeiling: readonly string[];
  readonly active: boolean;
}

export interface AuthenticatedAdapterPrincipal {
  readonly humanPrincipalId: string | null;
  readonly agentPrincipalId: string | null;
}

export type AuthenticatedIngressKind = "device" | "adapter";
declare const authenticatedIngressHandleBrand: unique symbol;
export type AuthenticatedIngressHandle<K extends AuthenticatedIngressKind = AuthenticatedIngressKind> = Readonly<{
  readonly kind: K;
  readonly handleId: string;
  readonly [authenticatedIngressHandleBrand]: true;
}>;

declare const loadedTrustedBindingBrand: unique symbol;
export type LoadedTrustedBinding =
  | {
      readonly kind: "device";
      readonly transport: "https" | "wss";
      readonly transportProfileId: string;
      readonly connectionId: string;
      readonly allocatedConnectionGeneration: bigint;
      readonly connectionLease: ConnectionLease;
      readonly credential: DeviceCredentialRecord;
      readonly [loadedTrustedBindingBrand]: true;
    }
  | {
      readonly kind: "adapter";
      readonly connectionId: string;
      readonly credential: AdapterCredentialRecord;
      readonly principal: AuthenticatedAdapterPrincipal;
      readonly credentialLease: AdapterCredentialLease;
      readonly [loadedTrustedBindingBrand]: true;
    };

export type ReplayDecision =
  | { kind: "accept"; next: ReplayWindowState }
  | { kind: "reject"; error: "REPLAY_REJECTED" };

declare const authenticatedBindingContextBrand: unique symbol;
export type AuthenticatedBindingContext =
  | Readonly<{
      kind: "device";
      credentialId: string;
      tenantId: string;
      humanPrincipalId: string;
      deviceId: string;
      pairingGeneration: bigint;
      connectionGeneration: bigint;
      direction: "app-to-bridge" | "bridge-to-app";
      [authenticatedBindingContextBrand]: true;
    }>
  | Readonly<{
      kind: "adapter";
      credentialId: string;
      adapterCredentialGeneration: bigint;
      tenantId: string;
      humanPrincipalId: string;
      agentPrincipalId: string;
      agentInstanceId: string;
      workspaceId: string;
      scopeCeiling: readonly string[];
      direction: "adapter-to-bridge" | "bridge-to-adapter";
      [authenticatedBindingContextBrand]: true;
    }>;

export type ReplaySpace =
  | { kind: "device"; credentialId: string; pairingGeneration: bigint; keyId: string; direction: "app-to-bridge" | "bridge-to-app" }
  | { kind: "adapter"; credentialId: string; adapterCredentialGeneration: bigint; keyId: string; direction: "adapter-to-bridge" | "bridge-to-adapter" };

export interface EnvelopeAuthorizationGate {
  evaluate(messageType: string, payload: unknown, context: AuthenticatedBindingContext):
    | { allowed: true }
    | { allowed: false; denial: "PAIRING_INACTIVE" | "SCOPE_DENIED" | "REVISION_MISMATCH" | "POLICY_BLOCKED" };
}

export interface ReplayWindowState {
  readonly highestSeen: bigint | null;
  readonly seenBitmap: bigint;
}

export type ReplayRowStatus = "pending" | "finalized" | "abandoned";

declare const lockedReplayPolicyBrand: unique symbol;
export const REPLAY_POLICY_LITERALS = Object.freeze({
  task5Default: Object.freeze({
    class_id: "task5_default",
    retention_rule_id: "retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1",
  } as const),
  operationSecurityLedger: Object.freeze({
    class_id: "operation_security_ledger",
    retention_rule_id: "retain_until_max_operation_expires_at_or_bridge_ack_at_plus_2592000_seconds_v1",
  } as const),
} as const);

export type PersistedReplayPolicy =
  | typeof REPLAY_POLICY_LITERALS.task5Default
  | typeof REPLAY_POLICY_LITERALS.operationSecurityLedger;

export const LOCKED_REPLAY_POLICY_DESCRIPTORS = Object.freeze({
  task5Default: Object.freeze({
    classId: REPLAY_POLICY_LITERALS.task5Default.class_id,
    receiptReservationBytes: 16384,
    retentionRuleId: REPLAY_POLICY_LITERALS.task5Default.retention_rule_id,
    intentMetadataCeilingBytes: null,
    tombstoneMetadataCeilingBytes: null,
  } as const),
  operationSecurityLedger: Object.freeze({
    classId: REPLAY_POLICY_LITERALS.operationSecurityLedger.class_id,
    receiptReservationBytes: 262144,
    retentionRuleId: REPLAY_POLICY_LITERALS.operationSecurityLedger.retention_rule_id,
    intentMetadataCeilingBytes: 65536,
    tombstoneMetadataCeilingBytes: 2048,
  } as const),
} as const);

type LockedReplayPolicyDescriptor =
  | typeof LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default
  | typeof LOCKED_REPLAY_POLICY_DESCRIPTORS.operationSecurityLedger;

export type LockedReplayPolicy = Readonly<LockedReplayPolicyDescriptor & {
  readonly [lockedReplayPolicyBrand]: true;
}>;

export interface ReplayRow<TMessageType extends string = Task5MessageType> {
  readonly claimId: string;
  readonly space: ReplaySpace;
  readonly messageType: TMessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly envelopeDigest: string;
  readonly expiresAt: string;
  readonly retentionUntil: string;
  readonly status: ReplayRowStatus;
  readonly replayPolicy: LockedReplayPolicy;
  readonly receiptReservationBytes: LockedReplayPolicy["receiptReservationBytes"];
  readonly inboundRawByteLength: number;
  readonly intentMetadataByteLength: number;
  readonly rawWire: ExactWireBytes;
  readonly receipt: ExactWireBytes | null;
  readonly admittedAt: string;
  readonly recoveryIntent: ReplayRecoveryIntent<TMessageType>;
  readonly createdAt: string;
  readonly updatedAt: string;
}

declare const replayClaimBrand: unique symbol;
export type ReplayClaim<TMessageType extends string = Task5MessageType> = Readonly<{
  readonly claimId: string;
  readonly space: ReplaySpace;
  readonly messageType: TMessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly envelopeDigest: string;
  readonly expiresAt: string;
  readonly retentionUntil: string;
  readonly replayPolicy: LockedReplayPolicy;
  [replayClaimBrand]: true;
}>;

declare const replayRecoveryIntentBrand: unique symbol;
export interface LockedReplayRegistryIdentity<TMessageType extends string = Task5MessageType> {
  readonly messageType: TMessageType;
  readonly messageSchemaId: string;
  readonly headerSchemaId: string;
  readonly envelopeSchemaId: string;
  readonly direction: ReplaySpace["direction"];
  readonly signatureDomain: SignatureDomain;
  readonly signerRole: SignerRole;
}

export type PersistedReplayBindingSnapshot =
  | Readonly<{
      adapter_credential_generation: null;
      agent_instance_id: null;
      agent_principal_id: null;
      connection_generation: string;
      credential_id: string;
      device_id: string;
      direction: "app-to-bridge" | "bridge-to-app";
      human_principal_id: string;
      kind: "device";
      pairing_generation: string;
      scope_ceiling: null;
      tenant_id: string;
      workspace_id: null;
    }>
  | Readonly<{
      adapter_credential_generation: string;
      agent_instance_id: string;
      agent_principal_id: string;
      connection_generation: null;
      credential_id: string;
      device_id: null;
      direction: "adapter-to-bridge" | "bridge-to-adapter";
      human_principal_id: string;
      kind: "adapter";
      pairing_generation: null;
      scope_ceiling: readonly string[];
      tenant_id: string;
      workspace_id: string;
    }>;

export type PersistedReplayLeaseRef =
  | Readonly<{
      adapter_credential_lease_id: null;
      connection_lease_id: string;
      kind: "device_connection";
    }>
  | Readonly<{
      adapter_credential_lease_id: string;
      connection_lease_id: null;
      kind: "adapter_credential";
    }>;

declare const replayLeasePersistenceIdBrand: unique symbol;
export type ReplayLeasePersistenceId = string & {
  readonly [replayLeasePersistenceIdBrand]: true;
};

export type PersistedReplayRegistryIdentity<TMessageType extends string = Task5MessageType> = Readonly<{
  direction: ReplaySpace["direction"];
  envelope_schema_id: string;
  header_schema_id: string;
  message_schema_id: string;
  message_type: TMessageType;
  signature_domain: SignatureDomain;
  signer_role: SignerRole;
}>;

export type PersistedReplaySpace =
  | Readonly<{
      adapter_credential_generation: null;
      credential_id: string;
      direction: "app-to-bridge" | "bridge-to-app";
      key_id: string;
      kind: "device";
      pairing_generation: string;
    }>
  | Readonly<{
      adapter_credential_generation: string;
      credential_id: string;
      direction: "adapter-to-bridge" | "bridge-to-adapter";
      key_id: string;
      kind: "adapter";
      pairing_generation: null;
    }>;

type PersistedReplayIntentMetadataBase<TMessageType extends string> = Readonly<{
  admitted_at: string;
  claim_id: string;
  registry_identity: PersistedReplayRegistryIdentity<TMessageType>;
  replay_policy: PersistedReplayPolicy;
  retention_until: string;
}>;

export type PersistedReplayIntentMetadata<TMessageType extends string = Task5MessageType> = Readonly<
  PersistedReplayIntentMetadataBase<TMessageType> & (
    | {
        binding_snapshot: Extract<PersistedReplayBindingSnapshot, { kind: "device" }>;
        lease_ref: Extract<PersistedReplayLeaseRef, { kind: "device_connection" }>;
        space: Extract<PersistedReplaySpace, { kind: "device" }>;
      }
    | {
        binding_snapshot: Extract<PersistedReplayBindingSnapshot, { kind: "adapter" }>;
        lease_ref: Extract<PersistedReplayLeaseRef, { kind: "adapter_credential" }>;
        space: Extract<PersistedReplaySpace, { kind: "adapter" }>;
      }
  )
>;

declare const replayIntentMetadataAuthorityBrand: unique symbol;
export type ReplayIntentMetadataAuthority<TMessageType extends string = Task5MessageType> = Readonly<
  {
    admittedAt: string;
    claimId: string;
    registryIdentity: LockedReplayRegistryIdentity<TMessageType>;
    replayPolicy: LockedReplayPolicy;
    retentionUntil: string;
    readonly [replayIntentMetadataAuthorityBrand]: true;
  } & (
    | {
        adapterCredentialLease: null;
        adapterCredentialLeasePersistenceId: null;
        bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "device" }>;
        connectionLease: ConnectionLease;
        connectionLeasePersistenceId: ReplayLeasePersistenceId;
        space: Extract<ReplaySpace, { kind: "device" }>;
      }
    | {
        adapterCredentialLease: AdapterCredentialLease;
        adapterCredentialLeasePersistenceId: ReplayLeasePersistenceId;
        bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
        connectionLease: null;
        connectionLeasePersistenceId: null;
        space: Extract<ReplaySpace, { kind: "adapter" }>;
      }
  )
>;

export function projectPersistedReplayIntentMetadata<TMessageType extends string>(
  authority: ReplayIntentMetadataAuthority<TMessageType>,
): PersistedReplayIntentMetadata<TMessageType>;

export function canonicalReplayIntentMetadataBytes<TMessageType extends string>(
  metadata: PersistedReplayIntentMetadata<TMessageType>,
): Uint8Array;

export type ReplayRecoveryIntent<TMessageType extends string = Task5MessageType> =
  | Readonly<{
      readonly claimId: string;
      readonly rawWire: ExactWireBytes;
      readonly registryIdentity: LockedReplayRegistryIdentity<TMessageType>;
      readonly replayPolicy: LockedReplayPolicy;
      readonly bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "device" }>;
      readonly connectionLease: ConnectionLease;
      readonly adapterCredentialLease: null;
      readonly admittedAt: string;
      readonly persistedMetadata: Extract<PersistedReplayIntentMetadata<TMessageType>, {
        binding_snapshot: { kind: "device" };
      }>;
      readonly [replayRecoveryIntentBrand]: true;
    }>
  | Readonly<{
      readonly claimId: string;
      readonly rawWire: ExactWireBytes;
      readonly registryIdentity: LockedReplayRegistryIdentity<TMessageType>;
      readonly replayPolicy: LockedReplayPolicy;
      readonly bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
      readonly connectionLease: null;
      readonly adapterCredentialLease: AdapterCredentialLease;
      readonly admittedAt: string;
      readonly persistedMetadata: Extract<PersistedReplayIntentMetadata<TMessageType>, {
        binding_snapshot: { kind: "adapter" };
      }>;
      readonly [replayRecoveryIntentBrand]: true;
    }>;

declare const trustedReplayReconcilerBrand: unique symbol;
export type TrustedReplayReconciler = Readonly<{
  readonly reconcilerId: string;
  readonly [trustedReplayReconcilerBrand]: true;
}>;

export interface ReplayCapacityLimits {
  readonly maxRowsPerSpace: 4096;
  readonly maxRetainedBytesPerSpace: 67108864;
}

export const TASK5_REPLAY_LIMITS: ReplayCapacityLimits = Object.freeze({
  maxRowsPerSpace: 4096,
  maxRetainedBytesPerSpace: 67108864,
});
export const TASK5_RECEIPT_BYTE_BUDGET =
  LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default.receiptReservationBytes;

interface ReplayAdmissionBase {
  readonly envelope: VerifiedSignedEnvelope<string>;
  readonly admittedAt: string;
}

export type DeviceReplayAdmissionRequest<TMessageType extends string = Task5MessageType> = ReplayAdmissionBase & {
  readonly envelope: VerifiedSignedEnvelope<TMessageType>;
  readonly context: Extract<AuthenticatedBindingContext, { kind: "device" }>;
  readonly connectionLease: ConnectionLease;
};

export type AdapterReplayAdmissionRequest<TMessageType extends string = Task5MessageType> = ReplayAdmissionBase & {
  readonly envelope: VerifiedSignedEnvelope<TMessageType>;
  readonly context: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
  readonly adapterCredentialLease: AdapterCredentialLease;
};

export type OrdinaryReplayCapacityDenial =
  { kind: "rejected"; error: "REPLAY_REJECTED"; denial: "CAPACITY_EXHAUSTED" };
export type SecurityReplayCapacityDenial =
  { kind: "rejected"; error: "SECURITY_LEDGER_FULL"; denial: "SECURITY_PARTITION_EXHAUSTED" };

export type StoreReplayDecision<
  TMessageType extends string = Task5MessageType,
  TCapacityDenial extends OrdinaryReplayCapacityDenial | SecurityReplayCapacityDenial = OrdinaryReplayCapacityDenial,
> =
  | { kind: "accepted"; frame: AcceptedTransportFrame<TMessageType> }
  | { kind: "duplicate"; cachedReceipt: ExactWireBytes }
  | { kind: "rejected"; error: "CONNECTION_FENCED"; denial: "DEVICE_LEASE_STALE" | "ADAPTER_LEASE_STALE" }
  | { kind: "rejected"; error: "INTEGRITY_FAILED"; denial: "MESSAGE_ID_CONFLICT" }
  | { kind: "rejected"; error: "REPLAY_REJECTED"; denial: "PENDING" | "WINDOW_REJECTED" }
  | TCapacityDenial;

export interface ReplayStore<TMessageType extends string = Task5MessageType> {
  finalize(claim: ReplayClaim<TMessageType>, receipt: ExactWireBytes): Promise<
    | { kind: "stored" | "same" }
    | { kind: "rejected"; error: "INTEGRITY_FAILED" }
  >;
  loadPending(reconciler: TrustedReplayReconciler, claimId: string): Promise<
    | { kind: "pending"; frame: AcceptedTransportFrame<TMessageType> }
    | { kind: "not_found" | "not_pending" }
  >;
  resumePending(reconciler: TrustedReplayReconciler, claimId: string): Promise<
    | { kind: "resumed"; frame: AcceptedTransportFrame<TMessageType> }
    | { kind: "not_found" | "not_pending" }
  >;
  abandonPending(reconciler: TrustedReplayReconciler, claimId: string): Promise<
    | { kind: "abandoned" | "same" }
    | { kind: "not_found" | "already_finalized" }
  >;
  compact(reconciler: TrustedReplayReconciler, space: ReplaySpace, clock: Clock): Promise<{
    readonly removedRows: bigint;
    readonly removedRetainedBytes: bigint;
  }>;
}

export interface ConnectionFenceKey {
  readonly credentialId: string;
  readonly pairingGeneration: bigint;
}

declare const connectionLeaseBrand: unique symbol;
export type ConnectionLease = Readonly<{
  readonly [connectionLeaseBrand]: true;
}>;

export interface ConnectionAllocation {
  readonly generation: bigint;
  readonly fenceRevision: bigint;
  readonly lease: ConnectionLease;
}

export type ConnectionLeaseInspection =
  | { kind: "current"; generation: bigint; fenceRevision: bigint }
  | { kind: "fenced" };

export interface ConnectionFenceStore {
  allocateNext(
    key: ConnectionFenceKey,
    connectionId: string,
    transportProfileId: string,
  ): Promise<
    | { kind: "allocated"; allocation: ConnectionAllocation }
    | { kind: "exhausted" }
  >;
  inspect(lease: ConnectionLease): Promise<ConnectionLeaseInspection>;
}

declare const deviceAdmissionBackendBrand: unique symbol;
export interface DeviceReplayAdmissionStore<
  TMessageType extends string = Task5MessageType,
  TCapacityDenial extends OrdinaryReplayCapacityDenial | SecurityReplayCapacityDenial = OrdinaryReplayCapacityDenial,
> extends ReplayStore<TMessageType>, ConnectionFenceStore {
  readonly [deviceAdmissionBackendBrand]: true;
  loadCommittedDeviceBinding(handle: AuthenticatedIngressHandle<"device">): Promise<
    Extract<LoadedTrustedBinding, { kind: "device" }>
  >;
  admitDevice(request: DeviceReplayAdmissionRequest<TMessageType>): Promise<
    StoreReplayDecision<TMessageType, TCapacityDenial>
  >;
}

declare const adapterCredentialLeaseBrand: unique symbol;
export type AdapterCredentialLease = Readonly<{
  readonly [adapterCredentialLeaseBrand]: true;
}>;

export type AdapterCredentialLeaseInspection =
  | { kind: "current"; generation: bigint }
  | { kind: "fenced" };

declare const adapterAdmissionBackendBrand: unique symbol;
export interface AdapterReplayAdmissionStore<
  TMessageType extends string = Task5MessageType,
  TCapacityDenial extends OrdinaryReplayCapacityDenial | SecurityReplayCapacityDenial = OrdinaryReplayCapacityDenial,
> extends ReplayStore<TMessageType> {
  readonly [adapterAdmissionBackendBrand]: true;
  loadCommittedAdapterBinding(handle: AuthenticatedIngressHandle<"adapter">): Promise<
    Extract<LoadedTrustedBinding, { kind: "adapter" }>
  >;
  inspectAdapterLease(lease: AdapterCredentialLease): Promise<AdapterCredentialLeaseInspection>;
  admitAdapter(request: AdapterReplayAdmissionRequest<TMessageType>): Promise<
    StoreReplayDecision<TMessageType, TCapacityDenial>
  >;
}

export type SignedEnvelopeVerificationResult<TMessageType extends string = Task5MessageType> =
  | { ok: true; envelope: VerifiedSignedEnvelope<TMessageType> }
  | { ok: false; error: Extract<Task5AdmissionError,
      "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED" |
      "INTEGRITY_FAILED" | "MESSAGE_EXPIRED"> };

export type BindingVerificationResult =
  | { ok: true; context: AuthenticatedBindingContext }
  | { ok: false; error: "CONNECTION_FENCED" | "AUTH_BINDING_MISMATCH" | "ADAPTER_PRINCIPAL_MISSING" };

export type TransportFrameDecision =
  | { ok: true; kind: "accepted"; frame: AcceptedTransportFrame }
  | { ok: true; kind: "duplicate"; cachedReceipt: ExactWireBytes }
  | { ok: false; error: Task5AdmissionError };

declare const verifiedSignedEnvelopeBrand: unique symbol;
declare const exactWireBytesBrand: unique symbol;
export type ExactWireBytes = Readonly<{
  readonly byteLength: number;
  copy(): Uint8Array;
  readonly [exactWireBytesBrand]: true;
}>;

export interface VerifiedSignedEnvelope<TMessageType extends string = Task5MessageType> {
  readonly rawWire: ExactWireBytes;
  readonly messageType: TMessageType;
  readonly header: Readonly<Record<string, unknown>>;
  readonly payload: Readonly<Record<string, unknown>>;
  readonly registryEntry: MessageRegistryEntry;
  readonly signerRole: SignerRole;
  readonly envelopeDigest: string;
  readonly [verifiedSignedEnvelopeBrand]: true;
}

export type OrdinaryTask5MessageType = "device_ping" | "bridge_ping" | "device_presence";
export type RotationTask5MessageType = Exclude<Task5MessageType, OrdinaryTask5MessageType>;

declare const sendableOutboundEnvelopeBrand: unique symbol;
export type SendableOutboundEnvelope = Readonly<{
  readonly messageType: Task5MessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly space: ReplaySpace;
  readonly issuedAt: string;
  readonly expiresAt: string;
  readonly envelopeDigest: string;
  readonly rawWire: ExactWireBytes;
  readonly [sendableOutboundEnvelopeBrand]: true;
}>;

declare const preparedRotationHandleBrand: unique symbol;
export type PreparedRotationHandle = Readonly<{
  readonly handleId: string;
  readonly messageType: RotationTask5MessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly space: ReplaySpace;
  readonly envelopeDigest: string;
  readonly [preparedRotationHandleBrand]: true;
}>;

export interface OutboundPreparationInput {
  readonly messageType: Task5MessageType;
  readonly payload: unknown;
  readonly messageId: string;
  readonly issuedAt: string;
  readonly expiresAt: string;
}

export interface OutboundEnvelopeStore {
  prepareOrdinaryAtomically(
    input: OutboundPreparationInput & { readonly messageType: OrdinaryTask5MessageType },
    context: AuthenticatedBindingContext,
  ): Promise<
    | { kind: "prepared" | "same"; envelope: SendableOutboundEnvelope }
    | { kind: "rejected"; error: "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED" }
  >;
  prepareRotationAtomically(
    input: OutboundPreparationInput & { readonly messageType: RotationTask5MessageType },
    context: AuthenticatedBindingContext,
  ): Promise<
    | { kind: "prepared" | "same"; handle: PreparedRotationHandle }
    | { kind: "rejected"; error: "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED" }
  >;
  loadOrdinarySendable(space: ReplaySpace, messageId: string): Promise<SendableOutboundEnvelope | null>;
  loadRotationHandle(space: ReplaySpace, messageId: string): Promise<PreparedRotationHandle | null>;
}

export function prepareSignedEnvelope(
  input: OutboundPreparationInput & { readonly messageType: OrdinaryTask5MessageType },
  context: AuthenticatedBindingContext,
  store: OutboundEnvelopeStore,
): Promise<
  | { ok: true; kind: "prepared" | "same"; envelope: SendableOutboundEnvelope }
  | { ok: false; error: "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED" }
>;

export function prepareRotationEnvelope(
  input: OutboundPreparationInput & { readonly messageType: RotationTask5MessageType },
  context: AuthenticatedBindingContext,
  store: OutboundEnvelopeStore,
): Promise<
  | { ok: true; kind: "prepared" | "same"; handle: PreparedRotationHandle }
  | { ok: false; error: "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED" }
>;

export interface TransportSender {
  send(envelope: SendableOutboundEnvelope): Promise<void>;
}

declare const acceptedTransportFrameBrand: unique symbol;
export type AcceptedTransportFrame<TMessageType extends string = Task5MessageType> =
  | Readonly<{
      readonly envelope: VerifiedSignedEnvelope<TMessageType>;
      readonly context: Extract<AuthenticatedBindingContext, { kind: "device" }>;
      readonly claim: ReplayClaim<TMessageType>;
      readonly connectionLease: ConnectionLease;
      readonly [acceptedTransportFrameBrand]: true;
    }>
  | Readonly<{
      readonly envelope: VerifiedSignedEnvelope<TMessageType>;
      readonly context: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
      readonly claim: ReplayClaim<TMessageType>;
      readonly connectionLease: null;
      readonly adapterCredentialLease: AdapterCredentialLease;
      readonly [acceptedTransportFrameBrand]: true;
    }>;

export function verifySignedEnvelope(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"device">,
  dependencies: Readonly<{
    admission: DeviceReplayAdmissionStore;
    keyRings: KeyRingStore;
    clock: Clock;
  }>,
): Promise<SignedEnvelopeVerificationResult>;

export function verifySignedEnvelope(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"adapter">,
  dependencies: Readonly<{
    admission: AdapterReplayAdmissionStore;
    keyRings: KeyRingStore;
    clock: Clock;
  }>,
): Promise<SignedEnvelopeVerificationResult>;

export function verifyAuthenticatedBinding(
  envelope: VerifiedSignedEnvelope,
  trusted: Extract<LoadedTrustedBinding, { kind: "device" }>,
  inspection: ConnectionLeaseInspection,
): BindingVerificationResult;

export function verifyAdapterAdmission(
  envelope: VerifiedSignedEnvelope,
  trusted: Extract<LoadedTrustedBinding, { kind: "adapter" }>,
): BindingVerificationResult;

export function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"device">,
  dependencies: Readonly<{
    clock: Clock;
    keyRings: KeyRingStore;
    admission: DeviceReplayAdmissionStore;
    authorization: EnvelopeAuthorizationGate;
  }>,
): Promise<TransportFrameDecision>;

export function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"adapter">,
  dependencies: Readonly<{
    clock: Clock;
    keyRings: KeyRingStore;
    admission: AdapterReplayAdmissionStore;
    authorization: EnvelopeAuthorizationGate;
  }>,
): Promise<TransportFrameDecision>;

export function acceptSequence(
  state: ReplayWindowState,
  sequence: bigint,
): ReplayDecision;

export function allocateConnectionGeneration(
  store: ConnectionFenceStore,
  key: ConnectionFenceKey,
  connectionId: string,
  transportProfileId: string,
): Promise<
  | { ok: true; allocation: ConnectionAllocation }
  | { ok: false; error: "CONNECTION_FENCED" }
>;

export function fenceConnection(
  inspection: ConnectionLeaseInspection,
  allocatedGeneration: bigint,
  headerGeneration: bigint,
): { ok: true } | { ok: false; error: "CONNECTION_FENCED" };
```

The signed control envelope is exactly `{ header, payload, signature }`. Add both schema documents to the data-only `PROTOCOL_SCHEMA_DOCUMENTS`, add both document roots plus all 27 leaf IDs below to `REQUIRED_PROTOCOL_SCHEMA_IDS`, and extend `fixtureFor`; the catalog import-count assertion becomes exactly 8. No runtime module may register a schema dynamically. The roots are `urn:open-android-intelligence:protocol:v1:control-envelope` and `urn:open-android-intelligence:protocol:v1:key-rotation`. The exact leaf IDs are:

```text
urn:open-android-intelligence:protocol:v1:message:device_ping
urn:open-android-intelligence:protocol:v1:message:bridge_ping
urn:open-android-intelligence:protocol:v1:message:device_presence
urn:open-android-intelligence:protocol:v1:message:device_key_rotation
urn:open-android-intelligence:protocol:v1:message:device_key_rotation_ack
urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation
urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation_ack
urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation
urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation_ack
urn:open-android-intelligence:protocol:v1:header:device_ping
urn:open-android-intelligence:protocol:v1:header:bridge_ping
urn:open-android-intelligence:protocol:v1:header:device_presence
urn:open-android-intelligence:protocol:v1:header:device_key_rotation
urn:open-android-intelligence:protocol:v1:header:device_key_rotation_ack
urn:open-android-intelligence:protocol:v1:header:bridge_key_rotation
urn:open-android-intelligence:protocol:v1:header:bridge_key_rotation_ack
urn:open-android-intelligence:protocol:v1:header:adapter_key_rotation
urn:open-android-intelligence:protocol:v1:header:adapter_key_rotation_ack
urn:open-android-intelligence:protocol:v1:envelope:device_ping
urn:open-android-intelligence:protocol:v1:envelope:bridge_ping
urn:open-android-intelligence:protocol:v1:envelope:device_presence
urn:open-android-intelligence:protocol:v1:envelope:device_key_rotation
urn:open-android-intelligence:protocol:v1:envelope:device_key_rotation_ack
urn:open-android-intelligence:protocol:v1:envelope:bridge_key_rotation
urn:open-android-intelligence:protocol:v1:envelope:bridge_key_rotation_ack
urn:open-android-intelligence:protocol:v1:envelope:adapter_key_rotation
urn:open-android-intelligence:protocol:v1:envelope:adapter_key_rotation_ack
```

`control-envelope.schema.json` owns the three non-rotation message/header/envelope leaf triples. `key-rotation.schema.json` owns the six rotation triples. Reusable paired-device and adapter family header/envelope shells exist only as internal `$defs` without public `$id`; they are neither catalog entries nor accepted roots. Every named header leaf composes Task 4's `signed_header_base`, fixes that row's literal `message_type`, `message_schema` and direction, and closes with `unevaluatedProperties:false`: paired-device leaves add exactly `device_id`, `pairing_generation`, `connection_generation`; adapter leaves add exactly `adapter_credential_id`, `adapter_credential_generation`. For every message type `T`, the named `envelope:T` leaf is exactly `{header,payload,signature}`, references both `header:T` and `message:T`, closes the outer object and validates canonical P1363 signature syntax. Stage 2 strict-parses only against the trusted-ingress internal family shell selected outside the wire; this shell establishes the closed family shape and signature encoding but has no `$id` and does not assert a type-specific payload tuple. Stage 3 resolves exactly one locked registry row by the parsed `message_type`; stage 4 checks that row's exact schema/direction/domain/role tuple; stage 5 validates the complete value against `envelope:T`, thereby revalidating the exact header and exact message payload together. There is no accepted base header, public family-envelope ID, unregistered control-message fallback, caller-supplied schema ID or fallback branch.

Freeze these exact closed payloads; `rotation_id` is lowercase UUIDv4, `old_key_id`/`new_key_id` use `opaque_server_id`, every `challenge` is canonical decoded-32-byte base64url, every key thumbprint uses Task 4's exact `{crv,kty,x,y}` projection, and every public JWK passes Task 3's exact-field and curve-point validation:

| message | exact payload |
|---|---|
| `device_ping` | `{ challenge }` |
| `bridge_ping` | `{ challenge }` |
| `device_presence` | `{ state:"online"|"idle" }` |
| `device_key_rotation` | `{ rotation_id, old_key_id, new_public_jwk, new_key_thumbprint, challenge }` |
| `device_key_rotation_ack` | `{ rotation_id, old_key_id, new_key_id, new_key_thumbprint, challenge, proposal_digest }` |
| `bridge_key_rotation` | `{ rotation_id, old_key_id, new_public_jwk, new_key_thumbprint, challenge }` |
| `bridge_key_rotation_ack` | `{ rotation_id, old_key_id, new_key_id, new_key_thumbprint, challenge, proposal_digest }` |
| `adapter_key_rotation` | `{ rotation_id, old_key_id, new_public_jwk, new_key_thumbprint, challenge, next_adapter_credential_generation }` |
| `adapter_key_rotation_ack` | `{ rotation_id, old_key_id, new_key_id, new_key_thumbprint, challenge, next_adapter_credential_generation, proposal_digest }` |

For every proposal, `old_key_id` equals the signed header `key_id`, the internal `newKeyId` is exactly `new_public_jwk.kid`, the recomputed thumbprint equals `new_key_thumbprint`, old/new IDs differ, and—apart from replay of the byte-identical current pending proposal—the new key ID has never appeared in that credential's retained ring. A recomputed-thumbprint mismatch is `INTEGRITY_FAILED`. `proposal_digest` is canonical decoded-32-byte base64url equal to `b64u(SHA-256(exact complete signed proposal rawWire))`; it covers header, proposal payload and signature, not merely the payload or transcript. Every ACK must exactly echo the pending proposal's rotation ID, old/new key IDs, thumbprint and challenge and must constant-time match this digest to the locally retained `PreparedRotationHandle.envelopeDigest`/journaled `SendableOutboundEnvelope.envelopeDigest` or received `AcceptedTransportFrame.envelope.envelopeDigest`. Adapter `next_adapter_credential_generation` is exactly current generation plus one; max-u64 cannot rotate and never wraps. No ping, presence or rotation payload accepts tenant, principal, Agent instance/workspace, scope, transport, connection, operation, capability or revision fields; the applicable identity/generation remains in the signed header and trusted context. A ping response uses the opposite-direction ping branch and echoes the exact challenge; `offline` presence is inferred from the fenced/closed connection rather than accepted as a device claim.

Declare `VerifiedSignedEnvelope` as an opaque, recursively frozen result with retained exact `rawWire`, parsed closed `header`/`payload`, selected locked registry entry, signer role and `envelopeDigest = b64u(SHA-256(rawWire))`. Because accepted input is already canonical, this digest is over the exact full `{header,payload,signature}` UTF-8 bytes, not the payload digest, signable value or Base64 text. `AcceptedTransportFrame` is another recursively frozen, constructor-private brand created only after stage 11; its discriminated device branch retains the same opaque current connection lease admitted atomically, while its adapter branch retains the same opaque adapter credential lease, and both retain the matching context, exact wire and stable replay claim. No public API accepts an already-parsed value, registry, schema, signature domain, signer role, key record or identity copied from the wire.

`AuthenticatedIngressHandle<K>` is minted only by the HTTPS/WSS listener after its transport authentication completes; raw HTTP headers, query/body fields and caller-created structural objects can never construct or retag it. There is no separately injectable binding loader. The matching opaque admission backend capability is the only dependency that may load, inspect and admit its branch: `DeviceReplayAdmissionStore.loadCommittedDeviceBinding/inspect/admitDevice` and `AdapterReplayAdmissionStore.loadCommittedAdapterBinding/inspectAdapterLease/admitAdapter`. Each load returns only its branded, recursively frozen `LoadedTrustedBinding` and reads credential, connection/profile or principal facts independently of all wire fields. For a device, `loadCommittedDeviceBinding` reads the persisted current connection/profile/generation/revision row and constructor-privately mints or restores its `ConnectionLease` in the same physical backend used later by `inspect` and `admitDevice`. For an adapter, `loadCommittedAdapterBinding` reads the authoritative generation row plus credential/principal facts in one committed serializable snapshot and constructor-privately mints the `AdapterCredentialLease` used later by `inspectAdapterLease` and `admitAdapter`. A record, principal, lease or loader from another object/backend can never be substituted. Tailnet IP/node/tag, MagicDNS/Serve headers, latency and payload/header `transport*` fields are never inputs to authority. The two trusted device transports deliberately derive the same replay/fence keys. `verifyAdapterAdmission` requires active exact credential generation, non-null human and agent principals and an agent principal equal to the record, and returns the branded adapter context. Any payload identities are only equality claims checked after authentication; they never select the credential record.

When loading an adapter record, Task 5 validates each scope only against Task 4's `scope_name` format (`^[a-z][a-z0-9._-]{0,127}$`), makes a fresh copy, sorts by Unicode code-point order, rejects duplicates, freezes the array and then recursively freezes the whole binding. Task 5 deliberately does not import or consult Task 6's not-yet-created capability registry. Neither the mutable source collection nor an insertion-order-dependent `Set` crosses the trusted boundary. `verifyAuthenticatedBinding`/`verifyAdapterAdmission` alone mint the recursively frozen branded `AuthenticatedBindingContext` used for outbound authority. A compile-time test rejects structural `AuthenticatedIngressHandle`, `LoadedTrustedBinding`, `AuthenticatedBindingContext`, `ConnectionLease`, `AdapterCredentialLease` and `AcceptedTransportFrame` values; runtime tests mutate the source credential/principal/scope objects after loading and prove admission authority is unchanged.

`prepareSignedEnvelope` and `prepareRotationEnvelope` take no caller sequence, replay space, domain, schema, direction, key ID, signature or encoded header. `OutboundEnvelopeStore` is a trusted durable service configured with the static catalog/registry, `KeyRingStore`, production `Signer` and Task 3 crypto; applications cannot supply an encoder callback or prebuilt bytes. Its `prepareOrdinaryAtomically`/`prepareRotationAtomically` transaction locks the derived replay space and key ring, rejects a conflicting existing `messageId`, allocates the next decimal-u64 sequence (starting at 0), derives the ring/tuple/role from the frozen registry plus authenticated context, reads the sole active `KeyRecord`, validates the exact input and lifetime, builds the closed header/payload, computes `payload_digest`, signs the Task 3 preimage using that record's `keyId`, and immediately verifies the returned signature with `verifyEs256` using that same in-transaction `KeyRecord.publicJwk`. It then canonical-encodes and strict-parses the full envelope again, verifies the locked header/envelope/payload leaves and signature, recomputes the payload/full-envelope digests, and only then commits the sequence plus an immutable defensive copy of the exact bytes and metadata. A mismatch at self-verification is `INTEGRITY_FAILED`; it commits neither sequence nor row and emits no bytes.

After its atomic commit, ordinary preparation returns a recursively frozen opaque `SendableOutboundEnvelope`; `loadOrdinarySendable` recovers that same byte-identical brand. `ExactWireBytes` owns a private immutable byte string and exposes only a fresh defensive `copy()`; neither a retained caller buffer nor a typed-array view can mutate persisted bytes. Rotation preparation instead returns only a constructor-private `PreparedRotationHandle`; the handle contains identity/digest metadata but exposes no raw bytes, byte view, `copy()` or send method, and `OutboundEnvelopeStore` has no API that upgrades it to sendable. A byte-identical retry for the same space/`messageId` and semantic draft returns the same ordinary envelope or rotation handle without signing or allocating; any changed type, payload, issue/expiry or context for that key is `INTEGRITY_FAILED`. A crash before preparation commit exposes nothing and may leave only a documented sequence gap if the backing allocator cannot roll the reservation back; after preparation commit ordinary recovery reloads exact sendable bytes, while rotation recovery still returns only the handle. Sequence values are never reused and decimal-u64 max returns `SEQUENCE_EXHAUSTED` without wrap. `TransportSender.send` accepts only the opaque `SendableOutboundEnvelope` brand and transmits its defensive exact byte copy without re-encoding or re-signing. Rotation takes the same key-ring/outbound locks through the matching `DeviceRotationStore` or `AdapterRotationStore`, so no ordinary prepare can commit under a key that was no longer active at its serialization point. Direction fixes signer role exactly: App→Bridge is `device`, Bridge→App is `bridge-command`, adapter→Bridge is `adapter`, and Bridge→adapter is `bridge-command`.

Inbound stage 6a likewise resolves the header key to one retained `KeyRecord`, checks its role/lifecycle/issuance interval and invokes Task 3 `verifyEs256(record.publicJwk, preimage, signature)` directly. It never asks a second resolver/`Verifier` to look up the same key ID, and the exact `KeyRecord.publicJwk` used for lifecycle authorization is the one used cryptographically. Test doubles mutate any alternate same-ID JWK source and prove it is neither consulted nor accepted.

`verifySignedEnvelope` and `verifyTransportFrame` are asynchronous because Task 3 verification and all durable stores are asynchronous. Their fixed public precedence is:

| stage | check | public result |
|---:|---|---|
| 1 | byte length | `MESSAGE_TOO_LARGE` |
| 2 | strict UTF-8/JCS parse, closed family envelope/header and signature encoding | `SCHEMA_INVALID` |
| 3 | exactly one internally locked registry entry by `message_type` | `SCHEMA_INVALID` |
| 4 | exact schema/direction tuple and registered profile domain/role | `SCHEMA_INVALID` |
| 5 | full exact public `envelope:T` leaf, including exact `header:T` and exact `message:T`; compute but do not yet compare payload digest | `SCHEMA_INVALID` |
| 6a | server-loaded ring owns header key, key is live for that issuance interval, role matches, signature verifies | `AUTH_FAILED` |
| 6b | constant-time decoded-32-byte payload digest comparison | `INTEGRITY_FAILED` |
| 7 | timestamp relation/freshness | `MESSAGE_EXPIRED` |
| 8 | exact current connection generation or adapter credential generation | `CONNECTION_FENCED` |
| 9 | every wire identity equals the independently loaded binding; adapter principal propagation present | `AUTH_BINDING_MISMATCH` or `ADAPTER_PRINCIPAL_MISSING` |
| 10 | injected authorization/revision gate | `NOT_AUTHORIZED` |
| 11 | store-owned atomic connection-lease recheck plus exact-message/replay admission | cached duplicate, `CONNECTION_FENCED`, `INTEGRITY_FAILED`, `REPLAY_REJECTED`, or accepted frame |

Sample `Clock.wallNow()` exactly once at admission entry without branching, then reuse that value only when stage 6 checks grace-key live-verification time and stage 7 checks timestamps; the early read cannot change stages 1–5 precedence. After parsing Task 3 canonical timestamps to exact integer milliseconds, require `expires_at > issued_at`, `issued_at <= now + 60 seconds`, `expires_at > now`, and `expires_at-issued_at <= TASK5_MAX_LIFETIME_SECONDS[message_type]`. The three ping/presence types have a 60-second maximum lifetime; all six rotation proposal/ACK types have 300 seconds. Equality at the future-skew or maximum-lifetime ceiling is accepted, while equality at `expires_at == issued_at` or `expires_at == now` is rejected; every failure maps to `MESSAGE_EXPIRED`. Outbound preparation applies the same relations against its one sampled clock and refuses to sign an invalid lifetime. On the App side the injected clock is the Bridge-adjusted authority established from verified negotiation, not an uncorrected device wall clock. No expiry or authorization failure reads replay/cache state. `NOT_AUTHORIZED` and `ADAPTER_PRINCIPAL_MISSING` are typed internal admission decisions, not newly invented wire protocol-error codes; Task 7's closed response/error model determines whether a correlated signed response is legal. A startup-time missing/duplicate schema, duplicate message type, unknown profile domain or unresolved registry schema ID is a configuration failure, never a wire error.

Append exactly this Task 5 delta; `control_envelope` is a container and `receipt_replay` belongs to Task 7:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `device_ping` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:device_ping` |
| `bridge_ping` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:bridge_ping` |
| `device_presence` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:device_presence` |
| `device_key_rotation` | `app-to-bridge` | `key-rotation/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:device_key_rotation` |
| `device_key_rotation_ack` | `bridge-to-app` | `key-rotation/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:device_key_rotation_ack` |
| `bridge_key_rotation` | `bridge-to-app` | `key-rotation/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation` |
| `bridge_key_rotation_ack` | `app-to-bridge` | `key-rotation/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation_ack` |
| `adapter_key_rotation` | `adapter-to-bridge` | `key-rotation/adapter-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation` |
| `adapter_key_rotation_ack` | `bridge-to-adapter` | `key-rotation/bridge-to-adapter` | `urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation_ack` |

There is no generic ACK. Update the cumulative registry test before the RED run; its exact cumulative array must include the nine rows above, every message payload `$id` must occur exactly once in the two catalog documents, and every production payload `$id` must still have exactly one registry row.

Replay uses this exact pure bitmap algorithm. The empty state is `highestSeen=null`, `seenBitmap=0`; bit 0 denotes `highestSeen`, bit `n` denotes `highestSeen-n`, and only the low 1,024 bits may be nonzero. For an unknown sequence, empty accepts with bit 0 set; `sequence > highestSeen` computes `delta=sequence-highestSeen`, uses `0` as the shifted bitmap when `delta >= 1024` and otherwise shifts left by `Number(delta)`, masks to the low 1,024 bits, then sets bit 0. Otherwise `offset=highestSeen-sequence`; `offset >= 1024` rejects, a set bit rejects, and a clear bit is set. `acceptSequence` is only this deterministic value model: it knows no message ID, receipt, store revision, retry or connection lease and performs no I/O.

The branch-specific `admitDevice`/`admitAdapter` method owns the entire linearizable decision. In one serializable transaction it first performs exact `messageId` lookup in the one shared conflict index. The same stored sequence and full-envelope digest in `finalized` state returns a defensive byte copy of the retained receipt; the same values in ordinary `pending` state returns denial `PENDING`/public `REPLAY_REJECTED` and never returns a claim or re-executes work; the same values in `abandoned` state returns `REPLAY_REJECTED`; any different sequence or digest returns `MESSAGE_ID_CONFLICT`/public `INTEGRITY_FAILED`. Only an unknown message ID is passed through `acceptSequence`, locked-policy capacity enforcement and insertion of one durable `pending` `ReplayRow<TMessageType>`. The backend derives `LockedReplayPolicy` solely from the already resolved immutable `LockedReplayRegistryIdentity<TMessageType>`; no admission request contains a class, reservation, retention rule or quota selector. That insertion atomically persists a mandatory `ReplayRecoveryIntent<TMessageType>` containing an immutable defensive copy of the exact inbound wire, the recursively frozen accepted binding snapshot, the exact branch lease, admission time, stable unguessable `claimId`, exact locked registry identity, derived policy and the matching output of `projectPersistedReplayIntentMetadata`. A database constraint rejects a pending row with a null, missing or mismatched intent, projection or policy, and requires `row.receiptReservationBytes === row.replayPolicy.receiptReservationBytes`. The backend returns its branded `ReplayClaim<TMessageType>` and `AcceptedTransportFrame<TMessageType>` only after commit. Thus callers never load a snapshot, compute a candidate row or CAS it themselves.

`LockedReplayRegistryIdentity` is the complete closed field projection `{ messageType, messageSchemaId, headerSchemaId, envelopeSchemaId, direction, signatureDomain, signerRole }` copied from the single locked registry row and resolved catalog leaves at stages 3–5. It deliberately retains no registry-row digest member: hashing that already complete tuple would be redundant and supply no independent authority. On recovery, the backend resolves `messageType` in its immutable startup-validated registry, requires field-for-field equality with all six remaining tuple fields, resolves the three schema IDs in the static catalog, and revalidates retained `rawWire` against that exact envelope leaf before reconstructing authority. A missing row/leaf or any projection mismatch is a fail-closed configuration/recovery integrity failure and cannot resume or fall back to a caller/retry registry. Tests mutate every projection field independently and prove recovery fails; a byte-identical projection reconstructs the same accepted frame across restart.

`PersistedReplayIntentMetadata<TMessageType>` is the sole persisted non-wire/JCS projection; it is not an alias for any branded camelCase runtime object. Its top level has exactly the eight required members `admitted_at`, `binding_snapshot`, `claim_id`, `lease_ref`, `registry_identity`, `replay_policy`, `retention_until` and `space`. Each nested union is closed by its required `kind` discriminator and the exact members shown in the interface above. There are no optional members: every inactive cross-branch member is present as JSON `null`, while every active member is non-null. `canonicalReplayIntentMetadataBytes` rejects a missing member, an unknown member, JavaScript `undefined`, a wrong discriminator/type, a noncanonical scalar or a branch mismatch before calling Task 3's RFC 8785 encoder; on success it returns exactly `JCS_UTF8(metadata)`. Implementations may neither spread/serialize a branded runtime object nor add database, ORM, version or cache fields to this projection.

The admission backend performs exactly `projectPersistedReplayIntentMetadata(authority)`, with no alternative converter or serializer; only the backend can mint its branded `ReplayIntentMetadataAuthority`. The function allocates a fresh object and maps `admittedAt → admitted_at`, `claimId → claim_id` and store-derived `retentionUntil → retention_until`. For a device binding it maps every accepted context field exactly once: `credentialId`, `tenantId`, `humanPrincipalId`, `deviceId`, `pairingGeneration`, `connectionGeneration` and `direction` to the corresponding snake-case member, sets `kind:"device"`, and writes all five adapter-only members shown by the type as `null`. For an adapter binding it maps every accepted field exactly once: `credentialId`, `adapterCredentialGeneration`, `tenantId`, `humanPrincipalId`, `agentPrincipalId`, `agentInstanceId`, `workspaceId`, `scopeCeiling` and `direction`, sets `kind:"adapter"`, and writes `connection_generation`, `device_id` and `pairing_generation` as `null`. `scope_ceiling` is a fresh JSON array in the exact Unicode-code-point-sorted order already frozen by Task 5; it is never converted through a set, re-sorted by locale or omitted when empty.

Opaque leases have one persistence mapping. At lease mint the same backend assigns a durable 32-byte random primary-key value, encoded in metadata as exactly 43 ASCII characters of canonical RFC 4648 unpadded base64url. A device row writes `lease_ref = { adapter_credential_lease_id:null, connection_lease_id:<that ID>, kind:"device_connection" }`; an adapter row writes `{ adapter_credential_lease_id:<that ID>, connection_lease_id:null, kind:"adapter_credential" }`. The ID is private persisted data, never exposed through either opaque lease type or accepted from a request. Recovery must look it up through the same branch capability and require the exact durable lease row used by admission: for a device, credential and pairing generation equal both the binding snapshot and device replay space while connection generation equals the binding snapshot; for an adapter, credential and credential generation equal both the binding snapshot and adapter replay space while the committed principal snapshot equals every corresponding adapter-binding field. A missing, cross-branch, cross-backend or unequal lease is a recovery-integrity failure; no caller-supplied lease or stable ID is a fallback.

The remaining nested sources are equally mechanical. `registry_identity` maps all seven `LockedReplayRegistryIdentity` members camelCase-to-snake-case; `space` maps every member of the matching `ReplaySpace` branch and writes the inactive generation member as `null`; `replay_policy` is the exact two-string member projection selected from `REPLAY_POLICY_LITERALS`. The converter requires one identical `direction` in binding, registry identity and replay space; matching credential ID in binding/space; and matching pairing or adapter-credential generation in binding/space before producing output. Every bigint/generation is converted with `value.toString(10)` and must satisfy canonical decimal-u64. Timestamps must already satisfy `rfc3339-utc-milliseconds`. All other IDs, schema IDs, directions, domains, roles and scope strings are copied as the already validated JSON string's Unicode scalar sequence with no case folding or Unicode normalization; JSON escaping and member ordering come only from RFC 8785, and final text bytes are UTF-8. `rawWire`, receipt bytes and any Base64/string encoding of either are absent and excluded from the metadata length.

The pending-row transaction persists that closed projection alongside the branded recovery intent and sets `intentMetadataByteLength = canonicalReplayIntentMetadataBytes(persistedMetadata).byteLength`. Its constraint field-compares top-level row facts and all runtime intent fields with the projection. Recovery re-resolves the locked registry and policy, performs the branch-specific lease lookup, rebuilds the branded `ReplayIntentMetadataAuthority` from the retained authoritative facts, reruns `projectPersistedReplayIntentMetadata`, and requires recursive equality with the stored projection including member presence, every explicit `null`, array order and scalar text. It then requires the fresh and stored-projection RFC 8785 byte arrays to be byte-identical and both lengths to equal the stored `intentMetadataByteLength`. Any mismatch, noncanonical stored projection or unlisted implementation field fails closed before a frame is reconstructed; cached totals or a permissive JSON decoder are never recovery authority.

Only a branded server-owned `TrustedReplayReconciler` may call `loadPending`, `resumePending`, `abandonPending` or `compact`. `loadPending` and `resumePending` reconstruct and return the opaque `AcceptedTransportFrame` from the stored intent and locked catalog/registry, never a naked claim and never any wire bytes or authority supplied by a retry. `resumePending` authorizes only continuation of a crash-recoverable protocol journal; it does not authorize repeating an Android command, Agent tool call or any other external side effect. Task 7 must consult its execution-claim ledger before any such continuation. Normal ingress has no reconciler and can never turn a pending duplicate into accepted work. `finalize` defensively copies non-empty canonical opaque receipt bytes, changes exactly the matching claim/digest from `pending` to `finalized`, and returns `same` only for byte-identical bytes already stored; missing, abandoned, mismatched or conflicting finalization is `INTEGRITY_FAILED`. Replay and outbound sequence spaces contain credential, pairing/adapter generation, direction and key ID, but deliberately contain neither connection generation nor HTTPS/WSS/transport profile, so reconnect or path change never resets them.

Task 5 replay capacity is a hard per-`ReplaySpace` limit of 4,096 rows and 67,108,864 total retained bytes. The byte total charges the retained inbound `ExactWireBytes` once, the exact serialized non-wire mandatory-intent metadata, and either the pending 16,384-byte receipt reservation or finalized receipt's actual bytes; `intentMetadataByteLength` explicitly excludes the separately charged `rawWire`. Every Task 5 admission reserves exactly 16,384 receipt bytes in its pending row; `finalize` rejects an empty or larger receipt as `INTEGRITY_FAILED` and atomically replaces the reservation with the actual byte count, so an accepted claim can always be finalized without exceeding capacity. Each inserted row stores the verified message type, exact signed `expiresAt`, status and `retentionUntil = max(expiresAt, admittedAt + 86,400 seconds)`; these times are canonical strings derived by the store from already parsed instants, never caller-selected retention policy. Before inserting an unknown message ID, the same admission transaction attempts only the safe compaction below; if either hard limit would still be exceeded it returns internal denial `CAPACITY_EXHAUSTED`/public `REPLAY_REJECTED`, creates no claim and changes neither window nor rows.

Safe compaction may remove only a `finalized` or reconciler-marked `abandoned` row when all four predicates hold: sampled `now >= retentionUntil`; its sequence is outside the current 1,024-bit window (`highestSeen-sequence >= 1024`); no rotation journal, activation outbox, operation/tombstone or migration record references its claim/receipt; and removing it cannot delete the last required historical key-evidence reference. Explicit `compact` accepts an injected `Clock` and samples `wallNow()` exactly once; admission-triggered compaction reuses that admission's one stage-7 sample. It updates row count and exact receipt-byte accounting in the same transaction. `pending` rows are never automatically expired or compacted, even after `expiresAt`/`retentionUntil`; a trusted reconciler must inspect its durable protocol journal and resume or abandon it first. Capacity pressure never relaxes a predicate, evicts a live receipt, reuses a message ID/sequence or changes public error precedence.

`ConnectionFenceStore.allocateNext` is a durable linearizable fetch-increment keyed exactly by `{credentialId,pairingGeneration}` across Tailnet/public profiles and HTTPS/WSS. It atomically persists the new current row including exact `connectionId`, `transportProfileId`, `generation`, monotonic `fenceRevision` and an unforgeable lease identity; a missing row allocates generation 1/fence revision 1, otherwise both counters increment, and decimal-u64 exhaustion returns `exhausted` without mutation or wrap. The returned `ConnectionLease` is opaque and carries no caller-readable/reconstructible authority fields. Allocation occurs only after Task 4 has admitted/negotiated the authenticated hello and is committed before connect welcome is signed. A lost welcome never reuses its generation.

`ConnectionFenceStore.inspect` is only the stage-8 precedence check: it requires the lease to name the persisted current connection/profile/generation/revision, and `verifyAuthenticatedBinding` additionally requires `header.connection_generation === trusted.allocatedConnectionGeneration === inspection.generation`. Lower, higher/unallocated, a stale revision, or a generation allocated to another concrete connection/profile returns `CONNECTION_FENCED`. Stage 8 alone does not admit replay. The opaque `DeviceReplayAdmissionStore` capability exposes device load, allocation/inspection, `admitDevice`, recovery and finalization over one physical serializable backend. At stage 11 its `admitDevice` transaction revalidates the opaque lease's persisted `connectionId`, `transportProfileId`, generation, fence revision and current marker before any message lookup, window/capacity read or replay mutation. If a newer allocation commits between stages 8 and 11, stage 11 returns `DEVICE_LEASE_STALE`/`CONNECTION_FENCED` with zero replay delta. No implementation may compose independently injected load/fence/replay databases, locks or best-effort transactions behind this capability. Two concurrent allocations may return adjacent generations, but only the final lease remains usable.

Adapter stage 8 calls `AdapterReplayAdmissionStore.inspectAdapterLease` and checks the header generation against that committed lease snapshot only for precedence. At stage 11 the same opaque capability's `admitAdapter` transaction rechecks the identical lease against the authoritative current generation before any message-ID lookup, replay-window/capacity read or mutation. The authoritative adapter-generation row, adapter replay window/rows and adapter rotation journal are tables in one physical serializable backend exposed jointly as `AdapterRotationStore`; they cannot be composed from independently injected load/replay stores, separate databases or ordered best-effort calls. If rotation linearizes first, the old frame returns `ADAPTER_LEASE_STALE`/public `CONNECTION_FENCED` with zero replay delta. If admission linearizes first, it admits exactly once and atomically retains the recoverable intent; subsequent rotation fences that lease.

Add these exact key-ring contracts:

```ts
export type KeyRingOwner = "device-installation" | "bridge-command" | "adapter";
export interface KeyRingId { readonly owner: KeyRingOwner; readonly credentialId: string }
export type KeyLifecycle = "pending" | "active" | "grace_verify_only" | "archived" | "revoked";

export interface KeyRecord {
  readonly keyId: string;
  readonly publicJwk: P256PublicJwk;
  readonly lifecycle: KeyLifecycle;
  readonly activatedAt: string | null;
  readonly signingNotAfter: string | null;
  readonly liveVerifyUntil: string | null;
}

export interface RotationTranscript {
  readonly rotationId: string;
  readonly oldKeyId: string;
  readonly newPublicJwk: P256PublicJwk;
  readonly newKeyThumbprint: string;
  readonly challenge: string;
  readonly proposalDigest: string;
  readonly pairingGeneration: bigint | null;
  readonly oldAdapterCredentialGeneration: bigint | null;
  readonly nextAdapterCredentialGeneration: bigint | null;
}

export interface RotationActivationFact {
  readonly factId: string;
  readonly rotationId: string;
  readonly owner: KeyRingOwner;
  readonly credentialId: string;
  readonly pairingGeneration: bigint | null;
  readonly oldAdapterCredentialGeneration: bigint | null;
  readonly nextAdapterCredentialGeneration: bigint | null;
  readonly oldKeyId: string;
  readonly newKeyId: string;
  readonly proposalDigest: string;
  readonly activatedAt: string;
  readonly retireAt: string;
}

export interface KeyRingState {
  readonly activeKeyId: string | null;
  readonly bindingGeneration: bigint;
  readonly keys: ReadonlyMap<string, KeyRecord>;
  readonly pending: Readonly<{
    transcript: RotationTranscript;
    localProposal: SendableOutboundEnvelope | null;
    cachedAck: SendableOutboundEnvelope | null;
    proposalClaimId: string | null;
    ackClaimId: string | null;
  }> | null;
  readonly activationOutbox: ReadonlyMap<string, RotationActivationFact>;
}

export interface KeyRingSnapshot { readonly storeRevision: bigint; readonly state: KeyRingState }
export interface KeyRingStore {
  load(id: KeyRingId): Promise<KeyRingSnapshot>;
}

export type RotationBranch = "device" | "adapter";
export type RotationKeyRingId<K extends RotationBranch> = KeyRingId & Readonly<{
  owner: K extends "device" ? "device-installation" | "bridge-command" : "adapter";
}>;
export type RotationContext<K extends RotationBranch> = Extract<
  AuthenticatedBindingContext,
  { kind: K }
>;
export type RotationAcceptedFrame<K extends RotationBranch> = Extract<
  AcceptedTransportFrame,
  { context: { kind: K } }
>;

export type KeyRotationInput<K extends RotationBranch> =
  | { kind: "prepare_local"; id: RotationKeyRingId<K>; context: RotationContext<K>; transcript: RotationTranscript; proposal: PreparedRotationHandle }
  | { kind: "accept_remote_proposal"; id: RotationKeyRingId<K>; proposal: RotationAcceptedFrame<K>; ack: PreparedRotationHandle }
  | { kind: "accept_ack"; id: RotationKeyRingId<K>; ack: RotationAcceptedFrame<K> }
  | { kind: "retire_due"; id: RotationKeyRingId<K> }
  | { kind: "revoke"; id: RotationKeyRingId<K>; keyId: string };

export type KeyRotationDecision =
  | { ok: true; kind: "prepared"; proposal: SendableOutboundEnvelope }
  | { ok: true; kind: "acknowledged"; ack: SendableOutboundEnvelope; activation: RotationActivationFact }
  | { ok: true; kind: "activated"; activation: RotationActivationFact }
  | { ok: true; kind: "already_applied"; cached: SendableOutboundEnvelope | null }
  | { ok: true; kind: "retired" | "revoked" }
  | { ok: false; error: "AUTH_FAILED" | "INTEGRITY_FAILED" | "AUTH_BINDING_MISMATCH" | "CONNECTION_FENCED" | "INVALID_STATE_TRANSITION" };

export type RotationJournalStatus =
  | "local_prepared"
  | "remote_ack_committed"
  | "local_activated"
  | "retired"
  | "revoked";

export interface RotationJournalEntry<K extends RotationBranch> {
  readonly journalId: string;
  readonly id: RotationKeyRingId<K>;
  readonly transcript: RotationTranscript;
  readonly status: RotationJournalStatus;
  readonly localProposal: SendableOutboundEnvelope | null;
  readonly cachedAck: SendableOutboundEnvelope | null;
  readonly proposalClaimId: string | null;
  readonly ackClaimId: string | null;
  readonly activation: RotationActivationFact | null;
}

declare const deviceRotationBackendBrand: unique symbol;
export interface DeviceRotationStore extends KeyRingStore, OutboundEnvelopeStore, DeviceReplayAdmissionStore {
  readonly [deviceRotationBackendBrand]: true;
  applyAtomic(input: KeyRotationInput<"device">, clock: Clock): Promise<KeyRotationDecision>;
  loadJournal(journalId: string): Promise<RotationJournalEntry<"device"> | null>;
  resumeJournal(
    reconciler: TrustedReplayReconciler,
    journalId: string,
    clock: Clock,
  ): Promise<KeyRotationDecision>;
}

declare const adapterRotationBackendBrand: unique symbol;
export interface AdapterRotationStore extends KeyRingStore, OutboundEnvelopeStore, AdapterReplayAdmissionStore {
  readonly [adapterRotationBackendBrand]: true;
  applyAtomic(input: KeyRotationInput<"adapter">, clock: Clock): Promise<KeyRotationDecision>;
  loadJournal(journalId: string): Promise<RotationJournalEntry<"adapter"> | null>;
  resumeJournal(
    reconciler: TrustedReplayReconciler,
    journalId: string,
    clock: Clock,
  ): Promise<KeyRotationDecision>;
}

export function applyKeyRotation(
  input: KeyRotationInput<"device">,
  store: DeviceRotationStore,
  clock: Clock,
): Promise<KeyRotationDecision>;
export function applyKeyRotation(
  input: KeyRotationInput<"adapter">,
  store: AdapterRotationStore,
  clock: Clock,
): Promise<KeyRotationDecision>;
```

The rotating side first obtains only a `PreparedRotationHandle` proposal from the same branch store that will journal it; its `envelopeDigest` becomes `RotationTranscript.proposalDigest`. `applyAtomic(prepare_local)` resolves the handle to store-private retained bytes, verifies the prepared message type/direction/space, same-`KeyRecord.publicJwk` self-verification, exact transcript-to-payload equality and same-digest equality, then atomically records `pending + localProposal + local_prepared`. Only after that journal transaction commits may the branch store constructor-privately mint and return the `SendableOutboundEnvelope`. No API accepts any raw rotation-wire argument, bare `VerifiedSignedEnvelope` or already-parsed proposal/ACK, and no application can read/copy/send the handle. A crash after preparation but before journal commit exposes no rotation bytes; retry adopts the same handle. A crash after journal commit recovers and returns the same sendable exact bytes without signing again.

The receiver first admits the proposal through all eleven stages, so `accept_remote_proposal.proposal` must be the branch-matching fully branded `RotationAcceptedFrame<K>`, then obtains only a self-verified `PreparedRotationHandle` ACK whose private retained envelope has a `proposal_digest` equal to that frame's full-envelope digest. Type-level overloads reject a device frame/store paired with an adapter input/store and vice versa. In one physical serializable `applyAtomic` transaction, `DeviceRotationStore` rechecks the current `ConnectionLease`, finalizes the device replay claim, mutates the ring, records the exact cached ACK, appends the activation outbox and commits `remote_ack_committed`; its inherited device load/fence/replay methods are tables and operations of that same backend capability. `AdapterRotationStore` provides the parallel guarantee for `AdapterCredentialLease`, authoritative adapter generation and adapter replay. Only the successful branch journal transaction mints/returns the ACK `SendableOutboundEnvelope`; a crash before commit exposes no ACK bytes, while recovery after commit or an exact duplicate proposal returns the stored byte-identical sendable ACK without a second state transition, receipt or fact.

The proposer likewise passes only a branch-matching fully admitted ACK. The same branch transaction requires the exact pending transcript echo including constant-time `proposal_digest`, activates the new signing key, changes the old key to `grace_verify_only`, writes the activation fact/outbox and `local_activated` journal state, and finalizes the ACK replay claim with canonical immutable activation-receipt bytes. For device/Bridge rotation, the ring, retained rotation bytes/handles, device connection/fence row, replay row/window, journal and outbox are tables in the single `DeviceRotationStore` backend, so lease recheck, replay finalization and journal commit share one transaction. For adapter rotation this transaction also requires `next_adapter_credential_generation = current + 1`, inserts it under a unique `(credentialId,generation)` constraint, changes both ring `bindingGeneration` and the authoritative `AdapterCredentialRecord.generation`, and fences every old-generation adapter session; conflict or max-u64 returns `INVALID_STATE_TRANSITION` with no partial mutation. An implementation may not simulate either branch with ordered calls across stores.

`resumeJournal` is the only crash-recovery path and requires `TrustedReplayReconciler`. It loads the durable status plus stable claim IDs, uses `resumePending` only for the referenced protocol claim, and idempotently completes or returns the already-committed transaction; ordinary ingress never invokes it. It never accepts replacement proposal/ACK bytes from a retry. Before journal commit it returns no sendable rotation envelope; after commit it constructor-privately re-mints the same `SendableOutboundEnvelope` over the retained exact bytes. Every status transition has a uniqueness constraint on `(owner,credentialId,rotationId)` and on `factId`. Both sides use the ACK header's canonical `issued_at` as `activatedAt`, so fact ID, proposal digest, activation time and retirement deadline agree across retries. Same rotation ID with any changed field/digest is `AUTH_BINDING_MISMATCH`; an ACK without the matching durable pending proposal is `INVALID_STATE_TRANSITION`. `applyKeyRotation` delegates its injected `Clock`; `applyAtomic` or `resumeJournal` samples `wallNow()` exactly once per call and callers cannot supply `now`.

At activation, only the new key may sign. The old key verifies live envelopes only when their signed `issued_at <= activated_at` and receiver time is strictly before `retireAt`; `retireAt` is exactly `activatedAt + 900 seconds` unless an earlier explicit revoke applies. At retirement the old public record becomes `archived`: it cannot sign or admit live transport, but a separate historical verifier may use it only for a retained receipt/migration record whose original issuance lies within that key's recorded interval. Revocation immediately disables live signing/verification without deleting archived public evidence. `bindingGeneration` equals pairing generation for device/Bridge rings and never changes during rotation; for an adapter ring it equals the server-loaded `AdapterCredentialRecord.generation`, and the activation journal transaction advances both representations to the exact proposed next generation before fencing the prior session. Device-installation or Bridge-command loss has no recovery message and requires re-pairing/new `pairing_generation`; adapter loss uses server-side credential reissue, exact next generation and fences the prior session. Device-installation/Bridge-command activation facts advance the affected device epoch in Task 6; adapter facts change only adapter credential generation. `factId` is the rotation ID prefixed by owner and credential ID, the outbox is durable/at-least-once, and its Task 6 consumer must deduplicate this ID before advancing any epoch.

- [ ] **Step 4: Run focused and property tests**

Run: `tools/run-node24 npm test -- protocol/test/schema-validator.test.ts protocol/test/control-envelope.test.ts protocol/test/outbound-envelope.test.ts protocol/test/replay-window.test.ts protocol/test/connection-fence.test.ts protocol/test/key-ring.test.ts protocol/test/adapter-admission.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: the static catalog, every closed wire mutation, both barrier races, restart/exhaustion cases, error-precedence matrix, fencing and rotation crash-cut suite pass; TypeScript canonical fixtures are frozen for later Task 11/12 consumption.

- [ ] **Step 5: Commit signed control handling**

```bash
git add docs/protocol/v1/control-envelope.md protocol/schemas/v1 protocol/registries/v1/messages.json protocol/src protocol/test
git commit -m "feat(protocol): add signed control fencing"
```

### Task 6: Implement the two authorization domains and capability manifest

**Files:**
- Create: `docs/protocol/v1/authorization.md`
- Create: `protocol/schemas/v1/authorization.schema.json`
- Create: `protocol/schemas/v1/authorization-revision.schema.json`
- Create: `protocol/schemas/v1/data-query-grant.schema.json`
- Create: `protocol/schemas/v1/capability-manifest.schema.json`
- Create: `protocol/schemas/v1/zero-retention-profile.schema.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/registries/v1/capabilities.json`
- Create: `protocol/model/v1/revision-events.json`
- Create: `protocol/src/authorization.ts`
- Create: `protocol/src/authorization-revision.ts`
- Create: `protocol/src/data-query-grant.ts`
- Create: `protocol/src/capability-manifest.ts`
- Create: `protocol/src/risk-policy.ts`
- Create: `protocol/src/egress-policy.ts`
- Create: `protocol/test/authorization.test.ts`
- Create: `protocol/test/authorization-revision.test.ts`
- Create: `protocol/test/data-query-grant.test.ts`
- Create: `protocol/test/capability-manifest.test.ts`
- Create: `protocol/test/risk-policy.test.ts`
- Create: `protocol/test/egress-policy.test.ts`
- Modify: `protocol/test/messages-registry.test.ts`

**Interfaces:**
- Produces: `evaluateDeviceAccess(input)`, `evaluateStoredQuery(input)`, `createEnvelopeAuthorizationGate(state)`, `applyRevisionEvent(state,event)`, `reduceDataQueryGrant(state,event)`, `effectiveCapabilities(manifest,binding)`, `evaluateRiskPolicy(input)`, `evaluateEgressProfile(evidence)`, `DataQueryGrantKey`.
- Consumes: Task 4 identity/revision primitives and Task 5 verified `AuthenticatedBindingContext`/`EnvelopeAuthorizationGate` boundary plus `RotationActivationFact`.

- [ ] **Step 1: Write failing authorization-table tests**

```ts
it.each([
  ["inactive pairing", deviceInput({ activePairing: false }), "PAIRING_INACTIVE"],
  ["principal mismatch", deviceInput({ bindingMatches: false }), "BINDING_MISMATCH"],
  ["stale revision", deviceInput({ revisionMatches: false }), "REVISION_MISMATCH"],
  ["backend unavailable", deviceInput({ backendAvailable: false }), "BACKEND_UNAVAILABLE"],
])("denies %s deterministically", (_name, input, reason) => {
  expect(evaluateDeviceAccess(input)).toEqual({ allowed: false, reason });
});

it("stored queries ignore current Android permission and device epoch", () => {
  const input = storedQueryInput({ systemPermission: false, authorizationEpoch: "999" });
  expect(evaluateStoredQuery(input)).toEqual({ allowed: true });
});

it("changes the displayed grant state only after the matching signed Bridge ACK", () => {
  const pending = reduceDataQueryGrant(effectiveGrant, localRevokeRevision8);
  expect(pending.deviceUiState).toBe("revoked_pending_ack");
  expect(pending.bridgeEffectiveState).toBe("effective");
  expect(() => reduceDataQueryGrant(pending, forgedAckRevision8)).toThrowError("AUTH_FAILED");
  expect(() => reduceDataQueryGrant(pending, validBridgeAckRevision7)).toThrowError("INVALID_STATE_TRANSITION");
  expect(reduceDataQueryGrant(pending, validBridgeAckRevision8)).toMatchObject({
    deviceUiState: "revoked",
    bridgeEffectiveState: "revoked",
    grantRevision: 8n,
  });
});

it.each(["pause", "resume", "emergency_stop", "scope_policy_change", "system_permission_change", "backend_credential_change", "bridge_command_key_activated"])(
  "advances authorization epoch for %s",
  (event) => expect(applyRevisionEvent(activeRevision7, revisionEvent(event)).authorizationEpoch).toBe(8n),
);

it("never lowers SMS confirmation or L4 denial", () => {
  expect(evaluateRiskPolicy(policyInput("sms.send", { userOverride: "continuous" })))
    .toMatchObject({ allowed: true, approval: "per_operation_on_device" });
  expect(evaluateRiskPolicy(policyInput("ui.control.typed", { deterministicRisk: "L4", backend: "typed_root" })))
    .toEqual({ allowed: false, reason: "POLICY_BLOCKED" });
});

it.each([undefined, unknownEvidence, driftedEvidence])(
  "fails closed when zero-retention evidence is absent, unknown or drifted",
  (evidence) => expect(evaluateEgressProfile(evidence)).toEqual({ allowed: false, reason: "POLICY_BLOCKED" }),
);
```

Add cross-tenant, same-tenant cross-principal/device/session, admin expansion, forged/late/out-of-order grant ACK, filter hash, lineage, TTL/tombstone, model-supplied `user_id`, network metadata, stale manifest, paused/revoked state and revoke-then-regrant rejection of a generic revision-stamped object. Add the Task 5/6 boundary matrix: syntactically invalid, duplicate or non-canonically ordered scope ceilings are rejected by Task 5 loading, while a syntactically valid but unknown scope passes that syntax boundary and is then rejected fail-closed by Task 6's locked capability-registry membership check before any authorization decision. Registry unavailable, wrong-version, duplicate-name and unresolvable capability-schema states fail startup rather than treating an unknown scope as allowed. Task 7, Task 8 and Task 10 respectively add the concrete old operation, approval and ticket integration cases after those types exist; Task 11 covers the end-to-end chain.

- [ ] **Step 2: Run tests and verify missing evaluators fail**

Run: `tools/run-node24 npm test -- protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/data-query-grant.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts protocol/test/egress-policy.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL on missing authorization modules.

- [ ] **Step 3: Implement explicit deny-first evaluators**

```ts
export interface RevisionSnapshot {
  pairingGeneration: bigint;
  authorizationEpoch: bigint;
  scopeRevisions: ReadonlyMap<string, bigint>;
}

export type AuthorizationDecision =
  | { allowed: true }
  | { allowed: false; reason:
      | "PAIRING_INACTIVE"
      | "BINDING_MISMATCH"
      | "SCOPE_DENIED"
      | "SYSTEM_PERMISSION_MISSING"
      | "BACKEND_UNAVAILABLE"
      | "REVISION_MISMATCH"
      | "GRANT_REVOKED"
      | "LINEAGE_MISMATCH"
      | "RECORD_EXPIRED"
      | "RECORD_DELETED" };
```

Before `createEnvelopeAuthorizationGate` evaluates any message, Task 6 loads its immutable version-locked capability registry and fail-closed validates every scope in Task 5's branded, sorted ceiling for exact membership. The gate accepts no caller-selected registry or fallback namespace. Unknown, removed, wrong-version or duplicate registry membership returns `SCOPE_DENIED` before authorization; unavailable or internally inconsistent locked registry data is a startup failure. This membership check is deliberately owned by Task 6: Task 5 owns only Task 4 `scope_name` syntax, copying, Unicode-code-point sorting, duplicate rejection, freezing and branding.

`evaluateDeviceAccess` follows the exact conjunction in §7.2. `evaluateStoredQuery` never reads system permission, backend availability or device authorization epoch. `DataQueryGrantKey` is exactly tenant + human principal + device + capability + filter hash, and only human-principal authenticated flows may expand it.

`data_query_grant_update` and `data_query_grant_ack` are signed closed messages. Any update that carries a filter must first pass its capability-specific closed filter schema; Bridge then recomputes `filter_hash` from that exact value and constant-time compares the decoded 32-byte digest before grant-key lookup or mutation. A mismatch is `INTEGRITY_FAILED` and changes neither grant nor replay state. A device-originated change remains `pending_*_ack` in device UI until a matching Bridge signature covers grant key, proposed digest, monotonic `grant_revision` and Bridge effective state. Bridge blocks new queries immediately after durably applying a revoke, then emits the ACK; forged, stale or out-of-order ACKs never change either view. The reducer models device UI and Bridge-effective state separately so offline revocation is represented honestly rather than inferred.

`revision-events.json` is the executable transition table. New pairing advances `pairing_generation` and permanently revokes the old binding. Pause, resume, emergency stop, Android permission change, enhanced-backend session/credential change and Bridge command-key activation each advance `authorization_epoch`; a scope consent/policy revoke or regrant advances both the epoch and that capability's `scope_revision`. Local device state is exactly `active | paused | revoked`, and only an authenticated local-user transition can leave `paused`; `revoked` requires new pairing. Adapter credential reissue is explicitly absent from this device table and advances only adapter generation.

Manifest entries retain separate booleans/state for schema support, backend availability/kind, system permission, local consent, effective Agent scope, offline policy, freshness, constraints and revision tuple, plus model destination and zero-retention evidence revision/status. `effectiveCapabilities()` still re-evaluates authenticated binding and current authorization; a manifest alone can never return allowed.

Append exactly this Task 6 delta; evaluator/reducer calls and zero-retention evidence are not messages:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `authorization_revision_update` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:authorization_revision_update` |
| `authorization_revision_ack` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:authorization_revision_ack` |
| `data_query_grant_update` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:data_query_grant_update` |
| `data_query_grant_ack` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:data_query_grant_ack` |
| `capability_manifest` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:capability_manifest` |

Update the cumulative registry test before the RED run; each `$id` must be a closed branch in the schema file named by this task.

The capability registry contains every scope/pattern from §11.1 and, for each, freezes risk floor, data sensitivity, offline policy, minimum approval mode, allowed backend classes and special invariant IDs. `evaluateRiskPolicy` takes the maximum requirement across that registry, sensitivity, user override and backend correction; L4 is always `POLICY_BLOCKED`, B1 requires an active enhanced-backend session, and `sms.send` always returns `per_operation_on_device` approval bound to recipient, complete body, SIM, path, operation, expiry and revision.

`zero-retention-profile.schema.json` requires provider destination, account/profile identifier, contract/config evidence revision, verification time, expiry and an exact statement that request, response, attachment and tool payload create no provider log/training/review/cache/backup object. `evaluateEgressProfile` permits body egress only while that signed deployment evidence is current and unchanged; missing, unknown, expired, drifted or provider-object-returned states fail closed. The provider remains a plaintext processing boundary but creates no deletion target.

The capability registry explicitly rejects `shell.root`, `shell.root.*`, generic exec, script and dynamic action names.

- [ ] **Step 4: Run authorization and registry tests**

Run: `tools/run-node24 npm test -- protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/data-query-grant.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts protocol/test/egress-policy.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: all deny-table, independence and forbidden-capability cases pass.

- [ ] **Step 5: Commit authorization oracles**

```bash
git add docs/protocol/v1/authorization.md protocol/schemas/v1 protocol/registries/v1 protocol/model/v1 protocol/src protocol/test
git commit -m "feat(protocol): add authorization reference model"
```

### Task 7: Implement operation, error and execution-claim state machines

**Files:**
- Create: `docs/protocol/v1/operations.md`
- Create: `protocol/schemas/v1/operation.schema.json`
- Create: `protocol/schemas/v1/receipt.schema.json`
- Create: `protocol/schemas/v1/migration-receipt.schema.json`
- Create: `protocol/schemas/v1/error-response.schema.json`
- Create: `protocol/registries/v1/errors.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/model/v1/operation-transitions.json`
- Create: `protocol/model/v1/error-precedence.json`
- Create: `protocol/test-only/migration/v0.9/profile.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation.schema.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation-signature.json`
- Create: `protocol/src/operation-machine.ts`
- Create: `protocol/src/execution-ledger.ts`
- Modify: `protocol/src/control-envelope.ts`
- Modify: `protocol/src/adapter-admission.ts`
- Modify: `protocol/src/replay-window.ts`
- Create: `protocol/src/migration.ts`
- Create: `protocol/test/operation-machine.test.ts`
- Create: `protocol/test/execution-ledger.test.ts`
- Modify: `protocol/test/control-envelope.test.ts`
- Modify: `protocol/test/adapter-admission.test.ts`
- Modify: `protocol/test/replay-window.test.ts`
- Create: `protocol/test/migration.test.ts`
- Modify: `protocol/test/messages-registry.test.ts`

**Interfaces:**
- Produces: `reduceOperation(record,event)`, `toWireOperationState(record)`, `registerOperation`, `claimExecution`, `recordResult`, `reconcile`, `wrapReceiptReplay`, `migrateSignedRecord(input,targetVersion)`, `ExecutionLedgerStore`, `OperationBindingStore`, `StoredOperationBinding`, `AuthenticatedAdapterInvocation`, `verifyAdapterInvocationBinding`, `Task6ReplayMessageType`, `Task7ReplayMessageType`, `Task7SecurityReplayMessageType`, `P0aReplayMessageType`, `Task7DeviceReplayAdmissionStore`, `Task7AdapterReplayAdmissionStore`, `Task7TransportFrameDecision`, `OPERATION_SECURITY_LEDGER_LIMITS`, `classifyProtocolFailure(stage)`.
- Consumes: Task 5 replay/fence/cached-receipt primitives, Task 6 authorization/policy decisions, and verified identity/revision/parameter digest.

- [ ] **Step 1: Write failing exhaustive transition and crash-window tests**

```ts
it("never changes result_unknown when reconciliation arrives", () => {
  const unknown = operation({ terminalOutcome: "result_unknown" });
  const reconciled = reduceOperation(unknown, {
    type: "reconcile_evidence",
    outcome: "succeeded",
    evidence_digest: DIGEST_A,
    observed_at: "2026-08-08T12:00:00.000Z",
  });
  expect(reconciled.terminalOutcome).toBe("result_unknown");
  expect(reconciled.reconciliation?.outcome).toBe("succeeded");
});

it("returns the current state when cancel loses to execution claim", async () => {
  await claimExecution(ledger, OPERATION_A, PARAMS_A);
  expect(await ledger.cancelBeforeClaim(OPERATION_A)).toEqual({ cancelled: false, requestStatus: "executing" });
  expect(sideEffectCalls).toBe(0);
});

it("wraps an old signed receipt only inside a current authorized connection", () => {
  expect(wrapReceiptReplay(oldGenerationReceipt, currentAuthorizedContext))
    .toMatchObject({ message_type: "receipt_replay", connection_generation: "8" });
  expect(() => wrapReceiptReplay(oldGenerationReceipt, staleRevisionContext))
    .toThrowError("REVISION_MISMATCH");
});

it("migrates a locked legacy pending record without rewriting signed facts", () => {
  const migrated = migrateSignedRecord(legacy09PendingOperation, "1.0");
  expect(migrated).toMatchObject({
    operation_id: legacy09PendingOperation.operation_id,
    original_schema: legacy09PendingOperation.message_schema,
    original_digest: legacy09PendingOperation.payload_digest,
    original_signature: legacy09PendingOperation.signature,
  });
  expect(() => migrateSignedRecord(mutatedCriticalFieldFixture, "1.0"))
    .toThrowError("INTEGRITY_FAILED");
});
```

Generate every legal edge and assert every unlisted state/event pair returns `INVALID_STATE_TRANSITION` without changing durable state. Add WAIT_READ versus FAIL_OFFLINE, device locked, same operation/different digest, ledger full, crash before claim, crash after claim/before side effect, crash after side effect/before result, conflicting result, result ACK loss, late receipt, stale operation after revoke/regrant and protocol-error-does-not-rewrite-terminal cases. Recovery after any durable claim but before a trustworthy result is always `result_unknown`, even when a test fault hook knows the external call had not started, because production recovery cannot prove that fact.

In `replay-window.test.ts`, freeze and assert the exact security formula `16,384 × (262,144 inbound ceiling + 262,144 receipt reservation + 65,536 non-wire intent-metadata ceiling) = 9,663,676,416 bytes`. Test one-below/equal/one-above boundaries for row count, each component ceiling, actual charged bytes and total bytes. Prove the receipt reservation admits and finalizes the largest closed operation receipt allowed by the 262,144-byte control-envelope limit and rejects one byte above before mutation; prove intent metadata of 65,536 bytes is accepted and 65,537 is a configuration/admission integrity failure rather than an undercharged row. A permanent compacted tombstone counts as one of the same 16,384 rows and charges the exact JCS UTF-8 byte length of its closed projection, capped at 2,048 bytes; test 2,047/2,048/2,049 and prove compaction changes no row count while atomically replacing the old byte charge with the tombstone charge. Cover compaction just before/at/after operation expiry and 30 days after Bridge ACK, all admit/finalize/ACK/compact crash cuts, restart recomputation of counters/partitions/tombstones, exact duplicate after compaction, changed-digest message-ID conflict, and no re-execution after bitmap eviction or restart. Exhaustion must return typed `SECURITY_PARTITION_EXHAUSTED`/public `SECURITY_LEDGER_FULL` before execution claim/external invocation with zero window, row, tombstone, operation or ledger mutation. Repeat the same boundary through an ordinary Task 5 registry identity and prove its derived `task5_default` policy still returns `CAPACITY_EXHAUSTED`/public `REPLAY_REJECTED`.

Add checked-in fixed replay vectors `intent-metadata-device-v1`, `intent-metadata-adapter-v1` and `intent-metadata-adapter-empty-scope-v1`. Each vector contains only the exact closed `PersistedReplayIntentMetadata` semantic input, `metadata_jcs_b64` as canonical RFC 4648 standard Base64 with required padding over the complete expected RFC 8785 UTF-8 bytes, and `metadata_jcs_byte_length` as a canonical decimal-u64 string. The device vector uses the ordinary literal pair and includes all adapter-only binding/space/lease members as explicit `null`; the adapter vectors use the security literal pair, include every device-only member as explicit `null`, and cover both a nonempty Unicode-code-point-sorted `scope_ceiling` and `[]`. Before canonicalization, Task 7's TypeScript store fixtures mint both branch authorities and prove `projectPersistedReplayIntentMetadata` equals the corresponding semantic input member-for-member. TypeScript then reconstructs and byte-compares the full decoded JCS bytes and length; Task 11 promotes these same immutable replay vectors to the manifest, and Task 12's independent Kotlin implementation reconstructs and compares the same bytes and length rather than trusting the expected byte field. Mutation vectors independently omit each nullable member, replace `null` with omission or a value, inject every unknown nested/top-level member, reorder or locale-sort the scope array, change either policy literal, use a number/leading-zero decimal in place of a decimal-u64 string, use a padded/non-43-character lease ID, or cross a device binding/lease/space with an adapter branch; all fail before replay mutation. Compile/runtime tests also require the exact ordinary `16384/null/null` and security `262144/65536/2048` numeric descriptors, prove those runtime-only members never enter JCS, and fail recovery after changing any member. Recovery otherwise succeeds only when re-projection is byte-identical and fails on every nested-field, array-order, lease-row or stored-length mismatch.

Add adapter request-context tests for all five API actions (`create`=`operation_submit`, `get`, `wait`, `cancel`, `reconcile`) in which the upstream Agent runtime authenticates exactly one `session_id` or `job_id`. Mutating the payload session/job, omitting both, supplying both, or reusing an otherwise valid same-tenant request from another session/job must fail with the same opaque `AUTH_BINDING_MISMATCH` result after at most a read-only context-scoped binding lookup, but before operation registration/state disclosure, authorization effects, replay mutation or cached-receipt lookup. Add `operation_submit` vectors where `parameters` is unchanged but `parameters_digest` is attacker-chosen, and vice versa; both must return `INTEGRITY_FAILED` with zero operation/approval/limiter/replay mutation. Registry/schema mutation tests must independently reject an unknown action, use of one action's payload under another message type, wait above 30 seconds and Agent-supplied reconciliation outcome/evidence.

- [ ] **Step 2: Run tests and verify missing state machine fails**

Run: `tools/run-node24 npm test -- protocol/test/control-envelope.test.ts protocol/test/adapter-admission.test.ts protocol/test/replay-window.test.ts protocol/test/operation-machine.test.ts protocol/test/execution-ledger.test.ts protocol/test/migration.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL on missing reducer/ledger exports.

- [ ] **Step 3: Implement the closed state and error model**

```ts
export type Task7SecurityReplayMessageType =
  | "operation_submit" | "operation_get" | "operation_wait"
  | "operation_cancel" | "operation_reconcile" | "operation_command"
  | "operation_receipt" | "operation_receipt_ack" | "receipt_replay"
  | "operation_snapshot";

export type Task7ProtocolErrorMessageType =
  | "device_protocol_error" | "bridge_protocol_error" | "adapter_protocol_error";

export type Task6ReplayMessageType =
  | "authorization_revision_update" | "authorization_revision_ack"
  | "data_query_grant_update" | "data_query_grant_ack"
  | "capability_manifest";

export type Task7ReplayMessageType =
  | Task7SecurityReplayMessageType
  | Task7ProtocolErrorMessageType;

export type P0aReplayMessageType =
  | Task5MessageType
  | Task6ReplayMessageType
  | Task7ReplayMessageType;
export type Task7ReplayCapacityDenial =
  | OrdinaryReplayCapacityDenial
  | SecurityReplayCapacityDenial;
export type Task7DeviceReplayAdmissionStore = DeviceReplayAdmissionStore<
  P0aReplayMessageType,
  Task7ReplayCapacityDenial
>;
export type Task7AdapterReplayAdmissionStore = AdapterReplayAdmissionStore<
  P0aReplayMessageType,
  Task7ReplayCapacityDenial
>;
export type Task7TransportFrameDecision =
  | { ok: true; kind: "accepted"; frame: AcceptedTransportFrame<P0aReplayMessageType> }
  | { ok: true; kind: "duplicate"; cachedReceipt: ExactWireBytes }
  | { ok: false; error: Task5AdmissionError | "SECURITY_LEDGER_FULL" };

export function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"device">,
  dependencies: Readonly<{
    clock: Clock;
    keyRings: KeyRingStore;
    admission: Task7DeviceReplayAdmissionStore;
    authorization: EnvelopeAuthorizationGate;
  }>,
): Promise<Task7TransportFrameDecision>;
export function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"adapter">,
  dependencies: Readonly<{
    clock: Clock;
    keyRings: KeyRingStore;
    admission: Task7AdapterReplayAdmissionStore;
    authorization: EnvelopeAuthorizationGate;
  }>,
): Promise<Task7TransportFrameDecision>;

export const OPERATION_SECURITY_LEDGER_LIMITS = Object.freeze({
  maxRowsPerSpace: 16384,
  maxRetainedBytesPerSpace: 9663676416,
  inboundWireCeilingBytesPerRow: 262144,
  receiptReservationBytesPerClaim: 262144,
  intentMetadataCeilingBytesPerRow: 65536,
  tombstoneMetadataCeilingBytesPerRow: 2048,
} as const);

export type RequestStatus =
  | "created" | "waiting_device" | "dispatching" | "accepted_device"
  | "awaiting_approval" | "approved" | "executing";

export type TerminalOutcome =
  | "succeeded" | "failed" | "denied" | "cancelled" | "expired" | "result_unknown";

export type OperationState =
  | { kind: "pending"; requestStatus: RequestStatus; terminalOutcome: null; operationReason: null }
  | { kind: "terminal"; requestStatus: null; terminalOutcome: TerminalOutcome; operationReason: OperationReason | null };

export interface SignedOperationReceipt {
  operationId: string;
  parametersDigest: string;
  state: OperationState;
  resultDigest: string | null;
  canonicalBytes: Uint8Array;
}

export type LedgerEntry =
  | { kind: "registered"; operationId: string; parametersDigest: string }
  | { kind: "claimed"; operationId: string; parametersDigest: string; claimedAt: string }
  | { kind: "result"; operationId: string; parametersDigest: string; receipt: SignedOperationReceipt };

export interface ExecutionLedgerStore {
  register(operationId: string, parametersDigest: string): Promise<"new" | "same" | "conflict">;
  claim(operationId: string, parametersDigest: string): Promise<"claimed" | "already_claimed" | "unavailable">;
  cancelBeforeClaim(operationId: string): Promise<{ cancelled: true } | { cancelled: false; requestStatus: RequestStatus }>;
  putResult(operationId: string, result: SignedOperationReceipt): Promise<void>;
  get(operationId: string): Promise<LedgerEntry | undefined>;
}

export async function claimExecution(
  store: ExecutionLedgerStore,
  operationId: string,
  parametersDigest: string,
): Promise<"claimed" | "already_claimed">;

export type AuthenticatedAdapterInvocation = {
  adapter: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
  invocation: { kind: "session"; sessionId: string } | { kind: "job"; jobId: string };
};

export interface StoredOperationBinding {
  tenantId: string;
  humanPrincipalId: string;
  agentPrincipalId: string;
  agentInstanceId: string;
  workspaceId: string;
  sessionOrJob: { kind: "session"; sessionId: string } | { kind: "job"; jobId: string };
  deviceId: string;
  operationId: string;
  parametersDigest: string;
}

export interface OperationBindingStore {
  loadScoped(
    operationId: string,
    authenticated: AuthenticatedAdapterInvocation,
  ): Promise<{ kind: "match"; binding: StoredOperationBinding } | { kind: "not_found_or_mismatch" }>;
  createOrMatch(
    binding: StoredOperationBinding,
  ): Promise<"created" | "same_exact_binding" | "conflict_or_mismatch">;
}

export function verifyAdapterInvocationBinding(
  payload: unknown,
  authenticated: AuthenticatedAdapterInvocation,
): { ok: true } | { ok: false; error: "AUTH_BINDING_MISMATCH" };
```

`claimExecution` is the sole production wrapper around `ExecutionLedgerStore.claim`: it maps `unavailable` to fail-closed `SECURITY_LEDGER_FULL`, never calls an external effect, and returns only `claimed` or `already_claimed`. `AuthenticatedAdapterPrincipal` from Task 5 is intentionally a long-lived human/agent identity, not session authority. For every one of `operation_submit`, `operation_get`, `operation_wait`, `operation_cancel` and `operation_reconcile`, the upstream Agent runtime must supply the server-authenticated discriminated `AuthenticatedAdapterInvocation`; it is never constructed from envelope payload or model arguments. `verifyAdapterInvocationBinding` first requires exactly one authenticated session/job and byte-for-byte equality with the payload's discriminated `session_or_job`, in addition to the Task 5 tenant/human/agent/instance/workspace context.

For get/wait/cancel/reconcile and duplicate-submit handling, Bridge may then perform exactly one read-only `OperationBindingStore.loadScoped` call before replay admission. The store applies tenant/human/agent/instance/workspace/session-or-job/device row scoping internally and never returns a record for a mismatch; missing and cross-binding IDs are the same `not_found_or_mismatch` result, with no existence, state or timing-sensitive payload disclosure. A match is compared again to every authenticated/payload binding before any snapshot, mutation or cached receipt is exposed. A fresh submit calls `createOrMatch` only after all semantic integrity/authorization checks and the matching branch capability's `admitDevice`/`admitAdapter` has returned an `AcceptedTransportFrame<P0aReplayMessageType>`; a replay or security-partition rejection therefore cannot create an operation. The store enforces global operation-ID uniqueness atomically, returns `same_exact_binding` only for the identical full binding plus authoritative parameters digest, and collapses changed digest or cross-binding collisions to `conflict_or_mismatch` without disclosing the existing record. Existing operation, approval and ticket records retain this binding, so later requests cannot cross session/job.

All five adapter request payloads are separate closed schema branches sharing exactly tenant/human/agent principal, Agent instance, workspace, discriminated session-or-job, device and operation IDs. `operation_submit` additionally carries capability, the complete `parameters` object, its `parameters_digest` and revision snapshot. Its fixed semantic-integrity step is capability lookup → closed capability-specific parameters-schema validation → recompute the globally defined `parameters_digest` → decode both as 32 bytes and constant-time compare. A mismatch is `INTEGRITY_FAILED` before scoped registration, authorization/limiter/approval effects or replay admission, with zero durable delta. Bridge stores only the recomputed value as the authoritative operation digest; every later command, approval, execution claim and receipt compares against that stored value rather than trusting another caller-carried digest. `operation_get`, `operation_cancel` and `operation_reconcile` add no caller-controlled result/evidence fields; Bridge loads those from the stored operation. `operation_wait` adds only decimal-u64 `after_state_revision` and `wait_timeout_ms` in `0..30000`. `operation_reconcile` merely requests Bridge's typed reconciliation procedure and can never assert an outcome or upload evidence. Every successful branch returns the same Bridge-signed `operation_snapshot`; `wait` may return an unchanged revision on timeout.

A non-null `result_digest = b64u(SHA-256(JCS_UTF8(result)))` over the exact closed normalized `result` member in the device receipt; it is `null` only when that receipt schema branch has no result member. Receipt intake validates the result branch, recomputes/constant-time compares this digest and the stored authoritative `parameters_digest`, and only then may write result/operation state or replay receipt bytes. Reconciliation uses a server/adapter-verified closed evidence descriptor exactly `{ evidence_kind, evidence_source, observed_outcome, observed_at, reference_id }`; `evidence_digest = b64u(SHA-256(JCS_UTF8(descriptor)))`. The descriptor itself is stored as protected evidence but is never accepted from `operation_reconcile`, echoed in an operation snapshot or placed in content-free traces. Fixed vectors mutate every descriptor/result field, distinguish missing from explicit `null`, test both digest mismatch paths with zero state/replay delta and compare TypeScript/Kotlin bytes. Freeze the transition table in both `operations.md` and a data constant consumed by tests. The wire schema is a closed `oneOf`: pending records carry `request_status` only; terminal records carry `terminal_outcome` and an independently typed nullable `operation_reason`; protocol errors are envelope rejection objects and never fields masquerading as operation state. `toWireOperationState` is the unique lossless mapping, and every state vector asserts that TypeScript and Kotlin encode the same three-column representation. `accepted_device` is reachable only after the fake device stores the operation and emits a signed receipt. Only registry entries with `offline_policy=WAIT_READ` may enter `waiting_device`, for at most 900 seconds; all streams and side effects use `FAIL_OFFLINE`. Every external side effect must follow verify → claim CAS → one invocation → durable result → signed receipt → repeated delivery until signed Bridge ACK.

Task 7 now has the receipt schema needed to own replay wrapping: exact cached bytes from Task 5 remain signed historical evidence, while `wrapReceiptReplay` creates a newly signed current-generation outer message only after Task 6's current binding/revision gate passes. Its payload is exactly `{ original_receipt_wire_b64, original_receipt_digest }`; `original_receipt_wire_b64` is canonical RFC 4648 standard Base64 with required padding, and `original_receipt_digest = b64u(SHA-256(base64_decode(original_receipt_wire_b64)))` covers the exact decoded original bytes. The inner bytes/signature remain unchanged, and the outer canonical `{header,payload}` is signed under `control/app-to-bridge`.

Task 7 widens the generic replay types to the exact cumulative `P0aReplayMessageType` union (Task 5's nine rows, Task 6's five rows and Task 7's thirteen rows) and upgrades the two branch capabilities to `Task7DeviceReplayAdmissionStore`/`Task7AdapterReplayAdmissionStore`; it does not add a union admission method. The immutable policy registry is the exact total function over that union: every one of the ten rows in `Task7SecurityReplayMessageType` derives only `REPLAY_POLICY_LITERALS.operationSecurityLedger`, the pair `{ class_id:"operation_security_ledger", retention_rule_id:"retain_until_max_operation_expires_at_or_bridge_ack_at_plus_2592000_seconds_v1" }`; every row in `Task5MessageType | Task6ReplayMessageType | Task7ProtocolErrorMessageType` derives only `REPLAY_POLICY_LITERALS.task5Default`, the pair `{ class_id:"task5_default", retention_rule_id:"retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1" }`. Those two pairs are the complete allowed policy-literal set; there is no default arm and no third class or retention rule. The first rule derives `retentionUntil = max(expiresAt, admittedAt + 86,400 seconds)`. The second initially writes the associated authoritative operation expiry as `retentionUntil` but treats a missing signed Bridge ACK as categorically ineligible for compaction; the ACK transaction rewrites it to `max(operationExpiresAt, bridgeAckAt + 2,592,000 seconds)`, regenerates `persistedMetadata`, recomputes its exact JCS length and adjusts the security byte counter atomically. Startup compares the total key set, every exact pair and the resulting ten-security/seventeen-ordinary classification to the locked messages registry and fails on a missing, additional or differently classified row.

After exact leaf validation, the backend uses only `LockedReplayRegistryIdentity.messageType` to resolve that total function and constructor-privately mint the matching camelCase `LockedReplayPolicy` from `LOCKED_REPLAY_POLICY_DESCRIPTORS`; its class/retention pair must round-trip exactly to the corresponding snake-case persisted literal. The complete ordinary numeric descriptor is `{ receiptReservationBytes:16384, intentMetadataCeilingBytes:null, tombstoneMetadataCeilingBytes:null }`: `null` means Task 5 has no separate per-row intent-metadata ceiling beyond its exact actual-byte/per-space limit and creates no permanent tombstone. The complete security descriptor is `{ receiptReservationBytes:262144, intentMetadataCeilingBytes:65536, tombstoneMetadataCeilingBytes:2048 }`. These required numeric-or-null members are constructor-private runtime policy facts, not additional members of the persisted two-string `replay_policy` projection. No request, caller, payload, context or generic store method can pass or override a class, reservation, retention rule or partition. Recovery re-resolves the registry identity, exact policy pair and all three exact numeric-or-null descriptor members from that frozen table; it requires the full descriptor to equal the retained runtime policy and separately requires its class/retention pair to equal `persistedMetadata.replay_policy` before reconstructing the frame.

Both policies share the same `ReplaySpace`, one 1,024-bit sequence window and one global message-ID/full-envelope-digest conflict index. There is no second replay namespace, bitmap or duplicate path. Only capacity counters, receipt reservation and retention/compaction accounting are partitioned. `task5_default` retains Task 5's 4,096-row/67,108,864-byte limits, 16,384-byte reservation, public `REPLAY_REJECTED` capacity mapping and 24-hour rule. `operation_security_ledger` has exactly 16,384 rows, a 262,144-byte inbound-wire ceiling, a 262,144-byte receipt reservation and a 65,536-byte non-wire intent-metadata ceiling per row. Therefore its exact retained-byte limit is `16,384 × (262,144 + 262,144 + 65,536) = 9,663,676,416`; no unlisted overhead is charged outside these counters.

For a pending or not-yet-compacted abandoned security row, charge `rawWire.byteLength + 262,144 + intentMetadataByteLength`; for a finalized row charge `rawWire.byteLength + receipt.byteLength + intentMetadataByteLength`. `intentMetadataByteLength` is exactly `canonicalReplayIntentMetadataBytes(row.recoveryIntent.persistedMetadata).byteLength` over the named closed `PersistedReplayIntentMetadata<P0aReplayMessageType>` projection and no other fields. Its eight top-level members, complete device/adapter `binding_snapshot`, branch-specific durable `lease_ref`, seven-member `registry_identity`, two-member literal `replay_policy` and device/adapter `space` are exactly the required snake-case shapes and source conversions frozen in Task 5. Every inactive cross-branch member remains explicit JSON `null`; an active adapter `scope_ceiling` remains a required array even when it is `[]`. `rawWire`, receipt bytes and their Base64/string encodings remain excluded. ORM columns, brands, cached counters, row status/timestamps other than the named two, and any implementation-specific metadata are neither serialized nor charged. The backend rejects a closed projection above 65,536 UTF-8 bytes before replay mutation; it charges the exact actual JCS byte length at or below the ceiling.

Operation receipt/claim rows override Task 5's 24-hour default and remain receipt-dedupe-addressable until the later of operation expiry or 30 days after the signed Bridge ACK. Before that boundary they are ineligible for default compaction even when outside the bitmap. After it, compaction replaces the bulky row atomically with the permanent closed tombstone `{ envelope_digest, message_id, message_type, sequence, space, status:"compacted" }`. A tombstone consumes one of the same 16,384 rows forever and charges exactly `JCS_UTF8(tombstone).byteLength`, with a hard 2,048-byte ceiling; compaction never releases a row slot and cannot admit a 2,049-byte tombstone. The tombstone is the shared conflict-index entry: an exact future duplicate is non-executing and a changed full-envelope digest remains `INTEGRITY_FAILED`.

At restart the backend ignores cached totals, re-resolves every row/tombstone policy from its locked registry identity, recomputes the row count and exact state-dependent byte charge above, and compares them to the transactionally persisted counters; mismatch, unknown identity/policy or a component above its ceiling fails startup closed. Admission at the exact row/byte boundary is allowed, one unit above returns `SECURITY_PARTITION_EXHAUSTED`/public `SECURITY_LEDGER_FULL` before creating an execution claim or invoking any external system and commits zero window, replay-row, tombstone, operation or ledger delta. Ordinary-policy exhaustion remains `CAPACITY_EXHAUSTED`/public `REPLAY_REJECTED`. Neither partition may borrow quota from the other, weaken retention under pressure or evict pending/referenced rows.

The locked `v0.9` fixture is conformance-only and `negotiable=false`. `migrateSignedRecord` first verifies the old fixture schema/digest/signature, then creates a new v1 operation record plus a closed `migration-receipt.schema.json` record. The receipt payload is exactly `{ migration_id, source_schema_id, source_record_digest, source_signature, target_schema_id, target_record_digest, target_record_id, migrated_at }`. Define `source_record_digest = b64u(SHA-256(JCS_UTF8(full verified legacy signed record {header,payload,signature})))`; define `target_record_digest = b64u(SHA-256(JCS_UTF8(new closed v1 target operation record)))` over the exact persisted target record excluding the migration receipt itself. If a target-record schema branch contains its own signature, that signature member is included because it is part of that closed target record. The receipt envelope also carries the current Bridge `key_id` and ES256 signature. The current Bridge command-signing key signs canonical `{header,payload}` under `migration/bridge`, and verification uses the trusted Bridge key ring plus Task 3's preimage rules. It is a persisted conformance record, not a `messages.json` wire entry. It retains original schema/digest/signature and never lowers generation, revision, revocation, tombstone or terminal facts. Fixed cross-language vectors distinguish payload-only, signature-excluded and alternate-serialization digests and reject each wrong preimage. Mutation, wrong-key/domain and rollback tests target the concrete v1 operation/receipt contracts created in this task rather than a forward-declared shape.

The error registry separately enumerates protocol errors, nonterminal status, terminal outcome, operation failure reason and internal decision reason. It implements the closed total-spec table, including `SCHEMA_INVALID`, `MESSAGE_TOO_LARGE`, `MESSAGE_EXPIRED`, `CONNECTION_FENCED`, `SECURITY_LEDGER_FULL`, `FLOW_CONTROL_VIOLATION`, `INVALID_STATE_TRANSITION` and `RESULT_CONFLICT`; protocol-level failures cannot rewrite an existing operation terminal outcome. `error-response.schema.json` is a closed signed payload `{ code, stage, correlation_message_id, retry_after }`: `code` and `stage` are registry enums, `correlation_message_id` is nullable UUIDv4, and decimal-string seconds `retry_after` is required only for registered retryable codes and forbidden otherwise. It contains no free-text message or echoed input. Device↔Bridge errors use their control direction/domain; Bridge→adapter errors and operation snapshots use the Bridge command key under `adapter/bridge-to-adapter`. Error messages themselves receive normal message ID/sequence/replay validation, while rejecting the correlated offending message never commits its replay state.

`error-precedence.json` fixes evaluation order as size → canonical parse/schema → key/direction/domain/signature → payload digest → expiry → connection fence → authenticated binding → authorization/revision → exact-duplicate/replay/sequence → operation transition. A rejected control message never commits replay, approval, channel ticket, cursor or operation state. Enrollment intake has only Task 4's explicit mutation exceptions: it invokes IP admission once before size/parse evaluation, but only an allowed decision appends an admitted timestamp and neither decision overrides `MESSAGE_TOO_LARGE`/`SCHEMA_INVALID`; it may expose an IP `RATE_LIMITED` only after canonical parse, then performs bounded ticket extraction and atomic known-ticket consumption. After a winning consume it invokes principal admission once, retains that decision through full validation, and only an allowed principal decision appends; validation errors precede a retained principal denial. A post-ticket enrollment rejection therefore commits only the consumed ticket plus any admitted limiter timestamps, no external invocation and no other durable delta; denied calls never mutate limiter timestamps or retry horizon. Internal reasons such as pending-limit, cooldown or credit exhaustion are never emitted as wire codes; `errors.json` maps them to `RATE_LIMITED` plus `retry_after` or `FLOW_CONTROL_VIOLATION`. `system_ui_handoff` is a typed result whose external side effect status is `not_observed`; it is never encoded as confirmed send success.

Append exactly this Task 7 delta:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `operation_submit` | `adapter-to-bridge` | `adapter/adapter-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:operation_submit` |
| `operation_get` | `adapter-to-bridge` | `adapter/adapter-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:operation_get` |
| `operation_wait` | `adapter-to-bridge` | `adapter/adapter-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:operation_wait` |
| `operation_cancel` | `adapter-to-bridge` | `adapter/adapter-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:operation_cancel` |
| `operation_reconcile` | `adapter-to-bridge` | `adapter/adapter-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:operation_reconcile` |
| `operation_command` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:operation_command` |
| `operation_receipt` | `app-to-bridge` | `receipt/device` | `urn:open-android-intelligence:protocol:v1:message:operation_receipt` |
| `operation_receipt_ack` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:operation_receipt_ack` |
| `receipt_replay` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:receipt_replay` |
| `operation_snapshot` | `bridge-to-adapter` | `adapter/bridge-to-adapter` | `urn:open-android-intelligence:protocol:v1:message:operation_snapshot` |
| `device_protocol_error` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:device_protocol_error` |
| `bridge_protocol_error` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:bridge_protocol_error` |
| `adapter_protocol_error` | `bridge-to-adapter` | `adapter/bridge-to-adapter` | `urn:open-android-intelligence:protocol:v1:message:adapter_protocol_error` |

`operation_receipt` is the closed union for `accepted_device`, later pending receipts and terminal results; `operation_snapshot` is the content-free Bridge-signed response used by adapter create/get/wait/cancel/reconcile and never carries a command body. The five named adapter request branches above are the only network entry points for those APIs; Bridge reducer internals, execution claims and reconciliation evidence are not separate wire messages. Update the cumulative registry test before the RED run and assert all five request/one response branches, authenticated invocation binding, the replay outer/inner signature rule and error retry-field constraints.

- [ ] **Step 4: Run the exhaustive state/property suite**

Run: `tools/run-node24 npm test -- protocol/test/control-envelope.test.ts protocol/test/adapter-admission.test.ts protocol/test/replay-window.test.ts protocol/test/operation-machine.test.ts protocol/test/execution-ledger.test.ts protocol/test/migration.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: every allowed edge and crash point passes; every illegal transition is rejected.

- [ ] **Step 5: Commit operation semantics**

```bash
git add docs/protocol/v1/operations.md protocol/schemas/v1 protocol/registries/v1 protocol/model/v1 protocol/test-only/migration protocol/src protocol/test
git commit -m "feat(protocol): define operation execution semantics"
```

### Task 8: Bind approvals and enforce anti-harassment limits

**Files:**
- Create: `protocol/schemas/v1/approval.schema.json`
- Create: `protocol/src/approval.ts`
- Create: `protocol/src/request-limiter.ts`
- Create: `protocol/test/approval.test.ts`
- Create: `protocol/test/request-limiter.test.ts`
- Modify: `protocol/registries/v1/messages.json`
- Modify: `protocol/test/messages-registry.test.ts`
- Modify: `docs/protocol/v1/operations.md`

**Interfaces:**
- Produces: `issueApprovalCredential`, `claimExecutionWithApproval`, `ApprovalClaimStore`, `RequestLimiter.evaluate`.
- Consumes: signed identity, operation, capability, parameter digest and revision types.

- [ ] **Step 1: Write failing approval mutation and limiter tests**

```ts
it.each(["tenant", "human", "agent", "agent_instance", "workspace", "session_or_job", "device", "operation", "capability", "parameters", "backend", "revision"])(
  "rejects a changed %s binding",
  async (field) => {
    expect(await claimExecutionWithApproval(mutate(validApproval, field), expected, store, clock))
      .toEqual({ ok: false, error: "NOT_AUTHORIZED", reason: "BINDING_MISMATCH" });
  },
);

it("atomically consumes approval and creates the execution claim", async () => {
  store.injectFailure("before_atomic_commit");
  expect(await claimExecutionWithApproval(validApproval, expected, store, clock))
    .toEqual({ ok: false, error: "SECURITY_LEDGER_FULL" });
  expect(store.approvalConsumed(APPROVAL_A)).toBe(false);
  expect(store.executionClaimed(OPERATION_A)).toBe(false);
});

it("enforces 60/minute, three pending and ten-minute rejection cooldown", () => {
  expect(runSixtyRequests(limiter)).toHaveLength(60);
  expect(limiter.evaluate(request61)).toEqual({ allowed: false, wireError: "RATE_LIMITED", decisionReason: "WINDOW_LIMIT", retryAfterSeconds: "60" });
  expect(limiter.openApproval(pending4)).toEqual({ allowed: false, wireError: "RATE_LIMITED", decisionReason: "PENDING_LIMIT", retryAfterSeconds: "1" });
  expect(limiter.evaluate(rejectedEquivalentAtNineMinutes)).toEqual({ allowed: false, wireError: "RATE_LIMITED", decisionReason: "REJECTION_COOLDOWN", retryAfterSeconds: "60" });
});
```

- [ ] **Step 2: Run tests and verify missing approval APIs fail**

Run: `tools/run-node24 npm test -- protocol/test/approval.test.ts protocol/test/request-limiter.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL on missing approval/limiter modules.

- [ ] **Step 3: Implement one-shot credentials and deterministic limits**

Approval claims contain `approval_id`, all principal/session/device/operation IDs, capability, `parameters_digest`, backend kind, complete revision snapshot, `issued_at`, `expires_at` and one-use nonce. `ApprovalClaimStore.claimExecutionWithApproval()` validates first, then atomically marks `approval_id` consumed and writes the matching `operation_id + parameters_digest` execution claim in one durable transaction. A crash or storage failure cannot leave only one half committed; an exact command retransmission returns the existing claim/receipt, while a different operation or digest cannot reuse the approval. A revoke/regrant revision advance permanently rejects the old approval even if every other field still matches. The signature domain is `approval/device`.

`RequestLimiter` uses injected monotonic time and a rolling 60-second request window keyed by agent principal + device. The pending-operation dedupe key is exactly tenant + human + agent + Agent instance + workspace + session-or-job + device + backend + capability + parameters digest + complete revision snapshot, so requests from different principals or sessions never fold together. The maximum is three open approvals per agent/device. Rejection cooldown remains keyed by agent + device + capability + parameters digest for 600 seconds as approved. Equivalent pending requests return the existing operation; every limit response emits registered wire code `RATE_LIMITED`, a closed internal decision reason and deterministic `retry_after`.

Append exactly one Task 8 entry; issuing/claiming/consuming credentials and limiter results are internal actions, while the device prompt already travels in `operation_command`:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `approval_decision` | `app-to-bridge` | `approval/device` | `urn:open-android-intelligence:protocol:v1:message:approval_decision` |

The closed decision union represents approve-with-one-shot-credential, deny and expiry. Update the cumulative registry test before the RED run.

- [ ] **Step 4: Run approval and limiter tests**

Run: `tools/run-node24 npm test -- protocol/test/approval.test.ts protocol/test/request-limiter.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: all one-shot, mutation, expiry, dedupe and limit cases pass.

- [ ] **Step 5: Commit approval controls**

```bash
git add docs/protocol/v1/operations.md protocol/schemas/v1/approval.schema.json protocol/registries/v1/messages.json protocol/src protocol/test
git commit -m "feat(protocol): bind approvals and request limits"
```

### Task 9: Freeze the device-event wire identity contract

**Files:**
- Create: `docs/protocol/v1/events.md`
- Create: `protocol/schemas/v1/event.schema.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/src/event-contract.ts`
- Create: `protocol/test/event-contract.test.ts`
- Modify: `protocol/test/messages-registry.test.ts`

**Interfaces:**
- Produces: `validateDeviceEvent(value,authenticatedDevice)`, `validateEventAck(value,authenticatedBridge)`.
- Consumes: signed device envelope and revision snapshot; no Agent principal/session input.

- [ ] **Step 1: Write failing event identity tests**

```ts
it("does not route a device event using client-supplied Agent identity", () => {
  expect(() => validateDeviceEvent({ ...validEvent, agent_principal_id: "agent-b" }, authenticatedDeviceA))
    .toThrowError("SCHEMA_INVALID");
});

it("binds source epoch, cursor and capture revision to the authenticated device", () => {
  expect(validateDeviceEvent(validEvent, authenticatedDeviceA)).toMatchObject({
    deviceId: "device-a",
    routeByServerSubscriptionOnly: true,
  });
  expect(() => validateDeviceEvent(validEvent, authenticatedDeviceB))
    .toThrowError("AUTH_BINDING_MISMATCH");
});
```

Add same-tenant cross-principal/device, stale capture revision, malformed upsert/delete tombstone/loss marker union, forbidden session/Agent fields and event-ACK direction/key substitution cases.

- [ ] **Step 2: Run tests and verify missing event model fails**

Run: `tools/run-node24 npm test -- protocol/test/event-contract.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL because event schema and identity validator do not exist.

- [ ] **Step 3: Implement the event contract**

Each event contains `source_epoch`, `occurrence_id`, `record_key`, `record_revision`, `cursor`, `captured_at`, `event_kind`, source capability and capture-time revision snapshot. `event_kind` is exactly `upsert | delete_tombstone | loss_marker`; loss markers also carry closed `lost_from_cursor`, `lost_to_cursor`, `reason` fields.

P0a freezes only these signed fields, direction/domain, device binding and the rule that Bridge routing uses authenticated server-side subscriptions rather than an Agent ID supplied by the device. Durable cursor storage, contiguous-ACK advancement, out-of-order buffering, gap/resync and source collector behavior remain explicitly owned by P0b/P1a and are not implemented or claimed by this plan.

Append exactly this Task 9 delta:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `device_event` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:device_event` |
| `event_ack` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:event_ack` |

Update the cumulative registry test before the RED run. It must reject all Agent principal/instance/workspace/session/job fields in `device_event`, and reject a wrong direction or substituted Bridge key/domain for `event_ack`.

- [ ] **Step 4: Run event tests**

Run: `tools/run-node24 npm test -- protocol/test/event-contract.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: all event-union, binding, direction and schema-isolation cases pass.

- [ ] **Step 5: Commit event semantics**

```bash
git add docs/protocol/v1/events.md protocol/schemas/v1/event.schema.json protocol/registries/v1/messages.json protocol/src/event-contract.ts protocol/test/event-contract.test.ts protocol/test/messages-registry.test.ts
git commit -m "feat(protocol): freeze event wire contract"
```

### Task 10: Define artifact and live-stream tickets, PoP and lifecycle

**Files:**
- Create: `docs/protocol/v1/artifacts-streams.md`
- Create: `protocol/schemas/v1/channel-ticket.schema.json`
- Create: `protocol/schemas/v1/artifact.schema.json`
- Create: `protocol/schemas/v1/stream.schema.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/src/channel-ticket.ts`
- Create: `protocol/src/artifact-machine.ts`
- Create: `protocol/src/stream-machine.ts`
- Create: `protocol/test/channel-ticket.test.ts`
- Create: `protocol/test/artifact-machine.test.ts`
- Create: `protocol/test/stream-machine.test.ts`
- Modify: `protocol/test/messages-registry.test.ts`

**Interfaces:**
- Produces: `redeemTicket`, `verifyChannelPop`, `validateResumeTicket`, `reduceArtifact`, `acceptArtifactChunk`, `commitArtifact`, `reduceStream`, `grantCredit`, `sendStreamChunk`.
- Consumes: Bridge ticket signer, device/adapter verifier, stored operation binding, identity/revision, device connection generation and adapter credential generation.

- [ ] **Step 1: Write failing ticket, resume and credit tests**

```ts
it("requires holder-of-key proof and consumes a ticket once", async () => {
  expect(await redeemTicket(ticket, popSignedByWrongDevice, store, clock)).toEqual({ ok: false, error: "AUTH_FAILED" });
  expect(await redeemTicket(ticket, validPop, store, clock)).toMatchObject({ ok: true });
  expect(await redeemTicket(ticket, validPop, store, clock)).toEqual({ ok: false, error: "REPLAY_REJECTED" });
});

it("resumes artifacts only with a new ticket at the confirmed offset", () => {
  expect(validateResumeTicket(oldTicket, confirmedOffset)).toEqual({ ok: false, error: "REPLAY_REJECTED" });
  expect(validateResumeTicket(newTicketAtWrongOffset, confirmedOffset)).toEqual({ ok: false, error: "INTEGRITY_FAILED" });
  expect(validateResumeTicket(newTicketAtConfirmedOffset, confirmedOffset)).toEqual({ ok: true });
});

it("never resumes live streams and never exceeds granted byte credit", () => {
  expect(() => reduceStream(disconnectedStream, { type: "resume" })).toThrowError("INVALID_STATE_TRANSITION");
  expect(sendStreamChunk(streamWith1024Credit, chunkOf1025Bytes)).toEqual({
    ok: false,
    error: "FLOW_CONTROL_VIOLATION",
    decisionReason: "CREDIT_EXCEEDED",
  });
});
```

Add five-minute expiry, cross-tenant/session/device, same-tenant cross-human/agent-principal/agent-instance download, wrong device connection generation, wrong adapter credential generation, stale ticket after revoke/regrant, channel nonce replay, upload-ticket-before-final-hash, digest/length/MIME mutation, exact 4-file/25-MiB/50-MiB boundaries, below-baseline capability declaration, chunk sequence/hash, duplicate commit, message-before-artifact, 24-hour orphan expiry, cancellation and stream ticket revocation cases.

- [ ] **Step 2: Run tests and verify missing channel state fails**

Run: `tools/run-node24 npm test -- protocol/test/channel-ticket.test.ts protocol/test/artifact-machine.test.ts protocol/test/stream-machine.test.ts protocol/test/messages-registry.test.ts`

Expected: FAIL on missing ticket/artifact/stream modules.

- [ ] **Step 3: Implement closed ticket claims and state machines**

The common ticket payload binds ticket ID, tenant/human/agent principal, Agent instance, workspace, session-or-job, device, operation, direction, capability, the operation's current device `connection_generation`, explicit revision snapshot, issued/expiry time, limits and `channel_nonce`. The Bridge derives principal/session/device/operation/connection fields only from the stored server-authoritative operation binding created in Task 7, never from a ticket request payload. The Bridge generates the nonce as exactly 32 random bytes encoded as 43-character unpadded base64url before signing; it is a required closed field in both `device_channel_ticket` and `adapter_channel_ticket`. The full ticket outer object is canonical `{header,payload,signature}`, signed with `ticket/bridge`, so its payload digest and signature cover the exact nonce delivered to the holder. Define `ticket_digest = base64url_unpadded(SHA-256(JCS_UTF8(full signed {header,payload,signature})))` and `channel_nonce_digest = base64url_unpadded(SHA-256(base64url_decode(channel_nonce)))`; both are exactly 43-character base64url strings, and nonce decoding must yield exactly 32 bytes before either digest or redemption proceeds. Bridge persists `{ ticket_id, ticket_digest, channel_nonce_digest, holder_key_id, unconsumed }`; PoP must echo the signed nonce and no side channel may supply or replace it. The issuer/redeemer and TypeScript/Kotlin vectors use these exact preimages—never the nonce's encoded text, a JCS string wrapper or unsigned ticket bytes.

Channel open is a full signed JSON message branch, not string concatenation. Its payload is exactly `{ ticket_id, channel_nonce }`. The closed header is discriminated by holder: `device_channel_pop` uses the paired-device leaf and therefore carries/fences the current device `connection_generation`; `adapter_channel_pop` uses the adapter leaf and therefore carries/fences the current `adapter_credential_generation` and forbids device/pairing/connection header fields. The adapter ticket payload still binds the stored operation's current device `connection_generation`, which `redeemTicket` compares with Bridge device/operation state independently of the adapter header fence. Both headers carry protocol/schema/message type, message ID, holder `key_id`, registered direction, issued/expiry timestamps and payload digest. The holder signs canonical `{header,payload}` under `channel-pop/device` or `channel-pop/adapter` using Task 3's length-prefixed preimage. `redeemTicket` recomputes and constant-time compares both frozen digests, verifies the ticket, exact nonce bytes, holder/key/direction/context, stored session/job and current revisions/generations, then atomically consumes the store key `{ ticket_id, channel_nonce_digest, holder_key_id }`; signature failure, digest mismatch or replay makes no partial mutation. Fixed vectors cover both digest preimages/encodings, nonce length/encoding, field reordering, raw concatenation, cross-domain, cross-holder, device-connection and adapter-generation substitutions.

Artifact tickets additionally bind artifact ID, MIME, final byte length, final SHA-256, `start_offset`, `chunk_size` and byte ceiling. Each chunk hash and the final artifact digest use the exact raw-byte preimages frozen globally; no JCS/base64 text wrapper is hashed. `chunk_size` is a negotiated power of two from 65,536 through 1,048,576 bytes; v1 default is 262,144. The v1 advertised interoperability baseline is at most four artifacts, 26,214,400 bytes per artifact and 52,428,800 bytes per message, with JPEG/PNG/WebP, PDF and `text/plain` MIME values; a deployment may advertise stricter runtime limits but cannot claim the later P3 attachment exit. Resume offset must equal the receiver-confirmed sum of complete chunks. Live-stream tickets bind stream ID/nonce, codec, `config_digest = b64u(SHA-256(JCS_UTF8(config)))` over the exact closed negotiated config member, chunk/frame/byte/duration/rate ceilings and `max_in_flight_bytes`; they contain neither final digest nor resume offset. Before accepting a stream ticket, both endpoints recompute/constant-time compare `config_digest` against their stored negotiated config; mismatch consumes no ticket and opens no stream.

Artifact and stream reducers implement exactly the approved states. Stream credit is measured in payload bytes and every accepted chunk decrements it atomically. Disconnect/revocation/lock/stop closes the stream and releases buffered bytes; no raw frame is persisted.

Append exactly this Task 10 delta:

| message_type | direction | signature_domain | schema_id |
|---|---|---|---|
| `device_channel_ticket` | `bridge-to-app` | `ticket/bridge` | `urn:open-android-intelligence:protocol:v1:message:device_channel_ticket` |
| `adapter_channel_ticket` | `bridge-to-adapter` | `ticket/bridge` | `urn:open-android-intelligence:protocol:v1:message:adapter_channel_ticket` |
| `device_channel_pop` | `app-to-bridge` | `channel-pop/device` | `urn:open-android-intelligence:protocol:v1:message:device_channel_pop` |
| `adapter_channel_pop` | `adapter-to-bridge` | `channel-pop/adapter` | `urn:open-android-intelligence:protocol:v1:message:adapter_channel_pop` |

Ticket branches use a closed artifact/live-stream union. Atomic valid PoP consumption is redemption; no `ticket_redeem` message exists. Artifact/stream lifecycle states and binary channel chunk/credit/commit/close frames are not independent ES256 messages and therefore are absent from `messages.json`. Update the cumulative registry test before the RED run.

- [ ] **Step 4: Run channel and lifecycle tests**

Run: `tools/run-node24 npm test -- protocol/test/channel-ticket.test.ts protocol/test/artifact-machine.test.ts protocol/test/stream-machine.test.ts protocol/test/messages-registry.test.ts && tools/run-node24 npm run typecheck`

Expected: all PoP, isolation, resume, integrity and backpressure cases pass.

- [ ] **Step 5: Commit artifact/stream protocol**

```bash
git add docs/protocol/v1/artifacts-streams.md protocol/schemas/v1 protocol/registries/v1/messages.json protocol/src protocol/test
git commit -m "feat(protocol): add artifact and stream tickets"
```

### Task 11: Build deterministic fake actors and golden traces

**Files:**
- Create: `protocol/src/testing/fake-clock.ts`
- Create: `protocol/src/testing/fake-stores.ts`
- Create: `protocol/src/testing/fake-bridge.ts`
- Create: `protocol/src/testing/fake-device.ts`
- Create: `protocol/src/testing/fake-adapter.ts`
- Create: `protocol/src/testing/protocol-harness.ts`
- Create: `protocol/src/testing/trace-recorder.ts`
- Create: `protocol/src/audit.ts`
- Create: `protocol/tools/generate-vectors.ts`
- Create: `protocol/vectors/v1/manifest.json`
- Create: `protocol/vectors/v1/public-keys.json`
- Create: `protocol/test/golden-traces.test.ts`
- Create: `protocol/test/audit.test.ts`
- Modify: `package.json`

**Interfaces:**
- Produces: `FakeClock`, `FakeBridge`, `FakeDevice`, `FakeAdapter`, `ProtocolHarness.run(trace)`, `npm run vectors:generate`, `npm run vectors:check`.
- Consumes: all Task 3–10 pure interfaces; no production network or persistence.

- [ ] **Step 1: Write failing end-to-end fake traces**

```ts
it("retries a lost result ACK without repeating the side effect", async () => {
  const harness = ProtocolHarness.seeded("ack-loss-001");
  const trace = await harness.run("operation/ack-loss");
  expect(trace.sideEffectInvocations).toBe(1);
  expect(trace.adapterOutcome).toBe("succeeded");
  expect(trace.events).toContainEqual(expect.objectContaining({ type: "receipt_replayed" }));
});

it("crashes after the side effect into immutable result_unknown", async () => {
  const harness = ProtocolHarness.seeded("result-unknown-001");
  harness.faults.arm("after_side_effect_before_result");
  const trace = await harness.run("operation/crash-window");
  expect(trace.sideEffectInvocations).toBe(1);
  expect(trace.adapterOutcome).toBe("result_unknown");
  expect(trace.automaticRetryCount).toBe(0);
});

it.each(["body", "command_output", "notification_body", "screen_frame", "token", "unregistered_field"])(
  "rejects forbidden or unknown audit field %s",
  (field) => expect(() => sanitizeAuditEvent({ ...validAuditMetadata, [field]: "sentinel-secret" }))
    .toThrowError("AUDIT_FIELD_FORBIDDEN"),
);

it("rejects nested content smuggling and preserves only typed metadata", () => {
  expect(() => sanitizeAuditEvent({ ...validAuditMetadata, outcome: { body: "sentinel-secret" } }))
    .toThrowError("AUDIT_VALUE_INVALID");
  expect(sanitizeAuditEvent(validAuditMetadata)).toEqual(validAuditMetadata);
});
```

Add complete enrollment/connect and short-code tamper, adapter missing-principal/old-generation admission, device and stored-query authorization-domain separation, stale revision after revoke/regrant, HTTP/WSS reorder, key rotation, old connection fence, approval mutation, event-envelope principal injection, artifact resume, stream disconnect, same-tenant cross-principal plus cross-tenant isolation, error-precedence competition, SMS/L4 policy, forbidden-root surface and zero-retention-profile egress denial traces.

- [ ] **Step 2: Run traces and verify missing fakes fail**

Run: `tools/run-node24 npm test -- protocol/test/golden-traces.test.ts protocol/test/audit.test.ts`

Expected: FAIL because fake actors and harness are absent.

- [ ] **Step 3: Implement fakes and stable trace recording**

```ts
export type FaultPoint =
  | "before_execution_claim"
  | "after_execution_claim_before_side_effect"
  | "after_side_effect_before_result"
  | "after_result_before_bridge_ack"
  | "drop_result_ack"
  | "disconnect_transport";

export class ProtocolHarness {
  static seeded(seed: string): ProtocolHarness;
  readonly clock: FakeClock;
  readonly bridge: FakeBridge;
  readonly device: FakeDevice;
  readonly adapter: FakeAdapter;
  readonly faults: FaultController;
  run(traceId: string): Promise<RecordedTrace>;
}
```

All IDs, nonces, test signatures, timestamps and fault points derive from the named seed. No test or generator can read runtime/user data: every content-bearing fixture is a visibly synthetic constant, uses reserved `example.invalid` identities where applicable and is labeled `fixture_data_class=synthetic_non_sensitive`. Persistable trace output omits private keys and content payloads, retaining only wire/payload digests and stable enum/metadata results. Canonical raw wire is stored only in the separate conformance input fixture for a labeled synthetic case. Fake stores expose crash/restart snapshots without claiming production durability.

`sanitizeAuditEvent` reads Task 2's `field_contracts`, rejects every forbidden or unknown key before serialization, rejects nested objects/arrays except the explicitly typed decimal revision map, and validates identifier/timestamp/decimal/digest/closed-enum/byte-count bounds. It returns a newly allocated metadata-only object; no input body is copied by fallback or stringification. Every fake trace sends audit output through this sanitizer. A content sentinel injected into a synthetic request may exist only in the explicit conformance input bytes; a full leak scan must prove it is absent from persistable trace output, audit events, normalized results, diagnostics, test reports and vector expected-output fields.

- [ ] **Step 4: Generate and lock TypeScript vectors**

Add scripts:

```json
{
  "vectors:generate": "tsx protocol/tools/generate-vectors.ts --write",
  "vectors:check": "tsx protocol/tools/generate-vectors.ts --check"
}
```

`manifest.json` groups vectors under `canonical`, `crypto`, `enrollment`, `version`, `control`, `replay`, `rotation`, `authorization`, `operation`, `approval`, `events`, `artifact`, `stream`, `isolation`, `policy`, `egress` and `audit`. Every vector records `id`, `kind`, `security_property_ids`, `evidence_ids`, `fixture_data_class`, public key ID, authenticated context, injected clock/nonces, initial durable state, ordered actions and normalized expected results. The manifest also contains the exact closed `security_evidence_index` below; the generator fails unless each row's vector family exists, carries both the stated `security_property_id` and `evidence_id`, and its `direct_test` path exists. Byte-level wire cases additionally hold canonical RFC 4648 padded-Base64 `input_wire_b64`, `input_wire_digest = b64u(SHA-256(base64_decode(input_wire_b64)))` over those exact decoded bytes, and oracle-only padded-Base64 `signing_preimage_b64`; a producer interop case may add only a `test_signer_key_id` present in Task 3's closed test-only keyring. The three `kind:"replay_intent_metadata"` cases instead have the exact closed `fixture_input:PersistedReplayIntentMetadata`, padded-Base64 `metadata_jcs_b64` over the expected full JCS UTF-8 bytes and canonical decimal-string `metadata_jcs_byte_length`; their closed vector-schema branch forbids wire/preimage fields and any additional member. State/policy traces do not misuse `input_wire_digest`: they hold a closed `fixture_input` projection and `fixture_input_digest = b64u(SHA-256(JCS_UTF8(fixture_input)))`. Raw synthetic wire is committed only when the conformance operation genuinely consumes it. The generator and both SUTs reject a digest-source discriminator mismatch and fixed vectors distinguish raw wire bytes from Base64 text/JCS wrappers. Expected output never echoes input wire, content or private key material. `public-keys.json` contains distinct device, Bridge command and adapter current/next P-256 public keys; private halves remain under `test-only`, and wrong-role/current-next substitution vectors must fail.

Every ordered action records content-free projections of `state_before`, `state_after` and `state_delta`; payload-bearing fields are represented only by digest, byte count and enum. Side-effect fields include external invocations, claims, results, bytes, replay/key/approval/ticket mutations and audit count/content-sentinel. Every rejected action has `state_delta.external_invocations = 0`. Rejected control/channel actions also have zero non-audit durable delta. Enrollment intake is the only exception: any request may append at most one admitted IP timestamp, and a post-ticket rejection additionally records exactly one enrollment-ticket consumption plus at most one admitted principal timestamp; denied limiter calls append nothing and never extend the retry horizon. It has zero external invocation and zero other durable delta. Trace totals are separate and never overloaded as per-step deltas. `signing_preimage_b64` is diagnostic oracle input only for explicitly synthetic byte-level fixtures, is never copied into expected output and is never supplied to either conformance SUT during verification.

The committed set contains at least these thirty-two named families, with field mutations materialized as stable cases rather than generated randomly in CI:

1. `p0a.v1.canonical.profile`
2. `p0a.v1.signature.dev2br`
3. `p0a.v1.signature.br2dev`
4. `p0a.v1.signature.tamper-domain-matrix`
5. `p0a.v1.binding.auth-context-matrix`
6. `p0a.v1.replay.sequence-ledger`
7. `p0a.v1.connection.fence-reconnect`
8. `p0a.v1.key-rotation.lifecycle`
9. `p0a.v1.authorization.revision-lifecycle`
10. `p0a.v1.approval.single-use-atomic-claim`
11. `p0a.v1.operation.transition-matrix`
12. `p0a.v1.operation.idempotency`
13. `p0a.v1.operation.cancel-race`
14. `p0a.v1.operation.crash-cuts`
15. `p0a.v1.operation.reconcile`
16. `p0a.v1.artifact.upload-commit`
17. `p0a.v1.artifact.resume-abuse`
18. `p0a.v1.stream.lifecycle-abuse`
19. `p0a.v1.version.negotiate-downgrade`
20. `p0a.v1.version.schema-preserving-migration`
21. `p0a.v1.event-envelope.binding-matrix`
22. `p0a.v1.error.precedence-matrix`
23. `p0a.v1.policy.sms-send-always-confirms`
24. `p0a.v1.policy.l4-backend-deny-matrix`
25. `p0a.v1.isolation.same-tenant-principal-matrix`
26. `p0a.v1.audit.forbidden-content-rejected`
27. `p0a.v1.enrollment.ticket-transcript`
28. `p0a.v1.authorization.stored-query-domain`
29. `p0a.v1.adapter.admission-fail-closed`
30. `p0a.v1.egress.zero-retention-fail-closed`
31. `p0a.v1.schema.root-shell-absent`
32. `p0a.v1.capability.manifest-not-token`

Freeze this literal security evidence index; there is no inferred name conversion or fallback lookup:

| security_property_id | evidence_id | vector_family_id | direct_test |
|---|---|---|---|
| `SEC-001` | `auth.network-metadata-denied` | `p0a.v1.binding.auth-context-matrix` | `protocol/test/authorization.test.ts` |
| `SEC-002` | `auth.principal-injection-denied` | `p0a.v1.binding.auth-context-matrix` | `protocol/test/control-envelope.test.ts` |
| `SEC-003` | `auth.stored-query-independent` | `p0a.v1.authorization.stored-query-domain` | `protocol/test/data-query-grant.test.ts` |
| `SEC-004` | `auth.stale-revision-denied` | `p0a.v1.authorization.revision-lifecycle` | `protocol/test/authorization-revision.test.ts` |
| `SEC-005` | `operation.ledger-full-fails-closed` | `p0a.v1.operation.crash-cuts` | `protocol/test/execution-ledger.test.ts` |
| `SEC-006` | `operation.ack-loss-executes-once` | `p0a.v1.operation.idempotency` | `protocol/test/execution-ledger.test.ts` |
| `SEC-007` | `approval.parameter-mutation-denied` | `p0a.v1.approval.single-use-atomic-claim` | `protocol/test/approval.test.ts` |
| `SEC-008` | `isolation.cross-tenant-denied` | `p0a.v1.isolation.same-tenant-principal-matrix` | `protocol/test/authorization.test.ts` |
| `SEC-009` | `connection.old-generation-fenced` | `p0a.v1.connection.fence-reconnect` | `protocol/test/control-envelope.test.ts` |
| `SEC-010` | `audit.forbidden-content-rejected` | `p0a.v1.audit.forbidden-content-rejected` | `protocol/test/audit.test.ts` |
| `SEC-011` | `egress.zero-retention-unknown-denied` | `p0a.v1.egress.zero-retention-fail-closed` | `protocol/test/egress-policy.test.ts` |
| `SEC-012` | `schema.root-shell-absent` | `p0a.v1.schema.root-shell-absent` | `protocol/test/capability-manifest.test.ts` |
| `SEC-013` | `auth.adapter-principal-missing` | `p0a.v1.adapter.admission-fail-closed` | `protocol/test/adapter-admission.test.ts` |
| `SEC-014` | `auth.manifest-is-not-token` | `p0a.v1.capability.manifest-not-token` | `protocol/test/capability-manifest.test.ts` |
| `SEC-015` | `policy.sms-send-always-confirms` | `p0a.v1.policy.sms-send-always-confirms` | `protocol/test/risk-policy.test.ts` |
| `SEC-016` | `policy.l4-always-denied` | `p0a.v1.policy.l4-backend-deny-matrix` | `protocol/test/risk-policy.test.ts` |

`golden-traces.test.ts` deep-compares this index, proves its `evidence_id` set equals Task 2's sixteen literal `properties[*].tests[0]` values, rejects duplicate/unreferenced IDs and verifies every indexed vector case produces the expected security verdict. Thus Task 13 resolves a committed executable entity rather than treating a registry string or arbitrary existing path as evidence.

Run: `tools/run-node24 npm run vectors:generate && tools/run-node24 npm run vectors:check && tools/run-node24 npm test`

Expected: generator check reports zero changed vectors and the full TypeScript suite passes.

- [ ] **Step 5: Commit fakes and vectors**

```bash
git add package.json package-lock.json protocol/src/testing protocol/src/audit.ts protocol/tools protocol/vectors protocol/test/golden-traces.test.ts protocol/test/audit.test.ts
git commit -m "test(protocol): add deterministic golden traces"
```

### Task 12: Add an independent Kotlin/JVM conformance consumer

**Files:**
- Create: `conformance/SUT.md`
- Create: `conformance/schemas/sut-request-v1.schema.json`
- Create: `conformance/schemas/sut-response-v1.schema.json`
- Create: `protocol/tools/conformance-cli.ts`
- Create: `protocol/tools/run-conformance.ts`
- Create: `protocol/tools/cross-language-interop.ts`
- Create: `conformance/kotlin/settings.gradle.kts`
- Create: `conformance/kotlin/build.gradle.kts`
- Create: `conformance/kotlin/gradle.properties`
- Create: `conformance/kotlin/toolchains.json`
- Create: `conformance/kotlin/gradle/verification-metadata.xml`
- Create: `conformance/kotlin/gradle/wrapper/gradle-wrapper.jar`
- Create: `conformance/kotlin/gradle/wrapper/gradle-wrapper.properties`
- Create: `conformance/kotlin/gradlew`
- Create: `conformance/kotlin/gradlew.bat`
- Create: `conformance/kotlin/run-gradle`
- Create: `conformance/kotlin/run-sut`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/CanonicalJson.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/Es256.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/SchemaValidation.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/VectorModels.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/ReferenceReducers.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/SutMain.kt`
- Create: `conformance/kotlin/src/test/kotlin/life/agent/protocol/GoldenVectorTest.kt`

**Interfaces:**
- Produces: Gradle `test`/`installDist`, stable TypeScript/Kotlin NDJSON launchers, and ABI operations `canonicalize`, `canonicalize_replay_intent_metadata`, `digest`, `encode_sign_envelope`, `verify_envelope`, `validate_schema`, `negotiate_version`, `accept_sequence`, `authorize_device`, `authorize_stored_query`, `evaluate_risk`, `evaluate_egress`, `claim_with_approval`, `transition_operation`, `validate_event_contract`, `redeem_ticket`, `reduce_artifact`, `reduce_stream`, `migrate`, `sanitize_audit` and `run_trace`.
- Consumes: frozen protocol profile/schema/registry/vector JSON plus Task 3's locked test-only signer keyring only for producer operations; it must not import or execute TypeScript reference code, accept caller-supplied private JWKs or use fixture keys outside conformance.

- [ ] **Step 1: Configure the verified Gradle wrapper and add the failing Kotlin vector test**

Create the settings/build files with only Maven Central and Gradle Plugin Portal repositories, exact dependency versions, Eclipse Temurin 21.0.11+10 Java toolchain and strict dependency verification. Commit `toolchains.json` with the exact Temurin release, Linux x64 target, official asset URL and SHA-256 `4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de` used by local bootstrap and CI. Do not accept a different JDK build merely because its major version is 21; a different host architecture needs a separately reviewed URL/checksum record before it can produce release evidence.

Add executable `conformance/kotlin/run-gradle`. It accepts Gradle arguments only, downloads the single `toolchains.json` JDK entry into ignored `/.toolchains/` when absent, verifies the retained archive hash on every invocation, extracts atomically, validates Linux x64 plus the exact Temurin vendor/runtime/build output, and executes the repository `gradlew` with its `JAVA_HOME` and `PATH` scoped to that child. It never falls back to an ambient Java or Gradle. Task 12 Step 1 uses the same URL/hash manually only because the wrapper does not exist yet; Step 2 onward and CI use this committed launcher.

Download the official Gradle 9.6.1 binary ZIP into an ephemeral directory, verify its SHA-256 is `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`, extract it and invoke that absolute bootstrap binary to generate the wrapper. Never use an ambient bare `gradle`. Set `distributionSha256Sum` to the same value and verify `gradle-wrapper.jar` has SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. Then generate and manually review SHA-256 dependency verification metadata after explicitly resolving every resolvable compile, runtime and test configuration:

```bash
P0A_BOOTSTRAP_DIR="$(mktemp -d)"
curl --fail --location --proto '=https' --tlsv1.2 \
  --output "$P0A_BOOTSTRAP_DIR/temurin-21.0.11+10.tar.gz" \
  https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz
printf '%s  %s\n' \
  4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de \
  "$P0A_BOOTSTRAP_DIR/temurin-21.0.11+10.tar.gz" | sha256sum --check
tar -xzf "$P0A_BOOTSTRAP_DIR/temurin-21.0.11+10.tar.gz" -C "$P0A_BOOTSTRAP_DIR"
P0A_JAVA_HOME="$P0A_BOOTSTRAP_DIR/jdk-21.0.11+10"
"$P0A_JAVA_HOME/bin/java" -version
curl --fail --location --proto '=https' --tlsv1.2 \
  --output "$P0A_BOOTSTRAP_DIR/gradle-9.6.1-bin.zip" \
  https://services.gradle.org/distributions/gradle-9.6.1-bin.zip
printf '%s  %s\n' \
  9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14 \
  "$P0A_BOOTSTRAP_DIR/gradle-9.6.1-bin.zip" | sha256sum --check
unzip -q "$P0A_BOOTSTRAP_DIR/gradle-9.6.1-bin.zip" -d "$P0A_BOOTSTRAP_DIR"
env PATH="$P0A_JAVA_HOME/bin:$PATH" \
  "$P0A_BOOTSTRAP_DIR/gradle-9.6.1/bin/gradle" \
  -p conformance/kotlin wrapper --gradle-version 9.6.1 --distribution-type bin
printf '%s  %s\n' \
  497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7 \
  conformance/kotlin/gradle/wrapper/gradle-wrapper.jar | sha256sum --check
conformance/kotlin/run-gradle --no-daemon -p conformance/kotlin \
  --write-verification-metadata sha256 resolveAllDependencies
```

`P0A_BOOTSTRAP_DIR` must be an explicit `mktemp -d` path and must never be the repository, workspace root, `$HOME` or `~`. `resolveAllDependencies` is a small build task created in `build.gradle.kts`; it traverses every resolvable configuration before the empty test task so verification metadata cannot omit a dependency used only by a later source set or launcher. Expected: the ZIP and wrapper JAR hashes match exactly; metadata covers all compile/runtime/test artifacts; subsequent builds run in strict verification mode.

```kotlin
class GoldenVectorTest {
    @Test
    fun `every v1 vector matches the independent Kotlin implementation`() {
        val manifest = VectorLoader.load("../../protocol/vectors/v1/manifest.json")
        val failures = manifest.vectors.mapNotNull { VectorRunner.run(it) }
        assertEquals(emptyList<String>(), failures)
    }
}
```

This task is the first Kotlin execution gate for the TypeScript byte/preimage fixtures introduced in Task 5 and promoted to the Task 11 manifest; no earlier task may claim Kotlin equivalence merely from a TypeScript-local assertion. For each `replay_intent_metadata` vector, both SUTs' dedicated `canonicalize_replay_intent_metadata` operation must independently validate the recursively closed device/adapter projection, reject missing/extra/omitted-null or branch-mismatched members, produce the complete RFC 8785 UTF-8 bytes, and return their exact decimal byte length. The driver decodes the vector's padded `metadata_jcs_b64` and compares all bytes and the length; neither SUT receives those expected fields. This includes the device nullable branch, adapter nullable branch, sorted nonempty adapter scope array and empty adapter scope array.

Add an interop test that asks Kotlin to encode and sign an unsigned semantic device envelope and TypeScript to verify the resulting raw wire, then asks TypeScript to encode/sign a Bridge command and Kotlin to verify its raw wire. The producer receives semantic input plus a `test_signer_key_id` that must resolve through the closed test-only keyring; it receives no private JWK, prebuilt canonical bytes or preimage. The consumer receives only `wire_b64` encoded as canonical RFC 4648 standard Base64 with required padding, the public key ring, authenticated context and injected clock, and must independently decode the exact bytes then reconstruct JCS bytes, payload digest, domain and length-prefixed preimage. Non-canonical/URL-safe/incorrectly padded Base64 is rejected rather than normalized. Include unknown signer ID, private-key-in-request, non-canonical raw-wire, one-byte mutation and cross-role key negatives. Assertions compare semantic output and low-S validity, not freshly generated ECDSA signature bytes.

- [ ] **Step 2: Run Gradle and verify missing runner classes fail**

Run: `conformance/kotlin/run-gradle --no-daemon -p conformance/kotlin test --tests life.agent.protocol.GoldenVectorTest`

Expected: compilation FAIL because `VectorLoader` and `VectorRunner` are absent.

- [ ] **Step 3: Implement the independent Kotlin conformance path**

Use Gradle wrapper 9.6.1, Kotlin JVM/serialization plugins 2.4.10, Eclipse Temurin 21.0.11+10 toolchain, `kotlinx-serialization-json:1.11.0`, `io.github.erdtman:java-json-canonicalization:1.1`, `com.networknt:json-schema-validator:3.0.6` and Kotlin/JUnit 5 tests. Dependency verification metadata, toolchain evidence, distribution checksum and wrapper JAR checksum must remain committed and pass CI validation.

`conformance/SUT.md` and the two closed `sut-abi-v1` schemas define one canonical JSON request and response per NDJSON line. Each request contains `case_id`, operation name, `profile_id`, injected `now`, input bytes/state and authenticated context; only `encode_sign_envelope` may additionally contain `test_signer_key_id`, and all private-key/JWK fields are forbidden. Each response contains the same `case_id`, `ok`, and either normalized `value` or `{ failure_layer, code, stage }`; it can never contain signer key material. Each SUT resolves the ID locally from the locked test-only keyring and logs only the ID. `run_trace` receives profile, fixed clock/nonces, authenticated context, initial state and ordered actions, but never the vector's expected result. Stdout is NDJSON only; diagnostics go to stderr and never include payload body or key material.

`CanonicalJson` uses the Java RFC 8785 implementation and compares complete raw canonical bytes. `Es256` parses P-256 JWK coordinates, converts strict DER/P1363 in both directions, normalizes every test-produced JCA signature to low-S, and rejects high-S on verification. `SchemaValidation` loads the committed Draft 2020-12 schemas and mandatory `decimal-u64` format. `ReferenceReducers` independently implements every action required by all 32 families, including the exact persisted replay-intent metadata projection and length: enrollment, version/replay/fence/rotation, adapter admission, both authorization domains and revisions, capability-manifest non-authority, risk/egress, atomic approval claim, operation/crash/reconciliation, event identity, artifact, stream, isolation, error precedence, migration, forbidden surfaces and audit sanitization. `SutMain` implements the same NDJSON ABI as `protocol/tools/conformance-cli.ts` without importing TypeScript reference code or expected vector output. A successful complete Kotlin run emits the stable evidence ID `conformance.kotlin.all-vectors` with `manifest_digest = b64u(SHA-256(JCS_UTF8(parsed manifest.json)))` and counts only.

The Kotlin application plugin sets a fixed main class and distribution name `open-android-intelligence-kotlin-sut`; `installDist` creates its launcher, while committed executable `conformance/kotlin/run-sut` resolves and `exec`s that launcher without shell evaluation of request data. `protocol/tools/run-conformance.ts` starts each SUT once, validates every request/response against the ABI schemas, sends every committed vector through `run_trace`, and compares the normalized response with the driver's held expected result. This path is mandatory even if language-local unit tests pass. Runners report vector ID plus expected/actual enums only; they never log vector payload bodies.

- [ ] **Step 4: Run cross-language conformance**

Run: `tools/run-node24 npm run vectors:check && conformance/kotlin/run-gradle --no-daemon -p conformance/kotlin test installDist && tools/run-node24 npx tsx protocol/tools/run-conformance.ts && tools/run-node24 npx tsx protocol/tools/cross-language-interop.ts`

Expected: TypeScript regeneration makes no changes; every Kotlin unit test passes; both NDJSON SUTs independently pass every vector; and Kotlin→TypeScript plus TypeScript→Kotlin canonical producer/consumer directions verify, including mutation negatives.

- [ ] **Step 5: Commit Kotlin conformance**

```bash
git add conformance protocol/tools/conformance-cli.ts protocol/tools/run-conformance.ts protocol/tools/cross-language-interop.ts
git commit -m "test(protocol): verify vectors with Kotlin"
```

### Task 13: Freeze coverage gates and P0a release evidence

**Files:**
- Create: `docs/protocol/v1/p0a-acceptance.md`
- Create: `docs/protocol/v1/README.md`
- Create: `protocol/tools/check-spec-lock.ts`
- Create: `protocol/tools/check-requirement-coverage.ts`
- Create: `protocol/tools/check-forbidden-surfaces.ts`
- Create: `protocol/spec-lock.json`
- Create: `protocol/test/release-gates.test.ts`
- Create: `protocol/test-only/spec-lock/source.schema.json`
- Create: `protocol/test-only/spec-lock/spec-lock.json`
- Modify: `conformance/SUT.md`
- Create: `.github/workflows/protocol.yml`
- Modify: `package.json`

**Interfaces:**
- Produces: `npm run schema:check`, `npm run coverage:check`, `npm run forbidden:check`, `npm run check`, immutable semantic hash manifest and CI evidence.
- Consumes: every profile, schema, registry, model, requirement/security property, migration fixture, ABI and vector plus both language test suites.

- [ ] **Step 1: Write failing lock and coverage-gate tests**

```ts
it("never mutates an already locked schema ID", async () => {
  const result = await checkSpecLock({
    repositoryRoot: "protocol/test-only/spec-lock",
    lock: "spec-lock.json",
    include: ["source.schema.json"],
  });
  expect(result.changedExistingEntries).toEqual([]);
});

it("maps every P0a requirement and security property to existing evidence", async () => {
  const result = await checkRequirementCoverage();
  expect(result).toMatchObject({
    missingRequirements: [],
    missingSecurityProperties: [],
    outOfScopeEntries: [],
  });
});
```

`check-forbidden-surfaces` semantically enumerates every accepted production schema value, registry entry and exported command/API surface and proves none can represent arbitrary Root Shell, generic exec, script upload/interpreter, dynamic Root action registration or user automation rule DSL. Dedicated negative tests/test-only vectors may contain the forbidden strings as inputs; docs, the checker's deny fixture and rejected vector payloads are excluded from literal scanning, while their expected verdict must be reject.

- [ ] **Step 2: Run the gates and verify missing lock/tools fail**

Run: `tools/run-node24 npm test -- protocol/test/release-gates.test.ts`

Expected: FAIL because the check tools are absent; the test uses the isolated fixture lock and never requires or creates the release `protocol/spec-lock.json`.

- [ ] **Step 3: Implement immutable semantic locks and coverage gates**

Finalize Task 13's edits to `conformance/SUT.md` and all normative protocol documents before any release lock is generated. `spec-lock.json` uses `repositoryRoot: "."` and an explicit allowlist covering `protocol/profile/**`, production/conformance `protocol/schemas/**`, `protocol/registries/**`, `protocol/model/**`, `protocol/test-only/keys/**`, `protocol/test-only/migration/**`, `conformance/SUT.md`, `conformance/schemas/**`, vector manifest/public keys and every vector file referenced by that manifest. It also locks the normative `docs/protocol/v1/{threat-model,wire-format,enrollment,versioning-and-migration,control-envelope,authorization,operations,events,artifacts-streams}.md` files. It excludes the isolated checker fixture, implementation/tests, build output, reports, non-normative README files and `docs/protocol/v1/p0a-acceptance.md` so the acceptance record can contain the final lock digest without self-reference.

Each lock entry stores repository-relative path, `$id` where applicable and SHA-256. `--update` never replaces an existing path, `$id` or hash. A future negotiated line may only append one complete new-version bundle: `protocol/profile/vN.json`, `protocol/schemas/vN/**`, `protocol/registries/vN/**` including that line's immutable versions registry, `protocol/model/vN/**`, `docs/protocol/vN/**`, version-namespaced migration fixtures and vectors, plus a new versioned `conformance/SUT-vN.md`/ABI schema when the ABI changes. Every added path and `$id` must be new, the bundle must declare its predecessor and migration policy, and the old v1 registry/SUT/vector/docs entries remain byte-identical; the negotiator aggregates immutable per-version registries rather than editing `v1/versions.json`. Partial bundles and additions outside a declared new version namespace fail. `--check` is read-only and fails on missing, additional or changed allowlisted artifacts. `checkRequirementCoverage` resolves every `P0A-001..022` artifact/evidence path and every `SEC-001..016` evidence ID through Task 11's literal `security_evidence_index`; it deep-compares the index to Task 2's exact SEC→evidence mapping, requires the referenced vector family/case and direct-test path, rejects missing/duplicate/unindexed IDs, rejects any registry entry whose owner phase is later than P0a, and specifically requires `SEC-010` to resolve to the audit implementation, `protocol/test/audit.test.ts` and `p0a.v1.audit.forbidden-content-rejected` vector rather than merely a registry string.

Add these scripts before creating the release lock:

```json
{
  "schema:check": "tsx protocol/tools/check-spec-lock.ts --check",
  "coverage:check": "tsx protocol/tools/check-requirement-coverage.ts",
  "forbidden:check": "tsx protocol/tools/check-forbidden-surfaces.ts",
  "prelock:check": "npm run typecheck && npm test && npm run vectors:check && npm run coverage:check && npm run forbidden:check",
  "check": "npm run prelock:check && npm run schema:check"
}
```

- [ ] **Step 4: Add CI and run the complete pre-lock verification**

GitHub Actions runs on a fixed `ubuntu-24.04` x64 runner, executes Node only through `tools/run-node24`, and executes Gradle only through `conformance/kotlin/run-gradle`, thereby using exactly the Temurin 21.0.11+10 artifact/checksum declared in `toolchains.json`. Every third-party action reference is a full 40-character commit SHA with its human-readable release tag in a comment; floating major tags are forbidden. Freeze two explicit stages: the Step 4 pre-lock validation runs `tools/run-node24 npm ci`, `tools/run-node24 npm run prelock:check`, `conformance/kotlin/run-gradle --no-daemon -p conformance/kotlin test installDist` under strict dependency verification, `tools/run-node24 npx tsx protocol/tools/run-conformance.ts`, and `tools/run-node24 npx tsx protocol/tools/cross-language-interop.ts`; it does not call `schema:check` before `protocol/spec-lock.json` exists. The committed release CI gate is enabled only after Step 5 creates the lock and replaces only the Node gate with `tools/run-node24 npm run check`, then runs the same Kotlin/conformance commands. Both stages first assert `uname -m` is `x86_64` and validate both launchers, `toolchains.json`, the committed wrapper JAR SHA-256 and `distributionSha256Sum` against the Task 1/12 values. They upload only test reports and enum failures; raw vector bodies, fixture private keys and wire payloads are not printed as artifacts.

Run: `tools/run-node24 npm ci && tools/run-node24 npm run prelock:check && conformance/kotlin/run-gradle --no-daemon -p conformance/kotlin test installDist && tools/run-node24 npx tsx protocol/tools/run-conformance.ts && tools/run-node24 npx tsx protocol/tools/cross-language-interop.ts`

Expected: Node and Kotlin suites pass, both signing directions interoperate, vector generation changes nothing, requirement coverage is complete, forbidden surfaces are absent and no generated artifact outside the not-yet-created release lock changes.

- [ ] **Step 5: Generate and verify the final semantic lock**

Run `tools/run-node24 npx tsx protocol/tools/check-spec-lock.ts --update` once, review its complete new-entry list against the explicit allowlist, then run it a second time and require an empty change list. Run `tools/run-node24 npm run schema:check` and verify both the command and `--check` leave the lock byte-identical. Record `spec_lock_file_digest = b64u(SHA-256(exact committed protocol/spec-lock.json raw bytes))` for the acceptance record; this file digest deliberately hashes bytes, while every individual lock entry hashes the exact raw bytes of its repository-relative target. A manually authored digest or an `--update` that changes an existing entry fails the task.

- [ ] **Step 6: Write acceptance evidence, re-run the closed gate and commit**

`p0a-acceptance.md` must record:

- approved design commit;
- protocol/schema version and `spec-lock.json` digest;
- exact algorithm/domain/size/version/rotation/replay decisions;
- positive and negative vector counts by manifest group;
- TypeScript, Kotlin and bidirectional TypeScript↔Kotlin interop command results;
- mapping for `P0A-001`–`P0A-022`;
- mapping for `SEC-001`–`SEC-016`;
- known residual risks from the threat model;
- explicit statement that production Bridge, Android persistence/Keystore, real transport and upstream adapters remain P0b–P0f work.

The acceptance record cites the actual successful Step 4 pre-lock commands, Step 5 lock digest/checks and the final closed-gate command below. After writing it, run `tools/run-node24 npm run check`, `tools/run-node24 npm run coverage:check`, `tools/run-node24 npm run forbidden:check`, `git diff --check`, and inspect `git status --short`; before commit, status must contain only the Task 13 release artifacts listed in this task. If any check fails or any locked input changes, regenerate/review the lock, update the evidence and repeat the closed gate.

```bash
git add package.json package-lock.json docs/protocol/v1 protocol conformance/SUT.md .github/workflows/protocol.yml
git commit -m "docs(protocol): freeze P0a contract"
```

## Final Verification

Run these smoke commands from the repository root after the Task 13 commit; the equivalent full conformance results are already recorded by Task 13 Step 4/6:

```bash
tools/run-node24 npm ci
tools/run-node24 npm run check
conformance/kotlin/run-gradle --no-daemon -p conformance/kotlin test installDist
tools/run-node24 npx tsx protocol/tools/run-conformance.ts
tools/run-node24 npx tsx protocol/tools/cross-language-interop.ts
git diff --check
git status --short
```

Expected:

- Node typecheck, schema validation, unit/property tests, traces, vector regeneration, security coverage and forbidden-surface gates all exit 0.
- Kotlin independently consumes every frozen vector and exits 0; Kotlin→TypeScript and TypeScript→Kotlin signing/verification both pass.
- `git diff --check` reports nothing.
- `git status --short` reports nothing after the final commit.
- P0a may be declared frozen only when all above evidence is recorded in `docs/protocol/v1/p0a-acceptance.md`; otherwise P0b–P3 remain blocked.
