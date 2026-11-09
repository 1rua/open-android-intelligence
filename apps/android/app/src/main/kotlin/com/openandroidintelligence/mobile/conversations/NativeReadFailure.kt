package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.gateway.agents.AgentSessionException
import com.openandroidintelligence.gateway.auth.GatewayAuthException
import com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException
import com.openandroidintelligence.gateway.http.GatewayConnectionSecurity
import com.openandroidintelligence.gateway.http.GatewaySecurityException
import com.openandroidintelligence.gateway.negotiation.NegotiationHttpException
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

internal class NativePersistenceFailed(cause: Exception) : Exception("LOCAL_SAVE_FAILED", cause)

internal enum class NativeReadFailureKind { TRANSIENT, AUTHENTICATION, SECURITY, PROTOCOL, STORAGE }

/** 观察故障分类不改变远端 generation 事实，也不擦除任何登录或配对资料。 */
internal data class NativeReadFailure(
    val kind: NativeReadFailureKind,
    val stage: String,
    val code: String,
    val status: Int = 0,
) {
    val notice: String get() = "NATIVE_EVENT_SYNC_FAILED:$stage:$code" + if (status == 0) "" else ":$status"
}

internal fun classifyNativeReadFailure(failure: Exception, defaultStage: String = "READ_FAILED"): NativeReadFailure {
    val stage = (failure as? NativeEventSyncFailure)?.stage ?: defaultStage
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
    val agent = causes.filterIsInstance<AgentSessionException>().firstOrNull()
    val auth = causes.filterIsInstance<GatewayAuthException>().firstOrNull()
    val negotiation = causes.filterIsInstance<NegotiationHttpException>().firstOrNull()
    val status = agent?.status?.takeIf { it != 0 } ?: auth?.httpStatus?.takeIf { it != 0 } ?: negotiation?.httpStatus ?: 0
    val cause = (failure as? NativeEventSyncFailure)?.cause ?: failure
    val rawCode = agent?.code ?: auth?.code ?: negotiation?.code ?: when (cause) {
        is NativeHistoryBehind -> "NATIVE_HISTORY_BEHIND"
        is NativePersistenceFailed -> cause.cause?.javaClass?.simpleName ?: "LOCAL_SAVE_FAILED"
        else -> cause.javaClass.simpleName
    }
    // Peer error fields are not a diagnostic vocabulary until validated.
    val code = rawCode.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,127}")) }
        ?: if (agent != null || auth != null) "AGENT_REQUEST_FAILED" else cause.javaClass.simpleName
    if (stage == "LOCAL_SAVE_FAILED" || causes.any { it is NativePersistenceFailed })
        return NativeReadFailure(NativeReadFailureKind.STORAGE, "LOCAL_SAVE_FAILED", code, status)
    val legacyPin = causes.firstOrNull { it is IOException &&
        (it.message?.startsWith("PIN_MISMATCH") == true || it.message?.startsWith("PIN_REQUIRES_HTTPS") == true) }
    if (GatewayConnectionSecurity.isSecurityFailure(failure) || legacyPin != null) {
        val security = causes.firstOrNull { it is SSLException || it is CertificateException ||
            it.message?.startsWith("PIN_MISMATCH") == true || it.message?.startsWith("PIN_REQUIRES_HTTPS") == true }
        val securityCode = when {
            security is GatewaySecurityException -> "TLS_${security.code}"
            security?.message?.startsWith("PIN_MISMATCH") == true -> "TLS_PIN_MISMATCH"
            security?.message?.startsWith("PIN_REQUIRES_HTTPS") == true -> "TLS_PIN_REQUIRES_HTTPS"
            security != null -> security.javaClass.simpleName
            else -> "TLS_SECURITY_FAILURE"
        }
        return NativeReadFailure(NativeReadFailureKind.SECURITY, stage, securityCode, status)
    }
    val kind = when {
        status in setOf(401, 403) || negotiation?.credentialRefused == true || causes.any { it is SessionRecoveryPausedException } ||
            code in setOf("AUTHENTICATION_REQUIRED", "AUTHENTICATION_FAILED", "UNAUTHORIZED", "FORBIDDEN",
                "SESSION_EXPIRED", "SESSION_REVOKED", "SESSION_REJECTED", "REFRESH_REUSED") -> NativeReadFailureKind.AUTHENTICATION
        code in setOf("TLS_ERROR", "TLS_IDENTITY_MISMATCH", "PIN_MISMATCH", "PIN_REQUIRES_HTTPS") -> NativeReadFailureKind.SECURITY
        code in setOf("PROTOCOL_INCOMPATIBLE", "NATIVE_HISTORY_BEHIND", "AGENT_RESPONSE_INVALID", "AGENT_STREAM_INVALID", "NATIVE_EVENT_SCOPE_MISMATCH",
            "NATIVE_APPROVAL_SCOPE_MISMATCH", "UNSUPPORTED", "UNSUPPORTED_CAPABILITY", "GENERATION_NOT_FOUND") -> NativeReadFailureKind.PROTOCOL
        status == 408 || status == 429 || status in 500..599 -> NativeReadFailureKind.TRANSIENT
        status != 0 -> NativeReadFailureKind.PROTOCOL
        code in setOf("AGENT_TIMEOUT", "AGENT_UNAVAILABLE", "EVENT_ACK_UNAVAILABLE") || cause is IOException -> NativeReadFailureKind.TRANSIENT
        else -> NativeReadFailureKind.PROTOCOL
    }
    return NativeReadFailure(kind, stage, code, status)
}
