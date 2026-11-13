# MVP Production Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the shortest production-shaped slice in which one Android 34+ installation pairs with one private Bridge through an app-scoped Tailscale userspace node, reads policy-bounded notifications, exposes the same authenticated query/event contract to Hermes and OpenClaw, and renders a text reply through the selected default-assistant entry point.

**Architecture:** Keep P0a as the only protocol/security authority. Android owns collection and a narrow paired-Bridge transport; Bridge owns enrollment, durable operations/events, tenant/principal/session binding and egress decisions; Hermes and OpenClaw are thin server-side adapters with one authoritative profile each. M1.1 attachments are a separate post-slice milestone because they require P0a Task 10 artifact tickets and proof-of-possession.

**Tech Stack:** Existing Node 24/TypeScript P0a reference model and Vitest; Kotlin/Android API 34+ with pinned Gradle/AGP/Kotlin/AndroidX/Compose; a pinned Tailscale Go userspace core built as an AAR; Bridge runtime selected by the controller dependency lock; Hermes/OpenClaw official plugin profiles.

## Current task/worktree alignment (2026-08-11)

| Area | Worktree | Status | Evidence / remaining gate |
| --- | --- | --- | --- |
| Task 5 transport/security | `.worktrees/p0a-protocol-security-model` / `feat/p0a-protocol-security-model` | implementation GREEN, merge pending at this checkpoint | 32 protocol test files / 334 tests and typecheck pass; release integration still requires review record and merge |
| Task 6 authorization/grants | protocol worktree | reference-contract GREEN | capability, revision, grant, egress, risk and replay-association seams pass; production ingress/lock still pending |
| Task 7 operations/artifacts | protocol worktree | independent slice GREEN, canonical gate partial | operation reducer/ledger, schemas, error/migration fixtures and vectors pass; replay-policy/message rows and any unresolved product-dependent vectors remain gated |
| Task 9 events/ACK | protocol worktree | bounded reference slice GREEN | accepted literals are `device_event=24h`, `event_ack=5m`, replay `task5_default`; the pre-replay authority gate and mismatch/precedence matrix are covered, while fixed cross-language vectors, production cursor/ACK durability and deployed routing remain pending |
| WP-00..WP-10 MVP source slice | main checkout | SDK-free GREEN | `run-smoke.sh --sdk-free`: 16 Vitest files / 98 tests, Android static 48/48; release gate intentionally blocked by locks/toolchain/device/production adapters |

The two worktrees are intentionally committed independently: the protocol branch
holds the P0a reference implementation, while `main` holds the Android/Bridge/
integration source slice. No row below is promoted to production-ready solely by
these host/static results.

## Global Constraints

- The Android product MUST NOT declare or start `VpnService`, create a system TUN, modify system routes/DNS, expose a listener/proxy/LocalAPI/Funnel, or expose generic Tailnet `Dial`/`Listen`.
- The only real device target is the enrollment-ticket-bound Bridge identity on TCP 443; callers never provide host, port, URL, socket, route or DNS parameters.
- A failed P0t userspace feasibility gate pauses real transport work. No `VpnService` fallback and no automatic public-HTTPS fallback are permitted.
- P0a Task 5/6/7/9 signed envelopes, authorization, operation/receipt and event/ACK semantics are imported; this slice never creates a parallel bearer-token protocol.
- Default notification policy is an empty allowlist with metadata access and revision `0`; denied records are neither persisted nor sent.
- Adapter context is server-minted and contains tenant, human principal, agent principal, agent instance, workspace and exactly one session/job. Model/tool arguments cannot supply or override it.
- No SMS, calls, contacts, clipboard, location, health, sensors, calendar, alarms, Accessibility, screen capture/control, Device Owner/Shizuku/Root, background commands, generic execution or automation DSL is implemented in this slice.
- Device notification and chat text, enrollment secrets, auth keys, signed envelopes and provider payloads are absent from logs, disk retry caches and audit bodies.
- Every implementation task is test-first and ends with one focused commit; real-device evidence is content-free and reproducible.

---

## Baseline and dependency boundary

