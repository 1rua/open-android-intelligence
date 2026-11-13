package com.openandroidintelligence.sms

import org.junit.Assert.*
import org.junit.Test

class SmsPushSourceTest {
    @Test fun pushCaptureUsesRealReaderWithAscendingCursorAndMetadataProjection() {
        var actual: SmsInboxQuery? = null
        val rows = (1L..3).map { SmsInboxRow(it, null, "sender", "body", 100 + it, false, null) }
        val source = SmsPushSource(SmsInboxReader { query -> actual = query; rows })
        val accepted = mutableListOf<Long>()
        val result = source.capture(SmsPushCaptureRequest(50, senders = setOf("sender"), includeContent = false), { true }, enqueueDurably@{ row ->
            if (row.providerId == 3L) return@enqueueDurably false
            accepted += row.providerId
            true
        })
        assertTrue(actual!!.oldestFirst)
        assertFalse(actual!!.includeBody)
        assertEquals(setOf("sender"), actual!!.senders)
        assertEquals(listOf(1L, 2L), accepted)
        assertEquals(SmsCursor(2, 102), result.lastAccepted)
        assertTrue(result.paused)
        assertTrue(result.morePossible)
    }

    @Test fun missingLoginPermissionOrScopeDoesNotQueryTheProvider() {
        var calls = 0
        val source = SmsPushSource(SmsInboxReader { calls++; emptyList() })
        source.capture(SmsPushCaptureRequest(0), { true }, { error("UNEXPECTED_ADMISSION") })
        source.capture(SmsPushCaptureRequest(0, allSenders = true), { false }, { error("UNEXPECTED_ADMISSION") })
        assertEquals(0, calls)
    }
    @Test fun keywordMetadataCaptureFiltersAndAdvancesRejectedRowsWithoutBodies() {
        val rows = listOf(SmsInboxRow(1, null, "sender", "unrelated", 101, false, null),
            SmsInboxRow(2, null, "sender", "CAFÉ 10%_ O'NEAL", 102, false, null),
            SmsInboxRow(3, null, "sender", "unrelated", 103, false, null))
        val source = SmsPushSource(SmsInboxReader { rows })
        val captured = mutableListOf<SmsInboxRow>()
        val skipped = mutableListOf<SmsCursor>()
        val result = source.capture(SmsPushCaptureRequest(0, keywords = setOf("absent", "café", "%", "_", "'"),
            includeContent = false, maxRecords = 3), { true }, { skipped += it; true }) { captured += it; true }
        assertEquals(listOf(2L), captured.map { it.providerId })
        assertNull(captured.single().body)
        assertEquals(setOf("café", "%", "_", "'"), captured.single().matchedKeywords)
        assertEquals(listOf(SmsCursor(1, 101), SmsCursor(3, 103)), skipped)
        assertEquals(SmsCursor(3, 103), result.lastAccepted)
        assertTrue(result.morePossible)
        assertFalse(result.paused)
    }

    @Test fun legacyExactSendersAndFailedCursorPersistenceNeverWidenOrSkipRows() {
        val source = SmsPushSource(SmsInboxReader { listOf(
            SmsInboxRow(1, null, "BANK", "body", 101, false, null),
            SmsInboxRow(2, null, "bank", "body", 102, false, null),
            SmsInboxRow(3, null, "+8613800000000", "body", 103, false, null)) })
        val captured = mutableListOf<Long>()
        val result = source.capture(SmsPushCaptureRequest(0, senders = setOf("BANK", "+86 13800000000")),
            { true }, { false }) { captured += it.providerId; true }
        assertEquals(listOf(1L), captured)
        assertEquals(SmsCursor(1, 101), result.lastAccepted)
        assertTrue(result.paused)
    }
    @Test fun aFullRejectedPageAdvancesToTheNextMatchingPageWithoutAnUnboundedScan() {
        val rows = (1L..129L).map { SmsInboxRow(it, null, "sender", if (it == 129L) "code" else "not matched", it, false, null) }
        val source = SmsPushSource(SmsInboxReader { query ->
            rows.filter { query.cursor == null || SmsCursor(it.providerId, it.messageAtEpochMs) > query.cursor }
                .take(query.history.maxRecords)
        })
        var cursor: SmsCursor? = null
        val captured = mutableListOf<Long>()
        fun capture(): SmsPushCaptureResult = source.capture(SmsPushCaptureRequest(0, after = cursor,
            keywords = setOf("CODE"), includeContent = false), { true }, { cursor = it; true }) {
                captured += it.providerId; cursor = SmsCursor(it.providerId, it.messageAtEpochMs); true
            }
        val first = capture()
        assertEquals(128, first.read)
        assertEquals(0, first.accepted)
        assertEquals(SmsCursor(128, 128), cursor)
        assertTrue(first.morePossible)
        val second = capture()
        assertEquals(1, second.read)
        assertEquals(listOf(129L), captured)
        assertFalse(second.morePossible)
    }
}
