package com.openandroidintelligence.conversation.title

import com.openandroidintelligence.conversation.ports.OutgoingMessage
import java.text.BreakIterator
import java.util.Locale

object ConversationTitlePolicy {
    private const val MAX_GRAPHEMES = 48
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun generateTitle(
        firstMessage: OutgoingMessage,
        attachmentNames: List<String> = emptyList(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val rawText = firstMessage.text.trim()
        if (rawText == "/new" || rawText.startsWith("/new ")) {
            return "新对话"
        }
        if (rawText.isBlank()) {
            val firstAttachment = attachmentNames.firstOrNull { it.isNotBlank() }?.trim()
            return when {
                firstAttachment != null -> {
                    val lower = firstAttachment.lowercase(Locale.ROOT)
                    when {
                        lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.contains("voice") -> "语音消息"
                        lower.contains("screen") || lower.contains("crop") -> "屏幕选区"
                        else -> truncateGraphemes(firstAttachment, locale)
                    }
                }
                firstMessage.attachmentIds.isNotEmpty() -> "附件内容"
                else -> "新对话"
            }
        }

        val firstLine = rawText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "新对话"
        return truncateGraphemes(firstLine, locale)
    }

    fun generateTitle(firstMessage: OutgoingMessage, locale: Locale): String =
        generateTitle(firstMessage, emptyList(), locale)

    private fun truncateGraphemes(text: String, locale: Locale): String {
        val normalized = text.replace(WHITESPACE_REGEX, " ").trim()
        val iterator = BreakIterator.getCharacterInstance(locale)
        iterator.setText(normalized)

        var count = 0
        var boundary = 0
        while (iterator.next() != BreakIterator.DONE) {
            count++
            if (count <= MAX_GRAPHEMES) {
                boundary = iterator.current()
            } else {
                break
            }
        }

        return if (count > MAX_GRAPHEMES) {
            normalized.substring(0, boundary) + "…"
        } else {
            normalized
        }
    }
}

