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
            val location = pushStateLocation(context.noBackupFilesDir, installationId)
            // Remote target 是路由身份的升级；本机 Push 配置、队列和未确认回执仍属于同一安装数据。
            // 复用既有路径与 Keystore alias，版本化正文由 PushStateCodec 显式升级。
            val file = location.file
            val alias = location.keyAlias
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
            out.writeInt(6)
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
            out.writeInt(state.remoteReceipts.size)
            state.remoteReceipts.values.forEach { receipt ->
                out.text(receipt.deliveryId); out.endpoint(receipt.endpoint); out.text(receipt.eventId)
                out.text(receipt.payloadDigest); out.writeLong(receipt.expiresAt); out.writeBoolean(receipt.consumed);out.optional(receipt.readerKey);out.text(receipt.dispatchState)
                out.optional(receipt.legacyTokenHash); out.optional(receipt.legacyReaderKey)
                out.writeInt(receipt.kind?.ordinal ?: -1)
            }
            // 短信和通知各有32个合法目标，收件绑定覆盖二者并集。
            require(state.remoteRecipients.size <= MAX_PUSH_RECIPIENTS)
            out.writeInt(state.remoteRecipients.size)
            state.remoteRecipients.forEach { (endpoint,binding) ->
                out.endpoint(endpoint);out.text(binding.principalKey);out.text(binding.configurationDigest);out.writeBoolean(binding.active)
            }
            out.writeLong(state.authorizationGeneration)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PushStoredState {
        require(bytes.size <= MAX_PUSH_STATE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            check(version in 1..6)
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
                    val target = input.endpoint(version)
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
                    val endpoint = input.endpoint(version)
                    check(endpoint !in revocations)
                    val ids = List(input.count(MAX_PUSH_REVOCATION_IDS)) { input.text(128) }
                    totalIds += ids.size
                    check(ids.isNotEmpty() && ids.size == ids.toSet().size && totalIds <= MAX_PUSH_REVOCATION_IDS)
                    check(ids.all { it.isNotBlank() })
                    revocations[endpoint] = ids.toSet()
                }
            }
            val receipts=linkedMapOf<String,RemotePushReceiptRecord>()
            if (version >= 5) repeat(input.count(MAX_PUSH_REVOCATION_IDS)) {
                val id=input.text(128); val endpoint=input.endpoint(version); val event=input.text(128)
                val digest=input.text(128)
                // v5 的哈希曾对应进入普通聊天历史的 bearer；读取旧布局但不继承收件许可。
                val legacyToken=if (version == 5) input.text(128) else null
                val expires=input.nonnegative(); val consumed=input.readBoolean()
                val reader=input.optional(); val dispatch=input.text(32)
                val receipt=RemotePushReceiptRecord(id,endpoint,event,digest,expires,consumed,
                    if (version == 5) null else reader,dispatch,
                    if (version >= 6) input.optional() else legacyToken,
                    if (version >= 6) input.optional() else reader,
                    if (version >= 6) input.readInt().let { if (it == -1) null else PushKind.entries[it] }
                    else events.firstOrNull { it.event.eventId == event }?.event?.kind)
                check(id !in receipts && endpoint.remoteTarget != null)
                check(receipt.dispatchState in setOf("prepared","dispatched","accepted","outcome_unknown"))
                receipts[id]=receipt
            }
            val recipients=linkedMapOf<PushEndpoint,RemotePushRecipientBinding>()
            if (version >= 6) repeat(input.count(MAX_PUSH_RECIPIENTS)) {
                val endpoint=input.endpoint(version)
                val principal=input.text(128);val digest=input.text(128)
                check(endpoint.remoteTarget != null && endpoint !in recipients && Regex("[0-9a-f]{64}").matches(principal) &&
                    Regex("sha256:[0-9a-f]{64}").matches(digest))
                recipients[endpoint]=RemotePushRecipientBinding(principal,digest,input.readBoolean())
            }
            val authorizationGeneration=if (version >= 6) input.nonnegative() else 0
            check(input.available() == 0 && events.all { it.event.eventId in seen })
            PushStoredState(installation, revision, observed, config, events, seen, expired, revoked, capacity, smsCursor, revocations,receipts,recipients,authorizationGeneration)
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
        val notifications = NotificationPushSettings(readBoolean(), NotificationPushScope(notificationPackages(), readBoolean()), endpoints(version))
        val smsEnabled = readBoolean()
        val senders = strings()
        val allSenders = readBoolean()
        val content = readBoolean()
        // Never apply the new ALL default to an old explicit false/empty sender scope.
        val mode = if (version >= 4) SmsScopeMode.valueOf(text(64))
            else if (allSenders) SmsScopeMode.ALL else SmsScopeMode.PHONE_NUMBERS
        val keywords = if (version >= 4) strings() else emptySet()
        val sms = SmsPushSettings(smsEnabled, SmsPushScope(senders, allSenders, content, mode, keywords), endpoints(version))
        return PushConfiguration(notifications, sms, readInt(), readInt(), readInt())
    }

    private fun DataOutputStream.endpoint(value: PushEndpoint) {
        text(value.coreInstanceId); text(value.accountScope); text(value.endpointId); writeLong(value.generation ?: 0)
        writeBoolean(value.remoteTarget != null)
        value.remoteTarget?.let { text(com.openandroidintelligence.gateway.schema.Json.canonical(it.encode())) }
    }
    private fun DataInputStream.endpoint(version: Int): PushEndpoint {
        val core=text(1_024);val account=text(1_024);val id=text(1_024);val generation=readLong()
        val remote=if (version >= 5 && readBoolean()) RemotePushTarget.decode(com.openandroidintelligence.gateway.schema.Json.parse(text(16_384))) else null
        // Remote 没有公开端点租约；v5 的账号代次加一只作为旧布局读取，不再伪装租约。
        if (version >= 6 && remote != null) check(generation == 0L)
        return PushEndpoint(core,account,id,if (remote != null) null else generation,remote)
    }
    private fun DataOutputStream.endpoints(value: Set<PushEndpoint>) {
        writeInt(value.size); value.forEach { endpoint(it) }
    }
    private fun DataInputStream.endpoints(version: Int) = List(count(MAX_PUSH_ENDPOINTS)) { endpoint(version) }.toSet().also {
        check(it.size <= MAX_PUSH_ENDPOINTS)
    }
    private fun DataOutputStream.strings(value: Set<String>) { writeInt(value.size); value.forEach { text(it) } }
    private fun DataInputStream.strings() = List(count(256)) { text(1_024) }.toSet()
    private fun DataInputStream.notificationPackages(): Set<String> {
        val size = readInt()
        // Each nonblank package needs its length prefix and at least one UTF-8 byte.
        // Bound malformed counts by the actual input, without a product app-count limit.
        check(size in 0..(available() / (Int.SIZE_BYTES + 1)))
        return buildSet {
            repeat(size) {
                val packageId = text(1_024)
                check(packageId.isNotBlank())
                add(packageId)
            }
        }
    }
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

/** 安装级配置和队列的固定位置；Remote 路由变化不能创建新的空库。 */
internal data class PushStateLocation(val file: File, val keyAlias: String)
internal fun pushStateLocation(noBackupFilesDir: File, installationId: String): PushStateLocation {
    require(installationId.isNotBlank() && installationId.length <= 512)
    val digest=MessageDigest.getInstance("SHA-256").digest(installationId.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return PushStateLocation(File(noBackupFilesDir,"device-push-v1/$digest.bin"),"oai_device_push_v1_$digest")
}
