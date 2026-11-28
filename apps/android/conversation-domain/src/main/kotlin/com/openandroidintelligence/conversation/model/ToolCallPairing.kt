package com.openandroidintelligence.conversation.model

/**
 * 全后端工具调用配对的领域层唯一权威规则：
 * - 规范化工具调用 ID（去首尾空白、拒绝空白串）；
 * - 识别占位工具名，防止精简结果帧覆盖真实工具名、参数或已有输出；
 * - 提供单一非破坏性步骤合并函数，供数据投影层、控制器与展示层统一复用。
 */
object ToolCallPairing {
    private val PLACEHOLDER_TOOL_NAMES = setOf(
        "tool",
        "tool_call",
        "tool-call",
        "toolcall",
        "tool_use",
        "tool-use",
        "tooluse",
        "tool_result",
        "tool-result",
        "toolresult",
        "function",
        "function_call",
        "function-call",
        "functioncall",
        "function_response",
        "function-response",
        "functionresponse",
        "function_call_output",
        "function-call-output",
        "functioncalloutput",
    )

    /** 规范化工具调用唯一标识；空白或仅含空白字符时返回 null。 */
    fun normalizeCallId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** 判断工具名是否为空或协议包装占位名（如 `"tool"`、`"tool_result"`、`"functionCallOutput"`）。 */
    fun isPlaceholderToolName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase().orEmpty()
        return normalized.isEmpty() || normalized in PLACEHOLDER_TOOL_NAMES
    }

    /** 优先保留真实非占位工具名，绝不用空白或 `"tool"` 覆盖已有有效工具名。 */
    fun chooseToolName(existing: String?, incoming: String?): String {
        val cleanIncoming = incoming?.trim().orEmpty()
        val cleanExisting = existing?.trim().orEmpty()
        return when {
            !isPlaceholderToolName(cleanIncoming) -> cleanIncoming
            !isPlaceholderToolName(cleanExisting) -> cleanExisting
            cleanExisting.isNotEmpty() -> cleanExisting
            cleanIncoming.isNotEmpty() -> cleanIncoming
            else -> "tool"
        }
    }

    /** 优先保留有效参数；新快照为空或退化为占位空对象 `"{}"` 时保留原有真实参数。 */
    fun chooseArguments(existing: String, incoming: String): String = when {
        incoming.isBlank() -> existing
        incoming.trim() == "{}" && existing.isNotBlank() && existing.trim() != "{}" -> existing
        else -> incoming
    }

    /** 优先采用新快照的非空输出；新快照为 null 或空串时不抹除已有非空输出。 */
    fun chooseOutput(existing: String?, incoming: String?): String? = when {
        !incoming.isNullOrEmpty() -> incoming
        !existing.isNullOrEmpty() -> existing
        incoming != null -> incoming
        else -> existing
    }

    /** 判断步骤是否已处于终态（已收到结果或明确失败）。 */
    fun isTerminalStatus(status: NativeExecutionStatus): Boolean =
        status == NativeExecutionStatus.COMPLETED || status == NativeExecutionStatus.FAILED

    /**
     * 判断是否为临时/兜底生成轮次标识（例如本地发送占位 `request:*` 或投影兜底 ID）。
     * 用于在跨临时身份升级期间识别同一步骤，避免重复渲染两张卡片。
     */
    fun isProvisionalGenerationId(generationId: String?): Boolean {
        val id = generationId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return id.startsWith("request:") ||
            id.startsWith("history-turn:") ||
            id.startsWith("remote-execution:") ||
            id.startsWith("acp-turn:")
    }

    /**
     * 单一权威非破坏性合并规则：
     * - 终态（COMPLETED / FAILED）绝不被后续 RUNNING / UNKNOWN 倒退；
     * - 保留真实非占位工具名、有效参数与非空输出；
     * - 保留原调用时间与单调顺序号，并在新快照带来真实宿主消息 ID 时升级临时 `sourceMessageId`。
     */
    fun mergeSteps(
        existing: NativeExecutionStep,
        incoming: NativeExecutionStep,
    ): NativeExecutionStep {
        val existingTerminal = isTerminalStatus(existing.status)
        val incomingNonTerminal = !isTerminalStatus(incoming.status)
        val mergedStatus = if (existingTerminal && incomingNonTerminal) existing.status else incoming.status
        val mergedOutput = if (existingTerminal && incomingNonTerminal) {
            chooseOutput(incoming.output, existing.output)
        } else {
            chooseOutput(existing.output, incoming.output)
        }
        val canonicalId = normalizeCallId(existing.stepId) ?: normalizeCallId(incoming.stepId) ?: existing.stepId
        return incoming.copy(
            stepId = canonicalId,
            eventId = incoming.eventId.ifBlank { existing.eventId },
            toolName = chooseToolName(existing.toolName, incoming.toolName),
            arguments = chooseArguments(existing.arguments, incoming.arguments),
            output = mergedOutput,
            status = mergedStatus,
            revision = maxOf(existing.revision, incoming.revision),
            sourceMessageId = NativeExecutionStep.resolveSourceMessageId(existing.sourceMessageId, incoming.sourceMessageId),
            occurredAt = existing.occurredAt?.takeIf { it > 0L } ?: incoming.occurredAt,
            orderSequence = existing.orderSequence ?: incoming.orderSequence,
        )
    }

    /**
     * 单一权威快照合并规则（供 [NativeExecutionStep.applyNewerSnapshot]、控制器与展示层共享）：
     * - 非终态（RUNNING / UNKNOWN）收到终态（COMPLETED / FAILED）时无条件升级，即使重连重建投影导致修订号更低或相等；
     * - 已终态绝不被任意修订号的非终态倒退，但仍非破坏性补全缺失的真实工具名、入参、宿主来源与顺序元数据；
     * - 同为终态或同为非终态时，高修订号优先；同修订号支持 UNKNOWN → RUNNING 升级与空输出补全；
     *   低修订号保留主快照状态与输出，同时非破坏性补全缺失字段。
     */
    fun mergeSnapshot(
        existing: NativeExecutionStep,
        incoming: NativeExecutionStep,
    ): NativeExecutionStep {
        val existingTerminal = isTerminalStatus(existing.status)
        val incomingTerminal = isTerminalStatus(incoming.status)
        val preferIncoming = when {
            existingTerminal && !incomingTerminal -> false
            !existingTerminal && incomingTerminal -> true
            incoming.revision > existing.revision -> true
            incoming.revision < existing.revision -> false
            existing.status == NativeExecutionStatus.UNKNOWN && incoming.status == NativeExecutionStatus.RUNNING -> true
            existing.output.isNullOrBlank() && !incoming.output.isNullOrBlank() -> true
            else -> false
        }
        return if (preferIncoming) {
            mergeSteps(existing = existing, incoming = incoming)
        } else {
            mergeSteps(existing = incoming, incoming = existing).copy(
                sourceMessageId = NativeExecutionStep.resolveSourceMessageId(existing.sourceMessageId, incoming.sourceMessageId),
                occurredAt = existing.occurredAt?.takeIf { it > 0L } ?: incoming.occurredAt,
                orderSequence = existing.orderSequence ?: incoming.orderSequence,
            )
        }
    }
}
