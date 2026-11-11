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
    @Test fun anExplicitRecoveryResumesALegacyHostsCappedGenerationRetry() = runTest {
        val h = LegacyRecoveryHarness(this).apply { failedGenerations = 9 }
        h.repository.timeline("chat", PageRequest()); runCurrent()
        for (backoff in listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 16000L, 32000L)) {
            advanceTimeBy(backoff); runCurrent()
        }
        assertEquals("旧宿主必须已进入六十秒退避", 9, h.generationReads)
        assertEquals(60_000L, h.retryBases.last())

        h.repository.connectionRestored(expediteRetry = true, revalidateNetwork = true)
        runCurrent()

        assertEquals("明确恢复信号须唤醒旧宿主回执读取，不能继续睡满六十秒", 10, h.generationReads)
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertTrue(h.requests.filter { it.target.contains("/generation?") }.all {
            it.target.contains("clientMessageId=legacy-client") && it.target.contains("generationId=legacy-generation")
        })
        assertTrue(h.requests.none { it.method == "POST" || it.target.contains("/events") })
    }

    @Test fun aForegroundSignalReplacesALegacyHostsHalfOpenGenerationRead() = runTest {
        val h = LegacyRecoveryHarness(this).apply { runningUntilRead = 2; holdGenerationRead = 2 }
        h.repository.timeline("chat", PageRequest()); runCurrent()
        assertEquals("必须先持有真实旧回执长轮询", 2, h.generationReads)

        h.repository.connectionRestored(expediteRetry = true, revalidateNetwork = true)
        runCurrent()

        assertEquals("前台恢复须独立取消旧宿主半开网络请求", 1, h.cancelledGenerations)
        assertEquals(3, h.generationReads)
        assertEquals(GenerationState.COMPLETED, h.repository.generationState.value)
        assertTrue(h.requests.none { it.method == "POST" || it.target.contains("/events") })
    }

    @Test fun healthyActivityKeepsTheSameReadAndDoesNotReloadHistory() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(51); runCurrent()
        assertEquals(2, h.reads)
        assertEquals(1, h.histories)
        repeat(8) {
            h.repository.connectionRestored()
            h.repository.connectionRestored(expediteRetry = true)
            runCurrent()
        }
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

    @Test fun recoveryWhileChoosingTheRetryDelayCannotLoseItsWakeup() = runTest {
        lateinit var h: Harness
        h = Harness(this, jitter = { backoff ->
            h.repository.connectionRestored(expediteRetry = true)
            backoff
        }).apply { failedReads = 1 }
        h.repository.activeConversationChanged(); runCurrent()

        assertEquals("恢复恰好发生在确定退避的时间边界也必须消费唤醒", 2, h.reads)
        assertEquals(StreamHealth.LIVE, h.repository.streamHealth.first())
        assertEquals("next", h.storage.cursor)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun aConfirmedTransportRebuildReplacesTheHalfOpenReadWithoutRecreatingItsSynchronizer() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(51); runCurrent()
        assertEquals("必须先让旧长轮询真正保持等待", 2, h.reads)
        assertEquals("next", h.storage.cursor)
        val acceptedBeforeRecovery = h.requests.count { it.target.endsWith("/events/ack") }

        h.resumeReadsThrough = 3
        h.nextCursor = "recovered"
        h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = 1)
        runCurrent()

        assertEquals("确认正式传输已重建后必须替换旧半开读取", 1, h.cancelledReads)
        assertEquals(3, h.reads)
        assertEquals("recovered", h.storage.cursor)
        assertNull(h.storage.pending)
        assertEquals("同一个同步器不能重新读取已确认历史", 1, h.histories)
        assertEquals(acceptedBeforeRecovery + 1, h.requests.count { it.target.endsWith("/events/ack") })
        assertTrue(h.requests.none { it.target.endsWith("/messages") })

        advanceTimeBy(51); runCurrent()
        val healthyReads = h.reads
        repeat(5) {
            h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = 1)
            runCurrent()
        }
        h.repository.connectionRestored(confirmedTransportRebuild = 0); runCurrent()
        assertEquals("同代次或过期通知不能取消恢复后的健康读取", 1, h.cancelledReads)
        assertEquals(healthyReads, h.reads)
    }

    @Test fun aConfirmedTransportRebuildRetriesTheSamePendingAckBeforeReadingAnotherPage() = runTest {
        val h = Harness(this).apply { holdAck = true }
        h.repository.activeConversationChanged(); runCurrent()
        assertEquals("next", h.storage.pending)
        assertEquals(1, h.reads)
        assertEquals(1, h.saves)
        assertEquals(1, h.requests.count { it.target.endsWith("/events/ack") })

        h.holdAck = false
        h.repository.connectionRestored(confirmedTransportRebuild = 2)
        runCurrent()

        assertEquals("重连只取消旧 ACK 的观察，不能重做本地保存", 1, h.saves)
        assertEquals(1, h.cancelledAcks)
        assertNull(h.storage.pending)
        assertEquals("next", h.storage.cursor)
        val replayedAckIndex = h.requests.indexOfLast { it.target.endsWith("/events/ack") }
        val nextReadIndex = h.requests.indexOfLast { it.target.contains("/events?") }
        assertTrue("补传原 ACK 成功后才可发起下一次长轮询", replayedAckIndex < nextReadIndex)
        assertEquals(2, h.requests.count { it.target.endsWith("/events/ack") })
        assertTrue(h.requests.filter { it.target.endsWith("/events/ack") }.all {
            it.body.toString(Charsets.UTF_8).contains("\"cursor\":\"next\"")
        })
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun transportRecoveryCannotCancelAlreadySavedEventsWaitingForALocalSubscriber() = runTest {
        val historyRelease = CompletableDeferred<Unit>()
        val subscriberRelease = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val h = Harness(this).apply {
            historyGate = historyRelease
            historyBody = """{"messages":[${(1..8).joinToString(",") { """{"id":"message-$it","role":"assistant","text":"内容$it","contentRevision":1}""" }}]}"""
        }
        val subscriber = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.repository.observeEvents(ConversationScope("profile", "https://core.example", "account", "install"))
                .collect { event ->
                    subscriberRelease.await()
                    (event as? VerifiedConversationEvent.TimelineUpsert)?.let { received += it.message.id }
                }
        }
        try {
            historyRelease.complete(Unit); runCurrent()
            assertEquals("事件页应先完成本地保存", 1, h.saves)
            assertEquals("next", h.storage.pending)
            assertTrue("本地订阅尚未消费时不能确认远端页", h.requests.none { it.target.endsWith("/events/ack") })

            h.repository.connectionRestored(confirmedTransportRebuild = 1); runCurrent()
            subscriberRelease.complete(Unit); runCurrent()

            assertEquals("连接恢复不能跳过尚未发布的已保存消息", (1..8).map { "message-$it" }, received)
            assertNull(h.storage.pending)
            assertEquals(1, h.requests.count { it.target.endsWith("/events/ack") })
        } finally { subscriber.cancel() }
    }

    @Test fun aRealForegroundRecoveryCanRestoreANativeOnlyHalfOpenRequest() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(51); runCurrent()
        assertEquals(2, h.reads)
        h.resumeReadsThrough = 3
        h.nextCursor = "foreground-recovered"

        h.repository.connectionRestored(expediteRetry = true, revalidateNetwork = true); runCurrent()

        assertEquals("Platform 仍正常时，真实前台恢复也须独立替换 Native 半开请求", 1, h.cancelledReads)
        assertEquals("foreground-recovered", h.storage.cursor)
        assertNull(h.storage.pending)
        assertEquals(1, h.histories)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
        advanceTimeBy(51); runCurrent()
        val healthyReads = h.reads
        repeat(5) { h.repository.connectionRestored(expediteRetry = true, revalidateNetwork = true); runCurrent() }
        assertEquals("同一次前台恢复的重复信号不能反复取消新读取", 1, h.cancelledReads)
        assertEquals(healthyReads, h.reads)
    }

    @Test fun aForegroundNativeOnlyHalfOpenRequestRecoversWithoutAnyLifecycleSignal() = runTest {
        val h = Harness(this).apply { foreground = true }
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(51); runCurrent()
        assertEquals(2, h.reads)
        h.resumeReadsThrough = 3
        h.nextCursor = "foreground-watchdog"

        advanceTimeBy(8_251); runCurrent()

        assertEquals("持续前台必须独立检测并替换 Native 半开网络请求", 1, h.cancelledReads)
        assertEquals("foreground-watchdog", h.storage.cursor)
        assertNull(h.storage.pending)
        assertEquals(1, h.histories)
        assertTrue(h.requests.none { it.target.endsWith("/messages") })
    }

    @Test fun healthyForegroundLongPollsKeepTheirFiveSecondCadenceWithoutReloadingHistory() = runTest {
        val h = Harness(this).apply { foreground = true; serveLongPollAfterMillis = 5_000 }
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(60_000); runCurrent()

        assertEquals("首次快照加上每五秒一次事件等待", 13, h.reads)
        assertEquals(0, h.cancelledReads)
        assertEquals(1, h.histories)
        assertEquals("健康空页不能重复确认同一游标", 1, h.requests.count { it.target.endsWith("/events/ack") })
        assertTrue(h.requests.filter { it.target.contains("/events?") && it.target.contains("waitMs=") }
            .all { it.target.contains("waitMs=5000") })
    }

    @Test fun backgroundLongPollingKeepsTheOriginalTwentyFiveSecondWaitAndReadBudget() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(20_000); runCurrent()

        assertEquals(2, h.reads)
        assertEquals(0, h.cancelledReads)
        assertTrue(h.requests.last { it.target.contains("/events?") }.target.contains("waitMs=25000"))
        assertEquals(1, h.histories)
    }

    @Test fun aClosedRepositoryCannotResumeNetworkOrAccessItsClosedStore() = runTest {
        val h = Harness(this)
        h.repository.activeConversationChanged(); runCurrent()
        advanceTimeBy(51); runCurrent()
        h.repository.close(); runCurrent()
        val previousRequests = h.requests.size
        val previousStoreReads = h.storeReads
        h.storeClosed = true

        h.repository.connectionRestored(expediteRetry = true, revalidateNetwork = true, confirmedTransportRebuild = 1)
        h.repository.activeConversationChanged()
        runCurrent()

        assertEquals("关闭后的恢复通知不能重新读取已释放存储", previousStoreReads, h.storeReads)
        assertEquals("关闭后的恢复通知不能重建网络读取者", previousRequests, h.requests.size)
    }

    @Test fun emptyUnauthorizedAndForbiddenResponsesKeepTheirStatusAndPause() = runTest {
        for (status in listOf(401, 403)) {
            val h = Harness(this).apply { responseStatus = status }
            h.repository.activeConversationChanged(); runCurrent()
            assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
            assertEquals("NATIVE_EVENT_SYNC_FAILED:READ_FAILED:AGENT_REQUEST_FAILED:$status", h.repository.streamFailure.first())
            repeat(4) { h.repository.connectionRestored(); h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = it + 1L) }
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
            repeat(4) { repository.connectionRestored(); repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = it + 1L); runCurrent() }
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
            repeat(4) { h.repository.connectionRestored(confirmedTransportRebuild = it + 1L) }
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
        repeat(4) { h.repository.connectionRestored(); h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = it + 1L) }
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
        repeat(4) { h.repository.connectionRestored(confirmedTransportRebuild = it + 1L) }
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

    private class LegacyRecoveryHarness(scope: TestScope) {
        var failedGenerations = 0
        var generationReads = 0
        var runningUntilRead = 0
        var holdGenerationRead: Int? = null
        var cancelledGenerations = 0
        var generationFailure: Exception = IOException("测试旧宿主暂时离线")
        var foreground = false
        val requests = mutableListOf<WireRequest>()
        val retryBases = mutableListOf<Long>()
        val storage = NativeEventSynchronizerTest.Storage()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val heldGeneration = CompletableDeferred<Unit>()
        private val agent = CoreAgent("legacy", "旧宿主回归", "hermes", true, "account",
            AgentCapabilities(history = true, generationRecovery = true, eventSync = false))
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.target.endsWith("/history") -> """{"messages":[{"id":"legacy-user","role":"user","text":"测试输入","clientMessageId":"legacy-client","generationId":"legacy-generation","contentRevision":1}]}"""
                    request.target.contains("/generation?") -> {
                        generationReads++
                        if (failedGenerations > 0) { failedGenerations--; throw generationFailure }
                        if (holdGenerationRead == generationReads) try { heldGeneration.await() }
                            catch (cancelled: CancellationException) { cancelledGenerations++; throw cancelled }
                        val running = generationReads <= runningUntilRead
                        """{"generation":{"generationId":"legacy-generation","conversationId":"chat","clientMessageId":"legacy-client","userMessageId":"legacy-user","state":"${if (running) "running" else "completed"}","revision":${if (running) 1 else 2}}}"""
                    }
                    else -> error("旧宿主恢复只能读取历史与原执行回执")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repository = CoreAgentConversationRepository(
            AgentSessionClient(GatewayHttpClient(GatewayProfile("account", "phone", "login", "https://core.example",
                accessToken = "synthetic"), transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher)),
            { agent }, { "chat" }, scope.backgroundScope, dispatcher, storeForAgent = { storage },
            nativeRetryJitter = { retryBases += it; it }, nativeMonotonicMillis = { scope.testScheduler.currentTime },
            isForeground = { foreground },
        )
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
        var foreground = false
        var historyGate: CompletableDeferred<Unit>? = null
        var historyBody = """{"messages":[]}"""
        var serveLongPollAfterMillis: Long? = null
        var cancelledReads = 0
        var resumeReadsThrough = 0
        var nextCursor = "next"
        var holdAck = false
        var cancelledAcks = 0
        var rejectSave = false
        var saves = 0
        var storeReads = 0
        var storeClosed = false
        var saveGate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<WireRequest>()
        val retryBases = mutableListOf<Long>()
        val storage = NativeEventSynchronizerTest.Storage()
        private val store = object : NativeConversationStore by storage {
            override suspend fun pendingAck(conversationId: String): String? {
                storeReads++
                check(!storeClosed) { "测试存储已关闭" }
                return storage.pendingAck(conversationId)
            }
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
                        if (!served.add(session) && request.target.contains("waitMs=") && reads > resumeReadsThrough) {
                            try { serveLongPollAfterMillis?.let { delay(it) } ?: idle.await() }
                            catch (cancelled: CancellationException) { cancelledReads++; throw cancelled }
                        }
                        """{"events":[],"cursor":"$nextCursor","hasMore":false}"""
                    }
                    request.target.endsWith("/history") -> { histories++; historyGate?.await(); historyBody }
                    request.target.endsWith("/events/ack") -> {
                        if (holdAck) try { idle.await() }
                            catch (cancelled: CancellationException) { cancelledAcks++; throw cancelled }
                        """{"cursor":"$nextCursor"}"""
                    }
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
            nativeMonotonicMillis = { scope.testScheduler.currentTime }, isForeground = { foreground })
    }
}
