# Notification Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the Android notification loop with local policy settings, on-demand Agent reads, and policy-gated automatic delivery through the existing encrypted outbox and paired Bridge transport.

**Architecture:** Extend the existing 'core-model', 'policy-engine', and 'notification-collector' seams. Persist the local delivery mode with the fail-closed notification authority, gate outbox enqueueing in 'NotificationRuntime', and expose a transport-neutral typed query gateway. Keep the main app UI local-only and reuse the current outbox/ACK/transport implementation.

**Tech Stack:** Kotlin 2.1.20, Android Gradle Plugin 8.9.2, Gradle 8.12, Android SDK 35, min SDK 34, Java 17, Android platform views, Kotlin coroutines, JUnit 4.13.2.

## Global Constraints

- The Android toolchain is 'AGP 8.9.2 / Gradle 8.12 / Kotlin 2.1.20 / SDK 35 / minSdk 34'.
- The app must not add 'VpnService', routes, DNS/proxy APIs, generic sockets, generic HTTP clients, or listener endpoints.
- Local notification consent and delivery mode are mutated only by the Android settings UI; Agent requests are read-only.
- Fresh install remains deny-first: no local grant, empty allowlist, metadata access, and 'ON_DEMAND' delivery.
- Notification content is stripped before active retention, outbox enqueue, or query response unless the current local policy grants content access.
- Policy and authorization revisions are monotonic; stale or corrupted state fails closed.
- Use TDD for each behavior: add one focused failing test, observe the expected failure, implement the smallest behavior, then rerun the focused and module suites.
- Every commit must stage only the files listed for that task; verify 'git diff --cached --name-only' before committing so pre-existing user changes are not included.

## File Map

| File | Responsibility |
| --- | --- |
| 'apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettings.kt' | Delivery-mode enum and shared Unicode-code-point package ordering. |
| 'apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationContracts.kt' | Reuse the shared package ordering in the policy value object. |
| 'apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettingsTest.kt' | Shared ordering and default-mode behavior tests. |
| 'apps/android/policy-engine/src/main/kotlin/com/openandroidintelligence/policy/PersistentNotificationPolicyAuthority.kt' | Persist/restore delivery mode with policy and authorization revisions. |
| 'apps/android/policy-engine/src/test/kotlin/com/openandroidintelligence/policy/PersistentNotificationPolicyAuthorityTest.kt' | Authority round-trip, migration, revision, and corruption tests. |
| 'apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGateway.kt' | Typed Agent query validation, redaction, filtering, and operation idempotency. |
| 'apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGatewayTest.kt' | Query authorization and result behavior tests. |
| 'apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationRuntime.kt' | Skip new automatic outbox writes in on-demand mode. |
| 'apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationRuntimeTest.kt' | Delivery-mode regression tests. |
| 'apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NotificationSettingsState.kt' | Pure settings draft-to-commit normalization used by the Android UI. |
| 'apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt' | Local notification settings screen while preserving assistant handoff methods. |
| 'apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt' | Expose the process-local authority/controller to the settings activity. |
| 'apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NotificationSettingsStateTest.kt' | Settings normalization and revision tests. |
| 'apps/android/app/build.gradle.kts' | Add the app's JUnit test dependency. |
| 'apps/android/README.md' | Document the completed notification slice and verification commands. |

---

### Task 1: Add shared delivery mode and package ordering

**Files:**
- Create: 'apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettings.kt'
- Modify: 'apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationContracts.kt'
- Create: 'apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettingsTest.kt'

**Interfaces:**
- Produces 'NotificationDeliveryMode { ON_DEMAND, AUTO_SEND }'.
- Produces 'compareNotificationPackageIds(left: String, right: String): Int'.
- Produces 'sortNotificationPackageIds(values: Iterable<String>): List<String>'.
- 'NotificationCollectionPolicyV1' must call the shared comparator instead of its private duplicate.

- [ ] Step 1: Write the failing tests

Add 'NotificationCollectionSettingsTest.kt' with literal expectations:

