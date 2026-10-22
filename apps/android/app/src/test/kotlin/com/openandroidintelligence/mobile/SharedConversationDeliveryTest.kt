package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the Activity's real flow collector, including online-before-thread startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedConversationDeliveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingShareIsDeliveredOnceWhenTheInitialThreadRefreshCompletesAfterLogin() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val threadPage = CompletableDeferred<ConversationPage>()
        val controller = WorkbenchController(scope, object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = threadPage.await()
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("Must not create")
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("Must not send")
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("Must not send")
            override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance = error("Must not send")
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "install") },
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))
        val shares = SharedConversationAttachments(SavedStateHandle())
        val uri = Uri.parse("content://shared/photo")
        shares.receive(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri))
        val connected = mutableStateOf<WorkbenchController?>(null)
        var imports = 0
        var accepted = 0
        try {
            compose.setContent {
                ReceivePendingConversationShares(shares, connected.value, { uris ->
                    assertEquals("thread-a", controller.state.value.activeThreadId)
                    assertEquals(listOf(uri), uris)
                    accepted++
                    true
                }, { imports++ })
            }
            compose.runOnIdle { connected.value = controller }
            compose.runOnIdle {
                assertTrue(controller.state.value.isOnline)
                assertNull(controller.state.value.activeThreadId)
                assertEquals(0, accepted)
                assertEquals(listOf(uri.toString()), shares.pending.value)
                threadPage.complete(ConversationPage(listOf(ConversationSummary(ConversationId("thread-a"), "Thread A", 1)), null))
            }
            compose.runOnIdle {
                assertEquals(1, accepted)
                assertEquals(1, imports)
                assertTrue(shares.pending.value.isEmpty())
                controller.openThread("thread-b")
            }
            compose.runOnIdle { assertEquals(1, accepted) }
        } finally { controller.close(); job.cancel() }
    }
}
