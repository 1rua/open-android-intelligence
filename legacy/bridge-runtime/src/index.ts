/** WP-06 runtime adapters: deterministic development seams plus the locked
 * single-host Node SQLite production stack. */
export {
  createFileBackedBridgeStore,
  FileBackedBridgeStore,
  FILE_BACKED_BRIDGE_STORE_FORMAT,
  FILE_BACKED_BRIDGE_STORE_VERSION,
  openFileBackedBridgeStore,
} from "./file-backed-store.js";
export {
  DURABLE_OPERATION_DISPATCHER_PORT,
  DurableOperationDispatcher,
  assertDurableOperationDispatcher,
  isDurableOperationDispatcher,
} from "./durable-operation-dispatcher.js";
export type {
  DurableOperationDispatcherOptions,
  ReplayAssociationInput,
} from "./durable-operation-dispatcher.js";
export {
  createDurableBridgeComposition,
  createFencedDurableBridgeComposition,
} from "./composition.js";
export type {
  DurableBridgeComposition,
  DurableBridgeCompositionOptions,
  FencedDurableBridgeComposition,
  FencedDurableBridgeCompositionOptions,
} from "./composition.js";
export {
  DurableBridgeStateRepositories,
  DurableNotificationRepository,
  DurablePairingRepository,
  DurableSubscriptionRepository,
} from "./durable-state-repositories.js";
export type { DurableBridgeStateRepositoriesOptions } from "./durable-state-repositories.js";
export {
  BRIDGE_LEASE_COORDINATOR_PORT,
  PAIRING_TICKET_VERIFIER_PORT,
  assertBridgeLease,
  assertConnectedBridgeLeaseCoordinator,
  assertConnectedPairingTicketVerifier,
} from "./production-ports.js";
export type {
  BridgeLease,
  BridgeLeaseCoordinatorPort,
  PairingTicketVerifierPort,
} from "./production-ports.js";
export { runBridgeBackupRestoreDrill } from "./backup-restore-drill.js";
export type {
  BridgeBackupRestoreDrillOptions,
  BridgeBackupRestoreDrillReport,
} from "./backup-restore-drill.js";
export type {
  FileBackedBridgeManifest,
  FileBackedBridgeStoreOptions,
  FileBackedRecoveryReport,
} from "./file-backed-store.js";
export {
  BridgeIngress,
  ConnectionGenerationFence,
  MemoryReplayAdmission,
} from "./ingress.js";
export type {
  BridgeIngressOptions,
  IngressControlFrame,
  IngressDispatch,
  IngressReceipt,
  IngressStatus,
  ReplayAdmission,
  ReplayLookup,
  TailscaleUserspaceListener,
  TsnetDependencyState,
} from "./ingress.js";
export { BridgeHealth, createHealthHttpHandler } from "./health.js";
export type {
  HealthCheck,
  HealthCheckResult,
  HealthHttpRequest,
  HealthHttpResponse,
  LiveHealth,
  ReadyHealth,
} from "./health.js";
export { MigrationRunner, runBridgeMigrations } from "./migration-runner.js";
export type { MigrationRunReport, MigrationStep } from "./migration-runner.js";
export {
  NODE_SQLITE_BRIDGE_DRIVER,
} from "../../bridge-contract/src/persistence.js";
export {
  NodeSqliteBridgeAdapter,
  isNodeSqliteLeaseCoordinator,
  openNodeSqliteBridgeAdapter,
} from "./node-sqlite-adapter.js";
export type { NodeSqliteBridgeAdapterOptions } from "./node-sqlite-adapter.js";
export {
  LocalPairingTicketVerifier,
  PAIRING_TICKET_ENVELOPE,
  openLocalPairingTicketVerifier,
} from "./local-pairing-ticket-verifier.js";
export type { LocalPairingTicketVerifierOptions } from "./local-pairing-ticket-verifier.js";
export {
  createRuntimeHttpHandler,
} from "./runtime-http.js";
export type {
  RuntimeControl,
  RuntimeControlRequest,
  RuntimeHttpHandlerOptions,
  RuntimeHttpRequest,
  RuntimeHttpResponse,
} from "./runtime-http.js";
export {
  cleanupRealBridgeBackupRestoreDrill,
  runRealBridgeBackupRestoreDrill,
} from "./real-backup-restore-drill.js";
export type {
  RealBridgeBackupRestoreDrillOptions,
  RealBridgeBackupRestoreDrillResult,
} from "./real-backup-restore-drill.js";