~~~kotlin
@Test
fun package_ids_sort_by_unicode_code_point_not_utf16_units() {
    val actual = sortNotificationPackageIds(listOf("com.\uD83D\uDE00", "com.a", "com.\uE000"))
    assertEquals(listOf("com.a", "com.\uE000", "com.\uD83D\uDE00"), actual)
}

@Test
fun duplicate_package_ids_are_rejected_after_normalization() {
    assertThrows(IllegalArgumentException::class.java) {
        sortNotificationPackageIds(listOf("com.mail", "com.mail"))
    }
}

@Test
fun sorting_empty_selection_returns_empty() {
    assertEquals(emptyList<String>(), sortNotificationPackageIds(emptyList()))
}
~~~

The second test catches silently accepting duplicate package selections; the
first catches using Kotlin's UTF-16 'sorted()' behavior. The third protects the
empty selection boundary used by a fresh allowlist.

- [ ] Step 2: Run the focused test and verify the intended failure

Run:

~~~sh
cd apps/android
./gradlew --no-daemon :core-model:testDebugUnitTest --tests com.openandroidintelligence.core.model.NotificationCollectionSettingsTest
~~~

Expected: compilation/test failure because the shared enum and ordering
functions do not exist.

- [ ] Step 3: Implement the smallest shared helpers

Create the enum and comparator. The comparator must iterate 'String.codePoints()'
and compare each integer, then compare code-point lengths. The sorter must
copy the input, reject duplicates, and return the comparator-sorted list. In
'NotificationContracts.kt', remove the private comparator and use the shared
function in the policy initializer.

- [ ] Step 4: Run the focused and existing core-model tests

Run:

~~~sh
./gradlew --no-daemon :core-model:testDebugUnitTest --tests com.openandroidintelligence.core.model.NotificationCollectionSettingsTest --tests com.openandroidintelligence.core.model.NotificationContractsTest
~~~

Expected: all selected tests pass with no new warning or forbidden-surface
output.

- [ ] Step 5: Commit only Task 1 files

Run:

~~~sh
git add -- apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettings.kt apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/NotificationContracts.kt apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/NotificationCollectionSettingsTest.kt
git diff --cached --name-only
git commit -m "feat(android): add notification collection settings model"
~~~

The staged-name output must contain exactly the three listed paths.

### Task 2: Persist delivery mode in the local notification authority

**Files:**
- Modify: 'apps/android/policy-engine/src/main/kotlin/com/openandroidintelligence/policy/PersistentNotificationPolicyAuthority.kt'
- Create: 'apps/android/policy-engine/src/test/kotlin/com/openandroidintelligence/policy/PersistentNotificationPolicyAuthorityTest.kt'

**Interfaces:**
- 'NotificationAuthoritySnapshot' gains 'deliveryMode: NotificationDeliveryMode', defaulting to 'ON_DEMAND' for source compatibility.
- 'LocalNotificationPolicyController.apply(policy, authorizationRevision, granted, deliveryMode)' updates all local state in one durable transaction.
- The existing three-argument 'apply' call remains valid and preserves the current delivery mode.
- 'LocalNotificationPolicyController.revoke' preserves the current mode while setting 'granted = false'.

- [ ] Step 1: Write failing persistence and revision tests

Add tests with an 'InMemoryNotificationPolicyPersistence':

~~~kotlin
@Test
fun delivery_mode_round_trips_with_grant_and_policy() {
    val persistence = InMemoryNotificationPolicyPersistence()
    val first = PersistentNotificationPolicyAuthority(persistence)
    val policy = NotificationCollectionPolicyV1(
        mode = NotificationRuleMode.ALLOWLIST,
        packageIds = listOf("com.example.mail"),
        fieldAccess = NotificationFieldAccess.CONTENT,
        policyRevision = 1u,
    )
    first.localController().apply(policy, authorizationRevision = 1u, granted = true, deliveryMode = NotificationDeliveryMode.AUTO_SEND)

    val restored = PersistentNotificationPolicyAuthority(persistence).snapshot()
    assertEquals(NotificationDeliveryMode.AUTO_SEND, restored.deliveryMode)
    assertEquals(true, restored.granted)
    assertEquals(1u, restored.policy.policyRevision)
}

