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
    ): SmsPushCaptureResult {
        if ((!request.allSenders && request.senders.isEmpty()) || !isCurrent()) {
            return SmsPushCaptureResult(0, 0, request.after, false, true)
        }
        val rows = reader.query(SmsInboxQuery(
            history = SmsHistoryPolicy(request.fromEpochMs, request.maxRecords),
            cursor = request.after,
            senders = if (request.allSenders) emptySet() else request.senders,
            oldestFirst = true,
            includeBody = request.includeContent,
        )).sortedWith(compareBy(SmsInboxRow::messageAtEpochMs, SmsInboxRow::providerId))
        var accepted = 0
        var cursor = request.after
        for (row in rows) {
            if (!isCurrent() || !enqueueDurably(row)) {
                return SmsPushCaptureResult(rows.size, accepted, cursor, true, true)
            }
            cursor = SmsCursor(row.providerId, row.messageAtEpochMs)
            accepted += 1
        }
        return SmsPushCaptureResult(rows.size, accepted, cursor, rows.size == request.maxRecords, false)
    }
}
