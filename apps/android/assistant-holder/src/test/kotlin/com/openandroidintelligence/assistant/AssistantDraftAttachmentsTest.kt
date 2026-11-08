package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import com.openandroidintelligence.conversation.assistant.AssistantAttachmentDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * 草稿附件缩略条的投影回归。
 *
 * 协议只传元数据：图片缩略图必须按 `localKey` 从本进程存储取回并解码；
 * 取不到时保持 null，输入栏回退到按媒体类型区分的图标，而不是画一张假图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantDraftAttachmentsTest {

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }

    private fun dto(
        draftId: String = "draft-1",
        filename: String = "screen.png",
        mediaType: String = "image/png",
        isImage: Boolean = true,
        localKey: String = draftId,
    ) = AssistantAttachmentDto(
        draftId = draftId,
        filename = filename,
        mediaType = mediaType,
        isImage = isImage,
        localKey = localKey,
    )

    @Test fun anImageAttachmentUsesTheLocalThumbnailKeyedByLocalKey() {
        val previews = AssistantLocalPreviews()
        assertTrue(previews.put("draft-1", png()))

        val attachment = dto().toCompactAttachment(previews)

        assertEquals("draft-1", attachment.id)
        assertEquals("screen.png", attachment.filename)
        assertEquals("image/png", attachment.mediaType)
        assertTrue("图片附件必须带上解码后的缩略图", attachment.thumbnail != null)
    }

    @Test fun aMissingOrUndecodableThumbnailStaysNullSoTheBarFallsBackToAnIcon() {
        val previews = AssistantLocalPreviews()

        assertNull("没有本地字节时不得伪造缩略图", dto().toCompactAttachment(previews).thumbnail)

        previews.put("draft-1", byteArrayOf(1, 2, 3, 4))
        assertNull("坏字节解码失败时同样回退到图标", dto().toCompactAttachment(previews).thumbnail)
    }

    @Test fun nonImageAttachmentsNeverCarryABitmap() {
        val previews = AssistantLocalPreviews()
        previews.put("draft-2", png())

        val voice = dto(
            draftId = "draft-2",
            filename = "voice_1.m4a",
            mediaType = "audio/mp4",
            isImage = false,
        ).toCompactAttachment(previews)

        assertNull(voice.thumbnail)
        assertEquals("audio/mp4", voice.mediaType)
    }

    @Test fun aBlankFilenameFallsBackToTheDraftId() {
        val attachment = dto(filename = "").toCompactAttachment(AssistantLocalPreviews())

        assertEquals("draft-1", attachment.filename)
    }
}
