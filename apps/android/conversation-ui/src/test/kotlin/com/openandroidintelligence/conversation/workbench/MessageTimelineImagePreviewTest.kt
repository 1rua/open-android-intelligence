package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimelineImagePreviewTest {

    @Test
    fun timelineAttachmentIdentifiesImageMimeAndExtensions() {
        val jpgAttachment = TimelineAttachment(
            draftId = "att_1",
            filename = "photo.jpg",
            mediaType = "image/jpeg",
            imageBytes = byteArrayOf(1, 2, 3),
        )
        assertTrue(jpgAttachment.isImage)

        val docAttachment = TimelineAttachment(
            draftId = "att_2",
            filename = "document.pdf",
            mediaType = "application/pdf",
        )
        assertFalse(docAttachment.isImage)
    }

    @Test
    fun timelineEntryHoldsAttachmentsList() {
        val attachment = TimelineAttachment(
            draftId = "att_test",
            filename = "test.png",
            mediaType = "image/png",
            imageBytes = byteArrayOf(0, 1),
        )
        val entry = TimelineEntry(
            key = "entry_1",
            sender = "user",
            text = "",
            isUser = true,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            attachments = listOf(attachment),
        )
        assertEquals(1, entry.attachments.size)
        assertEquals("att_test", entry.attachments[0].draftId)
        assertTrue(entry.attachments[0].isImage)
    }
}
