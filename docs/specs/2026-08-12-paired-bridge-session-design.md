# Paired Bridge Session Design

**Date:** 2026-08-12

**Status:** Approved design; implementation pending.

## Goal

Implement the Android-side pairing session coordinator for the already-defined
ticket-bound userspace transport. The coordinator will validate Bridge-issued
pairing material, start and stop the userspace core, expose a fenced
`BridgeSession`, and reconnect with a durable monotonically increasing
connection generation.

This slice makes the pairing/session lifecycle executable and JVM-testable. It
does not claim that a real Tailscale AAR, Android device, or production Bridge
ingress is available in the current environment.

## Scope

### In scope

- Validate `EnrollmentTicket`, `BridgeIdentity`, `PolicyAttestation`, and the
  expected pairing generation through the existing
  `VerifiedPairingTransportBindingFactory`.
- Start a `TailscaleUserspaceCore` with the configured node identity and
  `NoBackupTailnetStateStore`.
- Open only the ticket-bound `UserspaceBridgeChannel` operation.
- Persist and restore connection generations through the existing
  `ConnectionGenerationStore` abstraction.
- Fence the old Android `BridgeSession` before reconnecting.
- Stop the channel and userspace core on close or failed setup.
- Test success, rejection, fencing, cleanup, and process-restart generation
  behavior with fake/recording implementations.

### Out of scope

- Implementing or vendoring a Tailscale/libtailscale AAR.
- Adding URL, IP, port, route, proxy, generic socket, VPN, or listener APIs.
- Implementing enrollment UI, QR/deep-link parsing, or user-facing pairing
  screens.
- Implementing authenticated Bridge ingress, database persistence, or protocol
  wire encoding.
- Automatic retry loops, background reconnect scheduling, or relay selection.
- Adding Android provider permissions or device/adb tests.

## Architecture

The existing module boundaries remain authoritative:

- `core-model` owns immutable pairing material, `VerifiedPairingTransportBinding`,
  `BridgeSession`, status values, and close/failure enums.
- `tailnet-core` owns the narrow `TailscaleUserspaceCore` seam, no-backup node
  state, connection-generation persistence interfaces, and binding minting.
- `transport` owns the session lifecycle, generation fencing, and the new
  high-level coordinator.
- `app` remains responsible for injecting already-verified pairing material and
  a real `LibTailscaleBinding` in a later integration step. It never supplies an
  endpoint or generic network handle.

The data flow is:

```text
Bridge-issued PairingMaterial
        |
        v
VerifiedPairingTransportBindingFactory.mint(...)
        |
        v
PairedBridgeSessionCoordinator
  |         |                 |
  |         |                 +--> Persistent ConnectionGenerationStore
  |         +--------------------> TailscaleUserspaceCore
  +------------------------------> TsnetPairedBridgeTransport
                                      |
                                      v
                         ticket-bound UserspaceBridgeChannel
```

`TsnetPairedBridgeTransport` remains compatible with the existing
`PairedBridgeTransport` interface used by notification dispatch. Its shared
session implementation owns the active session and generation state. A small
explicit reconnect operation may be added to that implementation so the
coordinator can invalidate the old channel before opening the next generation.

## Public API

The coordinator introduces a high-level input that contains only Bridge-issued
identity material and verification context:

```kotlin
data class PairingMaterial(
    val ticket: EnrollmentTicket,
    val bridge: BridgeIdentity,
    val policy: PolicyAttestation,
    val expectedPairingGeneration: ULong,
    val nowEpochSeconds: Long,
)
```

The coordinator has this shape:

```kotlin
class PairedBridgeSessionCoordinator(
    core: TailscaleUserspaceCore,
    nodeIdentity: String,
    stateStore: NoBackupTailnetStateStore,
    generationStore: ConnectionGenerationStore,
) : PairedBridgeTransport {
    suspend fun connect(material: PairingMaterial): BridgeSession

    suspend fun reconnect(
        material: PairingMaterial,
        cause: TransportCloseReason = TransportCloseReason.NETWORK_CHANGED,
        attempt: Int = 1,
    ): BridgeSession

    fun status(): PairingTransportStatus

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession
    override suspend fun close(reason: TransportCloseReason)
}
```

`connect` and `reconnect` mint the binding internally. `open(binding)` remains
available for existing callers that already possess an authenticated binding;
it does not broaden the transport boundary.

## Lifecycle and fencing rules

1. `connect` mints the binding before changing core/session state. Expired,
   consumed, mismatched, or stale material therefore leaves an existing session
   intact and does not call the userspace core.
2. The coordinator starts the userspace core once before its first channel open.
   Reconnect reuses the running core. A close stops it; a later connect starts a
   new core lifecycle.
3. `PairingReconnectStateMachine.beginOpen` reserves and persists the next
   connection generation before the session is exposed.
4. The coordinator returns a `BridgeSession` only after the channel is opened
   and the generation is marked connected.
5. Reconnect first invalidates and closes the old channel, records the
   reconnecting status, and then opens a new channel with a newer generation.
6. An old session fails its local active-session check after reconnect or close.
   It cannot send or receive bytes through the new channel.
7. A generation race during channel open closes the just-opened channel and
   returns `TransportFailure.STALE_GENERATION` without exposing a session.
8. Any setup failure cleans up the channel if one exists, stops the core when
   the coordinator started it, and does not return a partially initialized
   session. No automatic retry or alternate path is attempted.
9. `close` is idempotent with respect to absent channel/core state and records
   the requested `TransportCloseReason`.

## Error and status behavior

Binding validation uses the existing `IllegalArgumentException` contract from
`VerifiedPairingTransportBinding.mint`:

- used ticket: rejected;
- expired ticket: rejected;
- Bridge fingerprint mismatch: rejected;
- pairing generation mismatch: rejected;
- policy attestation below the ticket minimum: rejected.

These validation failures occur before core start or `openPairedBridge`.

Transport failures preserve the existing closed status vocabulary. A stale
generation produces `Failed(STALE_GENERATION)`. An ordinary setup failure
closes with `FAILURE`; the original exception remains the thrown cause. A
successful reconnect reports `Connected(newGeneration, path)`.

## Test strategy

Tests are added before implementation in
`apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt`.
Recording fakes will capture observable effects rather than mock assertions:

- invalid material does not start the core or open a channel;
- first connect starts the core once and returns generation one;
- reconnect closes the old channel, increments/persists the generation, and
  fences the old session;
- a generation race closes the new channel and reports stale fencing;
- core/channel failures do not expose a session and perform cleanup;
- close is safe when repeated;
- a new coordinator restores the persisted generation and continues from it.

Host validation will run:

```sh
python3 apps/android/tools/test_transport_boundary.py
cd apps/android
./gradlew --no-daemon :transport:test :tailnet-core:test
./gradlew --no-daemon check
```

The real AAR, Android SDK/device, and adb gates remain explicitly unverified
until those external inputs are supplied.

## Files

Expected implementation changes are limited to:

- `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/PairedBridgeSessionCoordinator.kt`
- `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/TsnetPairedBridgeTransport.kt`
- `apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt`
- `apps/android/transport/build.gradle.kts` only if an explicit coroutine
  synchronization dependency is required by the implementation.

No dependency lock row, Bridge protocol schema, app manifest, or forbidden
surface rule is changed by this design.