@Test
fun changing_delivery_mode_without_an_authorization_revision_is_rejected() {
    val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
    val controller = authority.localController()
    controller.apply(NotificationCollectionPolicyV1.default(), 1u, true, NotificationDeliveryMode.ON_DEMAND)

    assertThrows(PolicyRevisionRace::class.java) {
        controller.apply(NotificationCollectionPolicyV1.default(), 1u, true, NotificationDeliveryMode.AUTO_SEND)
    }
}

@Test
fun corrupt_persisted_bytes_restore_as_deny_first_corrupted_state() {
    val persistence = InMemoryNotificationPolicyPersistence()
    persistence.write(byteArrayOf(0x41, 0x42, 0x43))
    val snapshot = PersistentNotificationPolicyAuthority(persistence).snapshot()
    assertEquals(false, snapshot.granted)
    assertEquals(true, snapshot.corrupted)
}
~~~

Also add a legacy V1 byte fixture using the current magic
'OPEN_ANDROID_INTELLIGENCE_NOTIFICATION_AUTHORITY_V1'; it must restore with
'deliveryMode == ON_DEMAND' so existing app-private state remains readable.
Build the fixture with this exact field order from the current V1 encoder:

~~~kotlin
private fun legacyV1Bytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        writeLegacyString(output, "OPEN_ANDROID_INTELLIGENCE_NOTIFICATION_AUTHORITY_V1")
        output.writeLong(7L)
        output.writeBoolean(true)
        output.writeByte(NotificationRuleMode.ALLOWLIST.ordinal)
        output.writeByte(NotificationFieldAccess.METADATA.ordinal)
        output.writeLong(3L)
        output.writeInt(1)
        writeLegacyString(output, "mail")
    }
    bytes.toByteArray()
}

private fun writeLegacyString(output: DataOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    output.writeInt(bytes.size)
    output.write(bytes)
}
~~~

The migration test writes 'legacyV1Bytes()' into the persistence, constructs a
new authority, and asserts revisions, grant, package selection, and the
'ON_DEMAND' mode.

- [ ] Step 2: Run the authority tests and verify the intended failure

Run:

~~~sh
cd apps/android
./gradlew --no-daemon :policy-engine:testDebugUnitTest --tests com.openandroidintelligence.policy.PersistentNotificationPolicyAuthorityTest
~~~

Expected: compilation failure because the snapshot and controller do not yet
accept a delivery mode.

- [ ] Step 3: Implement versioned persistence and monotonic mutation

Add the delivery mode to 'NotificationAuthoritySnapshot' and to the in-memory
default. Write a V2 magic value and one mode ordinal in the encoded record. The
decoder must accept both the existing V1 magic (mapping it to 'ON_DEMAND') and
the new V2 magic, reject unknown ordinals/trailing bytes, and preserve the
existing corrupted-state behavior.

Update 'applyLocal' so a delivery-mode change without an authorization-revision
change raises 'PolicyRevisionRace'. Persist the complete candidate before
publishing it to 'current' or notifying listeners. Keep the existing policy and
grant revision checks unchanged.

- [ ] Step 4: Run policy, collector, and core regression tests

Run:

~~~sh
./gradlew --no-daemon :policy-engine:testDebugUnitTest :notification-collector:testDebugUnitTest :core-model:testDebugUnitTest
~~~

Expected: all existing tests and the new authority tests pass.

- [ ] Step 5: Commit only Task 2 files

Run:

~~~sh
git add -- apps/android/policy-engine/src/main/kotlin/com/openandroidintelligence/policy/PersistentNotificationPolicyAuthority.kt apps/android/policy-engine/src/test/kotlin/com/openandroidintelligence/policy/PersistentNotificationPolicyAuthorityTest.kt
git diff --cached --name-only
git commit -m "feat(android): persist notification delivery mode"
~~~

### Task 3: Add the typed Agent on-demand query gateway

