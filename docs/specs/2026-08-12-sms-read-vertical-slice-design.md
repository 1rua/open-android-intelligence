# SMS Read Vertical Slice Design

**Date:** 2026-08-12  
**Status:** Ready for written review

## Goal

Implement the first Android SMS capability vertical slice: after an explicit
local grant, open-android-intelligence can read received SMS text from the Android inbox on
demand or periodically auto-send newly observed messages to the paired Bridge.
The feature must remain typed, policy-revision fenced, encrypted while queued,
and unable to access MMS, attachments, sent messages, or generic network APIs.

## Scope

### In scope

- Read only received pure SMS rows from `Telephony.Sms.Inbox`.
- Preserve complete SMS body text, including an intentionally empty body.
- Expose sender, thread, receive time, read state, and optional SIM identity.
- Let the local user configure the history start time and maximum records per
  read/sync batch.
- Support `ON_DEMAND` and `AUTO_SEND` through the existing closed capability
  authorization model.
- Support automatic sync intervals of manual, 15, 30, or 60 minutes. Android
  may execute a scheduled job later than the requested interval.
- Use a stable `(messageAtEpochMs, recordId)` cursor for auto-send deduplication.
- Queue auto-send payloads in encrypted local storage and remove them only
  after the existing authenticated Bridge ACK path accepts them.
- Add the closed SMS wire contract and corresponding Hermes/OpenClaw adapter
  operation names required by the Android capability.
- Add unit, host-static, wire-contract, and Android build verification.

### Out of scope

- Reading sent SMS, drafts, MMS, RCS messages, attachments, or multimedia.
- Sending, deleting, marking, or modifying SMS.
- Becoming the default SMS application.
- Contact resolution beyond the raw provider sender address.
- Room migration for existing notification storage.
- A real Tailscale AAR, device certification, or model-provider changes.
- Generic HTTP, socket, VPN, route, proxy, shell, or command-execution APIs.

## Alternatives considered

### A. Dedicated `sms-collector` module (selected)

Add a focused Android library that implements `SmsCapabilityProvider` using an
injected `ContentResolver` adapter, a typed history policy, a cursor store, and
the Android scheduler. Keep capability authorization in `capability-ports` and
Bridge composition in the app/runtime layers.

This keeps Android provider details separate from future calls and contacts
adapters, permits JVM tests with a fake resolver, and preserves the current
no-VPN boundary.

### B. Implement SMS directly in `app`

This is initially shorter, but it would mix Android permission handling,
provider queries, settings persistence, Bridge composition, and lifecycle code
in the main APK. Future capabilities would repeat the same integration pattern.

### C. Mirror all SMS rows into Room first

This would simplify offline queries and historical cursors, but it creates a
larger sensitive-data retention surface before the first read path is proven.
The first slice therefore persists only encrypted outbox events and a cursor,
not SMS bodies or a searchable SMS database.

## Architecture

```text
Local SMS grant + READ_SMS permission + SmsHistoryPolicy
                         |
                         v
              SmsCapabilityProvider
                |             |
         ON_DEMAND       AUTO_SEND scheduler
                |             |
                v             v
          SmsPayload     encrypted capability outbox
                                  |
                                  v
                    ticket-bound PairedBridgeTransport
                                  |
                                  v
                      Bridge SMS wire contract + ACK
```

`capability-ports` remains the authorization boundary. The provider receives a
minted `AuthorizedReadScope` or `AuthorizedAutoSendScope`, never an arbitrary
`AgentDataRequest`. The provider does not know an endpoint, URL, IP address,
port, or socket operation.

The new `sms-collector` module owns:

- the Android `ContentResolver` query adapter;
- SMS row mapping and closed history filtering;
- cursor comparison and persistence through an injected cursor store;
- periodic `JobScheduler` integration;
- provider-level read and auto-send tests.

The app owns:

- the `READ_SMS` runtime permission request and status display;
- local SMS settings persistence and the settings UI;
- composition of the provider, authorization state, encrypted outbox, and
  paired transport.

The Bridge/protocol layers own:

- versioned snake-case SMS query, subscription, event, and response shapes;
- strict unknown-field rejection;
- plugin translation for Hermes and OpenClaw.

