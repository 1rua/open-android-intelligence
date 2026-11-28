package com.openandroidintelligence.conversation.sanitizer

/**
 * 系统上下文提示词与指令剥离清洗器（兼容层，委托给 [ContextInjectionFilter] 统一管线）。
 */
object SystemPromptSanitizer {
    /**
     * 对输入文本执行统一上下文注入净化。
     * 单参数重载保留默认流式清洗语义，所有规则均纯委托至领域唯一权威 [ContextInjectionFilter]。
     */
    fun sanitize(text: String): String =
        sanitize(text, streaming = true)

    /** 按指定流式状态对输入文本执行统一上下文注入净化（纯委托，零独立正则分叉）。 */
    fun sanitize(text: String, streaming: Boolean): String {
        if (text.isEmpty()) return ""
        return ContextInjectionFilter.sanitizeText(
            text = text,
            role = ContextInjectionFilter.SenderRole.ASSISTANT,
            streaming = streaming,
        )
    }

    /** 判定一条消息是否应当直接丢弃不上屏。 */
    fun shouldDropMessage(
        text: String,
        hasAttachments: Boolean = false,
        hasSteps: Boolean = false,
        hasReasoning: Boolean = false,
        isMeta: Boolean = false,
        sender: String? = "assistant",
        visibility: String? = null,
        messageType: String? = null,
    ): Boolean = ContextInjectionFilter.shouldDropMessage(
        text = text,
        hasAttachments = hasAttachments,
        hasSteps = hasSteps,
        hasReasoning = hasReasoning,
        isMeta = isMeta,
        sender = sender,
        visibility = visibility,
        messageType = messageType,
    )

    /** 兼容别名方法，等同于 [shouldDropMessage]。 */
    fun shouldDiscard(
        sanitizedText: String,
        hasAttachments: Boolean = false,
        hasExecutionSteps: Boolean = false,
        hasReasoning: Boolean = false,
        isMeta: Boolean = false,
        sender: String? = "assistant",
        visibility: String? = null,
        messageType: String? = null,
    ): Boolean = shouldDropMessage(
        text = sanitizedText,
        hasAttachments = hasAttachments,
        hasSteps = hasExecutionSteps,
        hasReasoning = hasReasoning,
        isMeta = isMeta,
        sender = sender,
        visibility = visibility,
        messageType = messageType,
    )
}
