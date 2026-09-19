package com.openandroidintelligence.gateway.diagnostics

/**
 * Diagnostic outlet for the Gateway transport.
 *
 * `android.util.Log` throws in a local JVM unit test, so the transport never
 * touches it directly: it writes here, and the app installs a sink that
 * forwards to Logcat. With no sink installed everything is a no-op, which
 * keeps the module usable from tests and from non-Android hosts.
 */
object GatewayLog {
    /** `(tag, message)` — installed by the app, absent in tests. */
    var sink: ((String, String) -> Unit)? = null

    fun d(tag: String, message: String) = emit(tag, message)

    fun w(tag: String, message: String) = emit(tag, message)

    fun e(tag: String, message: String) = emit(tag, message)

    private fun emit(tag: String, message: String) {
        // A logging sink must never be able to break the transport that called it.
        runCatching { sink?.invoke(tag, message) }
    }
}
