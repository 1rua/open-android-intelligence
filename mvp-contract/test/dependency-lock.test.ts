import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { buildLockRows, openClawPluginLockFields } from "../../tools/compute-lock-hashes.ts";
import {
  EXPECTED_BLOCKS,
  EXPECTED_LOCK_IDS,
  canonicalEvidence,
  parseDependencyLock,
  sha256Evidence,
  validateDependencyLock,
} from "../tools/check-lock.ts";

const row = (overrides: Record<string, string> = {}): Record<string, string> => ({
  decision_id: "MVP-DEP-ANDROID",
  official_reference: "https://developer.android.com/reference",
  immutable_version: "agp@8.8.0;gradle@8.10.2;kotlin@2.1.0",
  integrity: "",
  license_review: "Apache-2.0; reviewed",
  reviewer_time: "2026-08-10T00:00:00Z",
  evidence_expires_at: "2026-09-10T00:00:00Z",
  verify_command: "./gradlew --version",
  status: "locked",
  blocks: "WP-02,WP-03,WP-08,WP-09",
  ...overrides,
});

const markdown = (rows: Record<string, string>[]): string => {
  const header = [
    "decision_id",
    "official_reference",
    "immutable_version",
    "integrity",
    "license_review",
    "reviewer_time",
    "evidence_expires_at",
    "verify_command",
    "status",
    "blocks",
  ];
  const line = (value: Record<string, string>) => `| ${header.map((key) => value[key] ?? "").join(" | ")} |`;
  return [
    `| ${header.join(" | ")} |`,
    `| ${header.map(() => "---").join(" | ")} |`,
    ...rows.map((value) => line(value)),
  ].join("\n");
};

const completeRows = (): Record<string, string>[] => {
  const rows = EXPECTED_LOCK_IDS.map((decision_id) => row({ decision_id, blocks: "WP-02" }));
  rows[0] = row({ decision_id: "MVP-DEP-ANDROID", blocks: "WP-02,WP-03,WP-08,WP-09" });
  rows[1] = row({ decision_id: "MVP-DEP-TSNET", official_reference: "https://tailscale.com/kb/", immutable_version: "tailscale@abc123", blocks: "WP-05,WP-09" });
  rows[2] = row({ decision_id: "MVP-DEP-BRIDGE", official_reference: "https://nodejs.org/docs/", immutable_version: "bridge-runtime@abc123", blocks: "WP-06,WP-09" });
  rows[3] = row({ decision_id: "MVP-DEP-HERMES", official_reference: "https://hermes-agent.example/docs", immutable_version: "hermes@abc123", blocks: "WP-07,WP-09" });
  rows[4] = row({ decision_id: "MVP-DEP-OPENCLAW", official_reference: "https://openclaw.example/docs", immutable_version: "openclaw@abc123", blocks: "WP-07,WP-09" });
  rows[5] = row({ decision_id: "MVP-DEP-OPENCLAW-PLUGIN", official_reference: "https://github.com/1rua/openclaw-gateway-plugin", immutable_version: "openclaw-gateway-plugin@abc123", blocks: "WP-07,WP-09" });
  rows[6] = row({ decision_id: "MVP-DEP-MODEL", official_reference: "https://example.com/model/retention", immutable_version: "profile@abc123", blocks: "WP-06,WP-08,WP-09" });
  rows[7] = row({ decision_id: "MVP-DEP-ARTIFACT", official_reference: "https://example.com/object-store", immutable_version: "artifact@abc123", blocks: "WP-10" });
  for (const value of rows) value.integrity = `sha256:${sha256Evidence(value)}`;
  return rows;
};

