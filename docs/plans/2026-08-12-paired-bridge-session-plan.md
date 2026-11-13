# Paired Bridge Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JVM-testable Android coordinator that validates Bridge-issued pairing material, owns the userspace core lifecycle, and reconnects with durable connection-generation fencing.

**Architecture:** Keep `core-model` and `tailnet-core` boundaries unchanged. Extend the existing `TsnetPairedBridgeTransport` with an explicit reconnect operation, then wrap it in a `PairedBridgeSessionCoordinator` that mints bindings, starts/stops `TailscaleUserspaceCore`, and serializes lifecycle operations. All tests use recording fakes; the real Tailscale AAR remains behind `LibTailscaleBinding`.

**Tech Stack:** Kotlin 2.1.20, Android library modules, Kotlin coroutines `1.9.0` for a lifecycle mutex, JUnit 4, Gradle 8.12, SDK 35, minSdk 34.

## Global Constraints

- Never add URL, IP, port, route, proxy, generic socket, VPN, or listener APIs.
- Only `VerifiedPairingTransportBinding` may cross the transport boundary.
- Binding validation must happen before userspace core startup or channel opening.
- Connection generation is reserved and persisted before a `BridgeSession` is exposed.
- Reconnect must fence the old session before opening the new generation.
- Failed setup must not expose a partial session and must clean up the core/channel.
- Tests must assert observable behavior of recording fakes, not mock existence.
- Preserve all unrelated user changes in the dirty worktree.
- Run host/static checks and Gradle tests before claiming completion.

## File Map

- Modify: `apps/android/transport/build.gradle.kts` — declare the explicit coroutines implementation needed by the coordinator mutex.
- Modify: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/TsnetPairedBridgeTransport.kt` — add reconnect fencing while preserving the existing `PairedBridgeTransport` API.
- Create: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/PairedBridgeSessionCoordinator.kt` — add `PairingMaterial`, high-level connect/reconnect, core lifecycle and cleanup.
- Modify: `apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt` — add failing tests and recording fakes for coordinator behavior.

## Task 1: Add the failing coordinator and reconnect tests

**Files:**
- Modify: `apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt`

**Interfaces:**
- Consumes existing `TailscaleUserspaceCore`, `NoBackupTailnetStateStore`, `PersistentConnectionGenerationStore`, `VerifiedPairingTransportBindingFactory`, and `TsnetPairedBridgeTransport` seams.
- Produces the executable expectations for `PairingMaterial`, `PairedBridgeSessionCoordinator.connect`, `reconnect`, `close`, and `status`.

- [ ] **Step 1: Add a recording core with lifecycle counters and per-channel close state.**

Extend the test-only recording fake so it records `startCalls`, `stopCalls`, `openCalls`, the node identity, restored state, and each channel's `closeCalls`. Its `openPairedBridge` returns a fresh channel per call and stores the channels for later assertions. Keep the existing `RecordingCore` tests intact or use a separate `LifecycleRecordingCore` helper so current transport tests retain their original behavior.

- [ ] **Step 2: Write a failing test for material rejection before core startup.**

Add a test with an expired `EnrollmentTicket` and a fresh coordinator. Run `coordinator.connect(material)` and assert `IllegalArgumentException`, `startCalls == 0`, and `openCalls == 0`.

```kotlin
@Test
fun coordinator_rejects_invalid_material_before_starting_core() {
    val core = LifecycleRecordingCore()
    val coordinator = coordinator(core)
    val invalid = PairingMaterial(
        ticket = ticket(expiresAtEpochSeconds = 9),
        bridge = BridgeIdentity("bridge-a"),
        policy = PolicyAttestation(2u, "digest"),
        expectedPairingGeneration = 7u,
        nowEpochSeconds = 10,
    )

    assertThrows(IllegalArgumentException::class.java) {
        runSuspend { coordinator.connect(invalid) }
    }
    assertEquals(0, core.startCalls)
    assertEquals(0, core.openCalls)
}
```

- [ ] **Step 3: Run the focused test and verify it fails for the missing production API.**

