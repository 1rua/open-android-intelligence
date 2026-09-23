package com.openandroidintelligence.gateway.http

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** A replayable HTTP entity body whose exact plain bytes were measured before signing. */
class GatewayRequestBody(
    val contentLength: Long,
    val sha256Hex: String,
    openStream: () -> InputStream,
    private val onBytesWritten: ((Long) -> Unit)? = null,
) {
    private val streamFactory = openStream
    init {
        require(contentLength >= 0) { "REQUEST_BODY_INVALID:length" }
        require(SHA256_HEX.matches(sha256Hex)) { "REQUEST_BODY_INVALID:digest" }
    }

    fun openStream(): InputStream = streamFactory()

    internal fun reportBytesWritten(value: Long) {
        onBytesWritten?.invoke(value)
    }

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        fun fromBytes(bytes: ByteArray): GatewayRequestBody {
            val content = bytes.copyOf()
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) }
            return GatewayRequestBody(
                contentLength = content.size.toLong(),
                sha256Hex = digest,
                openStream = { ByteArrayInputStream(content) },
            )
        }
    }
}
