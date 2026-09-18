package com.openandroidintelligence.gateway.http

import java.security.MessageDigest

data class SignedRequestInput(
    val method: String,
    val target: String,
    val accountId: String,
    val deviceId: String,
    val sessionId: String,
    val requestId: String,
    val timestamp: String,
    val nonce: String,
    val body: ByteArray,
)

/**
 * Builds the exact byte string the device key signs.
 *
 * The preimage covers the canonical target and the digest of the exact body
 * bytes, never a re-serialised body, so a proxy that rewrites the target or the
 * payload cannot produce a signature that still verifies.
 */
object RequestSigner {

    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
    private val WIRE_ID = Regex("^[A-Za-z0-9._~-]{1,128}$")
    private val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
    private val TIMESTAMP_FORMATTER = java.time.format.DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)

    fun preimage(input: SignedRequestInput): ByteArray {
        if (input.method !in METHODS) throw IllegalArgumentException("SCHEMA_INVALID:method")
        requireWireId(input.accountId, "accountId")
        requireWireId(input.deviceId, "deviceId")
        requireWireId(input.sessionId, "sessionId")
        requireWireId(input.requestId, "requestId")
        requireTimestamp(input.timestamp)
        requireCanonicalNonce(input.nonce)

        val target = CanonicalTarget.canonicalize(input.target)
        val bodyDigest = MessageDigest.getInstance("SHA-256").digest(input.body)
            .joinToString("") { "%02x".format(it) }

        return listOf(
            "OPEN-ANDROID-INTELLIGENCE-REQUEST-V2",
            input.method,
            target,
            input.accountId,
            input.deviceId,
            input.sessionId,
            input.requestId,
            input.timestamp,
            input.nonce,
            bodyDigest,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun requireWireId(value: String, field: String) {
        if (!WIRE_ID.matches(value)) throw IllegalArgumentException("SCHEMA_INVALID:$field")
    }

    /**
     * The timestamp is fixed-format with exactly three fractional digits.
     *
     * `DateTimeFormatter.ISO_INSTANT` drops the fraction when it is zero, which
     * would silently produce a preimage the Gateway cannot reproduce, so the
     * formatter is built to always emit milliseconds.
     */
    fun formatTimestamp(instant: java.time.Instant): String =
        TIMESTAMP_FORMATTER.format(instant)

    private fun requireTimestamp(value: String) {
        if (!TIMESTAMP.matches(value)) throw IllegalArgumentException("SCHEMA_INVALID:timestamp")
        val parsed = runCatching { java.time.Instant.parse(value) }.getOrNull()
            ?: throw IllegalArgumentException("SCHEMA_INVALID:timestamp")
        if (TIMESTAMP_FORMATTER.format(parsed) != value) {
            throw IllegalArgumentException("SCHEMA_INVALID:timestamp")
        }
    }

    /** The nonce must be exactly 16 bytes and already in canonical base64url. */
    private fun requireCanonicalNonce(value: String) {
        if (!BASE64URL.matches(value)) throw IllegalArgumentException("SCHEMA_INVALID:nonce")
        val decoded = java.util.Base64.getUrlDecoder().decode(value)
        if (decoded.size != 16) throw IllegalArgumentException("SCHEMA_INVALID:nonce")
        val reencoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
        if (reencoded != value) throw IllegalArgumentException("SCHEMA_INVALID:nonce")
    }
}
