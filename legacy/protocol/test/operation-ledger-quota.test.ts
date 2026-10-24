import { describe, expect, it } from "vitest";
import {
  SECURITY_LEDGER_LIMITS,
  SecurityQuotaError,
  chargeSecurityRow,
  createSecurityTombstone,
  securityTombstoneByteLength,
  type SecurityQuotaState,
  admitSecurityCharge,
} from "../src/operation-ledger-quota.js";

describe("Task 7 security-ledger quota", () => {
  it("uses the frozen bigint formula and admits exact equality", () => {
    expect(SECURITY_LEDGER_LIMITS.maxRows).toBe(16_384n);
    expect(SECURITY_LEDGER_LIMITS.maxChargedBytes).toBe(9_663_676_416n);
    const charge = chargeSecurityRow({
      phase: "pending", rawWireBytes: 262_144n, intentMetadataBytes: 65_536n,
    });
    expect(charge).toBe(589_824n);
    const state: SecurityQuotaState = { rows: 16_383n, chargedBytes: SECURITY_LEDGER_LIMITS.maxChargedBytes - charge };
    expect(admitSecurityCharge(state, charge)).toEqual({ rows: 16_384n, chargedBytes: SECURITY_LEDGER_LIMITS.maxChargedBytes });
  });

  it("rejects component and total overflow before mutation", () => {
    expect(() => chargeSecurityRow({ phase: "pending", rawWireBytes: 262_145n, intentMetadataBytes: 0n }))
      .toThrowError(new RegExp(SecurityQuotaError.RAW_WIRE_TOO_LARGE));
    expect(() => chargeSecurityRow({ phase: "pending", rawWireBytes: 1n, intentMetadataBytes: 65_537n }))
      .toThrowError(new RegExp(SecurityQuotaError.INTENT_METADATA_TOO_LARGE));
    expect(() => chargeSecurityRow({ phase: "finalized", rawWireBytes: 1n, receiptBytes: 262_145n, intentMetadataBytes: 0n }))
      .toThrowError(new RegExp(SecurityQuotaError.RECEIPT_TOO_LARGE));
    const state = { rows: 16_384n, chargedBytes: 0n };
    expect(() => admitSecurityCharge(state, 1n)).toThrowError(new RegExp(SecurityQuotaError.ROWS_FULL));
    expect(() => admitSecurityCharge({ rows: 0n, chargedBytes: SECURITY_LEDGER_LIMITS.maxChargedBytes }, 1n))
      .toThrowError(new RegExp(SecurityQuotaError.BYTES_FULL));
    expect(state).toEqual({ rows: 16_384n, chargedBytes: 0n });
  });

  it("charges finalized rows by exact receipt and emits a closed six-key tombstone", () => {
    expect(chargeSecurityRow({ phase: "finalized", rawWireBytes: 10n, receiptBytes: 20n, intentMetadataBytes: 30n })).toBe(60n);
    const tombstone = createSecurityTombstone({
      envelope_digest: "digest", message_id: "message", message_type: "operation_submit",
      sequence: "1", space: { kind: "device", credential_id: "credential", pairing_generation: "1", key_id: "key", direction: "app-to-bridge" },
    });
    expect(Object.keys(tombstone).sort()).toEqual(["envelope_digest", "message_id", "message_type", "sequence", "space", "status"]);
    expect(tombstone.status).toBe("compacted");
    expect(securityTombstoneByteLength(tombstone)).toBeGreaterThan(0n);
    expect(securityTombstoneByteLength(tombstone)).toBeLessThanOrEqual(2_048n);
  });
});
