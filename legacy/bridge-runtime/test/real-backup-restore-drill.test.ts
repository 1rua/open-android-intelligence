import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
import { BRIDGE_STORE_NAMESPACES } from "../../bridge-contract/src/durable-store.js";
import { cleanupRealBridgeBackupRestoreDrill, runRealBridgeBackupRestoreDrill } from "../src/real-backup-restore-drill.js";

const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-real-drill-"));

afterAll(async () => {
  await rm(root, { recursive: true, force: true });
});

describe("real Node SQLite backup/restore drill", () => {
  it("backs up, restores, recovers, and compares every closed namespace", async () => {
    const result = await runRealBridgeBackupRestoreDrill({ workDir: root });
    const removedPaths = await cleanupRealBridgeBackupRestoreDrill(result);
    expect(result.verified).toBe(true);
    expect(result.driver.backend).toBe("node:sqlite");
    expect(result.driver.node).toBe("24.18.0");
    expect(result.driver.sqlite).toBe("3.53.1");
    expect(result.report.schemaVersion).toBe(1);
    expect(result.report.namespaces).toHaveLength(BRIDGE_STORE_NAMESPACES.length);
    for (const namespace of result.report.namespaces) {
      expect(namespace.entries).toBe(1);
    }
    expect(result.report.digest).toMatch(/^sha256:[0-9a-f]{64}$/);
    expect(removedPaths).toContain(join(root, "source.sqlite"));
  });
});
