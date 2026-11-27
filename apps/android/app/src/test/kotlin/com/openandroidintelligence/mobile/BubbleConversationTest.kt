package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.kernel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36], application = Application::class)
class BubbleConversationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val target = ReplyNotificationTarget("profile-a", "account-a", "conv-a")

    @Test fun bubbleIntentTargetsEmbeddedActivityAndRoundTripsEncodedConversationIdentity() {
        val unusual = target.copy(conversationId = "conversation /一")
        val intent = BubbleConversationActivity.intent(context, unusual)
        assertEquals(BubbleConversationActivity::class.java.name, intent.component!!.className)
        assertEquals(unusual, BubbleConversationActivity.targetFromIntent(intent))
        assertNotEquals(MainActivity::class.java.name, intent.component!!.className)
        assertNotEquals(intent.data, BubbleConversationActivity.intent(context, target).data)
    }

    @Test fun mismatchedExtrasMalformedRoutesAndReplyMainActivityRoutesAreRejected() {
        assertNull(BubbleConversationActivity.targetFromIntent(target.intent(context)))
        assertNull(BubbleConversationActivity.targetFromIntent(BubbleConversationActivity.intent(context, target)
            .putExtra("bubble_account_id", "other-account")))
        assertNull(BubbleConversationActivity.targetFromIntent(BubbleConversationActivity.intent(context, target)
            .setData(Uri.parse("oai://bubble/conversation/profile-a/account-a/conv-a?borrow=other"))))
        assertNull(BubbleConversationActivity.targetFromIntent(Intent()))
    }

    @Test fun workspaceRejectsAnotherAccountBeforeCreatingAnyConversationAndClosesOnSessionTeardown() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val host = Host()
        val created = mutableListOf<WorkbenchController>()
        val workspace = BubbleConversationWorkspace("profile-a", "account-a") { bubbleTarget ->
            controller(scope, host).also { it.openThread(bubbleTarget.conversationId); created += it }
        }
        try {
            assertNull(workspace.open(target.copy(accountId = "foreign")))
            assertNull(workspace.open(target.copy(profileId = "foreign")))
            assertTrue(created.isEmpty())
            val bubble = workspace.open(target)!!
            assertEquals("conv-a", bubble.state.value.activeThreadId)
            workspace.close()
            assertFalse(bubble.state.value.isOnline)
            assertNull(workspace.open(target))
        } finally { workspace.close(); job.cancel() }
    }

    @Test fun bubbleSelectionDraftAndRealSendDoNotMutateMainWorkbench() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val host = Host()
        val main = controller(scope, host)
        val workspace = BubbleConversationWorkspace("profile-a", "account-a") { bubbleTarget ->
            controller(scope, host).also { it.openThread(bubbleTarget.conversationId) }
        }
        try {
            main.openThread("main-thread")
            main.editDraft("主窗口草稿")
            val bubble = workspace.open(target)!!
            assertNotSame(main, bubble)
            assertEquals("", bubble.state.value.draft)
            bubble.editDraft("从气泡回复")
            bubble.sendDraft()
            yield()
            assertEquals(listOf("conv-a" to "从气泡回复"), host.sent)
            assertEquals("main-thread", main.state.value.activeThreadId)
            assertEquals("主窗口草稿", main.state.value.draft)
            assertFalse(workspace.release(bubble))
            assertTrue("UI disposal must retain its own accepted task until a real terminal status", bubble.state.value.isOnline)
            val sent = host.messages.single().second
            host.events.emit(VerifiedConversationEvent.MessageStatus("completed", 2, ConversationId("conv-a"), "accepted-message",
                sent.clientMessageId, AgentMessageStatus.COMPLETED, 1, null))
            yield()
            assertTrue(workspace.releaseIfIdle(bubble))
            assertFalse(bubble.state.value.isOnline)
            assertTrue(main.state.value.isOnline)
        } finally { workspace.close(); main.close(); job.cancel() }
    }

    @Test fun nativeBubbleOpensTheNotificationConversationWhenItsSessionArrivesLate() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val host = Host()
        val native = target.copy(agentId = "hermes")
        val store = ViewModelStore()
        val window = BubbleConversationWindow(SavedStateHandle())
        store.put("bubble", window)
        val opened = mutableListOf<String>()
        val controllers = mutableListOf<WorkbenchController>()
        val open: (ReplyNotificationTarget) -> WorkbenchController = { selected ->
            controller(scope, host).also {
                it.openThread(selected.conversationId); opened += selected.conversationId; controllers += it
            }
        }
        try {
            // 账号恢复或 Agent 发现还没完成：这一帧拿不到 session，控制器建不出来。
            window.bind(null, native, open) {}
            assertNull(window.controller)
            assertNull(window.selectedConversationId)

            // session 到达后必须仍然进入通知指向的会话，而不是停在会话列表。
            window.bind(native, native, open) {}
            assertTrue(window.isBoundTo(native, native))
            assertEquals("conv-a", window.selectedConversationId)
            assertEquals("conv-a", window.controller?.state?.value?.activeThreadId)
            assertEquals(listOf("conv-a"), opened)

            // 用户回到列表是明确的导航选择，之后的绑定不能把他绑回聊天页。
            window.showBrowser()
            window.bind(native, native, open) {}
            assertNull(window.selectedConversationId)
        } finally {
            store.clear()
            controllers.forEach(WorkbenchController::close)
            job.cancel()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Gateway operation did not finish", condition())
    }

    private fun controller(scope: CoroutineScope, host: Host) = WorkbenchController(scope, host,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("profile-a", "https://gateway.example", "account-a", "installation") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false), mediaDispatcher = Dispatchers.Unconfined)

    /** Lets the test delay process observers while the session's Main work continues. */
    private class PausableDispatcher : CoroutineDispatcher() {
        @Volatile var paused = false
        private val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = paused
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (paused) pending.add(block) else block.run()
        }
        fun resume() {
            paused = false
            while (true) (pending.poll() ?: return).run()
        }
    }

    private class Host : ConversationRepository {
        val sent = mutableListOf<Pair<String, String>>()
        val messages = mutableListOf<Pair<String, OutgoingMessage>>()
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("Unexpected create")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("Unexpected batch")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("Conversation identity required")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            sent += conversationId to message.text
            messages += conversationId to message
            return MessageAcceptance("accepted-message", message.clientMessageId.value)
        }
        override fun observeEvents(scope: ConversationScope) = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