**Files:**
- Create: 'apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGateway.kt'
- Create: 'apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGatewayTest.kt'

**Interfaces:**
- 'NotificationQueryFilter(packageIds: List<String> = emptyList(), fieldAccess: NotificationFieldAccess = METADATA)' validates non-empty package IDs, package-name syntax, uniqueness, and Unicode-code-point ordering.
- 'NotificationAgentQueryRequest(operationId: String, policyRevision: ULong, limit: Int, filter: NotificationQueryFilter = NotificationQueryFilter())' validates a non-blank operation ID and a limit from 1 through 100.
- 'NotificationQueryRejected(code: String)' is the typed exception for malformed input or operation identity conflicts.
- 'NotificationAgentQueryGateway(collector: NotificationCollector, authority: PersistentNotificationPolicyAuthority)' exposes 'suspend fun query(request: NotificationAgentQueryRequest): NotificationCaptureResult'.

- [ ] Step 1: Write the failing query tests

Use a real 'AndroidNotificationCollector' and 'PersistentNotificationPolicyAuthority'
backed by 'InMemoryNotificationPolicyPersistence'; do not mock the collector. Cover
these behaviors:

~~~kotlin
@Test
fun query_returns_requested_package_and_content_only_when_local_policy_allows_it() = runSuspend {
    val authority = authorityWithContentPolicy()
    val collector = AndroidNotificationCollector(authorization = authority)
    collector.applyPolicyBlocking(authority.snapshot().policy)
    collector.onPosted(raw("com.mail", "mail", "subject", "body"))
    collector.onPosted(raw("com.chat", "chat", "chat subject", "chat body"))

    val result = NotificationAgentQueryGateway(collector, authority).query(
        NotificationAgentQueryRequest(
            operationId = "op-1",
            policyRevision = 1u,
            limit = 10,
            filter = NotificationQueryFilter(listOf("com.mail"), NotificationFieldAccess.CONTENT),
        ),
    )

    assertEquals(NotificationCaptureStatus.COMPLETE, result.status)
    assertEquals("com.mail", (result.records.single() as NotificationRecordV1.Upsert).metadata.packageName)
    assertEquals("body", (result.records.single() as NotificationRecordV1.Upsert).content?.body)
}

@Test
fun metadata_query_redacts_content_even_when_local_policy_allows_content() = runSuspend {
    val gateway = gatewayWithContentPolicyAndOneRecord()
    val result = gateway.query(NotificationAgentQueryRequest("metadata-op", 1u, 10))
    assertEquals(null, (result.records.single() as NotificationRecordV1.Upsert).content)
}

@Test
fun identical_operation_retry_does_not_capture_twice_and_conflicting_reuse_is_rejected() = runSuspend {
    val countingCollector = CountingNotificationCollector(realCollectorWithOneRecord())
    val gateway = NotificationAgentQueryGateway(countingCollector, authorityWithContentPolicy())
    val request = NotificationAgentQueryRequest("same-op", 1u, 10)
    assertEquals(gateway.query(request), gateway.query(request))
    assertEquals(1, countingCollector.captureCount)
    assertThrows(NotificationQueryRejected::class.java) {
        runSuspend { gateway.query(request.copy(limit = 9)) }
    }
}
~~~

The test helpers must delegate to the real collector and only count invocations
at the collector boundary; assertions must be on returned records and status,
not on mock calls.

Define the test helpers in the same test file so every referenced symbol is
concrete:

~~~kotlin
private fun authorityWithContentPolicy(): PersistentNotificationPolicyAuthority {
    val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
    authority.localController().apply(
        NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("com.chat", "com.mail"),
            NotificationFieldAccess.CONTENT,
            1u,
        ),
        authorizationRevision = 1u,
        granted = true,
    )
    return authority
}

private fun realCollectorWithOneRecord(): AndroidNotificationCollector {
    val authority = authorityWithContentPolicy()
    return AndroidNotificationCollector(authorization = authority).also { collector ->
        collector.applyPolicyBlocking(authority.snapshot().policy)
        collector.onPosted(RawNotification("com.mail", "mail", "subject", "body", null, 1))
    }
}

