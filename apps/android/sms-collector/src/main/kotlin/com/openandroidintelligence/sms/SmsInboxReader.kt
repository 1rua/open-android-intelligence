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
)

fun interface SmsInboxReader {
    fun query(request: SmsInboxQuery): List<SmsInboxRow>
}
