package com.openandroidintelligence.companion

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 令牌绑定的目标远端网络地址。 */
data class Destination(
    val host: String,
    val port: Int,
)

/** 令牌绑定的调用上下文身份。 */
data class CompanionBinding(
    val hostUid: Int,
    val companionUid: Int,
    val pluginId: String,
    val accountId: String,
    val pairingId: String,
    val grantRevision: Long,
)

/** 单用途操作令牌。 */
data class SingleUseOperationToken(
    val tokenId: String,
    val serializedToken: String,
    val binding: CompanionBinding,
    val operation: String,
    val destination: Destination,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val nonce: String,
)

/** 成功消费令牌后返回的操作上下文。 */
data class ConsumedToken(
    val binding: CompanionBinding,
    val operation: String,
    val destination: Destination,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
)

/**
 * 签发与核销短期单用途操作令牌。
 *
 * 核心安全保证：
 * 1. 令牌只能消费一次（Single-Use）：消费即原子销毁；
 * 2. 目标地址绑定（Destination Bound）：必须与签发时的 host/port 完全匹配；
 * 3. 严格过期限制（TTL Bound）：超过有效期自动作废；
 * 4. 身份与授权版本绑定（Binding Bound）：包含双方 UID、插件身份、账号与配对。
 */
class OperationTokenIssuer(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rng: () -> String = { UUID.randomUUID().toString() },
) {
    private val activeTokens = ConcurrentHashMap<String, SingleUseOperationToken>()

    /** 签发一个短期单用途操作令牌。 */
    fun issue(
        binding: CompanionBinding,
        operation: String,
        destination: Destination,
        ttlMillis: Long,
    ): SingleUseOperationToken {
        val now = clock()
        val nonce = rng()
        val tokenId = UUID.randomUUID().toString()
        val expiresAt = now + ttlMillis

        val serialized = "altok:v1:$tokenId:$nonce:${destination.host}:${destination.port}"

        val token = SingleUseOperationToken(
            tokenId = tokenId,
            serializedToken = serialized,
            binding = binding,
            operation = operation,
            destination = destination,
            issuedAtMillis = now,
            expiresAtMillis = expiresAt,
            nonce = nonce,
        )

        activeTokens[serialized] = token
        return token
    }

    /**
     * 核销并消费令牌。
     *
     * 任意一项不符或令牌已被消费过，均返回 null。
     */
    fun consume(
        serializedToken: String,
        destination: Destination,
        nowMillis: Long = clock(),
    ): ConsumedToken? {
        // 原子移除以保证单用途（Single-Use）
        val token = activeTokens.remove(serializedToken) ?: return null

        // 检查过期时间
        if (nowMillis > token.expiresAtMillis) {
            return null
        }

        // 检查目标地址绑定
        if (token.destination.host != destination.host || token.destination.port != destination.port) {
            return null
        }

        return ConsumedToken(
            binding = token.binding,
            operation = token.operation,
            destination = token.destination,
            issuedAtMillis = token.issuedAtMillis,
            expiresAtMillis = token.expiresAtMillis,
        )
    }

    /** 废除某个配对或插件的所有活跃令牌（用于撤权或崩溃时）。 */
    fun revokeAllForPairing(pairingId: String) {
        val iterator = activeTokens.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.binding.pairingId == pairingId) {
                iterator.remove()
            }
        }
    }
}

