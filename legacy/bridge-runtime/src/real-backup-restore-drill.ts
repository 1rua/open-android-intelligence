import { rm } from "node:fs/promises";
import { isAbsolute } from "node:path";
import { join } from "node:path";
import { BRIDGE_STORE_NAMESPACES } from "../../bridge-contract/src/durable-store.js";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
import {
  NODE_SQLITE_BRIDGE_DRIVER,
} from "../../bridge-contract/src/persistence.js";
import {
  openNodeSqliteBridgeAdapter,
  type NodeSqliteBridgeAdapter,
} from "./node-sqlite-adapter.js";
import {
  runBridgeBackupRestoreDrill,
  type BridgeBackupRestoreDrillReport,
} from "./backup-restore-drill.js";

export type RealBridgeBackupRestoreDrillOptions = Readonly<{
  workDir: string;
}>;

export type RealBridgeBackupRestoreDrillResult = Readonly<{
  verified: true;
  recordedAt: string;
  driver: Readonly<{
    backend: "node:sqlite";
    id: typeof NODE_SQLITE_BRIDGE_DRIVER;
    node: string;
    sqlite: string;
  }>;
  database: Readonly<{
    source: string;
    restoreTarget: string;
    backupArtifact: string;
    schemaVersion: number;
  }>;
  report: BridgeBackupRestoreDrillReport;
  cleanup: Readonly<{
    removedPaths: readonly string[];
  }>;
}>;

const error = (code: string): BridgeServiceError => new BridgeServiceError(code);

const removeDatabaseArtifacts = async (path: string): Promise<readonly string[]> => {
  const paths = [path, `${path}-wal`, `${path}-shm`];
  for (const candidate of paths) await rm(candidate, { force: true });
  return Object.freeze(paths);
};

const close = async (adapter: NodeSqliteBridgeAdapter | null): Promise<void> => {
  if (adapter !== null) await adapter.close().catch(() => undefined);
};

/**
 * Runs the production drill with the locked Node SQLite backend.
 *
 * This is intentionally not a fake-adapter test: it creates two isolated
 * database files, writes every closed durable namespace, performs a real
 * SQLite backup and restore, verifies recovery/content, then removes the
 * temporary files. It never touches the production Bridge database.
 */
export const runRealBridgeBackupRestoreDrill = async (
  options: RealBridgeBackupRestoreDrillOptions,
): Promise<RealBridgeBackupRestoreDrillResult> => {
  if (!options || typeof options !== "object") {
    throw error("BACKUP_RESTORE_DRILL_OPTIONS_INVALID");
  }
  if (typeof options.workDir !== "string" || options.workDir.length === 0 || !isAbsolute(options.workDir)) {
    throw error("BACKUP_RESTORE_DRILL_WORKDIR_INVALID");
  }
  const sourcePath = join(options.workDir, "source.sqlite");
  const restorePath = join(options.workDir, "restore.sqlite");
  const destination = join(options.workDir, "backup.sqlite");
  if (sourcePath === destination || restorePath === destination) {
    throw error("BACKUP_RESTORE_TARGET_NOT_ISOLATED");
  }

  let source: NodeSqliteBridgeAdapter | null = null;
  let restoreTarget: NodeSqliteBridgeAdapter | null = null;
  try {
    const [nodeVersion, sqliteVersion] = [process.versions.node, process.versions.sqlite];
    if (nodeVersion !== "24.18.0" || sqliteVersion !== "3.53.1") {
      throw error("SQLITE_DRIVER_LOCK_MISMATCH");
    }
    source = await openNodeSqliteBridgeAdapter({
      databasePath: sourcePath,
      ownerId: "bridge-backup-drill-source",
    });
    restoreTarget = await openNodeSqliteBridgeAdapter({
      databasePath: restorePath,
      ownerId: "bridge-backup-drill-restore",
    });
    await source.transact("bridge.backup-drill.seed", async (transaction) => {
      for (const namespace of BRIDGE_STORE_NAMESPACES) {
        await transaction.write(namespace, "drill:representative", Object.freeze({
          bigint: 9_007_199_254_740_993n,
          nested: Object.freeze([1n, "text", false, null]),
          namespace,
        }));
      }
    });
    const report = await runBridgeBackupRestoreDrill({
      source,
      restoreTarget,
      destination,
      namespaces: BRIDGE_STORE_NAMESPACES,
    });
    return Object.freeze({
      verified: true,
      recordedAt: new Date().toISOString(),
      driver: Object.freeze({
        backend: "node:sqlite",
        id: NODE_SQLITE_BRIDGE_DRIVER,
        node: nodeVersion,
        sqlite: sqliteVersion,
      }),
      database: Object.freeze({
        source: sourcePath,
        restoreTarget: restorePath,
        backupArtifact: destination,
        schemaVersion: report.schemaVersion,
      }),
      report,
      cleanup: Object.freeze({
        removedPaths: Object.freeze([]),
      }),
    });
  } finally {
    await close(source);
    await close(restoreTarget);
  }
};

export const cleanupRealBridgeBackupRestoreDrill = async (
  result: RealBridgeBackupRestoreDrillResult,
): Promise<readonly string[]> => {
  const removed = [
    ...await removeDatabaseArtifacts(result.database.source),
    ...await removeDatabaseArtifacts(result.database.restoreTarget),
    ...await removeDatabaseArtifacts(result.database.backupArtifact),
  ];
  return Object.freeze(removed);
};
