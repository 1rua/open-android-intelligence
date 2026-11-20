package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter

internal const val RUNNING_REPLY_EXCERPT_CODE_POINTS = 420
// Android sanitizes notification fields at 1,024 UTF-16 units. Even an all-emoji
// excerpt stays below that limit, so the final tail and full-reply hint survive.
internal const val TERMINAL_REPLY_EXCERPT_CODE_POINTS = 480

/** Bound system-facing text without changing the process-owned full reply snapshot. */
internal fun notificationReplyExcerpt(text: String, isRunning: Boolean, completeHint: String = ""): String {
    val cleanText = ContextInjectionFilter.sanitizeText(
        text = text,
        role = ContextInjectionFilter.SenderRole.ASSISTANT,
        streaming = isRunning,
    )
    val limit = if (isRunning) RUNNING_REPLY_EXCERPT_CODE_POINTS else TERMINAL_REPLY_EXCERPT_CODE_POINTS
    val count = cleanText.codePointCount(0, cleanText.length)
    if (count <= limit) return cleanText
    if (isRunning) {
        val start = cleanText.offsetByCodePoints(cleanText.length, -(limit - 1))
        return "…" + cleanText.substring(start)
    }
    val separator = "\n…\n"
    val hint = if (completeHint.isNotEmpty()) "\n\n$completeHint" else ""
    val bodyBudget = limit - separator.codePointCount(0, separator.length) - hint.codePointCount(0, hint.length)
    val headCount = bodyBudget / 2
    val tailCount = bodyBudget - headCount
    return cleanText.substring(0, cleanText.offsetByCodePoints(0, headCount)) + separator +
        cleanText.substring(cleanText.offsetByCodePoints(cleanText.length, -tailCount)) + hint
}
