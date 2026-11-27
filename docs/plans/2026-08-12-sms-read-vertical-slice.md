# SMS Read Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a permission-gated Android capability that reads received pure SMS with complete body text, supports user-configured history and auto-sync, and delivers authorized data through an encrypted outbox and paired Bridge to Hermes/OpenClaw.

**Architecture:** Keep `capability-ports` as the closed authorization boundary and add a dedicated `sms-collector` module for `Telephony.Sms.Inbox`, history policy, cursor, scheduler, provider, and auto-sync orchestration. Add a capability-specific encrypted outbox and canonical SMS wire codec, then compose the provider in the main APK and expose matching Bridge/plugin contracts without adding any endpoint, socket, VPN, or shell surface.

**Tech Stack:** Kotlin 2.1.20, Android API 35/min API 34, AGP 8.9.2, Gradle 8.12, JDK 17, Android `ContentResolver`/`JobScheduler`/runtime permissions, Android Keystore AES-GCM, TypeScript 7, Vitest 4, JSON Schema 2020-12.

## Global Constraints

- Read only `Telephony.Sms.Inbox.CONTENT_URI`; never query MMS, RCS, sent, draft, outbox, or attachment providers.
- Preserve the complete SMS body; provider `null` body is normalized to `Released("")`, while no unauthorized scope may release body text.
- The local user controls grant, `agentMayRequest`, on-demand, auto-send, history start time, maximum records, and sync interval; an Agent request never changes them.
- `SmsHistoryPolicy.fromEpochMs` is nullable and `maxRecords` is `1..10_000`; local first-enable defaults are 90 days, 500 records, and 30 minutes but remain editable.
- Auto-sync choices are exactly `MANUAL`, `MINUTES_15`, `MINUTES_30`, and `MINUTES_60`; the Android scheduler may run later than the requested interval.
- Auto-send uses a stable `(messageAtEpochMs, providerId)` cursor, deterministic event identity, encrypted queueing, and cursor advancement only after durable enqueue.
- SMS body bytes must never be written to ordinary preferences, logs, or cleartext database storage.
- Every remote read/subscription is policy-revision fenced and must pass `READ_SMS`, local grant, mode, and `agentMayRequest` checks.
- The Android feature receives only typed binding/session seams; it cannot accept an endpoint, URL, IP, port, generic socket, VPN, proxy, shell, or arbitrary command.
- New behavior follows TDD: write one focused failing test, run it and observe the intended failure, implement the smallest change, rerun focused tests, then run the module suite.
- Existing user work, including the untracked `apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettingsTest.kt`, is outside this plan and must not be staged or modified accidentally.
- A real Tailscale AAR, physical device, and locked external releases are not fabricated; final reporting separates host/JVM evidence from unavailable device/AAR evidence.

## File Map

- `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt` — complete SMS metadata and body-release semantics.
- `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/SmsCapabilityContracts.kt` — SMS history policy, interval, cursor-independent local policy value types.
- `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/CapabilityProviderContractsTest.kt` — SMS authorization and normalization tests.
- `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/SmsCapabilityContractsTest.kt` — policy and interval validation tests.
- `apps/android/sms-collector/build.gradle.kts` — new Android library dependencies.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsInboxReader.kt` — platform-neutral row/query seam.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/AndroidSmsInboxReader.kt` — `ContentResolver` adapter limited to the inbox URI.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsCursor.kt` — monotonic `(date,id)` cursor and persistence seam.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSettingsAuthority.kt` — fail-closed local settings persistence and controller.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/AndroidSmsCapabilityProvider.kt` — typed on-demand and finite auto-send batch provider.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinator.kt` — durable enqueue, cursor advancement, and dispatch orchestration.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncScheduler.kt` — interval mapping and `JobScheduler` adapter.
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsWireCodec.kt` — deterministic Kotlin SMS event bytes for the Android queue.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/AndroidSmsCapabilityProviderTest.kt` — provider mapping and failure tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/AndroidSmsInboxReaderTest.kt` — inbox URI, projection, selection, and limit tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsCursorTest.kt` — cursor ordering and restart tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSettingsAuthorityTest.kt` — local settings persistence and fail-closed tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinatorTest.kt` — durable enqueue, cursor, egress, and retry tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncSchedulerTest.kt` — interval mapping tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncJobServiceTest.kt` — permission and job result tests.
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsWireCodecTest.kt` — deterministic JSON byte tests.
- `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContracts.kt` — generic encrypted capability event contract.
- `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStore.kt` — AES-GCM capability outbox implementation.
- `apps/android/encrypted-store/src/test/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStoreTest.kt` — ciphertext, restart, ACK, and idempotency tests.
- `apps/android/settings.gradle.kts` and `apps/android/app/build.gradle.kts` — register and consume `:sms-collector`.
- `apps/android/app/src/main/AndroidManifest.xml` — declare `READ_SMS` and the non-exported sync job service.
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt` — compose SMS settings, provider, outbox, scheduler, and paired transport.
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt` — local SMS settings controls and permission request only.
- `mvp-contract/schemas/v1/sms-record.schema.json` and `mvp-contract/schemas/v1/sms-api.schema.json` — closed SMS records and operations.
- `mvp-contract/src/wire-codec.ts` and `mvp-contract/test/sms-contract.test.ts` — TypeScript SMS encode/validate boundary.
- `bridge-contract/src/sms-store.ts`, `bridge-contract/src/sms-subscription-store.ts`, and `bridge-contract/src/sms-service.ts` — Bridge query/subscription/ACK contract.
- `bridge-contract/src/service-types.ts`, `bridge-contract/src/index.ts`, and `bridge-contract/test/sms-service.test.ts` — shared types, exports, and contract tests.
- `integrations/shared/adapter.ts`, `integrations/shared/sms-contract.test.ts`, `integrations/hermes/adapter.ts`, `integrations/openclaw/adapter.ts`, and their tests — normalized agent operations.
- `integrations/hermes/plugin-manifest.json`, `integrations/openclaw/plugin-manifest.json`, and `integrations/skills/android-device-bridge/SKILL.md` — advertise the closed SMS capability.
- `docs/mvp/mvp-vertical-slice-contract.md` and `apps/android/README.md` — document the implemented SMS slice and truthful verification limits.