The main checkout now contains the Android/Bridge/integration MVP source and contract tests, including source-only capability/control ports and a durable-store boundary. The production-shaped TypeScript protocol sources and tests remain in the `feat/p0a-protocol-security-model` worktree. That worktree contains cumulative Task 5 hardening plus Task 6/7 implementation and independent Task 7 artifact evidence; its current implementation is committed at `c75a96a` plus the Task 9 pre-replay gate commit `1b84e60`, but it is still a separate release line from `main`. No production packet may claim release readiness until the dependency lock and physical gates pass.

The current repository includes `apps/android/`, `bridge-contract/`, `bridge-runtime/` (local adapter work in progress), `integrations/` and `e2e/mvp/`. These are contract/static and process-local implementations until the controller locks exact versions and a production Bridge adapter. Existing commands are the SDK-free smoke/readiness launchers; Android and Bridge release launchers remain gated by the controller lock.

### Confirmed TSNET Android decision

The Android transport uses the project-built, minimal `tsnet-android` AAR described
in `docs/superpowers/specs/2026-08-11-tsnet-android-decision.md`. It is built from
pinned Tailscale Go userspace sources through gomobile/NDK; it is not an assumed
official or third-party AAR. The AAR exposes only the ticket-bound Bridge session
port and connection state. It must not introduce `VpnService`, TUN, global proxy,
system route/DNS changes, listeners, Funnel, LocalAPI or generic Tailnet dial/listen.
`MVP-DEP-TSNET` remains pending until the reproducible AAR build and API 34+ physical
evidence are available.

### Task 5 status and non-blocking rule

The Task 5 implementation now supplies verified pairing binding, connection-generation fencing, signed control bytes, key identity, replay admission, durable recovery checks, compaction/accounting and guarded key rotation. It is verified in the protocol worktree and is committed independently from the main MVP slice; it is not yet merged into the main release line. Therefore:

1. The Android transport spike (WP-05) consumes only the committed public ports and deterministic fakes. It may start from `07a5c307` and does not wait for the repair diff.
2. No real Bridge or physical E2E integration may claim production completion until the dependency lock, Task 5/6/7 review records and physical gates are accepted.
3. Task 7’s operation reducer, authorization/revision/receipt admission and quota implementation are present; D1–D4 are recorded in `docs/mvp/p0a-gate-decisions.md`, while implementation/vector and production gates remain explicit.
4. Task 6 remains the owner of capability-registry membership, current authorization, revision state, grants and zero-retention evidence; Task 5 remains the owner of authentication, scope syntax/order/duplicate rejection and opaque binding contexts.

### Controller dependency lock (must be committed before real work)

Create `docs/mvp/mvp-dependency-lock.md`. Each row is closed only when it contains `decision_id`, official reference, immutable version/commit, checksum or integrity reference, license review, reviewer/time and an executable verification command. A missing, expired or altered row blocks the corresponding real task; contract fakes remain runnable.

| ID | Lock exactly | Blocks |
|---|---|---|
| `MVP-DEP-ANDROID` | AGP, Gradle wrapper, Kotlin, Compose/AndroidX, min/target SDK, Notification Listener/RoleManager/VoiceInteractionService/WorkManager/no-backup APIs | WP-02, WP-03, WP-08, WP-09 |
| `MVP-DEP-TSNET` | Tailscale Go source commit, gomobile/NDK/ABI route, state-store/client-dial API, Go toolchain and resource budgets | WP-05, WP-09 |
| `MVP-DEP-BRIDGE` | Bridge runtime, durable transaction/database, authenticated ingress, WSS, secret store, migrations and health command | WP-06, WP-09 |
| `MVP-DEP-HERMES` | Verified release/tag and one official plugin/platform profile carrying the full authenticated binding | WP-07, WP-09 |
| `MVP-DEP-OPENCLAW` | Verified release/tag and one official Gateway/plugin profile carrying the same binding | WP-07, WP-09 |
| `MVP-DEP-MODEL` | Model destination/profile, zero-retention evidence revision/expiry, health cadence and bounded in-memory retry policy | WP-06, WP-08, WP-09 |
| `MVP-DEP-ARTIFACT` | Controller-approved object store/upload/scanner and 24-hour orphan policy compatible with Task 10 | WP-10 only |

---

## Frozen MVP contracts

WP-01 writes closed Draft 2020-12 schemas with `additionalProperties:false` (and `unevaluatedProperties:false` where applicable):

