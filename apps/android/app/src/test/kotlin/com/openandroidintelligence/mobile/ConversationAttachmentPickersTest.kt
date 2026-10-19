package com.openandroidintelligence.mobile

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.BaseCursor
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationAttachmentPickersTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pickerResultsKeepTheirOriginalComposerAcrossEditsAndRejectSendOrThreadRoundTrips() {
        withControllers { main, _, _ ->
            main.openThread("thread-a")
            main.editDraft("draft")
            val pickers = ConversationAttachmentPickerState().apply { controller = main }
            assertTrue(pickers.capture(ConversationAttachmentPicker.GALLERY))
            val editTarget = pickers.consume(ConversationAttachmentPicker.GALLERY)!!
            main.editDraft("edited draft")
            assertTrue("ordinary typing keeps the same composer destination", pickers.accepts(editTarget))
            main.sendDraft()
            assertFalse("a picker cannot add an attachment to the next message after Send", pickers.accepts(editTarget))

            assertTrue(pickers.capture(ConversationAttachmentPicker.DOCUMENT))
            val threadTarget = pickers.consume(ConversationAttachmentPicker.DOCUMENT)!!
            main.openThread("thread-b")
            main.openThread("thread-a")
            assertFalse("returning to the same thread does not revive an old picker destination", pickers.accepts(threadTarget))
        }
    }

    @Test fun accountChangesAndOverlappingLaunchesCannotReplaceAPendingPickerDestination() {
        withControllers { main, nextAccount, _ ->
            main.openThread("thread-a")
            nextAccount.openThread("thread-a")
            val pickers = ConversationAttachmentPickerState().apply { controller = main }
            assertTrue(pickers.capture(ConversationAttachmentPicker.DOCUMENT))
            pickers.controller = nextAccount
            assertFalse("the same launcher must not overwrite its outstanding destination", pickers.capture(ConversationAttachmentPicker.DOCUMENT))
            val original = pickers.consume(ConversationAttachmentPicker.DOCUMENT)!!
            assertSame(main, original.controller)
            assertFalse(pickers.accepts(original))
            assertNull(pickers.consume(ConversationAttachmentPicker.DOCUMENT))

            var selected = false
            pickers.prepare(original, context) {
                selected = true
                LocalAttachmentSelection("stale.pdf", "application/pdf", AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1)) })
            }
            assertFalse("a stale account result must not even resolve or stage its content", selected)
            assertEquals("对话已切换，附件未加入", ShadowToast.getTextOfLatestToast())
            pickers.isCurrentController = { false }
            assertFalse(pickers.capture(ConversationAttachmentPicker.CAMERA))
        }
    }

    @Test fun rebindingTheSameBubbleAfterConfigurationChangeRetainsItsDraftAndPendingPicker() {
        withControllers { main, _, scope ->
            val target = ReplyNotificationTarget("profile", "account", "bubble-thread")
            val store = ViewModelStore()
            val window = BubbleConversationWindow(SavedStateHandle())
            store.put("bubble", window)
            val opened = mutableListOf<WorkbenchController>()
            val released = mutableListOf<WorkbenchController>()
            val discarded = mutableListOf<String>()
            val uploads = object : AttachmentDraftCoordinator {
                override suspend fun prepare(selection: LocalAttachmentSelection) = AttachmentDraft(
                    AttachmentDraftId("rotating-photo"), selection.filename, selection.mediaType, 3, "0".repeat(64), AttachmentState.UPLOADING)
                override fun observe(draftId: String) = flowOf(AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.UPLOADING))
                override fun retry(draftId: String) = Unit
                override fun remoteAttachmentId(draftId: String): String? = null
                override suspend fun discard(draftId: String) { discarded += draftId }
                override suspend fun releaseAfterSubmit(draftId: String) = Unit
                override suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent = error("Not submitted")
                override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
            }
            val open: (ReplyNotificationTarget) -> WorkbenchController = { selected ->
                controller(scope, BubbleAttachmentDraftCoordinator(uploads)).also { it.openThread(selected.conversationId); opened += it }
            }
            val release: (WorkbenchController) -> Unit = { released += it; it.close() }
            try {
                window.bind(main, target, open, release)
                window.select("bubble-thread")
                val bubble = window.controller!!
                bubble.editDraft("气泡草稿")
                bubble.addAttachment(LocalAttachmentSelection("rotation.pdf", "application/pdf",
                    AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
                val pickers = ConversationAttachmentPickerState().apply { controller = bubble }
                assertTrue(pickers.capture(ConversationAttachmentPicker.DOCUMENT))

                window.bind(main, target, open, release)
                assertSame(bubble, window.controller)
                assertEquals("气泡草稿", window.controller!!.state.value.draft)
                assertEquals("rotating-photo", window.controller!!.state.value.attachments.single().id.value)
                assertTrue("rotation must keep the pending upload owned by the retained controller", discarded.isEmpty())
                assertTrue(pickers.accepts(pickers.consume(ConversationAttachmentPicker.DOCUMENT)!!))
                assertEquals(1, opened.size)
                assertTrue(released.isEmpty())

                window.showBrowser()
                window.select("bubble-thread")
                assertSame(bubble, window.controller)
                window.select("historical-thread")
                assertEquals("historical-thread", window.controller!!.state.value.activeThreadId)
                assertEquals(listOf(bubble), released)
                assertEquals(listOf("rotating-photo"), discarded)
                assertEquals("", main.state.value.draft)
            } finally {
                store.clear()
                opened.forEach(WorkbenchController::close)
            }
            assertEquals(2, released.size)
        }
    }

    @Test fun documentAndPhotoSelectionsPersistReadGrantsAndKeepTheirOriginalStream() {
        val resolver = context.contentResolver
        val uri = Uri.parse("content://picker/document/1")
        shadowOf(resolver).setCursor(uri, object : BaseCursor() {
            override fun moveToFirst() = true
            override fun getColumnIndex(columnName: String) = if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
            override fun getString(columnIndex: Int) = "original.pdf"
            override fun close() = Unit
        })
        val original = byteArrayOf(1, 2, 3)
        shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(original) }
        val selection = pickedAttachmentSelection(context, uri)
        assertEquals("original.pdf", selection.filename)
        assertTrue(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        assertArrayEquals(original, selection.contentSource.openStream().use { it.readBytes() })
    }

    @Test fun cameraSelectionProvidesAReopenableJpegAndBoundedPreview() {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            val selection = cameraAttachmentSelection(bitmap)
            assertEquals("image/jpeg", selection.mediaType)
            assertTrue(selection.filename.endsWith(".jpg"))
            assertTrue(selection.previewBytes!!.size <= 256 * 1024)
            assertTrue(selection.contentSource.openStream().use { it.readBytes() }.isNotEmpty())
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    private fun withControllers(test: (WorkbenchController, WorkbenchController, CoroutineScope) -> Unit) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val first = controller(scope)
        val second = controller(scope)
        try { test(first, second, scope) }
        finally { first.close(); second.close(); job.cancel() }
    }

    private fun controller(scope: CoroutineScope, attachments: AttachmentDraftCoordinator? = null) = WorkbenchController(scope, object : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("Unexpected create")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("Unexpected batch")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("Conversation required")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage) = MessageAcceptance("accepted", message.clientMessageId.value)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }, object : AgentCommandCatalogRepository {
        override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
    }, { ConversationScope("profile", "gateway", "account", "install") },
        attachmentCoordinator = attachments,
        onClosed = { (attachments as? BubbleAttachmentDraftCoordinator)?.close(scope) },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false), mediaDispatcher = Dispatchers.Unconfined)
}
