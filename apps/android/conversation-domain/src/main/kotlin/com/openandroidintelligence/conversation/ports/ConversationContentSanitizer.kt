package com.openandroidintelligence.conversation.ports

import com.openandroidintelligence.conversation.model.MessagePart

/** 会话正文与离线镜像共用清洗规则，避免系统上下文进入界面和通知。 */
object ConversationContentSanitizer {
    private const val opening = "<system-reminder>"
    private val openingPattern = Regex("<system-reminder>", RegexOption.IGNORE_CASE)
    private val reminders = Regex("<system-reminder>[\\s\\S]*?</system-reminder>", RegexOption.IGNORE_CASE)

    fun text(value: String, streaming: Boolean = false): String {
        var cleaned = reminders.replace(value, "")
        // 流的下一片尚未到达时，也不能把未闭合提示当作普通正文先显示。
        openingPattern.find(cleaned)?.range?.first?.let { cleaned = cleaned.substring(0, it) }
        if (streaming) {
            val partial = (opening.length - 1 downTo 1).firstOrNull { length -> cleaned.takeLast(length).equals(opening.take(length), ignoreCase = true) }
            if (partial != null) cleaned = cleaned.dropLast(partial)
        }
        return cleaned
    }

    fun message(value: TimelineMessage): TimelineMessage? {
        if (value.isDeleted) return value
        if (value.sender !in setOf("user", "assistant") && value.executionSteps.isEmpty()) return null
        val streaming = value.state == "STREAMING"
        val parts = mutableListOf<MessagePart>()
        val pending = StringBuilder()
        fun flushText() {
            if (pending.isEmpty()) return
            text(pending.toString(), streaming).takeIf { it.isNotBlank() }?.let { parts += MessagePart.Text(it) }
            pending.setLength(0)
        }
        value.parts.forEach { part ->
            if (part is MessagePart.Text) pending.append(part.value)
            else { flushText(); parts += part }
        }
        flushText()
        val originalText = value.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        val reasoning = value.reasoning.mapNotNull { block ->
            text(block.text, streaming && !block.complete).takeIf { it.isNotBlank() }?.let { cleaned ->
                block.copy(text = cleaned, bodyOffset = text(originalText.take(block.bodyOffset), streaming).length)
            }
        }
        // 失败与执行回执可能没有正文，不能通过内容清洗吞掉真实失败。
        if (parts.isEmpty() && reasoning.isEmpty() && value.executionSteps.isEmpty() &&
            value.errorCode == null && value.localSubmissionFailure == null && value.state != "FAILED") return null
        return value.copy(parts = parts, reasoning = reasoning)
    }
}
