package com.openandroidintelligence.conversation.sanitizer

/**
 * 系统上下文提示词与指令剥离清洗器。
 *
 * 负责在实时流接收、历史加载回放以及 UI 渲染出口三层架构中，
 * 彻底剥离上游注入的 `<system-reminder>`、时区信息以及 `AGENTS.md` 规则等元提示词，
 * 并提供准确的空消息判定逻辑，防止空气泡上屏。
 */
object SystemPromptSanitizer {
    /** 匹配 <system-reminder>...</system-reminder> 标签块，支持流式未闭合片断截断及遗留标签清除。 */
    private val SYSTEM_REMINDER_REGEX = Regex(
        "(?i)<system-reminder>[\\s\\S]*?(?:</system-reminder>|$)|</system-reminder>"
    )

    /** 匹配客户端/浏览器时区注入行。 */
    private val TIME_ZONE_REGEX = Regex(
        "(?i)Browser time zone for this request:[^\\r\\n]*(?:\\r?\\n|$)"
    )

    /** 匹配 AGENTS.md 规则指令块，直至连续换行分界或文本末尾。 */
    private val AGENTS_MD_REGEX = Regex(
        "(?i)(?:Instructions from:\\s*~?/?\\.?dsh/AGENTS\\.md|Instructions from:\\s*[^\\r\\n]*AGENTS\\.md)[\\s\\S]*?(?=(?:\\r?\\n){2,}|$)"
    )

    /**
     * 对输入文本执行三项正则脱敏并清除首尾多余空白。
     *
     * @param text 待清洗的原始文本
     * @return 脱敏后的文本内容
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return ""
        var result = text
        result = SYSTEM_REMINDER_REGEX.replace(result, "")
        result = TIME_ZONE_REGEX.replace(result, "")
        result = AGENTS_MD_REGEX.replace(result, "")
        return result.trim()
    }

    /**
     * 判定一条消息是否应当直接丢弃不上屏。
     *
     * 判定规则：
     * 1. 若标记为元数据消息 (isMeta == true)，必须无条件丢弃；
     * 2. 若脱敏后正文为空，且既无附件、也无工具执行步骤、亦无正在流式的思考内容时，判定为空气泡应丢弃；
     * 3. 正在流式输出思考内容（hasReasoning == true）或包含执行步骤/附件的消息绝对不可丢弃。
     */
    fun shouldDropMessage(
        text: String,
        hasAttachments: Boolean = false,
        hasSteps: Boolean = false,
        hasReasoning: Boolean = false,
        isMeta: Boolean = false,
    ): Boolean {
        if (isMeta) return true
        return text.isBlank() && !hasAttachments && !hasSteps && !hasReasoning
    }

    /**
     * 兼容别名方法，等同于 shouldDropMessage。
     */
    fun shouldDiscard(
        sanitizedText: String,
        hasAttachments: Boolean = false,
        hasExecutionSteps: Boolean = false,
        hasReasoning: Boolean = false,
        isMeta: Boolean = false,
    ): Boolean = shouldDropMessage(
        text = sanitizedText,
        hasAttachments = hasAttachments,
        hasSteps = hasExecutionSteps,
        hasReasoning = hasReasoning,
        isMeta = isMeta,
    )
}