Run: `cd apps/android && ./gradlew --no-daemon :transport:testDebugUnitTest --tests com.openandroidintelligence.transport.TransportBoundaryTest.coordinator_rejects_invalid_material_before_starting_core`

Expected: compilation failure because `PairingMaterial`, `PairedBridgeSessionCoordinator`, and the coordinator test helper do not yet exist. In this environment the run was blocked earlier by the missing pinned Kotlin compiler artifact; the test source was still kept red-first and was not treated as passing.

- [ ] **Step 4: Add the remaining behavior-first tests before implementation.**

Add focused tests for these observable contracts:

```kotlin
@Test
fun coordinator_connect_starts_core_once_and_returns_first_generation()

@Test
fun coordinator_reconnect_closes_old_channel_persists_new_generation_and_fences_old_session()

@Test
fun coordinator_reconnect_failure_stops_core_and_exposes_no_new_session()

@Test
fun coordinator_generation_race_closes_new_channel_and_reports_stale_generation()

@Test
fun coordinator_close_stops_core_and_is_idempotent()

@Test
fun coordinator_restores_generation_after_process_restart()
```

The reconnect test must send through the first session before reconnect, assert the first channel is closed, assert the second session has generation `2u`, and assert the first session's subsequent send throws `IllegalStateException`. The restart test must reuse one `RecordingGenerationPersistence`, create two coordinators, and assert the second session uses generation `2u`.

- [ ] **Step 5: Run the complete focused test class and confirm it is red for missing implementation.**

Run: `cd apps/android && ./gradlew --no-daemon :transport:testDebugUnitTest --tests com.openandroidintelligence.transport.TransportBoundaryTest`

Expected: the test task fails because the new coordinator/reconnect APIs are absent, not because existing tests regress.

## Task 2: Implement reconnect fencing in the low-level transport

**Files:**
- Modify: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/TsnetPairedBridgeTransport.kt`

**Interfaces:**
- Consumes existing `PairingReconnectStateMachine` and `UserspaceBridgeChannel`.
- Produces `suspend fun reconnect(binding: VerifiedPairingTransportBinding, cause: TransportCloseReason, attempt: Int): BridgeSession` for the coordinator.

- [ ] **Step 1: Implement explicit reconnect invalidation.**

Add `reconnect` to `TsnetPairedBridgeTransport`. Validate `attempt > 0`, capture the active session, set `active = null`, mark the old generation disconnected with the supplied cause, close the old channel, and call the existing `open(binding)` to reserve the next generation. If no active session exists, call `open(binding)` directly. The old session must fail its existing `checkCurrent()` after `active` is cleared.

- [ ] **Step 2: Keep cleanup and stale-generation behavior fail-closed.**

If closing the old channel throws, retain the old session as fenced and continue through the transport's failure path without opening a second channel. If `open(binding)` detects that another connection reserved the generation, close the newly opened channel and preserve `Failed(STALE_GENERATION)`.

- [ ] **Step 3: Run the existing transport tests plus the reconnect-focused test.**

Run: `cd apps/android && ./gradlew --no-daemon :transport:testDebugUnitTest --tests com.openandroidintelligence.transport.TransportBoundaryTest`

Expected: the low-level reconnect behavior is implemented, but coordinator tests still fail because the coordinator does not yet exist. Existing fake transport, binding, state, and current tsnet adapter tests must remain passing.

## Task 3: Implement the lifecycle coordinator minimally

**Files:**
- Create: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/PairedBridgeSessionCoordinator.kt`
- Modify: `apps/android/transport/build.gradle.kts`

**Interfaces:**
- Consumes `TailscaleUserspaceCore`, `NoBackupTailnetStateStore`, `ConnectionGenerationStore`, `VerifiedPairingTransportBindingFactory`, and `TsnetPairedBridgeTransport`.
- Produces `PairingMaterial`, `PairedBridgeSessionCoordinator.connect`, `reconnect`, `open`, `close`, and `status`.