- `mvp-contract/schemas/v1/notification-policy.schema.json`: `allowlist|denylist`, Unicode-code-point-sorted unique package IDs, `metadata|content`, decimal-u64 `policy_revision`.
- `mvp-contract/schemas/v1/notification-record.schema.json`: `upsert|delete_tombstone|loss_marker`; metadata/content/loss nullability is closed; every record has Task-9 source epoch, cursor and capture revision; no agent/workspace/session/job fields.
- `mvp-contract/schemas/v1/notification-api.schema.json`: `mobile.notifications.query`, `subscribe`, `unsubscribe`; `on_demand|auto_send`; complete/waiting_device/failed responses; `limit` 1..100.
- `mvp-contract/schemas/v1/assistant-chat.schema.json`: text-only request/response bound to one assistant session and message ID.

The Kotlin consumer ports are fixed before implementation:

```kotlin
interface PairedBridgeTransport {
    suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession
    suspend fun close(reason: TransportCloseReason)
}
interface BridgeSession {
    suspend fun sendControl(canonicalWire: ByteArray)
    suspend fun receiveControl(): ByteArray
    val connectionGeneration: ULong
}
interface NotificationCollector {
    suspend fun applyPolicy(policy: NotificationCollectionPolicyV1)
    suspend fun captureOnDemand(request: OnDemandNotificationRead): NotificationCaptureResult
    fun observeAutoSend(): Flow<NotificationCaptureResult>
}
interface NotificationOutbox {
    suspend fun enqueueAccepted(record: NotificationRecordV1): DurableEvent
    suspend fun acknowledge(eventId: String, eventAckWire: ByteArray)
    suspend fun recoverUnacknowledged(): List<DurableEvent>
}
```

`VerifiedPairingTransportBinding` is constructor-private and can be minted only after enrollment ticket, Bridge key, current policy-attestation and pairing-generation verification. It contains no caller-controlled endpoint. `captureOnDemand` is callable only after a durable Task-7 execution claim; `observeAutoSend` emits only after local policy and Task-6 authorization pass.

The server adapter contract is:

```text
mobile.notifications.query(request: NotificationQueryRequestV1 + policy_revision + closed filter): NotificationQueryResponseV1
mobile.notifications.subscribe(request: NotificationSubscriptionRequestV1 + policy_revision + closed filter): { subscription_id: string }
mobile.notifications.unsubscribe(subscription_id, policy_revision): { removed: boolean }
```

The server derives `AuthenticatedAdapterInvocationV1` from its authenticated ingress. A retry with the same upstream operation/tool-call identity reads the existing Task-7 operation; it never invokes collection again. Auto-send is a subscription, not a command. The Bridge derives routing from its durable subscription binding, not model-supplied user/device/session values.

---

## Work packets and execution order

The packets below are the canonical handoff units. WP-01 through WP-04 are deterministic and can be dispatched in parallel after WP-00. WP-05 is an isolated P0t feasibility spike and may run while Task 5 review, Task 6 and Task 7 finish. WP-06 and WP-07 require the stated P0a gates. WP-09 is the only serial integration packet. WP-10 is post-MVP M1.1.

### WP-00 — Controller locks and contract-test launcher

**Files:** Create `docs/mvp/mvp-dependency-lock.md`, `mvp-contract/test/dependency-lock.test.ts`, `mvp-contract/tools/check-lock.ts`; modify `package.json` scripts only.

**Dependencies:** Existing Node 24 launcher; no Task 5/6/7 implementation.

**RED:** Test missing, duplicate, expired and checksum-altered rows; assert every production packet’s `blocks` ID is present exactly once.

**GREEN:** Implement a pure lock validator that reads the seven rows above, rejects missing/expired/altered evidence, and adds `npm run mvp:lock:check`.

**Verify:** `tools/run-node24 npm test -- mvp-contract/test/dependency-lock.test.ts && tools/run-node24 npm run mvp:lock:check`.

**Commit:** `git add docs/mvp/mvp-dependency-lock.md mvp-contract package.json && git commit -m "docs(mvp): lock production dependencies"`.

### WP-01 — Closed contracts and deterministic fixtures