---

### Task 1: Close the Android SMS capability contract

**Files:**
- Create: `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/SmsCapabilityContracts.kt`
- Modify: `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt`
- Create: `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/CapabilityProviderContractsTest.kt`
- Create: `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/SmsCapabilityContractsTest.kt`

**Interfaces:**
- Consumes: `CapabilityFilter.Sms`, `AuthorizedReadScope`, `AuthorizedAutoSendScope`, `CapabilityPayload`, and existing provider interfaces.
- Produces: `SmsMetadata(recordId, senderAddress, threadId, messageAtEpochMs, observedAtEpochMs, read, subscriptionId)`, `SmsHistoryPolicy(fromEpochMs, maxRecords)`, `SmsSyncInterval`, `MAX_SMS_BATCH_RECORDS`, and SMS-specific authorization normalization used by Tasks 2–5.

- [ ] **Step 1: Write the failing contract tests.**

  Add tests named `sms_scope_releases_complete_body_including_empty_body`, `non_sms_future_scope_still_withholds_content`, `sms_history_policy_rejects_negative_start_zero_limit_and_over_limit`, `sms_interval_exposes_only_manual_15_30_and_60_minutes`, and `sms_metadata_rejects_negative_message_or_observed_time`.

  The first test must build an internally authorized `CapabilityFilter.Sms` request, call `requireReadScope(MobileDataCapability.SMS)`, normalize both `"full body"` and `""`, and assert `NormalizedContent.Released` for both. The second must use a `CapabilityFilter.Calls` scope and assert `NormalizedContent.Withheld` for the same raw string. Use literal expected values; do not compute them with production helpers.

- [ ] **Step 2: Run the focused tests to verify RED.**

  Run:

  ```sh
  cd apps/android
  ./gradlew --no-daemon :capability-ports:test --tests 'com.openandroidintelligence.capability.CapabilityProviderContractsTest' --tests 'com.openandroidintelligence.capability.SmsCapabilityContractsTest'
  ```

  Expected: compilation/test failure because the SMS metadata fields, policy type, interval type, and SMS disclosure rule do not yet exist.

- [ ] **Step 3: Implement the minimum contract.**

  Move the SMS-specific metadata declaration into `SmsCapabilityContracts.kt` or replace the existing two-field declaration in place. Validate `recordId` and `observedAtEpochMs` through the existing metadata invariant, require `messageAtEpochMs >= 0`, allow nullable sender/thread/subscription values, and require `maxRecords in 1..10_000` and `fromEpochMs == null || fromEpochMs >= 0`.

  Add `SmsSyncInterval.periodMs` with `null` for `MANUAL`, `15*60*1000L`, `30*60*1000L`, and `60*60*1000L` for the three periodic choices. Change `CapabilityFilter.allowsContentDisclosure()` to return true for `CapabilityFilter.Sms` and for notification content access only. Keep all other future capabilities withheld.

- [ ] **Step 4: Run the focused and module tests to verify GREEN.**

  Run:

  ```sh
  ./gradlew --no-daemon :capability-ports:test
  ```

  Expected: all capability-port tests pass, including the existing static contract assumptions.

- [ ] **Step 5: Commit the contract slice.**

  ```sh
  git add apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/SmsCapabilityContracts.kt apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/CapabilityProviderContractsTest.kt apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/SmsCapabilityContractsTest.kt
  git commit -m "feat(android): close SMS capability contract"
  ```

### Task 2: Add the SMS inbox query adapter and typed provider

**Files:**
- Create: `apps/android/sms-collector/build.gradle.kts`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsInboxReader.kt`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/AndroidSmsInboxReader.kt`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsCursor.kt`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/AndroidSmsCapabilityProvider.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/AndroidSmsCapabilityProviderTest.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/AndroidSmsInboxReaderTest.kt`
- Modify: `apps/android/settings.gradle.kts`

