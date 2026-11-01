package com.openandroidintelligence.mobile.push

import android.content.Context
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStore
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedOutboxPersistence
import com.openandroidintelligence.encrypted.store.FileEncryptedOutboxPersistence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey

class PushStateUnavailable : IllegalStateException("PUSH_STATE_UNAVAILABLE")
class PushStateWriterConflict : IllegalStateException("PUSH_STATE_WRITER_CONFLICT")

/** Reuses the existing AES-256-GCM envelope and fsync/rename ciphertext persistence. */
class EncryptedPushStateStore(
    private val installationId: String,
    persistence: EncryptedOutboxPersistence,
    key: SecretKey,
) {
    private val blob = AesGcmEncryptedBlobStore(persistence, key)
    private val lock = locks.computeIfAbsent(installationId) { Any() }

    init { require(installationId.isNotBlank() && installationId.length <= 512) }

    internal fun load(): PushStoredState = synchronized(lock) {
        try {
            val bytes = blob.readPlaintext() ?: return@synchronized PushStoredState(installationId)
            try {
                PushStateCodec.decode(bytes).also { check(it.installationId == installationId) }
            } finally { bytes.fill(0) }
        } catch (_: Exception) { throw PushStateUnavailable() }
    }

    internal fun save(expectedRevision: Long, next: PushStoredState): PushStoredState = synchronized(lock) {
        if (load().revision != expectedRevision) throw PushStateWriterConflict()
        check(next.installationId == installationId && expectedRevision < Long.MAX_VALUE)
        val committed = next.copy(revision = expectedRevision + 1)
        val bytes = PushStateCodec.encode(committed)
        try {
            check(bytes.size <= MAX_PUSH_STATE_BYTES) { "PUSH_STATE_CAPACITY_EXCEEDED" }
            blob.writePlaintext(bytes)
        } finally { bytes.fill(0) }
        committed
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** One runtime per installation; existing unreadable data never triggers a replacement key. */
        fun fromContext(context: Context, installationId: String): EncryptedPushStateStore {
            require(installationId.isNotBlank() && installationId.length <= 512)
            val digest = MessageDigest.getInstance("SHA-256").digest(installationId.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = File(context.noBackupFilesDir, "device-push-v1/$digest.bin")
            val alias = "oai_device_push_v1_$digest"
            if (file.isFile) {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!keys.containsAlias(alias)) throw PushStateUnavailable()
            }
            return EncryptedPushStateStore(
                installationId,
                FileEncryptedOutboxPersistence(file),
                AndroidKeystoreOutboxKeyProvider(alias).getOrCreate(),
            )
        }
    }
}