**Files:** Create the four schemas under `mvp-contract/schemas/v1/`, `docs/mvp/mvp-vertical-slice-contract.md`, and `mvp-contract/test/mvp-contract.test.ts`.

**Dependencies:** WP-00; imports only committed P0a schema IDs/error registry, never the dirty Task 5 repair files. Can run alongside WP-02.

**RED:** Add fixtures covering empty-default denial, both rule modes, Unicode order/duplicates, metadata stripping, all three record kinds, content authorization denial, principal/session injection, `limit` 0/1/100/101 and every response state.

**GREEN:** Add a schema catalog check and fixtures that reject every extra field, wrong nullability, unknown error code and model-supplied invocation context.

**Verify:** `tools/run-node24 npm test -- mvp-contract/test/mvp-contract.test.ts && tools/run-node24 npm run typecheck`.

**Commit:** `git add docs/mvp/mvp-vertical-slice-contract.md mvp-contract && git commit -m "feat(mvp): freeze notification and chat contracts"`.

### WP-02 — Android multi-APK skeleton and no-VPN static gate

**Files:** Create `apps/android/settings.gradle.kts`, root/app/holder/module build files, pinned wrapper metadata, `apps/android/app/src/main/AndroidManifest.xml`, `apps/android/assistant-holder/src/main/AndroidManifest.xml`, and `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts`.

**Dependencies:** WP-00 and `MVP-DEP-ANDROID`; no Task 5/6/7 runtime. Can run alongside WP-01.

**RED:** Merged-manifest and source-import tests require two package names/two UIDs; reject `VpnService`, `BIND_VPN_SERVICE`, TUN/route/DNS APIs, proxy/listener APIs, `QUERY_ALL_PACKAGES`, and any holder `INTERNET`. Assert only `transport` depends on `tailnet-core`, and holder declares no notification/network/sensitive permissions.

**GREEN:** Add empty compiling modules (`app`, `assistant-holder`, `core-model`, `policy-engine`, `notification-collector`, `tailnet-core`, `transport`, `encrypted-store`) and the static gate. Configure encrypted no-backup app-private storage without implementing collection or networking.

**Verify:** `apps/android/gradlew --no-daemon :app:check :assistant-holder:check && apps/android/gradlew --no-daemon check`.

**Commit:** `git add apps/android && git commit -m "feat(android): add isolated MVP skeleton and no-vpn gate"`.

### WP-03 — Deterministic policy, collector and event outbox

**Files:** Create `apps/android/core-model/.../NotificationContracts.kt`, `policy-engine/.../NotificationPolicyEvaluator.kt`, `notification-collector/.../AndroidNotificationCollector.kt`, `encrypted-store/.../NotificationOutboxStore.kt`, and unit/device tests in the corresponding `src/test` and `src/androidTest` trees.

**Dependencies:** WP-01 and WP-02. Uses fake `AuthorizationDecision`; Task 6 is not required for deterministic tests.

**RED:** Test default denial, allow/deny matching, duplicate rejection, metadata stripping, Task-6 fake denial after local allow, policy revision race, exact upsert/tombstone/loss shapes, encrypted retention, wrong/missing ACK, restart retransmission and forged agent/session fields.

**GREEN:** Implement a closed evaluator, `NotificationListenerService` normalizer and durable outbox. Denied callbacks have no persistence path; outbox retains the exact Task-9 event until its signed ACK.

**Verify:** `apps/android/gradlew --no-daemon :notification-collector:test :encrypted-store:test :notification-collector:connectedAndroidTest && apps/android/gradlew --no-daemon :app:lint`.

**Commit:** `git add apps/android/core-model apps/android/policy-engine apps/android/notification-collector apps/android/encrypted-store && git commit -m "feat(android): collect policy-bounded notifications"`.

### WP-04 — Fake paired transport and cross-layer trace harness

**Files:** Create `apps/android/transport/.../PairedBridgeTransport.kt`, deterministic Kotlin fake/tests, `bridge-contract/src/fake-bridge.ts`, `bridge-contract/test/fake-bridge.test.ts` and `bridge-contract/test/notification-flow.trace.test.ts`.

**Dependencies:** WP-01; committed Task 5 public binding/fence interfaces only. Can run in parallel with WP-03. No real network and no Task 6/7 implementation.

