package com.openandroidintelligence.conversation.sanitizer

/**
 * 系统上下文提示词与指令剥离清洗器（兼容层，委托给 [ContextInjectionFilter] 统一管线）。
 */
object SystemPromptSanitizer {
    private val LEGACY_SYSTEM_REMINDER_REGEX = Regex(
        "(?i)<system-reminder>[\\s\\S]*?</system-reminder>|</system-reminder>"
    )

    /**
     * 对输入文本执行统一上下文注入净化。
     * 单参数重载保留流式未闭合与正文内历史 `<system-reminder>` 标签兼容行为，
     * 避免破坏既有单参清洗调用方的语义；新管线请直接调用 [ContextInjectionFilter]。
     */
    fun sanitize(text: String): String =
        sanitize(text, streaming = true)

    /** 按指定流式状态对输入文本执行统一上下文注入净化。 */
    fun sanitize(text: String, streaming: Boolean): String {
        if (text.isEmpty()) return ""
        val cleaned = ContextInjectionFilter.sanitizeText(
            text = text,
            role = ContextInjectionFilter.SenderRole.ASSISTANT,
            streaming = streaming,
        )
        // 兼容历史单参清洗中处于助手文本中间的闭合/孤立 `<system-reminder>` 片段，代码块受 ContextInjectionFilter 保护思路豁免
        val protected = ContextInjectionFilter.protectedCodeRangesForLegacyCompat(cleaned, streaming)
        val legacyCleaned = LEGACY_SYSTEM_REMINDER_REGEX.replace(cleaned) { match ->
            if (protected.any { match.range.first in it }) match.value else ""
        }
        return ContextInjectionFilter.sanitizeText(
            text = legacyCleaned,
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
