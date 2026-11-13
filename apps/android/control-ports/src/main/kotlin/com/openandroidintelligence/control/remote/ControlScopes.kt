package com.openandroidintelligence.control.remote

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

class ControlRequestException(val code: String) : IllegalArgumentException(code)

/** 仅验证连接层已经规范化的 origin，不替代服务地址解析或 HTTP 许可策略。 */
@JvmInline
value class ControlOrigin private constructor(val value: String) {
    companion object {
        fun fromCanonical(value: String): ControlOrigin {
            val uri = try { URI(value) } catch (_: Exception) { throw ControlRequestException("CONTROL_ORIGIN_INVALID") }
            val host = uri.host ?: throw ControlRequestException("CONTROL_ORIGIN_INVALID")
            if (uri.scheme !in setOf("https", "http") || host != host.lowercase(Locale.ROOT) ||
                value.any { it.code !in 0x21..0x7e } || '%' in host ||
                uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in -1..65535 || uri.port == (if (uri.scheme == "https") 443 else 80) ||
                URI(uri.scheme, null, host, uri.port, null, null, null).toASCIIString() != value) {
                throw ControlRequestException("CONTROL_ORIGIN_NOT_CANONICAL")
            }
            return ControlOrigin(value)
        }
    }
}

/** 来自认证通道的稳定主体；账号显示名与连接代次不构成持久防重身份。 */
data class RemoteControlPrincipal(
    val origin: ControlOrigin,
    val accountId: String,
    val controllerDeviceId: String,
    val hostDeviceId: String,
    val controllerKeyFingerprint: String,
    val hostKeyFingerprint: String,
) {
    init {
        require(listOf(accountId, controllerDeviceId, hostDeviceId).all { it.isNotEmpty() }) { "CONTROL_PRINCIPAL_INVALID" }
        require(listOf(controllerKeyFingerprint, hostKeyFingerprint).all { it.matches(Regex("sha256:[0-9a-f]{64}")) }) {
            "CONTROL_PRINCIPAL_KEY_INVALID"
        }
    }

    internal fun coordinates(): List<String> = listOf(origin.value, accountId, controllerDeviceId, hostDeviceId,
        controllerKeyFingerprint, hostKeyFingerprint)

    val storageKey: String get() = scopeDigest("oai-control-principal-v1", coordinates())
}

/** 本机装配提供的当前访问关系；参数解析器没有构造该关系的入口。 */
data class ControlAccessBinding(
    val principal: RemoteControlPrincipal,
    val authorizationEpoch: Long,
    val connectionId: String,
    val connectionGeneration: Long,
) {
    init {
        require(authorizationEpoch >= 0 && connectionGeneration >= 0 && connectionId.isNotEmpty()) { "CONTROL_ACCESS_INVALID" }
    }
}

data class ControlTaskScope(
    val principal: RemoteControlPrincipal,
    val taskSessionId: String,
    val authorizationEpoch: Long,
    val processEpoch: String,
) {
    init { require(taskSessionId.isNotEmpty() && processEpoch.isNotEmpty() && authorizationEpoch >= 0) { "CONTROL_TASK_INVALID" } }

    fun permits(access: ControlAccessBinding, currentProcessEpoch: String): Boolean =
        principal == access.principal && authorizationEpoch == access.authorizationEpoch && processEpoch == currentProcessEpoch
}

/** 持久防重只按稳定主体与 request_id；切任务、重连、授权更新均不能使旧请求重执行。 */
data class ControlRequestKey(val principal: RemoteControlPrincipal, val requestId: String) {
    init { require(Regex("[A-Za-z0-9_-]{16,128}").matches(requestId)) { "CONTROL_REQUEST_ID_INVALID" } }
    val storageKey: String get() = scopeDigest("oai-control-request-v1", principal.coordinates() + requestId)
}

data class ControlRequestScope(val task: ControlTaskScope, val requestId: String) {
    val key: ControlRequestKey = ControlRequestKey(task.principal, requestId)
}

data class ControlArtifactScope(val request: ControlRequestScope, val artifactId: String, val version: String) {
    init { require(artifactId.isNotEmpty() && version.isNotEmpty()) { "CONTROL_ARTIFACT_INVALID" } }
    fun permits(access: ControlAccessBinding, task: ControlTaskScope, processEpoch: String): Boolean =
        request.task == task && task.permits(access, processEpoch)
}

/** 长度前缀避免坐标中的分隔符产生歧义，摘要只用作存储定位而非访问授权。 */
private fun scopeDigest(domain: String, values: List<String>): String {
    val bytes = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            (listOf(domain) + values).forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        buffer.toByteArray()
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