## Data model

`CapabilityFilter.Sms` remains a closed `data object`. Unlike the currently
withheld future-capability defaults, this reviewed SMS filter explicitly
authorizes the complete SMS body when the local SMS grant and current policy
revision are valid. No generic `include_content` map or wildcard field is
introduced.

The capability model is extended to carry the fields needed by an agent:

```kotlin
data class SmsMetadata(
    override val recordId: String,
    val senderAddress: String?,
    val threadId: String?,
    val messageAtEpochMs: Long,
    override val observedAtEpochMs: Long,
    val read: Boolean,
    val subscriptionId: Int?,
) : CapabilityMetadata

data class SmsHistoryPolicy(
    val fromEpochMs: Long?,
    val maxRecords: Int,
)

enum class SmsSyncInterval {
    MANUAL,
    MINUTES_15,
    MINUTES_30,
    MINUTES_60,
}
```

The implementation validates non-negative times, a positive `maxRecords`, and
an upper bound of 10,000 records per batch. A new local setting defaults to
deny; when the user first enables SMS, the UI starts with a 90-day start time,
500 records per batch, and a 30-minute interval, all of which the user may
change. The default values are local UX defaults, not protocol requirements.

`SmsPayload.content` is `NormalizedContent.Released<String>` for a valid SMS
scope. An empty provider body remains `Released("")`; it is never converted to
`Withheld`. Provider failures never produce a partially populated result.

## Android query rules

The provider queries only:

```text
Telephony.Sms.Inbox.CONTENT_URI
```

The closed projection is:

```text
_id, thread_id, address, body, date, read, sub_id
```

Rows are ordered by `date DESC, _id DESC`. `fromEpochMs`, when present, becomes
the lower-bound selection on `date`; `maxRecords` is the query limit. The
provider never queries `Telephony.Mms`, `content://mms`, sent, draft, or outbox
URIs. It does not resolve addresses through Contacts in this slice.

The stable `recordId` is derived from the provider `_id` with a closed `sms:`
prefix. The auto-send cursor compares the message date first and the numeric
provider ID second, so multiple messages with identical timestamps are neither
lost nor emitted twice.

`READ_SMS` is declared by the main APK and requested only by local UI code. A
remote request cannot request, grant, or bypass the Android permission.

## Local settings and authorization

The SMS settings surface exposes:

- local SMS capability grant;
- history start time, including an explicit “from earliest available” choice;
- maximum records per batch;
- manual/15/30/60-minute auto-sync interval;
- current Android SMS permission status;
- whether Agent-initiated requests are allowed;
- whether on-demand and auto-send modes are enabled.

Settings persistence is revisioned and fail-closed. Corrupt bytes preserve the
evidence and produce a disabled SMS capability rather than resetting to an
enabled state. Every remote query/subscription carries the policy revision and
must match the current local grant. A policy change invalidates stale access
objects and causes the scheduler to stop until a valid subscription is
re-established.

`ON_DEMAND` reads are direct provider reads after authorization. `AUTO_SEND`
requires both the local auto-send grant and an authorized subscription. A
subscription descriptor may be rehydrated after process restart only if its
stored policy revision still matches the current local grant; otherwise the
job exits without reading SMS data.

## Auto-sync lifecycle

The implementation uses Android `JobScheduler` for periodic execution. The
minimum supported interval is 15 minutes, so the four UI choices map to:

```text
MANUAL       no scheduled job
MINUTES_15   15-minute periodic job
MINUTES_30   30-minute periodic job
MINUTES_60   60-minute periodic job
```

The OS may defer a job. The slice does not assume the app is the default SMS
app and does not rely on `SMS_DELIVER` or `SMS_RECEIVED` broadcasts. An active
job queries the inbox with the policy window and cursor, normalizes each row,
and enqueues the canonical event before advancing the cursor. The cursor is
advanced only after the event is durably accepted by the encrypted outbox.

If the Bridge is unavailable, transport generation is stale, or ACK validation
fails, the encrypted event remains recoverable. Re-running the job uses the
same event identity and cursor range. Policy egress is checked immediately
before every send, including retries.

## Encrypted storage and Bridge wire boundary

