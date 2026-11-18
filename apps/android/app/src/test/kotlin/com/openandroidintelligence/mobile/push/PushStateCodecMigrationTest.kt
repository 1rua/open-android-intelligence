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
            try { assertEquals(6, DataInputStream(ByteArrayInputStream(upgraded)).readInt()) }
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
        assertEquals(6, java.io.DataInputStream(java.io.ByteArrayInputStream(PushStateCodec.encode(after))).readInt())
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
        val future = PushStateCodec.encode(PushStoredState("future")).apply { this[3] = 7 }
        assertThrows(IllegalStateException::class.java) { PushStateCodec.decode(future) }
    }

    @Test fun largeNotificationScopesInIndependentV1ThroughV3LayoutsUpgradeWithoutLosingOtherState() {
        val packages = (1..1_025).mapTo(linkedSetOf()) { "com.legacy.selected$it" }
        for (version in 1..3) {
            val memory = InMemoryOutboxPersistence()
            AesGcmEncryptedBlobStore(memory, key).writePlaintext(legacy(
                version, pendingRevocation = version >= 3, notificationPackages = packages,
            ))
            val store = EncryptedPushStateStore("legacy-installation", memory, key)
            val before = store.load()
            assertTrue(before.configuration.notifications.enabled)
            assertEquals(packages, before.configuration.notifications.scope.packageIds)
            assertEquals(setOf(endpoint, other), before.configuration.notifications.endpoints)
            assertEquals(1, before.events.size)
            assertEquals("delivery_stable", before.events.single().targets[endpoint])
            assertNull(before.events.single().targets[other])
            assertEquals(PushSmsCursor(7, clock.epoch - 1_000), before.smsCursor)
            assertEquals(setOf(eventId), before.seen.keys)
            val committed = store.save(before.revision, before)
            assertEquals(before.copy(revision = before.revision + 1), committed)
            val reopened = EncryptedPushStateStore("legacy-installation", memory, key)
            assertEquals(committed, reopened.load())
            val upgraded = AesGcmEncryptedBlobStore(memory, key).readPlaintext()!!
            try {
                assertEquals(6, DataInputStream(ByteArrayInputStream(upgraded)).readInt())
            } finally { upgraded.fill(0) }
        }
    }

    @Test fun malformedNotificationCountsLengthsAndTruncationRejectEncryptedStateWithoutResettingIt() {
        val valid = legacy(3, notificationPackages = setOf("com.legacy.selected"))
        val input = DataInputStream(ByteArrayInputStream(valid))
        input.readInt()
        val installationBytes = input.readInt()
        assertEquals(installationBytes, input.skipBytes(installationBytes))
        input.readLong(); input.readLong(); input.readBoolean()
        val countOffset = valid.size - input.available()
        val lengthOffset = countOffset + Int.SIZE_BYTES
        val itemOffset = lengthOffset + Int.SIZE_BYTES
        val packageLength = "com.legacy.selected".toByteArray().size
        fun withInt(offset: Int, value: Int) = valid.copyOf().also {
            java.nio.ByteBuffer.wrap(it).putInt(offset, value)
        }
        val malformed = linkedMapOf(
            "negative count" to withInt(countOffset, -1),
            "count exceeds remaining bytes" to withInt(countOffset, Int.MAX_VALUE),
            "count disagrees with complete layout" to withInt(countOffset, 2),
            "missing package length" to valid.copyOf(lengthOffset),
            "truncated package length" to valid.copyOf(itemOffset - 1),
            "truncated package bytes" to valid.copyOf(itemOffset + packageLength - 1),
            "negative package length" to withInt(lengthOffset, -1),
            "oversized package length" to withInt(lengthOffset, 1_025),
            "blank package" to valid.copyOf().also { bytes ->
                " ".repeat(packageLength).toByteArray().copyInto(bytes, itemOffset)
            },
            "invalid package UTF-8" to valid.copyOf().also { it[itemOffset] = 0x80.toByte() },
            "truncated state tail" to valid.copyOf(valid.size - 1),
            "future version" to withInt(0, 7),
        )
        for ((label, bytes) in malformed) {
            assertThrows(label, Exception::class.java) { PushStateCodec.decode(bytes) }
            val memory = InMemoryOutboxPersistence()
            AesGcmEncryptedBlobStore(memory, key).writePlaintext(bytes)
            val ciphertext = memory.read()!!
            val store = EncryptedPushStateStore("legacy-installation", memory, key)
            assertThrows(label, PushStateUnavailable::class.java) { store.load() }
            assertThrows(label, PushStateUnavailable::class.java) {
                store.save(0, PushStoredState("legacy-installation"))
            }
            assertArrayEquals(label, ciphertext, memory.read())
            assertThrows(label, PushStateUnavailable::class.java) {
                EncryptedPushStateStore("legacy-installation", memory, key).load()
            }
        }
    }

    @Test fun 安装级存储复用原文件和原Keystore别名而不创建Remote空库() {
        val location=pushStateLocation(java.io.File("no-backup"),"legacy-installation")
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest("legacy-installation".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(java.io.File("no-backup","device-push-v1/$digest.bin"),location.file)
        assertEquals("oai_device_push_v1_$digest",location.keyAlias)
    }

    @Test fun v5旧未确认正文和回执升级后保留但不继承历史令牌授权() {
        val memory=InMemoryOutboxPersistence()
        AesGcmEncryptedBlobStore(memory,key).writePlaintext(legacyRemoteV5())
        val store=EncryptedPushStateStore("legacy-remote",memory,key)
        val old=store.load()
        assertEquals("legacy-secret",old.events.single().event.payloadJson)
        assertEquals("delivery_old",old.events.single().targets.values.single())
        val receipt=old.remoteReceipts.getValue("delivery_old")
        assertFalse(receipt.consumed);assertNull(receipt.readerKey)
        assertEquals("old-reader",receipt.legacyReaderKey)
        assertEquals("sha256:"+"a".repeat(64),receipt.legacyTokenHash)
        assertNull(receipt.endpoint.generation)
        assertEquals(old.configuration.notifications.endpoints.single(),receipt.endpoint)
        store.save(old.revision,old)
        assertEquals(old.copy(revision=old.revision+1),EncryptedPushStateStore("legacy-remote",memory,key).load())
    }

    @Test fun Remote目标没有公开端点租约且账号代次不能冒充generation() {
        val target=remoteTarget()
        assertNull(target.endpoint().generation)
        assertNull(target.copy(accountEpoch=Long.MAX_VALUE).endpoint().generation)
        assertThrows(IllegalArgumentException::class.java) { target.endpoint().copy(generation=target.accountEpoch+1) }
        assertThrows(IllegalArgumentException::class.java) { endpoint.copy(generation=null) }
        assertFalse(target.endpoint().replaces(target.endpoint()))
    }

    @Test fun 短信通知合法目标并集33到64个收件绑定保存重开后仍可继续写入() {
        for (count in listOf(33,64)) {
            val targets=(0 until count).map { remoteTarget().copy(sessionId="session-$it").endpoint() }
            val config=PushConfiguration(
                notifications=NotificationPushSettings(true,NotificationPushScope(setOf("test.app")),targets.take(32).toSet()),
                sms=SmsPushSettings(true,SmsPushScope(),targets.drop(32).toSet()))
            val bindings=targets.associateWith { RemotePushRecipientBinding("a".repeat(64),"sha256:"+"b".repeat(64)) }
            val memory=InMemoryOutboxPersistence()
            val store=EncryptedPushStateStore("recipient-union-$count",memory,key)
            val saved=store.save(0,PushStoredState("recipient-union-$count",configuration=config,remoteRecipients=bindings))
            val reopened=EncryptedPushStateStore("recipient-union-$count",memory,key)
            assertEquals(saved,reopened.load())
            val continued=reopened.save(saved.revision,saved.copy(capacityRejections=1))
            assertEquals(continued,EncryptedPushStateStore("recipient-union-$count",memory,key).load())
            assertEquals(count,continued.remoteRecipients.size)
        }
    }

    private fun remoteTarget()=RemotePushTarget("https://remote.example","account",3,"phone-client","sha256:"+"1".repeat(64),
        "desktop-host","sha256:"+"2".repeat(64),"phone-host","sha256:"+"3".repeat(64),"harness","workspace","session")

    /** 冻结旧v5布局，不能调用当前编码器生成兼容性夹具。 */
    private fun legacyRemoteV5(): ByteArray=ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            fun text(value:String) { val data=value.toByteArray();out.writeInt(data.size);out.write(data) }
            fun route() { val target=remoteTarget();text(target.origin);text(target.accountId)
                text(com.openandroidintelligence.gateway.schema.Json.sha256(target.encode()));out.writeLong(4)
                out.writeBoolean(true);text(com.openandroidintelligence.gateway.schema.Json.canonical(target.encode())) }
            out.writeInt(5);text("legacy-remote");out.writeLong(9);out.writeLong(clock.epoch)
            out.writeBoolean(true);out.writeInt(1);text("test.app");out.writeBoolean(true);out.writeInt(1);route()
            out.writeBoolean(false);out.writeInt(0);out.writeBoolean(true);out.writeBoolean(true)
            text("ALL");out.writeInt(0);out.writeInt(0)
            out.writeInt(72);out.writeInt(1000);out.writeInt(4*1024*1024)
            repeat(3) {out.writeLong(0)}
            out.writeInt(1);text(eventId);out.writeInt(PushKind.NOTIFICATION.ordinal)
            out.writeLong(clock.epoch);out.writeLong(clock.epoch+PUSH_HOUR_MS);text("legacy-secret")
            out.writeLong(clock.epoch+PUSH_HOUR_MS);out.writeInt(0);out.writeInt(1);route()
            out.writeBoolean(true);text("delivery_old")
            out.writeBoolean(false);out.writeInt(1);text(eventId);out.writeLong(clock.epoch+PUSH_HOUR_MS)
            out.writeInt(0);out.writeInt(1)
            text("delivery_old");route();text(eventId);text("sha256:"+"b".repeat(64));text("sha256:"+"a".repeat(64))
            out.writeLong(clock.epoch+PUSH_HOUR_MS);out.writeBoolean(false);out.writeBoolean(true);text("old-reader");text("accepted")
        }
        bytes.toByteArray()
    }

    /** Exact prior binary layouts; deliberately independent of the current encoder. */
    private fun legacy(version: Int, allSenders: Boolean = true, pendingRevocation: Boolean = false,
                       notificationPackages: Set<String> = emptySet()): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            fun text(value: String) { val encoded = value.toByteArray(); output.writeInt(encoded.size); output.write(encoded) }
            fun route(value: PushEndpoint) { text(value.coreInstanceId); text(value.accountScope); text(value.endpointId); output.writeLong(value.generation!!) }
            output.writeInt(version)
            text("legacy-installation")
            output.writeLong(9)
            output.writeLong(clock.epoch - 100)
            output.writeBoolean(notificationPackages.isNotEmpty())
            output.writeInt(notificationPackages.size)
            notificationPackages.forEach(::text)
            output.writeBoolean(true)
            output.writeInt(if (notificationPackages.isEmpty()) 0 else 2)
            if (notificationPackages.isNotEmpty()) { route(endpoint); route(other) }
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
