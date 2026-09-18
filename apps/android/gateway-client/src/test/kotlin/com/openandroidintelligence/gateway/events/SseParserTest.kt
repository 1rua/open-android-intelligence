package com.openandroidintelligence.gateway.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE is byte framing, not line framing: an event is only real once its
 * terminating blank line arrives. A chunk boundary may fall anywhere, including
 * inside a UTF-8 character, so nothing may be committed before the terminator.
 */
class SseParserTest {

    private fun parser(cursorStore: EventCursorStore = InMemoryEventCursorStore(), accountId: String = "acct-1"): SseParser =
        SseParser { event -> event.id?.let { cursorStore.save(accountId, it) } }

    @Test
    fun resumesAfterLastCompleteEventOnly() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        parser.feed("id: e1\nevent: gateway.notice\ndata: {}\n\nid: e2\ndata:")

        assertEquals("e1", cursorStore.load("acct-1"))
    }

    @Test
    fun incompleteEventIsNotEmitted() {
        val parser = parser()
        val events = parser.feed("id: e1\nevent: gateway.notice\ndata: hel")
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventArrivesWhenTerminatorCompletesTheFrame() {
        val parser = parser()
        parser.feed("id: e1\nevent: gateway.notice\ndata: hel")
        val events = parser.feed("lo\n\n")

        assertEquals(1, events.size)
        assertEquals("gateway.notice", events[0].event)
        assertEquals("hello", events[0].data)
        assertEquals("e1", events[0].id)
    }

    @Test
    fun multiChunkSplitInsideUtf8CharacterStillReassembles() {
        val parser = parser()
        val text = "héllo"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val splitAt = bytes.indexOf(0xC3.toByte())

        parser.feed("id: e1\nevent: gateway.notice\ndata: ")
        parser.feedBytes(bytes.copyOfRange(0, splitAt))
        val events = parser.feedBytes(bytes.copyOfRange(splitAt, bytes.size) + "\n\n".toByteArray())

        assertEquals(1, events.size)
        assertEquals(text, events[0].data)
    }

    @Test
    fun carriageReturnLineFeedIsAccepted() {
        val parser = parser()
        val events = parser.feed("id: e1\r\nevent: gateway.notice\r\ndata: {}\r\n\r\n")

        assertEquals(1, events.size)
        assertEquals("{}", events[0].data)
    }

    @Test
    fun eventWithoutIdDoesNotAdvanceCursor() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        parser.feed("event: gateway.notice\ndata: {}\n\n")

        assertEquals(null, cursorStore.load("acct-1"))
    }

    @Test
    fun multipleDataLinesJoinWithNewline() {
        val parser = parser()
        val events = parser.feed("id: e1\nevent: gateway.notice\ndata: one\ndata: two\n\n")

        assertEquals("one\ntwo", events[0].data)
    }

    @Test
    fun cancelRequestedIsAnIntentNotATerminalState() {
        val parser = parser()
        val events = parser.feed(
            "id: e1\nevent: device.request.cancel.requested\ndata: {\"requestId\":\"r1\"}\n\n",
        )

        assertEquals(1, events.size)
        assertEquals("device.request.cancel.requested", events[0].event)
        assertEquals(false, events[0].isTerminalDeviceRequestOutcome)
    }

    @Test
    fun cursorAdvancesToNewestCompleteEvent() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        parser.feed("id: e1\nevent: a\ndata: {}\n\nid: e2\nevent: b\ndata: {}\n\n")

        assertEquals("e2", cursorStore.load("acct-1"))
    }

    @Test
    fun commentsAreIgnored() {
        val parser = parser()
        val events = parser.feed(": keepalive\n\nid: e1\nevent: gateway.notice\ndata: {}\n\n")

        assertEquals(1, events.size)
        assertEquals("e1", events[0].id)
    }

    @Test
    fun singleByteChunkingReassemblesEvent() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)
        val raw = "id: chunk-1\nevent: notice\ndata: payload\n\n"
        val rawBytes = raw.toByteArray(Charsets.UTF_8)

        val emitted = mutableListOf<GatewayEvent>()
        for (i in 0 until rawBytes.size - 1) {
            emitted += parser.feedBytes(byteArrayOf(rawBytes[i]))
            assertTrue("Should not emit before terminator completes", emitted.isEmpty())
        }
        emitted += parser.feedBytes(byteArrayOf(rawBytes.last()))

        assertEquals(1, emitted.size)
        assertEquals("chunk-1", emitted[0].id)
        assertEquals("notice", emitted[0].event)
        assertEquals("payload", emitted[0].data)
        assertEquals("chunk-1", cursorStore.load("acct-1"))
    }

    @Test
    fun trailingSingleNewlineDoesNotTriggerEmission() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        // Trailing single \n
        var events = parser.feed("id: e1\nevent: notice\ndata: hello\n")
        assertTrue(events.isEmpty())
        assertEquals(null, cursorStore.load("acct-1"))

        // Completing the terminator with second \n
        events = parser.feed("\n")
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
        assertEquals("e1", cursorStore.load("acct-1"))
    }

    @Test
    fun trailingSingleCrLfDoesNotTriggerEmission() {
        val cursorStore = InMemoryEventCursorStore()
        val parser = parser(cursorStore)

        // Trailing \r\n (one line break, not a blank line)
        var events = parser.feed("id: e2\nevent: notice\ndata: world\r\n")
        assertTrue(events.isEmpty())
        assertEquals(null, cursorStore.load("acct-1"))

        // Complete with \r\n
        events = parser.feed("\r\n")
        assertEquals(1, events.size)
        assertEquals("world", events[0].data)
        assertEquals("e2", cursorStore.load("acct-1"))
    }

    @Test
    fun allTerminatorVariantsAreSupported() {
        val parser = parser()

        // 1. \n\n
        var events = parser.feed("data: variant1\n\n")
        assertEquals(1, events.size)
        assertEquals("variant1", events[0].data)

        // 2. \r\n\r\n
        events = parser.feed("data: variant2\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("variant2", events[0].data)

        // 3. \n\r\n
        events = parser.feed("data: variant3\n\r\n")
        assertEquals(1, events.size)
        assertEquals("variant3", events[0].data)

        // 4. \r\n\n
        events = parser.feed("data: variant4\r\n\n")
        assertEquals(1, events.size)
        assertEquals("variant4", events[0].data)
    }

    @Test
    fun boundaryPartialTerminatorsDoNotThrowOutOfBounds() {
        val parser = parser()

        // Partial sequences at chunk boundaries must not throw or emit prematurely
        assertTrue(parser.feed("data: test\r").isEmpty())
        assertTrue(parser.feed("\n").isEmpty())
        assertTrue(parser.feed("\r").isEmpty())
        val events = parser.feed("\n") // Completes \r\n\r\n
        assertEquals(1, events.size)
        assertEquals("test", events[0].data)

        // Ending with \n\r
        assertTrue(parser.feed("data: test2\n\r").isEmpty())
        val events2 = parser.feed("\n") // Completes \n\r\n
        assertEquals(1, events2.size)
        assertEquals("test2", events2[0].data)
    }
}