private fun gatewayWithContentPolicyAndOneRecord(): NotificationAgentQueryGateway {
    val authority = authorityWithContentPolicy()
    val collector = AndroidNotificationCollector(authorization = authority).also {
        it.applyPolicyBlocking(authority.snapshot().policy)
        it.onPosted(RawNotification("com.mail", "mail", "subject", "body", null, 1))
    }
    return NotificationAgentQueryGateway(collector, authority)
}

private fun raw(packageName: String, key: String, title: String, body: String): RawNotification =
    RawNotification(packageName, key, packageName, title, body, null, 1)

private class CountingNotificationCollector(
    private val delegate: NotificationCollector,
) : NotificationCollector {
    var captureCount: Int = 0
        private set

    override suspend fun applyPolicy(policy: NotificationCollectionPolicyV1) = delegate.applyPolicy(policy)

    override suspend fun captureOnDemand(request: OnDemandNotificationRead): NotificationCaptureResult {
        captureCount += 1
        return delegate.captureOnDemand(request)
    }

    override fun observeAutoSend() = delegate.observeAutoSend()
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
~~~

Import 'java.io.ByteArrayOutputStream', 'java.io.DataOutputStream',
'kotlin.coroutines.Continuation', 'kotlin.coroutines.EmptyCoroutineContext',
and 'kotlin.coroutines.startCoroutine' in this test file.

Also test stale policy revision and missing local grant return
'NotificationCaptureStatus.FAILED' with 'AUTHORIZATION_REVISION_STALE' or
'LOCAL_GRANT_REQUIRED', content requests under metadata policy fail, limits 0
and 101 are rejected, and unsorted/duplicate package filters are rejected.

- [ ] Step 2: Run the focused gateway tests and verify the intended failure

Run:

~~~sh
cd apps/android
./gradlew --no-daemon :notification-collector:testDebugUnitTest --tests com.openandroidintelligence.notifications.NotificationAgentQueryGatewayTest
~~~

Expected: compilation failure because the gateway request, result handling, and
operation cache do not exist.

- [ ] Step 3: Implement the gateway with a serialized operation cache

Implement request validation before collector access. Use a coroutines 'Mutex'
to serialize the operation cache and collector call. Store the complete
request plus 'NotificationCaptureResult' under 'operationId'; return the stored
result for an identical retry and throw 'NotificationQueryRejected("OPERATION_IDENTITY_MISMATCH")' when any request field differs.

Before capture, compare the request revision with
'authority.snapshot().policy.policyRevision', require a non-corrupted local
grant, and reject content access unless the current policy field access is
'CONTENT'. For these failures return a failed 'NotificationCaptureResult' with
an empty record list and the current policy revision.

Call 'collector.captureOnDemand(OnDemandNotificationRead(operationId, revision, limit))'.
After capture, apply the request filter in memory:

- A package filter keeps only upsert/delete records whose metadata package is
  listed and excludes loss markers.
- A metadata request maps upserts to 'content = null' and never adds content to
  delete/loss records.
- A content request preserves the already policy-authorized content.
- Preserve the collector status, failure reason, and policy revision.

- [ ] Step 4: Run focused, collector, and policy tests

Run:

~~~sh
./gradlew --no-daemon :notification-collector:testDebugUnitTest :policy-engine:testDebugUnitTest :core-model:testDebugUnitTest
~~~

Expected: all selected tests pass.

- [ ] Step 5: Commit only Task 3 files

Run:

~~~sh
git add -- apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGateway.kt apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGatewayTest.kt
git diff --cached --name-only
git commit -m "feat(android): add notification agent query gateway"
~~~

### Task 4: Gate runtime auto-send by the local delivery mode

**Files:**
- Modify: 'apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationRuntime.kt'
- Modify: 'apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationRuntimeTest.kt'

**Interfaces:**
- 'NotificationRuntime' continues to accept the existing optional 'PersistentNotificationPolicyAuthority'.
- With an authority, newly captured records are enqueued only when 'snapshot().deliveryMode == AUTO_SEND'.
- Without an authority, existing injected runtime tests retain their current auto-send behavior.
- Recovery of already accepted outbox events remains on the existing dispatcher path and still uses the egress policy/ACK gates.

- [ ] Step 1: Write the failing runtime tests

Add tests using the existing 'RecordingOutbox', 'record()', and
'runSuspendRuntime' fixtures already defined in 'NotificationRuntimeTest.kt':

~~~kotlin
@Test
fun on_demand_mode_keeps_callback_out_of_outbox() = runSuspendRuntime {
    val authority = authorityWithMode(NotificationDeliveryMode.ON_DEMAND)
    val outbox = RecordingOutbox()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val runtime = NotificationRuntime(
        initialCollector = AndroidNotificationCollector(authorization = authority),
        outbox = outbox,
        scope = scope,
        policyAuthority = authority,
    )

    runtime.persistAndDispatch(NotificationCaptureResult(listOf(record())))

    assertEquals(0, outbox.events.size)
    scope.cancel()
}

@Test
fun auto_send_mode_keeps_existing_enqueue_and_dispatch_behavior() = runSuspendRuntime {
    val authority = authorityWithMode(NotificationDeliveryMode.AUTO_SEND)
    val outbox = RecordingOutbox()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val runtime = NotificationRuntime(
        initialCollector = AndroidNotificationCollector(authorization = authority),
        outbox = outbox,
        scope = scope,
        policyAuthority = authority,
    )

    runtime.persistAndDispatch(NotificationCaptureResult(listOf(record())))

    assertEquals(1, outbox.events.size)
    scope.cancel()
}
~~~

Keep the existing tests for outbox failures, policy revocation, retry pending,
and collector replacement unchanged so the new branch cannot bypass their
behavior.

Define this authority helper in the same test file; it uses the real local
controller and the exact policy accepted by the existing 'record()' fixture:

~~~kotlin
private fun authorityWithMode(mode: NotificationDeliveryMode): PersistentNotificationPolicyAuthority {
    val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
    authority.localController().apply(
        NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("mail"),
            NotificationFieldAccess.CONTENT,
            1u,
        ),
        authorizationRevision = 1u,
        granted = true,
        deliveryMode = mode,
    )
    return authority
}
~~~

