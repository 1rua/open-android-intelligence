package com.openandroidintelligence.mobile

internal const val RUNNING_REPLY_EXCERPT_CODE_POINTS = 420
// Android sanitizes notification fields at 1,024 UTF-16 units. Even an all-emoji
// excerpt stays below that limit, so the final tail and full-reply hint survive.
internal const val TERMINAL_REPLY_EXCERPT_CODE_POINTS = 480
internal const val COMPLETE_REPLY_HINT = "点击查看完整回复"

/** Bound system-facing text without changing the process-owned full reply snapshot. */
internal fun notificationReplyExcerpt(text: String, isRunning: Boolean): String {
    val limit = if (isRunning) RUNNING_REPLY_EXCERPT_CODE_POINTS else TERMINAL_REPLY_EXCERPT_CODE_POINTS
    val count = text.codePointCount(0, text.length)
    if (count <= limit) return text
    if (isRunning) {
        val start = text.offsetByCodePoints(text.length, -(limit - 1))
        return "…" + text.substring(start)
    }
    val separator = "\n…\n"
    val hint = "\n\n$COMPLETE_REPLY_HINT"
    val bodyBudget = limit - separator.codePointCount(0, separator.length) - hint.codePointCount(0, hint.length)
    val headCount = bodyBudget / 2
    val tailCount = bodyBudget - headCount
    return text.substring(0, text.offsetByCodePoints(0, headCount)) + separator +
        text.substring(text.offsetByCodePoints(text.length, -tailCount)) + hint
}
