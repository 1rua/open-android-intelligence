package com.openandroidintelligence.calls

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CallLog
import com.openandroidintelligence.capability.CallDirection
import com.openandroidintelligence.capability.CallHistoryPolicy
import com.openandroidintelligence.capability.CallNumberPresentation

internal enum class CallLogProviderTarget { CALLS }

/** Read-only adapter for the bounded public call-log provider surface. */
class AndroidCallLogReader private constructor(
    private val queryExecutor: QueryExecutor,
) : CallLogReader {
    constructor(resolver: ContentResolver) : this(
        QueryExecutor { target, projection, selection, selectionArgs, sortOrder, limit ->
            val uri = when (target) {
                CallLogProviderTarget.CALLS -> CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                    .build()
            }
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)
        },
    )

    internal constructor(
        queryExecutor: (
            CallLogProviderTarget,
            Array<String>,
            String?,
            Array<String>?,
            String,
            Int,
        ) -> Cursor?,
    ) : this(QueryExecutor(queryExecutor))

    override fun query(request: CallLogQuery): List<CallLogRow> {
        val providerTypes = request.directions.canonicalProviderTypes()
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        request.history.fromEpochMs?.let {
            clauses += "date >= ?"
            arguments += it.toString()
        }
        clauses += "type IN (${providerTypes.joinToString(",") { "?" }})"
        arguments += providerTypes.map(Int::toString)
        request.cursor?.let {
            clauses += "(date > ? OR (date = ? AND _id > ?))"
            arguments += it.startedAtEpochMs.toString()
            arguments += it.startedAtEpochMs.toString()
            arguments += it.providerId.toString()
        }

        val cursor = try {
            queryExecutor.query(
                CallLogProviderTarget.CALLS,
                PROJECTION,
                clauses.joinToString(" AND "),
                arguments.toTypedArray(),
                SORT_ORDER,
                request.history.maxRecords,
            )
        } catch (_: SecurityException) {
            throw CallLogPermissionRequiredException()
        } catch (_: Exception) {
            throw CallLogQueryException()
        }
        return cursor?.let { providerCursor ->
            readAndClose(providerCursor, request.history.maxRecords)
        }.orEmpty()
    }

    private fun readAndClose(cursor: Cursor, maxRecords: Int): List<CallLogRow> {
        var rows: List<CallLogRow>? = null
        var readFailure: Exception? = null
        var readCompleted = false
        try {
            try {
                rows = readRows(cursor, maxRecords)
            } catch (failure: SecurityException) {
                readFailure = CallLogPermissionRequiredException()
            } catch (failure: CallLogPermissionRequiredException) {
                readFailure = failure
            } catch (failure: CallLogInvalidRowException) {
                readFailure = failure
            } catch (failure: CallLogQueryException) {
                readFailure = failure
            } catch (_: Exception) {
                readFailure = CallLogQueryException()
            }
            readCompleted = true
        } finally {
            val closeFailure = try {
                cursor.close()
                null
            } catch (_: SecurityException) {
                CallLogPermissionRequiredException()
            } catch (_: Exception) {
                CallLogQueryException()
            }
            if (readCompleted && closeFailure != null) {
                throw closeFailure
            }
        }

        when (val failure = readFailure) {
            null -> return rows.orEmpty()
            is CallLogInvalidRowException -> throw failure
            is CallLogPermissionRequiredException -> throw failure
            is CallLogQueryException -> throw failure
            else -> throw CallLogQueryException()
        }
    }

    private fun readRows(cursor: Cursor, maxRecords: Int): List<CallLogRow> {
        val indexes = ColumnIndexes(cursor)
        return buildList {
            while (size < maxRecords && cursor.moveToNext()) {
                add(indexes.read(cursor))
            }
        }
    }

    override fun probe() {
        query(
            CallLogQuery(
                history = CallHistoryPolicy(fromEpochMs = null, maxRecords = 1),
                directions = CallDirection.entries.toSet(),
            ),
        )
    }

    private fun Set<CallDirection>.canonicalProviderTypes(): List<Int> =
        CallDirection.entries.filter(this::contains).map { it.providerType() }

    private class ColumnIndexes(cursor: Cursor) {
        private val providerId = cursor.getColumnIndexOrThrow("_id")
        private val type = cursor.getColumnIndexOrThrow("type")
        private val date = cursor.getColumnIndexOrThrow("date")
        private val duration = cursor.getColumnIndexOrThrow("duration")
        private val number = cursor.getColumnIndexOrThrow("number")
        private val numberPresentation = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)

        fun read(cursor: Cursor): CallLogRow {
            val rawProviderId: Long
            val rawType: Int
            val rawDate: Long
            val rawDuration: Long
            val rawNumber: String?
            val rawNumberPresentation: Int
            try {
                rawProviderId = cursor.getLong(providerId)
                rawType = cursor.getInt(type)
                rawDate = cursor.getLong(date)
                rawDuration = cursor.getLong(duration)
                rawNumber = cursor.getString(number)
                rawNumberPresentation = cursor.getInt(numberPresentation)
            } catch (failure: SecurityException) {
                throw CallLogPermissionRequiredException()
            } catch (failure: CallLogPermissionRequiredException) {
                throw failure
            } catch (_: Exception) {
                throw CallLogQueryException()
            }
            return try {
                CallLogRow(
                    providerId = rawProviderId,
                    direction = rawType.toCallDirection(),
                    startedAtEpochMs = rawDate,
                    durationSeconds = rawDuration,
                    number = rawNumber,
                    numberPresentation = rawNumberPresentation.toNumberPresentation(),
                )
            } catch (failure: CallLogInvalidRowException) {
                throw failure
            } catch (_: Exception) {
                throw CallLogInvalidRowException()
            }
        }
    }

    private fun interface QueryExecutor {
        fun query(
            target: CallLogProviderTarget,
            projection: Array<String>,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String,
            limit: Int,
        ): Cursor?
    }

    private companion object {
        val PROJECTION = arrayOf("_id", "type", "date", "duration", "number", CallLog.Calls.NUMBER_PRESENTATION)
        const val SORT_ORDER = "date DESC, _id DESC"
    }
}

private fun CallDirection.providerType(): Int = when (this) {
    CallDirection.INCOMING -> CallLog.Calls.INCOMING_TYPE
    CallDirection.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
    CallDirection.MISSED -> CallLog.Calls.MISSED_TYPE
    CallDirection.REJECTED -> CallLog.Calls.REJECTED_TYPE
}

private fun Int.toCallDirection(): CallDirection = when (this) {
    CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
    CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
    CallLog.Calls.REJECTED_TYPE -> CallDirection.REJECTED
    else -> throw CallLogInvalidRowException()
}

private fun Int.toNumberPresentation(): CallNumberPresentation = when (this) {
    CallLog.Calls.PRESENTATION_ALLOWED -> CallNumberPresentation.ALLOWED
    CallLog.Calls.PRESENTATION_RESTRICTED -> CallNumberPresentation.RESTRICTED
    CallLog.Calls.PRESENTATION_UNKNOWN -> CallNumberPresentation.UNKNOWN
    CallLog.Calls.PRESENTATION_PAYPHONE -> CallNumberPresentation.PAYPHONE
    CallLog.Calls.PRESENTATION_UNAVAILABLE -> CallNumberPresentation.UNAVAILABLE
    else -> throw CallLogInvalidRowException()
}
