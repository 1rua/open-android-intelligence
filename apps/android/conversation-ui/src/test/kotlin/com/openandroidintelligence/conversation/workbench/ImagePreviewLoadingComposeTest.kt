package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreviewLoadingComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun validPreviewDoesNotShowFailureWhileDecodeIsPending() {
        val decodeStarted = CompletableDeferred<Unit>()
        val decodeGate = CompletableDeferred<Unit>()
        val bytes = validPng()
        var decoderInput: ByteArray? = null
        val decoder = ImagePreviewDecoder { source, maxEdgePx ->
            decoderInput = source
            decodeStarted.complete(Unit)
            decodeGate.await()
            decodeImagePreview(source, maxEdgePx)
        }

        compose.setContent {
            CompositionLocalProvider(LocalImagePreviewDecoder provides decoder) {
                MaterialTheme {
                    MessageTimeline(
                        entries = listOf(entry(bytes)),
                        onRequestOriginalAttachment = {},
                    )
                }
            }
        }

        try {
            compose.waitUntil(timeoutMillis = 5_000) { decodeStarted.isCompleted }
            assertNotSame(bytes, decoderInput)
            compose.onNodeWithContentDescription("图片预览不可用").assertDoesNotExist()
            compose.onNodeWithText("图片预览不可用").assertDoesNotExist()

            bytes.fill(0)
            decodeGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("attachment-image:att_preview", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("attachment-image:att_preview", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(decoderInput!!.all { it == 0.toByte() })
            compose.onNodeWithContentDescription("图片预览不可用").assertDoesNotExist()
            compose.onNodeWithText("图片预览不可用").assertDoesNotExist()
        } finally {
            decodeGate.complete(Unit)
        }
    }

    @Test fun aLocalThumbnailReadStillInProgressDoesNotDisplayAnUnavailableImage() {
        val waiting=entry(validPng()).let { row -> row.copy(attachments=row.attachments.map {
            it.copy(previewBytes=null,localState=AttachmentLocalState.PENDING)
        }) }
        compose.setContent { MaterialTheme { TimelineRow(entry=waiting,onDecide={_,_->}) } }
        compose.onNodeWithText("图片预览不可用").assertDoesNotExist()
        compose.onNodeWithContentDescription("图片预览不可用").assertDoesNotExist()
    }

    @Test
    fun sameAttachmentKeepsLastSuccessfulPreviewWhileReplacementDecodes() {
        val firstBytes = validPng()
        val replacementStarted = CompletableDeferred<Unit>()
        val replacementGate = CompletableDeferred<Unit>()
        val replacementBytes = validPng(width = 4)
        var replacementInput: ByteArray? = null
        var decodeCount = 0
        val decoder = ImagePreviewDecoder { source, maxEdgePx ->
            if (decodeCount++ == 0) {
                decodeImagePreview(source, maxEdgePx)
            } else {
                replacementInput = source
                replacementStarted.complete(Unit)
                replacementGate.await()
                decodeImagePreview(source, maxEdgePx)
            }
        }
        val currentEntry = mutableStateOf(entry(firstBytes))

        compose.setContent {
            CompositionLocalProvider(
                LocalImagePreviewScope provides "conversation-a/agent-a",
                LocalImagePreviewDecoder provides decoder,
            ) {
                MaterialTheme {
                    TimelineRow(entry = currentEntry.value, onDecide = { _, _ -> })
                }
            }
        }

        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("attachment-image:att_preview", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                currentEntry.value = entry(replacementBytes).copy(timestamp = 2L)
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 5_000) { replacementStarted.isCompleted }
            assertNotSame(replacementBytes, replacementInput)
            compose.onNodeWithTag("attachment-image:att_preview", useUnmergedTree = true).assertIsDisplayed()
            replacementBytes.fill(0)
            replacementGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) {
                replacementInput!!.all { it == 0.toByte() }
            }
            compose.onNodeWithTag("attachment-image:att_preview", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            replacementGate.complete(Unit)
        }
    }

    @Test
    fun changingPreviewScopeDoesNotReuseThePreviousAttachmentBitmap() {
        val firstBytes = validPng()
        val nextScopeStarted = CompletableDeferred<Unit>()
        val nextScopeGate = CompletableDeferred<Unit>()
        val scope = mutableStateOf<Any?>("conversation-a/agent-a")
        var decodeCount = 0
        val decoder = ImagePreviewDecoder { source, maxEdgePx ->
            if (decodeCount++ == 0) {
                decodeImagePreview(source, maxEdgePx)
            } else {
                nextScopeStarted.complete(Unit)
                nextScopeGate.await()
                decodeImagePreview(source, maxEdgePx)
            }
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalImagePreviewScope provides scope.value,
                LocalImagePreviewDecoder provides decoder,
            ) {
                MaterialTheme {
                    TimelineRow(entry = entry(firstBytes), onDecide = { _, _ -> })
                }
            }
        }

        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("attachment-image:att_preview", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { scope.value = "conversation-b/agent-a" }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 5_000) { nextScopeStarted.isCompleted }
            compose.onAllNodesWithTag("attachment-image:att_preview", useUnmergedTree = true)
                .assertCountEquals(0)
        } finally {
            nextScopeGate.complete(Unit)
        }
    }

    @Test
    fun confirmedPlatformDecodeFailureKeepsTheUnavailablePreviewMessage() {
        val decoder = ImagePreviewDecoder { _, _ -> null }

        compose.setContent {
            CompositionLocalProvider(LocalImagePreviewDecoder provides decoder) {
                MaterialTheme {
                    MessageTimeline(
                        entries = listOf(entry(validPng())),
                        onRequestOriginalAttachment = {},
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("图片预览不可用").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("图片预览不可用").assertIsDisplayed()
    }

    private fun entry(bytes: ByteArray) = TimelineEntry(
        key = "message_with_preview",
        sender = "user",
        text = "",
        isUser = true,
        timestamp = 1L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = listOf(
            TimelineAttachment(
                draftId = "att_preview",
                filename = "preview.png",
                mediaType = "image/png",
                previewBytes = bytes,
                localState = AttachmentLocalState.SAVED,
            ),
        ),
    )

    private fun validPng(width: Int = 3): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(width, 2, Bitmap.Config.ARGB_8888)
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
