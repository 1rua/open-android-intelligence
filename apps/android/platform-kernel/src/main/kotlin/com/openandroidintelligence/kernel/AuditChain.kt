package com.openandroidintelligence.kernel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** How much of an audit file could actually be trusted. */
enum class AuditChainStatus {
    /** Every record is chained and every digest checks out. */
    INTACT,

    /**
     * The chain does not check out: a record was edited, a record was removed or
     * inserted, a record that is not part of the chain appeared inside it, or a
     * line could not be read at all. [AuditChainVerification.reason] says which.
     */
    BROKEN,

    /**
     * No break was found, but the file still contains records written before the
     * chain existed. Their integrity cannot be proven by this file alone, and
     * saying "intact" would overstate what the log can show.
     */
    LEGACY_UNCHAINED,
    ;
}

/**
 * The result of walking an audit file's chain.
 *
 * Counts are the prefix that could be classified before the walk stopped at the
 * first break; [firstBrokenRecord] is the 1-based record number that failed, with
 * [reason] naming the failure and [headHash] the last digest that did verify —
 * the only value worth anchoring outside the file.
 */
data class AuditChainVerification(
    val status: AuditChainStatus,
    val verifiedRecords: Int,
    val legacyRecords: Int,
    val unreadableRecords: Int,
    val firstBrokenRecord: Int?,
    val reason: String?,
    val headHash: String?,
) {
    val isIntact: Boolean get() = status == AuditChainStatus.INTACT
}

/**
 * The append-only audit line format, versioned so a file from an older build is
 * still readable.
 *
 * A chained line is `v2|` plus the seven encoded record fields, the previous
 * record's digest and this record's digest:
 *
 * ```text
 * v2|<plugin>|<account>|<pairing>|<action>|<outcome>|<correlation>|<timestamp>|<prev>|<hash>
 * ```
 *
 * `hash` is SHA-256 over `v2|<encoded fields>|<prev>`, so editing any field of a
 * record invalidates its own digest, and re-computing that digest still leaves
 * the next record pointing at the old value. A `v1|` line has no chain fields:
 * it is what earlier builds wrote, it stays readable, and it is reported as
 * unverifiable rather than silently accepted as chained.
 *
 * Content is never stored in the clear: every field is base64url of the encoded
 * value, exactly as the previous format did, so the file still carries no
 * plaintext message body, plugin argument or capability text.
 */
internal object AuditLineCodec {

    const val CHAINED_VERSION = "v2"
    const val LEGACY_VERSION = "v1"

    /** Records the seven encoded fields, and nothing else. */
    private const val FIELD_COUNT = 7

    /**
     * The digest a brand-new chain starts from.
     *
     * It is derived from a fixed domain string rather than being all zeroes, so a
     * record whose `prev` was blanked by an attacker cannot coincide with the
     * real chain start.
     */
    val GENESIS: String = base64Url(sha256("open-android-intelligence/audit-chain/genesis".toByteArray(StandardCharsets.UTF_8)))

    private val SHA256_BASE64 = Regex("[A-Za-z0-9_-]{43}")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    sealed interface Decoded {
        data class Chained(val event: AuditEvent, val previousHash: String, val hash: String) : Decoded

        data class Legacy(val event: AuditEvent) : Decoded

        object Unreadable : Decoded
    }

    fun encodeChained(event: AuditEvent, previousHash: String): String {
        val payload = payloadOf(event)
        return listOf(CHAINED_VERSION, payload, previousHash, digest(previousHash, payload))
            .joinToString("|")
    }

