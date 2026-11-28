package com.openandroidintelligence.calls

import android.database.Cursor
import android.provider.CallLog
import com.openandroidintelligence.capability.CallDirection
import com.openandroidintelligence.capability.CallHistoryPolicy
import com.openandroidintelligence.capability.CallNumberPresentation
import com.openandroidintelligence.capability.CapabilityAvailability
import java.lang.reflect.Proxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCallLogReaderTest {
    @Test
    fun query_uses_bounded_calls_projection_and_strict_cursor_boundary() {
        var actual: RecordedQuery? = null
        val reader = AndroidCallLogReader { target, projection, selection, selectionArgs, sortOrder, limit ->
            actual = RecordedQuery(target, projection, selection, selectionArgs, sortOrder, limit)
            null
        }

        reader.query(
            CallLogQuery(
                history = CallHistoryPolicy(fromEpochMs = 1_000L, maxRecords = 3),
                directions = setOf(CallDirection.MISSED, CallDirection.INCOMING),
                cursor = CallLogCursor(startedAtEpochMs = 1_500L, providerId = 42L),
            ),
        )

        val query = requireNotNull(actual)
        assertEquals(CallLogProviderTarget.CALLS, query.target)
        assertEquals(3, query.limit)
        assertArrayEquals(
            arrayOf("_id", "type", "date", "duration", "number", CallLog.Calls.NUMBER_PRESENTATION),
            query.projection,
        )
        assertEquals(
            "date >= ? AND type IN (?,?) AND (date > ? OR (date = ? AND _id > ?))",
            query.selection,
        )
        assertArrayEquals(arrayOf("1000", "1", "3", "1500", "1500", "42"), query.selectionArgs)
        assertEquals("date DESC, _id DESC", query.sortOrder)
    }

    @Test
    fun query_maps_supported_provider_types_and_presentations_and_closes_cursor() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(
                listOf(1L, 1, 10L, 0L, "+8613800000000", 1),
                listOf(2L, 2, 11L, 1L, null, 2),
                listOf(3L, 3, 12L, 2L, "hidden", 3),
                listOf(4L, 5, 13L, 3L, "payphone", 4),
                listOf(5L, 1, 14L, 4L, "unavailable", 5),
            ),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val rows = reader.query(allDirectionsQuery())

        assertEquals(
            listOf(
                CallDirection.INCOMING,
                CallDirection.OUTGOING,
                CallDirection.MISSED,
                CallDirection.REJECTED,
                CallDirection.INCOMING,
            ),
            rows.map(CallLogRow::direction),
        )
        assertEquals(
            listOf(
                CallNumberPresentation.ALLOWED,
                CallNumberPresentation.RESTRICTED,
                CallNumberPresentation.UNKNOWN,
                CallNumberPresentation.PAYPHONE,
                CallNumberPresentation.UNAVAILABLE,
            ),
            rows.map(CallLogRow::numberPresentation),
        )
        assertEquals("+8613800000000", rows.first().number)
        assertNull(rows[1].number)
        assertTrue(cursor.closed)
    }

    @Test
    fun query_locally_truncates_and_closes_cursor() {
        val cursor = CursorDouble(COLUMNS, listOf(
            listOf(1L, 1, 10L, 0L, "1", 1),
            listOf(2L, 1, 11L, 0L, "2", 1),
        ))
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val rows = reader.query(allDirectionsQuery(maxRecords = 1))

        assertEquals(listOf(1L), rows.map(CallLogRow::providerId))
        assertTrue(cursor.closed)
    }

    @Test
    fun query_null_cursor_is_an_empty_result() {
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> null }

        assertEquals(emptyList<CallLogRow>(), reader.query(allDirectionsQuery()))
    }

    @Test
    fun query_closes_cursor_for_missing_column_and_bad_row_without_provider_diagnostics() {
        val missingColumn = CursorDouble(COLUMNS - "number", emptyList())
        val badRow = CursorDouble(COLUMNS, listOf(listOf(0L, 1, 10L, 0L, "private", 1)))
        val readerForMissingColumn = AndroidCallLogReader { _, _, _, _, _, _ -> missingColumn.value }
        val readerForBadRow = AndroidCallLogReader { _, _, _, _, _, _ -> badRow.value }

        val missing = assertThrows(CallLogQueryException::class.java) {
            readerForMissingColumn.query(allDirectionsQuery())
        }
        val invalid = assertThrows(CallLogInvalidRowException::class.java) {
            readerForBadRow.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_QUERY_FAILED", missing.message)
        assertNull(missing.cause)
        assertEquals("CALL_LOG_INVALID_ROW", invalid.message)
        assertNull(invalid.cause)
        assertTrue(missingColumn.closed)
        assertTrue(badRow.closed)
    }

    @Test
    fun query_rejects_excluded_and_unknown_types_atomically_without_provider_diagnostics() {
        for (type in listOf(4, 6, 7, 999)) {
            val cursor = CursorDouble(COLUMNS, listOf(
                listOf(1L, 1, 10L, 0L, "private", 1),
                listOf(2L, type, 11L, 0L, "private", 1),
            ))
            val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

            val failure = assertThrows(CallLogInvalidRowException::class.java) {
                reader.query(allDirectionsQuery())
            }

            assertEquals("CALL_LOG_INVALID_ROW", failure.message)
            assertNull(failure.cause)
            assertTrue(cursor.closed)
        }
    }

    @Test
    fun query_rejects_invalid_identifiers_times_durations_and_end_time_overflow() {
        for (row in listOf(
            listOf(0L, 1, 10L, 0L, "private", 1),
            listOf(1L, 1, -1L, 0L, "private", 1),
            listOf(1L, 1, 10L, -1L, "private", 1),
            listOf(1L, 1, Long.MAX_VALUE, 1L, "private", 1),
        )) {
            val cursor = CursorDouble(COLUMNS, listOf(row))
            val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

            val failure = assertThrows(CallLogInvalidRowException::class.java) {
                reader.query(allDirectionsQuery())
            }

            assertEquals("CALL_LOG_INVALID_ROW", failure.message)
            assertNull(failure.cause)
            assertTrue(cursor.closed)
        }
    }

    @Test
    fun query_scrubs_provider_cursor_failures_and_closes_the_cursor() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = emptyList(),
            moveFailure = IllegalStateException("provider exposed 15551234567 at 1700000000000"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogQueryException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_QUERY_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_get_long_failure_to_query_failure_and_closes_the_cursor() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(listOf(1L, 1, 10L, 0L, "private", 1)),
            getLongFailure = IllegalStateException("provider exposed 15551234567"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogQueryException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_QUERY_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_get_string_failure_to_query_failure_and_closes_the_cursor() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(listOf(1L, 1, 10L, 0L, "private", 1)),
            getStringFailure = IllegalStateException("provider exposed 15551234567"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogQueryException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_QUERY_FAILED", failure.message)
        assertNull(failure.cause)
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_success_close_failure_to_query_failure_without_suppressed_provider_details() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(listOf(1L, 1, 10L, 0L, "private", 1)),
            closeFailure = IllegalStateException("provider exposed 15551234567 at 1700000000000"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogQueryException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_QUERY_FAILED", failure.message)
        assertNull(failure.cause)
        assertEquals(0, failure.suppressed.size)
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_move_to_next_security_exception_to_permission_required_without_provider_details() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = emptyList(),
            moveFailure = SecurityException("provider exposed 15551234567"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogPermissionRequiredException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_PERMISSION_REQUIRED", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains("15551234567"))
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_get_long_security_exception_to_permission_required_without_provider_details() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(listOf(1L, 1, 10L, 0L, "private", 1)),
            getLongFailure = SecurityException("provider exposed 15551234567"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogPermissionRequiredException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_PERMISSION_REQUIRED", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains("15551234567"))
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_close_security_exception_to_permission_required_without_provider_details() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(listOf(1L, 1, 10L, 0L, "private", 1)),
            closeFailure = SecurityException("provider exposed 15551234567"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogPermissionRequiredException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_PERMISSION_REQUIRED", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains("15551234567"))
        assertEquals(0, failure.suppressed.size)
        assertTrue(cursor.closed)
    }

    @Test
    fun probe_maps_cursor_security_exception_to_permission_required_in_availability() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = emptyList(),
            moveFailure = SecurityException("provider exposed 15551234567"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }
        val availability = AndroidCallLogAvailability(
            localEnabled = { true },
            providerAvailable = { true },
            permissionGranted = { true },
            probe = reader::probe,
        )

        assertEquals(CapabilityAvailability.PERMISSION_REQUIRED, availability.current())
        assertTrue(cursor.closed)
    }

    @Test
    fun query_does_not_swallow_fatal_cursor_failures() {
        listOf(
            OutOfMemoryError("fatal provider error"),
            LinkageError("fatal linkage error"),
            ThreadDeath(),
        ).forEach { fatal ->
            val cursor = CursorDouble(COLUMNS, emptyList(), moveFailure = fatal)
            val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

            val thrown = assertThrows(fatal::class.java) {
                reader.query(allDirectionsQuery())
            }

            assertSame(fatal, thrown)
            assertTrue(cursor.closed)
        }
    }

    @Test
    fun query_maps_bad_row_and_close_failure_to_query_failure_without_suppressed_provider_details() {
        val cursor = CursorDouble(
            columns = COLUMNS,
            rows = listOf(listOf(0L, 1, 10L, 0L, "private", 1)),
            closeFailure = IllegalStateException("provider exposed 15551234567 at 1700000000000"),
        )
        val reader = AndroidCallLogReader { _, _, _, _, _, _ -> cursor.value }

        val failure = assertThrows(CallLogQueryException::class.java) {
            reader.query(allDirectionsQuery())
        }

        assertEquals("CALL_LOG_QUERY_FAILED", failure.message)
        assertNull(failure.cause)
        assertEquals(0, failure.suppressed.size)
        assertTrue(cursor.closed)
    }

    private fun allDirectionsQuery(maxRecords: Int = 10): CallLogQuery = CallLogQuery(
        history = CallHistoryPolicy(fromEpochMs = null, maxRecords = maxRecords),
        directions = CallDirection.entries.toSet(),
    )

    private data class RecordedQuery(
        val target: CallLogProviderTarget,
        val projection: Array<String>,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String,
        val limit: Int,
    )

    private class CursorDouble(
        columns: List<String>,
        private val rows: List<List<Any?>>,
        private val moveFailure: Throwable? = null,
        private val getLongFailure: Throwable? = null,
        private val getStringFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) {
        private val indexes = columns.withIndex().associate { it.value to it.index }
        private var rowIndex = -1
        var closed = false
            private set

        val value: Cursor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Cursor::class.java)) { _, method, arguments ->
            when (method.name) {
                "getColumnIndexOrThrow" -> indexes[arguments!![0] as String]
                    ?: throw IllegalArgumentException("provider's private missing column")
                "moveToNext" -> {
                    moveFailure?.let { throw it }
                    (++rowIndex) < rows.size
                }
                "getLong" -> {
                    getLongFailure?.let { throw it }
                    (cell(arguments) as Number).toLong()
                }
                "getInt" -> (cell(arguments) as Number).toInt()
                "getString" -> {
                    getStringFailure?.let { throw it }
                    cell(arguments) as String?
                }
                "isNull" -> cell(arguments) == null
                "close" -> {
                    closed = true
                    closeFailure?.let { throw it }
                    null
                }
                "isClosed" -> closed
                "toString" -> "Provider cursor with private 15551234567"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === arguments!![0]
                else -> throw UnsupportedOperationException("unexpected cursor method ${method.name}")
            }
        } as Cursor

        private fun cell(arguments: Array<out Any?>?): Any? = rows[rowIndex][arguments!![0] as Int]
    }

    private companion object {
        val COLUMNS = listOf("_id", "type", "date", "duration", "number", CallLog.Calls.NUMBER_PRESENTATION)
    }
}