describe("MVP dependency lock gate", () => {
  it("accepts a complete, future-dated lock with integrity-bound rows and packet coverage", () => {
    const result = validateDependencyLock(markdown(completeRows()), new Date("2026-08-11T00:00:00Z"));
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);
    expect(result.pending).toEqual([]);
  });

  it("rejects missing and duplicate decision rows", () => {
    const rows = completeRows().filter((value) => value.decision_id !== "MVP-DEP-ARTIFACT");
    expect(validateDependencyLock(markdown(rows)).errors.join(" ")).toContain("missing decision_id MVP-DEP-ARTIFACT");

    const duplicate = [...completeRows(), completeRows()[0]!];
    expect(validateDependencyLock(markdown(duplicate)).errors.join(" ")).toContain("duplicate decision_id MVP-DEP-ANDROID");
  });

  it("rejects expired evidence and a changed integrity reference", () => {
    const expired = completeRows();
    expired[0]!.evidence_expires_at = "2026-08-01T00:00:00Z";
    expired[0]!.integrity = `sha256:${sha256Evidence(expired[0]!)}`;
    expect(validateDependencyLock(markdown(expired), new Date("2026-08-11T00:00:00Z")).errors.join(" ")).toContain("expired");

    const altered = completeRows();
    altered[0]!.integrity = "sha256:" + "0".repeat(64);
    expect(validateDependencyLock(markdown(altered)).errors.join(" ")).toContain("integrity mismatch");
  });

  it("fails closed when an otherwise well-formed row is pending", () => {
    const rows = completeRows();
    rows[1] = row({
      decision_id: "MVP-DEP-TSNET",
      official_reference: "PENDING: controller must select the official userspace source",
      immutable_version: "PENDING",
      integrity: "PENDING",
      license_review: "PENDING",
      reviewer_time: "PENDING",
      evidence_expires_at: "PENDING",
      verify_command: "PENDING: locked Android CI command",
      status: "pending",
      blocks: "WP-05,WP-09",
    });
    const result = validateDependencyLock(markdown(rows));
    expect(result.ok).toBe(false);
    expect(result.pending).toContain("MVP-DEP-TSNET");
  });

  it("keeps the canonical evidence tuple independent from the integrity cell", () => {
    const value = completeRows()[0]!;
    expect(canonicalEvidence({ ...value, integrity: "sha256:" + "f".repeat(64) })).toBe(canonicalEvidence(value));
  });

  it("generates the OpenClaw plugin lock and install command from the supplied pin", () => {
    const revision = "a".repeat(40);
    const plugin = buildLockRows({
      repository: "1rua/openclaw-gateway-plugin",
      version: "1.0.3",
      ref: "v1.0.3",
      revision,
    }).find((value) => value.decision_id === "MVP-DEP-OPENCLAW-PLUGIN");

    expect(plugin?.official_reference).toBe("https://github.com/1rua/openclaw-gateway-plugin");
    expect(plugin?.immutable_version).toBe(`openclaw-gateway-plugin@1.0.3; ref v1.0.3; commit ${revision}`);
    expect(plugin?.verify_command).toContain(`checkout --detach ${revision}`);
    expect(plugin?.verify_command).toContain(`plugins install git:github.com/1rua/openclaw-gateway-plugin@${revision}`);
    expect(plugin?.verify_command).not.toContain("v1.0.2");
    expect(plugin?.verify_command).not.toContain("9b81b977dc8190536b1ac2944d97fbb004b450b9");
    expect(plugin?.verify_command).not.toContain("f4a04a28d4cc419de4c544d7c99bb08d62a3ee97");
  });

  it("uses the fixed revision even when the pin ref is a tag", () => {
    const revision = "a".repeat(40);
    const fields = openClawPluginLockFields({
      repository: "1rua/openclaw-gateway-plugin",
      version: "1.0.3",
      ref: "v1.0.3",
      revision,
    });
    expect(fields.verify_command).toContain(`fetch origin ${revision}`);
    expect(fields.verify_command).toContain(`checkout --detach ${revision}`);
    expect(fields.verify_command).toContain(`plugins install git:github.com/1rua/openclaw-gateway-plugin@${revision}`);
    expect(fields.verify_command).not.toContain("@v1.0.3");
  });

  it("rejects a plugin pin without a valid fixed revision", () => {
    expect(() => buildLockRows({
      repository: "1rua/openclaw-gateway-plugin",
      version: "1.0.3",
      ref: "v1.0.3",
      revision: "not-a-commit",
    })).toThrow("40-hex revision");
  });

  it("keeps the checked-in OpenClaw plugin lock row synchronized with the pin", () => {
    const pin = JSON.parse(readFileSync(new URL("../../openclaw-plugin-pin.json", import.meta.url), "utf8")) as {
      repository: string;
      version: string;
      ref: string;
      revision: string;
    };
    const generated = buildLockRows(pin).find((value) => value.decision_id === "MVP-DEP-OPENCLAW-PLUGIN");
    const lockMarkdown = readFileSync(new URL("../../docs/mvp/mvp-dependency-lock.md", import.meta.url), "utf8");
    const locked = parseDependencyLock(lockMarkdown).rows.find((value) => value.decision_id === "MVP-DEP-OPENCLAW-PLUGIN");

    if (!generated) throw new Error("OpenClaw plugin row is missing from the lock generator");
    if (!locked) throw new Error("OpenClaw plugin row is missing from the checked-in dependency lock");
    expect(locked.official_reference).toBe(generated.official_reference);
    expect(locked.immutable_version).toBe(generated.immutable_version);
    expect(locked.verify_command).toBe(generated.verify_command);
    expect(locked.integrity).toBe(`sha256:${sha256Evidence(locked)}`);
  });
});
