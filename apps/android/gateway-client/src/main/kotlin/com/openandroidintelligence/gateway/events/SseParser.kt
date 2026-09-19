package com.openandroidintelligence.gateway.events

import java.io.IOException

/**
 * One SSE frame, emitted only once its terminating blank line has arrived.
 */
data class GatewayEvent(
    val id: String?,
    val event: String?,
    val data: String,
) {
    /**
     * A cancel notice records an intent; it is not an outcome. Only a trusted
     * result decides `cancelled`, another real terminal state, or
     * `outcome_unknown`.
     */
    val isTerminalDeviceRequestOutcome: Boolean
        get() = event in TERMINAL_DEVICE_REQUEST_EVENTS

    private companion object {
        val TERMINAL_DEVICE_REQUEST_EVENTS = setOf(
            "device.request.succeeded",
            "device.request.failed",
            "device.request.denied",
            "device.request.cancelled",
            "device.request.outcome_unknown",
        )
    }
}

/**
 * Byte-oriented SSE parser.
 *
 * The stream is framed by a blank line, not by lines: a chunk boundary can fall
 * anywhere, including in the middle of a UTF-8 character, so bytes are buffered
 * until a complete frame terminator is seen. Nothing is emitted or committed
 * before then.
 */
class SseParser(private val onEvent: (GatewayEvent) -> Unit = {}) {

    private val buffer = java.io.ByteArrayOutputStream()
    private var cursor: String? = null
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    fun feedBytes(chunk: ByteArray): List<GatewayEvent> {
        if (buffer.size() + chunk.size > MAX_BUFFERED_BYTES) {
            throw IOException("SSE_BUFFER_OVERFLOW: frame terminator not found within $MAX_BUFFERED_BYTES bytes")
        }
        buffer.write(chunk, 0, chunk.size)
        return drain()
    }

    fun feed(chunk: String): List<GatewayEvent> = feedBytes(chunk.toByteArray(Charsets.UTF_8))

    fun reset() {
        buffer.reset()
        cursor = null
        eventName = null
        dataLines.clear()
    }

    private fun drain(): List<GatewayEvent> {
        val buffered = buffer.toByteArray()
        val emitted = mutableListOf<GatewayEvent>()
        var offset = 0
        while (offset < buffered.size) {
            val terminator = findTerminator(buffered, offset) ?: break

            // Decode only up to the terminator; trailing bytes stay buffered.
            val frame = String(buffered, offset, terminator.start - offset, Charsets.UTF_8)
            offset = terminator.endExclusive

            parseFrame(frame)?.let { event ->
                onEvent(event)
                emitted += event
            }
        }
        if (offset > 0) {
            buffer.reset()
            if (offset < buffered.size) {
                buffer.write(buffered, offset, buffered.size - offset)
            }
        }
        return emitted
    }

    /**
     * A frame ends at the first blank line. Both LF and CRLF are accepted,
     * including \n\n, \r\n\r\n, \n\r\n, and \r\n\n. A lone CR or lone LF is not
     * treated as a terminator, and bounds are strictly checked.
     */
    private fun findTerminator(bytes: ByteArray, startIndex: Int = 0): Terminator? {
        var index = startIndex
        while (index < bytes.size) {
            val firstLen = matchNewline(bytes, index)
            if (firstLen > 0) {
                val secondLen = matchNewline(bytes, index + firstLen)
                if (secondLen > 0) {
                    return Terminator(index, index + firstLen + secondLen)
                }
                index += firstLen
            } else {
                index += 1
            }
        }
        return null
    }

    private fun matchNewline(bytes: ByteArray, index: Int): Int {
        if (index >= bytes.size) return 0
        val cr = '\r'.code.toByte()
        val lf = '\n'.code.toByte()
        return when {
            bytes[index] == cr && index + 1 < bytes.size && bytes[index + 1] == lf -> 2
            bytes[index] == lf -> 1
            else -> 0
        }
    }

    private fun parseFrame(frame: String): GatewayEvent? {
        resetFrameState()
        for (rawLine in frame.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            if (line.isEmpty()) continue
            if (line.startsWith(":")) continue

            val separator = line.indexOf(':')
            val field = if (separator == -1) line else line.substring(0, separator)
            var value = if (separator == -1) "" else line.substring(separator + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                "id" -> cursor = value
                "event" -> eventName = value
                "data" -> dataLines += value
            }
        }

        // A frame with no data line carries no event, but still resets state.
        if (dataLines.isEmpty()) return null

        val event = GatewayEvent(
            id = cursor,
            event = eventName,
            data = dataLines.joinToString("\n"),
        )
        resetFrameState()
        return event
    }

    /**
     * The cursor belongs to the frame that carried it. An event with no `id`
     * line advances nothing, so a later frame must not inherit an earlier id.
     */
    private fun resetFrameState() {
        cursor = null
        eventName = null
        dataLines.clear()
    }

    private class Terminator(val start: Int, val endExclusive: Int)

    companion object {
        const val MAX_BUFFERED_BYTES = 10 * 1024 * 1024
    }
}