**Interfaces:**
- Consumes: `SmsCapabilityProvider`, `SmsHistoryPolicy`, `AuthorizedReadScope`, `AuthorizedAutoSendScope`, and the Android `ContentResolver`.
- Produces: `SmsCursor`, `SmsHistoryPolicySource`, `SmsCursorSource`, `SmsInboxRow`, `SmsInboxQuery`, `SmsInboxReader`, `AndroidSmsInboxReader`, and `AndroidSmsCapabilityProvider`.

  The provider exposes `read(scope): CapabilityReadResult<SmsPayload>` and `observeAutoSend(scope): Flow<CapabilityEvent<SmsPayload>>`. Each auto-send collection is one bounded batch; the coordinator in Task 4 starts a new collection for each scheduled job.

- [ ] **Step 1: Write failing provider and adapter tests.**

  `AndroidSmsInboxReaderTest` must use a recording resolver/reader seam and assert that the query uses the exact projection `[_id, thread_id, address, body, date, read, sub_id]`, the inbox URI, a lower-bound date selection when configured, and a bounded limit. `AndroidSmsCapabilityProviderTest` must cover complete body mapping, `null` body to `Released("")`, sender/thread/read/subscription mapping, descending history results, equal-date ID ordering, and a thrown reader error becoming `CapabilityReadStatus.FAILED` with no records.

  Add a test that `observeAutoSend` emits deterministic `eventId == "sms:42"` for provider ID 42 and does not emit a sent/MMS row because the reader contract only returns inbox rows.

- [ ] **Step 2: Run the new module tests to verify RED.**

  Run:

  ```sh
  ./gradlew --no-daemon :sms-collector:test
  ```

  Expected: project/module-not-found or unresolved-symbol failures because the module and provider types do not exist.

- [ ] **Step 3: Register the module and implement the platform-neutral row seam.**

  Add `:sms-collector` to `settings.gradle.kts`; make the module an Android library depending on `:capability-ports`, `:core-model`, `kotlinx-coroutines-core:1.9.0`, and JUnit for tests.

  Define:

  ```kotlin
  data class SmsInboxRow(
      val providerId: Long,
      val threadId: String?,
      val address: String?,
      val body: String?,
      val messageAtEpochMs: Long,
      val read: Boolean,
      val subscriptionId: Int?,
  )

  data class SmsCursor(val providerId: Long, val messageAtEpochMs: Long)

  fun interface SmsHistoryPolicySource {
      fun current(): SmsHistoryPolicy
  }

  fun interface SmsCursorSource {
      fun current(): SmsCursor?
  }

  data class SmsInboxQuery(val history: SmsHistoryPolicy, val cursor: SmsCursor? = null)

  fun interface SmsInboxReader {
      fun query(request: SmsInboxQuery): List<SmsInboxRow>
  }
  ```

  `AndroidSmsInboxReader` must call only `Telephony.Sms.Inbox.CONTENT_URI`, use the seven-column projection, use `date >= ?` for the configured history start, and use `(date > ? OR (date = ? AND _id > ?))` for a cursor. Interpolate only the validated integer `maxRecords` into a `LIMIT` clause; bind all dates and cursor values as selection arguments. Read rows with `getColumnIndexOrThrow`, close the returned cursor with `use`, and map `sub_id == null` to `null`.

- [ ] **Step 4: Implement provider normalization.**

  `AndroidSmsCapabilityProvider` accepts an `SmsInboxReader`, a `SmsHistoryPolicySource`, a `SmsCursorSource`, and a clock. It reads the local policy at collection time, queries the inbox, maps provider ID to `recordId = "sms:$providerId"`, maps `body ?: ""`, creates `SmsMetadata`, and returns `SmsPayload(metadata, NormalizedContent.Released(body))` only through the checked SMS scope. It must sort on `(messageAtEpochMs, providerId)` for auto-send emission so cursor advancement is monotonic even though history queries arrive newest-first.

  `read(scope)` returns `COMPLETE` with at most `history.maxRecords` records. `observeAutoSend(scope)` reads strictly after the stored cursor, returns a finite flow of `CapabilityEvent<SmsPayload>`, and never writes the cursor itself. Query exceptions return `FAILED` with reason `SMS_QUERY_FAILED` and an empty record list.

- [ ] **Step 5: Run the focused and module tests to verify GREEN.**

  ```sh
  ./gradlew --no-daemon :sms-collector:test
  ```

  Expected: all provider and adapter tests pass; the test output must show no query outside the inbox seam.

- [ ] **Step 6: Commit the query/provider slice.**

  ```sh
  git add apps/android/settings.gradle.kts apps/android/sms-collector
  git commit -m "feat(android): add received SMS inbox provider"
  ```

### Task 3: Persist SMS settings and the monotonic cursor