- [ ] Step 2: Run the focused tests and verify the intended failure

Run:

~~~sh
cd apps/android
./gradlew --no-daemon :notification-collector:testDebugUnitTest --tests com.openandroidintelligence.notifications.NotificationRuntimeTest
~~~

Expected: the on-demand test fails because the current runtime always enqueues
captured records.

- [ ] Step 3: Implement the mode gate

At the start of 'persistAndDispatch', after the nullable outbox check and before
iterating over records, read the current authority snapshot. If an authority is
present and its delivery mode is 'ON_DEMAND', return without enqueueing. Keep
the no-authority path unchanged for existing unit/injection behavior. Do not
change dispatcher recovery or the 'NotificationRecordEgressGate'; a mode change
must not weaken policy revocation or ACK handling.

- [ ] Step 4: Run the complete notification collector suite

Run:

~~~sh
./gradlew --no-daemon :notification-collector:testDebugUnitTest
~~~

Expected: all notification collector, dispatcher, runtime, and gateway tests
pass.

- [ ] Step 5: Commit only Task 4 files

Run:

~~~sh
git add -- apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationRuntime.kt apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationRuntimeTest.kt
git diff --cached --name-only
git commit -m "feat(android): gate notification auto-send mode"
~~~

### Task 5: Add local settings state and Android settings UI

**Files:**
- Create: 'apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NotificationSettingsState.kt'
- Modify: 'apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt'
- Modify: 'apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt'
- Modify: 'apps/android/app/build.gradle.kts'
- Create: 'apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NotificationSettingsStateTest.kt'

