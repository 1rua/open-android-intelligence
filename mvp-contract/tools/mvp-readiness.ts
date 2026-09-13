/**
 * Deterministic MVP readiness audit.
 *
 * This is deliberately an artifact/environment report, not a replacement for
 * the packet-specific tests. `run-readiness.sh` runs the SDK-free tests first
 * and then invokes this tool. A source seam is never reported as a production
 * pass: release readiness additionally requires the controller lock, the
 * Android toolchain/device, the real Bridge/Tailnet artifacts and the P0a
 * gates called out by the vertical-slice plan.
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { validateDependencyLock } from "./check-lock.ts";

export type ReadinessMode = "sdk-free" | "release";
export type ArtifactStatus = "PASS" | "MISSING";

export type PacketAudit = {
  id: string;
  label: string;
  artifacts: string[];
  missingArtifacts: string[];
};

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const LOCK_PATH = resolve(ROOT, "docs/mvp/mvp-dependency-lock.md");
const P0A_DECISIONS_PATH = resolve(ROOT, "docs/mvp/p0a-gate-decisions.md");

/**
 * These are the source-level handoff artifacts from WP-00..WP-10. External
 * artifacts (AARs, a database, a physical device and locked releases) are
 * intentionally listed as release blockers below rather than faked here.
 */
