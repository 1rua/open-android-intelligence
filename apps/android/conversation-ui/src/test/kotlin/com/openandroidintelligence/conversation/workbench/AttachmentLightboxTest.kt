package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.openandroidintelligence.conversation.model.AttachmentDownloadState
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.OriginalImageState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全屏大图预览的界面契约。
 *
 * 不依赖真实图片解码成功：解码失败也必须保留关闭、保存与明确的回退提示节点，
 * 否则用户在最需要退出/保存的时候反而没有可点的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentLightboxTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [31, 35])
    fun previewWindowCoversTheDisplayWithoutAnInsetOrCenteredDialogFrame() {
        render(attachment())
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
        compose.runOnIdle {
            val window = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
            assertEquals(android.view.Gravity.TOP, window.attributes.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
            assertEquals(0, window.attributes.fitInsetsTypes)
        }
    }

    @Test
    fun lightboxShowsCloseFilenameAndSaveAction() {
        render(attachment())

        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
        compose.onNodeWithText("海边日落.jpg").assertIsDisplayed()
        compose.onNodeWithText("保存至本地").assertIsDisplayed()
    }

    @Test
    fun closeButtonClosesThePreview() {
        var dismissed = false
        render(attachment(), onDismiss = { dismissed = true })

        compose.onNodeWithContentDescription("关闭大图预览").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun saveActionDownloadsTheAttachmentIdentity() {
        var downloaded: String? = null
        render(attachment(), onDownload = { downloaded = it })

        compose.onNodeWithText("保存至本地").performClick()

        assertEquals("att_image", downloaded)
    }

    @Test
    fun downloadingStateIsShownInPlace() {
        render(attachment(downloadState = AttachmentDownloadState.DOWNLOADING))

        compose.onNodeWithText("保存中…").assertIsDisplayed()
    }

    @Test
    fun savedStateIsShownInPlace() {
        render(attachment(downloadState = AttachmentDownloadState.SAVED))

        compose.onNodeWithText("已保存到下载目录").assertIsDisplayed()
    }

    @Test
    fun failedDownloadOffersRetryInPlace() {
        var downloaded: String? = null
        render(attachment(downloadState = AttachmentDownloadState.FAILED), onDownload = { downloaded = it })

        compose.onNodeWithText("保存失败，重试保存").assertIsDisplayed()
        compose.onNodeWithText("保存失败，重试保存").performClick()

        assertEquals("失败后必须允许就地重试", "att_image", downloaded)
    }

    @Test
    fun longPressOpensSaveAndCloseMenu() {
        var downloaded: String? = null
        render(attachment(), onDownload = { downloaded = it })

        openLongPressMenu()
        clickMenuItem("保存至本地")

        assertEquals("att_image", downloaded)
    }

    @Test
    fun longPressMenuCanCloseThePreview() {
        var dismissed = false
        render(attachment(), onDismiss = { dismissed = true })

        openLongPressMenu()
        clickMenuItem("关闭")

        assertTrue(dismissed)
    }

    @Test
    fun withoutADownloadPortSaveActionIsHiddenButPreviewStaysUsable() {
        render(attachment(), onDownload = null)

        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
        compose.onNodeWithText("保存至本地").assertDoesNotExist()

        openLongPressMenu()
        compose.onNodeWithText("保存至本地").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertExists()
    }

    @Test
    fun failedOriginalShowsFallbackHintInsteadOfBlank() {
        render(attachment(originalState = OriginalImageState.FAILED, originalBytes = null))

        compose.onNodeWithText("原图", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
        compose.onNodeWithText("保存至本地").assertIsDisplayed()
    }

    @Test
    fun failedOriginalOffersAnExplicitRetry() {
        var requests = 0
        render(
            attachment(originalState = OriginalImageState.FAILED),
            onRequestOriginal = { requests += 1 },
        )

        compose.onNodeWithText("重试加载原图").performClick()

        assertEquals("失败后的显式重试必须重新请求原图", 1, requests)
    }

    @Test
    fun loadingOriginalShowsLoadingHintInsteadOfBlank() {
        render(attachment(originalState = OriginalImageState.LOADING))

        compose.onNodeWithText("原图", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
    }

    @Test
    fun openingRequestsTheOriginalOnlyWhenIdle() {
        var requests = 0
        render(attachment(originalState = OriginalImageState.IDLE), onRequestOriginal = { requests += 1 })

        compose.waitForIdle()

        assertEquals("打开时应请求一次原图", 1, requests)
    }

    @Test
    fun readyOriginalIsNotRequestedAgain() {
        var requests = 0
        render(
            attachment(originalState = OriginalImageState.READY, originalBytes = byteArrayOf(1, 2, 3)),
            onRequestOriginal = { requests += 1 },
        )

        compose.waitForIdle()

        assertEquals("已有原图时不再重复请求", 0, requests)
        compose.onNodeWithContentDescription("关闭大图预览").assertIsDisplayed()
    }

    private fun render(
        attachment: TimelineAttachment,
        onDismiss: () -> Unit = {},
        onDownload: ((String) -> Unit)? = { },
        onRequestOriginal: ((String) -> Unit)? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                AttachmentLightbox(
                    attachment = attachment,
                    onDismiss = onDismiss,
                    onDownload = onDownload,
                    onRequestOriginal = onRequestOriginal,
                )
            }
        }
    }

    private fun attachment(
        previewBytes: ByteArray? = null,
        originalBytes: ByteArray? = null,
        originalState: OriginalImageState = OriginalImageState.IDLE,
        downloadState: AttachmentDownloadState = AttachmentDownloadState.IDLE,
    ) = TimelineAttachment(
        draftId = "att_image",
        filename = "海边日落.jpg",
        mediaType = "image/jpeg",
        previewBytes = previewBytes,
        localState = AttachmentLocalState.SAVED,
        downloadState = downloadState,
        originalBytes = originalBytes,
        originalState = originalState,
    )

/**
 * 通过无障碍长按动作打开菜单。
 *
 * Robolectric 里 DropdownMenu 的 Popup 窗口不做真实布局，真实触摸的长按会
 * 在弹出后又被同一次手势的 up 当成外部点击收起；语义动作直接走产品代码里的
 * 同一条 onLongPress 回调，既稳定又不依赖真机触摸时序。
 */
private fun openLongPressMenu() {
    compose.onNodeWithContentDescription("大图预览")
        .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
}

/** 菜单项同样用语义点击触发，避免依赖 Popup 在 Robolectric 中的屏幕坐标。 */
private fun clickMenuItem(text: String) {
    compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
}
}
