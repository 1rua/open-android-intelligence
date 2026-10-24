package com.openandroidintelligence.calls

import com.openandroidintelligence.capability.CallDirection
import com.openandroidintelligence.capability.CallNumberPresentation
import com.openandroidintelligence.capability.CallsMetadata
import com.openandroidintelligence.capability.CallsPayload
import com.openandroidintelligence.capability.normalizeCallCounterpartyNumber
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CallLogWireCodecTest {
    private val codec: CallLogEventCodec = DeterministicCallLogEventCodec()
    private val record = CallsPayload(
        CallsMetadata("call:42", 1_700_000_000_999, CallDirection.INCOMING, 1_700_000_000_000, 1_700_000_060_000, 60, CallNumberPresentation.ALLOWED),
        normalizeCallCounterpartyNumber("+15551234567", CallNumberPresentation.ALLOWED, CallLogTestFixtures.readScope(CallLogTestFixtures.policy(counterpartyAccess = com.openandroidintelligence.capability.CallCounterpartyAccess.NUMBER))),
    )

    @Test fun `encodes released fixture in frozen key order`() {
        val expected = fixture("call-record-released.json")
        assertArrayEquals(expected, codec.encode("call:7:42", record, 7u, 9u))
    }

    @Test fun `decodes only recovery facts and redacts diagnostics`() {
        val decoded = codec.decode(fixture("call-record-released.json"))
        assertEquals("call:42", decoded.recordId)
        assertEquals(7uL, decoded.sourceEpoch)
        assertEquals(CallLogCursor(1_700_000_000_000, 42), decoded.cursor)
        assertEquals(9uL, decoded.captureRevision)
        assertEquals(9uL, decoded.policyRevision)
        require(!decoded.toString().contains("42"))
    }

    @Test fun `rejects duplicate reordered trailing and malformed wire`() {
        val wire = String(fixture("call-record-released.json"), Charsets.UTF_8)
        rejects(wire.replaceFirst("\"kind\":\"upsert\",", "\"kind\":\"upsert\",\"kind\":\"upsert\","))
        rejects(wire.replaceFirst("\"direction\":\"incoming\",", "\"direction\":\"incoming\",\"direction\":\"incoming\","))
        rejects(wire.replaceFirst("\"kind\":\"upsert\",\"record_id\":\"call:42\"", "\"record_id\":\"call:42\",\"kind\":\"upsert\""))
        rejects("$wire ")
        rejects(wire.replace("\"source_epoch\":\"7\"", "\"source_epoch\":\"07\""))
        rejects(wire.replace("\"direction\":\"incoming\"", "\"direction\":\"incoming\\q\""))
        rejects(wire.replace("\"direction\":\"incoming\"", "\"direction\":\"\\uD800\""))
        rejects(wire.replace("\"record_revision\":\"1\"", "\"record_revision\":\"2\""))
        rejects(wire.replace("\"record_id\":\"call:42\"", "\"record_id\":\"call:43\""))
        rejects(wire.replace("\"value\":\"+15551234567\"", "\"value\":\"${"x".repeat(257)}\""))
    }

    @Test fun `rejects missing extra nested reorder and unknown enum fields`() {
        val wire = String(fixture("call-record-released.json"), Charsets.UTF_8)
        rejects(wire.replace(",\"policy_revision\":\"9\"", ""))
        rejects(wire.dropLast(1) + ",\"extra\":true}")
        rejects(wire.replace("\"direction\":\"incoming\",\"started_at_epoch_ms\":\"1700000000000\"", "\"started_at_epoch_ms\":\"1700000000000\",\"direction\":\"incoming\""))
        rejects(wire.replace("\"direction\":\"incoming\"", "\"direction\":\"blocked\""))
    }

    @Test fun `proves the shared withheld fixture and rejects its repository newline`() {
        val withheld = CallsPayload(
            CallsMetadata("call:43", 1_700_000_100_999, CallDirection.MISSED, 1_700_000_100_000, 1_700_000_100_000, 0, CallNumberPresentation.RESTRICTED),
            normalizeCallCounterpartyNumber("+15551234567", CallNumberPresentation.RESTRICTED, CallLogTestFixtures.readScope()),
        )
        assertArrayEquals(fixture("call-record-withheld.json"), codec.encode("call:8:43", withheld, 8uL, 10uL))
        val raw = rawFixture("call-record-withheld.json")
        if (raw.lastOrNull() == '\n'.code.toByte()) rejects(raw.toString(Charsets.UTF_8))
    }

    @Test fun `accepts supplementary unicode raw and escaped but rejects lone surrogates`() {
        val raw = String(fixture("call-record-released.json"), Charsets.UTF_8).replace("+15551234567", "😀")
        assertEquals("call:42", codec.decode(raw.toByteArray()).recordId)
        assertEquals("call:42", codec.decode(raw.replace("😀", "\\uD83D\\uDE00").toByteArray()).recordId)
        val releasedEmoji = record.copy(counterpartyNumber = normalizeCallCounterpartyNumber("😀", CallNumberPresentation.ALLOWED, CallLogTestFixtures.readScope(CallLogTestFixtures.policy(counterpartyAccess = com.openandroidintelligence.capability.CallCounterpartyAccess.NUMBER))))
        assertEquals("call:42", codec.decode(codec.encode("call:7:42", releasedEmoji, 7uL, 9uL)).recordId)
        rejects(raw.replace("😀", "\\uD800"))
        rejects(raw.replace("😀", "\\uDC00"))
        rejects(raw.replace("😀", "😀".repeat(65)))
    }

    @Test fun `accepts Long and ULong boundaries only in their designated fields`() {
        val wire = String(fixture("call-record-released.json"), Charsets.UTF_8)
        rejects(wire.replace("\"source_epoch\":\"7\"", "\"source_epoch\":\"18446744073709551616\""))
        assertEquals(ULong.MAX_VALUE, codec.decode(wire.replace("\"source_epoch\":\"7\"", "\"source_epoch\":\"18446744073709551615\"").toByteArray()).sourceEpoch)
        val maxRevisions = wire
            .replace("\"capture_revision\":\"9\"", "\"capture_revision\":\"18446744073709551615\"")
            .replace("\"policy_revision\":\"9\"", "\"policy_revision\":\"18446744073709551615\"")
        assertEquals(ULong.MAX_VALUE, codec.decode(maxRevisions.toByteArray()).captureRevision)
        rejects(maxRevisions.replace("18446744073709551615", "18446744073709551616"))
        val atLongMax = wire
            .replace("\"cursor_started_at_epoch_ms\":\"1700000000000\"", "\"cursor_started_at_epoch_ms\":\"9223372036854775807\"")
            .replace("\"captured_at_epoch_ms\":\"1700000000999\"", "\"captured_at_epoch_ms\":\"9223372036854775807\"")
            .replace("\"started_at_epoch_ms\":\"1700000000000\"", "\"started_at_epoch_ms\":\"9223372036854775807\"")
            .replace("\"ended_at_epoch_ms\":\"1700000060000\"", "\"ended_at_epoch_ms\":\"9223372036854775807\"")
            .replace("\"duration_seconds\":\"60\"", "\"duration_seconds\":\"0\"")
            .replace("\"observed_at_epoch_ms\":\"1700000000999\"", "\"observed_at_epoch_ms\":\"9223372036854775807\"")
        assertEquals("call:42", codec.decode(atLongMax.toByteArray()).recordId)
        rejects(atLongMax.replace("9223372036854775807", "9223372036854775808"))
    }

    @Test fun `encoder rejects invalid event source identity and lone surrogate number`() {
        rejectsEncode { codec.encode("call:8:42", record, 7uL, 9uL) }
        rejectsEncode { codec.encode("call:0:42", record, 0uL, 9uL) }
        rejectsEncode { codec.encode("call:7:42", record.copy(metadata = record.metadata.copy(recordId = "call:43")), 7uL, 9uL) }
        val loneSurrogate = record.copy(counterpartyNumber = normalizeCallCounterpartyNumber("\uD800", CallNumberPresentation.ALLOWED, CallLogTestFixtures.readScope(CallLogTestFixtures.policy(counterpartyAccess = com.openandroidintelligence.capability.CallCounterpartyAccess.NUMBER))))
        rejectsEncode { codec.encode("call:7:42", loneSurrogate, 7uL, 9uL) }
    }

    private fun rejects(value: String) {
        try { codec.decode(value.toByteArray()) ; fail("expected strict decode failure") } catch (_: CallLogWireFormatException) { }
    }

    private fun rejectsEncode(operation: () -> Unit) {
        try { operation(); fail("expected encoder failure") } catch (_: IllegalArgumentException) { }
    }

    private fun fixture(name: String): ByteArray {
        val bytes = rawFixture(name)
        return if (bytes.lastOrNull() == '\n'.code.toByte()) bytes.dropLast(1).toByteArray() else bytes
    }

    private fun rawFixture(name: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/v1/$name")
            ?: error("fixture not found: fixtures/v1/$name")
        return stream.use { it.readBytes() }
    }
}
