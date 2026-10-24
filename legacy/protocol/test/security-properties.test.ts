import { describe, expect, it } from "vitest";
import requirements from "../registries/v1/p0a-requirements.json" with { type: "json" };
import properties from "../registries/v1/security-properties.json" with { type: "json" };
import audit from "../registries/v1/audit-fields.json" with { type: "json" };
import { PROTOCOL_VERSION } from "../src/version.js";

const expectedRequirementBindings = [
  { id: "P0A-001", spec_refs: ["§10.1", "§10.2"], requirement: "canonical profile, bounds, digest and ES256 bytes", artifacts: ["protocol/profile/v1.json", "protocol/src/encoding.ts", "protocol/src/crypto.ts"], evidence: ["p0a.v1.canonical.profile", "p0a.v1.signature.dev2br", "p0a.v1.signature.br2dev"] },
  { id: "P0A-002", spec_refs: ["§6.1"], requirement: "ticket-owned enrollment, mutual transcript and human short code", artifacts: ["protocol/src/enrollment.ts", "protocol/src/pairing-transcript.ts"], evidence: ["p0a.v1.enrollment.ticket-transcript"] },
  { id: "P0A-003", spec_refs: ["§10.2"], requirement: "closed version registry, highest-common selection and downgrade refusal", artifacts: ["protocol/registries/v1/versions.json", "protocol/src/version-negotiation.ts"], evidence: ["p0a.v1.version.negotiate-downgrade"] },
  { id: "P0A-004", spec_refs: ["§10.2"], requirement: "message-specific signed unions and server-authoritative identity binding", artifacts: ["protocol/registries/v1/messages.json", "protocol/schemas/v1/control-envelope.schema.json"], evidence: ["p0a.v1.binding.auth-context-matrix"] },
  { id: "P0A-005", spec_refs: ["§10.2"], requirement: "shared replay space, connection fence and authenticated key rotation", artifacts: ["protocol/src/replay-window.ts", "protocol/src/connection-fence.ts", "protocol/src/key-ring.ts"], evidence: ["p0a.v1.replay.sequence-ledger", "p0a.v1.connection.fence-reconnect", "p0a.v1.key-rotation.lifecycle"] },
  { id: "P0A-006", spec_refs: ["§7.2"], requirement: "real-time device authorization conjunction", artifacts: ["protocol/src/authorization.ts"], evidence: ["p0a.v1.authorization.revision-lifecycle"] },
  { id: "P0A-007", spec_refs: ["§7.2", "§9.3"], requirement: "independent stored-query grant, signed ACK, lineage, TTL and tombstone", artifacts: ["protocol/src/data-query-grant.ts"], evidence: ["p0a.v1.authorization.stored-query-domain"] },
  { id: "P0A-008", spec_refs: ["§9.2"], requirement: "pairing/authorization/scope revision transition and stale-object invalidation", artifacts: ["protocol/src/authorization-revision.ts", "protocol/model/v1/revision-events.json"], evidence: ["p0a.v1.authorization.revision-lifecycle"] },
  { id: "P0A-009", spec_refs: ["§7.3"], requirement: "tenant plus same-tenant principal/device/session isolation", artifacts: ["protocol/src/authorization.ts"], evidence: ["p0a.v1.isolation.same-tenant-principal-matrix"] },
  { id: "P0A-010", spec_refs: ["§7.2", "§10.3"], requirement: "descriptive-only capability manifest and adapter principal fail-closed", artifacts: ["protocol/src/capability-manifest.ts", "protocol/src/adapter-admission.ts"], evidence: ["p0a.v1.adapter.admission-fail-closed"] },
  { id: "P0A-011", spec_refs: ["§11.1", "§11.2", "§11.3"], requirement: "risk/sensitivity/backend policy, SMS per-item approval and L4 denial", artifacts: ["protocol/src/risk-policy.ts", "protocol/registries/v1/capabilities.json"], evidence: ["p0a.v1.policy.sms-send-always-confirms", "p0a.v1.policy.l4-backend-deny-matrix"] },
  { id: "P0A-012", spec_refs: ["§4.2", "§9.4"], requirement: "zero-retention evidence and body-egress fail-closed decision", artifacts: ["protocol/src/egress-policy.ts"], evidence: ["p0a.v1.egress.zero-retention-fail-closed"] },
  { id: "P0A-013", spec_refs: ["§10.4", "§16.1"], requirement: "separate request status, terminal outcome, operation reason and protocol error", artifacts: ["protocol/schemas/v1/operation.schema.json", "protocol/src/operation-machine.ts"], evidence: ["p0a.v1.operation.transition-matrix"] },
  { id: "P0A-014", spec_refs: ["§10.4", "§10.5"], requirement: "offline, expiry, cancel-race and immutable reconciliation semantics", artifacts: ["protocol/src/operation-machine.ts"], evidence: ["p0a.v1.operation.cancel-race", "p0a.v1.operation.reconcile"] },
  { id: "P0A-015", spec_refs: ["§10.5"], requirement: "durable execution claim, idempotency, crash cuts and receipt replay", artifacts: ["protocol/src/execution-ledger.ts"], evidence: ["p0a.v1.operation.idempotency", "p0a.v1.operation.crash-cuts"] },
  { id: "P0A-016", spec_refs: ["§10.6"], requirement: "approval binding, atomic consumption and anti-harassment limits", artifacts: ["protocol/src/approval.ts", "protocol/src/request-limiter.ts"], evidence: ["p0a.v1.approval.single-use-atomic-claim"] },
  { id: "P0A-017", spec_refs: ["§9.5", "§10.2"], requirement: "device-event/event-ACK wire identity contract only", artifacts: ["protocol/src/event-contract.ts"], evidence: ["p0a.v1.event-envelope.binding-matrix"] },
  { id: "P0A-018", spec_refs: ["§10.7"], requirement: "artifact ticket, PoP, integrity, resume and lifecycle contract", artifacts: ["protocol/src/channel-ticket.ts", "protocol/src/artifact-machine.ts"], evidence: ["p0a.v1.artifact.upload-commit", "p0a.v1.artifact.resume-abuse"] },
  { id: "P0A-019", spec_refs: ["§10.7"], requirement: "non-resumable live-stream ticket, credit and close contract", artifacts: ["protocol/src/channel-ticket.ts", "protocol/src/stream-machine.ts"], evidence: ["p0a.v1.stream.lifecycle-abuse"] },
  { id: "P0A-020", spec_refs: ["§16.1", "§16.3"], requirement: "closed error precedence and content-free audit contract", artifacts: ["protocol/registries/v1/errors.json", "protocol/src/audit.ts"], evidence: ["p0a.v1.error.precedence-matrix", "p0a.v1.audit.forbidden-content-rejected"] },
  { id: "P0A-021", spec_refs: ["§11.4", "§17.1"], requirement: "no arbitrary Root Shell, generic exec, scripts or automation DSL in accepted surfaces", artifacts: ["protocol/registries/v1/capabilities.json", "protocol/tools/check-forbidden-surfaces.ts"], evidence: ["p0a.v1.schema.root-shell-absent"] },
  { id: "P0A-022", spec_refs: ["§10.2", "§19.1"], requirement: "immutable migration rules, deterministic fakes and independent cross-language evidence", artifacts: ["protocol/src/migration.ts", "conformance/SUT.md"], evidence: ["p0a.v1.version.schema-preserving-migration", "conformance.kotlin.all-vectors"] },
] as const;

