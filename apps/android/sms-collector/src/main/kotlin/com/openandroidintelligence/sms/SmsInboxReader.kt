package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.SmsHistoryPolicy

data class SmsInboxRow(
    val providerId: Long,
    val threadId: String?,
    val address: String?,
    val body: String?,
    val messageAtEpochMs: Long,
    val read: Boolean,
    val subscriptionId: Int?,
    val matchedKeywords: Set<String> = emptySet(),
    val scopeMatched: Boolean = true,
) {
    init {
        require(providerId > 0) { "SMS provider ID must be positive" }
        require(messageAtEpochMs >= 0) { "SMS message time must not be negative" }
    }
}

data class SmsInboxQuery(
    val history: SmsHistoryPolicy,
    val cursor: SmsCursor? = null,
    val senders: Set<String> = emptySet(),
    val textQuery: String = "",
    val oldestFirst: Boolean = false,
    val includeBody: Boolean = true,
    val allSenders: Boolean = true,
    val phoneNumbers: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    // Push pages retain redacted rejected rows only to advance their durable scan cursor.
    val includeRejected: Boolean = false,
)

fun interface SmsInboxReader {
    fun query(request: SmsInboxQuery): List<SmsInboxRow>
}

/** Collector-only query conditions; no dependency on the host's authorization/UI model. */
internal fun matchingSmsKeywords(body: String?, keywords: Set<String>): Set<String> =
    if (body == null) emptySet() else keywords.filterTo(linkedSetOf()) { it.isNotBlank() && body.contains(it, ignoreCase = true) }

internal fun matchesSmsConditions(allSenders: Boolean, senders: Set<String>, keywords: Set<String>, row: SmsInboxRow): Boolean =
    allSenders || row.address != null && row.address in senders ||
        matchingSmsKeywords(row.body, keywords).isNotEmpty() || keywords.any { keyword ->
            keyword.isNotBlank() && row.matchedKeywords.any { it.equals(keyword, ignoreCase = true) }
        }
