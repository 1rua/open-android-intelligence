package com.openandroidintelligence.encrypted.store

import com.openandroidintelligence.core.model.DurableEvent
import com.openandroidintelligence.core.model.NotificationContent
import com.openandroidintelligence.core.model.NotificationLoss
import com.openandroidintelligence.core.model.NotificationMetadata
import com.openandroidintelligence.core.model.NotificationOutbox
import com.openandroidintelligence.core.model.NotificationRecordV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Persistence seam. Implementations must point at app-private no-backup data. */
interface EncryptedOutboxPersistence {
    fun read(): ByteArray?
    fun write(ciphertext: ByteArray)
    fun clear()
}

class InMemoryOutboxPersistence : EncryptedOutboxPersistence {
    var bytes: ByteArray? = null
        private set

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(ciphertext: ByteArray) {
        bytes = ciphertext.copyOf()
    }

    override fun clear() {
        bytes?.fill(0)
        bytes = null
    }
}

/** Ciphertext-only file adapter suitable for a Context.noBackupFilesDir child. */
class FileEncryptedOutboxPersistence(private val file: File) : EncryptedOutboxPersistence {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(ciphertext: ByteArray) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: file.absoluteFile.parentFile ?: error("outbox file has no parent")
        val temporary = File(parent, "${file.name}.tmp")
        java.io.FileOutputStream(temporary).use { output -> output.write(ciphertext); output.fd.sync() }
        check(temporary.renameTo(file)) { "unable to atomically persist notification outbox" }
    }

    override fun clear() {
        check(!file.isFile || file.delete()) { "unable to remove encrypted outbox" }
        File(file.parentFile, "${file.name}.tmp").let { check(!it.exists() || it.delete()) }
    }
}

fun interface EventAckVerifier {
    fun verify(eventId: String, eventAckWire: ByteArray): Boolean
}

fun interface EventIdSource {
    fun nextId(): String
}

