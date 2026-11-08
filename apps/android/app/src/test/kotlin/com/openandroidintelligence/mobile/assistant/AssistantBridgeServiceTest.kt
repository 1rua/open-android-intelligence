package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.assistant.toDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 助理桥接 v2 发布的纯投影规则；跨进程帧本身由集成环境验证。 */
class AssistantBridgeServiceTest {

    @Test fun noticesBecomeStableCodesAndKeepTheMessage() {
        val notice = noticeFor("AGENT_OFFLINE:当前 Agent 不可用")
        assertEquals("AGENT_OFFLINE", notice.code)
        assertEquals("AGENT_OFFLINE:当前 Agent 不可用", notice.message)
    }

    @Test fun localizedNoticesUseTheGenericCode() {
        val notice = noticeFor("操作未完成，请检查当前连接或对话")
        assertEquals("ASSISTANT_NOTICE", notice.code)
        assertEquals("操作未完成，请检查当前连接或对话", notice.message)
    }

    @Test fun audioExtensionsFollowTheDeclaredMediaType() {
        assertEquals("m4a", audioExtension("audio/mp4"))
        assertEquals("aac", audioExtension("audio/aac"))
        assertEquals("mp3", audioExtension("audio/mpeg"))
        assertEquals("ogg", audioExtension("audio/opus"))
        assertEquals("webm", audioExtension("audio/webm"))
        // 未知类型不编造格式，回落到默认容器扩展名。
        assertEquals("m4a", audioExtension("audio/x-unknown"))
    }

    @Test fun draftAttachmentsPublishMetadataOnlyAndMapFailures() {
        val draft = AttachmentDraft(
            id = AttachmentDraftId("draft-1"),
            filename = "photo.png",
            mediaType = "image/png",
            sizeBytes = 12,
            sha256 = "0".repeat(64),
            state = AttachmentState.TERMINAL_FAILURE,
        )
        val dto = draft.toAssistantDto()
        assertEquals("draft-1", dto.draftId)
        assertEquals("photo.png", dto.filename)
        assertEquals("image/png", dto.mediaType)
        assertTrue(dto.isImage)
        assertEquals("draft-1", dto.localKey)
        assertEquals(AttachmentLocalState.FAILED.name, dto.localState)
    }

    @Test fun timelineAttachmentIsNotAnImageForAudioMediaTypes() {
        val audio = TimelineAttachment(draftId = "voice-1", filename = "voice.m4a", mediaType = "audio/mp4")
        assertFalse(audio.isImage)
        assertTrue(audio.isAudio)
    }

    @Test fun assistantEntryProjectionCarriesTheStableIdentity() {
        val entry = TimelineEntry(
            key = "m1",
            sender = "user",
            text = "hello",
            isUser = true,
            timestamp = 0L,
            pendingAcceptance = false,
            batchGroupId = null,
        )
        val dto = entry.toDto()
        assertEquals("m1", dto.id)
        assertEquals("user", dto.sender)
        assertEquals("hello", dto.text)
        assertNull(dto.approval)
    }
}