export const PACKETS: ReadonlyArray<{
  id: string;
  label: string;
  source: string[];
}> = [
  {
    id: "WP-00",
    label: "controller lock and contract launcher",
    source: [
      "docs/mvp/mvp-dependency-lock.md",
      "mvp-contract/tools/check-lock.ts",
      "mvp-contract/test/dependency-lock.test.ts",
    ],
  },
  {
    id: "WP-01",
    label: "closed schemas and deterministic fixtures",
    source: [
      "mvp-contract/schemas/v1/notification-policy.schema.json",
      "mvp-contract/schemas/v1/notification-record.schema.json",
      "mvp-contract/schemas/v1/notification-api.schema.json",
      "mvp-contract/schemas/v1/assistant-chat.schema.json",
      "mvp-contract/src/wire-codec.ts",
      "mvp-contract/test/mvp-contract.test.ts",
      "docs/mvp/mvp-vertical-slice-contract.md",
    ],
  },
  {
    id: "WP-02",
    label: "Android modules and no-VPN static gate",
    source: [
      "apps/android/settings.gradle.kts",
      "apps/android/app/src/main/AndroidManifest.xml",
      "apps/android/assistant-holder/src/main/AndroidManifest.xml",
      "apps/android/gradle/mvp-forbidden-surfaces.gradle.kts",
      "apps/android/tools/test_transport_boundary.py",
    ],
  },
  {
    id: "WP-03",
    label: "policy, collector and encrypted outbox",
    source: [
      "apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationContracts.kt",
      "apps/android/policy-engine/src/main/kotlin/com/openandroidintelligence/policy/NotificationPolicyEvaluator.kt",
      "apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/AndroidNotificationCollector.kt",
      "apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationRuntime.kt",
      "apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/NotificationOutboxStore.kt",
      "apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityPorts.kt",
      "apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt",
      "apps/android/tools/test_capability_ports_static.py",
      "apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/ControlPorts.kt",
      "apps/android/tools/test_control_ports_static.py",
      "apps/android/tools/test_wp03_static.py",
      "apps/android/tools/test_notification_runtime_static.py",
    ],
  },
  {
    id: "WP-04",
    label: "fake paired transport and trace harness",
    source: [
      "apps/android/transport/src/testFixtures/kotlin/com/openandroidintelligence/transport/FakeUserspaceTransport.kt",
      "bridge-contract/src/fake-bridge.ts",
      "bridge-contract/test/fake-bridge.test.ts",
      "bridge-contract/test/notification-flow.trace.test.ts",
    ],
  },
  {
    id: "WP-05",
    label: "P0t userspace Tailnet spike",
    source: [
      "apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/TailscaleUserspaceCore.kt",
      "apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/VerifiedPairingTransportBinding.kt",
      "apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/TsnetPairedBridgeTransport.kt",
      "docs/mvp/p0t-mvp-evidence.md",
    ],
  },
  {
    id: "WP-06",
    label: "Bridge pairing, notification API and text chat",
    source: [
      "bridge-contract/src/pairing-service.ts",
      "bridge-contract/src/notification-service.ts",
      "bridge-contract/src/notification-store.ts",
      "bridge-contract/src/subscription-store.ts",
      "bridge-contract/src/operation-dispatch.ts",
      "bridge-contract/src/assistant-chat-service.ts",
      "bridge-contract/src/durable-store.ts",
      "bridge-contract/src/persistence.ts",
      "bridge-contract/test/service-contract.test.ts",
      "bridge-contract/test/durable-store.test.ts",
      "bridge-contract/test/persistence-contract.test.ts",
      // The bridge runtime moved under `legacy/` with the modular plugin
      // migration; the packet still covers it, at its current location.
      "legacy/bridge-runtime/src/durable-operation-dispatcher.ts",
      "legacy/bridge-runtime/src/composition.ts",
      "legacy/bridge-runtime/src/migration-runner.ts",
      "legacy/bridge-runtime/src/node-sqlite-adapter.ts",
      "legacy/bridge-runtime/src/local-pairing-ticket-verifier.ts",
      "legacy/bridge-runtime/src/runtime-http.ts",
      "legacy/bridge-runtime/src/main.ts",
      "legacy/bridge-runtime/src/real-backup-restore-drill.ts",
      "legacy/bridge-runtime/src/ingress.ts",
      "legacy/bridge-runtime/src/health.ts",
      "legacy/bridge-runtime/ingress/go.mod",
      "legacy/bridge-runtime/ingress/cmd/open-android-intelligence-tsnet/main.go",
      "legacy/bridge-runtime/deploy/Dockerfile",
      "legacy/bridge-runtime/deploy/Dockerfile.ingress",
      "legacy/bridge-runtime/deploy/docker-compose.yml",
      "legacy/bridge-runtime/deploy/open-android-intelligence-bridge.service",
      "legacy/bridge-runtime/deploy/open-android-intelligence-ingress.service",
      "legacy/bridge-runtime/test/durable-operation-dispatcher.test.ts",
      "legacy/bridge-runtime/test/migration-runner.test.ts",
      "legacy/bridge-runtime/test/ingress-health.test.ts",
      "legacy/bridge-runtime/test/node-sqlite-adapter.test.ts",
      "legacy/bridge-runtime/test/node-sqlite-lease.test.ts",
      "legacy/bridge-runtime/test/local-pairing-ticket-verifier.test.ts",
      "legacy/bridge-runtime/test/runtime-http.test.ts",
      "legacy/bridge-runtime/test/production-runtime-main.test.ts",
      "legacy/bridge-runtime/test/real-backup-restore-drill.test.ts",
      "docs/mvp/bridge-persistence-readiness.md",
      "docs/mvp/bridge-production-readiness.md",
      "docs/mvp/bridge-ingress-health.md",
      "legacy/bridge-runtime/README.md",
    ],
  },
  {
    id: "WP-07",
    label: "Hermes/OpenClaw adapters and shared skill",
    source: [
      // The Hermes product is the native Python plugin; the TypeScript adapter of
      // the same name is the legacy fixture kept under `legacy/`.
      "integrations/hermes/open_android_intelligence_gateway/plugin.py",
      "integrations/hermes/pyproject.toml",
      "integrations/hermes/README.md",
      "legacy/integrations/hermes-v1/adapter.ts",
      "legacy/integrations/hermes-v1/adapter.test.ts",
      "legacy/integrations/hermes-v1/plugin-manifest.json",
      "integrations/openclaw/adapter.ts",
      "integrations/openclaw/adapter.test.ts",
      "integrations/openclaw/plugin-manifest.json",
      "integrations/shared/notification-contract.test.ts",
      "integrations/skills/android-device-bridge/SKILL.md",
    ],
  },
  {
    id: "WP-08",
    label: "isolated default-assistant holder",
    source: [
      "apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantVoiceService.kt",
      "apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt",
      "apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantActivity.kt",
      "apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantAttachmentContract.kt",
      "apps/android/assistant-holder/src/main/res/xml/voice_interaction_service.xml",
      "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt",
      "apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/AssistantHandoffContracts.kt",
      "apps/android/tools/test_assistant_holder_attachments_static.py",
      "apps/android/tools/test_assistant_handoff_static.py",
    ],
  },
  {
    id: "WP-09",
    label: "physical Android/Bridge/plugin E2E and release gate",
    source: [
      "e2e/mvp/run-smoke.sh",
      "e2e/mvp/test_mvp_smoke.py",
      "docs/mvp/mvp-smoke-evidence.md",
    ],
  },
  {
    id: "WP-10",
    label: "source-only selected attachment artifact contract",
    source: [
      "artifact-contract/src/artifact-ticket.ts",
      "artifact-contract/test/artifact-ticket.test.ts",
      "artifact-contract/README.md",
      "apps/android/artifact-ports/src/main/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPorts.kt",
      "apps/android/artifact-ports/build.gradle.kts",
      "apps/android/artifact-ports/README.md",
      "apps/android/tools/test_artifact_ports_static.py",
      "docs/mvp/m1_1-artifact-readiness.md",
    ],
  },
];

