package com.openandroidintelligence.core.model

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Task9CanonicalVectorsTest {
    @Test
    fun event_and_ack_vectors_have_independent_jcs_bytes_and_digests() {
        val fixtureText = loadFixtureText()
        val root = JsonParser(fixtureText).parse() as Map<*, *>
        assertEquals("task9-event-ack-v1", root["vector_set"])
        assertEquals("1.0", root["protocol_version"])
        val vectors = root["vectors"] as List<*>
        assertEquals(
            listOf("device_event_upsert_metadata_cursor_1", "event_ack_basic_metadata_cursor"),
            vectors.map { (it as Map<*, *>)["id"] },
        )

        for (rawVector in vectors) {
            val vector = rawVector as Map<*, *>
            assertTrue(vector["message_type"] == "device_event" || vector["message_type"] == "event_ack")
            val canonical = canonicalJson(vector["payload"])
            val bytes = canonical.toByteArray(Charsets.UTF_8)
            assertEquals(vector["jcs_base64"], Base64.getEncoder().encodeToString(bytes))
            assertEquals(
                vector["sha256_base64url"],
                Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)),
            )
            assertEquals(vector["utf8_bytes"], bytes.size.toString())
        }
    }

    private fun loadFixtureText(): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/v1/event-ack-vectors.json")
            ?: error("Task 9 canonical vector fixture not found in test resources")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

private data class JsonNumber(val raw: String)

/** Test-only JSON parser kept independent from the TypeScript canonicalizer. */
private class JsonParser(private val source: String) {
    private var index = 0

    fun parse(): Any? {
        val value = parseValue()
        whitespace()
        check(index == source.length) { "trailing JSON input" }
        return value
    }

    private fun parseValue(): Any? {
        whitespace()
        check(index < source.length) { "unexpected end of JSON" }
        return when (source[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        whitespace()
        val output = linkedMapOf<String, Any?>()
        if (take('}')) return output
        while (true) {
            whitespace()
            val key = parseString()
            check(!output.containsKey(key)) { "duplicate object key" }
            whitespace()
            expect(':')
            output[key] = parseValue()
            whitespace()
            if (take('}')) return output
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        whitespace()
        val output = mutableListOf<Any?>()
        if (take(']')) return output
        while (true) {
            output += parseValue()
            whitespace()
            if (take(']')) return output
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val output = StringBuilder()
        while (index < source.length) {
            val current = source[index++]
            when (current) {
                '"' -> return output.toString()
                '\\' -> {
                    check(index < source.length) { "unterminated escape" }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> output.append(escaped)
                        'b' -> output.append('\b')
                        'f' -> output.append('\u000c')
                        'n' -> output.append('\n')
                        'r' -> output.append('\r')
                        't' -> output.append('\t')
                        'u' -> {
                            check(index + 4 <= source.length) { "short unicode escape" }
                            output.append(source.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> error("invalid JSON escape: $escaped")
                    }
                }
                else -> {
                    check(current.code >= 0x20) { "unescaped control character" }
                    output.append(current)
                }
            }
        }
        error("unterminated string")
    }

    private fun parseNumber(): JsonNumber {
        val start = index
        if (source.getOrNull(index) == '-') index += 1
        if (source.getOrNull(index) == '0') {
            index += 1
        } else {
            check(source.getOrNull(index)?.isDigit() == true) { "invalid number" }
            while (source.getOrNull(index)?.isDigit() == true) index += 1
        }
        if (source.getOrNull(index) == '.') {
            index += 1
            check(source.getOrNull(index)?.isDigit() == true) { "invalid fraction" }
            while (source.getOrNull(index)?.isDigit() == true) index += 1
        }
        if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
            index += 1
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index += 1
            check(source.getOrNull(index)?.isDigit() == true) { "invalid exponent" }
            while (source.getOrNull(index)?.isDigit() == true) index += 1
        }
        return JsonNumber(source.substring(start, index))
    }

    private fun <T> literal(token: String, value: T): T {
        check(source.startsWith(token, index)) { "invalid literal" }
        index += token.length
        return value
    }

    private fun whitespace() {
        while (source.getOrNull(index)?.isWhitespace() == true) index += 1
    }

    private fun expect(character: Char) {
        check(take(character)) { "expected '$character' at $index" }
    }

    private fun take(character: Char): Boolean {
        if (source.getOrNull(index) != character) return false
        index += 1
        return true
    }
}

private fun canonicalJson(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "true" else "false"
    is JsonNumber -> value.raw
    is String -> canonicalString(value)
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
    is Map<*, *> -> value.entries
        .map { (key, member) -> (key as String) to member }
        .sortedBy { it.first }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, member) ->
            "${canonicalString(key)}:${canonicalJson(member)}"
        }
    else -> error("unsupported JSON value ${value::class}")
}

private fun canonicalString(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
