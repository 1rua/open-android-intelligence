package com.openandroidintelligence.sms

import android.content.ContentResolver
import com.openandroidintelligence.capability.SmsHistoryPolicy

/** Only the installation's push policy supplies these values, never the passive read grant. */
data class SmsPushCaptureRequest(
    val fromEpochMs: Long,
    val after: SmsCursor? = null,
    val senders: Set<String> = emptySet(),
    val allSenders: Boolean = false,
    val includeContent: Boolean = true,
    val maxRecords: Int = 128,
    val keywords: Set<String> = emptySet(),
) {
    init { require(fromEpochMs >= 0 && maxRecords in 1..512) }
}

data class SmsPushCaptureResult(
    val read: Int,
    val accepted: Int,
    val lastAccepted: SmsCursor?,
    val morePossible: Boolean,
    val paused: Boolean,
)

/**
 * Real inbox-reader composition for an IO worker or ContentObserver. Ascending queries allow
 * bounded backlog pages without skipping older SMS. Only durable acceptance advances the
 * returned cursor; the caller stores that cursor together with its encrypted push state.
 */
class SmsPushSource(private val reader: SmsInboxReader) {
    constructor(resolver: ContentResolver) : this(AndroidSmsInboxReader(resolver))

    fun capture(
        request: SmsPushCaptureRequest,
        isCurrent: () -> Boolean,
        enqueueDurably: (SmsInboxRow) -> Boolean,
    ): SmsPushCaptureResult = capture(request, isCurrent, { false }, enqueueDurably)

    fun capture(
        request: SmsPushCaptureRequest,
        isCurrent: () -> Boolean,
        advanceSkippedCursor: (SmsCursor) -> Boolean,
        enqueueDurably: (SmsInboxRow) -> Boolean,
    ): SmsPushCaptureResult {
        if ((!request.allSenders && request.senders.isEmpty() && request.keywords.isEmpty()) || !isCurrent()) {
            return SmsPushCaptureResult(0, 0, request.after, false, true)
        }
        val rows = reader.query(SmsInboxQuery(
            history = SmsHistoryPolicy(request.fromEpochMs, request.maxRecords),
            cursor = request.after,
            senders = if (request.allSenders || request.keywords.isNotEmpty()) emptySet() else request.senders,
            oldestFirst = true,
            includeBody = request.includeContent,
            allSenders = request.allSenders,
            phoneNumbers = request.senders,
            keywords = request.keywords,
            includeRejected = true,
        )).sortedWith(compareBy(SmsInboxRow::messageAtEpochMs, SmsInboxRow::providerId))
        var accepted = 0
        var cursor = request.after
        for (row in rows) {
            if (!isCurrent()) return SmsPushCaptureResult(rows.size, accepted, cursor, true, true)
            val nextCursor = SmsCursor(row.providerId, row.messageAtEpochMs)
            val matches = row.scopeMatched && matchesSmsConditions(request.allSenders, request.senders, request.keywords, row)
            val durable = if (matches) {
                val proof = matchingSmsKeywords(row.body, request.keywords) + row.matchedKeywords.filter { matched ->
                    request.keywords.any { it.equals(matched, ignoreCase = true) }
                }
                enqueueDurably(row.copy(body = row.body.takeIf { request.includeContent }, matchedKeywords = proof))
            } else advanceSkippedCursor(nextCursor)
            if (!durable) return SmsPushCaptureResult(rows.size, accepted, cursor, true, true)
            cursor = nextCursor
            if (matches) accepted += 1
        }
        return SmsPushCaptureResult(rows.size, accepted, cursor, rows.size == request.maxRecords, false)
    }
}