**RED:** Fixed-clock traces cover enrollment-to-pairing, reconnect fencing, ACK loss/restart, one-operation retry, policy revoke, cross-user subscription rejection and content-free diagnostics. Compile-time checks prove no fake or production transport interface accepts host/port/dial/listen.

**GREEN:** Implement in-memory control bytes, disconnect/duplicate/stale-generation/missing-ACK fault injection and a fake Bridge that records exactly one operation claim per identity.

**Verify:** `apps/android/gradlew --no-daemon :transport:test && tools/run-node24 npm test -- bridge-contract/test/fake-bridge.test.ts bridge-contract/test/notification-flow.trace.test.ts`.

**Commit:** `git add apps/android/transport bridge-contract && git commit -m "test(mvp): add deterministic paired notification traces"`.

### WP-05 — P0t userspace Tailnet spike and paired reconnect

**Files:** Create `apps/android/tailnet-core/.../TailscaleUserspaceCore.kt`, `VerifiedPairingTransportBinding.kt`, `NoBackupTailnetStateStore.kt`, physical-device tests, `apps/android/transport/.../RealPairedBridgeTransport.kt`, and `docs/mvp/p0t-mvp-evidence.md`.

**Dependencies:** WP-02, WP-04, `MVP-DEP-TSNET`; consumes stable Task 5 binding/fence ports. It does not wait for the uncommitted Task 5 repair diff, Task 6, or Task 7.

**RED:** On arm64-v8a and x86_64 API 34+ environments test valid/used/expired/tampered ticket, wrong Bridge identity/port, stale policy attestation, process-death restore, changed pairing generation, network switch, Doze, direct/DERP and another-system-VPN allowed/blocked paths. Assert no public fallback, second device identity, generic dial/listen or secret/content logging.

**GREEN:** Build only the locked project `tsnet-android` userspace AAR. Keep node state in encrypted no-backup storage, clear auth key after enrollment, constrain application traffic to the ticket-bound Bridge TCP 443 and persist connection generation before exposing the session. The first production artifact may target arm64-v8a only; additional ABIs require the same reproducible lock and evidence.

**P0t hard gate:** merged manifests contain no VPN service; `adb shell dumpsys vpn` shows no product VPN; route/DNS snapshots are unchanged; no proxy/listener/LocalAPI/TUN calls are observed; egress capture contains only approved control/STUN/DERP and the bound Bridge endpoint; the reference phone still permits another system VPN. Run split/full-tunnel and always-on+lockdown blocking tests; blocked paths report the real failure and never auto-switch to HTTPS. Any failure pauses WP-06/WP-09 and returns to design review—`VpnService` is not an allowed remediation.

**Verify:** `apps/android/gradlew --no-daemon :tailnet-core:connectedAndroidTest :transport:connectedAndroidTest`; run the locked resource command and attach redacted direct/DERP/VPN/16-KiB-page evidence.

**Commit:** `git add apps/android/tailnet-core apps/android/transport docs/mvp/p0t-mvp-evidence.md && git commit -m "feat(android): prove userspace tailnet transport gate"`.

### WP-06 — Bridge pairing, notification API/event routing and text chat

**Files:** Create `bridge-contract/src/{pairing-service,notification-service,notification-store,subscription-store,operation-dispatch,assistant-chat-service}.ts`, tests, and `bridge-runtime/` wiring/migrations selected by `MVP-DEP-BRIDGE`.

**Dependencies:** WP-01/WP-04; Task 5 review accepted; Task 6 canonical amendment accepted and implemented; Task 7 `[WAIT-T6]` authorization/revision/receipt contract accepted; `MVP-DEP-BRIDGE` and `MVP-DEP-MODEL` locked. This is the first packet allowed to use real P0a control wire.

**RED:** Test ticket single-use/expiry/replay, tenant and two-user isolation, pairing-generation continuity, Task-6 capability/grant/revision denial, Task-7 claim/result crash cuts, exact-once on-demand retry, Task-9 event validation/ACK/cursor/source epoch, subscription-only routing, cross-session lookup rejection, stale zero-retention evidence and provider-retained-object denial.

**GREEN:** Implement durable transactions around accepted P0a stores. Device ingress is signed control wire over the paired session; adapter ingress begins with server-authenticated invocation. Chat permits text only while Task-6 zero-retention evidence is current and keeps no body logs/spool/retry disk.