    fun decode(line: String): Decoded {
        val fields = line.split('|')
        return when {
            fields.size == FIELD_COUNT + 1 && fields[0] == LEGACY_VERSION ->
                // v1 行同样带版本前缀，解码前必须先剥掉，否则旧格式记录会被
                // 误判成不可解析，升级前的历史就会「读崩」。
                decodeEvent(fields.subList(1, fields.size))?.let(Decoded::Legacy) ?: Decoded.Unreadable

            fields.size == FIELD_COUNT + 3 && fields[0] == CHAINED_VERSION -> {
                val event = decodeEvent(fields.subList(1, 1 + FIELD_COUNT))
                val previousHash = fields[FIELD_COUNT + 1]
                val hash = fields[FIELD_COUNT + 2]
                if (event == null ||
                    !SHA256_BASE64.matches(previousHash) ||
                    !SHA256_BASE64.matches(hash)
                ) {
                    Decoded.Unreadable
                } else {
                    Decoded.Chained(event, previousHash, hash)
                }
            }

            else -> Decoded.Unreadable
        }
    }

    /** The digest a chained line carries, or null when it is not a chained line. */
    fun hashOf(line: String): String? = (decode(line) as? Decoded.Chained)?.hash

    /**
     * Walks the file from its first record and stops at the first thing that does
     * not check out.
     *
     * Records that predate the chain are counted instead of breaking the walk,
     * but only while they come first: an un-chained line *inside* a chained run
     * means the run is not continuous, which is exactly what replacing a record
     * with an old-format one would look like.
     */
    fun verify(lines: List<String>): AuditChainVerification {
        var head = GENESIS
        var verified = 0
        var legacy = 0
        var unreadable = 0
        var index = 0

        fun broken(at: Int, why: String) = AuditChainVerification(
            status = AuditChainStatus.BROKEN,
            verifiedRecords = verified,
            legacyRecords = legacy,
            unreadableRecords = unreadable,
            firstBrokenRecord = at,
            reason = why,
            headHash = head,
        )

        for (line in lines) {
            if (line.isBlank()) continue
            index++
            when (val decoded = decode(line)) {
                is Decoded.Chained -> when {
                    decoded.previousHash != head -> return broken(index, "chain-link-mismatch")
                    decoded.hash != digest(decoded.previousHash, payloadOf(decoded.event)) ->
                        return broken(index, "content-hash-mismatch")

                    else -> {
                        head = decoded.hash
                        verified++
                    }
                }

                is Decoded.Legacy -> {
                    if (verified > 0) return broken(index, "legacy-record-inside-chain")
                    legacy++
                }

                Decoded.Unreadable -> {
                    unreadable++
                    return broken(index, "unreadable-record")
                }
            }
        }

        return AuditChainVerification(
            status = if (legacy > 0) AuditChainStatus.LEGACY_UNCHAINED else AuditChainStatus.INTACT,
            verifiedRecords = verified,
            legacyRecords = legacy,
            unreadableRecords = unreadable,
            firstBrokenRecord = null,
            reason = null,
            headHash = head,
        )
    }

    private fun decodeEvent(fields: List<String>): AuditEvent? {
        if (fields.size != FIELD_COUNT) return null
        val values = fields.map { field ->
            runCatching { String(decoder.decode(field), StandardCharsets.UTF_8) }.getOrNull()
        }
        if (values.any { it == null }) return null
        val decoded = values.filterNotNull()
        val outcome = runCatching { AuditOutcome.valueOf(decoded[4]) }.getOrNull() ?: return null
        return AuditEvent(
            pluginId = decoded[0],
            accountId = decoded[1],
            pairingId = decoded[2],
            action = decoded[3],
            outcome = outcome,
            correlationId = decoded[5],
            timestampUtc = decoded[6],
        )
    }

    private fun payloadOf(event: AuditEvent): String = listOf(
        event.pluginId,
        event.accountId,
        event.pairingId,
        event.action,
        event.outcome.name,
        event.correlationId,
        event.timestampUtc,
    ).joinToString("|") { value -> encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8)) }

    private fun digest(previousHash: String, payload: String): String =
        base64Url(sha256("$CHAINED_VERSION|$payload|$previousHash".toByteArray(StandardCharsets.UTF_8)))

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