const expectedSecurityBindings = [
  { id: "SEC-001", property: "IP、MagicDNS、Tailscale identity/header 不产生授权", evidence: "auth.network-metadata-denied" },
  { id: "SEC-002", property: "服务端认证上下文是 principal/device binding 权威", evidence: "auth.principal-injection-denied" },
  { id: "SEC-003", property: "实时设备授权与 stored-query grant 是独立判定域", evidence: "auth.stored-query-independent" },
  { id: "SEC-004", property: "pairing/epoch/scope revision 精确匹配，撤销重授不复活旧对象", evidence: "auth.stale-revision-denied" },
  { id: "SEC-005", property: "execution claim 未持久化前不调用副作用", evidence: "operation.ledger-full-fails-closed" },
  { id: "SEC-006", property: "同一 operation 不因 transport/adapter 重试自动重复副作用", evidence: "operation.ack-loss-executes-once" },
  { id: "SEC-007", property: "approval 单次使用并绑定完整参数、身份、设备和 revision", evidence: "approval.parameter-mutation-denied" },
  { id: "SEC-008", property: "tenant/user/device/session/artifact/stream 不能交叉复用", evidence: "isolation.cross-tenant-denied" },
  { id: "SEC-009", property: "新 connection generation fence 旧连接", evidence: "connection.old-generation-fenced" },
  { id: "SEC-010", property: "audit 只含 allowlist 元数据且不记录正文", evidence: "audit.forbidden-content-rejected" },
  { id: "SEC-011", property: "零保留 profile 缺失、未知或漂移时正文外发 fail closed", evidence: "egress.zero-retention-unknown-denied" },
  { id: "SEC-012", property: "schema/registry/fake 中不存在任意 Root Shell 或 generic exec", evidence: "schema.root-shell-absent" },
  { id: "SEC-013", property: "adapter 无可信 principal 传播时启动失败", evidence: "auth.adapter-principal-missing" },
  { id: "SEC-014", property: "capability manifest 只描述状态，不能充当授权 token", evidence: "auth.manifest-is-not-token" },
  { id: "SEC-015", property: "每个正式 `sms.send` 均绑定完整参数并在手机逐条批准", evidence: "policy.sms-send-always-confirms" },
  { id: "SEC-016", property: "L4 操作在所有类型化/restricted backend 上无条件拒绝", evidence: "policy.l4-always-denied" },
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

const boundedKinds = new Set(["string", "integer", "map"]);
const scalarKinds = new Set(["string", "integer"]);
const boundedFormats = new Set([
  "rfc3339-utc", "uint64", "credential-id", "key-id", "tailnet-node-id", "tailnet-tag",
  "revision", "b64u-sha256-jcs-signed-attestation", "operation-id", "message-id", "tenant-id",
  "principal-id", "agent-instance-id", "workspace-id", "session-id", "job-id", "device-id",
  "pairing-generation", "authorization-epoch", "scope-id", "grant-revision", "duration-ms",
  "byte-count", "correlation-id",
]);

type AuditScalarContract = {
  kind: "string" | "integer";
  format?: string;
  enum_ref?: string;
  max_encoded_length: number;
};
type AuditMapContract = {
  kind: "map";
  key_format: string;
  value_kind: string;
  value_format: string;
  max_entries: number;
  max_encoded_length: number;
};
const fieldContracts = audit.field_contracts as Record<string, AuditScalarContract | AuditMapContract>;
const hasClosedBoundedMapValue = (valueKind: string) => scalarKinds.has(valueKind);
const nestedMapFixture = {
  key_format: "scope-id",
  value_kind: "map",
  value_format: "revision",
  max_entries: 1,
};

describe("P0a security registry", () => {
  it("maps every in-scope requirement to artifacts and evidence", () => {
    expect(requirements.map(({ id, spec_refs, requirement, artifacts, evidence }) => ({ id, spec_refs, requirement, artifacts, evidence })))
      .toEqual(expectedRequirementBindings);
    expect(requirements.every((r) => r.phase === "P0a" && r.protocol_version === PROTOCOL_VERSION)).toBe(true);
  });

  it("freezes every security property to its required evidence ID", () => {
    expect(properties.map((p) => ({ id: p.id, property: p.property, evidence: p.tests[0] })))
      .toEqual(expectedSecurityBindings);
    expect(properties.every((p) => p.tests.length === 1 && p.protocol_version === PROTOCOL_VERSION)).toBe(true);
  });

  it("freezes disjoint audit allow and deny registries with bounded contracts", () => {
    expect(audit.protocol_version).toBe(PROTOCOL_VERSION);
    expect(audit.allowed_audit_fields).toEqual(expectedAllowedAuditFields);
    expect(audit.forbidden_audit_fields).toEqual(expectedForbiddenAuditFields);
    expect(audit.allowed_audit_fields.filter((field) => audit.forbidden_audit_fields.includes(field))).toEqual([]);
    expect(Object.keys(fieldContracts)).toEqual(expectedAllowedAuditFields);

    for (const field of audit.allowed_audit_fields) {
      const contract = fieldContracts[field];
      if (!contract) throw new Error(`Missing contract for allowlisted audit field: ${field}`);
      expect(boundedKinds.has(contract.kind)).toBe(true);
      expect(contract.max_encoded_length).toBeGreaterThan(0);
      expect(contract.max_encoded_length).toBeLessThanOrEqual(512);

      if (contract.kind === "map") {
        expect(boundedFormats.has(contract.key_format)).toBe(true);
        expect(hasClosedBoundedMapValue(contract.value_kind)).toBe(true);
        expect(boundedFormats.has(contract.value_format)).toBe(true);
        expect(contract.max_entries).toBeGreaterThan(0);
      } else {
        expect(Boolean(contract.format) !== Boolean(contract.enum_ref)).toBe(true);
        if (contract.format) expect(boundedFormats.has(contract.format)).toBe(true);
        if (contract.enum_ref) expect(contract.enum_ref).toMatch(/^v1\.[a-z][a-z0-9-]*-enum$/);
      }
    }

    expect(hasClosedBoundedMapValue(nestedMapFixture.value_kind)).toBe(false);
  });
});