**Verify:** `tools/run-node24 npm test -- bridge-contract/test && tools/run-node24 npm run mvp:lock:check`; run the locked Bridge migration/health command.

**Commit:** `git add bridge-contract bridge-runtime docs/mvp package.json package-lock.json && git commit -m "feat(bridge): serve bound notification and chat contracts"`.

### WP-07 — Hermes/OpenClaw adapters, plugins and shared skill

**Files:** Create `integrations/hermes/{adapter.ts,plugin-manifest.json,adapter.test.ts}`, `integrations/openclaw/{adapter.ts,plugin-manifest.json,adapter.test.ts}`, `integrations/shared/notification-contract.test.ts`, and `integrations/skills/android-device-bridge/SKILL.md`.

**Dependencies:** WP-06; Task 6 capability/egress contract and Task 7 authenticated invocation/operation/receipt contract; `MVP-DEP-HERMES`, `MVP-DEP-OPENCLAW` and `MVP-DEP-MODEL` locked.

**RED:** For each pinned profile test query/event/text round trips, two same-tenant humans, changed workspace/session/job, cross-workspace assistant mapping, model-supplied tenant/device injection, duplicate/absent profile, unbound event, loss marker, waiting device, denied content and unknown tool. Assert byte-equivalent normalized JSON and no body/text logs.

**GREEN:** Expose exactly the three frozen mobile tools, one event shape and one bound text-chat mapping per adapter. Startup rejects duplicate/absent authoritative profiles. The skill only instructs device selection, access declaration, single operation identity, honest failure/loss handling and untrusted-text treatment; it cannot alter policy/auth/routing or retry with a new operation.

**Verify:** `tools/run-node24 npm test -- integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts integrations/shared/notification-contract.test.ts`; run both locked plugin-load commands.

**Commit:** `git add integrations docs/mvp/mvp-dependency-lock.md && git commit -m "feat(integrations): add bound Hermes and OpenClaw adapters"`.

### WP-08 — Isolated default-assistant holder and text-only UI

**Files:** Create holder `VoiceInteractionService`/session/status classes, app client/activity, signature IPC permission XML and Android tests under `apps/android/assistant-holder` and `apps/android/app`.

**Dependencies:** WP-02, WP-06, `MVP-DEP-ANDROID` and `MVP-DEP-MODEL`; no notification permission or network in holder.

**RED:** Test holder UID role/active-service verification, package/signature/user-bound IPC, disabled assist/screenshot flags, no microphone/network/store access, role-unavailable fallback, offline failure and one text request/response bound to one assistant session.

**GREEN:** Implement holder-only system entry and minimum signature IPC; call `setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT)`. Main app performs the paired Bridge text request only after user action. OEMs without the role use a visibly labelled activity/shortcut/share fallback.

**Verify:** `apps/android/gradlew --no-daemon :assistant-holder:connectedAndroidTest :app:connectedAndroidTest`; capture role/UID and no-sensitive-permission evidence.

**Commit:** `git add apps/android/assistant-holder apps/android/app && git commit -m "feat(android): add isolated text assistant entry"`.

### WP-09 — Physical Android/Bridge/plugin E2E and release gate

**Files:** Create `e2e/mvp/run-smoke.sh`, fixture notification app, `e2e/mvp/test/mvp-smoke.test.ts`, expected assertions and `docs/mvp/mvp-smoke-evidence.md`.

**Dependencies:** WP-03, WP-05 passing P0t gate, WP-06, WP-07, WP-08; all controller rows locked; Task 5/6/7/9 reviews green. This packet is serial and cannot substitute fakes for unavailable real prerequisites.

**RED:** Fail on missing locks/evidence, VPN declaration, unpaired endpoint, automatic fallback, default-deny leak, metadata/content mismatch, duplicate on-demand collection, duplicate event after ACK loss, cross-user/plugin routing, stale zero-retention evidence or holder UID breach.

**GREEN:** Install the fixture app on an API 34+ reference phone, pair one user/device, exercise policy/query/auto event/Hermes/OpenClaw/text-holder paths, kill/restart processes and collect content-free assertions. Verify `dumpsys vpn`, merged manifests, route/DNS snapshots, second VPN activity, direct/DERP state and no-log capture in the same run.