**Files:**
- Modify: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsCursor.kt`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSettingsAuthority.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsCursorTest.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSettingsAuthorityTest.kt`

**Interfaces:**
- Consumes: `SmsHistoryPolicy`, `SmsSyncInterval`, `SmsCursor`, `SmsHistoryPolicySource`, `SmsCursorSource`, `CapabilityGrant`, and app-private byte persistence.
- Produces: `SmsCursor(providerId, messageAtEpochMs)`, `SmsCursorStore`, `InMemorySmsCursorStore`, `FileSmsCursorStore`, `SmsSettingsSnapshot`, `PersistentSmsSettingsAuthority`, and `LocalSmsSettingsController`.

- [ ] **Step 1: Write failing persistence tests.**

  `SmsCursorTest` must assert that a newer date advances, a same-date higher provider ID advances, an older/equal position is rejected or ignored without changing persisted state, and a restarted store returns the exact cursor. `SmsSettingsAuthorityTest` must assert fresh install defaults to `granted=false`, all modes false, `agentMayRequest=false`, and `corrupted=false`; a local update restores after restart; revisions are monotonic; same-revision mutation fails; and malformed bytes set `corrupted=true` while leaving the bytes untouched and denying the grant.

  Add a test for the first-enable UX defaults: `fromEpochMs = now - 90.days`, `maxRecords = 500`, and `interval = MINUTES_30`; this test must call a pure default factory with a literal clock value.

- [ ] **Step 2: Run the focused tests to verify RED.**

  ```sh
  ./gradlew --no-daemon :sms-collector:test --tests 'com.openandroidintelligence.sms.SmsCursorTest' --tests 'com.openandroidintelligence.sms.SmsSettingsAuthorityTest'
  ```

  Expected: unresolved-symbol or missing-behavior failures.

- [ ] **Step 3: Implement cursor value and persistence.**

  Complete the `SmsCursor` value declared in Task 2 with non-negative `providerId` and `messageAtEpochMs`. Define `compareTo` by message time, then provider ID. `SmsCursorStore.advance` must persist only if the new cursor is strictly greater than the current cursor. The file adapter must use an app-private no-backup child and atomic temporary-file rename. Encode a magic string, two signed longs, and reject trailing bytes, negative values, and malformed lengths. Make `PersistentSmsSettingsAuthority` implement `SmsHistoryPolicySource` so the provider always reads the current local policy.

- [ ] **Step 4: Implement fail-closed settings authority.**

  `SmsSettingsSnapshot` must contain `historyPolicy`, `syncInterval`, `granted`, `onDemandEnabled`, `autoSendEnabled`, `agentMayRequest`, `policyRevision`, `authorizationRevision`, and `corrupted`. `PersistentSmsSettingsAuthority` must expose read-only `snapshot()`, `capabilityGrant()` returning `CapabilityGrant(MobileDataCapability.SMS, CapabilityFilter.Sms, ...)` or `null` when corrupted, and a local controller only constructible by the app composition.

  `LocalSmsSettingsController.update` must validate the policy, advance both revisions, write bytes before publishing memory, and notify listeners. `revoke` must set `granted=false`, disable both modes and agent requests, and advance the authorization revision. Corruption must never be overwritten by an update.

- [ ] **Step 5: Run the focused and module tests to verify GREEN.**

  ```sh
  ./gradlew --no-daemon :sms-collector:test
  ```

- [ ] **Step 6: Commit settings/cursor persistence.**

  ```sh
  git add apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsCursor.kt apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSettingsAuthority.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsCursorTest.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSettingsAuthorityTest.kt
  git commit -m "feat(android): persist SMS policy and cursor"
  ```

### Task 4: Add the encrypted capability outbox and auto-send coordinator

