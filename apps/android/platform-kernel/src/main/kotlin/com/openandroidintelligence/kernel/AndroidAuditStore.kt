package com.openandroidintelligence.kernel

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuditOutcome { ALLOWED, DENIED, FAILED }

/**
 * One audit record.
 *
 * The fields are exactly what the contract permits: who acted, what they
 * attempted, whether it was allowed, and the correlation ID that ties the
 * record to a request. There is deliberately nowhere to put a message body,
 * an attachment, a capability argument or a result payload, so a caller cannot
 * leak content even by trying to.
 */
data class AuditEvent(
    val pluginId: String,
    val accountId: String,
    val pairingId: String,
    val action: String,
    val outcome: AuditOutcome,
    val correlationId: String,
    val timestampUtc: String,
)

interface AuditSink {
    fun write(event: AuditEvent)
}

interface ObservableAuditSink : AuditSink {
    val eventsFlow: StateFlow<List<AuditEvent>>

    fun events(): List<AuditEvent>
}

class InMemoryAuditSink : ObservableAuditSink {
    private val lock = Any()
    private val _events = MutableStateFlow<List<AuditEvent>>(emptyList())
    override val eventsFlow: StateFlow<List<AuditEvent>> = _events.asStateFlow()

    override fun write(event: AuditEvent) {
        synchronized(lock) {
            _events.value = _events.value + event
        }
    }

    override fun events(): List<AuditEvent> = eventsFlow.value
}

/**
 * Private, metadata-only, tamper-evident audit storage for the Android host.
 *
 * The sink stores encoded fields rather than rendered text so a future UI can
 * still render through [AndroidAuditStore]. Invalid lines are ignored during
 * recovery (a damaged audit file must not make the host fail to start), but they
 * are not forgiven by [verifyChain]: the file is append-only and every chained
 * record carries its predecessor's digest ([AuditLineCodec]), so editing or
 * removing a record is detectable instead of silently overwritten away — the
 * old implementation rewrote the whole file on every write, which made exactly
 * the tampering the settings page claims to resist undetectable.
 *
 * Records written by older builds (`v1`, no chain fields) stay readable, and
 * [verifyChain] reports them as unverifiable rather than pretending they are
 * protected.
 */
