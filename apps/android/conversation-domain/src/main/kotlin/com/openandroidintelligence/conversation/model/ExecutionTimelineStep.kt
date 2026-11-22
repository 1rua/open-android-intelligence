package com.openandroidintelligence.conversation.model

/**
 * 单条消息或单回合内按真实发生顺序/内容块原始索引排列的统一异构执行步骤。
 *
 * 将思考过程与工具调用纳入同一有序列表，杜绝拆成两个独立集合导致的归堆与顺序错乱。
 */
sealed interface ExecutionTimelineStep {
    /** 步骤稳定身份（思考块 ID 或原生工具 callId/stepId）。 */
    val stepId: String

    /** 相对净化后正文文本的字符偏移，用于精确定位步骤与叙述/最终正文的穿插顺序。 */
    val bodyOffset: Int

    /** 同一消息内单调递增的顺序索引（内容块原始索引或实时流式到达顺序）。 */
    val orderIndex: Int

    fun withBodyOffset(offset: Int): ExecutionTimelineStep

    data class ThinkingStep(
        override val stepId: String,
        val text: String,
        override val bodyOffset: Int = 0,
        val complete: Boolean = true,
        override val orderIndex: Int = 0,
    ) : ExecutionTimelineStep {
        init {
            require(stepId.isNotEmpty() && bodyOffset >= 0) { "THINKING_STEP_INVALID" }
        }

        override fun withBodyOffset(offset: Int): ThinkingStep =
            if (offset == bodyOffset) this else copy(bodyOffset = offset)
    }

    data class ToolCallStep(
        val step: NativeExecutionStep,
        override val bodyOffset: Int = 0,
        override val orderIndex: Int = 0,
    ) : ExecutionTimelineStep {
        override val stepId: String get() = step.stepId

        init {
            require(step.stepId.isNotEmpty() && bodyOffset >= 0) { "TOOL_CALL_STEP_INVALID" }
        }

        override fun withBodyOffset(offset: Int): ToolCallStep =
            if (offset == bodyOffset) this else copy(bodyOffset = offset)
    }
}

/** 从统一异构时间线步骤提取兼容的思考列表。 */
fun List<ExecutionTimelineStep>.toMessageReasonings(): List<MessageReasoning> =
    filterIsInstance<ExecutionTimelineStep.ThinkingStep>()
        .map { MessageReasoning(it.stepId, it.text, it.bodyOffset, it.complete) }