**Interfaces:**
- 'NotificationSettingsDraft(granted, deliveryMode, fieldAccess, ruleMode, packageIds)' is a pure UI draft.
- 'NotificationSettingsCommit(policy, authorizationRevision, granted, deliveryMode)' is the normalized local mutation.
- 'NotificationSettingsDraft.commitAgainst(snapshot): NotificationSettingsCommit' sorts package IDs with 'sortNotificationPackageIds', increments policy and authorization revisions exactly once, and rejects revision overflow.
- 'OpenAndroidIntelligenceApplication.notificationAuthority()' and '.notificationPolicyController()' expose the already-created process-local authority/controller without exposing mutation methods to remote code.

- [ ] Step 1: Write failing pure settings tests

Create 'NotificationSettingsStateTest.kt':

~~~kotlin
@Test
fun commit_sorts_packages_and_advances_both_revisions() {
    val snapshot = NotificationAuthoritySnapshot(
        policy = NotificationCollectionPolicyV1.default(),
        authorizationRevision = 4u,
        granted = false,
        deliveryMode = NotificationDeliveryMode.ON_DEMAND,
    )
    val commit = NotificationSettingsDraft(
        granted = true,
        deliveryMode = NotificationDeliveryMode.AUTO_SEND,
        fieldAccess = NotificationFieldAccess.CONTENT,
        ruleMode = NotificationRuleMode.ALLOWLIST,
        packageIds = listOf("com.zeta", "com.alpha"),
    ).commitAgainst(snapshot)

    assertEquals(listOf("com.alpha", "com.zeta"), commit.policy.packageIds)
    assertEquals(1u, commit.policy.policyRevision)
    assertEquals(5u, commit.authorizationRevision)
    assertEquals(NotificationDeliveryMode.AUTO_SEND, commit.deliveryMode)
    assertEquals(true, commit.granted)
}

@Test
fun commit_rejects_revision_overflow_instead_of_wrapping_to_zero() {
    val snapshot = NotificationAuthoritySnapshot(
        NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST, emptyList(), NotificationFieldAccess.METADATA, ULong.MAX_VALUE,
        ),
        ULong.MAX_VALUE,
        false,
    )
    assertThrows(IllegalStateException::class.java) {
        NotificationSettingsDraft(false, NotificationDeliveryMode.ON_DEMAND, NotificationFieldAccess.METADATA, NotificationRuleMode.ALLOWLIST, emptyList()).commitAgainst(snapshot)
    }
}
~~~

- [ ] Step 2: Run the focused app test and verify the intended failure

Run:

~~~sh
cd apps/android
./gradlew --no-daemon :app:testDebugUnitTest --tests com.openandroidintelligence.mobile.NotificationSettingsStateTest
~~~

Expected: compilation failure because the draft and commit types do not exist
and the app currently has no JUnit dependency.

- [ ] Step 3: Implement the pure state commit and application accessors

Add the draft/commit data classes and 'commitAgainst' implementation. Use the
shared package sorter and construct a new 'NotificationCollectionPolicyV1' with
the incremented policy revision. In 'OpenAndroidIntelligenceApplication', retain the current
private authority field and add read-only accessors returning the authority and
its local controller. Do not expose 'PairedNotificationBridgeRegistry' or any
transport object through these accessors.

Add 'testImplementation("junit:junit:4.13.2")' to 'apps/android/app/build.gradle.kts'.

- [ ] Step 4: Implement the platform-view settings screen

Keep 'evaluateAssistantHandoff' and 'currentAssistantHandoffDecision' in
'MainActivity'. In 'onCreate', obtain the application authority snapshot and
build a vertical 'LinearLayout' containing:

1. A 'TextView' showing whether this package appears in
   'Settings.Secure.getString(contentResolver, "enabled_notification_listeners")'.
2. A button launching 'Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)'.
3. A local-grant 'Switch'.
4. An 'ON_DEMAND'/'AUTO_SEND' 'RadioGroup'.
5. A metadata/content 'RadioGroup'.
6. An allowlist/denylist 'RadioGroup'.
7. One 'CheckBox' per installed application from
   'packageManager.getInstalledApplications(0)', sorted by the shared package
   comparator, with package name and application label.