const readText = (path: string): string | null => {
  try {
    return readFileSync(path, "utf8");
  } catch {
    return null;
  }
};

const localCommandCandidates = (command: string): string[] => {
  const candidates = [
    process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, "bin", command) : "",
    resolve(ROOT, ".toolchains/jdk-17.0.20+8/bin", command),
    resolve(ROOT, ".toolchains/jdk-25.0.4+7/bin", command),
    resolve(ROOT, ".toolchains/android-sdk/platform-tools", command),
  ];
  return candidates.filter((candidate) => candidate.length > 0);
};

const resolveCommand = (command: string): string | null => {
  const result = spawnSync("bash", ["-lc", `command -v ${command}`], {
    cwd: ROOT,
    encoding: "utf8",
  });
  if (result.status === 0) {
    const resolved = String(result.stdout ?? "").trim();
    if (resolved.length > 0) return resolved;
  }
  return localCommandCandidates(command).find((candidate) => existsSync(candidate)) ?? null;
};

const commandAvailable = (command: string): boolean => resolveCommand(command) !== null;

const adbConnected = (): boolean => {
  const adb = resolveCommand("adb");
  if (!adb) return false;
  const result = spawnSync(adb, ["devices"], { cwd: ROOT, encoding: "utf8" });
  return result.status === 0 && /\tdevice(?:\s|$)/m.test(String(result.stdout ?? ""));
};

const hasFileWithExtension = (directory: string, extension: string): boolean => {
  if (!existsSync(directory)) return false;
  let entries;
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return false;
  }
  for (const entry of entries) {
    const child = resolve(directory, entry.name);
    if (entry.isFile() && entry.name.toLowerCase().endsWith(extension)) return true;
    if (entry.isDirectory() && hasFileWithExtension(child, extension)) return true;
  }
  return false;
};

export const auditPackets = (): PacketAudit[] => PACKETS.map((packet) => {
  const missingArtifacts = packet.source
    .filter((path) => !existsSync(resolve(ROOT, path)));
  return {
    id: packet.id,
    label: packet.label,
    artifacts: packet.source,
    missingArtifacts,
  };
});

export type ReleaseBlocker = {
  code: string;
  detail: string;
};

