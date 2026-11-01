package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStore
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class PushStateCodecMigrationTest {
    private val endpoint = PushEndpoint("core", "account", "endpoint", 4)
    private val other = endpoint.copy(endpointId = "unaccepted-endpoint")
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val clock = TestPushClock()
    private val eventId = "push_" + "a".repeat(64)

    @Test fun v1ThroughV3EncryptedFilesUpgradeWithoutDroppingContentIdsReceiptsCursorOrExpiry() {
        for (version in 1..3) {
            val memory = InMemoryOutboxPersistence()
            AesGcmEncryptedBlobStore(memory, key).writePlaintext(legacy(version))
            val store = EncryptedPushStateStore("legacy-installation", memory, key)
            val before = store.load()
            assertEquals(1, before.events.size)
            assertEquals(setOf(endpoint, other), before.events.single().targets.keys)
            assertEquals("delivery_stable", before.events.single().targets[endpoint])
            assertNull(before.events.single().targets[other])
            assertEquals("{\"record\":{\"body\":\"legacy-secret\"}}", before.events.single().event.payloadJson)
            val runtime = DevicePushRuntime(store, PushSystemAccess { true }, clock)
            assertEquals(PushSmsCursor(7, clock.epoch - 1_000), runtime.smsCursor())
            assertEquals(1, runtime.snapshot().queuedEvents)
            val after = store.load()
            assertEquals(before.events, after.events)
            assertEquals(before.seen, after.seen)
            assertEquals(before.configuration, after.configuration)
            assertTrue(after.revocations.isEmpty())
            val upgraded = AesGcmEncryptedBlobStore(memory, key).readPlaintext()!!
            try { assertEquals(4, DataInputStream(ByteArrayInputStream(upgraded)).readInt()) }
            finally { upgraded.fill(0) }
        }
    }

    @Test fun v3PendingRevocationsArePreservedAlongWithUnackedReceiptsAndOneHourDeadline() {
        val memory = InMemoryOutboxPersistence()
        AesGcmEncryptedBlobStore(memory, key).writePlaintext(legacy(3, pendingRevocation = true))
        val store = EncryptedPushStateStore("legacy-installation", memory, key)
        val before = store.load()
        val expectedRevocations = mapOf(other to setOf("push_" + "b".repeat(64), "push_" + "c".repeat(64)))
        assertEquals(expectedRevocations, before.revocations)
        assertEquals("delivery_stable", before.events.single().targets[endpoint])
        assertEquals(clock.epoch + PUSH_HOUR_MS, before.events.single().retentionDeadlineEpochMs)
        val runtime = DevicePushRuntime(store, PushSystemAccess { true }, clock)
        val after = store.load()
        assertEquals(before.events, after.events)
        assertEquals(before.smsCursor, after.smsCursor)
        assertEquals(before.seen, after.seen)
        assertEquals(expectedRevocations, after.revocations)
        assertEquals(PushRevocation(other, expectedRevocations.getValue(other)), runtime.pendingRevocations().single())
        assertEquals(4, java.io.DataInputStream(java.io.ByteArrayInputStream(PushStateCodec.encode(after))).readInt())
        clock.epoch += PUSH_HOUR_MS
        clock.elapsed += PUSH_HOUR_MS
        runtime.maintain()
        assertEquals(0, runtime.snapshot().queuedEvents)
        val revoked = store.load().revocations
        assertEquals(setOf(eventId), revoked.getValue(endpoint))
        assertEquals(expectedRevocations.getValue(other) + eventId, revoked.getValue(other))
    }

    @Test fun everyOldExplicitFalseEmptyListRemainsDenyAllAndFutureVersionFailsClosed() {
        for (version in 1..3) {
            val decoded = PushStateCodec.decode(legacy(version, allSenders = false))
            assertFalse(decoded.configuration.sms.scope.allSenders)
            assertEquals(com.openandroidintelligence.kernel.SmsScopeMode.PHONE_NUMBERS, decoded.configuration.sms.scope.mode)
            assertTrue(decoded.configuration.sms.scope.senderAddresses.isEmpty())
            assertEquals(decoded, PushStateCodec.decode(PushStateCodec.encode(decoded)))
        }
        val future = PushStateCodec.encode(PushStoredState("future")).apply { this[3] = 5 }
        assertThrows(IllegalStateException::class.java) { PushStateCodec.decode(future) }
    }

    /** Exact prior binary layouts; deliberately independent of the current encoder. */
    private fun legacy(version: Int, allSenders: Boolean = true, pendingRevocation: Boolean = false): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            fun text(value: String) { val encoded = value.toByteArray(); output.writeInt(encoded.size); output.write(encoded) }
            fun route(value: PushEndpoint) { text(value.coreInstanceId); text(value.accountScope); text(value.endpointId); output.writeLong(value.generation) }
            output.writeInt(version)
            text("legacy-installation")
            output.writeLong(9)
            output.writeLong(clock.epoch - 100)
            output.writeBoolean(false) // notifications disabled
            output.writeInt(0) // package scope
            output.writeBoolean(true)
            output.writeInt(0) // notification endpoints
            output.writeBoolean(true) // SMS enabled
            output.writeInt(0) // senders
            output.writeBoolean(allSenders) // exact legacy flag, including false with an empty list
            output.writeBoolean(true) // content
            output.writeInt(2); route(endpoint); route(other)
            output.writeInt(72); output.writeInt(1_000); output.writeInt(4 * 1_024 * 1_024)
            repeat(3) { output.writeLong(0) } // counters
            output.writeInt(1)
            text(eventId); output.writeInt(PushKind.SMS.ordinal)
            output.writeLong(clock.epoch - 1_000); output.writeLong(clock.epoch + 72 * PUSH_HOUR_MS)
            text("{\"record\":{\"body\":\"legacy-secret\"}}")
            if (version >= 2) output.writeLong(clock.epoch + PUSH_HOUR_MS)
            output.writeInt(2)
            route(endpoint); output.writeBoolean(true); text("delivery_stable")
            route(other); output.writeBoolean(false)
            output.writeBoolean(true); output.writeLong(7); output.writeLong(clock.epoch - 1_000)
            output.writeInt(1); text(eventId); output.writeLong(clock.epoch + 72 * PUSH_HOUR_MS)
            if (version >= 3) {
                output.writeInt(if (pendingRevocation) 1 else 0)
                if (pendingRevocation) {
                    route(other)
                    output.writeInt(2); text("push_" + "b".repeat(64)); text("push_" + "c".repeat(64))
                }
            }
        }
        bytes.toByteArray()
    }
}