8. A revision/status 'TextView' and a Save button.

The Save handler must build 'NotificationSettingsDraft' from the controls,
call 'commitAgainst(authority.snapshot())', then call
'notificationPolicyController().apply(commit.policy, commit.authorizationRevision, commit.granted, commit.deliveryMode)'. Display a safe error message for
'PolicyStateCorrupted', 'PolicyRevisionRace', or invalid local state without
including notification content. The screen must never invoke a remote Agent
operation or accept a host/URL field.

- [ ] Step 5: Run app unit tests and the SDK-free source boundary

Run:

~~~sh
./gradlew --no-daemon :app:testDebugUnitTest
cd ../..
python3 apps/android/tools/test_transport_boundary.py
~~~

Expected: the pure state tests pass and the static source gate reports no
forbidden VPN/listener/socket/HTTP surface. If the Android SDK is unavailable,
record that only the SDK-free check ran; do not substitute a source scan for a
build claim.

- [ ] Step 6: Commit only Task 5 files

Run:

~~~sh
git add -- apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NotificationSettingsState.kt apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt apps/android/app/build.gradle.kts apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NotificationSettingsStateTest.kt
git diff --cached --name-only
git commit -m "feat(android): add notification settings screen"
~~~

### Task 6: Document the slice and run full verification

**Files:**
- Modify: 'apps/android/README.md'

**Interfaces:**
- Documentation records the implemented notification behavior and the exact
  available SDK-free/build verification commands.
- No dependency-lock row is changed by this task; Tailscale, Bridge, Hermes,
  OpenClaw, and artifact lock evidence remains governed by the existing lock
  document.

- [ ] Step 1: Update the Android README

Replace the notification-related “source-only” wording with the actual current
boundary:

- local policy and delivery mode are persisted by the app authority;
- on-demand queries use the typed Android gateway;
- auto-send uses the encrypted outbox and existing paired transport;
- the raw signed protocol-session binding, Room, and real Tailscale AAR remain
  outside this slice.

Keep the existing commands and explicitly distinguish the SDK-free static gate
from the full './gradlew --no-daemon check' command.

- [ ] Step 2: Run the documentation diff check

Run:

~~~sh
git diff --check -- apps/android/README.md
~~~

Expected: exit 0.

- [ ] Step 3: Run the complete verification set

Run from the repository root:

~~~sh
python3 apps/android/tools/test_transport_boundary.py
cd apps/android
./gradlew --no-daemon check
~~~

Expected: the static boundary suite exits 0. If the full Android command is
blocked by a missing SDK, wrapper distribution, or native dependency, report
the exact command and blocker rather than claiming a build result.

Also run the repository TypeScript contract suites when the Node toolchain is
available:

~~~sh
cd ../..
./tools/run-node24 npm test -- --runInBand
~~~

The TypeScript suite is a regression check for the existing notification wire,
Bridge authorization, operation idempotency, and Hermes/OpenClaw adapter
contracts; no TypeScript production changes are expected in this slice.

- [ ] Step 4: Review the final diff and commit only the README

Run:

~~~sh
git diff --stat
git diff --check
git add -- apps/android/README.md
git diff --cached --name-only
git commit -m "docs(android): document notification vertical slice"
~~~

The staged-name output must contain only 'apps/android/README.md'. Finally run
'git status --short' and report any pre-existing user changes separately from
the files changed by this plan.

## Completion Checklist

- [ ] Shared delivery mode and package ordering are implemented and tested.
- [ ] Authority persistence restores V1 state as on-demand and persists V2 mode.
- [ ] Typed Agent queries enforce local grant, revision, field, package, limit,
      and operation identity boundaries.
- [ ] Runtime on-demand mode skips new outbox writes; auto-send retains the
      existing encrypted dispatch path.
- [ ] Settings UI exposes local consent, mode, field access, rule mode, and
      per-application selection without a network surface.
- [ ] Static source gate and available Android/TypeScript test suites have fresh
      verification evidence.
- [ ] Final diff contains only intended implementation/doc changes; pre-existing
      user edits are not included in task commits.
