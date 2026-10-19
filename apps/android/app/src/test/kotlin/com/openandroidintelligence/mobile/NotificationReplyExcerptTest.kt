package com.openandroidintelligence.mobile

import org.junit.Assert.*
import org.junit.Test

class NotificationReplyExcerptTest {
    @Test fun shortRepliesRemainExactInBothPresentations() {
        listOf("", "Short reply", "中文回复 😀🚀").forEach { text ->
            assertEquals(text, notificationReplyExcerpt(text, isRunning = true))
            assertEquals(text, notificationReplyExcerpt(text, isRunning = false))
        }
    }

    @Test fun aLongSharedPrefixDoesNotHideTheNewestStreamedTailOrFinalEnding() {
        val prefix = "A".repeat(6_000)
        val first = notificationReplyExcerpt(prefix + "first update", isRunning = true)
        val second = notificationReplyExcerpt(prefix + "second update", isRunning = true)
        assertNotEquals(first, second)
        assertTrue(first.startsWith("…"))
        assertTrue(second.endsWith("second update"))
        assertEquals(RUNNING_REPLY_EXCERPT_CODE_POINTS, second.codePointCount(0, second.length))
        val terminal = notificationReplyExcerpt("Opening line. " + prefix + "Final answer ending.", isRunning = false)
        assertTrue(terminal.startsWith("Opening line. "))
        assertTrue(terminal.contains("Final answer ending.\n\n$COMPLETE_REPLY_HINT"))
        assertEquals(TERMINAL_REPLY_EXCERPT_CODE_POINTS, terminal.codePointCount(0, terminal.length))
    }

    @Test fun supplementaryUnicodeCharactersNeverSplitAtEitherExcerptBoundary() {
        val text = "😀🚀".repeat(3_000)
        listOf(true, false).forEach { running ->
            val excerpt = notificationReplyExcerpt(text, running)
            var index = 0
            while (index < excerpt.length) {
                val char = excerpt[index]
                if (Character.isHighSurrogate(char)) {
                    assertTrue("A high surrogate must keep its low surrogate", index + 1 < excerpt.length && Character.isLowSurrogate(excerpt[index + 1]))
                    index += 2
                } else {
                    assertFalse("A low surrogate cannot start a code point", Character.isLowSurrogate(char))
                    index++
                }
            }
            val limit = if (running) RUNNING_REPLY_EXCERPT_CODE_POINTS else TERMINAL_REPLY_EXCERPT_CODE_POINTS
            assertEquals(limit, excerpt.codePointCount(0, excerpt.length))
        }
    }
}