/** Versioned, bounded decoder. No best-effort reset of damaged or future state. */
internal object PushStateCodec {
    fun encode(state: PushStoredState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(4)
            out.text(state.installationId)
            out.writeLong(state.revision)
            out.writeLong(state.observedAtEpochMs)
            out.config(state.configuration)
            out.writeLong(state.expiredEvents)
            out.writeLong(state.revokedDeliveries)
            out.writeLong(state.capacityRejections)
            out.writeInt(state.events.size)
            state.events.forEach { queued ->
                val event = queued.event
                out.text(event.eventId)
                out.writeInt(event.kind.ordinal)
                out.writeLong(event.capturedAtEpochMs)
                out.writeLong(event.expiresAtEpochMs)
                out.text(event.payloadJson)
                out.writeLong(queued.retentionDeadlineEpochMs)
                out.strings(queued.smsMatchedKeywords)
                out.writeInt(queued.targets.size)
                queued.targets.forEach { (endpoint, deliveryId) -> out.endpoint(endpoint); out.optional(deliveryId) }
            }
            out.writeBoolean(state.smsCursor != null)
            state.smsCursor?.let { out.writeLong(it.providerId); out.writeLong(it.receivedAtEpochMs) }
            out.writeInt(state.seen.size)
            state.seen.forEach { (id, expiry) -> out.text(id); out.writeLong(expiry) }
            out.writeInt(state.revocations.size)
            state.revocations.forEach { (endpoint, ids) ->
                out.endpoint(endpoint)
                out.writeInt(ids.size)
                ids.forEach { out.text(it) }
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PushStoredState {
        require(bytes.size <= MAX_PUSH_STATE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            check(version in 1..4)
            val installation = input.text(2_048)
            val revision = input.nonnegative()
            val observed = input.nonnegative()
            val config = input.config(version)
            val expired = input.nonnegative()
            val revoked = input.nonnegative()
            val capacity = input.nonnegative()
            val events = List(input.count(4_096)) {
                val id = input.text(128)
                val kind = PushKind.entries[input.readInt()]
                val captured = input.nonnegative()
                val expires = input.nonnegative()
                check(expires >= captured)
                val payload = input.text(MAX_PUSH_EVENT_BYTES)
                val deadline = if (version >= 2) input.nonnegative() else expires
                check(deadline <= expires)
                val proof = if (version >= 4) input.strings().also { values ->
                    check(values.all { it.isNotBlank() && it.length <= 256 })
                } else emptySet()
                check(kind == PushKind.SMS || proof.isEmpty())
                val targets = linkedMapOf<PushEndpoint, String?>()
                repeat(input.count(MAX_PUSH_ENDPOINTS)) {
                    val target = input.endpoint()
                    check(!targets.containsKey(target))
                    targets[target] = input.optional()
                }
                check(targets.isNotEmpty())
                QueuedPushEvent(PushEvent(id, kind, captured, expires, payload), targets, deadline, proof)
            }
            check(events.map { it.event.eventId }.distinct().size == events.size)
            val smsCursor = if (input.readBoolean()) PushSmsCursor(input.readLong(), input.nonnegative()) else null
            val seen = linkedMapOf<String, Long>()
            repeat(input.count(MAX_PUSH_SEEN_EVENTS)) {
                val id = input.text(128)
                check(!seen.containsKey(id))
                seen[id] = input.nonnegative()
            }
            val revocations = linkedMapOf<PushEndpoint, Set<String>>()
            if (version >= 3) {
                var totalIds = 0
                repeat(input.count(MAX_PUSH_REVOCATION_ROUTES)) {
                    val endpoint = input.endpoint()
                    check(endpoint !in revocations)
                    val ids = List(input.count(MAX_PUSH_REVOCATION_IDS)) { input.text(128) }
                    totalIds += ids.size
                    check(ids.isNotEmpty() && ids.size == ids.toSet().size && totalIds <= MAX_PUSH_REVOCATION_IDS)
                    check(ids.all { it.isNotBlank() })
                    revocations[endpoint] = ids.toSet()
                }
            }
            check(input.available() == 0 && events.all { it.event.eventId in seen })
            PushStoredState(installation, revision, observed, config, events, seen, expired, revoked, capacity, smsCursor, revocations)
        }
    }

    private fun DataOutputStream.config(value: PushConfiguration) {
        writeBoolean(value.notifications.enabled)
        strings(value.notifications.scope.packageIds)
        writeBoolean(value.notifications.scope.includeContent)
        endpoints(value.notifications.endpoints)
        writeBoolean(value.sms.enabled)
        strings(value.sms.scope.senderAddresses)
        writeBoolean(value.sms.scope.allSenders)
        writeBoolean(value.sms.scope.includeContent)
        text(value.sms.scope.mode.name)
        strings(value.sms.scope.keywords)
        endpoints(value.sms.endpoints)
        writeInt(value.retentionHours)
        writeInt(value.maxEvents)
        writeInt(value.maxQueueBytes)
    }

    private fun DataInputStream.config(version: Int): PushConfiguration {
        val notifications = NotificationPushSettings(readBoolean(), NotificationPushScope(strings(), readBoolean()), endpoints())
        val smsEnabled = readBoolean()
        val senders = strings()
        val allSenders = readBoolean()
        val content = readBoolean()
        // Never apply the new ALL default to an old explicit false/empty sender scope.
        val mode = if (version >= 4) SmsScopeMode.valueOf(text(64))
            else if (allSenders) SmsScopeMode.ALL else SmsScopeMode.PHONE_NUMBERS
        val keywords = if (version >= 4) strings() else emptySet()
        val sms = SmsPushSettings(smsEnabled, SmsPushScope(senders, allSenders, content, mode, keywords), endpoints())
        return PushConfiguration(notifications, sms, readInt(), readInt(), readInt())
    }

    private fun DataOutputStream.endpoint(value: PushEndpoint) {
        text(value.coreInstanceId); text(value.accountScope); text(value.endpointId); writeLong(value.generation)
    }
    private fun DataInputStream.endpoint() = PushEndpoint(text(1_024), text(1_024), text(1_024), readLong())
    private fun DataOutputStream.endpoints(value: Set<PushEndpoint>) {
        writeInt(value.size); value.forEach { endpoint(it) }
    }
    private fun DataInputStream.endpoints() = List(count(MAX_PUSH_ENDPOINTS)) { endpoint() }.toSet().also {
        check(it.size <= MAX_PUSH_ENDPOINTS)
    }
    private fun DataOutputStream.strings(value: Set<String>) { writeInt(value.size); value.forEach { text(it) } }
    private fun DataInputStream.strings() = List(count(256)) { text(1_024) }.toSet()
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) text(value) }
    private fun DataInputStream.optional() = if (readBoolean()) text(512) else null
    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(); writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.text(max: Int): String {
        val size = readInt()
        check(size in 0..max && size <= available())
        return ByteArray(size).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }
    private fun DataInputStream.count(max: Int) = readInt().also { check(it in 0..max) }
    private fun DataInputStream.nonnegative() = readLong().also { check(it >= 0) }
}
