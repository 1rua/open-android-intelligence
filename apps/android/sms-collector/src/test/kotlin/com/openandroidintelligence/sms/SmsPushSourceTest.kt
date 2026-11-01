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
}
