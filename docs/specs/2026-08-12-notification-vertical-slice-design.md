# Notification Vertical Slice Design

**Date:** 2026-08-12  
**Status:** Approved for implementation planning

## Goal

Complete the Android notification loop on top of the existing policy engine,
collector, encrypted outbox, Bridge dispatcher, and paired transport seams.
The loop must support local notification settings, on-demand Agent reads, and
automatic post-capture delivery without exposing a generic network surface or
allowing the Agent to change local consent.

## Scope

This slice includes:

- A local Android notification settings surface.
- Per-device notification collection policy: allowlist/denylist, package IDs,
  metadata/content access, and local grant state.
- A local delivery mode:
  - `ON_DEMAND`: keep policy-approved active notifications available for an
    Agent query, but do not enqueue each callback for automatic delivery.
  - `AUTO_SEND`: enqueue each policy-approved notification change in the
    existing encrypted outbox for Bridge delivery.
- A typed Android Agent query boundary with policy-revision validation,
  bounded limits, optional package/field filtering, and idempotent operation
  handling.
- Tests for settings persistence, collector behavior, query authorization,
  auto-send behavior, and regression of the current encrypted dispatch flow.

This slice does not include SMS, calls, contacts, Room migration, a real
Tailscale AAR, or a new generic HTTP/socket API. The raw authenticated control
wire remains behind the existing transport boundary; the notification feature
uses a typed adapter seam that can be bound to the protocol session without
letting feature code mint endpoints or sockets.

## Alternatives Considered

### Incremental extension of existing modules (selected)

Extend `core-model`, `policy-engine`, `notification-collector`, and the app
composition using the existing interfaces. Add only focused types for local
delivery mode and Agent queries, keep the UI local-only, and reuse the current
outbox and dispatcher.

This minimizes duplicated authorization logic and preserves the existing
fail-closed and no-VPN guarantees. It also keeps the implementation testable
without requiring the unavailable native Tailscale dependency.

### New notification service module

Move settings, query handling, wire conversion, and delivery orchestration into
a new Android service module. This would create a stronger standalone boundary,
but would duplicate the policy/runtime seams already present and make the first
vertical slice substantially larger.

### Settings-only implementation

Add the local settings page and persistence first, leaving Agent reads and
automatic delivery for a later milestone. This would provide visible UI sooner,
but would not complete the requested notification loop or validate the most
important authorization behavior.

## Architecture

### Local policy and delivery state

`PersistentNotificationPolicyAuthority` remains the only mutation authority for
notification consent. Its local controller is used by the Android settings
surface; remote code receives only a read-only authorization view.

The delivery mode is device-local state. It must be persisted with the same
fail-closed semantics as the notification policy, but it is not added to the
versioned Bridge notification policy payload because the Bridge contract already
models on-demand queries and auto-send subscriptions separately.

The persisted state must retain monotonic policy/authorization revisions. A
write is committed to app-private no-backup storage before the new state is
published to the collector/runtime. Corrupt state denies collection and egress.

### Collector behavior

`AndroidNotificationCollector` continues to perform package matching and field
redaction before storing a notification in its active map or emitting a record.
The collector always supports an on-demand read of currently active,
policy-approved notifications.

`NotificationRuntime` becomes delivery-mode aware:

- In `ON_DEMAND`, it keeps the collector and policy listener active but does not
  persist callback captures into the outbox.
- In `AUTO_SEND`, it retains the current enqueue-and-dispatch flow.
- A policy update is applied before any subsequent capture, and a revoked
  record cannot pass the egress gate.

The existing encrypted outbox, authenticated ACK verification, retry limit, and
Bridge binding checks remain unchanged except where tests require the runtime to
skip automatic enqueueing in on-demand mode.

### Agent query boundary

The Android side gets a small typed gateway with requests equivalent to the
existing notification contract:

- operation ID
- policy revision
- limit from 1 through 100
- optional sorted package filter
- optional metadata/content field request

The gateway validates the request against the current local authority, rejects
stale revisions and unauthorized content, and delegates the actual read to
`NotificationCollector.captureOnDemand`. It returns the existing capture status
model (`complete`, `waiting_device`, or `failed`) rather than exposing internal
exceptions or storage details.

Operation IDs are bound to the request identity and retained for the lifetime
of the gateway instance. A retry with identical identity and parameters returns
the original result without recapturing; reuse with different parameters is
rejected. This mirrors the Bridge operation semantics already covered by the
TypeScript contract tests.

The gateway is transport-neutral. A later authenticated control-session adapter
can decode the versioned wire payload and invoke it, while the feature itself
does not receive endpoint, URL, socket, or arbitrary command inputs.

### Settings surface

The existing main activity becomes the local notification settings entry point
for this slice. It uses platform Android views rather than adding a new UI
dependency to the provisional Android toolchain.

The surface exposes:

- Notification-listener access status and a link to Android system settings.
- Local collection grant toggle.
- `ON_DEMAND` / `AUTO_SEND` selection.
- Metadata/content selection.
- Allowlist/denylist selection.
- Installed application package list with selection state.
- Current policy revision and a safe error state when local evidence is
  corrupted.

Saving normalizes package IDs into Unicode-code-point order and advances the
appropriate local revision. The Agent cannot invoke the settings mutation
methods.

## Authorization and Failure Rules

- Fresh install is deny-first: no grant, empty allowlist, metadata, on-demand.
- A package denied by local matching is dropped before active storage, outbox,
  or query results.
- Metadata mode never retains title/body in the active map or durable outbox.
- Content mode still requires the local grant and current policy revision.
- Stale policy revisions fail before capture or egress.
- Local revocation prevents new records from being emitted and causes pending
  records to remain subject to the existing egress gate.
- Missing pairing keeps accepted outbox events recoverable; it never clears them
  or silently reports successful delivery.
- Invalid query limits, malformed filters, duplicate operation identities, and
  corrupted persisted state fail closed.
- No remote request changes notification consent, delivery mode, package
  selection, or Android system notification-listener permission.

## Verification Strategy

The implementation will use TDD for each behavior: write one focused failing
test, run it to observe the intended failure, add the smallest implementation,
then run the focused and module test suites.

Required behavior coverage:

1. Default local state denies capture and outbox writes.
2. Settings persistence restores mode, grant, field access, and sorted package
   selection; corrupted bytes deny access.
3. Allowlist and denylist decisions are deterministic and revision fenced.
4. Metadata collection strips title/body before retention; content collection
   preserves them only when authorized.
5. On-demand mode does not enqueue callback captures.
6. Auto-send mode enqueues and dispatches through the existing encrypted ACK
   path.
7. Query requests validate revision, limit, package filter, field access, and
   operation identity; duplicate identical operations do not recapture.
8. Revocation and pairing loss preserve fail-closed/retry semantics.
9. Existing Android source boundary, no-VPN scan, notification tests, encrypted
   store tests, and transport tests continue to pass.

The SDK-free static checks remain runnable from the repository root. If the
locked Android SDK is available, `cd apps/android && ./gradlew --no-daemon
check` is the build-level verification command.

## Non-Goals and Follow-Ups

- Bind the typed gateway to the complete signed protocol envelope.
- Replace the provisional file-backed policy/outbox adapters with Room.
- Build or vendor the Tailscale gomobile AAR.
- Add notification history beyond the current active set and outbox contract.
- Add SMS, calls, contacts, accessibility, clipboard, location, health, or
  sensor capabilities.