The existing notification outbox is notification-specific and will not be
forced to accept arbitrary capability payloads. A small encrypted capability
outbox stores only:

- event ID;
- capability and record ID;
- policy revision;
- canonical SMS wire bytes encrypted with an Android Keystore-backed key;
- creation and retry metadata.

No SMS body is stored in ordinary preferences, logs, crash strings, or a
cleartext database. The cursor contains only the last acknowledged position.

The protocol adds a closed `mobile.sms` family with separate operations for
query, subscribe, and unsubscribe. The event schema contains only approved
SMS fields, uses decimal strings for 64-bit values, rejects unknown properties,
and excludes tenant, agent, model, session, or endpoint fields. The existing
ticket-bound Bridge session carries the bytes, and the existing authenticated
ACK contract controls removal from the outbox.

Hermes and OpenClaw adapters expose the same typed operations and map their
agent-facing tool calls to the versioned Bridge shapes. Neither adapter can
expand the local grant or invent a provider query.

## Error and failure behavior

| Condition | Result | Data behavior |
| --- | --- | --- |
| Missing local grant | `NO_LOCAL_GRANT` | No provider query |
| Missing `READ_SMS` | `PERMISSION_REQUIRED` | No provider query |
| Agent request disabled | `AGENT_REQUESTS_DISABLED` | No provider query |
| Stale policy revision | `POLICY_REVISION_STALE` | No provider query or send |
| Unsupported provider state | `CAPABILITY_UNAVAILABLE` | No data emitted |
| Query exception | `FAILED` | No partial result or cursor advance |
| Bridge unavailable | retry-pending | Encrypted event retained |
| Invalid ACK | retry-pending | Encrypted event retained |
| Local revoke | egress denied | Pending event retained, not sent |
| Corrupt local state | disabled/fail-closed | Evidence preserved |

## Verification strategy

Implementation follows TDD: each behavior starts with one focused failing test,
then the smallest production change, then focused and full verification.

Required tests:

1. `capability-ports`: SMS scope releases complete body text, including an
   empty body; stale, missing, or mismatched grants deny access; other future
   capability content remains withheld.
2. `sms-collector`: inbox-only URI and projection, row mapping, date/limit
   filtering, stable ordering, malformed row handling, and query failure.
3. `sms-collector`: cursor behavior for equal timestamps, duplicate rows,
   restart recovery, and durable-accept-before-advance ordering.
4. `sms-collector`: manual/15/30/60 interval mapping and permission/grant
   gating for the scheduled job.
5. `encrypted-store`: SMS body is recoverable only through encrypted outbox
   APIs; invalid ACK or missing pairing does not remove it.
6. `mvp-contract`: exact SMS wire keys, valid/invalid boundaries, unknown-field
   rejection, policy-revision binding, and query/subscribe lifecycle.
7. Hermes/OpenClaw adapter tests: both adapters expose the same SMS operation
   names and do not accept arbitrary capability strings.
8. Existing Android static no-VPN checks and all module tests continue to pass.

Verification commands after implementation:

```sh
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
npm test
cd apps/android
./gradlew --no-daemon check
```

If the locked Android SDK or native Tailscale AAR is unavailable, the report
must distinguish host/static and JVM results from unverified device/AAR gates.

## Expected files

The implementation plan will cover these focused changes:

- `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt`
- `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/...`
- `apps/android/sms-collector/build.gradle.kts`
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/...`
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/...`
- `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/...`
- `apps/android/app/src/main/AndroidManifest.xml`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/...`
- `mvp-contract/schemas/v1/sms-*.schema.json`
- `mvp-contract/src/wire-codec.ts`
- `mvp-contract/test/...`
- `integrations/hermes/...` and `integrations/openclaw/...`

No generic network surface, VPN surface, arbitrary command surface, or model
provider configuration is added.

## Follow-ups

- Calls and contacts use their own reviewed provider/data schemas.
- MMS/RCS, SMS sending, and contact-name enrichment require separate designs.
- Room-backed capability retention can be evaluated after the encrypted outbox
  and cursor behavior are proven.
- Real Android permission/device tests and native Tailscale integration remain
  dependent on the locked toolchain and AAR evidence.
