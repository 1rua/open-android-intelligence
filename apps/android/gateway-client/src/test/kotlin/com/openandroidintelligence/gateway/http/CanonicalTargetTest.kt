package com.openandroidintelligence.gateway.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature covers the canonical target, so a target that is not already
 * canonical must be rejected rather than silently rewritten: rewriting it would
 * make the signature cover bytes other than those on the wire.
 */
class CanonicalTargetTest {

    @Test
    fun canonicalTargetPassesThroughUnchanged() {
        // Query pairs are ordered by name, so `cursor` sorts before `limit`.
        val target = "/open-android-intelligence/v2/conversations?cursor=abc&limit=10"
        assertEquals(target, CanonicalTarget.canonicalize(target))
    }

    @Test
    fun unsortedQueryIsRejectedRatherThanSilentlyReordered() {
        val failure = runCatching {
            CanonicalTarget.canonicalize("/open-android-intelligence/v2/conversations?limit=10&cursor=abc")
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("NON_CANONICAL_TARGET"))
    }

    @Test
    fun nonCanonicalTargetIsRejected() {
        val failure = runCatching {
            CanonicalTarget.canonicalize("/open-android-intelligence/v2/../v2/conversations")
        }.exceptionOrNull()

        assertTrue("a non-canonical target must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("NON_CANONICAL_TARGET"))
    }

    @Test
    fun unsignedQueryIsRejected() {
        val failure = runCatching { CanonicalTarget.canonicalize("/open-android-intelligence/v2/x?b=2&a=1") }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("NON_CANONICAL_TARGET"))
    }

    @Test
    fun lowercasePercentEncodingIsRejected() {
        val failure = runCatching { CanonicalTarget.canonicalize("/open-android-intelligence/v2/a%2fb") }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("NON_CANONICAL_TARGET"))
    }

    @Test
    fun nonSupportedSchemeIsRejectedByTheFactory() {
        val factory = GatewayConnectionFactory()
        val failure = runCatching { factory.open(java.net.URL("ftp://gateway.example.com/open-android-intelligence/v2")) }
            .exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun signaturePreimageCoversExactlyTenLines() {
        val input = SignedRequestInput(
            method = "POST",
            target = "/open-android-intelligence/v2/conversations",
            accountId = "acct-1",
            deviceId = "dev-1",
            sessionId = "sess-1",
            requestId = "req-1",
            timestamp = "2026-08-29T00:00:00.000Z",
            nonce = "AAAAAAAAAAAAAAAAAAAAAA",
            body = "{\"a\":1}".toByteArray(),
        )
        val preimage = String(RequestSigner.preimage(input), Charsets.UTF_8)
        val lines = preimage.split("\n")

        assertEquals(10, lines.size)
        assertEquals("OPEN-ANDROID-INTELLIGENCE-REQUEST-V2", lines[0])
        assertEquals("POST", lines[1])
        assertEquals(input.target, lines[2])
        assertTrue("the body is covered by its digest, not its bytes", lines[9].matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun signatureRejectsNonCanonicalTarget() {
        val input = SignedRequestInput(
            method = "GET",
            target = "/open-android-intelligence/v2/x?b=2&a=1",
            accountId = "acct-1",
            deviceId = "dev-1",
            sessionId = "sess-1",
            requestId = "req-1",
            timestamp = "2026-08-29T00:00:00.000Z",
            nonce = "AAAAAAAAAAAAAAAAAAAAAA",
            body = ByteArray(0),
        )
        val failure = runCatching { RequestSigner.preimage(input) }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("NON_CANONICAL_TARGET"))
    }
}
