package com.openandroidintelligence.mobile

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * generation-cancel-v1 协商门禁的宿主接线（任务 0）。
 *
 * 全链路验证「工作台停止生成」：登录 → 协商 → 工作台自动打开线程 →
 * SSE 事件发布网关下发的 generationId → stopGeneration() →
 * 必须真的向网关的 cancel 端点发出请求（网关同意该能力位时），
 * 或不发请求并如实显示不支持（未同意时）。
 *
 * 请求以回环 HTTP 桩按契约地址原样记录，验证的是「真的发到了网关」，
 * 而不是某个内部方法被调用过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenerationCancelGateTest {

    private lateinit var gateway: LoopbackGatewayStub
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var pairingGrants: PairingGrantStateHolder

    private val runtimeScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        gateway = LoopbackGatewayStub()
        credentialStore = InMemoryCredentialStore()
        pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(InMemoryAuditSink()),
        )
        gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
    }

    @After
    fun tearDown() {
        gateway.closed()
    }

    @Test
    fun stopGenerationReachesTheGatewayCancelEndpointWhenTheCapabilityWasNegotiated() {
        gateway.respond(NEGOTIATE_PATH, negotiateBody(withCancel = true))
        gateway.respond(CONVERSATIONS_PATH, CONVERSATIONS_BODY)
        gateway.respond(TIMELINE_PATH, TIMELINE_BODY)
        gateway.respond(EVENTS_PATH, GENERATION_EVENT_SSE, contentType = "text/event-stream")
        gateway.respond(CANCEL_PATH, """{"protocol":"2.1","data":{"outcome":"CANCELLED"}}""")

        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        val controller = awaitWorkbench(runtime)
        // 事件帧发布 generationId 与流 LIVE 状态在同一同步块内，LIVE 即保守就绪信号。
        awaitCondition { controller.state.value.streamHealth == StreamHealth.LIVE }

        controller.stopGeneration()

        awaitCondition { controller.state.value.generation == GenerationState.CANCELLED }
        assertTrue(
            "网关同意 generation-cancel-v1 时，停止生成必须真的发取消请求；" +
                "实际请求 ${gateway.requests.map { "${it.method} ${it.target}" }} " +
                "state=${controller.state.value}",
            gateway.targetsOf("POST").any { it.startsWith(CANCEL_PATH) },
        )
        val cancelRequest = gateway.requests.last { it.target.startsWith(CANCEL_PATH) }
        assertTrue("取消请求必须携带 requestId", cancelRequest.body.contains("requestId"))
    }

    @Test
    fun stopGenerationWithoutTheNegotiatedCapabilityNeverTouchesTheCancelEndpoint() {
        gateway.respond(NEGOTIATE_PATH, negotiateBody(withCancel = false))
        gateway.respond(CONVERSATIONS_PATH, CONVERSATIONS_BODY)
        gateway.respond(TIMELINE_PATH, TIMELINE_BODY)
        gateway.respond(EVENTS_PATH, GENERATION_EVENT_SSE, contentType = "text/event-stream")

        val runtime = runtime()
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        val controller = awaitWorkbench(runtime)
        awaitCondition { controller.state.value.streamHealth == StreamHealth.LIVE }

        controller.stopGeneration()

        awaitCondition { controller.state.value.generation == GenerationState.UNSUPPORTED }
        assertFalse(
            "网关未同意该能力位时，取消请求绝不能发出（fail-closed）",
            gateway.targetsOf("POST").any { it.startsWith(CANCEL_PATH) },
        )
    }

    // ------------------------------------------------------------------
    // 测试设施
    // ------------------------------------------------------------------

    private fun runtime(): GatewayRuntime = GatewayRuntime(
        context = ApplicationProvider.getApplicationContext(),
        scope = runtimeScope,
        pairingGrants = pairingGrants,
        credentialStore = credentialStore,
        deviceKeys = InMemoryDeviceKeySource(),
    )

    /**
     * 等待登录完成且工作台自动打开线程（refreshThreads 的首线程自动打开），
     * 期间持续泵 Robolectric 主线程 Looper，让工作台协程真实运行。
     */
    private fun awaitWorkbench(runtime: GatewayRuntime): com.openandroidintelligence.conversation.state.WorkbenchController {
        awaitCondition {
            val controller = runtime.controller.value
            controller != null && controller.state.value.activeThreadId == ACTIVE_THREAD
        }
        val controller = runtime.controller.value
        assertNotNull(
            "工作台没有在超时内建立：phase=${runtime.phase.value} " +
                "requests=${gateway.requests.map { "${it.method} ${it.target}" }}",
            controller,
        )
        return controller!!
    }

    private fun pumpMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(timeoutMillis: Long = 15_000L, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            pumpMain()
            if (block()) return true
            Thread.sleep(20)
        }
        pumpMain()
        return block()
    }

    private fun negotiateBody(withCancel: Boolean): String {
        val conversationUi = buildList {
            add("agent-command-catalog-v1")
            add("agent-command-new-v1")
            add("agent-approval-cards-v1")
            add("message-batches-v1")
            if (withCancel) add("generation-cancel-v1")
        }.joinToString(",", "[", "]") { "\"$it\"" }
        return """
            {"data":{"negotiationId":"neg_stub","protocol":{"major":2,"minor":1},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1",
            "conversationUi":$conversationUi},
            "limits":{            "attachmentTtlSeconds":3600,
            "eventRetentionSeconds":86400},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:stub"}}}
        """.trimIndent()
    }

    private companion object {
        const val NEGOTIATE_PATH = "/open-android-intelligence/v2/negotiate"
        const val PASSWORD_PATH = "/open-android-intelligence/v2/sessions/password"
        const val CONVERSATIONS_PATH = "/open-android-intelligence/v2/conversations"
        const val TIMELINE_PATH = "/open-android-intelligence/v2/conversations/conv_1/messages"
        const val EVENTS_PATH = "/open-android-intelligence/v2/events"
        const val CANCEL_PATH = "/open-android-intelligence/v2/conversations/conv_1/generations/gen_wire_1/cancel"
        const val ACTIVE_THREAD = "conv_1"

        val PASSWORD_BODY = """
            {"data":{"accountId":"acc_stub","deviceId":"dev_stub","sessionId":"sess_1",
            "accessToken":"token_1","refreshCredential":"refresh_1","pairingSummary":"stub pairing"}}
        """.trimIndent()

        val CONVERSATIONS_BODY = """
            {"protocol":"2.1","data":{"conversations":[{"conversationId":"conv_1","title":"排查",
            "lastMessageAt":"2026-09-01T00:00:00.000Z"}]}}
        """.trimIndent()

        val TIMELINE_BODY =
            """{"protocol":"2.1","data":{"messages":[],"nextCursor":null,"snapshotRevision":1}}"""

        /**
         * 一帧网关事件：未知事件名也会发布 payload 里的 generationId（取消只需要
         * 网关签发的 id），这是 GatewayEventDecoder 的真实规则。
         */
        val GENERATION_EVENT_SSE = """
            event: generation.started
            id: evt_g1
            data: {"payload":{"generationId":"gen_wire_1"}}

        """.trimIndent() + "\n"
    }
}
