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

    @Test fun v1AndV2EncryptedFilesUpgradeWithoutDroppingContentIdsReceiptsCursorOrExpiry() {
        for (version in 1..2) {
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
            try { assertEquals(3, DataInputStream(ByteArrayInputStream(upgraded)).readInt()) }
            finally { upgraded.fill(0) }
        }
    }

    /** Exact prior binary layouts; deliberately independent of the current encoder. */
    private fun legacy(version: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
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
            output.writeBoolean(true) // all senders
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
        }
        bytes.toByteArray()
    }
}
