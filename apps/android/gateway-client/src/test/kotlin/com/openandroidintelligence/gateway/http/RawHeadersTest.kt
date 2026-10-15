package com.openandroidintelligence.gateway.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headers are validated from the raw list, not from a parsed map: a map silently
 * keeps one of two conflicting values. A duplicated or obs-fold continued
 * singleton header is an ambiguity the caller must not be allowed to resolve.
 */
class RawHeadersTest {

    @Test
    fun duplicatedHistoryMediaFormatFailsClosed() {
        val failure = runCatching {
            RawHeaders.validate(headers(
                "X-Open-Android-Intelligence-Media-Format" to "stream-v1",
                "x-open-android-intelligence-media-format" to "legacy-json",
            ))
        }.exceptionOrNull()
        assertTrue("重复格式标记不能让原始 JSON 附件被当作协议外壳", failure?.message?.startsWith("DUPLICATE_HEADER") == true)
    }

    private fun headers(vararg pairs: Pair<String, String>) =
        pairs.map { (name, value) -> RawHeader(name, value) }

    @Test
    fun wellFormedHeadersPass() {
        val validated = RawHeaders.validate(headers("Content-Length" to "12", "Digest" to "sha-256=abc"))
        assertEquals(2, validated.size)
    }

    @Test
    fun duplicatedSingletonFailsClosed() {
        val failure = runCatching {
            RawHeaders.validate(headers("Content-Length" to "12", "Content-Length" to "99"))
        }.exceptionOrNull()

        assertTrue("two Content-Length values must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("DUPLICATE_HEADER"))
    }

    @Test
    fun duplicatedDigestFailsClosed() {
        val failure = runCatching {
            RawHeaders.validate(headers("Digest" to "sha-256=aaa", "Digest" to "sha-256=bbb"))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("DUPLICATE_HEADER"))
    }

    @Test
    fun foldedSingletonFailsClosed() {
        val failure = runCatching {
            RawHeaders.validate(headers("Content-Length" to "12", "Content-Length" to " 99"))
        }.exceptionOrNull()

        assertTrue("obs-fold continuation of a singleton must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("DUPLICATE_HEADER"))
    }

    @Test
    fun caseInsensitiveDuplicateIsCaught() {
        val failure = runCatching {
            RawHeaders.validate(headers("content-length" to "12", "Content-Length" to "12"))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("DUPLICATE_HEADER"))
    }

    @Test
    fun repeatedNonSingletonHeaderIsAllowed() {
        val validated = RawHeaders.validate(headers("Set-Cookie" to "a=1", "Set-Cookie" to "b=2"))
        assertEquals(2, validated.size)
    }

    @Test
    fun headerNameWithWhitespaceIsRejected() {
        val failure = runCatching { RawHeaders.validate(headers("Content Length" to "12")) }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("MALFORMED_HEADER"))
    }

    @Test
    fun singletonLookupReturnsTheOnlyValue() {
        val validated = RawHeaders.validate(headers("Content-Length" to "12"))
        assertEquals("12", RawHeaders.single(validated, "content-length"))
    }
}
