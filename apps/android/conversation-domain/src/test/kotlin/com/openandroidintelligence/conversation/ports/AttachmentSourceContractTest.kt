package com.openandroidintelligence.conversation.ports

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSourceContractTest {
    @Test
    fun aSelectedAttachmentCanReopenItsSourceWithoutHoldingItsCompleteBytes() {
        var opens = 0
        val selection = LocalAttachmentSelection(
            filename = "photo.heic",
            mediaType = "image/heic",
            contentSource = AttachmentContentSource {
                opens++
                ByteArrayInputStream("source".toByteArray())
            },
        )

        assertEquals("source", selection.contentSource.openStream().use { it.readBytes().decodeToString() })
        assertEquals("source", selection.contentSource.openStream().use { it.readBytes().decodeToString() })
        assertEquals(2, opens)
    }

    @Test
    fun aWriterBackedImageSourceStreamsToTheStagingReaderAndPropagatesWriterErrors() {
        val source = AttachmentContentSource.fromWriter { output -> output.write("camera-bytes".toByteArray()) }
        assertEquals("camera-bytes", source.openStream().use { it.readBytes().decodeToString() })

        val failing = AttachmentContentSource.fromWriter { throw IllegalStateException("CAMERA_IMAGE_ENCODING_FAILED") }
        val failure = runCatching { failing.openStream().use { it.readBytes() } }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("ATTACHMENT_READ_FAILED"))
    }
}