class PersistentAuditSink(
    private val file: File,
    private val clock: () -> Instant = { Instant.now() },
    private val retention: Duration = Duration.ofDays(30),
) : ObservableAuditSink {

    private class Recovered(val visibleEvents: List<AuditEvent>, val headHash: String)

    private val lock = Any()
    private var recovered: Recovered = load()
    private val _events = MutableStateFlow(recovered.visibleEvents)
    override val eventsFlow: StateFlow<List<AuditEvent>> = _events.asStateFlow()

    override fun write(event: AuditEvent) {
        synchronized(lock) {
            val cutoff = clock().minus(retention)
            val line = AuditLineCodec.encodeChained(event, recovered.headHash)
            append(line)
            recovered = Recovered(
                visibleEvents = (recovered.visibleEvents + event).filterNot { isExpired(it, cutoff) },
                headHash = AuditLineCodec.hashOf(line) ?: recovered.headHash,
            )
            _events.value = recovered.visibleEvents
        }
    }

    override fun events(): List<AuditEvent> = eventsFlow.value

    /**
     * Walks the file as it exists on disk right now — not the in-memory view —
     * so the answer reflects what an attacker with file access would find, not
     * what this process last wrote. A file that cannot be parsed is reported,
     * never silently passed off as intact.
     */
    fun verifyChain(): AuditChainVerification = synchronized(lock) {
        if (!file.isFile) {
            return AuditChainVerification(
                status = AuditChainStatus.INTACT,
                verifiedRecords = 0,
                legacyRecords = 0,
                unreadableRecords = 0,
                firstBrokenRecord = null,
                reason = null,
                headHash = AuditLineCodec.GENESIS,
            )
        }
        runCatching { AuditLineCodec.verify(file.readLines(StandardCharsets.UTF_8)) }.getOrElse {
            AuditChainVerification(
                status = AuditChainStatus.BROKEN,
                verifiedRecords = 0,
                legacyRecords = 0,
                unreadableRecords = 0,
                firstBrokenRecord = 1,
                reason = "file-unreadable",
                headHash = null,
            )
        }
    }

    /**
     * Append-only: the record is added at the end of the existing file and
     * synced. Records are never rewritten or removed in place, so a digest once
     * written cannot be quietly re-anchored.
     */
    private fun append(line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun load(): Recovered {
        if (!file.isFile) return Recovered(emptyList(), AuditLineCodec.GENESIS)
        val cutoff = clock().minus(retention)
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        var head = AuditLineCodec.GENESIS
        val events = mutableListOf<AuditEvent>()
        for (line in lines) {
            if (line.isBlank()) continue
            // 无法解析的行不进入内存视图（旧规则：损坏的文件不能让宿主无法
            // 启动），但它在磁盘上原样保留，verifyChain 会如实报告断链。
            when (val decoded = AuditLineCodec.decode(line)) {
                is AuditLineCodec.Decoded.Chained -> {
                    events += decoded.event
                    head = decoded.hash
                }

                is AuditLineCodec.Decoded.Legacy -> events += decoded.event

                AuditLineCodec.Decoded.Unreadable -> Unit
            }
        }
        return Recovered(events.filterNot { isExpired(it, cutoff) }, head)
    }

    private fun isExpired(event: AuditEvent, cutoff: Instant): Boolean =
        runCatching { Instant.parse(event.timestampUtc).isBefore(cutoff) }.getOrDefault(true)
}

/**
 * The authoritative Android audit log.
 *
 * Actions are identifiers, not free text: a plugin that tries to record its
 * payload under `action` gets a redacted record instead. That is what keeps the
 * audit log useful as evidence without turning it into a second copy of the
 * user's data.
 */
class AndroidAuditStore(
    private val sink: AuditSink = InMemoryAuditSink(),
    private val clock: () -> Instant = { Instant.now() },
) {
    companion object {
        private val ACTION_PATTERN = Regex("^[a-z][a-z0-9]*(?:\\.[a-z0-9-]+)*$")
        private const val MAX_ACTION_LENGTH = 64
        private const val REDACTED = "redacted"

        /**
         * Protocol timestamps must keep their milliseconds: `ISO_INSTANT`
         * silently drops the fraction when it is zero, producing a preimage the
         * Gateway cannot reproduce.
         */
        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
    }

    fun record(
        pluginId: String,
        accountId: String,
        pairingId: String,
        action: String,
        outcome: AuditOutcome,
        correlationId: String,
    ): AuditEvent {
        val event = AuditEvent(
            pluginId = sanitiseToken(pluginId),
            accountId = sanitiseToken(accountId),
            pairingId = sanitiseToken(pairingId),
            action = sanitiseAction(action),
            outcome = outcome,
            correlationId = sanitiseToken(correlationId),
            timestampUtc = TIMESTAMP.format(clock()),
        )
        sink.write(event)
        return event
    }

    /**
     * Renders one record with exactly the permitted fields. Any value that does
     * not look like an identifier is replaced, so a malformed action cannot
     * smuggle content into the log through the renderer.
     */
    fun render(event: AuditEvent): String = listOf(
        "ts=" + event.timestampUtc,
        "plugin=" + sanitiseToken(event.pluginId),
        "account=" + sanitiseToken(event.accountId),
        "pairing=" + sanitiseToken(event.pairingId),
        "action=" + sanitiseAction(event.action),
        "outcome=" + event.outcome.name,
        "correlation=" + sanitiseToken(event.correlationId),
    ).joinToString(" ")

    private fun sanitiseAction(action: String): String =
        if (action.length <= MAX_ACTION_LENGTH && ACTION_PATTERN.matches(action)) action else REDACTED

    private fun sanitiseToken(value: String): String =
        if (value.isEmpty() || value.length > 128 || value.any { it == ' ' || it == '\n' || it == '\r' }) {
            REDACTED
        } else {
            value
        }
}
