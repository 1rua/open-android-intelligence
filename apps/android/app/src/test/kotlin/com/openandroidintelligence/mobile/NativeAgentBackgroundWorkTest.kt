package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.negotiation.NATIVE_AGENT_SESSIONS_HEADER
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext

/** Native streams own the same background lease as workbench and bubble sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], application = Application::class)
class NativeAgentBackgroundWorkTest {
    @Test fun synchronousNativeSendKeepsItsLeaseWhenBackgroundingPrecedesTheObserver() {
        Fixture().use { fixture ->
            // Both native Core backends must work without legacy conversation events.
            for (agentId in listOf("dsh", "codex")) {
                fixture.runtime.onAppForegrounded()
                val native = fixture.selectSession(agentId)
                val release = fixture.holdSend(agentId)
                val starts = fixture.context.starts.value.size
                val stops = fixture.context.stops.value.size
                fixture.dispatcher.paused = true
                native.editDraft("Run native $agentId turn")
                native.send()
                assertTrue(native.state.value.sending)
                assertEquals(starts, fixture.context.starts.value.size)

                fixture.runtime.onAppBackgrounded()

                assertEquals(starts + 1, fixture.context.starts.value.size)
                assertTrue(fixture.recovery.hasPendingWork)
                fixture.dispatcher.resume()
                assertTrue(native.state.value.sending)
                assertTrue(fixture.runtime.nativeConversationNotifications.sessions.value.isEmpty())
                assertFalse(requireNotNull(fixture.runtime.controller.value).hasActiveWork.value)

                release.countDown()
                awaitState(native.state) { it.streamCompleted && !it.busy }
                awaitState(fixture.context.stops) { it.size == stops + 1 }
                assertFalse(fixture.recovery.hasPendingWork)
                assertFalse(fixture.recovery.hasRecoveryLease)
                assertEquals(AgentSessionForegroundService::class.java.name,
                    fixture.context.starts.value.last().component!!.className)
            }
        }
    }

    @Test fun nativeSendingObserverStartsAndReleasesTheLeaseWithoutALifecycleCallback() {
        Fixture().use { fixture ->
            val native = fixture.selectSession("dsh")
            val release = fixture.holdSend("dsh")
            native.editDraft("Native work while visible")
            native.send()

            awaitState(fixture.context.starts) { it.size == 1 }
            assertTrue(native.state.value.sending)
            assertTrue(fixture.recovery.hasPendingWork)
            fixture.runtime.onAppBackgrounded()
            assertEquals(1, fixture.context.starts.value.size)

            release.countDown()
            awaitState(fixture.context.stops) { it.size == 1 }
            assertFalse(native.state.value.sending)
            assertFalse(fixture.recovery.hasPendingWork)
        }
    }

    @Test fun accountTeardownReleasesTheNativeLeaseAndRetiresItsObserver() {
        Fixture().use { fixture ->
            val native = fixture.selectSession("codex")
            val release = fixture.holdSend("codex")
            native.editDraft("In flight at account teardown")
            native.send()
            awaitState(fixture.context.starts) { it.size == 1 }
            assertTrue(native.state.value.sending)
            fixture.runtime.onAppBackgrounded()

            fixture.runtime.chooseAnotherAccount()

            awaitState(fixture.runtime.phase) { it is ConnectionPhase.Disconnected }
            assertNull(fixture.runtime.nativeAgents.value)
            assertFalse(native.state.value.sending)
            assertFalse(fixture.recovery.hasPendingWork)
            assertFalse(fixture.recovery.hasRecoveryLease)
            assertEquals(1, fixture.context.stops.value.size)
            release.countDown()
            fixture.runtime.onAppForegrounded()
            assertEquals(1, fixture.context.starts.value.size)
            assertFalse(fixture.recovery.hasPendingWork)
        }
    }

    @Test fun nativeSendingStillRequiresTheUserNotificationSettingAndPermission() {
        Fixture().use { fixture ->
            val native = fixture.selectSession("dsh")
            val release = fixture.holdSend("dsh")
            fixture.runtime.replyNotifications.setEnabled(false)
            native.editDraft("Notification setting disabled")
            native.send()
            assertTrue(native.state.value.sending)
            fixture.runtime.onAppBackgrounded()
            assertTrue(fixture.context.starts.value.isEmpty())
            assertFalse(fixture.recovery.hasPendingWork)

            fixture.runtime.onAppForegrounded()
            shadowOf(fixture.context.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            fixture.runtime.replyNotifications.refreshPermission()
            fixture.runtime.replyNotifications.setEnabled(true)
            assertFalse(fixture.runtime.replyNotifications.allowed.value)
            fixture.runtime.onAppBackgrounded()
            assertTrue(fixture.context.starts.value.isEmpty())
            assertFalse(fixture.recovery.hasPendingWork)
            release.countDown()
            awaitState(native.state) { it.streamCompleted && !it.busy }
        }
    }

    private class Fixture : AutoCloseable {
        val context = RecordingContext()
        val dispatcher = PausableDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val gateway = LoopbackGatewayStub()
        private val releases = mutableListOf<CountDownLatch>()
        val recovery = AgentSessionRecoveryState(context)
        val runtime: GatewayRuntime

        init {
            shadowOf(context.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            context.getSharedPreferences("agent-session-recovery", Context.MODE_PRIVATE).edit().clear().commit()
            gateway.respond("/open-android-intelligence/v2/negotiate", NEGOTIATION,
                headers = mapOf(NATIVE_AGENT_SESSIONS_HEADER to "1"))
            gateway.respond("/open-android-intelligence/v2/sessions/password", SESSION)
            gateway.respond("/open-android-intelligence/v2/conversations", """{"data":{"conversations":[]}}""")
            gateway.respond("/open-android-intelligence/v2/events", "", contentType = "text/event-stream")
            gateway.respond(AgentSessionClient.ROOT, AGENTS)
            for (agentId in listOf("dsh", "codex")) {
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions", """{"sessions":[{"id":"native-session"}]}""")
                gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/history", """{"messages":[]}""")
            }
            runtime = GatewayRuntime(context, scope,
                PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink())),
                credentialStore = InMemoryCredentialStore(), deviceKeys = InMemoryDeviceKeySource(),
                localDocumentKeyProvider = TestDocumentKeys)
            runtime.replyNotifications.setEnabled(true)
            runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
            awaitState(runtime.phase) { it is ConnectionPhase.Connected }
            runtime.onAppForegrounded()
            val native = requireNotNull(runtime.nativeAgents.value)
            awaitState(native.state) { it.sessions.isNotEmpty() && !it.loadingSessions }
            assertTrue(context.starts.value.isEmpty())
        }

        fun selectSession(agentId: String): NativeAgentsController {
            val native = requireNotNull(runtime.nativeAgents.value)
            native.selectAgent(agentId)
            awaitState(native.state) { it.selectedAgentId == agentId && it.sessions.isNotEmpty() && !it.loadingSessions && !it.busy }
            native.selectSession("native-session")
            awaitState(native.state) { it.selectedSessionId == "native-session" && !it.loadingHistory }
            return native
        }

        fun holdSend(agentId: String): CountDownLatch {
            val release = CountDownLatch(1).also { releases += it }
            gateway.respond("${AgentSessionClient.ROOT}/$agentId/sessions/native-session/messages", contentType = "text/event-stream") {
                check(release.await(10, TimeUnit.SECONDS)) { "Native test stream was never released" }
                200 to "event: message\ndata: {\"type\":\"done\"}\n\n"
            }
            return release
        }

        override fun close() {
            releases.forEach { it.countDown() }
            dispatcher.resume()
            scope.cancel()
            gateway.closed()
        }
    }

    private class RecordingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val application get() = baseContext as Application
        val starts = MutableStateFlow<List<Intent>>(emptyList())
        val stops = MutableStateFlow<List<Intent>>(emptyList())
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName {
            starts.value += Intent(service)
            return requireNotNull(service.component)
        }
        override fun stopService(service: Intent): Boolean {
            stops.value += Intent(service)
            return true
        }
    }

    private class PausableDispatcher : CoroutineDispatcher() {
        @Volatile var paused = false
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = paused
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) pending.add(block) else block.run()
        }
        fun resume() {
            paused = false
            while (true) (pending.poll() ?: return).run()
        }
    }

    private companion object {
        /** Wait for real state emissions while driving Android's owner looper; no sleep-based assertions. */
        fun <T> awaitState(state: StateFlow<T>, predicate: (T) -> Boolean): T {
            val result = CompletableFuture<T>()
            val waiter = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                result.complete(state.first(predicate))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            try {
                while (true) {
                    shadowOf(Looper.getMainLooper()).idle()
                    try { return result.get(10, TimeUnit.MILLISECONDS) }
                    catch (timeout: TimeoutException) {
                        if (System.nanoTime() >= deadline) throw AssertionError("State did not reach expected value: ${state.value}", timeout)
                    }
                }
            } finally { waiter.cancel() }
        }

        val NEGOTIATION = """{"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},"features":{"auth":["password","refresh"],"messages":"chat-v1","attachments":"staged-sha256-v1","events":"sse-cursor-v1","deviceRequests":"risk-queue-v1","conversationUi":[]},"limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},"gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}"""
        val SESSION = """{"data":{"accountId":"account-native","deviceId":"device-native","sessionId":"session-native","accessToken":"token","refreshCredential":"refresh"}}"""
        val AGENTS = """{"agents":[{"id":"dsh","name":"DSH","kind":"dsh","online":true,"sessionScope":"deployment","capabilities":{"list":true,"history":true,"send":true,"stream":true}},{"id":"codex","name":"Codex","kind":"codex","online":true,"sessionScope":"deployment","capabilities":{"list":true,"history":true,"send":true,"stream":true}}]}"""
    }
}
