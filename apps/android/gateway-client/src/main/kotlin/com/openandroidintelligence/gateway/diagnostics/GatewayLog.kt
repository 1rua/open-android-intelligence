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
    private val bootId = java.util.UUID.randomUUID().toString()
    /** 只记录内部状态、计数与耗时；调用处不得传入凭据、正文、原始游标或异常消息。 */
    fun connectionEvent(
        type: String,
        generation: Long,
        reason: String,
        attempt: Long = 0,
        result: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        runCatching {
            emit("OaiConnection", com.openandroidintelligence.gateway.schema.Json.canonical(
                com.openandroidintelligence.gateway.schema.Json.of(fields + mapOf(
                    "type" to type, "at" to System.currentTimeMillis(),
                    "monotonicMs" to System.nanoTime() / 1_000_000, "bootId" to bootId,
                    "generation" to generation, "reason" to reason, "attempt" to attempt, "result" to result,
                )),
            ))
        }
    }
    /** Explicitly enabled by debug builds; contains IDs/timestamps, never tokens or message text. */
    var protocolEvidenceEnabled = false

    fun protocolEvidence(type: String, fields: Map<String, Any?>) {
        if (protocolEvidenceEnabled) emit("OaiE2E", com.openandroidintelligence.gateway.schema.Json.canonical(
            com.openandroidintelligence.gateway.schema.Json.of(fields + mapOf("type" to type, "at" to System.currentTimeMillis(),
                "monotonicNs" to System.nanoTime(), "bootId" to bootId)),
        ))
    }
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
