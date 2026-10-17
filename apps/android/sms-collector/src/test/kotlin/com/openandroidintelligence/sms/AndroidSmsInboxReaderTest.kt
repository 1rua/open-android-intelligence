package com.openandroidintelligence.sms

import android.database.Cursor
import com.openandroidintelligence.capability.SmsHistoryPolicy
import java.lang.reflect.Proxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSmsInboxReaderTest {
    @Test
    fun query_uses_only_the_inbox_uri_projection_bound_history_start_and_limit() {
        var actual: RecordedQuery? = null
        val reader = AndroidSmsInboxReader { target, projection, selection, selectionArgs, sortOrder ->
            actual = RecordedQuery(target, projection, selection, selectionArgs, sortOrder)
            null
        }

        reader.query(SmsInboxQuery(SmsHistoryPolicy(fromEpochMs = 1_700L, maxRecords = 3)))

        val query = requireNotNull(actual)
        assertEquals(SmsProviderTarget.INBOX, query.target)
        assertArrayEquals(arrayOf("_id", "thread_id", "address", "body", "date", "read", "sub_id"), query.projection)
        assertEquals("date >= ?", query.selection)
        assertArrayEquals(arrayOf("1700"), query.selectionArgs)
        assertEquals("date DESC, _id DESC", query.sortOrder)
    }

    @Test
    fun query_adds_a_strict_cursor_boundary_with_bound_values() {
        var actual: RecordedQuery? = null
        val reader = AndroidSmsInboxReader { target, projection, selection, selectionArgs, sortOrder ->
            actual = RecordedQuery(target, projection, selection, selectionArgs, sortOrder)
            null
        }

        reader.query(
            SmsInboxQuery(
                history = SmsHistoryPolicy(fromEpochMs = 1_000L, maxRecords = 2),
                cursor = SmsCursor(providerId = 42L, messageAtEpochMs = 1_500L),
            ),
        )

        val query = requireNotNull(actual)
        assertEquals("date >= ? AND (date > ? OR (date = ? AND _id > ?))", query.selection)
        assertArrayEquals(arrayOf("1000", "1500", "1500", "42"), query.selectionArgs)
        assertEquals("date DESC, _id DESC", query.sortOrder)
    }

    @Test
    fun query_binds_sender_and_matches_literal_unicode_text_before_limiting_results() {
        var actual: RecordedQuery? = null
        val cursor = CursorDouble(
            columns = listOf("_id", "thread_id", "address", "body", "date", "read", "sub_id"),
            rows = listOf(
                listOf(43L, "9", "bank", "new unrelated message", 1800L, 1, null),
                listOf(42L, "9", "bank", "CAFÉ 10%_\\", 1700L, 1, null),
                listOf(41L, "9", "bank", "CAFÉ 10%_\\", 1600L, 1, null),
            ),
        )
        val reader = AndroidSmsInboxReader { target, projection, selection, args, sort ->
            actual = RecordedQuery(target, projection, selection, args, sort)
            cursor.value
        }

        val rows = reader.query(SmsInboxQuery(SmsHistoryPolicy(null, 1), senders = setOf("bank", "friend"), textQuery = "café 10%_\\"))

        assertEquals("address IN (?,?)", actual!!.selection)
        assertArrayEquals(arrayOf("bank", "friend"), actual!!.selectionArgs)
        assertEquals(listOf(42L), rows.map { it.providerId })
        assertTrue(cursor.closed)
    }

    @Test
    fun query_maps_null_subscription_id_and_closes_the_cursor_after_success() {
        val cursor = CursorDouble(
            columns = listOf("_id", "thread_id", "address", "body", "date", "read", "sub_id"),
            rows = listOf(listOf(42L, "9", "+8613800000000", "complete body", 1_700L, 1, null)),
        )
        val reader = AndroidSmsInboxReader { _, _, _, _, _ -> cursor.value }

        val row = reader.query(SmsInboxQuery(SmsHistoryPolicy(fromEpochMs = null, maxRecords = 1))).single()

        assertEquals(42L, row.providerId)
        assertEquals("complete body", row.body)
        assertNull(row.subscriptionId)
        assertTrue(cursor.closed)
    }

    @Test
    fun query_closes_the_cursor_when_row_mapping_fails() {
        val cursor = CursorDouble(
            columns = listOf("_id", "thread_id", "address", "date", "read", "sub_id"),
            rows = emptyList(),
        )
        val reader = AndroidSmsInboxReader { _, _, _, _, _ -> cursor.value }

        assertThrows(IllegalArgumentException::class.java) {
            reader.query(SmsInboxQuery(SmsHistoryPolicy(fromEpochMs = null, maxRecords = 1)))
        }
        assertTrue(cursor.closed)
    }

    private data class RecordedQuery(
        val target: SmsProviderTarget,
        val projection: Array<String>,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String,
    )

    private class CursorDouble(
        columns: List<String>,
        private val rows: List<List<Any?>>,
    ) {
        private val columnIndexes = columns.withIndex().associate { it.value to it.index }
        private var rowIndex = -1
        var closed: Boolean = false
            private set

        val value: Cursor = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getColumnIndexOrThrow" -> columnIndexes[arguments!![0] as String]
                    ?: throw IllegalArgumentException("missing column ${arguments[0]}")
                "moveToNext" -> (++rowIndex) < rows.size
                "getLong" -> (cell(arguments) as Number).toLong()
                "getInt" -> (cell(arguments) as Number).toInt()
                "getString" -> cell(arguments) as String?
                "isNull" -> cell(arguments) == null
                "close" -> { closed = true; null }
                "isClosed" -> closed
                "toString" -> "CursorDouble"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === arguments!![0]
                else -> throw UnsupportedOperationException("unexpected Cursor method ${method.name}")
            }
        } as Cursor

        private fun cell(arguments: Array<out Any?>?): Any? = rows[rowIndex][arguments!![0] as Int]
    }
}
