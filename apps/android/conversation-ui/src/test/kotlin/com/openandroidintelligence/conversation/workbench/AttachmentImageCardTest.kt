package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.AttachmentDownloadState
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图片附件与全屏预览的界面契约：
 * - 气泡内不出现文件名与下载按钮，只保留可点击的图片；
 * - 点击进入 Lightbox，支持关闭、保存与长按菜单；
 * - PDF 等非图片附件保留既有文件名与下载入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentImageCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun imageAttachmentHidesFilenameAndListDownloadButton() {
        render(imageAttachment())

        compose.onNodeWithText("camera_1792374688850.jpg").assertDoesNotExist()
        compose.onNodeWithText("下载").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看大图", substring = true).assertIsDisplayed()
    }

    @Test
    fun clickingImageOpensLightboxAndSaveUsesTheAttachmentIdentity() {
        var downloaded: String? = null
        render(imageAttachment(), onDownload = { downloaded = it })

        compose.onNodeWithContentDescription("查看大图", substring = true).performClick()
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
        compose.onNodeWithText("保存至本地").performClick()

        assertEquals("att_image", downloaded)
    }

    @Test
    fun lightboxShowsDownloadingStateInPlace() {
        render(imageAttachment(downloadState = AttachmentDownloadState.DOWNLOADING))

        compose.onNodeWithContentDescription("查看大图", substring = true).performClick()
        compose.onNodeWithText("保存中…").assertIsDisplayed()
    }

    @Test
    fun lightboxShowsSavedStateInPlace() {
        render(imageAttachment(downloadState = AttachmentDownloadState.SAVED))

        compose.onNodeWithContentDescription("查看大图", substring = true).performClick()
        compose.onNodeWithText("已保存到下载目录").assertIsDisplayed()
    }

    @Test
    fun longPressOnPreviewOpensTheSaveMenu() {
        var downloaded: String? = null
        render(imageAttachment(), onDownload = { downloaded = it })

        compose.onNodeWithContentDescription("查看大图", substring = true).performClick()
        compose.onNodeWithContentDescription("大图预览").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        compose.onNodeWithText("保存至本地").performClick()

        assertEquals("att_image", downloaded)
    }

    @Test
    fun withoutDownloadPortPreviewStillOpensButHidesSave() {
        render(imageAttachment(), onDownload = null)

        compose.onNodeWithContentDescription("查看大图", substring = true).performClick()
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
        compose.onNodeWithText("保存至本地").assertDoesNotExist()
    }

    @Test
    fun unavailableImageKeepsAHonestFallbackWithoutFilenameOrDownload() {
        render(
            imageAttachment(
                previewBytes = null,
                localState = AttachmentLocalState.UNAVAILABLE,
            ),
        )

        compose.onNodeWithText("camera_1792374688850.jpg").assertDoesNotExist()
        compose.onNodeWithText("下载").assertDoesNotExist()
        compose.onNodeWithContentDescription("查看大图", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("图片预览不可用").assertIsDisplayed()
    }

    @Test
    fun previewSurvivesTheTimelineFoldingIntoAnExecutionPanel() {
        val assistantText = androidx.compose.runtime.mutableStateOf("<think>先看一眼</think>")
        val imageEntry = TimelineEntry(
            key = "message_image_fold",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 2L,
            pendingAcceptance = false,
            batchGroupId = null,
            attachments = listOf(imageAttachment()),
            isStreaming = true,
        )
        compose.setContent {
            MaterialTheme {
                MessageTimeline(
                    listOf(
                        TimelineEntry(
                            key = "user_fold",
                            sender = "user",
                            text = "看图",
                            isUser = true,
                            timestamp = 1L,
                            pendingAcceptance = false,
                            batchGroupId = null,
                        ),
                        imageEntry.copy(text = assistantText.value),
                    ),
                    onDownloadAttachment = {},
                    onRequestOriginalAttachment = {},
                )
            }
        }

        compose.onNodeWithContentDescription("查看大图", substring = true).performClick()
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()

        // 最终正文出现后该行会从 Single 折叠成 Execution，展示项 key 改变；
        // Dialog 挂在宿主预览槽上，不能被这次重组销毁。
        compose.runOnIdle { assistantText.value = "<think>先看一眼</think>最终答案" }

        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
    }

    @Test
    fun nonImageAttachmentKeepsFilenameAndDownloadButton() {
        val entry = TimelineEntry(
            key = "message_pdf",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 1L,
            pendingAcceptance = false,
            batchGroupId = null,
            attachments = listOf(
                TimelineAttachment(
                    draftId = "att_pdf",
                    filename = "原件.pdf",
                    mediaType = "application/pdf",
                    localState = AttachmentLocalState.SAVED,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), onDownloadAttachment = {})
            }
        }

        compose.onNodeWithText("原件.pdf").assertIsDisplayed()
        compose.onNodeWithText("下载").assertIsDisplayed()
        compose.onNodeWithText("查看大图").assertDoesNotExist()
    }

    private fun render(
        attachment: TimelineAttachment,
        onDownload: ((String) -> Unit)? = { },
    ) {
        compose.setContent {
            MaterialTheme {
                MessageTimeline(
                    listOf(entry(attachment)),
                    onDownloadAttachment = onDownload,
                    onRequestOriginalAttachment = { },
                )
            }
        }
    }

    private fun entry(attachment: TimelineAttachment) = TimelineEntry(
        key = "message_image",
        sender = "assistant",
        text = "",
        isUser = false,
        timestamp = 1L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = listOf(attachment),
    )

    private fun imageAttachment(
        previewBytes: ByteArray? = null,
        localState: AttachmentLocalState = AttachmentLocalState.PENDING,
        downloadState: AttachmentDownloadState = AttachmentDownloadState.IDLE,
    ) = TimelineAttachment(
        draftId = "att_image",
        filename = "camera_1792374688850.jpg",
        mediaType = "image/jpeg",
        previewBytes = previewBytes,
        localState = localState,
        downloadState = downloadState,
    )
}
