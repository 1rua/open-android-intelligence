package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStore
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class PushRevocationTest {
    private val endpoint = PushEndpoint("core", "account", "receiver", 1)
    private val other = endpoint.copy(endpointId = "other-receiver")
    private val key = SecretKeySpec(ByteArray(32) { (it + 10).toByte() }, "AES")
    private val clock = TestPushClock()
    private fun configuration(endpoints: Set<PushEndpoint> = setOf(endpoint)) = PushConfiguration(
        NotificationPushSettings(true, NotificationPushScope(setOf("test.app")), endpoints),
        SmsPushSettings(true, SmsPushScope(allSenders = true), endpoints),
    )
    private fun runtime(persistence: InMemoryOutboxPersistence = InMemoryOutboxPersistence()) =
        DevicePushRuntime(EncryptedPushStateStore("revocation-installation", persistence, key), PushSystemAccess { true }, clock)
    private fun ready(persistence: InMemoryOutboxPersistence = InMemoryOutboxPersistence()) = runtime(persistence).also {
        it.configure(configuration()); it.onLoginStateChanged(true)
    }
    private fun sms(id: Long = 1) = PushSms(id, clock.epoch, "sender", "sms-sensitive-body-$id")
    private fun notification() = PushNotification("key", "test.app", clock.epoch, "title", "notification-sensitive-body")

    @Test fun disablingSmsRevokesOnlyItsEventAtTheSharedEndpointAndKeepsNotificationReceipt() = runBlocking {
        val runtime = ready()
        val sms = runtime.onSms(sms()).eventId!!
        val notification = runtime.onNotification(notification()).eventId!!
        val transport = TestPushTransport { PushReceiptState.ACCEPTED }
        runtime.deliver(transport)
        runtime.configure(configuration().copy(sms = configuration().sms.copy(enabled = false)))
        assertEquals(listOf(PushRevocation(endpoint, setOf(sms))), runtime.pendingRevocations())
        assertEquals(1, runtime.snapshot().pendingDeliveries)
        transport.state = { PushReceiptState.CONSUMED }
        val before = transport.deliveries.size
        assertEquals(1, runtime.deliver(transport).acknowledged)
        assertEquals(listOf(notification), transport.deliveries.drop(before).map { it.event.eventId })
        assertEquals(1, transport.polls)
    }

    @Test fun metadataAndRemovedBodyCommitTogetherAndRecoverWithNoLoginOrSendQueue() {
        val memory = InMemoryOutboxPersistence()
        val runtime = ready(memory)
        val eventId = runtime.onSms(sms()).eventId!!
        var notifications = 0
        runtime.onRevocationAvailable = {
            notifications++
            // Reopen the SAME encrypted blob inside the callback: the record is already durable.
            val persisted = EncryptedPushStateStore("revocation-installation", memory, key).load()
            assertTrue(persisted.events.isEmpty())
            assertEquals(setOf(eventId), persisted.revocations[endpoint])
        }
        runtime.configure(PushConfiguration())
        assertEquals(1, notifications)
        val plaintext = AesGcmEncryptedBlobStore(memory, key).readPlaintext()!!
        try {
            assertFalse(plaintext.toString(Charsets.UTF_8).contains("sms-sensitive-body"))
            assertTrue(plaintext.toString(Charsets.UTF_8).contains(eventId))
        } finally { plaintext.fill(0) }
        val reopened = runtime(memory)
        assertFalse(reopened.snapshot().hasLoggedInAccount)
        assertEquals(0, reopened.snapshot().queuedEvents)
        assertEquals(listOf(PushRevocation(endpoint, setOf(eventId))), reopened.pendingRevocations())
        assertTrue(reopened.acknowledgeRevocation(reopened.pendingRevocations().single()))
        assertTrue(runtime(memory).pendingRevocations().isEmpty())
        assertFalse(reopened.acknowledgeRevocation(PushRevocation(endpoint, setOf(eventId))))
    }

    @Test fun acknowledgingAnOldBatchKeepsNewRevocationsAndOtherEndpointGenerations() {
        val runtime = ready()
        val first = runtime.onSms(sms()).eventId!!
        runtime.configure(configuration().copy(sms = configuration().sms.copy(enabled = false)))
        val oldBatch = runtime.pendingRevocations().single()
        runtime.configure(configuration())
        val second = runtime.onSms(sms(2)).eventId!!
        runtime.configure(configuration().copy(sms = configuration().sms.copy(enabled = false)))
        assertEquals(setOf(first, second), runtime.pendingRevocations().single().eventIds)
        assertFalse(runtime.acknowledgeRevocation(PushRevocation(endpoint.copy(generation = 2), setOf(first, second))))
        assertTrue(runtime.acknowledgeRevocation(oldBatch))
        assertEquals(listOf(PushRevocation(endpoint, setOf(second))), runtime.pendingRevocations())
    }

    @Test fun removingOneEndpointKeepsOtherTargetsAndNeverRequestsRouteWideCleanup() {
        val runtime = ready()
        runtime.configure(configuration(setOf(endpoint, other)))
        val event = runtime.onSms(sms()).eventId!!
        runtime.configure(configuration(setOf(other)))
        assertEquals(listOf(PushRevocation(endpoint, setOf(event))), runtime.pendingRevocations())
        assertEquals(1, runtime.snapshot().pendingDeliveries)
        assertTrue(runtime.pendingRevocations().all { it.eventIds.isNotEmpty() })
    }

    @Test fun narrowingOneSenderRangeKeepsTheOtherSenderAndRevokesOnlyDisallowedEvent() {
        val runtime = ready()
        val first = runtime.onSms(sms().copy(senderAddress = "one")).eventId!!
        val second = runtime.onSms(sms(2).copy(senderAddress = "two")).eventId!!
        runtime.configure(configuration().let { it.copy(sms = it.sms.copy(scope = SmsPushScope(setOf("two")))) })
        assertEquals(setOf(first), runtime.pendingRevocations().single().eventIds)
        assertFalse(second in runtime.pendingRevocations().single().eventIds)
        assertEquals(1, runtime.snapshot().queuedEvents)
    }

    @Test fun cleanupIsBatchedWithoutDroppingIdsAndMetadataSurvivesRepeatedRestart() {
        val memory = InMemoryOutboxPersistence()
        val runtime = ready(memory)
        val ids = (1L..140).map { runtime.onSms(sms(it)).eventId!! }.toSet()
        runtime.configure(PushConfiguration())
        var reopened = runtime(memory)
        val batches = reopened.pendingRevocations()
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.eventIds.size <= 128 })
        assertEquals(ids, batches.flatMap { it.eventIds }.toSet())
        reopened.acknowledgeRevocation(batches.first())
        reopened = runtime(memory)
        assertEquals(batches.last().eventIds, reopened.pendingRevocations().single().eventIds)
    }

    @Test fun expiredContentStillLeavesDurableCleanupMetadataUntilAuthenticatedAck() {
        val runtime = ready()
        val id = runtime.onSms(sms()).eventId!!
        runtime.onLoginStateChanged(false)
        clock.epoch += 73 * PUSH_HOUR_MS
        clock.elapsed += 73 * PUSH_HOUR_MS
        assertEquals(0, runtime.maintain().queuedEvents)
        assertEquals(listOf(PushRevocation(endpoint, setOf(id))), runtime.pendingRevocations())
    }
}