export const auditReleaseBlockers = (): ReleaseBlocker[] => {
  const blockers: ReleaseBlocker[] = [];
  const lock = readText(LOCK_PATH);
  const lockResult = lock === null
    ? { ok: false, pending: [], errors: ["dependency lock cannot be read"], rows: [] }
    : validateDependencyLock(lock);
  if (!lockResult.ok) {
    const pending = lockResult.pending.length > 0 ? ` pending=${lockResult.pending.join(",")}` : "";
    blockers.push({
      code: "MVP-DEP-LOCK",
      detail: `controller dependency lock is not fully locked (${lockResult.rows.length} rows);${pending}`,
    });
  }

  if (!commandAvailable("adb") || !adbConnected()) {
    blockers.push({ code: "ANDROID-DEVICE", detail: "no adb-connected API-34+ reference device" });
  }
  if (!commandAvailable("java")) {
    blockers.push({ code: "ANDROID-JAVA", detail: "Java is unavailable for the locked Gradle toolchain" });
  }
  if (!commandAvailable("gradle")) {
    blockers.push({ code: "ANDROID-GRADLE", detail: "Gradle is unavailable for the locked wrapper" });
  }
  const bridgeRuntimeRoot = resolve(ROOT, "bridge-runtime");
  if (!existsSync(bridgeRuntimeRoot)) {
    blockers.push({ code: "BRIDGE-RUNTIME", detail: "durable Bridge runtime/migration wiring is not present" });
  } else {
    // Keep the local development seam and the single-host deployment gate
    // separately observable. The real SQLite stack and drill are now present,
    // but image build and physical Tailnet/E2E evidence are still required.
    const runtimeReadme = readText(resolve(bridgeRuntimeRoot, "README.md"));
    const productionPaths = [
      "src/node-sqlite-adapter.ts",
      "src/local-pairing-ticket-verifier.ts",
      "src/main.ts",
      "src/real-backup-restore-drill.ts",
      "ingress/go.mod",
      "ingress/cmd/open-android-intelligence-tsnet/main.go",
      "deploy/Dockerfile",
      "deploy/Dockerfile.ingress",
      "deploy/docker-compose.yml",
      "deploy/open-android-intelligence-bridge.service",
      "deploy/open-android-intelligence-ingress.service",
      "deploy/verify-production.sh",
    ].filter((path) => !existsSync(resolve(bridgeRuntimeRoot, path)));
    if (runtimeReadme === null || !/not evidence of production readiness/i.test(runtimeReadme) || productionPaths.length > 0) {
      blockers.push({
        code: "BRIDGE-RUNTIME-PRODUCTION",
        detail: `Bridge production stack is incomplete; missing=${productionPaths.join(",") || "boundary declaration"}`,
      });
    } else {
      blockers.push({
        code: "BRIDGE-DEPLOYMENT",
        detail: "single-host Node SQLite/lease/secret/tsnet stack and real drill are present, but Docker image build plus physical Tailnet and Android/Bridge E2E evidence are pending",
      });
    }
  }
  if (!commandAvailable("docker")) {
    blockers.push({ code: "DOCKER-BUILD", detail: "Docker CLI/daemon unavailable for Compose config/build" });
  }
  if (!hasFileWithExtension(resolve(ROOT, "apps/android"), ".aar")) {
    blockers.push({ code: "TSNET-AAR", detail: "locked Tailscale userspace AAR/resource artifact is not present" });
  }

  const p0t = readText(resolve(ROOT, "docs/mvp/p0t-mvp-evidence.md"));
  if (!p0t || /source-level spike only|SKIPPED|not a P0t\s+pass/i.test(p0t)) {
    blockers.push({ code: "P0T-GATE", detail: "P0t evidence is source-level/SKIPPED, not a physical pass" });
  }

  // Product/security choices are recorded in the main checkout so this audit
  // does not infer approval from an implementation worktree's review status.
  // Task 9 deliberately has a second, production-facing gate: the bounded
  // pre-replay authority gate is reviewed, but vectors, durable cursor/ACK
  // storage and deployed routing are not production evidence. Keep that gate
  // fail-closed until those artifacts are explicitly accepted.
  const decisions = readText(P0A_DECISIONS_PATH);
  const hasAcceptedDecision = (decisionId: string, value: string): boolean => {
    if (!decisions) return false;
    const escapedId = decisionId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const escapedValue = value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return new RegExp(`^decision_id=${escapedId}\\s+status=accepted\\s+value=${escapedValue}\\s*$`, "mi")
      .test(decisions);
  };
  const task7DecisionsAccepted = [
    ["TASK7-D1", "A"],
    ["TASK7-D2", "9663676416"],
    ["TASK7-D3", "max(operation_expiry,bridge_ack_at+2592000s)"],
    ["TASK7-D4", "result_unknown_no_auto_retry"],
  ].every(([id, value]) => hasAcceptedDecision(id, value));
  if (!task7DecisionsAccepted) {
    blockers.push({
      code: "TASK7-DECISIONS",
      detail: "Task 7 D1-D4 product/security decisions are not recorded as accepted in docs/mvp/p0a-gate-decisions.md",
    });
  }

  const task9ProductDecisionsAccepted = [
    ["TASK9-event-lifetime", "device_event=86400s,event_ack=300s"],
    ["TASK9-replay-policy", "task5_default"],
  ].every(([id, value]) => hasAcceptedDecision(id, value));
  if (!task9ProductDecisionsAccepted) {
    blockers.push({
      code: "TASK9-REVIEW",
      detail: "Task 9 product lifetime/replay decisions are not recorded as accepted in docs/mvp/p0a-gate-decisions.md",
    });
  } else if (!hasAcceptedDecision("TASK9-technical-gate", "production_ready")) {
    blockers.push({
      code: "TASK9-REVIEW",
      detail: "Task 9 product literals and bounded pre-replay authority gate are accepted, but fixed vectors, production durability and deployed routing remain pending",
    });
  }
  return blockers;
};

