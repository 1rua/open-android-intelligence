package com.openandroidintelligence.gateway.remote.protocol

import java.util.Base64

/** 与固定上游 v1 共用的线协议常量，不作为新的产品限制。 */
object RemoteProtocol {
    const val VERSION = 1
    const val UPSTREAM_COMMIT = "ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7"
    const val NOISE_SUITE = "Noise_IK_25519_ChaChaPoly_SHA256"
    const val MAX_CONTROL_FRAME_BYTES = 64 * 1024
    const val MAX_RELAY_FRAME_BYTES = 1024 * 1024
    const val SECURE_FRAGMENT_CHUNK_BYTES = 48 * 1024
    const val MAX_SECURE_MESSAGE_BYTES = 4 * 1024 * 1024
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}

/** 错误只携带固定代码，不保留可能包含凭据或业务正文的解析异常。 */
class RemoteProtocolException(val code: String) : IllegalArgumentException(code)

/** 按上游严格接受无填充且未用位为零的 Base64URL。 */
object RemoteBase64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String): ByteArray {
        if (value.isEmpty() || !value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }) {
            throw RemoteProtocolException("INVALID_BASE64URL")
        }
        val decoded = try { Base64.getUrlDecoder().decode(value) }
        catch (_: IllegalArgumentException) { throw RemoteProtocolException("INVALID_BASE64URL") }
        if (encode(decoded) != value) throw RemoteProtocolException("INVALID_BASE64URL")
        return decoded
    }
}

/** prologue 只绑定上游定义的三个标识；账号和 origin 的隔离由身份存储负责。 */
data class RemoteConnectionBinding(
    val connectionId: String,
    val hostDeviceId: String,
    val clientDeviceId: String,
) {
    init {
        if (listOf(connectionId, hostDeviceId, clientDeviceId).any { it.isEmpty() || '\u0000' in it }) {
            throw RemoteProtocolException("INVALID_CONNECTION_BINDING")
        }
    }

    fun prologue(): ByteArray =
        "DSH-REMOTE\u0000v=1\u0000connection=$connectionId\u0000host=$hostDeviceId\u0000client=$clientDeviceId"
            .toByteArray(Charsets.UTF_8)
}