**Files:**
- Create: `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContracts.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStore.kt`
- Create: `apps/android/encrypted-store/src/test/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStoreTest.kt`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinator.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinatorTest.kt`
- Modify: `apps/android/encrypted-store/build.gradle.kts`
- Modify: `apps/android/sms-collector/build.gradle.kts`

**Interfaces:**
- Consumes: `PairedBridgeTransport`, `VerifiedPairingTransportBinding`, `BridgeSession`, `AuthorizedAutoSendSubscription`, `SmsCursorStore`, `SmsCapabilityProvider`, and `SmsEventEncoder`.
- Produces:

  ```kotlin
  data class CapabilityDurableEvent(
      val eventId: String,
      val capability: String,
      val recordId: String,
      val policyRevision: ULong,
      val eventWire: ByteArray,
  )

  interface CapabilityOutbox {
      suspend fun enqueueAccepted(event: CapabilityDurableEvent): CapabilityDurableEvent
      suspend fun acknowledge(eventId: String, eventAckWire: ByteArray)
      suspend fun recoverUnacknowledged(): List<CapabilityDurableEvent>
  }

  fun interface SmsEventEncoder {
      fun encode(eventId: String, record: SmsPayload, policyRevision: ULong): ByteArray
  }
  ```

  `SmsAutoSyncCoordinator.runOnce(subscription): SmsSyncRunResult` is the task-level entry point. It returns counts for captured, enqueued, acknowledged, retained, and failure status without exposing SMS body text in diagnostics.

- [ ] **Step 1: Write failing outbox tests.**

  Assert `CapabilityOutboxStore` encrypts the complete event wire (plaintext body absent from persistence bytes), restores it after restart, retains it after invalid ACK, removes it after a verifier-approved ACK, rejects event identity conflicts, and rejects capacity overflow without silently dropping an older SMS. Add a test that `eventWire` and returned byte arrays are defensive copies.

- [ ] **Step 2: Run the focused outbox tests to verify RED.**

  ```sh
  cd apps/android
  ./gradlew --no-daemon :encrypted-store:test --tests 'com.openandroidintelligence.encrypted.store.CapabilityOutboxStoreTest'
  ```

- [ ] **Step 3: Implement the generic encrypted capability outbox.**

  Use the existing `EncryptedOutboxPersistence` and AES-GCM pattern, but use a separate magic/version (`OPEN_ANDROID_INTELLIGENCE_CAPABILITY_OUTBOX_V1`) and serialize only event ID, capability, record ID, policy revision, and wire bytes inside the encrypted envelope. Use deterministic event IDs supplied by the caller, make enqueue idempotent for byte-identical existing IDs, and throw `CapabilityOutboxConflict` for a same ID with different metadata or bytes. Keep a bounded capacity of 10,000 and throw `CapabilityOutboxFull` instead of evicting SMS events.

- [ ] **Step 4: Write the failing auto-sync ordering test.**

  Configure three rows, including two with the same timestamp, a cursor at the first row, a fake outbox, and a fake transport. Assert the coordinator emits `sms:2` then `sms:3`, enqueues before advancing the cursor, advances through equal timestamps, and leaves the event retained when transport or ACK verification fails. Assert a policy revoke between enqueue and send blocks egress.

- [ ] **Step 5: Implement the coordinator and bound dispatcher.**

  `SmsAutoSyncCoordinator` must obtain a checked auto-send scope, collect the finite provider flow, encode each event, enqueue it with deterministic ID `sms:<numericProviderId>`, then advance the cursor only after enqueue returns. It must call a local capability dispatcher that opens only `PairedBridgeTransport` with the current verified binding, sends the recovered `eventWire`, receives the ACK, and delegates ACK validation/removal to `CapabilityOutbox`. Missing binding, transport failure, invalid ACK, or cancellation must retain the event; policy egress must be checked before every send and retry. Do not add endpoint parameters or generic network types.

- [ ] **Step 6: Run the focused and module tests to verify GREEN.**

  ```sh
  ./gradlew --no-daemon :encrypted-store:test :sms-collector:test
  ```

- [ ] **Step 7: Commit the encrypted auto-send slice.**

  ```sh
  git add apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContracts.kt apps/android/encrypted-store apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinator.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinatorTest.kt
  git commit -m "feat(android): add encrypted SMS auto-send outbox"
  ```

### Task 5: Add deterministic SMS wire encoding and the protocol schemas

**Files:**
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsWireCodec.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsWireCodecTest.kt`
- Create: `mvp-contract/schemas/v1/sms-record.schema.json`
- Create: `mvp-contract/schemas/v1/sms-api.schema.json`
- Modify: `mvp-contract/src/wire-codec.ts`
- Create: `mvp-contract/test/sms-contract.test.ts`
- Modify: `mvp-contract/test/mvp-contract.test.ts`

**Interfaces:**
- Consumes: `SmsPayload`, `SmsMetadata`, `CapabilityDurableEvent`, `SmsCursor`, and decimal-u64 wire conventions already used by notifications.
- Produces: Kotlin `SmsEventEncoder`, TypeScript `WireSmsRecord`, `encodeSmsRecord`, `validateWireSmsRecord`, `encodeSmsQuery`, `encodeSmsSubscribe`, `encodeSmsUnsubscribe`, and `validateWireSmsOperation` branches for the SMS family.

- [ ] **Step 1: Write failing wire tests in both languages.**

  Kotlin must assert exact UTF-8 bytes for a record containing a Unicode sender, empty body, nullable thread/SIM, decimal times, and policy revision; changing any field or JSON key must fail. TypeScript must assert exact closed keys, round-trip validation, decimal-u64 boundaries, rejection of unknown identity fields, rejection of negative/unsafe numbers, and operation policy-revision binding.

  Define the closed wire record as:

  ```json
  {
    "kind": "upsert",
    "record_id": "sms:42",
    "source_epoch": "1",
    "record_revision": "1",
    "cursor_message_at_epoch_ms": "1700000000000",
    "cursor_provider_id": "42",
    "captured_at_epoch_ms": "1700000000100",
    "capture_revision": "7",
    "policy_revision": "7",
    "metadata": {
      "sender_address": "+8613800000000",
      "thread_id": "9",
      "message_at_epoch_ms": "1700000000000",
      "observed_at_epoch_ms": "1700000000100",
      "read": false,
      "subscription_id": 1
    },
    "content": { "body": "" }
  }
  ```

  `mobile.sms.query` accepts `operation`, `operation_id`, `policy_revision`, and `limit` (`1..10_000`); `mobile.sms.subscribe` accepts `operation`, `subscription_id`, and `policy_revision`; `mobile.sms.unsubscribe` accepts `operation`, `subscription_id`, and `policy_revision`.

