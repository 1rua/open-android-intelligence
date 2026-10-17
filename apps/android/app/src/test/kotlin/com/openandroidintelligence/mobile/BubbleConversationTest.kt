package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.kernel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
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

    @Test fun listContainsOnlyCurrentAccountRunningSessionsAndTheTappedCompletedConversation() {
        fun session(id: String, status: NativeConversationStatus, time: Long, account: String = "account-a") =
            NativeConversationSession(target.copy(conversationId = id, accountId = account), null, "标题 $id", status, "回答", updatedAt = time)
        val sessions = listOf(
            session("conv-a", NativeConversationStatus.COMPLETED, 10),
            session("running", NativeConversationStatus.RUNNING, 20),
            session("queued", NativeConversationStatus.QUEUED, 30),
            session("old-completed", NativeConversationStatus.COMPLETED, 40),
            session("foreign", NativeConversationStatus.RUNNING, 50, "other-account"),
        )
        assertEquals(listOf("queued", "running", "conv-a"), bubbleSessionRows(sessions, target).map { it.target.conversationId })
    }

    @Test fun workspaceRejectsAnotherAccountBeforeCreatingAnyConversationAndClosesOnSessionTeardown() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val host = Host()
        val created = mutableListOf<WorkbenchController>()
        val workspace = BubbleConversationWorkspace("profile-a", "account-a") { id ->
            controller(scope, host).also { it.openThread(id); created += it }
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
        val workspace = BubbleConversationWorkspace("profile-a", "account-a") { id -> controller(scope, host).also { it.openThread(id) } }
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
            workspace.release(bubble)
            assertFalse(bubble.state.value.isOnline)
            assertTrue(main.state.value.isOnline)
        } finally { workspace.close(); main.close(); job.cancel() }
    }

    @Test fun authenticatedRuntimeBubbleUsesItsOwnControllerAndSendsThroughTheEstablishedGatewaySession() {
        val gateway = LoopbackGatewayStub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var bubble: WorkbenchController? = null
        try {
            gateway.respond("/open-android-intelligence/v2/negotiate", """{"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},"features":{"auth":["password","refresh"],"messages":"chat-v1","attachments":"staged-sha256-v1","events":"sse-cursor-v1","deviceRequests":"risk-queue-v1","conversationUi":[]},"limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},"gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}""")
            gateway.respond("/open-android-intelligence/v2/sessions/password", """{"data":{"accountId":"account-a","deviceId":"device-a","sessionId":"session-a","accessToken":"token","refreshCredential":"refresh"}}""")
            gateway.respond("/open-android-intelligence/v2/conversations", """{"protocol":"2.1","data":{"conversations":[{"conversationId":"main-thread","title":"Main","lastMessageAt":"2026-10-17T02:00:00Z"}]}}""")
            val emptyTimeline = """{"protocol":"2.1","data":{"messages":[],"nextCursor":null,"snapshotRevision":1}}"""
            gateway.respond("/open-android-intelligence/v2/conversations/main-thread/messages", emptyTimeline)
            gateway.respond("/open-android-intelligence/v2/conversations/conv-a/messages", emptyTimeline)
            gateway.respond("/open-android-intelligence/v2/events", "", contentType = "text/event-stream")
            val runtime = GatewayRuntime(context, scope,
                PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink())),
                credentialStore = InMemoryCredentialStore(), deviceKeys = InMemoryDeviceKeySource(), localDocumentKeyProvider = TestDocumentKeys)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            await { runtime.phase.value is ConnectionPhase.Connected && runtime.controller.value?.state?.value?.activeThreadId == "main-thread" }
            val main = runtime.controller.value!!
            main.editDraft("主窗口草稿")
            val profileId = Base64.getUrlEncoder().withoutPadding().encodeToString("${gateway.baseUrl}|operator".toByteArray())
            val current = target.copy(profileId = profileId)
            assertNull(runtime.createBubbleConversation(current.copy(accountId = "other-account")))
            bubble = runtime.createBubbleConversation(current)!!
            val chat = requireNotNull(bubble)
            assertNotSame(main, chat)
            await { chat.state.value.historyHydrated && chat.state.value.timeline !is com.openandroidintelligence.conversation.state.Loadable.Loading }
            gateway.respond("/open-android-intelligence/v2/conversations/conv-a/messages", """{"protocol":"2.1","data":{"message":{"status":"accepted","conversationId":"conv-a","messageId":"bubble-reply"}}}""")
            chat.editDraft("气泡中的真实回复")
            chat.sendDraft()
            await { gateway.requests.any { it.method == "POST" && it.target == "/open-android-intelligence/v2/conversations/conv-a/messages" } }
            assertEquals("main-thread", main.state.value.activeThreadId)
            assertEquals("主窗口草稿", main.state.value.draft)
            val request = gateway.requests.single { it.method == "POST" && it.target.endsWith("/conv-a/messages") }
            // The loopback recorder keeps HTTP bytes as Latin-1, including the UTF-8 body.
            val payload = com.openandroidintelligence.gateway.schema.JsonFields.obj(
                com.openandroidintelligence.gateway.schema.Json.parse(request.body.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)),
            )
            assertEquals("气泡中的真实回复", com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "text"))
            runtime.releaseBubbleConversation(chat)
        } finally { bubble?.close(); scope.cancel(); gateway.closed() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Gateway operation did not finish", condition())
    }

    private fun controller(scope: CoroutineScope, host: Host) = WorkbenchController(scope, host,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { ConversationScope("profile-a", "https://gateway.example", "account-a", "installation") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false), mediaDispatcher = Dispatchers.Unconfined)

    private class Host : ConversationRepository {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("Unexpected create")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("Unexpected batch")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("Conversation identity required")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            sent += conversationId to message.text
            return MessageAcceptance("accepted-message", message.clientMessageId.value)
        }
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
