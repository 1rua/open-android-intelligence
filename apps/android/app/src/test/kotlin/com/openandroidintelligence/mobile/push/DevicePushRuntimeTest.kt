package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.EncryptedOutboxPersistence
import com.openandroidintelligence.encrypted.store.FileEncryptedOutboxPersistence
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class DevicePushRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = PushEndpoint("core-a", "acct-a", "endpoint-a", 1)
    private val b = PushEndpoint("core-b", "acct-b", "endpoint-b", 2)
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val notification get() = PushNotification("os-key", "test.app", clock.epoch, "test title", "secret notification body")
    private val sms get() = PushSms(42, clock.epoch, "sender", "secret sms body")
    private val clock = TestPushClock()
    private fun configuration(endpoints: Set<PushEndpoint> = setOf(a, b)) = PushConfiguration(
        notifications = NotificationPushSettings(true, NotificationPushScope(setOf("test.app")), endpoints),
        sms = SmsPushSettings(true, SmsPushScope(setOf("sender")), endpoints),
    )
    private fun runtime(persistence: EncryptedOutboxPersistence = InMemoryOutboxPersistence(), access: PushSystemAccess = PushSystemAccess { true }) =
        DevicePushRuntime(EncryptedPushStateStore("installation", persistence, key), access, clock, 500)
    private fun ready(persistence: EncryptedOutboxPersistence = InMemoryOutboxPersistence()) = runtime(persistence).also {
        it.configure(configuration()); it.onLoginStateChanged(true)
    }

    @Test fun defaultsAreIndependentDisabledButFullContentIsTheDefaultScope() {
        val runtime = runtime()
        val config = runtime.snapshot().configuration
        assertFalse(config.sms.enabled)
        assertFalse(config.notifications.enabled)
        assertTrue(config.sms.scope.includeContent)
        assertTrue(config.notifications.scope.includeContent)
        assertTrue(config.sms.endpoints.isEmpty())
        assertEquals(72, config.retentionHours)
        assertEquals(PushCaptureStatus.LOGGED_OUT, runtime.onSms(sms).status)
        runtime.onLoginStateChanged(true)
        assertEquals(PushCaptureStatus.DISABLED, runtime.onSms(sms).status)
    }

    @Test fun installationConfigurationAndUnackedPerEndpointStateSurviveRealEncryptedFileRestart() = runBlocking {
        val file = temporary.newFile("push.bin")
        // An absent ciphertext file is the only valid empty state.
        val persistence = FileEncryptedOutboxPersistence(java.io.File(file.parentFile, "state.bin"))
        val runtime = ready(persistence)
        val event = runtime.onNotification(notification)
        assertEquals(PushCaptureStatus.ENQUEUED, event.status)
        val transport = TestPushTransport { delivery -> if (delivery.endpoint == a) PushReceiptState.CONSUMED else PushReceiptState.ACCEPTED }
        assertEquals(1, runtime.deliver(transport).acknowledged)
        val disk = persistence.read()!!
        assertFalse(disk.toString(Charsets.UTF_8).contains("secret notification body"))
        val recovered = runtime(persistence)
        assertEquals(configuration(), recovered.snapshot().configuration)
        assertEquals(1, recovered.snapshot().pendingDeliveries)
        assertTrue(recovered.deliver(transport).paused)
        recovered.onLoginStateChanged(true)
        transport.state = { PushReceiptState.CONSUMED }
        assertEquals(1, recovered.deliver(transport).acknowledged)
        assertEquals(0, recovered.snapshot().queuedEvents)
        assertEquals(PushCaptureStatus.DUPLICATE, recovered.onNotification(notification).status)
        assertEquals(listOf(a, b, b), transport.deliveries.map { it.endpoint })
        assertEquals(1, transport.polls)
    }

    @Test fun stableIdsNeverIncludeAccountAndAddingAnEndpointDoesNotBackfillOldEvents() = runBlocking {
        val runtime = ready()
        val first = runtime.onSms(sms)
        assertEquals(first.eventId, runtime.onSms(sms).eventId)
        assertEquals(PushCaptureStatus.DUPLICATE, runtime.onSms(sms).status)
        val c = PushEndpoint("core-c", "acct-c", "endpoint-a", 1)
        runtime.configure(configuration(setOf(a, b, c)))
        val transport = TestPushTransport()
        runtime.deliver(transport)
        assertEquals(setOf(a, b), transport.deliveries.map { it.endpoint }.toSet())
        assertTrue(transport.deliveries.all { it.event.payloadJson.contains("secret sms body") })
    }

    @Test fun httpAcceptanceIsNotReceiverAckAndMismatchedAckNeverCleansTheQueue() = runBlocking {
        val runtime = ready()
        runtime.onSms(sms)
        val transport = TestPushTransport { PushReceiptState.ACCEPTED }
        assertEquals(0, runtime.deliver(transport).acknowledged)
        assertEquals(2, runtime.snapshot().pendingDeliveries)
        transport.receiptOverride = { delivery -> PushReceipt(delivery.endpoint.copy(generation = 9), delivery.event.eventId, "delivery", PushReceiptState.CONSUMED) }
        assertEquals(0, runtime.deliver(transport).acknowledged)
        assertEquals(2, runtime.snapshot().pendingDeliveries)
    }

    @Test fun independentScopesAndSystemPermissionsAreRecheckedOnCollectionAndDelivery() = runBlocking {
        var granted = true
        val runtime = runtime(access = PushSystemAccess { granted })
        runtime.configure(configuration().copy(sms = SmsPushSettings(false)))
        runtime.onLoginStateChanged(true)
        assertEquals(PushCaptureStatus.DISABLED, runtime.onSms(sms).status)
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE, runtime.onNotification(notification.copy(packageName = "other.app")).status)
        assertEquals(PushCaptureStatus.ENQUEUED, runtime.onNotification(notification).status)
        granted = false
        val transport = TestPushTransport()
        runtime.deliver(transport)
        assertEquals(0, transport.deliveries.size)
        assertEquals(1, runtime.snapshot().queuedEvents)
    }

    @Test fun narrowingContentOrRemovingExactEndpointPurgesOnlyDisallowedLocalDeliveries() = runBlocking {
        val runtime = ready()
        runtime.onSms(sms)
        runtime.onNotification(notification)
        runtime.configure(configuration().copy(sms = configuration().sms.copy(scope = SmsPushScope(setOf("sender"), includeContent = false))))
        assertEquals(1, runtime.snapshot().queuedEvents)
        runtime.configure(runtime.snapshot().configuration.let { it.copy(notifications = it.notifications.copy(endpoints = setOf(b))) })
        assertEquals(1, runtime.snapshot().pendingDeliveries)
        val transport = TestPushTransport()
        runtime.deliver(transport)
        assertEquals(listOf(b), transport.deliveries.map { it.endpoint })
        assertEquals(3L, runtime.snapshot().revokedDeliveries)
    }

    @Test fun logoutCancelsRealSuspendingAttemptAndLoginRevalidatesBeforeRetrying() = runBlocking {
        val runtime = ready()
        runtime.onSms(sms)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val transport = object : PushTransport {
            override suspend fun verifyEndpoint(endpoint: PushEndpoint) = EndpointVerification.VALID
            override suspend fun send(delivery: PushDelivery, stillAuthorized: () -> Boolean): PushReceipt {
                entered.complete(Unit)
                never.await()
                error("MUST_BE_CANCELLED")
            }
            override suspend fun poll(delivery: PushDelivery, deliveryId: String, stillAuthorized: () -> Boolean) = send(delivery, stillAuthorized)
        }
        val run = async { runtime.deliver(transport) }
        entered.await()
        runtime.onLoginStateChanged(false)
        withTimeout(1_000) { run.await() }
        assertEquals(2, runtime.snapshot().pendingDeliveries)
        assertEquals(PushCaptureStatus.LOGGED_OUT, runtime.onNotification(notification).status)
        val recoveredTransport = TestPushTransport()
        runtime.onLoginStateChanged(true)
        assertEquals(2, runtime.deliver(recoveredTransport).acknowledged)
        assertEquals(listOf(a, b), recoveredTransport.verified)
    }

    @Test fun aRevokeDuringEndpointValidationPreventsAnyPayloadLeavingTheDevice() = runBlocking {
        val runtime = ready()
        runtime.onSms(sms)
        val transport = TestPushTransport()
        transport.beforeVerify = { runtime.configure(PushConfiguration()) }
        runtime.deliver(transport)
        assertTrue(transport.deliveries.isEmpty())
        assertEquals(0, runtime.snapshot().queuedEvents)
    }

    @Test fun callbackWakesSystemWorkAfterConfigureEnqueueAndLoginButNotMaintenance() {
        val runtime = runtime()
        var wakes = 0
        runtime.onWorkAvailable = { wakes++ }
        runtime.configure(configuration())
        runtime.onLoginStateChanged(true)
        runtime.onSms(sms)
        runtime.onSms(sms)
        runtime.maintain()
        assertEquals(3, wakes)
        runtime.onWorkAvailable = { throw IllegalStateException("scheduler offline") }
        runtime.onNotification(notification)
        assertTrue(runtime.schedulingFailed)
        assertEquals(2, runtime.snapshot().queuedEvents)
    }

    @Test fun capacityRejectsNewEventsAndDoesNotAdvanceSmsCursorOrLoseAcceptedContent() {
        val runtime = runtime()
        runtime.configure(configuration().copy(maxEvents = 1))
        runtime.onLoginStateChanged(true)
        runtime.onSms(sms)
        val cursor = runtime.smsCursor()
        assertEquals(PushCaptureStatus.QUEUE_FULL, runtime.onSms(sms.copy(providerId = 43)).status)
        assertEquals(cursor, runtime.smsCursor())
        assertEquals(1, runtime.snapshot().queuedEvents)
        assertEquals(1L, runtime.snapshot().capacityRejections)
    }

    @Test fun expiryRunsWhileLoggedOutAndClockRollbackCannotExtendIt() {
        val runtime = runtime()
        runtime.configure(configuration().copy(retentionHours = 1))
        runtime.onLoginStateChanged(true)
        runtime.onSms(sms)
        runtime.onLoginStateChanged(false)
        clock.elapsed += PUSH_HOUR_MS
        clock.epoch -= PUSH_HOUR_MS
        assertEquals(0, runtime.maintain().queuedEvents)
        assertEquals(1L, runtime.snapshot().expiredEvents)
    }

    @Test fun damagedCiphertextAndMissingInstallationOwnershipFailClosedWithoutReset() {
        val memory = InMemoryOutboxPersistence()
        val runtime = ready(memory)
        runtime.onSms(sms)
        val ciphertext = memory.read()!!
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        memory.write(ciphertext)
        assertThrows(PushStateUnavailable::class.java) { runtime(memory) }
        assertArrayEquals(ciphertext, memory.read())
        val other = InMemoryOutboxPersistence()
        ready(other)
        assertThrows(PushStateUnavailable::class.java) { DevicePushRuntime(EncryptedPushStateStore("another-installation", other, key), PushSystemAccess { true }, clock) }
    }

    @Test fun storageFailureDoesNotExposeUnsavedConfigurationAndStopsFurtherEgress() = runBlocking {
        val bytes = InMemoryOutboxPersistence()
        var fail = false
        val persistence = object : EncryptedOutboxPersistence by bytes {
            override fun write(ciphertext: ByteArray) { if (fail) throw IllegalStateException("disk full") else bytes.write(ciphertext) }
        }
        val runtime = ready(persistence)
        runtime.onSms(sms)
        fail = true
        assertThrows(IllegalStateException::class.java) { runtime.configure(PushConfiguration()) }
        assertFalse(runtime.isCollectionAllowed(PushKind.SMS))
        assertThrows(PushStateUnavailable::class.java) { runtime.snapshot() }
        assertEquals(1, runtime(persistence).snapshot().queuedEvents)
    }

    @Test fun invalidSettingsAndOversizePayloadAreRejectedWithoutInventingAnEvent() {
        assertThrows(IllegalArgumentException::class.java) { PushConfiguration(retentionHours = 0) }
        assertThrows(IllegalArgumentException::class.java) { PushConfiguration(retentionHours = 169) }
        val runtime = ready()
        assertEquals(PushCaptureStatus.TOO_LARGE, runtime.onSms(sms.copy(body = "x".repeat(MAX_PUSH_EVENT_BYTES))).status)
        assertEquals(0, runtime.snapshot().queuedEvents)
    }
    @Test fun remoteRevocationHookRunsAfterLocalCommitAndCannotRollItBackWhenItThrows() {
        val runtime = ready()
        runtime.onSms(sms)
        val revoked = mutableListOf<PushRevocation>()
        runtime.onRevocationAvailable = {
            assertEquals(0, runtime.snapshot().queuedEvents)
            assertFalse(runtime.snapshot().configuration.sms.enabled)
            revoked += runtime.pendingRevocations()
            throw IllegalStateException("network offline")
        }
        runtime.configure(PushConfiguration())
        assertEquals(setOf(a, b), revoked.map { it.endpoint }.toSet())
        assertTrue(runtime.revocationCallbackFailed)
        assertFalse(runtime.snapshot().configuration.notifications.enabled)
        assertEquals(0, runtime.snapshot().queuedEvents)
    }

    @Test fun wideningScopeKeepsUnacknowledgedContentAndDoesNotRevokeItsRoute() = runBlocking {
        val runtime = ready()
        runtime.onNotification(notification)
        val revoked = mutableListOf<PushRevocation>()
        runtime.onRevocationAvailable = { revoked += runtime.pendingRevocations() }
        runtime.configure(configuration().let { it.copy(notifications = it.notifications.copy(
            scope = it.notifications.scope.copy(packageIds = setOf("test.app", "another.app")))) })
        assertEquals(1, runtime.snapshot().queuedEvents)
        assertTrue(revoked.isEmpty())
        val transport = TestPushTransport()
        assertEquals(2, runtime.deliver(transport).acknowledged)
    }

    @Test fun shorteningLocalRetentionNeverChangesTheCanonicalEventSentAgainAfterBufferExpiry() = runBlocking {
        val runtime = ready()
        runtime.onSms(sms)
        val transport = TestPushTransport { PushReceiptState.ACCEPTED }
        runtime.deliver(transport)
        val original = transport.deliveries.first().event
        runtime.configure(configuration().copy(retentionHours = 1))
        transport.state = { PushReceiptState.MISSING }
        runtime.deliver(transport)
        transport.state = { PushReceiptState.CONSUMED }
        runtime.deliver(transport)
        assertTrue(transport.deliveries.all { it.event == original })
    }

}

