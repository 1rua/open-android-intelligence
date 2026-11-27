package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class DevicePushCycleTest {
    private val a = PushEndpoint("core-a", "account-a", "receiver-a", 1)
    private val b = PushEndpoint("core-b", "account-b", "receiver-b", 1)
    private val key = SecretKeySpec(ByteArray(32) { (it + 20).toByte() }, "AES")
    private val clock = TestPushClock()
    private fun configuration(endpoints: Set<PushEndpoint>) = PushConfiguration(
        NotificationPushSettings(true, NotificationPushScope(setOf("test.app")), endpoints),
        SmsPushSettings(true, SmsPushScope(allSenders = true), endpoints),
    )
    private fun runtime(persistence: InMemoryOutboxPersistence) = DevicePushRuntime(
        EncryptedPushStateStore("cycle-installation", persistence, key), PushSystemAccess { true }, clock,
    )

    @Test fun failedCoreRevocationKeepsItsRecordAndDoesNotBlockOtherCoreCollectionOrDeliveryAfterRestart() = runBlocking {
        val persistence = InMemoryOutboxPersistence()
        var current = runtime(persistence).apply {
            configure(configuration(setOf(a, b)))
            onLoginStateChanged(true)
        }
        val original = current.onSms(PushSms(1, clock.epoch, "sender", "old body")).eventId!!
        current.deliver(TestPushTransport { PushReceiptState.ACCEPTED })
        current.configure(configuration(setOf(b)))
        val cleanup = PushRevocation(a, setOf(original))
        assertEquals(listOf(cleanup), current.pendingRevocations())
        val transport = TestPushTransport()
        val expected = mutableSetOf(original)
        var notificationCaptures = 0
        var smsCaptures = 0
        var failures = 0
        val revocations = mutableListOf<PushRevocation>()
        repeat(2) { cycle ->
            assertFalse(runDevicePushCycle(current, transport,
                revoke = { revocations += it; throw IllegalStateException("core-a offline") },
                captureActiveNotifications = {
                    notificationCaptures++
                    val captured = current.onNotification(PushNotification("notification-$cycle", "test.app", clock.epoch, "title", "body"))
                    assertEquals(PushCaptureStatus.ENQUEUED, captured.status)
                    expected += captured.eventId!!
                },
                captureSmsPage = {
                    smsCaptures++
                    val captured = current.onSms(PushSms(cycle + 2L, clock.epoch, "sender", "new body"))
                    assertEquals(PushCaptureStatus.ENQUEUED, captured.status)
                    expected += captured.eventId!!
                    false
                },
                onRevocationFailure = { failures++ },
            ))
            assertEquals("Notification capture must continue for Core B", cycle + 1, notificationCaptures)
            assertEquals("SMS capture must continue for Core B", cycle + 1, smsCaptures)
            assertEquals(0, current.snapshot().pendingDeliveries)
            assertEquals(listOf(cleanup), current.pendingRevocations())
            current = runtime(persistence).also { it.onLoginStateChanged(true) }
            assertEquals(setOf(b), current.snapshot().configuration.sms.endpoints)
            assertEquals(listOf(cleanup), current.pendingRevocations())
        }
        assertEquals(2, failures)
        assertEquals(listOf(cleanup, cleanup), revocations)
        assertEquals(setOf(b), transport.verified.toSet())
        assertEquals(setOf(b), transport.deliveries.map { it.endpoint }.toSet())
        assertEquals(expected, transport.deliveries.map { it.event.eventId }.toSet())
        assertEquals(expected.size, transport.deliveries.size)
        assertEquals(1, transport.polls)

        assertTrue(runDevicePushCycle(current, transport,
            revoke = { assertEquals(cleanup, it) },
            captureActiveNotifications = { notificationCaptures++ },
            captureSmsPage = { smsCaptures++; false },
            onRevocationFailure = { fail("Core A cleanup must recover") },
        ))
        assertTrue(runtime(persistence).pendingRevocations().isEmpty())
        assertEquals(expected.size, transport.deliveries.size)
        assertEquals(3, notificationCaptures)
        assertEquals(3, smsCaptures)
    }

    @Test fun cancelledCoreRequestDoesNotCancelOtherCoreWhenTheCycleIsStillActive() = runBlocking {
        val persistence = InMemoryOutboxPersistence()
        val current = runtime(persistence).apply {
            configure(configuration(setOf(a, b)))
            onLoginStateChanged(true)
        }
        val eventId = current.onSms(PushSms(1, clock.epoch, "sender", "body")).eventId!!
        current.configure(configuration(setOf(b)))
        val transport = TestPushTransport()
        var captures = 0
        var failures = 0
        assertFalse(runDevicePushCycle(current, transport,
            revoke = { throw CancellationException("PUSH_CONNECTION_CHANGED") },
            captureActiveNotifications = { captures++ },
            captureSmsPage = { captures++; false },
            onRevocationFailure = { failures++ },
        ))
        assertEquals(2, captures)
        assertEquals(1, failures)
        assertEquals(listOf(b), transport.deliveries.map { it.endpoint })
        assertEquals(listOf(PushRevocation(a, setOf(eventId))), runtime(persistence).pendingRevocations())
    }

    @Test fun stoppingTheWholeCycleStillCancelsCleanupAndNeverStartsCollectionOrDelivery() = runBlocking {
        val persistence = InMemoryOutboxPersistence()
        val current = runtime(persistence).apply {
            configure(configuration(setOf(a, b)))
            onLoginStateChanged(true)
        }
        val eventId = current.onSms(PushSms(1, clock.epoch, "sender", "body")).eventId!!
        current.configure(configuration(setOf(b)))
        val entered = CompletableDeferred<Unit>()
        val suspended = CompletableDeferred<Unit>()
        val transport = TestPushTransport()
        var captures = 0
        var failures = 0
        val cycle = async {
            runDevicePushCycle(current, transport,
                revoke = { entered.complete(Unit); suspended.await() },
                captureActiveNotifications = { captures++ },
                captureSmsPage = { captures++; false },
                onRevocationFailure = { failures++ },
            )
        }
        entered.await()
        cycle.cancel()
        withTimeout(1_000) { cycle.join() }
        assertTrue(cycle.isCancelled)
        assertEquals(0, captures)
        assertEquals(0, failures)
        assertTrue(transport.deliveries.isEmpty())
        assertEquals(listOf(PushRevocation(a, setOf(eventId))), runtime(persistence).pendingRevocations())
        assertEquals(1, current.snapshot().pendingDeliveries)
    }
}