class OutboxAckRejected(message: String) : IllegalArgumentException(message)
class OutboxCorrupted(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Durable event outbox. Records are serialized into an authenticated
 * AES-GCM envelope before the persistence seam sees them; restart recovery
 * therefore never requires plaintext notification fields on disk.
 */
class NotificationOutboxStore(
    private val persistence: EncryptedOutboxPersistence,
    private val encryptionKey: SecretKey,
    private val ackVerifier: EventAckVerifier = EventAckVerifier { _, _ -> false },
    private val eventIds: EventIdSource = DefaultEventIdSource,
    private val maxEvents: Int = MAX_EVENTS,
) : NotificationOutbox {
    private val events = LinkedHashMap<String, DurableEvent>()
    private val lock = Any()

    init {
        require(encryptionKey.algorithm.equals("AES", ignoreCase = true)) { "outbox key must use AES" }
        require(maxEvents in 2..MAX_EVENTS) { "outbox capacity must be between 2 and $MAX_EVENTS" }
        restore()
    }

    constructor(
        persistence: EncryptedOutboxPersistence,
        encryptionKey: ByteArray,
        ackVerifier: EventAckVerifier = EventAckVerifier { _, _ -> false },
        eventIds: EventIdSource = DefaultEventIdSource,
        maxEvents: Int = MAX_EVENTS,
    ) : this(
        persistence = persistence,
        encryptionKey = SecretKeySpec(validateKeyBytes(encryptionKey), "AES"),
        ackVerifier = ackVerifier,
        eventIds = eventIds,
        maxEvents = maxEvents,
    )

    override suspend fun enqueueAccepted(record: NotificationRecordV1): DurableEvent = enqueueAcceptedBlocking(record)

    fun enqueueAcceptedBlocking(record: NotificationRecordV1): DurableEvent = synchronized(lock) {
        val snapshot = LinkedHashMap(events)
        try {
            if (events.size >= maxEvents) compactForBackpressure(record)
            val event = createEvent(record)
            events[event.eventId] = event
            persist()
            event.copy(eventWire = event.eventWire.copyOf())
        } catch (failure: RuntimeException) {
            events.clear()
            events.putAll(snapshot)
            throw failure
        }
    }

    override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) =
        acknowledgeBlocking(eventId, eventAckWire)

    fun acknowledgeBlocking(eventId: String, eventAckWire: ByteArray) = synchronized(lock) {
        val event = events[eventId] ?: throw OutboxAckRejected("unknown event")
        if (eventAckWire.isEmpty() || !ackVerifier.verify(eventId, eventAckWire.copyOf())) {
            throw OutboxAckRejected("event ACK failed verification")
        }
        val snapshot = LinkedHashMap(events)
        events.remove(eventId)
        try {
            persist()
        } catch (failure: RuntimeException) {
            // Keep retry/recovery semantics coherent if the persistence seam
            // rejects the deletion (disk full, transient I/O, etc.).
            events.clear()
            events.putAll(snapshot)
            throw failure
        }
    }

    override suspend fun recoverUnacknowledged(): List<DurableEvent> = recoverUnacknowledgedBlocking()

    fun recoverUnacknowledgedBlocking(): List<DurableEvent> = synchronized(lock) {
        events.values.map { it.copy(eventWire = it.eventWire.copyOf()) }
    }

    private fun restore() {
        val ciphertext = persistence.read() ?: return
        try {
            val plain = decrypt(ciphertext)
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val magic = readString(input)
                check(magic == MAGIC) { "outbox format mismatch" }
                val count = input.readInt()
                check(count in 0..MAX_EVENTS) { "outbox event count invalid" }
                repeat(count) {
                    val eventId = readString(input)
                    require(eventId.isNotBlank()) { "outbox event ID is blank" }
                    check(!events.containsKey(eventId)) { "duplicate outbox event ID" }
                    val wire = readBytes(input)
                    val decoded = decodeEventWire(wire)
                    check(decoded.first == eventId) { "outbox event identity mismatch" }
                    events[eventId] = DurableEvent(eventId, decoded.second, wire.copyOf())
                }
                check(input.available() == 0) { "outbox trailing bytes" }
            }
        } catch (failure: Throwable) {
            events.clear()
            throw if (failure is OutboxCorrupted) failure else OutboxCorrupted("outbox recovery failed", failure)
        }
    }

    private fun persist() {
        if (events.isEmpty()) {
            persistence.clear()
            return
        }
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                writeString(output, MAGIC)
                output.writeInt(events.size)
                events.values.forEach { event ->
                    writeString(output, event.eventId)
                    writeBytes(output, event.eventWire)
                }
            }
            bytes.toByteArray()
        }
        persistence.write(encrypt(plain))
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(iv)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plain)
            ByteArrayOutputStream().use { output ->
                output.write(iv)
                output.write(ciphertext)
                output.toByteArray()
            }
        } catch (failure: GeneralSecurityException) {
            throw OutboxCorrupted("outbox encryption failed", failure)
        }
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > IV_BYTES) { "outbox envelope is truncated" }
        return try {
            val iv = envelope.copyOfRange(0, IV_BYTES)
            val ciphertext = envelope.copyOfRange(IV_BYTES, envelope.size)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (failure: GeneralSecurityException) {
            throw OutboxCorrupted("outbox authentication failed", failure)
        }
    }

    private fun encodeEventWire(eventId: String, record: NotificationRecordV1): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            writeString(output, EVENT_MAGIC)
            writeString(output, eventId)
            writeRecord(output, record)
        }
        bytes.toByteArray()
    }

    private fun decodeEventWire(wire: ByteArray): Pair<String, NotificationRecordV1> = try {
        DataInputStream(ByteArrayInputStream(wire)).use { input ->
            check(readString(input) == EVENT_MAGIC) { "event format mismatch" }
            val eventId = readString(input)
            val record = readRecord(input)
            check(input.available() == 0) { "event trailing bytes" }
            eventId to record
        }
    } catch (failure: Throwable) {
        throw OutboxCorrupted("event decode failed", failure)
    }

    private fun createEvent(record: NotificationRecordV1): DurableEvent {
        var eventId: String
        var attempts = 0
        do {
            eventId = eventIds.nextId()
            require(eventId.isNotBlank()) { "event ID must not be blank" }
            attempts += 1
            check(attempts <= 100) { "event ID source returned duplicates" }
        } while (events.containsKey(eventId))
        return DurableEvent(eventId, record, encodeEventWire(eventId, record))
    }

    /** Replace evicted events with a durable range marker; no record vanishes silently. */
    private fun compactForBackpressure(incoming: NotificationRecordV1) {
        val dropCount = events.size - maxEvents + 2
        val dropped = events.values.take(dropCount)
        require(dropped.isNotEmpty()) { "backpressure compaction needs an event" }
        dropped.forEach { events.remove(it.eventId) }
        val lostFrom = dropped.minOf { event ->
            (event.record as? NotificationRecordV1.LossMarker)?.loss?.lostFromCursor ?: event.record.cursor
        }
        val lostTo = dropped.maxOf { event ->
            (event.record as? NotificationRecordV1.LossMarker)?.loss?.lostToCursor ?: event.record.cursor
        }
        val markerRecord = NotificationRecordV1.LossMarker(
            sourceEpoch = incoming.sourceEpoch,
            occurrenceId = "outbox-loss:$lostFrom:$lostTo",
            recordKey = "outbox-loss:$lostFrom:$lostTo",
            recordRevision = maxOf(1uL, incoming.recordRevision),
            cursor = lostTo,
            capturedAtEpochMs = incoming.capturedAtEpochMs,
            captureRevision = incoming.captureRevision,
            loss = NotificationLoss(lostFrom, lostTo, "OUTBOX_BACKPRESSURE"),
        )
        val marker = createEvent(markerRecord)
        events[marker.eventId] = marker
    }

    private fun writeRecord(output: DataOutputStream, record: NotificationRecordV1) {
        output.writeByte(
            when (record) {
                is NotificationRecordV1.Upsert -> 1
                is NotificationRecordV1.DeleteTombstone -> 2
                is NotificationRecordV1.LossMarker -> 3
            },
        )
        output.writeLong(record.sourceEpoch.toLong())
        writeString(output, record.occurrenceId)
        writeString(output, record.recordKey)
        output.writeLong(record.recordRevision.toLong())
        output.writeLong(record.cursor.toLong())
        output.writeLong(record.capturedAtEpochMs)
        output.writeLong(record.captureRevision.toLong())
        writeString(output, record.sourceCapability)
        when (record) {
            is NotificationRecordV1.Upsert -> {
                writeMetadata(output, record.metadata)
                writeNullableContent(output, record.content)
            }
            is NotificationRecordV1.DeleteTombstone -> writeMetadata(output, record.metadata)
            is NotificationRecordV1.LossMarker -> {
                output.writeLong(record.loss.lostFromCursor.toLong())
                output.writeLong(record.loss.lostToCursor.toLong())
                writeString(output, record.loss.reason)
            }
        }
    }

    private fun readRecord(input: DataInputStream): NotificationRecordV1 {
        val tag = input.readUnsignedByte()
        val sourceEpoch = input.readLong().toULong()
        val occurrenceId = readString(input)
        val recordKey = readString(input)
        val recordRevision = input.readLong().toULong()
        val cursor = input.readLong().toULong()
        val capturedAt = input.readLong()
        val captureRevision = input.readLong().toULong()
        val sourceCapability = readString(input)
        return when (tag) {
            1 -> NotificationRecordV1.Upsert(
                sourceEpoch, occurrenceId, recordKey, recordRevision, cursor, capturedAt, captureRevision,
                readMetadata(input), readNullableContent(input), sourceCapability,
            )
            2 -> NotificationRecordV1.DeleteTombstone(
                sourceEpoch, occurrenceId, recordKey, recordRevision, cursor, capturedAt, captureRevision,
                readMetadata(input), sourceCapability,
            )
            3 -> NotificationRecordV1.LossMarker(
                sourceEpoch, occurrenceId, recordKey, recordRevision, cursor, capturedAt, captureRevision,
                NotificationLoss(input.readLong().toULong(), input.readLong().toULong(), readString(input)), sourceCapability,
            )
            else -> error("unknown event record kind")
        }
    }

    private fun writeMetadata(output: DataOutputStream, value: NotificationMetadata) {
        writeString(output, value.packageName)
        writeNullableString(output, value.appLabel)
        writeNullableString(output, value.channelId)
        output.writeLong(value.postedAtEpochMs)
    }

    private fun readMetadata(input: DataInputStream) = NotificationMetadata(
        readString(input), readNullableString(input), readNullableString(input), input.readLong(),
    )

    private fun writeNullableContent(output: DataOutputStream, value: NotificationContent?) {
        output.writeBoolean(value != null)
        if (value != null) {
            writeNullableString(output, value.title)
            writeNullableString(output, value.body)
        }
    }

    private fun readNullableContent(input: DataInputStream): NotificationContent? = if (input.readBoolean()) {
        NotificationContent(readNullableString(input), readNullableString(input))
    } else null

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val MAX_EVENTS = 10_000
        private const val MAGIC = "OPEN_ANDROID_INTELLIGENCE_OUTBOX_V1"
        private const val EVENT_MAGIC = "OPEN_ANDROID_INTELLIGENCE_EVENT_V1"
        private val utf8 = StandardCharsets.UTF_8

        private fun validateKeyBytes(value: ByteArray): ByteArray {
            require(value.size == 16 || value.size == 24 || value.size == 32) {
                "outbox key must be 128, 192 or 256 bits"
            }
            return value.copyOf()
        }

        private fun writeString(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(utf8)
            require(bytes.size <= MAX_STRING_BYTES) { "outbox string is too large" }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        private fun readString(input: DataInputStream): String {
            val bytes = readBytes(input)
            return String(bytes, utf8)
        }

        private fun writeNullableString(output: DataOutputStream, value: String?) {
            output.writeBoolean(value != null)
            if (value != null) writeString(output, value)
        }

        private fun readNullableString(input: DataInputStream): String? = if (input.readBoolean()) readString(input) else null

        private fun writeBytes(output: DataOutputStream, value: ByteArray) {
            require(value.size <= MAX_WIRE_BYTES) { "outbox wire is too large" }
            output.writeInt(value.size)
            output.write(value)
        }

        private fun readBytes(input: DataInputStream): ByteArray {
            val length = input.readInt()
            require(length in 0..MAX_WIRE_BYTES) { "outbox byte field is invalid" }
            return ByteArray(length).also { input.readFully(it) }
        }

        private const val MAX_STRING_BYTES = 1_048_576
        private const val MAX_WIRE_BYTES = 4 * 1_048_576
    }
}

private object DefaultEventIdSource : EventIdSource {
    private val sequence = AtomicLong()
    override fun nextId(): String = "event-${sequence.incrementAndGet()}"
}