- [ ] **Step 1: Add the explicit coroutine dependency.**

In `apps/android/transport/build.gradle.kts`, add:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
```

Keep the existing project dependencies unchanged.

- [ ] **Step 2: Add `PairingMaterial` and coordinator state.**

Define the exact data class from the design. The coordinator should hold a private `TsnetPairedBridgeTransport`, `core`, `nodeIdentity`, `stateStore`, a `Mutex`, and `coreStarted = false`. The low-level transport receives the injected `generationStore` and keeps the existing default path `TransportPath.DIRECT`.

- [ ] **Step 3: Implement binding minting before the mutex.**

`connect(material)` and `reconnect(material, cause, attempt)` must call:

```kotlin
VerifiedPairingTransportBindingFactory.mint(
    ticket = material.ticket,
    bridge = material.bridge,
    policy = material.policy,
    expectedPairingGeneration = material.expectedPairingGeneration,
    nowEpochSeconds = material.nowEpochSeconds,
)
```

Minting before lifecycle mutation ensures invalid reconnect material leaves a valid active session untouched.

- [ ] **Step 4: Implement serialized connect/open.**

Inside `Mutex.withLock`, `open(binding)` must start the core once with `core.start(nodeIdentity, stateStore)`, then delegate to `TsnetPairedBridgeTransport.open(binding)`. On any failure, call the low-level close with `FAILURE`, stop the core if it was started, clear `coreStarted`, and rethrow the original failure. Do not return a session unless delegate open completes successfully.

- [ ] **Step 5: Implement serialized reconnect.**

Inside the mutex, start the core if needed, delegate to `TsnetPairedBridgeTransport.reconnect(binding, cause, attempt)`, and return its session. On failure, close with `FAILURE`, stop the core, clear `coreStarted`, and rethrow. The delegate is responsible for fencing the old session before opening the new generation.

- [ ] **Step 6: Implement close and status.**

`close(reason)` must always attempt low-level close, then stop the core in a `finally` block when `coreStarted` is true, and clear the flag. Preserve the first failure if cleanup itself fails. `status()` returns the delegate status without exposing internal channel/core handles.

- [ ] **Step 7: Run focused tests and make them green.**

Run: `cd apps/android && ./gradlew --no-daemon :transport:test --tests com.openandroidintelligence.transport.TransportBoundaryTest`

Expected: all transport tests pass, including the new coordinator tests. The implementation is complete, but the current environment cannot execute this task because `kotlin-compiler-embeddable:2.1.20` is not cached and Maven access is too slow to complete.

## Task 4: Refactor only after green and verify all Android gates

**Files:**
- Modify only the files listed in the File Map if cleanup is necessary.

- [ ] **Step 1: Review for duplicate lifecycle logic.**

Keep generation allocation and active-session checks in `TsnetPairedBridgeTransport`; keep core start/stop and Bridge material minting in the coordinator. Remove only duplication introduced by the new tests or implementation. Do not change public protocol schemas or app manifests.

- [ ] **Step 2: Run focused Android module tests.**

Run: `cd apps/android && ./gradlew --no-daemon :transport:testDebugUnitTest :tailnet-core:testDebugUnitTest`

Expected: exit code `0`; all transport and tailnet-core tests pass. Blocked here by the same missing Kotlin compiler artifact.

- [ ] **Step 3: Run the host-side boundary test.**

Run: `python3 apps/android/tools/test_transport_boundary.py`

Expected: all host boundary tests pass and no forbidden network/VPN surface is reported.

- [ ] **Step 4: Run the complete Android check task.**

Run: `cd apps/android && ./gradlew --no-daemon check`

Expected: exit code `0`. This remains unrun because the focused Kotlin compilation prerequisite is unavailable.

- [ ] **Step 5: Inspect the final diff and status.**

Run: `git diff --check` and `git status --short`.

Expected: only the approved design/plan plus the scoped transport implementation/test changes are present; unrelated pre-existing user changes remain untouched. Commit is optional because the environment currently rejects `.git/index.lock` creation.
