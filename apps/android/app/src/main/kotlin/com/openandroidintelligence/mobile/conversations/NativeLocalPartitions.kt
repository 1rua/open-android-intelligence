package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments

/** 以未派生的账号范围登记使用过的真实 Agent ID，供重启恢复和账号清理枚举。 */
class NativeLocalPartitions(
    context: Context,
    private val baseScope: ConversationScope,
    keyProvider: AesGcmKeyProvider? = null,
) {
    private val scopeKey = Json.canonical(Json.of(listOf(
        baseScope.profileId, baseScope.gatewayId, baseScope.accountId, baseScope.installId,
    )))
    private val documents = if (keyProvider == null) EncryptedDocuments(context, "native-local-partitions-v1:$scopeKey")
        else EncryptedDocuments(context, "native-local-partitions-v1:$scopeKey", keyProvider)

    private val legacyClaims = if (keyProvider == null) EncryptedDocuments(context, "native-media-legacy-claims-v1:$scopeKey")
        else EncryptedDocuments(context, "native-media-legacy-claims-v1:$scopeKey", keyProvider)

    fun register(agentId: String) {
        require(agentId.isNotBlank()) { "NATIVE_AGENT_REQUIRED" }
        synchronized(lock) {
            // 每个 Agent 独立提交；多个界面实例登记时不会覆盖其他 Agent 的记录。
            if (documents.read(agentId) == null) documents.write(agentId, byteArrayOf(1))
        }
    }

    fun agents(): Set<String> = synchronized(lock) {
        documents.keys().also { check(it.none(String::isBlank)) { "NATIVE_PARTITIONS_INVALID" } }
    }

    fun partitionScope(agentId: String): ConversationScope = partitionScope(baseScope, agentId)

    /** 旧键只能由同一 Agent 重试；中断复制也不能让另一 Agent 接管未定归属。 */
    internal fun claimLegacyOriginal(agentId: String, conversationId: String, attachmentId: String): Boolean = synchronized(lock) {
        require(agentId.isNotBlank()) { "NATIVE_AGENT_REQUIRED" }
        val key = Json.canonical(Json.of(listOf(conversationId, attachmentId)))
        val owner = legacyClaims.read(key)?.let { bytes ->
            try { bytes.decodeToString() } finally { bytes.fill(0) }
        }
        when {
            owner != null -> owner == agentId
            else -> { register(agentId); legacyClaims.write(key, agentId.toByteArray()); true }
        }
    }

    /** 调用方先退休并清理所有已登记分区，再清理枚举记录。 */
    fun wipe() = synchronized(lock) { legacyClaims.erase(); documents.erase() }

    companion object {
        // EncryptedDocuments 的原子文件写入也需要在本进程的不同实例之间串行。
        private val lock = Any()

        /** 沿用既有原生 Mirror 的本地范围格式；不能将此派生账号 ID 用作远端身份。 */
        fun partitionScope(baseScope: ConversationScope, agentId: String): ConversationScope {
            require(agentId.isNotBlank()) { "NATIVE_AGENT_REQUIRED" }
            return baseScope.copy(accountId = "${baseScope.accountId}#native#$agentId")
        }
    }
}
