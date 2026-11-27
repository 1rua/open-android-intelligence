package com.openandroidintelligence.sms

import android.content.ContentResolver
import android.database.Cursor
import android.provider.Telephony

internal enum class SmsProviderTarget { INBOX }

class SmsQueryScanLimitExceeded : IllegalStateException("SMS_QUERY_SCAN_LIMIT_EXCEEDED")

/** Thin Android query adapter; all provider logic works through [SmsInboxReader]. */
class AndroidSmsInboxReader private constructor(
    private val queryExecutor: QueryExecutor,
) : SmsInboxReader {
    constructor(resolver: ContentResolver) : this(
        queryExecutor = QueryExecutor { target, projection, selection, selectionArgs, sortOrder ->
            val uri = when (target) {
                SmsProviderTarget.INBOX -> Telephony.Sms.Inbox.CONTENT_URI
            }
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)
        },
    )

    internal constructor(
        queryExecutor: (SmsProviderTarget, Array<String>, String?, Array<String>?, String) -> Cursor?,
    ) : this(QueryExecutor(queryExecutor))

    override fun query(request: SmsInboxQuery): List<SmsInboxRow> {
        if ((!request.allSenders && request.phoneNumbers.isEmpty() && request.keywords.isEmpty()) ||
            !request.includeBody && request.textQuery.isNotEmpty()) return emptyList()
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        request.history.fromEpochMs?.let {
            clauses += "date >= ?"
            arguments += it.toString()
        }
        request.cursor?.let {
            clauses += "(date > ? OR (date = ? AND _id > ?))"
            arguments += it.messageAtEpochMs.toString()
            arguments += it.messageAtEpochMs.toString()
            arguments += it.providerId.toString()
        }
        if (request.senders.isNotEmpty() && !request.includeRejected) {
            clauses += "address IN (${request.senders.joinToString(",") { "?" }})"
            arguments += request.senders
        }
        if (!request.includeRejected && !request.allSenders && request.phoneNumbers.isNotEmpty() && request.keywords.isEmpty()) {
            clauses += "address IN (${request.phoneNumbers.joinToString(",") { "?" }})"
            arguments += request.phoneNumbers
        }
        val readBody = request.includeBody || request.keywords.isNotEmpty()
        val startedAt = System.nanoTime()
        return queryExecutor.query(
            SmsProviderTarget.INBOX,
            if (readBody) PROJECTION else METADATA_PROJECTION,
            clauses.joinToString(" AND ").ifEmpty { null },
            arguments.toTypedArray().takeIf { it.isNotEmpty() },
            if (request.oldestFirst) "date ASC, _id ASC" else "date DESC, _id DESC",
        )?.use { readRows(it, request, readBody, startedAt) }.orEmpty()
    }

    private fun readRows(cursor: Cursor, request: SmsInboxQuery, readBody: Boolean, startedAt: Long): List<SmsInboxRow> {
        val providerId = cursor.getColumnIndexOrThrow("_id")
        val threadId = cursor.getColumnIndexOrThrow("thread_id")
        val address = cursor.getColumnIndexOrThrow("address")
        val body = if (readBody) cursor.getColumnIndexOrThrow("body") else -1
        val date = cursor.getColumnIndexOrThrow("date")
        val read = cursor.getColumnIndexOrThrow("read")
        val subscriptionId = cursor.getColumnIndexOrThrow("sub_id")
        var scanned = 0
        return buildList {
            while (size < request.history.maxRecords && cursor.moveToNext()) {
                // Synchronous provider traversal is not interrupted by coroutine withTimeout.
                // Fail explicitly, without disclosing a partially filtered history result.
                if (++scanned > MAX_SCAN_ROWS || System.nanoTime() - startedAt > MAX_SCAN_NANOS)
                    throw SmsQueryScanLimitExceeded()
                val text = if (body >= 0) cursor.getString(body) else null
                val sender = cursor.getString(address)
                val matchedKeywords = matchingSmsKeywords(text, request.keywords)
                val matches = (request.senders.isEmpty() || sender in request.senders) &&
                    (request.allSenders || sender != null && sender in request.phoneNumbers || matchedKeywords.isNotEmpty())
                if (!matches && !request.includeRejected) continue
                // SQLite LIKE only folds ASCII. Keep the public literal Unicode
                // substring semantics, and apply the limit after matching rows.
                if (request.textQuery.isNotEmpty() && !text.orEmpty().contains(request.textQuery, ignoreCase = true)) continue
                add(
                    SmsInboxRow(
                        providerId = cursor.getLong(providerId),
                        threadId = cursor.getString(threadId),
                        address = sender,
                        body = text.takeIf { request.includeBody && matches },
                        messageAtEpochMs = cursor.getLong(date),
                        read = cursor.getInt(read) != 0,
                        subscriptionId = if (cursor.isNull(subscriptionId)) null else cursor.getInt(subscriptionId),
                        matchedKeywords = matchedKeywords,
                        scopeMatched = matches,
                    ),
                )
            }
        }
    }

    internal companion object {
        const val MAX_SCAN_ROWS = 4_096
        private const val MAX_SCAN_NANOS = 2_000_000_000L
        private val PROJECTION = arrayOf("_id", "thread_id", "address", "body", "date", "read", "sub_id")
        private val METADATA_PROJECTION = arrayOf("_id", "thread_id", "address", "date", "read", "sub_id")
    }

    private fun interface QueryExecutor {
        fun query(
            target: SmsProviderTarget,
            projection: Array<String>,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String,
        ): Cursor?
    }
}