const printReport = (mode: ReadinessMode): { sourcePass: boolean; blockers: ReleaseBlocker[] } => {
  const packetAudits = auditPackets();
  const blockers = auditReleaseBlockers();
  const sourcePass = packetAudits.every((packet) => packet.missingArtifacts.length === 0);

  console.log(`MVP readiness report (mode=${mode})`);
  console.log("Source artifact audit (WP-00..WP-10):");
  for (const packet of packetAudits) {
    if (packet.missingArtifacts.length === 0) {
      console.log(`- ${packet.id} PASS — ${packet.label}`);
    } else {
      console.log(`- ${packet.id} MISSING — ${packet.label}: ${packet.missingArtifacts.join(", ")}`);
    }
  }
  console.log(`- source_artifacts=${sourcePass ? "PASS" : "FAIL"}`);

  console.log("Production blockers (never substituted by SDK-free fakes):");
  if (blockers.length === 0) {
    console.log("- none");
  } else {
    for (const blocker of blockers) console.log(`- ${blocker.code}: ${blocker.detail}`);
  }

  if (mode === "sdk-free") {
    if (sourcePass) console.log("SDK_FREE_READINESS_PASS (contract/static checks only; production gate not claimed)");
    else console.log("SDK_FREE_READINESS_BLOCKED: source artifact audit failed");
  } else if (sourcePass && blockers.length === 0) {
    console.log("RELEASE_READINESS_PASS");
  } else {
    console.log("RELEASE_READINESS_BLOCKED");
  }
  return { sourcePass, blockers };
};

export const runReadiness = (mode: ReadinessMode): number => {
  const result = printReport(mode);
  if (!result.sourcePass) return 1;
  return mode === "release" && result.blockers.length > 0 ? 1 : 0;
};

const mode = process.argv[2];
if (process.argv[1]?.endsWith("mvp-readiness.ts")) {
  if (mode !== "--sdk-free" && mode !== "--release") {
    console.error("usage: node --experimental-strip-types mvp-contract/tools/mvp-readiness.ts --sdk-free|--release");
    process.exitCode = 2;
  } else {
    process.exitCode = runReadiness(mode.slice(2) as ReadinessMode);
  }
}
