package com.openandroidintelligence.tailnet.core

/**
 * Supplies the opaque ALTSNET1 bootstrap bytes produced by the pairing
 * subsystem. The source is never an endpoint, auth key, or URL parameter.
 */
interface NativeEnrollmentSource {
    fun bootstrapBytes(): ByteArray
}

/** Deny-first composition: without an enrollment source the userspace core is unavailable. */
object UnavailableNativeEnrollmentSource : NativeEnrollmentSource {
    override fun bootstrapBytes(): ByteArray =
        throw IllegalStateException("native enrollment source is unavailable")
}
