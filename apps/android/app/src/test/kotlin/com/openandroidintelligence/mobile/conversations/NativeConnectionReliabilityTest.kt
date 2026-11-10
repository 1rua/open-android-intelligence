package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeConnectionReliabilityTest {
    @Test fun healthyActivityKeepsTheSameReadAndDoesNotReloadHistory() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(51); runCurrent()
        assertEquals(2, h.reads)
        assertEquals(1, h.histories)
        repeat(8) { h.repository.connectionRestored(); runCurrent() }
        assertEquals(2, h.reads)
        assertEquals(0, h.cancelledReads)
        assertEquals(1, h.histories)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
    }

    @Test fun readOutagesContinueThroughTheCappedBackoffWithoutAnyWakeup() = runTest {
        val h = Harness(this).apply { failedReads = 10 }
        h.repository.activeConversationChanged(); runCurrent()
        val delays = listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L)
        delays.forEachIndexed { index, delay ->
            assertEquals(index + 1, h.reads)
            assertEquals(StreamHealth.RECONNECTING, h.repository.streamHealth.first())
            repeat(3) { h.repository.connectionRestored() }
            advanceTimeBy(delay - 1); runCurrent()
            assertEquals("Activity cannot shorten the existing delay", index + 1, h.reads)
            advanceTimeBy(1); runCurrent()
        }
        assertEquals(delays, h.retryBases)
        assertEquals(11, h.reads)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertNull(h.repository.streamFailure.first())
        assertEquals("next", h.storage.cursor)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun injectedJitterChangesTheDelayWithoutResettingTheRetry() = runTest {
        val h = Harness(this, jitter = { it + 25 }).apply { failedReads = 1 }
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(250); runCurrent()
        assertEquals(1, h.reads)
        h.repository.connectionRestored()
        advanceTimeBy(25); runCurrent()
        assertEquals(2, h.reads)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
    }

    @Test fun anExplicitNetworkRecoveryResumesACappedRetryWithoutResubmittingTheMessage() = runTest {
        val h = Harness(this).apply { failedReads = 9 }
        h.repository.activeConversationChanged(); runCurrent()
        for (backoff in listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 16000L, 32000L)) {
            advanceTimeBy(backoff); runCurrent()
        }
        assertEquals("恢复信号前必须已经进入六十秒退避", 9, h.reads)
        assertEquals(60_000L, h.retryBases.last())
        h.repository.connectionRestored(expediteRetry = true)
        runCurrent()

        assertEquals("网络恢复应立即唤醒原读取者，不能继续等待六十秒", 10, h.reads)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertEquals("next", h.storage.cursor)
        assertNull(h.storage.pending)
        assertEquals(1, h.requests.count { it.target.endsWith("/events/ack") })
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun repeatedNetworkRecoverySignalsCannotSkipMultipleRetryDelaysWhileStillOffline() = runTest {
        val h = Harness(this).apply { failedReads = Int.MAX_VALUE }
        h.repository.activeConversationChanged(); runCurrent()
        for (backoff in listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 16000L, 32000L)) {
            advanceTimeBy(backoff); runCurrent()
        }
        assertEquals(9, h.reads)

        repeat(5) { h.repository.connectionRestored(expediteRetry = true); runCurrent() }

        assertEquals("同一恢复信号突发只允许提前尝试一次，后续失败保留退避", 10, h.reads)
        assertTrue(h.retryBases.takeLast(2).all { it == 60_000L })
        assertEquals("base", h.storage.cursor)
        assertTrue(h.requests.none { it.target.endsWith("/events/ack") || it.target.endsWith("/messages") })

        advanceTimeBy(5_000)
        h.failedReads = 0
        h.repository.connectionRestored(expediteRetry = true); runCurrent()
        assertEquals("冷却后真正恢复仍须能够唤醒", 11, h.reads)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertEquals("next", h.storage.cursor)
    }

    @Test fun emptyUnauthorizedAndForbiddenResponsesKeepTheirStatusAndPause() = runTest {
        for (status in listOf(401, 403)) {
            val h = Harness(this).apply { responseStatus = status }
            h.repository.activeConversationChanged(); runCurrent()
            assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
            assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AGENT_REQUEST_FAILED:$status", h.repository.streamFailure.first())
            repeat(4) { h.repository.connectionRestored() }
            advanceTimeBy(120000); runCurrent()
            assertEquals(1, h.reads)
            assertEquals("base", h.storage.cursor)
        }
    }

    @Test fun authenticationPauseOnlyResumesAfterTheSharedCredentialVersionChanges() = runTest {
        credentialRotationScenario(eventSync = true)
    }

    @Test fun generationRecoveryAuthenticationPauseOnlyResumesAfterTheSharedCredentialVersionChanges() = runTest {
        credentialRotationScenario(eventSync = false)
    }

    private suspend fun TestScope.credentialRotationScenario(eventSync: Boolean) {
        val initial = GatewayProfile("account", "phone", "sessionA", "https://core.example", accessToken = "synthetic-a")
        var allowRotation = false
        var refreshes = 0
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            if (!allowRotation) throw com.openandroidintelligence.gateway.auth.GatewayAuthException(
                "AUTHENTICATION_FAILED", "AUTHENTICATION_FAILED", 401)
            it.copy(sessionId = "sessionB", accessToken = "synthetic-b")
        }
        val requests = mutableListOf<WireRequest>()
        val idle = CompletableDeferred<Unit>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val session = request.headers.single { it.name == "X-Open-Android-Intelligence-Session" }.value
                if (request.target.endsWith("/history")) return WireResponse(200, emptyList(),
                    """{"messages":[{"id":"user","role":"user","text":"synthetic","clientMessageId":"client","generationId":"gen","contentRevision":1}]}""".toByteArray())
                if (session == "sessionA") return WireResponse(401, emptyList(), ByteArray(0))
                assertEquals("sessionB", session)
                val body = when {
                    request.target.contains("/events?") -> {
                        if (request.target.contains("waitMs=")) idle.await()
                        """{"events":[],"cursor":"next","hasMore":false}"""
                    }
                    request.target.contains("/generation?") -> {
                        if (request.target.contains("waitMs=")) idle.await()
                        """{"generation":{"generationId":"gen","conversationId":"chat","clientMessageId":"client","userMessageId":"user","state":"running","revision":1}}"""
                    }
                    request.target.endsWith("/events/ack") -> """{"cursor":"next"}"""
                    else -> error("Only native recovery reads and ACK are allowed")
                }
                return WireResponse(200, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = AgentSessionClient(GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            sessionRenewal = renewal, ioDispatcher = dispatcher))
        val agent = CoreAgent("hermes", "synthetic", "hermes", true, "account",
            AgentCapabilities(history = true, eventSync = eventSync, generationRecovery = !eventSync))
        val storage = NativeEventSynchronizerTest.Storage()
        val repository = CoreAgentConversationRepository(client, { agent }, { "chat" }, backgroundScope, dispatcher,
            storeForAgent = { storage }, nativeRetryJitter = { it })
        try {
            if (eventSync) repository.activeConversationChanged() else repository.timeline("chat", PageRequest())
            runCurrent()
            assertEquals(StreamHealth.FAILED, repository.streamHealth.first())
            assertEquals("sessionA", client.credentialVersion())
            val rejectedRequests = requests.size
            val failure = repository.streamFailure.first()
            repeat(4) { repository.connectionRestored(); runCurrent() }
            assertEquals(rejectedRequests, requests.size)
            assertEquals(1, refreshes)
            assertEquals(failure, repository.streamFailure.first())
            if (eventSync) {
                repository.activeConversationChanged(); runCurrent()
                assertEquals("Reopening the same conversation cannot bypass refused credentials", rejectedRequests, requests.size)
            }
            allowRotation = true
            renewal.refresh()
            assertEquals("sessionB", client.credentialVersion())
            repository.connectionRestored(); runCurrent()
            assertEquals(StreamHealth.LIVE, repository.streamHealth.first())
            assertNull(repository.streamFailure.first())
            if (eventSync) assertEquals("next", storage.cursor) else {
                assertEquals("gen", repository.generationId.value)
                assertEquals(GenerationState.RUNNING, repository.generationState.value)
                assertTrue(requests.none { it.target.contains("/events") })
            }
            assertTrue(requests.drop(rejectedRequests).all { request ->
                request.headers.single { it.name == "X-Open-Android-Intelligence-Session" }.value == "sessionB"
            })
            val healthyRequests = requests.size
            repeat(4) { repository.connectionRestored(); runCurrent() }
            assertEquals(healthyRequests, requests.size)
            assertEquals(2, refreshes)
            assertTrue(requests.none { it.target.endsWith("/messages") })
        } finally { renewal.close() }
    }

    @Test fun emptyHttpServiceErrorsRemainRetryableAndPreserveTheirStatus() = runTest {
        for (status in listOf(408, 429, 500, 502, 503)) {
            val h = Harness(this).apply { responseStatus = status }
            h.repository.activeConversationChanged(); runCurrent()
            for (delay in listOf(250L, 500L, 1000L, 2000L, 4000L)) {
                advanceTimeBy(delay); runCurrent()
            }
            assertEquals(6, h.reads)
            assertEquals(StreamHealth.RECONNECTING, h.repository.streamHealth.first())
            assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AGENT_REQUEST_FAILED:$status", h.repository.streamFailure.first())
            h.responseStatus = 200
            advanceTimeBy(8000); runCurrent()
            assertEquals(7, h.reads)
            assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        }
    }

    @Test fun authenticationStorageUnavailableRetriesWithoutRotatingTheValidCredential() = runTest {
        val profile = GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic")
        var refreshes = 0
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
            refreshes++
            error("存储暂时忙不得触发凭据续签")
        }
        val h = Harness(this, renewal = renewal).apply {
            responseStatus = 503
            responseBody = """{"error":{"code":"AUTHENTICATION_STORAGE_UNAVAILABLE","retryable":true,"details":{"stage":"SESSION_LOOKUP","storageFailure":"SQLITE_BUSY"}}}"""
        }
        try {
            h.repository.activeConversationChanged(); runCurrent()
            for (delay in listOf(250L, 500L, 1000L, 2000L, 4000L)) { advanceTimeBy(delay); runCurrent() }
            assertEquals(6, h.reads)
            assertEquals(StreamHealth.RECONNECTING, h.repository.streamHealth.first())
            assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AUTHENTICATION_STORAGE_UNAVAILABLE:503", h.repository.streamFailure.first())
            assertEquals(0, refreshes)
            assertEquals(profile, renewal.current)
            assertEquals("base", h.storage.cursor)
            h.responseStatus = 200
            advanceTimeBy(8000); runCurrent()
            assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
            assertNull(h.repository.streamFailure.first())
            assertEquals(0, refreshes)
            assertEquals(profile, renewal.current)
        } finally { h.repository.close(); renewal.close() }
    }

    @Test fun anUnavailableTokenRefreshIsNotConfusedWithRefusedCredentials() = runTest {
        val h = Harness(this).apply {
            readFailure = com.openandroidintelligence.gateway.auth.GatewayAuthException("REFRESH_FAILED", "UPSTREAM_UNAVAILABLE", 503)
            failedReads = 6
        }
        h.repository.activeConversationChanged(); runCurrent()
        for (delay in listOf(250L, 500L, 1000L, 2000L, 4000L)) { advanceTimeBy(delay); runCurrent() }
        assertEquals(StreamHealth.RECONNECTING, h.repository.streamHealth.first())
        assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:UPSTREAM_UNAVAILABLE:503", h.repository.streamFailure.first())
        advanceTimeBy(8000); runCurrent()
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
    }

    @Test fun tlsAndPinFailuresNeverUseTheNetworkRetryLoop() = runTest {
        for ((failure, code) in listOf(
            SSLHandshakeException("synthetic certificate rejection") to "SSLHandshakeException",
            GatewaySecurityException("PIN_MISMATCH") to "TLS_PIN_MISMATCH",
            IOException("PIN_MISMATCH: synthetic") to "TLS_PIN_MISMATCH",
            IOException("wrapped", SSLHandshakeException("synthetic")) to "SSLHandshakeException",
        )) {
            val h = Harness(this).apply { readFailure = failure; failedReads = Int.MAX_VALUE }
            h.repository.activeConversationChanged(); runCurrent()
            assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
            assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:$code", h.repository.streamFailure.first())
            repeat(4) { h.repository.connectionRestored() }
            advanceTimeBy(120000); runCurrent()
            assertEquals(1, h.reads)
            assertEquals("base", h.storage.cursor)
        }
    }

    @Test fun aProtocolCodeOnAServiceResponseDoesNotBecomeANetworkRetry() = runTest {
        val h = Harness(this).apply {
            readFailure = AgentSessionException("AGENT_RESPONSE_INVALID", 503)
            failedReads = Int.MAX_VALUE
        }
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
        assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AGENT_RESPONSE_INVALID:503", h.repository.streamFailure.first())
        repeat(4) { h.repository.connectionRestored() }
        advanceTimeBy(120000); runCurrent()
        assertEquals(1, h.reads)
    }

    @Test fun upstreamBridgeAuthenticationFailuresRemainRetryableAndResumeTheCommittedCursor() = runTest {
        val failure = classifyNativeReadFailure(AgentSessionException("AGENT_UNAVAILABLE", 502))
        assertEquals(NativeReadFailureKind.TRANSIENT, failure.kind)
        assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AGENT_UNAVAILABLE:502", failure.notice)

        val h = Harness(this).apply {
            responseStatus = 502
            responseBody = """{"error":{"code":"AGENT_UNAVAILABLE","details":{"source":"agent_bridge","reason":"authentication_failed","upstreamStatus":401}}}"""
        }
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals(StreamHealth.RECONNECTING, h.repository.streamHealth.first())
        assertEquals(1, h.reads)
        assertEquals("base", h.storage.cursor)
        assertEquals("login", h.client.credentialVersion())

        listOf(250L, 500L, 1000L).forEachIndexed { index, delay ->
            advanceTimeBy(delay - 1); runCurrent()
            assertEquals(index + 1, h.reads)
            if (index == 2) h.responseStatus = 200
            advanceTimeBy(1); runCurrent()
        }

        assertEquals(listOf(250L, 500L, 1000L), h.retryBases)
        h.repository.connectionRestored(); runCurrent()
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertNull(h.repository.streamFailure.first())
        assertEquals(4, h.reads)
        assertEquals("next", h.storage.cursor)
        assertNull(h.storage.pending)
        val eventReads = h.requests.filter { it.target.contains("/events?") }
        assertEquals(4, eventReads.size)
        assertTrue(eventReads.all { it.target.contains("cursor=base") })
        assertEquals(1, h.requests.count { it.target.endsWith("/events/ack") })
        assertEquals("login", h.client.credentialVersion())
        assertTrue(h.requests.all { request ->
            request.headers.single { it.name == "X-Open-Android-Intelligence-Session" }.value == "login"
        })
    }

    @Test fun peerErrorTextCannotEnterTheDiagnosticNotice() {
        val failure = classifyNativeReadFailure(AgentSessionException("synthetic-secret\nmessage-body", 503))
        assertEquals(NativeReadFailureKind.TRANSIENT, failure.kind)
        assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AGENT_REQUEST_FAILED:503", failure.notice)
    }

    @Test fun protocolFailuresDoNotExposeUntrustedExceptionTextAsADiagnosticCode() = runTest {
        val h = Harness(this).apply {
            readFailure = AgentSessionException("NATIVE_EVENT_SCOPE_MISMATCH")
            failedReads = Int.MAX_VALUE
        }
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
        assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:NATIVE_EVENT_SCOPE_MISMATCH", h.repository.streamFailure.first())
        repeat(4) { h.repository.connectionRestored() }
        advanceTimeBy(120000); runCurrent()
        assertEquals(1, h.reads)
    }

    @Test fun localSaveFailureHasItsOwnBudgetAndNeverAcknowledgesUnsavedHistory() = runTest {
        val h = Harness(this).apply { rejectSave = true }
        h.repository.activeConversationChanged(); runCurrent()
        for (delay in listOf(250L, 500L, 1000L, 2000L, 4000L)) { advanceTimeBy(delay); runCurrent() }
        assertEquals(6, h.saves)
        assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
        assertEquals("NATIVE_EVENT_SYNC_FAILED:LOCAL_SAVE_FAILED:IOException", h.repository.streamFailure.first())
        assertEquals("base", h.storage.cursor)
        assertNull(h.storage.pending)
        assertTrue(h.requests.none { it.target.endsWith("/events/ack") })
        advanceTimeBy(60001); runCurrent()
        assertEquals(6, h.saves)
        h.rejectSave = false
        h.repository.connectionRestored(); runCurrent()
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertNull(h.repository.streamFailure.first())
    }

    @Test fun aWakeDuringTheFinalSaveAttemptIsConsumedAfterTheOldOwnerExits() = runTest {
        val h = Harness(this).apply { rejectSave = true }
        h.repository.activeConversationChanged(); runCurrent()
        for (delay in listOf(250L, 500L, 1000L, 2000L)) { advanceTimeBy(delay); runCurrent() }
        h.saveGate = CompletableDeferred()
        advanceTimeBy(4000); runCurrent()
        assertEquals(6, h.saves)
        h.repository.connectionRestored()
        h.rejectSave = false
        h.saveGate!!.complete(Unit); runCurrent()
        assertEquals(7, h.saves)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertNull(h.repository.streamFailure.first())
        assertEquals("next", h.storage.cursor)
    }

    @Test fun aNewConversationDoesNotReceiveThePreviousOwnersRetryOrFailure() = runTest {
        val h = Harness(this).apply { failedReads = 6 }
        h.repository.activeConversationChanged(); runCurrent()
        for (delay in listOf(250L, 500L, 1000L, 2000L, 4000L)) { advanceTimeBy(delay); runCurrent() }
        assertNotNull(h.repository.streamFailure.first())
        h.conversationId = "other"
        h.failedReads = 0
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertNull(h.repository.streamFailure.first())
        advanceTimeBy(8001); runCurrent()
        assertEquals(6, h.requests.count { it.target.contains("/sessions/chat/events?") })
        assertTrue(h.requests.none { it.target.contains("/sessions/chat/events/ack") })
    }

    @Test fun completingAMessageCannotReplaceHealthyEventSyncWithIdle() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client"), "synthetic")); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
    }

    @Test fun aSuccessfulMessageCannotHideAPausedEventReadFailure() = runTest {
        val h = Harness(this).apply { responseStatus = 401 }
        h.repository.activeConversationChanged(); runCurrent()
        val failure = h.repository.streamFailure.first()
        h.repository.submitMessage("chat", OutgoingMessage(ClientMessageId("client"), "synthetic")); runCurrent()
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
        assertEquals(failure, h.repository.streamFailure.first())
    }

    private class Harness(scope: TestScope, jitter: (Long) -> Long = { it },
        renewal: com.openandroidintelligence.gateway.auth.GatewaySessionRenewal? = null) {
        var conversationId = "chat"
        var failedReads = 0
        var readFailure: Exception = IOException("synthetic outage")
        var responseStatus = 200
        var responseBody: String? = null
        var reads = 0
        var histories = 0
        var cancelledReads = 0
        var rejectSave = false
        var saves = 0
        var saveGate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<WireRequest>()
        val retryBases = mutableListOf<Long>()
        val storage = NativeEventSynchronizerTest.Storage()
        private val store = object : NativeConversationStore by storage {
            override suspend fun commitPage(conversationId: String, events: List<VerifiedConversationEvent>,
                approvals: List<NativeEventApproval>, cursor: String) {
                saves++
                if (rejectSave) { saveGate?.await(); throw IOException("synthetic local save failure") }
                storage.commitPage(conversationId, events, approvals, cursor)
            }
        }
        private val idle = CompletableDeferred<Unit>()
        private val served = mutableSetOf<String>()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                var contentType = "application/json"
                val body = when {
                    request.target.contains("/events?") -> {
                        reads++
                        if (failedReads > 0) { failedReads--; throw readFailure }
                        if (responseStatus != 200) return WireResponse(responseStatus, emptyList(),
                            responseBody?.toByteArray() ?: ByteArray(0))
                        val session = request.target.substringBefore("/events?")
                        if (!served.add(session) && request.target.contains("waitMs=")) {
                            try { idle.await() } catch (cancelled: CancellationException) { cancelledReads++; throw cancelled }
                        }
                        """{"events":[],"cursor":"next","hasMore":false}"""
                    }
                    request.target.endsWith("/history") -> { histories++; """{"messages":[]}""" }
                    request.target.endsWith("/events/ack") -> """{"cursor":"next"}"""
                    request.target.endsWith("/messages") -> {
                        contentType = "text/event-stream"
                        "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"gen\",\"clientMessageId\":\"client\",\"userMessageId\":\"user\",\"state\":\"running\",\"revision\":1}\n\n" +
                            "event: message\ndata: {\"type\":\"done\",\"generationId\":\"gen\",\"outcome\":\"completed\",\"text\":\"\",\"revision\":2}\n\n"
                    }
                    else -> error("Unexpected native request: ${request.method} ${request.target}")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", contentType)), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = AgentSessionClient(GatewayHttpClient(
            GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher,
            sessionRenewal = renewal,
        ))
        private val agent = CoreAgent("hermes", "synthetic", "hermes", true, "account",
            AgentCapabilities(history = true, eventSync = true, send = true, stream = true))
        val repository = CoreAgentConversationRepository(client, { agent }, { conversationId }, scope.backgroundScope,
            dispatcher, storeForAgent = { store }, nativeRetryJitter = { retryBases += it; jitter(it) },
            nativeMonotonicMillis = { scope.testScheduler.currentTime })
    }
}
