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
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test fun authenticatedRuntimeBubbleUsesItsOwnControllerAndSendsThroughTheEstablishedGatewaySession() {
        val gateway = LoopbackGatewayStub()
        val runtimeDispatcher = PausableDispatcher()
        val scope = CoroutineScope(SupervisorJob() + runtimeDispatcher)
        var bubble: WorkbenchController? = null
        var releaseAcceptance: java.util.concurrent.CountDownLatch? = null
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
            shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            runtime.replyNotifications.setEnabled(true)
            runtime.onAppForegrounded()
            val profileId = Base64.getUrlEncoder().withoutPadding().encodeToString("${gateway.baseUrl}|operator".toByteArray())
            val current = target.copy(profileId = profileId)
            assertNull(runtime.createBubbleConversation(current.copy(accountId = "other-account")))
            bubble = runtime.createBubbleConversation(current)!!
            val chat = requireNotNull(bubble)
            assertNotSame(main, chat)
            await { chat.state.value.historyHydrated && chat.state.value.timeline !is com.openandroidintelligence.conversation.state.Loadable.Loading }
            releaseAcceptance = gateway.holdResponse("/open-android-intelligence/v2/conversations/conv-a/messages", """{"protocol":"2.1","data":{"message":{"status":"accepted","conversationId":"conv-a","messageId":"bubble-reply","generationId":"bubble-generation"}}}""")
            // Reproduce STOP without UI disposal while the process-owned
            // observers have not yet consumed the synchronous send fact.
            runtimeDispatcher.paused = true
            chat.editDraft("气泡中的真实回复")
            chat.sendDraft()
            assertTrue(chat.hasActiveWork.value)
            runtime.onAppBackgrounded()
            assertEquals(AgentSessionForegroundService::class.java.name, requireNotNull(shadowOf(context).nextStartedService).component!!.className)
            runtimeDispatcher.resume()
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
            assertTrue("No native frame or HTTP receipt has yet taken ownership", runtime.nativeConversationNotifications.sessions.value.isEmpty())
            assertTrue(chat.hasActiveWork.value)
            assertTrue("Disposing the bubble UI must not cancel its POST or event stream", chat.state.value.isOnline)

            val clientId = requireNotNull(com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "clientMessageId"))
            val terminal = """{"accountId":"account-a","occurredAt":"${java.time.Instant.now()}","payload":{"conversationId":"conv-a","generationId":"bubble-generation","messageId":"bubble-reply","clientMessageId":"$clientId","status":"completed","revision":1,"errorCode":null}}"""
            gateway.respond("/open-android-intelligence/v2/events", "id: bubble-terminal\nevent: conversation.message.status\ndata: $terminal\n\n", contentType = "text/event-stream")
            requireNotNull(releaseAcceptance).countDown()
            await { !chat.hasActiveWork.value && !chat.state.value.isOnline }
            await { shadowOf(context).nextStoppedService?.component?.className == AgentSessionForegroundService::class.java.name }

            // Rotating credentials keeps the authenticated workspace and its
            // in-flight bubble task until the Gateway supplies its terminal event.
            gateway.respond("/open-android-intelligence/v2/conversations/conv-a/messages", emptyTimeline)
            runtime.onAppForegrounded()
            val retired = requireNotNull(runtime.createBubbleConversation(current))
            bubble = retired
            await { retired.state.value.timeline !is com.openandroidintelligence.conversation.state.Loadable.Loading }
            gateway.respond("/open-android-intelligence/v2/conversations/conv-a/messages", """{"protocol":"2.1","data":{"message":{"status":"accepted","conversationId":"conv-a","messageId":"second-bubble-reply","generationId":"second-bubble-generation"}}}""")
            retired.editDraft("刷新凭据时仍运行的任务"); retired.sendDraft()
            await { gateway.requests.count { it.method == "POST" && it.target.endsWith("/conv-a/messages") } == 2 }
            runtime.releaseBubbleConversation(retired)
            assertTrue(retired.hasActiveWork.value)
            gateway.respond("/open-android-intelligence/v2/sessions/refresh", """{"data":{"accountId":"account-a","deviceId":"device-a","sessionId":"refreshed-session","accessToken":"refreshed-token","refreshCredential":"refreshed-credential"}}""")
            runtime.refreshSession()
            await { !runtime.isRefreshingSession.value && gateway.requests.any { it.target.endsWith("/sessions/refresh") } }
            assertSame(main, runtime.controller.value)
            assertTrue(retired.state.value.isOnline)
            assertTrue(retired.hasActiveWork.value)
            await { gateway.requests.any { request -> request.target.substringBefore('?').endsWith("/events") &&
                request.headers.any { it.equals("Authorization: Bearer refreshed-token", true) } } }
            val secondRequest = gateway.requests.last { it.method == "POST" && it.target.endsWith("/conv-a/messages") }
            val secondPayload = com.openandroidintelligence.gateway.schema.JsonFields.obj(
                com.openandroidintelligence.gateway.schema.Json.parse(secondRequest.body.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)))
            val secondClientId = requireNotNull(com.openandroidintelligence.gateway.schema.JsonFields.string(secondPayload, "clientMessageId"))
            val secondTerminal = """{"accountId":"account-a","occurredAt":"${java.time.Instant.now()}","payload":{"conversationId":"conv-a","generationId":"second-bubble-generation","messageId":"second-bubble-reply","clientMessageId":"$secondClientId","status":"completed","revision":1,"errorCode":null}}"""
            gateway.respond("/open-android-intelligence/v2/events", "id: second-bubble-terminal\nevent: conversation.message.status\ndata: $secondTerminal\n\n", contentType = "text/event-stream")
            await { !retired.hasActiveWork.value && !retired.state.value.isOnline }
            await { shadowOf(context).nextStoppedService?.component?.className == AgentSessionForegroundService::class.java.name }
            assertFalse(retired.hasActiveWork.value)
        } finally { releaseAcceptance?.countDown(); runtimeDispatcher.resume(); bubble?.close(); scope.cancel(); gateway.closed() }
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
