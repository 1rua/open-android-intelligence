package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.AgentEndpoint
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.EndpointKey
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.gateway.remote.rpc.RemoteDeliveryOutcome
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteApprovalRepositoryTest {
    private class Channel : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "rpc-client", "connection", 1)
        override val capabilities = setOf("harness.remote.v3", "codex.appserver.v1", "agent.acp.v1", "agent.acp.hermes.v1")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        val calls = mutableListOf<Pair<String, JsonValue.JObject>>()
        var receipt: suspend () -> JsonValue = { JsonValue.JNull }
        val decisions get() = calls.filter { (method, params) ->
            params.text("endpoint")?.startsWith("\$events/") == true || method.endsWith(".respond")
        }

        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            calls += method to params
            return when (params.text("endpoint")) {
                "session/page" -> obj("ok" to true, "value" to obj("cursor" to 0, "records" to emptyList<JsonValue>()))
                "\$events/result", "\$events/answer" -> receipt()
                null -> if (method.endsWith(".respond")) receipt() else JsonValue.JNull
                else -> JsonValue.JNull
            }
        }

        fun streamId(prefix: String = "harness.remote", endpoint: String? = "\$events", index: Int = 0) =
            calls.filter { (method, params) -> method == "$prefix.stream.open" && params.text("endpoint") == endpoint }
                .elementAt(index).second.requiredText("streamId")

        suspend fun frame(streamId: String, value: JsonValue, prefix: String = "harness.remote") {
            val field = if (prefix == "harness.remote") "value" else "frame"
            events.emit(RemoteRpcEvent("$prefix.frame", obj("streamId" to streamId, field to value)))
        }

        suspend fun ready(streamId: String, clientId: String = "event-client") = frame(streamId, obj("type" to "ready", "clientId" to clientId))

        suspend fun asked(streamId: String, id: String = "approval:一/1", sessionId: String = "session/一", callId: String? = null) = frame(streamId,
            obj("type" to "waterfall", "event" to "approval/request", "agentId" to sessionId, "eventId" to id,
                "request" to obj("toolName" to "测试工具", "callId" to callId)))

        suspend fun audit(type: String, seq: Int, vararg data: Pair<String, Any?>) = frame(streamId(endpoint = "session/follow"),
            obj("type" to "event", "event" to obj("type" to "approval/$type", "seq" to seq, "time" to (1000 + seq), "data" to obj(*data))))

        override suspend fun close() = Unit
    }

    private val first = RemoteLocalIds.encode("session/一")
    private val second = RemoteLocalIds.encode("session/二")

    private fun TestScope.repository(
        carrier: RemoteSessionCarrier,
        workspace: String = "workspace",
        active: () -> String = { first },
        onApproval: (VerifiedConversationEvent) -> Unit,
    ): RemoteConversationRepository {
        val scope = carrier.channel.scope
        val key = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(scope.origin), scope.accountId,
            scope.hostDeviceId, BackendId(carrier.backend), workspace)
        return RemoteConversationRepository(carrier, RemoteWorkspace(AgentEndpoint(key, "工作区"), "/$workspace"),
            backgroundScope, active, onEvent = { _, event -> onApproval(event) })
    }

    @Test fun `会话跟随收到审批后用来源事件流送答且空回执等待权威日志`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val streamId = channel.streamId()
            channel.ready(streamId)
            channel.audit("asked", 1, "id" to "audit-id", "callId" to "tool-call-id")
            channel.asked(streamId, callId = "tool-call-id")
            runCurrent()

            val request = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request
            val result = repository.submitApprovalDecision(request.approvalId, ApprovalChoice.ONCE)

            assertEquals(listOf("harness.remote.call" to obj("endpoint" to "\$events/result", "payload" to obj("args" to obj(
                "clientId" to "event-client", "eventId" to "approval:一/1", "outcome" to obj("kind" to "result", "value" to "allowed-once")
            )))), channel.decisions)
            assertEquals(ApprovalSubmissionOutcome.SUBMITTED, result.outcome)
            assertNull(result.settledOutcome)
            assertNull(result.decidedAt)
            assertTrue(events.none { it is VerifiedConversationEvent.ApprovalResolved })

            channel.audit("decided", 2, "id" to "audit-id", "outcome" to "allowed-once")
            runCurrent()
            val terminal = events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().single()
            assertEquals(request.approvalId, terminal.approvalId)
            assertEquals(ApprovalOutcome.ALLOWED_ONCE, terminal.outcome)
            assertEquals(1002L, terminal.decidedAt)
        } finally {
            repository.close()
        }
    }

    @Test fun `同名审批按订阅和会话隔离且另一工作区不能使用旧卡片`() = runTest {
        val channel = Channel()
        var active = first
        val events = mutableListOf<VerifiedConversationEvent>()
        val carrier = HarnessSessionCarrier(channel)
        val repository = repository(carrier, active = { active }) { events.add(it) }
        val otherWorkspace = repository(carrier, workspace = "other") {}
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val firstStream = channel.streamId()
            channel.asked(firstStream)
            channel.ready(channel.streamId(endpoint = "session/follow"), "wrong-ready")
            channel.asked(channel.streamId(endpoint = "session/follow"))
            channel.ready(firstStream, "original-client")
            channel.asked(firstStream, sessionId = "session/二")
            channel.asked(firstStream)
            runCurrent()
            val firstRequest = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request

            repository.timeline(second, PageRequest())
            runCurrent()
            val secondStream = channel.streamId(index = 1)
            channel.ready(secondStream, "other-client")
            channel.asked(secondStream, sessionId = "session/二")
            channel.frame(secondStream, obj("type" to "cancel", "eventId" to "approval:一/1", "agentId" to "session/一"))
            runCurrent()
            val secondRequest = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().last().request
            assertNotEquals(firstRequest.approvalId, secondRequest.approvalId)
            assertTrue(events.none { it is VerifiedConversationEvent.ApprovalResolved })

            assertEquals(ApprovalSubmissionOutcome.NOT_FOUND, repository.submitApprovalDecision(secondRequest.approvalId, ApprovalChoice.ONCE).outcome)
            assertEquals(ApprovalSubmissionOutcome.NOT_FOUND, otherWorkspace.submitApprovalDecision(firstRequest.approvalId, ApprovalChoice.ONCE).outcome)
            assertTrue(channel.decisions.isEmpty())
            val denied = repository.submitApprovalDecision(firstRequest.approvalId, ApprovalChoice.DENY)
            assertEquals(ApprovalSubmissionOutcome.SUBMITTED, denied.outcome)
            assertNull(denied.settledOutcome)
            val firstArgs = channel.decisions.single().second.at("payload")?.at("args")
            assertEquals("original-client", firstArgs?.text("clientId"))
            assertEquals("rejected", firstArgs?.at("outcome")?.text("value"))

            active = second
            channel.ready(secondStream, "replacement-client")
            runCurrent()
            val stale = repository.submitApprovalDecision(secondRequest.approvalId, ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.FAILED, stale.outcome)
            assertEquals("APPROVAL_STREAM_CLOSED", stale.errorCode)
            assertEquals(1, channel.decisions.size)
        } finally {
            repository.close()
            otherWorkspace.close()
        }
    }

    @Test fun `清理先于空回执时保持结果未知直到权威日志确认其他人拒绝`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val stream = channel.streamId()
            channel.ready(stream)
            channel.audit("asked", 1, "id" to "audit-id", "callId" to "tool-call-id")
            channel.asked(stream, callId = "tool-call-id")
            runCurrent()
            val id = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request.approvalId
            val ack = CompletableDeferred<JsonValue>()
            channel.receipt = { ack.await() }
            val decision = async { repository.submitApprovalDecision(id, ApprovalChoice.ONCE) }
            runCurrent()
            assertEquals(1, channel.decisions.size)

            channel.frame(stream, obj("type" to "cancel", "eventId" to "approval:一/1"))
            channel.asked(stream, callId = "tool-call-id")
            runCurrent()
            val closed = events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().single()
            assertEquals(id, closed.approvalId)
            assertEquals(ApprovalOutcome.UNKNOWN, closed.outcome)

            channel.audit("decided", 2, "id" to "audit-id", "outcome" to "rejected")
            runCurrent()
            ack.complete(JsonValue.JNull)
            val result = decision.await()
            assertEquals(ApprovalSubmissionOutcome.ALREADY_RESOLVED, result.outcome)
            assertEquals(ApprovalOutcome.DENIED, result.settledOutcome)
            val terminal = events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().last()
            assertEquals(id, terminal.approvalId)
            assertEquals(ApprovalOutcome.DENIED, terminal.outcome)
            assertEquals(1, events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().size)
            assertEquals(result, repository.submitApprovalDecision(id, ApprovalChoice.DENY))
            assertEquals(1, channel.decisions.size)
        } finally {
            repository.close()
        }
    }

    @Test fun `重复callId和缺失callId都不能用日志猜测实时审批结果`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val stream = channel.streamId()
            channel.ready(stream)
            channel.audit("asked", 1, "id" to "audit-1", "callId" to "duplicate")
            channel.audit("asked", 2, "id" to "audit-2", "callId" to "duplicate")
            channel.audit("asked", 3, "id" to "no-call")
            channel.asked(stream, id = "no-call")
            channel.asked(stream, id = "event-with-call", callId = "duplicate")
            runCurrent()
            val ids = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().map { it.request.approvalId }
            assertEquals(2, ids.size)
            ids.forEach { assertNull(repository.submitApprovalDecision(it, ApprovalChoice.ONCE).settledOutcome) }

            channel.audit("decided", 4, "id" to "no-call", "outcome" to "allowed-once")
            channel.audit("decided", 5, "id" to "audit-1", "outcome" to "rejected")
            channel.frame(stream, obj("type" to "cancel", "eventId" to "no-call"))
            channel.frame(stream, obj("type" to "cancel", "eventId" to "event-with-call"))
            runCurrent()
            assertEquals(listOf(ApprovalOutcome.UNKNOWN, ApprovalOutcome.UNKNOWN),
                events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().map { it.outcome })
            ids.forEach { assertEquals(ApprovalSubmissionOutcome.OUTCOME_UNKNOWN, repository.submitApprovalDecision(it, ApprovalChoice.DENY).outcome) }
            assertEquals(2, channel.decisions.size)
        } finally {
            repository.close()
        }
    }

    @Test fun `明确未发送可重试但回执缺失时禁止再次发送并接受后续终态`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val stream = channel.streamId()
            channel.ready(stream)
            channel.asked(stream)
            runCurrent()
            val id = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request.approvalId
            channel.receipt = { throw RemoteRpcException("DATA_CHANNEL_CLOSED", RemoteDeliveryOutcome.NOT_SENT) }
            val failed = repository.submitApprovalDecision(id, ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.FAILED, failed.outcome)
            assertEquals("DATA_CHANNEL_CLOSED", failed.errorCode)
            assertNull(failed.settledOutcome)

            channel.receipt = { throw RemoteRpcException("RPC_TIMEOUT", RemoteDeliveryOutcome.UNKNOWN) }
            val unknown = repository.submitApprovalDecision(id, ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.OUTCOME_UNKNOWN, unknown.outcome)
            assertEquals("RPC_TIMEOUT", unknown.errorCode)
            assertNull(unknown.settledOutcome)
            assertEquals(unknown, repository.submitApprovalDecision(id, ApprovalChoice.DENY))
            assertEquals(2, channel.decisions.size)

            channel.frame(stream, obj("type" to "approval/resolved", "sessionId" to "session/一", "approvalId" to "approval:一/1",
                "outcome" to "expired", "decidedAt" to 100))
            runCurrent()
            val terminal = events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().single()
            assertEquals(ApprovalOutcome.TIMED_OUT, terminal.outcome)
            assertEquals(100L, terminal.decidedAt)
            val expired = repository.submitApprovalDecision(id, ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.EXPIRED, expired.outcome)
            assertEquals(100L, expired.decidedAt)
            assertEquals(2, channel.decisions.size)
        } finally {
            repository.close()
        }
    }

    @Test fun `结构化拒绝到达时保留远端决策和已过期原因`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val stream = channel.streamId()
            channel.ready(stream)
            channel.asked(stream, "choice")
            channel.asked(stream, "expiry")
            channel.asked(stream, "new-code")
            channel.asked(stream, "unstructured-code")
            runCurrent()
            val (choice, expiry, newCode, unstructured) = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().map { it.request.approvalId }
            channel.receipt = { obj("ok" to true, "value" to obj("ok" to false, "error" to obj(
                "code" to "APPROVAL_ALREADY_RESOLVED", "message" to "私有内容", "details" to obj("approval" to obj(
                    "approvalId" to "choice", "conversationId" to "session/一", "decision" to "deny", "decidedAt" to 200))))) }
            val resolved = repository.submitApprovalDecision(choice, ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.ALREADY_RESOLVED, resolved.outcome)
            assertEquals(ApprovalOutcome.DENIED, resolved.settledOutcome)
            assertEquals(200L, resolved.decidedAt)
            assertEquals("APPROVAL_ALREADY_RESOLVED", resolved.errorCode)

            channel.receipt = { throw RemoteRpcException("APPROVAL_EXPIRED") }
            val expired = repository.submitApprovalDecision(expiry, ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.EXPIRED, expired.outcome)
            assertEquals(ApprovalOutcome.TIMED_OUT, expired.settledOutcome)
            assertEquals("APPROVAL_EXPIRED", expired.errorCode)
            assertNull(expired.decidedAt)

            channel.receipt = { throw RemoteRpcException("HOST_APPROVAL_STALE") }
            assertEquals("HOST_APPROVAL_STALE", repository.submitApprovalDecision(newCode, ApprovalChoice.ONCE).errorCode)
            channel.receipt = { throw RemoteRpcException("有私密内容的错误文本") }
            assertEquals("APPROVAL_REMOTE_REJECTED", repository.submitApprovalDecision(unstructured, ApprovalChoice.ONCE).errorCode)
        } finally {
            repository.close()
        }
    }

    @Test fun `接受型回执继续等待而标准网关终态与畸形回执分别处理`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val stream = channel.streamId()
            channel.ready(stream)
            listOf("accepted", "settled", "invalid", "wrong-scope").forEach { channel.asked(stream, it) }
            runCurrent()
            val ids = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().map { it.request.approvalId }
            channel.receipt = { obj("ok" to true, "value" to obj("accepted" to true)) }
            val accepted = repository.submitApprovalDecision(ids[0], ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.SUBMITTED, accepted.outcome)
            assertNull(accepted.settledOutcome)
            assertNull(accepted.decidedAt)
            channel.frame(stream, obj("type" to "approval/resolved", "sessionId" to "session/一", "approvalId" to "accepted", "outcome" to "rejected"))
            runCurrent()
            assertEquals(ApprovalOutcome.DENIED, events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().single().outcome)

            channel.receipt = { obj("protocol" to "2.1", "data" to obj("approval" to obj(
                "approvalId" to "settled", "conversationId" to "session/一", "decision" to "once", "decidedAt" to 300))) }
            val settled = repository.submitApprovalDecision(ids[1], ApprovalChoice.ONCE)
            assertEquals(ApprovalOutcome.ALLOWED_ONCE, settled.settledOutcome)
            assertEquals(300L, settled.decidedAt)

            channel.receipt = { obj("ok" to true, "value" to obj("resolved" to true, "decision" to "PRIVATE_SERVER_CONTENT")) }
            val invalid = repository.submitApprovalDecision(ids[2], ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.OUTCOME_UNKNOWN, invalid.outcome)
            assertEquals("APPROVAL_INVALID_RECEIPT", invalid.errorCode)

            channel.receipt = { obj("data" to obj("approval" to obj("approvalId" to "settled", "decision" to "deny"))) }
            val mismatched = repository.submitApprovalDecision(ids[3], ApprovalChoice.ONCE)
            assertEquals(ApprovalSubmissionOutcome.OUTCOME_UNKNOWN, mismatched.outcome)
            assertEquals("APPROVAL_RECEIPT_SCOPE_MISMATCH", mismatched.errorCode)
        } finally {
            repository.close()
        }
    }

    @Test fun `Codex和ACP用各自原生拒绝词并按resolved回执结算`() = runTest {
        val channel = Channel()
        val cases = listOf(
            Triple(CodexSessionCarrier(channel), "codex.app", "item/commandExecution/requestApproval"),
            Triple(AcpSessionCarrier(channel, "hermes"), "agent.acp", "session/request_permission"),
        )
        for ((native, prefix, method) in cases) {
            val events = mutableListOf<VerifiedConversationEvent>()
            val carrier = object : RemoteSessionCarrier by native {
                override suspend fun history(sessionId: String, page: PageRequest) = RemoteHistoryPage(emptyList())
            }
            val repository = repository(carrier) { events.add(it) }
            try {
                repository.timeline(first, PageRequest())
                runCurrent()
                channel.frame(channel.streamId(prefix, endpoint = null), obj("method" to method,
                    "params" to obj("sessionId" to "session/二", "requestHandle" to "wrong-handle")), prefix)
                channel.frame(channel.streamId(prefix, endpoint = null), obj("method" to method,
                    "params" to obj("sessionId" to "session/一", "requestHandle" to "$prefix-handle", "options" to listOf(
                        obj("kind" to "allow_always", "optionId" to "permanent"),
                        obj("kind" to "reject_once", "optionId" to "no", "name" to "拒绝此次")))), prefix)
                runCurrent()
                val request = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request
                val id = request.approvalId
                if (prefix == "agent.acp") {
                    assertEquals(listOf(ApprovalChoice.DENY), request.options.map { it.choice })
                    assertEquals("拒绝此次", request.options.single().label)
                    assertEquals(ApprovalSubmissionOutcome.UNSUPPORTED, repository.submitApprovalDecision(id, ApprovalChoice.ONCE).outcome)
                    assertTrue(channel.decisions.none { it.first == "agent.acp.respond" })
                    channel.frame(channel.streamId(prefix, endpoint = null), obj("method" to method,
                        "params" to obj("sessionId" to "session/一", "requestHandle" to "no-routable-option", "options" to listOf(
                            obj("kind" to "allow_always", "optionId" to "only-permanent")))), prefix)
                    runCurrent()
                    val fallback = events.filterIsInstance<VerifiedConversationEvent.ApprovalTextFallback>().single()
                    assertEquals("APPROVAL_OPTIONS_UNSUPPORTED", fallback.diagnosticCode)
                }
                channel.receipt = { obj("resolved" to true) }
                val decision = repository.submitApprovalDecision(id, ApprovalChoice.DENY)
                assertEquals(ApprovalOutcome.DENIED, decision.settledOutcome)
                assertEquals("$prefix.respond" to obj("requestHandle" to "$prefix-handle",
                    "decision" to if (prefix == "codex.app") "decline" else "reject-once"), channel.decisions.last())
            } finally {
                repository.close()
            }
        }
    }

    @Test fun `传输协程取消原样传播且禁止重发可能已送达的决策`() = runTest {
        val channel = Channel()
        val events = mutableListOf<VerifiedConversationEvent>()
        val repository = repository(HarnessSessionCarrier(channel)) { events.add(it) }
        try {
            repository.timeline(first, PageRequest())
            runCurrent()
            val stream = channel.streamId()
            channel.ready(stream)
            channel.asked(stream)
            runCurrent()
            val id = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request.approvalId
            val cancellation = CancellationException("测试取消")
            channel.receipt = { throw cancellation }
            try {
                repository.submitApprovalDecision(id, ApprovalChoice.ONCE)
                throw AssertionError("应传播取消")
            } catch (exception: CancellationException) {
                assertSame(cancellation, exception)
            }
            assertEquals(ApprovalSubmissionOutcome.OUTCOME_UNKNOWN, repository.submitApprovalDecision(id, ApprovalChoice.ONCE).outcome)
            assertEquals(1, channel.decisions.size)
        } finally {
            repository.close()
        }
    }
}