**Acceptance:** (1) fresh ticket produces one stable `device_id`/`pairing_generation`; process death reconnects without re-pairing; (2) no product VPN/TUN/route/DNS/proxy/listener and another VPN remains eligible; (3) empty policy stores/sends nothing, metadata mode strips content, content requires Task-6 grant, denylist stops subsequent records; (4) on-demand retries reuse one Task-7 operation and auto-send delivers only to its durable server subscription; (5) Hermes and OpenClaw cannot cross tenant/human/workspace/session and reject forged event identity; (6) default-assistant holder renders text only, does not read notifications or network, and labelled fallback is honest; (7) offline, permission, lock, unsupported, queue-loss and zero-retention failures are explicit; (8) notification/chat body never appears in logs/evidence.

**Verify:** `tools/run-node24 npm test && tools/run-node24 npm run typecheck`; run locked Android, Bridge and plugin verification commands; attach redacted evidence with versions, digests, enum outcomes and timestamps.

**Commit:** `git add e2e/mvp docs/mvp/mvp-smoke-evidence.md && git commit -m "test(e2e): prove MVP Android Bridge vertical slice"`.

### WP-10 — M1.1 selected attachments (post-MVP)

**Files:** Add Photo Picker/SAF UI and encrypted cache, Task-10 artifact client/service/tests and `e2e/mvp/test/m1_1-attachment-smoke.test.ts`; create `docs/mvp/m1_1-artifact-evidence.md`.

**Dependencies:** WP-09 complete, P0a Task 10 accepted, `MVP-DEP-ARTIFACT` locked. This packet never replaces signed ticket/PoP/digest/message-commit with a direct upload URL.

**Scope:** User-selected JPEG/PNG/WebP/PDF/`text/plain`, max four files, 25 MiB each and 50 MiB total; encrypted temporary copy; digest before ticket request; interruption gets a new ticket; local copy is removed only after durable `message_committed`; 24-hour orphan reclamation. No broad storage permission.

**Verify:** Android picker/transport tests, Bridge artifact tests and physical smoke; evidence is redacted and does not block the first notification/text demonstration.

---

## Parallelization and hard dependencies

```text
WP-00 controller locks
  ├─ WP-01 schemas/fixtures ─┬─ WP-03 collector/outbox ──────┐
  ├─ WP-02 APK skeleton ─────┘                               ├─ WP-09 E2E
  └─ WP-04 fake transport ───────────────────────────────────┤
       └─ WP-05 P0t userspace spike (Task5 stable ports) ────┤
Task5 review + Task6 + Task7[WAIT-T6] + model lock ─ WP-06 ──┤
WP-06 + Hermes/OpenClaw locks ───────────────────── WP-07 ───┤
WP-02 + WP-06 ───────────────────────────────────── WP-08 ───┘
WP-09 + Task10 + artifact lock ──────────────────── WP-10
```

Task 6 is a hard dependency for real capability membership, authorization revisions, data-query grants and zero-retention egress. Task 7 is a hard dependency for operation claim/result/receipt, adapter invocation binding and exactly-once recovery. Neither blocks WP-02’s forbidden-surface gate, WP-03’s local deterministic policy tests, WP-04’s fake trace harness or WP-05’s userspace feasibility spike. The only real-network hard gate before WP-09 is P0t; failure cannot be worked around with a VPN service or silent HTTPS downgrade.

## Plan self-review

- Every approved first-demo requirement has an owning packet and a test/evidence output; attachments are explicitly separated behind Task 10.
- No packet imports the dirty Task 5 repair diff as authority. The stable Task 5 ports are enough for the transport spike; Task 5 review remains required before real Bridge wire integration.
- Task 6/7 ownership and gates are explicit, including Task 7’s independent versus `[WAIT-T6]` portions.
- The no-VPN guarantee is both a build/static gate and a physical runtime gate covering `dumpsys`, routes/DNS, egress, second VPN and blocking conditions.
- No task adds generic execution, arbitrary networking, hidden permissions, dynamic plugin registration or model-controlled identity.
- All dependency choices not frozen by the approved spec are controller lock rows with verification commands; no implementation worker is asked to invent a release/version.
