package com.openandroidintelligence.conversation.title

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class ConversationTitlePolicyTest {

    private fun msg(text: String, attachmentIds: List<String> = emptyList()): OutgoingMessage =
        OutgoingMessage(
            clientMessageId = ClientMessageId("cmid_1"),
            text = text,
            attachmentIds = attachmentIds,
        )

    @Test
    fun newCommandReturnsDefaultTitle() {
        assertEquals("新对话", ConversationTitlePolicy.generateTitle(msg("/new")))
        assertEquals("新对话", ConversationTitlePolicy.generateTitle(msg("/new topic")))
        assertEquals("新对话", ConversationTitlePolicy.generateTitle(msg("   /new   ")))
    }

    @Test
    fun commandBoundaryPreservesNonNewCommands() {
        assertEquals("/newest ideas", ConversationTitlePolicy.generateTitle(msg("/newest ideas")))
    }

    @Test
    fun normalTextGeneratesFirstLineNormalizedTitle() {
        assertEquals("今天天气怎么样？", ConversationTitlePolicy.generateTitle(msg("  今天天气怎么样？\n明天会下雨吗？  ")))
    }

    @Test
    fun normalizesConsecutiveWhitespace() {
        val input = "  Hello   \t   world   how   are  you  "
        assertEquals("Hello world how are you", ConversationTitlePolicy.generateTitle(msg(input)))
    }

    @Test
    fun exact48GraphemesIsNotTruncated() {
        val text48 = "A".repeat(48)
        assertEquals(text48, ConversationTitlePolicy.generateTitle(msg(text48)))
    }

    @Test
    fun exceeds48GraphemesIsTruncatedWithEllipsis() {
        val text49 = "A".repeat(49)
        val expected = "A".repeat(48) + "…"
        assertEquals(expected, ConversationTitlePolicy.generateTitle(msg(text49)))

        val text100 = "B".repeat(100)
        val expected100 = "B".repeat(48) + "…"
        assertEquals(expected100, ConversationTitlePolicy.generateTitle(msg(text100)))
    }

    @Test
    fun unicodeChineseCharactersTruncatedCorrectly() {
        val cn48 = "测".repeat(48)
        assertEquals(48, cn48.length)
        assertEquals(cn48, ConversationTitlePolicy.generateTitle(msg(cn48)))

        val cn50 = cn48 + "超长"
        assertEquals(cn48 + "…", ConversationTitlePolicy.generateTitle(msg(cn50)))
    }

    @Test
    fun unicodeEmojiGraphemeClustersTruncatedSafely() {
        // Each emoji flag or person cluster should be treated as 1 grapheme cluster
        val emojis48 = "🎉".repeat(48)
        assertEquals(emojis48, ConversationTitlePolicy.generateTitle(msg(emojis48)))

        val emojis50 = "🎉".repeat(50)
        assertEquals("🎉".repeat(48) + "…", ConversationTitlePolicy.generateTitle(msg(emojis50)))
    }

    @Test
    fun blankTextWithVoiceAttachmentReturnsVoiceTitle() {
        assertEquals(
            "语音消息",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("recording.m4a")),
        )
        assertEquals(
            "语音消息",
            ConversationTitlePolicy.generateTitle(msg("   "), listOf("audio.aac")),
        )
        assertEquals(
            "语音消息",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("my_voice_memo.mp3")),
        )
        assertEquals(
            "语音消息",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("VOICE_RECORD_01.WAV")),
        )
    }

    @Test
    fun blankTextWithScreenCropAttachmentReturnsScreenCropTitle() {
        assertEquals(
            "屏幕选区",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("screenshot_2026.png")),
        )
        assertEquals(
            "屏幕选区",
            ConversationTitlePolicy.generateTitle(msg("  "), listOf("screen_area.jpg")),
        )
        assertEquals(
            "屏幕选区",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("crop_selection.png")),
        )
        assertEquals(
            "屏幕选区",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("IMAGE_CROP.PNG")),
        )
    }

    @Test
    fun blankTextWithRegularFilenameUsesFilename() {
        assertEquals(
            "document.pdf",
            ConversationTitlePolicy.generateTitle(msg(""), listOf("document.pdf")),
        )
        assertEquals(
            "report_2026.xlsx",
            ConversationTitlePolicy.generateTitle(msg("   "), listOf("  report_2026.xlsx  ")),
        )

        val longFilename = "F".repeat(60) + ".dat"
        val expectedLong = ("F".repeat(60) + ".dat").take(48) + "…"
        assertEquals(
            expectedLong,
            ConversationTitlePolicy.generateTitle(msg(""), listOf(longFilename)),
        )
    }

    @Test
    fun blankTextWithEmptyAttachmentNamesFallsBackToAttachmentIds() {
        assertEquals(
            "附件内容",
            ConversationTitlePolicy.generateTitle(msg("", attachmentIds = listOf("att_1")), emptyList()),
        )
        assertEquals(
            "附件内容",
            ConversationTitlePolicy.generateTitle(msg("", attachmentIds = listOf("att_1")), listOf("   ")),
        )
    }

    @Test
    fun blankTextWithoutAttachmentsReturnsDefaultTitle() {
        assertEquals("新对话", ConversationTitlePolicy.generateTitle(msg("")))
        assertEquals("新对话", ConversationTitlePolicy.generateTitle(msg("   ")))
        assertEquals("新对话", ConversationTitlePolicy.generateTitle(msg(""), emptyList()))
    }

    @Test
    fun supportsOverloadSignatures() {
        val message = msg("Hello World")
        assertEquals("Hello World", ConversationTitlePolicy.generateTitle(message))
        assertEquals("Hello World", ConversationTitlePolicy.generateTitle(message, Locale.CHINA))
        assertEquals("Hello World", ConversationTitlePolicy.generateTitle(message, emptyList()))
        assertEquals("Hello World", ConversationTitlePolicy.generateTitle(message, emptyList(), Locale.US))
    }
}
