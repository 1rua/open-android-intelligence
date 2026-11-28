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
     * 合并同一工具调用步骤的前后快照，统一委托给领域唯一权威实现 [ToolCallPairing.mergeSteps]。
     */
    fun mergeWith(incoming: NativeExecutionStep): NativeExecutionStep =
        ToolCallPairing.mergeSteps(this, incoming)

    /**
     * 统一委托给领域层唯一权威快照合并实现 [ToolCallPairing.mergeSnapshot]：
     * 支持高修订号推进、重连/同修订号非终态向终态升级以及跨修订号非破坏性字段补全，
     * 绝不将已终态倒退为运行中或破坏已有真实工具名、参数、输出与顺序元数据。
     */
    fun applyNewerSnapshot(incoming: NativeExecutionStep?): NativeExecutionStep =
        if (incoming == null) this else ToolCallPairing.mergeSnapshot(this, incoming)

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
