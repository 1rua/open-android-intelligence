package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.FileEncryptedOutboxPersistence
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationPushScopePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = PushEndpoint("core-a", "account-a", "endpoint-a", 1)
    private val b = PushEndpoint("core-b", "account-b", "endpoint-b", 2)
    private val key = SecretKeySpec(ByteArray(32) { 17 }, "AES")

    @Test fun largeScopesKeepEncryptedQueueReceiptsCursorExpiryAndRevocationsAcrossFileRestarts() = runBlocking {
        for (count in listOf(257, 1_025, 2_049)) {
            val packages = (1..count).mapTo(linkedSetOf()) { "com.example.selected$it" }
            val clock = TestPushClock()
            val file = temporary.root.resolve("notification-$count.bin")
            fun store() = EncryptedPushStateStore("notification-$count", FileEncryptedOutboxPersistence(file), key)
            val original = DevicePushRuntime(store(), PushSystemAccess { true }, clock)
            val configuration = PushConfiguration(
                notifications = NotificationPushSettings(true, NotificationPushScope(packages), setOf(a, b)),
                sms = SmsPushSettings(true, SmsPushScope(setOf("sender")), setOf(a, b)),
            )
            original.configure(configuration)
            original.onLoginStateChanged(true)
            val cursor = PushSmsCursor(73, clock.epoch - 100)
            assertTrue(original.advanceSmsCursor(cursor, configuration.sms.scope))

            // Create real pending cleanup, then restore the full selected application set.
            val removed = original.onNotification(PushNotification("removed", packages.first(), clock.epoch))
            assertEquals(PushCaptureStatus.ENQUEUED, removed.status)
            original.configure(configuration.copy(notifications = configuration.notifications.copy(
                scope = NotificationPushScope(packages - packages.first()),
            )))
            original.configure(configuration)
            val notification = PushNotification("last-selected", packages.last(), clock.epoch, "title", "encrypted body $count")
            val captured = original.onNotification(notification)
            assertEquals(PushCaptureStatus.ENQUEUED, captured.status)
            val receipts = mapOf(a to "receipt-a-$count", b to "receipt-b-$count")
            val transport = TestPushTransport().apply {
                receiptOverride = { delivery -> PushReceipt(delivery.endpoint, delivery.event.eventId,
                    receipts.getValue(delivery.endpoint), PushReceiptState.ACCEPTED) }
            }
            assertEquals(2, original.deliver(transport).accepted)
            val retainedConfiguration = configuration.copy(retentionHours = 1)
            original.configure(retainedConfiguration)

            val before = store().load()
            assertEquals(retainedConfiguration, before.configuration)
            assertEquals(packages, before.configuration.notifications.scope.packageIds)
            assertEquals(1, before.events.size)
            val queued = before.events.single()
            assertEquals(captured.eventId, queued.event.eventId)
            assertEquals(PushKind.NOTIFICATION, queued.event.kind)
            assertTrue(queued.event.payloadJson.contains("encrypted body $count"))
            assertEquals(receipts, queued.targets)
            assertEquals(clock.epoch + 72 * PUSH_HOUR_MS, queued.event.expiresAtEpochMs)
            assertEquals(clock.epoch + PUSH_HOUR_MS, queued.retentionDeadlineEpochMs)
            assertEquals(mapOf(removed.eventId!! to queued.event.expiresAtEpochMs,
                captured.eventId!! to queued.event.expiresAtEpochMs), before.seen)
            assertEquals(cursor, before.smsCursor)
            val pendingCleanup = setOf(removed.eventId!!)
            assertEquals(mapOf(a to pendingCleanup, b to pendingCleanup), before.revocations)
            assertEquals(2L, before.revokedDeliveries)
            assertEquals(0L, before.expiredEvents)
            assertEquals(0L, before.capacityRejections)
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.contentEquals(PushStateCodec.encode(before)))
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains("encrypted body $count"))
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains(packages.last()))

            // New persistence, store and runtime instances must recover the actual encrypted file.
            val reopenedStore = store()
            assertEquals(before, reopenedStore.load())
            val restarted = DevicePushRuntime(reopenedStore, PushSystemAccess { true }, clock)
            assertEquals(before, reopenedStore.load())
            assertFalse(restarted.snapshot().hasLoggedInAccount)
            assertEquals(1, restarted.snapshot().queuedEvents)
            assertEquals(2, restarted.snapshot().pendingDeliveries)
            assertEquals(cursor, restarted.smsCursor())
            restarted.onLoginStateChanged(true)
            assertEquals(PushCaptureStatus.DUPLICATE, restarted.onNotification(notification).status)
            assertEquals(PushCaptureStatus.OUT_OF_SCOPE, restarted.onNotification(
                notification.copy(notificationKey = "unselected", packageName = "com.example.unselected"),
            ).status)
            assertEquals(before, store().load())

            clock.epoch += PUSH_HOUR_MS - 1
            clock.elapsed += PUSH_HOUR_MS - 1
            restarted.maintain()
            assertEquals(before.events, store().load().events)
            clock.epoch += 1
            clock.elapsed += 1
            restarted.maintain()
            val expired = store().load()
            assertTrue(expired.events.isEmpty())
            assertEquals(1L, expired.expiredEvents)
            assertEquals(before.seen, expired.seen)
            assertEquals(before.smsCursor, expired.smsCursor)
            assertEquals(retainedConfiguration, expired.configuration)
            assertEquals(mapOf(a to (pendingCleanup + captured.eventId!!),
                b to (pendingCleanup + captured.eventId!!)), expired.revocations)
            val afterExpiry = DevicePushRuntime(store(), PushSystemAccess { true }, clock)
            assertEquals(0, afterExpiry.snapshot().queuedEvents)
            assertEquals(4, afterExpiry.snapshot().pendingRevocations)
            assertEquals(expired, store().load())
        }
    }

    @Test fun lastOf2049SelectedApplicationsStillEnqueuesAfterRestartAndUnselectedApplicationCannot() {
        val packages = (1..2_049).mapTo(linkedSetOf()) { "com.example.selected$it" }
        val clock = TestPushClock()
        val file = temporary.root.resolve("scope.bin")
        fun store() = EncryptedPushStateStore("notification-scope", FileEncryptedOutboxPersistence(file), key)
        val configuration = PushConfiguration(notifications = NotificationPushSettings(
            true, NotificationPushScope(packages), setOf(a, b),
        ))
        DevicePushRuntime(store(), PushSystemAccess { true }, clock).configure(configuration)
        val restarted = DevicePushRuntime(store(), PushSystemAccess { true }, clock)
        assertEquals(configuration, restarted.snapshot().configuration)
        restarted.onLoginStateChanged(true)
        val selected = PushNotification("selected", packages.last(), clock.epoch, body = "last selected app")
        val result = restarted.onNotification(selected)
        assertEquals(PushCaptureStatus.ENQUEUED, result.status)
        val before = store().load()
        assertEquals(result.eventId, before.events.single().event.eventId)
        assertTrue(before.events.single().event.payloadJson.contains(packages.last()))
        assertEquals(setOf(a, b), before.events.single().targets.keys)
        assertEquals(PushCaptureStatus.OUT_OF_SCOPE, restarted.onNotification(
            selected.copy(notificationKey = "unselected", packageName = "com.example.unselected"),
        ).status)
        assertEquals(before, store().load())
    }
}
