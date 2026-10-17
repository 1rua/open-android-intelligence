package com.openandroidintelligence.sms

import android.content.ContentResolver
import android.database.Cursor
import android.provider.Telephony

internal enum class SmsProviderTarget { INBOX }

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
        if (request.senders.isNotEmpty()) {
            clauses += "address IN (${request.senders.joinToString(",") { "?" }})"
            arguments += request.senders
        }
        return queryExecutor.query(
            SmsProviderTarget.INBOX,
            PROJECTION,
            clauses.joinToString(" AND ").ifEmpty { null },
            arguments.toTypedArray().takeIf { it.isNotEmpty() },
            "date DESC, _id DESC",
        )?.use { readRows(it, request.history.maxRecords, request.textQuery) }.orEmpty()
    }

    private fun readRows(cursor: Cursor, limit: Int, textQuery: String): List<SmsInboxRow> {
        val providerId = cursor.getColumnIndexOrThrow("_id")
        val threadId = cursor.getColumnIndexOrThrow("thread_id")
        val address = cursor.getColumnIndexOrThrow("address")
        val body = cursor.getColumnIndexOrThrow("body")
        val date = cursor.getColumnIndexOrThrow("date")
        val read = cursor.getColumnIndexOrThrow("read")
        val subscriptionId = cursor.getColumnIndexOrThrow("sub_id")
        return buildList {
            while (size < limit && cursor.moveToNext()) {
                val text = cursor.getString(body)
                // SQLite LIKE only folds ASCII. Keep the public literal Unicode
                // substring semantics, and apply the limit after matching rows.
                if (textQuery.isNotEmpty() && !text.orEmpty().contains(textQuery, ignoreCase = true)) continue
                add(
                    SmsInboxRow(
                        providerId = cursor.getLong(providerId),
                        threadId = cursor.getString(threadId),
                        address = cursor.getString(address),
                        body = text,
                        messageAtEpochMs = cursor.getLong(date),
                        read = cursor.getInt(read) != 0,
                        subscriptionId = if (cursor.isNull(subscriptionId)) null else cursor.getInt(subscriptionId),
                    ),
                )
            }
        }
    }

    private companion object {
        val PROJECTION = arrayOf("_id", "thread_id", "address", "body", "date", "read", "sub_id")
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