- [ ] **Step 2: Run the focused wire tests to verify RED.**

  ```sh
  ./tools/run-node24 npm test -- mvp-contract/test/sms-contract.test.ts
  cd apps/android
  ./gradlew --no-daemon :sms-collector:test --tests 'com.openandroidintelligence.sms.SmsWireCodecTest'
  ```

  Expected: missing schema/encoder/validator failures.

- [ ] **Step 3: Implement the closed schemas and validators.**

  Use `additionalProperties: false`/`unevaluatedProperties: false`, decimal string patterns for all 64-bit values, integer bounds for `subscription_id`, and required `metadata`/`content` objects. Reject `agent_principal_id`, `session_id`, `tenant_id`, `workspace_id`, `job_id`, endpoint fields, MMS fields, and arbitrary capability names. Add SMS schema names to the existing schema inventory assertion.

  Implement Kotlin encoding with fixed key order and a dedicated JSON string escaper for quotes, backslashes, control characters, and Unicode. Do not use map iteration order. Implement TypeScript encoding/validation by following the existing notification codec style and return frozen objects.

- [ ] **Step 4: Run all focused wire tests and type checks to verify GREEN.**

  ```sh
  ./tools/run-node24 npm test -- mvp-contract/test/sms-contract.test.ts mvp-contract/test/mvp-contract.test.ts
  ./tools/run-node24 npm run typecheck
  cd apps/android
  ./gradlew --no-daemon :sms-collector:test
  ```

- [ ] **Step 5: Commit the wire slice.**

  ```sh
  git add apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsWireCodec.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsWireCodecTest.kt mvp-contract/schemas/v1/sms-record.schema.json mvp-contract/schemas/v1/sms-api.schema.json mvp-contract/src/wire-codec.ts mvp-contract/test/sms-contract.test.ts mvp-contract/test/mvp-contract.test.ts
  git commit -m "feat(protocol): add closed SMS wire contract"
  ```

### Task 6: Compose the Android permission, settings UI, and scheduler

