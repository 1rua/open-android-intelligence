package com.openandroidintelligence.mobile

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.auth.Ed25519DeviceKeyStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository
import com.openandroidintelligence.mobile.conversations.NativeConversationMirror
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 平台连接保持健康，只让原生长轮询半开，复用真实 Hermes 桥和加密镜像。 */
@RunWith(AndroidJUnit4::class)
class NativeConnectionRecoveryInstrumentedTest {
    @Test fun nativeOnlyHalfOpenReadRecoversAndAcknowledgesWithoutResubmittingInput(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val core = isolated(requireNotNull(args.getString("oaiCoreUrl")))
        val controller = isolated(requireNotNull(args.getString("oaiControlUrl")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installation = "native_e2e_" + UUID.randomUUID().toString().replace("-", "")
        val keys = Ed25519DeviceKeyStore(File(context.filesDir, "connection-e2e/$installation"))
        val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("fixture", "fixture", "fixture", core)),
            installation, "0.0.0-e2e", Build.VERSION.SDK_INT)
        val negotiation = "neg_" + UUID.randomUUID().toString().replace("-", "")
        auth.negotiate(negotiation)
        val credentials = auth.loginWithPassword(negotiation, "connection-e2e", "connection-fixture-only".toCharArray(),
            "原生连接验收", keys.publicKeyBase64Url(installation))
        val profile = GatewayProfile(credentials.accountId, credentials.deviceId, credentials.sessionId, core,
            accessToken = credentials.accessToken)
        val status = EventStreamStatusSink()
        val http = GatewayHttpClient(profile, GatewayTransport(profile), { keys.sign(installation, it) },
            InMemoryEventCursorStore(), statusSink = status)
        http.setAppForeground(true)
        val client = AgentSessionClient(http)
        val agent = client.agents().single { it.id == "hermes" }
        assertTrue("真实Hermes桥必须声明可用eventSync", agent.online && agent.capabilities.eventSync)
        val conversation = client.sessions(agent).single().id
        val scope = ConversationScope(installation, core, credentials.accountId, installation)
        val mirror = NativeConversationMirror(context, scope, { agent.id }, observerId = installation)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = CoreAgentConversationRepository(client, { agent }, { conversation }, workers,
            storeForAgent = { mirror }, isForeground = { args.getString("oaiSkipNativeForeground") != "true" })
        val platform = workers.launch { http.events().collect() }
        try {
            withTimeout(10_000) { status.status.first { it == EventStreamStatus.LIVE } }
            repository.activeConversationChanged()
            waitFor { mirror.eventCursor(conversation) != null && mirror.pendingAck(conversation) == null }
            control(controller, "prepare")
            waitFor { mirror.localTimeline(conversation)?.messages?.map { it.id } == listOf("native_before") && mirror.pendingAck(conversation) == null }
            waitFor { JsonFields.int(control(controller, "status", "GET"), "liveEventConnections") == 1 }
            val platformOpens = JsonFields.int(control(controller, "status", "GET"), "platformOpens")
            val started = SystemClock.elapsedRealtime()
            val frozen = control(controller, "half_open")
            assertEquals(1, JsonFields.int(frozen, "frozenOpen"))
            assertEquals(0, JsonFields.int(frozen, "closedByFixture"))
            // 不触发connectionRestored；持续前台的原生请求自身预算必须独立恢复。
            waitFor(timeout = 10_000) {
                mirror.localTimeline(conversation)?.messages?.map { it.id } == listOf("native_before", "native_during") &&
                    mirror.pendingAck(conversation) == null
            }
            val recoveryMillis = SystemClock.elapsedRealtime() - started
            assertTrue("原生半开恢复与ACK目标为十秒", recoveryMillis <= 10_000)
            control(controller, "finish")
            waitFor { mirror.localTimeline(conversation)?.messages?.map { it.id } == listOf("native_before", "native_during", "native_after") && mirror.pendingAck(conversation) == null }
            val completed = control(controller, "status", "GET")
            assertEquals("平台事件通道不能被原生半开影响", platformOpens, JsonFields.int(completed, "platformOpens"))
            assertEquals(EventStreamStatus.LIVE, status.status.value)
            assertEquals("恢复禁止重新POST输入", 0, JsonFields.int(completed, "promptPosts"))
            assertTrue(requireNotNull(JsonFields.int(completed, "nativeAcks")) >= 3)
            Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.result",
                "case" to "native_half_open", "result" to "passed", "recoveryMillis" to recoveryMillis,
                "orderedMessages" to 3, "duplicateMessages" to 0, "promptPosts" to 0,
                "platformSubscriptions" to platformOpens, "pendingAck" to false))))
        } finally {
            repository.close()
            platform.cancelAndJoin()
            workers.cancel()
        }
    }

    private suspend fun waitFor(timeout: Long = 10_000, condition: suspend () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(20) }
    }
    private fun isolated(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in setOf("", "/"))
        return value.trimEnd('/')
    }
    private suspend fun control(base: String, operation: String, method: String = "POST"): JsonValue.JObject =
        isolatedConnectionFixtureControl(base, operation, method)
}
