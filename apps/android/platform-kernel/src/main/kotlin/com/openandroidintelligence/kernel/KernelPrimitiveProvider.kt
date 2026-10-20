package com.openandroidintelligence.kernel

/**
 * 局部授权与调用上下文。
 * 传递给底层原始能力提供者，用于本地安全策略与审计追踪。
 */
data class LocalGrantContext(
    val pluginId: String,
    val accountId: String,
    val pairingId: String,
    val correlationId: String,
)

/**
 * 内核受保护原始能力提供者接口。
 *
 * 底层 Android 采集器（如通知、短信、通话记录）实现此接口，
 * 仅提供经本地过滤的原始数据读取，不持有 Gateway、配对或队列状态。
 */
interface KernelPrimitiveProvider {
    val primitiveId: String
    /** Read-only diagnostic. Null means available; execution still rechecks every local gate. */
    fun availability(): String? = null
    suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray
}

