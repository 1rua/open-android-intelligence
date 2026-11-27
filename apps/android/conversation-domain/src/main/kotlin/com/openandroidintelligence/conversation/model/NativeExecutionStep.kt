package com.openandroidintelligence.conversation.model

/** 宿主工具调用事实；正文、时间和列表位置不能替代这些原生身份。 */
data class NativeExecutionStep(
    val stepId: String,
    val eventId: String,
    val toolName: String,
    val arguments: String,
    val output: String? = null,
    val status: NativeExecutionStatus = NativeExecutionStatus.UNKNOWN,
    val revision: Long = 0,
    /** 宿主明确提供的公共源消息身份，用于跨引导保持实际位置。 */
    val sourceMessageId: String? = null,
    /** 原调用事件时间（毫秒）；结果更新不能改写调用位置。 */
    val occurredAt: Long? = null,
    /** 同一会话/回合内单调递增的事件到达序或协议 seq；结果更新保留首次调用时的顺序。 */
    val orderSequence: Long? = null,
) {
    /**
     * 合并同一工具调用步骤的前后快照：
     * - 终态（[NativeExecutionStatus.COMPLETED] / [NativeExecutionStatus.FAILED]）不被后续非终态回写；
     * - 保留首次有效工具名、参数、发生时间与单调顺序号；
     * - 若旧 `sourceMessageId` 为临时占位行（`history-tool:*` / `remote-stream:*`）且新快照提供了真实宿主消息 ID，则升级为真实消息 ID。
     */
    fun mergeWith(incoming: NativeExecutionStep): NativeExecutionStep {
        val existingTerminal = status == NativeExecutionStatus.COMPLETED || status == NativeExecutionStatus.FAILED
        val incomingNonTerminal = incoming.status == NativeExecutionStatus.RUNNING || incoming.status == NativeExecutionStatus.UNKNOWN
        val mergedStatus = if (existingTerminal && incomingNonTerminal) status else incoming.status
        val mergedName = when {
            incoming.toolName.isNotBlank() && incoming.toolName != "tool" -> incoming.toolName
            toolName.isNotBlank() -> toolName
            else -> incoming.toolName.ifBlank { "tool" }
        }
        return incoming.copy(
            toolName = mergedName,
            arguments = incoming.arguments.ifEmpty { arguments },
            output = incoming.output ?: output,
            status = mergedStatus,
            revision = maxOf(revision, incoming.revision),
            sourceMessageId = resolveSourceMessageId(sourceMessageId, incoming.sourceMessageId),
            occurredAt = occurredAt?.takeIf { it > 0L } ?: incoming.occurredAt,
            orderSequence = orderSequence ?: incoming.orderSequence,
        )
    }

    /**
     * 仅在新快照修订号更高且不把已终态步骤倒退为运行中时应用更新，并继承原调用位置与顺序元数据。
     */
    fun applyNewerSnapshot(incoming: NativeExecutionStep?): NativeExecutionStep {
        if (incoming == null || incoming.revision <= revision) return this
        val existingTerminal = status == NativeExecutionStatus.COMPLETED || status == NativeExecutionStatus.FAILED
        val incomingNonTerminal = incoming.status == NativeExecutionStatus.RUNNING || incoming.status == NativeExecutionStatus.UNKNOWN
        if (existingTerminal && incomingNonTerminal) return this
        return incoming.copy(
            sourceMessageId = resolveSourceMessageId(sourceMessageId, incoming.sourceMessageId),
            occurredAt = occurredAt ?: incoming.occurredAt,
            orderSequence = orderSequence ?: incoming.orderSequence,
        )
    }

    companion object {
        fun isTemporarySourceMessageId(id: String?): Boolean =
            id != null && (id.startsWith("history-tool:") || id.startsWith("remote-stream:"))

        fun resolveSourceMessageId(existing: String?, incoming: String?): String? {
            val cleanExisting = existing?.takeIf { it.isNotBlank() }
            val cleanIncoming = incoming?.takeIf { it.isNotBlank() }
            return when {
                cleanExisting == null -> cleanIncoming
                isTemporarySourceMessageId(cleanExisting) && cleanIncoming != null && !isTemporarySourceMessageId(cleanIncoming) ->
                    cleanIncoming
                else -> cleanExisting
            }
        }
    }
}

/** COMPLETED 表示宿主返回了结果，不从结果正文推断执行成功。 */
enum class NativeExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    UNKNOWN,
}
