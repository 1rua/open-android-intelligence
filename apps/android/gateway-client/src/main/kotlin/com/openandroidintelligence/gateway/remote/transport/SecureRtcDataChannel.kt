package com.openandroidintelligence.gateway.remote.transport

import com.openandroidintelligence.gateway.remote.crypto.RemoteSecureChannel

/** 同时拥有 cipher 和 carrier，禁止部分发送失败后继续使用已推进的 Noise nonce。 */
class SecureRtcDataChannel(
    private val secure: RemoteSecureChannel,
    private val carrier: RtcDataChannelTransport,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val handlers = linkedSetOf<(ByteArray) -> Unit>()
    private var received: AutoCloseable? = null
    private var closedSubscription: AutoCloseable? = null

    init {
        if (secure.binding != carrier.identity.binding) {
            close()
            fail("SECURE_RTC_BINDING_MISMATCH")
        }
        var messageSubscription: AutoCloseable? = null
        var closeSubscription: AutoCloseable? = null
        try {
            messageSubscription = carrier.onMessage { ciphertext ->
                val message = try { secure.decryptMessage(ciphertext) } catch (_: Exception) {
                    close()
                    null
                }
                if (message != null) {
                    val callbacks = synchronized(lock) { if (closed) emptyList() else handlers.toList() }
                    callbacks.forEach { handler ->
                        synchronized(lock) { if (closed) return@onMessage }
                        try { handler(message.copyOf()) } catch (_: Exception) { close(); return@onMessage }
                    }
                }
            }
            synchronized(lock) {
                if (closed) fail("SECURE_RTC_INITIALIZATION_FAILED")
                received = messageSubscription
            }
            closeSubscription = carrier.onClose { close() }
            synchronized(lock) {
                if (closed) fail("SECURE_RTC_INITIALIZATION_FAILED")
                closedSubscription = closeSubscription
            }
        } catch (_: Exception) {
            close()
            // onClose 可能在返回订阅句柄前同步关闭；局部句柄也必须清理，不能只依赖字段。
            closeQuietly(messageSubscription)
            closeQuietly(closeSubscription)
            fail("SECURE_RTC_INITIALIZATION_FAILED")
        }
    }

    suspend fun sendMessage(plaintext: ByteArray) {
        // 固定上游 4MiB secure message 限制由 cipher codec 验证，队列仍按原始正文大小计费。
        carrier.sendEncrypted(plaintext, { secure.encryptMessage(it) }, { secure.close() })
    }

    fun onMessage(handler: (ByteArray) -> Unit): AutoCloseable {
        synchronized(lock) {
            if (closed) fail("RTC_CLOSED")
            handlers += handler
        }
        return AutoCloseable { synchronized(lock) { handlers -= handler } }
    }

    override fun close() {
        val subscriptions = synchronized(lock) {
            if (closed) return
            closed = true
            handlers.clear()
            listOfNotNull(received, closedSubscription).also {
                received = null
                closedSubscription = null
            }
        }
        closeQuietly(secure)
        subscriptions.forEach(::closeQuietly)
        closeQuietly(carrier)
    }

    private fun closeQuietly(resource: AutoCloseable?) {
        try { resource?.close() } catch (_: Exception) { }
    }
}
