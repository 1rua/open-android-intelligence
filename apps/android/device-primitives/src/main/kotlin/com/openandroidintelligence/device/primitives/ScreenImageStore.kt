package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.control.ScreenAuthorizationBinding
import com.openandroidintelligence.control.ScreenOperationException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded, memory-only image handles; every access is fenced to the original local session. */
internal class ScreenImageStore(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Image(val owner: ScreenAuthorizationBinding, val sessionId: String,
        val png: ByteArray, val width: Int, val height: Int, val expiresAt: Long, val digest: String)
    private val images = linkedMapOf<String, Image>()

    @Synchronized fun put(owner: ScreenAuthorizationBinding, sessionId: String, png: ByteArray,
        width: Int, height: Int, sessionExpiresAt: Long): Map<String, Any> {
        expire()
        if (png.isEmpty() || png.size > MAX_IMAGE_BYTES) throw ScreenOperationException("SCREEN_IMAGE_TOO_LARGE")
        if (images.size >= MAX_IMAGES) throw ScreenOperationException("SCREEN_IMAGE_LIMIT_REACHED")
        val id = UUID.randomUUID().toString()
        val expires = minOf(clock() + TTL_MILLIS, sessionExpiresAt)
        if (expires <= clock()) throw ScreenOperationException("SCREEN_SESSION_EXPIRED")
        val image = Image(owner, sessionId, png.copyOf(), width, height, expires,
            MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it.toInt() and 255) })
        images[id] = image
        // The scheduler uses elapsed time: a wall-clock rollback must not keep
        // sensitive pixels past the original bounded lifetime.
        EXPIRY_TIMER.schedule({ discard(id) }, (expires - clock()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return mapOf("imageId" to id, "width" to width, "height" to height, "sizeBytes" to png.size,
            "sha256" to image.digest, "mimeType" to "image/png", "expiresAt" to expires, "chunkSize" to CHUNK_BYTES)
    }

    @Synchronized fun read(owner: ScreenAuthorizationBinding, sessionId: String, id: String, offset: Int): Map<String, Any> {
        expire()
        val image = owned(owner, sessionId, id)
        if (offset < 0 || offset >= image.png.size || offset % CHUNK_BYTES != 0) throw ScreenOperationException("SCHEMA_INVALID")
        val end = minOf(offset + CHUNK_BYTES, image.png.size)
        return mapOf("imageId" to id, "offset" to offset, "nextOffset" to end,
            "eof" to (end == image.png.size), "data" to Base64.getEncoder().encodeToString(image.png.copyOfRange(offset, end)))
    }

    @Synchronized fun release(owner: ScreenAuthorizationBinding, sessionId: String, id: String): Map<String, Any> {
        expire()
        images[id]?.let { owned(owner, sessionId, id).png.fill(0); images.remove(id) }
        return mapOf("released" to true)
    }

    @Synchronized fun clear() { images.values.forEach { it.png.fill(0) }; images.clear() }
    @Synchronized fun discard(id: String) { images.remove(id)?.png?.fill(0) }
    @Synchronized fun expire() {
        val expired = images.filterValues { it.expiresAt <= clock() }.keys.toList()
        expired.forEach { images.remove(it)?.png?.fill(0) }
    }
    private fun owned(owner: ScreenAuthorizationBinding, sessionId: String, id: String): Image =
        images[id]?.takeIf { it.owner == owner && it.sessionId == sessionId }
            ?: throw ScreenOperationException("SCREEN_IMAGE_UNAVAILABLE")

    companion object {
        const val CHUNK_BYTES = 24_576
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGES = 2
        const val TTL_MILLIS = 60_000L
        private val EXPIRY_TIMER = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "oai-screen-image-expiry").apply { isDaemon = true }
        }
    }
}
