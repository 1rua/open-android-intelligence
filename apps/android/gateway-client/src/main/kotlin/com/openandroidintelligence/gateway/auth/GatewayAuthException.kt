package com.openandroidintelligence.gateway.auth

/**
 * A Gateway authentication failure, as the protocol states it (contract §2, §14).
 *
 * `code` is `error.code` — the only field a client may branch on — and
 * `httpStatus` is kept beside it rather than inside the message, because a
 * Gateway that predates the envelope rule, or a proxy in front of one, still has
 * only a status to report. The message stays `"<operation>:<code-or-status>"` so
 * logs and the connection banner keep reading the same shape they always did.
 *
 * 这里刻意不再用「消息里最后一个冒号后的数字」表达凭据被拒：那使擦除本地
 * refresh 凭据的行为依赖字符串形状，网关一旦开始回结构化 code 就会被削弱。
 * [credentialRefused] 是该判定的唯一实现，且只看结构与闭集 code。
 */
class GatewayAuthException(
    /** The operation that failed, e.g. `AUTHENTICATION_FAILED`. */
    val operation: String,
    /** The Gateway's `error.code`, or null when the response carried none. */
    val code: String?,
    /** The HTTP status of the failing response. */
    val httpStatus: Int,
    /** The Gateway's `error.retryable`, or null when it was absent. */
    val retryable: Boolean? = null,
) : IllegalStateException("$operation:${code ?: httpStatus}") {

    companion object {
        /** Statuses that mean the credential itself was refused. */
        val REFUSED_STATUSES: Set<Int> = setOf(401, 403)

        /**
         * Contract §14 codes that mean the presented credential is gone and must
         * not be kept for the next resume.
         *
         * `REFRESH_REUSED` is the strongest of them: the Gateway saw a rotated
         * credential replayed, so the whole family is dead. `SESSION_EXPIRED`
         * and `SESSION_REVOKED` end the session the credential is bound to, and
         * `AUTHENTICATION_REQUIRED`/`AUTHENTICATION_FAILED` are the Gateway
         * saying it did not accept what was presented.
         */
        val REFUSED_CODES: Set<String> = setOf(
            "AUTHENTICATION_REQUIRED",
            "AUTHENTICATION_FAILED",
            "REFRESH_REUSED",
            "SESSION_EXPIRED",
            "SESSION_REVOKED",
        )

        private const val MAX_CAUSE_DEPTH = 8

        /**
         * Whether [cause] is the Gateway explicitly refusing the credential this
         * client presented — the only condition under which the host may destroy
         * local key material.
         *
         * A network drop, a 5xx, a 404 from an older Gateway build or a wrapped
         * failure say nothing about the credential, so they answer `false` and
         * the stored refresh credential survives.
         *
         * The cause chain is walked because a caller may have wrapped the
         * failure on its way up; the first [GatewayAuthException] found decides.
         */
        fun credentialRefused(cause: Throwable?): Boolean {
            var current = cause
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current is GatewayAuthException) {
                    return current.httpStatus in REFUSED_STATUSES || current.code in REFUSED_CODES
                }
                current = current.cause
                depth++
            }
            return false
        }
    }
}
