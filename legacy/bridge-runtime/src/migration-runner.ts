import {
  assertSqliteBridgeAdapterPort,
  type SqliteBridgeAdapterPort,
} from "../../bridge-contract/src/persistence.js";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
import type { DurableBridgeTransaction } from "../../bridge-contract/src/durable-store.js";

export type MigrationStep = Readonly<{
  id: string;
  from: number;
  to: number;
  apply: (transaction: DurableBridgeTransaction) => Promise<void> | void;
}>;

export type MigrationRunReport = Readonly<{
  from: number;
  to: number;
  applied: readonly string[];
}>;

const MIGRATION_ID = /^[a-z0-9][a-z0-9._/-]{0,127}$/;

const migrationError = (code: string): BridgeServiceError => new BridgeServiceError(code);

function assertVersion(value: unknown): asserts value is number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw migrationError("MIGRATION_VERSION_INVALID");
  }
}

const validateSteps = (steps: readonly MigrationStep[]): void => {
  const ids = new Set<string>();
  const starts = new Set<number>();
  const targets = new Set<number>();
  for (const step of steps) {
    if (typeof step !== "object" || step === null || typeof step.id !== "string" || !MIGRATION_ID.test(step.id)) {
      throw migrationError("MIGRATION_CHAIN_INVALID");
    }
    assertVersion(step.from);
    assertVersion(step.to);
    if (step.to !== step.from + 1 || typeof step.apply !== "function") {
      throw migrationError("MIGRATION_CHAIN_INVALID");
    }
    if (ids.has(step.id) || starts.has(step.from) || targets.has(step.to)) {
      throw migrationError("MIGRATION_CHAIN_INVALID");
    }
    ids.add(step.id);
    starts.add(step.from);
    targets.add(step.to);
  }
};

/**
 * Runs immutable, contiguous schema steps against the external SQLite port.
 * The runner owns ordering and validation; the adapter owns SQLite locking,
 * transaction commit and durable schema-version publication.
 */
export class MigrationRunner {
  readonly #adapter: SqliteBridgeAdapterPort;
  readonly #steps: readonly MigrationStep[];

  constructor(adapter: unknown, steps: readonly MigrationStep[]) {
    this.#adapter = assertSqliteBridgeAdapterPort(adapter);
    if (!Array.isArray(steps)) throw migrationError("MIGRATION_CHAIN_INVALID");
    this.#steps = Object.freeze([...steps]);
    validateSteps(this.#steps);
  }

  async run(): Promise<MigrationRunReport> {
    const start = await this.#adapter.schemaVersion();
    assertVersion(start);
    const byFrom = new Map(this.#steps.map((step) => [step.from, step]));
    const highest = Math.max(start, ...this.#steps.map((step) => step.to));
    // Preflight the entire chain before asking the adapter to mutate anything;
    // a gap must not leave a partially upgraded database.
    let expected = start;
    while (expected < highest) {
      const step = byFrom.get(expected);
      if (step === undefined) throw migrationError("MIGRATION_CHAIN_INVALID");
      expected = step.to;
    }
    let version = start;
    const applied: string[] = [];
    while (version < highest) {
      const step = byFrom.get(version);
      if (step === undefined) throw migrationError("MIGRATION_CHAIN_INVALID");
      const scope = `bridge.migration.${step.id}`;
      await this.#adapter.runMigration(scope, version, step.to, step.apply);
      const committed = await this.#adapter.schemaVersion();
      if (committed !== step.to) throw migrationError("MIGRATION_COMMIT_UNVERIFIED");
      applied.push(step.id);
      version = step.to;
    }
    return Object.freeze({ from: start, to: version, applied: Object.freeze(applied) });
  }
}

export const runBridgeMigrations = (
  adapter: unknown,
  steps: readonly MigrationStep[],
): Promise<MigrationRunReport> => new MigrationRunner(adapter, steps).run();