internal class TestPushClock(var epoch: Long = 1_800_000_000_000, var elapsed: Long = 0) : PushClock {
    override fun epochMs() = epoch
    override fun elapsedMs() = elapsed
}

/** Test double for state-machine tests only; no claim of Core/device integration. */
internal class TestPushTransport(var state: (PushDelivery) -> PushReceiptState = { PushReceiptState.CONSUMED }) : PushTransport {
    val deliveries = mutableListOf<PushDelivery>()
    val verified = mutableListOf<PushEndpoint>()
    var polls = 0
    var beforeVerify: (() -> Unit)? = null
    var receiptOverride: ((PushDelivery) -> PushReceipt)? = null
    override suspend fun verifyEndpoint(endpoint: PushEndpoint): EndpointVerification {
        verified += endpoint
        beforeVerify?.invoke()
        return EndpointVerification.VALID
    }
    override suspend fun send(delivery: PushDelivery, stillAuthorized: () -> Boolean): PushReceipt {
        check(stillAuthorized())
        deliveries += delivery
        return receiptOverride?.invoke(delivery) ?: PushReceipt(delivery.endpoint, delivery.event.eventId, "delivery", state(delivery))
    }
    override suspend fun poll(delivery: PushDelivery, deliveryId: String, stillAuthorized: () -> Boolean): PushReceipt {
        polls++
        return send(delivery, stillAuthorized)
    }
}