**Files:**
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncScheduler.kt`
- Create: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncJobService.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncSchedulerTest.kt`
- Create: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncJobServiceTest.kt`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt`
- Create: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SmsSettingsPresenterTest.kt`

**Interfaces:**
- Consumes: `PersistentSmsSettingsAuthority`, `LocalSmsSettingsController`, `AndroidSmsCapabilityProvider`, `SmsAutoSyncCoordinator`, `CapabilityAvailability`, Android runtime permission APIs, and `JobScheduler`.
- Produces: `SmsRuntime`, `SmsRuntimeFactoryRegistry`, `SmsSyncScheduler`, non-exported `SmsSyncJobService`, and a local-only settings presenter/view state.

- [ ] **Step 1: Write failing scheduler and presenter tests.**

  Assert `MANUAL` cancels/no-ops a scheduled job, the three periodic choices use exactly 15/30/60 minutes in milliseconds, and a disabled/corrupted/missing-permission state does not run a provider query. Assert the presenter exposes grant, permission status, start-time mode, max records, interval, on-demand, auto-send, and Agent-request controls, and that a remote request has no route to the mutation controller.

- [ ] **Step 2: Run focused tests to verify RED.**

  ```sh
  cd apps/android
  ./gradlew --no-daemon :sms-collector:test :app:test --tests 'com.openandroidintelligence.sms.SmsSyncSchedulerTest' --tests 'com.openandroidintelligence.mobile.SmsSettingsPresenterTest'
  ```

- [ ] **Step 3: Implement scheduler seams and job service.**

  Define a platform-neutral `SmsJobScheduler` interface with `schedule(interval)` and `cancel()`. `AndroidSmsSyncScheduler` must use one fixed job ID, `setPeriodic(periodMs)`, and `setPersisted(true)` only for periodic intervals; `MANUAL` calls `cancel(jobId)`. `SmsSyncJobService` obtains the installed runtime, checks `READ_SMS` and the current settings snapshot, constructs an authorized auto-send subscription from local state, runs one coordinator batch, calls `jobFinished(params, needsReschedule = result.retryPending)`, and returns `START_NOT_STICKY` behavior through the JobService contract. The service must be non-exported and have no implicit broadcast receiver.

- [ ] **Step 4: Implement app composition and permission-gated local UI.**

  Add `<uses-permission android:name="android.permission.READ_SMS" />` and register the job service with `android:exported="false"`. Do not add `RECEIVE_SMS`, `SEND_SMS`, MMS, default-SMS-app, VPN, or listener permissions.

  Add `testImplementation("junit:junit:4.13.2")` to the app module so the pure presenter test can run without introducing a UI test framework.

  In `OpenAndroidIntelligenceApplication`, create app-private files `sms-settings-v1.bin`, `sms-cursor-v1.bin`, and `sms-outbox-v1.aesgcm`; create an Android Keystore AES key with a distinct SMS capability alias; compose the inbox reader, settings authority, cursor store, `AndroidSmsCapabilityProvider`, capability outbox, `SmsAutoSyncCoordinator`, and scheduler. Register them through `SmsRuntimeFactoryRegistry` with a deny-first fallback when key creation or state recovery fails.

  In `MainActivity`, use platform views only. Render the SMS controls described in the design, request `Manifest.permission.READ_SMS` only from a local click handler, save settings through `LocalSmsSettingsController`, and update/cancel the scheduler after a successful local save. Display `PERMISSION_REQUIRED`, corrupted-state, and disabled-state messages without showing SMS body text. No exported Activity or IPC endpoint may mutate SMS settings.

- [ ] **Step 5: Run focused tests and the app compile to verify GREEN.**

  ```sh
  ./gradlew --no-daemon :sms-collector:test :app:test :app:assembleDebug
  python3 apps/android/tools/test_transport_boundary.py
  ```

- [ ] **Step 6: Commit Android composition.**

  ```sh
  git add apps/android/app/build.gradle.kts apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncScheduler.kt apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncJobService.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncSchedulerTest.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncJobServiceTest.kt
  git commit -m "feat(android): compose SMS permission and auto-sync"
  ```

### Task 7: Add the Bridge SMS service and adapter operations

**Files:**
- Modify: `bridge-contract/src/service-types.ts`
- Create: `bridge-contract/src/sms-store.ts`
- Create: `bridge-contract/src/sms-subscription-store.ts`
- Create: `bridge-contract/src/sms-service.ts`
- Modify: `bridge-contract/src/index.ts`
- Create: `bridge-contract/test/sms-service.test.ts`
- Modify: `integrations/shared/adapter.ts`
- Create: `integrations/shared/sms-contract.test.ts`
- Modify: `integrations/hermes/adapter.ts`
- Modify: `integrations/hermes/adapter.test.ts`
- Modify: `integrations/openclaw/adapter.ts`
- Modify: `integrations/openclaw/adapter.test.ts`
- Modify: `integrations/hermes/plugin-manifest.json`
- Modify: `integrations/openclaw/plugin-manifest.json`
- Modify: `integrations/skills/android-device-bridge/SKILL.md`

**Interfaces:**
- Consumes: `PairingService`, `OperationDispatcher`, `BridgeSessionIdentity`, SMS wire/record rules from Task 5, and the existing notification service patterns.
- Produces: `SmsRecordV1`, `SmsStore`, `SmsSubscriptionStore`, `SmsService`, `FROZEN_SMS_TOOLS`, `querySms`, `subscribeSms`, `unsubscribeSms`, `receiveSmsEvent`, and normalized Hermes/OpenClaw behavior.

- [ ] **Step 1: Write failing Bridge and adapter tests.**

  Bridge tests must cover inbox record validation, empty body preservation, malformed/MMS-shaped field rejection, monotonic equal-time cursor handling, query operation idempotency, policy revision rejection, subscription binding, publish/ACK validation, and cross-session isolation. Adapter tests must assert Hermes and OpenClaw return byte-equivalent SMS query results, expose only the three frozen SMS tool names, reject arbitrary capability strings and model identity fields, and retain complete body text.

- [ ] **Step 2: Run focused tests to verify RED.**

  ```sh
  ./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
  ```

- [ ] **Step 3: Implement closed Bridge SMS types and stores.**

  Add `SmsRecordV1` with exactly `recordId`, `senderAddress`, `threadId`, `messageAtEpochMs`, `observedAtEpochMs`, `read`, `subscriptionId`, `body`, `sourceEpoch`, `cursorProviderId`, `captureRevision`, and `policyRevision`. Validate `recordId` as `sms:<positive decimal>`, non-negative decimal-backed values, nullable address/thread/SIM, boolean read state, and string body including `""`; reject any MMS, attachment, package, URL, tenant, session, or model field.

  `SmsStore.append` must reject position replay/conflict and retain a clone; `read(deviceId, limit)` must bound `limit` by 10,000 and return ascending `(messageAtEpochMs, providerId)` records. `SmsService.query/subscribe/unsubscribe/publishAuthorized/acknowledge` must mirror the existing notification service's paired-session, operation-idempotency, revision, and immediate pre-egress recheck rules, with capability names `mobile.sms.query`, `mobile.sms.subscribe`, and `mobile.sms.unsubscribe`.

- [ ] **Step 4: Implement shared adapter methods and manifests.**

  Extend `AdapterOptions` with `onDemandSms?: () => Promise<readonly SmsRecord[]>` and add `querySms`, `subscribeSms`, `unsubscribeSms`, `receiveSmsEvent`, and a `SmsRecord` type with no identity fields. Keep tool-call identity supplied by the adapter runtime; reject `operationId` and all tenant/session/model fields from tool input. Both provider adapters must delegate to the same shared implementation and only differ in their existing manifest profile metadata. Add `mobile.sms.query`, `mobile.sms.subscribe`, and `mobile.sms.unsubscribe` to the plugin manifests and document the local-grant/complete-body/no-MMS behavior in the Android bridge skill.

- [ ] **Step 5: Run focused tests and TypeScript checks to verify GREEN.**

  ```sh
  ./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
  (cd bridge-contract && ../tools/run-node24 npm run typecheck)
  (cd integrations && ../tools/run-node24 npm run typecheck)
  ```

- [ ] **Step 6: Commit Bridge and plugin integration.**

  ```sh
  git add bridge-contract integrations/shared integrations/hermes integrations/openclaw integrations/skills/android-device-bridge/SKILL.md
  git commit -m "feat: add SMS Bridge and agent integrations"
  ```

### Task 8: Update readiness documentation and run the complete verification gate

**Files:**
- Modify: `apps/android/tools/test_capability_ports_static.py`
- Create: `apps/android/tools/test_sms_collector_static.py`
- Modify: `docs/mvp/mvp-vertical-slice-contract.md`
- Modify: `apps/android/README.md`
- Create: `docs/mvp/sms-read-readiness.md`

**Interfaces:**
- Consumes: all Android, protocol, Bridge, and integration artifacts from Tasks 1–7.
- Produces: SDK-free source-boundary checks, a truthful SMS readiness report, and documented verification commands.

- [ ] **Step 1: Write failing host/static checks.**

  `test_sms_collector_static.py` must verify the new module is registered, source contains `Telephony.Sms.Inbox.CONTENT_URI` and no MMS/sent/draft/outbox URI, the manifest declares `READ_SMS` but not `RECEIVE_SMS`/`SEND_SMS`, the job service is non-exported, and no SMS source contains forbidden VPN/socket/listener/URL/process patterns. Extend capability static checks to require the SMS content-release branch, complete metadata fields, and closed `SmsHistoryPolicy`/`SmsSyncInterval` types.

- [ ] **Step 2: Run the static tests to verify RED.**

  ```sh
  python3 -m unittest apps/android/tools/test_capability_ports_static.py apps/android/tools/test_sms_collector_static.py
  ```

- [ ] **Step 3: Implement only the static assertions and documentation updates.**

  Add the checks without turning them into source-text-only production claims: the static checks protect forbidden surfaces and registration, while Kotlin/TypeScript tests cover runtime behavior. Document that SMS body text is complete and user-authorized, that history/max-record settings are local, that auto-sync is best-effort under Android scheduling, and that real device/AAR evidence remains separate.

- [ ] **Step 4: Run the complete verification suite.**

  From the repository root:

  ```sh
  python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
  ./tools/run-node24 npm test
  ./tools/run-node24 npm run typecheck
  ```

  From `apps/android`:

  ```sh
  ./gradlew --no-daemon check
  ```

  Expected: all host, TypeScript, and Android JVM/static checks pass. If Gradle cannot resolve the locked SDK or native AAR in the environment, record the exact failing dependency/toolchain command and report host/JVM results separately; do not mark device/AAR validation complete.

- [ ] **Step 5: Review the diff and commit the readiness packet.**

  ```sh
  git diff --check
  git status --short
  git diff --stat
  git add apps/android/tools/test_capability_ports_static.py apps/android/tools/test_sms_collector_static.py docs/mvp/mvp-vertical-slice-contract.md apps/android/README.md docs/mvp/sms-read-readiness.md
  git commit -m "docs: record SMS vertical slice readiness"
  ```

  Confirm that the pre-existing untracked `NotificationCollectionSettingsTest.kt` is still untracked and was not included.

## Plan Self-Review

- Scope coverage: Tasks 1–2 cover contract and inbox-only reads; Task 3 covers user-configurable history and cursor; Task 4 covers encrypted auto-send and failure retention; Task 5 covers Android and TypeScript wire closure; Task 6 covers permission, settings, and 15/30/60/manual scheduling; Task 7 covers Bridge, Hermes, and OpenClaw; Task 8 covers static boundaries and verification reporting.
- Placeholder scan: no task requires a guessed dependency version, an unspecified file, or an unspecified error branch. The only environment-dependent result is explicitly reported as unavailable when its external artifact is absent.
- Type consistency: `SmsHistoryPolicy`, `SmsSyncInterval`, `SmsCursor`, `SmsCapabilityProvider`, `CapabilityOutbox`, `SmsAutoSyncCoordinator`, and the SMS wire operation names are defined before downstream tasks consume them.
- Safety review: no task adds SMS sending, MMS access, default-SMS role, `RECEIVE_SMS`, generic networking, VPN, shell, or remote permission mutation.
