package com.openandroidintelligence.mobile

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 35], application = Application::class)
class SharedConversationAttachmentsTest {
    private val photo = Uri.parse("content://gallery/photo/1")
    private val otherPhoto = Uri.parse("content://gallery/photo/2")
    private val document = Uri.parse("content://documents/document/1")

    @Test fun singleAndMultipleSharesCombineStreamAndClipDataWithoutDuplicates() {
        val single = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, photo).apply { clipData = clip(photo) }
        assertEquals(listOf(photo), sharedAttachmentUris(single))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, otherPhoto, document))
            .apply { clipData = clip(photo, otherPhoto, document) }
        assertEquals(listOf(photo, otherPhoto, document), sharedAttachmentUris(multiple))
    }

    @Test fun clipOnlySharesWorkAndNonFileOrUnrelatedIntentsAreIgnored() {
        assertEquals(listOf(document), sharedAttachmentUris(Intent(Intent.ACTION_SEND).apply { clipData = clip(document) }))
        assertTrue(sharedAttachmentUris(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, photo)).isEmpty())
        assertTrue(sharedAttachmentUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/data"))).isEmpty())
        assertTrue(sharedAttachmentUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.com/image.png"))).isEmpty())
        val malformed = Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, "not a stream")
            .apply { clipData = clip(photo) }
        assertEquals(listOf(photo), sharedAttachmentUris(malformed))
    }

    @Test fun pendingFilesSurviveSavedStateRecreationAndAreConsumedExactlyOnce() {
        val handle = SavedStateHandle()
        val pending = SharedConversationAttachments(handle)
        pending.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, photo))
        pending.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, document))
        val restored = SharedConversationAttachments(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(listOf(photo, document), restored.consume())
        assertTrue(restored.consume().isEmpty())
        assertTrue(restored.pending.value.isEmpty())
    }

    @Test fun systemPhotoPickerResultRetainsAllSelectedImages() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_COMPOSER_ATTACHMENTS)
        val request = contract.createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        assertEquals("image/*", request.type)
        assertEquals(listOf(photo, otherPhoto), contract.parseResult(Activity.RESULT_OK, Intent().apply { clipData = clip(photo, otherPhoto) }))
        assertTrue(contract.parseResult(Activity.RESULT_CANCELED, null).isEmpty())
    }

    @Test fun applicationIsRegisteredForImageAndDocumentShares() {
        val manager = ApplicationProvider.getApplicationContext<Application>().packageManager
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("image/png", "application/pdf", "text/plain")) {
                val targets = manager.queryIntentActivities(Intent(action).setType(type), 0)
                assertTrue("$action $type must resolve to the main share receiver", targets.any { it.activityInfo.name == MainActivity::class.java.name })
            }
        }
    }

    @Test fun textAndUrlSharesShowAnExplicitRejectionWithoutDroppingPendingFiles() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pending = SharedConversationAttachments(SavedStateHandle())
        pending.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, document))
        for (text in listOf("selected text", "https://example.com/page")) {
            receiveSharedConversationAttachments(context, pending,
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
            assertEquals("未收到可读取的文件；文字或链接请复制后粘贴到输入框", ShadowToast.getTextOfLatestToast())
            assertEquals(listOf(document.toString()), pending.pending.value)
        }
        assertEquals(listOf(document), pending.consume())
    }

    private fun clip(vararg uris: Uri) = ClipData("files", arrayOf("image/png", "application/pdf"), ClipData.Item(uris.first())).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
}
