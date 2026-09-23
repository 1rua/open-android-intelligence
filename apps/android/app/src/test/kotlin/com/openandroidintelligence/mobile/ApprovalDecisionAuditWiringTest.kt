package com.openandroidintelligence.mobile

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 审批卡「用户确认」→ 平台审计链的端到端接线（条目 2-9）：
 * 网关下发审批卡（SSE 事件）→ 用户在卡片上按下「允许一次」→
 * 真实 POST 决策到网关（SUBMITTED）→ 审计链必须出现对应的确认事件。
 *
 * 桥接缺失（GatewayRuntime 未把 ApprovalDecisionAudit 接进 ApprovalClient）时，
 * 审计链没有事件，本测试变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApprovalDecisionAuditWiringTest {

    private lateinit var gateway: LoopbackGatewayStub
    private lateinit var pairingGrants: PairingGrantStateHolder
    private lateinit var auditSink: InMemoryAuditSink

    private val runtimeScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        gateway = LoopbackGatewayStub()
        auditSink = InMemoryAuditSink()
        pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(auditSink),
        )
        gateway.respond(PASSWORD_PATH, PASSWORD_BODY)
        gateway.respond(CONVERSATIONS_PATH, CONVERSATIONS_BODY)
        gateway.respond(TIMELINE_PATH, TIMELINE_BODY)
        gateway.respond(NEGOTIATE_PATH, NEGOTIATE_BODY)
        gateway.respond(EVENTS_PATH, APPROVAL_EVENT_SSE, contentType = "text/event-stream")
        gateway.respond(
            DECISION_PATH,
            """{"protocol":"2.1","data":{"approval":{"approvalId":"appr_1","decision":"once"}}}""",
        )
    }

    @After
    fun tearDown() {
        gateway.closed()
    }

    @Test
    fun aSubmittedApprovalDecisionIsAuditedThroughTheHostBridge() {
        val runtime = GatewayRuntime(
            context = ApplicationProvider.getApplicationContext(),
            scope = runtimeScope,
            pairingGrants = pairingGrants,
            auditStore = AndroidAuditStore(auditSink),
            credentialStore = InMemoryCredentialStore(),
            deviceKeys = InMemoryDeviceKeySource(),
        )
        runtime.login(gateway.baseUrl, "operator", "secret".toCharArray())
        awaitCondition {
            val controller = runtime.controller.value
            controller != null && controller.state.value.activeThreadId == ACTIVE_THREAD
        }
        val controller = runtime.controller.value!!
        awaitCondition { controller.state.value.streamHealth == com.openandroidintelligence.conversation.model.StreamHealth.LIVE }

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)

        // 决策真的发到了网关自己的决策端点。
        assertTrue(
            "决策必须发到网关；实际请求 ${gateway.requests.map { "${it.method} ${it.target}" }} " +
                "timeline=${controller.state.value.timeline}",
            awaitCondition { gateway.targetsOf("POST").any { it.startsWith(DECISION_PATH) } },
        )
        // 用户确认进入平台审计链。
        awaitCondition { auditSink.events().isNotEmpty() }
        val events = auditSink.events()
        assertEquals(1, events.size)
        assertEquals("approval.user.confirmed.once.submitted", events[0].action)
        assertEquals(APPROVAL_ID, events[0].correlationId)
        assertEquals("acc_stub", events[0].accountId)
        assertEquals(pairingGrants.state.value?.pairingId, events[0].pairingId)
        assertNotNull(events[0].timestampUtc)
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

    private companion object {
        const val NEGOTIATE_PATH = "/open-android-intelligence/v2/negotiate"
        const val PASSWORD_PATH = "/open-android-intelligence/v2/sessions/password"
        const val CONVERSATIONS_PATH = "/open-android-intelligence/v2/conversations"
        const val TIMELINE_PATH = "/open-android-intelligence/v2/conversations/conv_1/messages"
        const val EVENTS_PATH = "/open-android-intelligence/v2/events"
        const val DECISION_PATH = "/open-android-intelligence/v2/approvals/appr_1/decisions"
        const val ACTIVE_THREAD = "conv_1"
        const val APPROVAL_ID = "appr_1"

        val NEGOTIATE_BODY = """
            {"protocol":"2.1","data":{"negotiationId":"neg_stub","protocol":{"major":2,"minor":1},
            "features":{"auth":["password","refresh"],"messages":"chat-v1",
            "attachments":"staged-sha256-v1","events":"sse-cursor-v1",
            "deviceRequests":"risk-queue-v1",
            "conversationUi":["agent-command-catalog-v1","agent-approval-cards-v1"]},
            "limits":{            "attachmentTtlSeconds":3600,
            "eventRetentionSeconds":86400},
            "gatewayIdentity":{"deploymentId":"dep_stub","tlsSpkiSha256":"sha256:stub"}}}
        """.trimIndent()

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

        /** 一帧审批请求事件：请求此刻开始、五分钟后过期。data 必须单行。 */
        val APPROVAL_EVENT_SSE = run {
            val now = System.currentTimeMillis()
            val data = """{"payload":{"approvalId":"appr_1","command":"重启服务","timeoutSeconds":300,""" +
                """"requestedAt":$now,"expiresAt":${now + 300_000L},""" +
                """"options":[{"choice":"once","label":"允许一次","style":"primary"},""" +
                """{"choice":"deny","label":"拒绝","style":"danger"}]}}"""
            "event: conversation.approval.requested\n" +
                "id: evt_a1\n" +
                "data: $data\n\n"
        }
    }
}
