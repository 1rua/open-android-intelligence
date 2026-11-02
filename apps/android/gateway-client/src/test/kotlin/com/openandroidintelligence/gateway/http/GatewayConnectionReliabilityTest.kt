package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.*
import com.openandroidintelligence.gateway.ws.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConnectionReliabilityTest {
    private val profile = GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "test")

    @Test fun moreThanTheDiagnosticThresholdCanRecoverWithBoundedBackoff() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                if (++attempts <= 9) throw IOException("offline")
                emit("id: recovered\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            delayFn = { delays += it }, maxConsecutiveFailures = 2, jitterFn = { it })
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test fun authenticatedQuietWebSocketIsLiveBeforeAnyEvent() = runTest {
        val sink = EventStreamStatusSink()
        val ready = CompletableDeferred<Unit>()
        val websocket = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow {
                onSignal(GatewayWebSocketSignal.Ready(0))
                ready.complete(Unit)
                awaitCancellation()
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("no SSE fallback")
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            webSocketTransport = websocket, statusSink = sink, monotonicMillis = { testScheduler.currentTime })
        val job = launch { client.events().collect() }
        ready.await()
        assertEquals(EventStreamStatus.LIVE, sink.status.value)
        job.cancelAndJoin()
    }

    @Test fun healthySseRemainsOpenAcrossFailedProbesAndHandsOffAfterTheProbeCloses() = runTest {
        val order = mutableListOf<String>()
        val cursors = InMemoryEventCursorStore()
        var probes = 0
        val websocket = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow {
                if (probes == 0) throw GatewayWebSocketHandshakeException(426)
                order += "ws-open:$cursor"
                onSignal(GatewayWebSocketSignal.Ready(testScheduler.currentTime))
                emit(GatewayEvent("last", "notice", "{}"))
            }
            override suspend fun probe(sessionSnapshot: GatewayProfile?): GatewayProfile {
                probes++
                if (probes <= 2) throw GatewayWebSocketHandshakeException(404)
                order += "probe-closed"
                return requireNotNull(sessionSnapshot)
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                order += "sse-open"
                try {
                    var index = 0
                    while (true) {
                        emit("id: sse_${++index}\ndata: {}\n\n".toByteArray())
                        delay(15_000)
                    }
                } finally { order += "sse-closed" }
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, cursors,
            webSocketTransport = websocket, monotonicMillis = { testScheduler.currentTime }, jitterFn = { it })
        val events = client.events().takeWhile { it.id != "last" }.toList()
        assertTrue(events.size > 3)
        assertEquals(3, probes)
        assertEquals(1, order.count { it == "sse-open" })
        assertTrue(order.indexOf("probe-closed") < order.indexOf("sse-closed"))
        assertTrue(order.indexOf("sse-closed") < order.indexOfFirst { it.startsWith("ws-open:") })
        assertEquals("ws-open:${cursors.load("account")}", order.last())
    }

    @Test fun sixtySecondsOfCompleteHeartbeatsRestoreTheInitialReconnectDelay() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                if (attempts <= 2) throw IOException("offline")
                if (attempts == 3) {
                    repeat(5) { emit(": heartbeat\n\n".toByteArray()); delay(15_000) }
                    throw IOException("offline again")
                }
                emit("id: recovered\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            monotonicMillis = { testScheduler.currentTime }, jitterFn = { it },
            delayFn = { delays += it; delay(it) })
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(listOf(1_000L, 2_000L, 1_000L), delays)
    }

    @Test fun disabledReconnectAttemptsTheSelectedTransportOnlyOnce() = runTest {
        var attempts = 0
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { attempts++; throw IOException("offline") }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            delayFn = { error("must not schedule retry") })
        assertTrue(client.events(autoReconnect = false).toList().isEmpty())
        assertEquals(1, attempts)
    }

    @Test fun tlsAndForbiddenWebSocketFailuresCannotDowngradeOrScheduleRetries() = runTest {
        listOf(javax.net.ssl.SSLHandshakeException("test certificate"), EventStreamForbiddenException()).forEach { rejected ->
            val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
                override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                    onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow { throw rejected }
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = error("security cannot downgrade")
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                webSocketTransport = ws, delayFn = { error("security cannot retry") })
            val failure = runCatching { client.events().toList() }.exceptionOrNull()
            assertEquals(rejected.javaClass, failure?.javaClass)
        }
    }

    @Test fun unsupportedUpgradeStatusesUseTheAuthenticatedSseFallback() = runTest {
        listOf(404, 405, 426, 501).forEach { status ->
            val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
                override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                    onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow { throw GatewayWebSocketHandshakeException(status) }
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                    assertTrue(request.headers.any { it.name == "Authorization" && it.value == "Bearer test" })
                    emit("id: fallback_$status\ndata: {}\n\n".toByteArray())
                }
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(), webSocketTransport = ws)
            assertEquals("fallback_$status", client.events(autoReconnect = false).toList().single().id)
        }
    }

    @Test fun aRejectedProbeSharesOneRenewalAndCannotSpendItAgainOnHandshakeReadiness() = runTest {
        var refreshes = 0
        var sseClosed = false
        val attempted = mutableListOf<String>()
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
            refreshes++
            it.copy(sessionId = "renewed", accessToken = "new")
        }
        val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow { throw GatewayWebSocketHandshakeException(426) }
            override suspend fun probe(sessionSnapshot: GatewayProfile?): GatewayProfile {
                attempted += requireNotNull(sessionSnapshot).sessionId
                throw EventSessionRejectedException(sessionSnapshot)
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                try { while (true) { emit(": heartbeat\n\n".toByteArray()); delay(15_000) } }
                finally { sseClosed = true }
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            webSocketTransport = ws, sessionRenewal = renewal, jitterFn = { it }, monotonicMillis = { testScheduler.currentTime })
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(listOf("session", "renewed"), attempted)
        assertEquals(1, refreshes)
        assertTrue(sseClosed)
    }

    @Test fun retryDiagnosticsIdentifyTransportAndStageWithoutPeerMessagesOrCursor() = runTest {
        val oldSink = com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink
        val logs = mutableListOf<String>()
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { _, line -> logs += line }
        try {
            val cursors = InMemoryEventCursorStore().apply { save("account", "secret_cursor") }
            var attempts = 0
            val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
                override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                    onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow {
                    onSignal(GatewayWebSocketSignal.Ready(testScheduler.currentTime))
                    if (++attempts == 1) throw GatewayWebSocketClosedException(1011)
                    emit(GatewayEvent("recovered", "notice", "{}"))
                }
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, cursors,
                webSocketTransport = ws, delayFn = {}, jitterFn = { it }, monotonicMillis = { testScheduler.currentTime })
            client.events().take(1).toList()
            assertTrue(logs.any { it.contains("transport=WS") && it.contains("stage=CLOSE") &&
                it.contains("closeCode=1011") && it.contains("nextDelayMillis=1000") && it.contains("ownerID=") })
            assertFalse(logs.any { it.contains("secret_cursor") || it.contains("Bearer") || it.contains("Signature:") })
        } finally { com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = oldSink }
    }


    @Test fun injectedJitterIsClampedToTwentyPercentAndSixtySeconds() = runTest {
        listOf(
            Long.MIN_VALUE to listOf(800L, 1_600L, 4_000L, 8_000L, 16_000L, 32_000L, 48_000L),
            Long.MAX_VALUE to listOf(1_200L, 2_400L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L),
        ).forEach { (jitter, expected) ->
            var attempts = 0
            val delays = mutableListOf<Long>()
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                    if (++attempts <= 7) throw IOException("offline")
                    emit("id: recovered\ndata: {}\n\n".toByteArray())
                }
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                jitterFn = { jitter }, delayFn = { delays += it })
            client.events().take(1).toList()
            assertEquals(expected, delays)
        }
    }

    @Test fun lateCallbacksFromAnOldCollectionCannotOverwriteTheNewOwnersFailure() = runTest {
        val sink = EventStreamStatusSink()
        val started = CompletableDeferred<Unit>()
        lateinit var lateSignal: (GatewayWebSocketSignal) -> Unit
        var attempts = 0
        val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow {
                if (++attempts == 1) {
                    lateSignal = onSignal
                    started.complete(Unit)
                    awaitCancellation()
                }
                throw EventStreamForbiddenException()
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            webSocketTransport = ws, statusSink = sink)
        val old = launch { client.events().collect() }
        started.await()
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventStreamForbiddenException)
        lateSignal(GatewayWebSocketSignal.Ready(testScheduler.currentTime))
        assertEquals(EventStreamStatus.FAILED, sink.status.value)
        old.cancelAndJoin()
    }


    @Test fun aProbeResultFromAnOlderSessionCannotStartTheFormalWebSocket() = runTest {
        var probes = 0
        var formalSnapshot: GatewayProfile? = null
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
            it.copy(sessionId = "renewed", accessToken = "new")
        }
        val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow {
                if (probes == 0) throw GatewayWebSocketHandshakeException(426)
                formalSnapshot = sessionSnapshot
                emit(GatewayEvent("from_ws", "notice", "{}"))
            }
            override suspend fun probe(sessionSnapshot: GatewayProfile?): GatewayProfile {
                probes++
                if (probes == 1) renewal.refresh(requireNotNull(sessionSnapshot))
                return requireNotNull(sessionSnapshot)
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                while (true) { emit(": heartbeat\n\n".toByteArray()); delay(15_000) }
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            webSocketTransport = ws, sessionRenewal = renewal, jitterFn = { it }, monotonicMillis = { testScheduler.currentTime })
        assertEquals("from_ws", client.events().take(1).toList().single().id)
        assertEquals(2, probes)
        assertEquals(renewal.current, formalSnapshot)
    }


    @Test fun formalWebSocket503AfterARecoveryProbeResumesSseAtItsCommittedCursor() = runTest {
        val order = mutableListOf<String>()
        val cursors = InMemoryEventCursorStore()
        var wsAttempts = 0
        var sseAttempts = 0
        val ws = object : GatewayWebSocketTransport(profile, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (GatewayWebSocketSignal) -> Unit): Flow<GatewayEvent> = flow {
                wsAttempts++
                if (wsAttempts == 1) throw GatewayWebSocketHandshakeException(426)
                order += "formal-ws:$cursor"
                throw GatewayWebSocketHandshakeException(503)
            }
            override suspend fun probe(sessionSnapshot: GatewayProfile?): GatewayProfile {
                order += "probe-closed"
                return requireNotNull(sessionSnapshot)
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                sseAttempts++
                if (sseAttempts == 1) {
                    try {
                        emit("id: before_handoff\ndata: {}\n\n".toByteArray())
                        while (true) { emit(": heartbeat\n\n".toByteArray()); delay(15_000) }
                    } finally { order += "sse-closed" }
                } else {
                    assertTrue(request.target.endsWith("?cursor=before_handoff"))
                    order += "sse-resumed"
                    emit("id: after_handoff\ndata: {}\n\n".toByteArray())
                }
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, cursors,
            webSocketTransport = ws, jitterFn = { it }, monotonicMillis = { testScheduler.currentTime },
            delayFn = { error("503 upgrade must resume SSE without a WS-only retry") })
        assertEquals(listOf("before_handoff", "after_handoff"), client.events().take(2).toList().map { it.id })
        assertEquals(listOf("probe-closed", "sse-closed", "formal-ws:before_handoff", "sse-resumed"), order)
        assertEquals(2, wsAttempts)
        assertEquals(2, sseAttempts)
    }


    @Test fun expiredCursorSnapshotNetworkFailuresRetryWithinTheSameSubscription() = runTest {
        val cursors = InMemoryEventCursorStore().apply { save("account", "last_committed") }
        var streams = 0
        var rebuilds = 0
        val delays = mutableListOf<Long>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                streams++
                if (streams == 1) throw EventCursorExpiredException()
                assertTrue(request.target.endsWith("?cursor=snapshot_baseline"))
                emit("id: recovered\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, cursors,
            jitterFn = { it }, monotonicMillis = { testScheduler.currentTime }, delayFn = { delays += it; delay(it) })
        client.setCursorRecovery {
            rebuilds++
            assertEquals("last_committed", cursors.load("account"))
            if (rebuilds <= 2) throw IOException("snapshot network unavailable")
            "snapshot_baseline"
        }
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(3, rebuilds)
        assertEquals(2, streams)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals("snapshot_baseline", cursors.load("account"))
    }


    @Test fun temporaryRenewalFailuresUseTheExistingBackoffWithoutRecollecting() = runTest {
        listOf<Throwable>(
            IOException("temporary network failure"),
            com.openandroidintelligence.gateway.auth.GatewayAuthException("REFRESH_FAILED", "UNAVAILABLE", 503, true),
        ).forEach { temporary ->
            var refreshes = 0
            val delays = mutableListOf<Long>()
            val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
                if (++refreshes <= 2) throw temporary
                it.copy(sessionId = "renewed", accessToken = "new")
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                    if (request.headers.any { it.name == "Authorization" && it.value == "Bearer test" }) throw EventSessionRejectedException()
                    emit("id: renewed\ndata: {}\n\n".toByteArray())
                }
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                sessionRenewal = renewal, jitterFn = { it }, delayFn = { delays += it })
            assertEquals("renewed", client.events().take(1).toList().single().id)
            assertEquals(3, refreshes)
            assertEquals(listOf(1_000L, 2_000L), delays)
        }
    }

    @Test fun unknownRenewalOutcomeCredentialRevocationAndTlsAreTerminalBeforeNetworkRetry() = runTest {
        listOf<Throwable>(
            com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException("REFRESH_OUTCOME_UNKNOWN"),
            com.openandroidintelligence.gateway.auth.GatewayAuthException("REFRESH_FAILED", "SESSION_REVOKED", 503, true),
            javax.net.ssl.SSLHandshakeException("test identity"),
        ).forEach { terminal ->
            var refreshes = 0
            val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
                refreshes++
                throw terminal
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw EventSessionRejectedException() }
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                sessionRenewal = renewal, delayFn = { error("terminal renewal cannot retry") })
            assertEquals(terminal.javaClass, runCatching { client.events().toList() }.exceptionOrNull()?.javaClass)
            assertEquals(1, refreshes)
        }
    }

    @Test fun snapshotPersistenceAndProtocolErrorsPreserveTheOldCursorAndStop() = runTest {
        listOf("bad baseline", "valid_baseline").forEach { baseline ->
            val cursors = object : EventCursorStore {
                var value = "old_cursor"
                override fun load(accountId: String) = value
                override fun save(accountId: String, cursor: String) { throw IOException("storage failed") }
                override fun clear(accountId: String) { error("must preserve old cursor") }
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw EventCursorExpiredException() }
            }
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, cursors,
                delayFn = { error("persistence and protocol failures cannot become network retries") })
            client.setCursorRecovery { baseline }
            val failure = runCatching { client.events().toList() }.exceptionOrNull()
            if (baseline == "valid_baseline") assertEquals("storage failed", failure?.message)
            else assertTrue(failure is IllegalArgumentException)
            assertEquals("old_cursor", cursors.load("account"))
        }
    }

    @Test fun snapshotHttp401UsesTheSharedEventRenewalFenceAndThenResumesTheStream() = runTest {
        var refreshes = 0
        var streams = 0
        val auth = mutableListOf<String>()
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
            refreshes++
            it.copy(sessionId = "renewed", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val bearer = request.headers.single { it.name == "Authorization" }.value
                auth += bearer
                return WireResponse(if (bearer == "Bearer test") 401 else 200, emptyList(), byteArrayOf())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                if (++streams == 1) throw EventCursorExpiredException()
                assertTrue(request.target.endsWith("?cursor=baseline"))
                emit("id: recovered\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        client.setCursorRecovery {
            val response = client.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot"))
            check(response.status == 200)
            "baseline"
        }
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(listOf("Bearer test", "Bearer new"), auth)
        assertEquals(1, refreshes)
    }


    @Test fun snapshotRepeated401DoesNotGetAnIndependentHttpRenewalBudget() = runTest {
        var refreshes = 0
        var requests = 0
        val renewal = com.openandroidintelligence.gateway.auth.GatewaySessionRenewal(profile, backgroundScope) {
            refreshes++
            it.copy(sessionId = "renewed", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests++
                return WireResponse(401, emptyList(), byteArrayOf())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw EventCursorExpiredException() }
        }
        val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            sessionRenewal = renewal, delayFn = { error("rejected credentials cannot become network retry") })
        client.setCursorRecovery {
            client.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot"))
            "baseline"
        }
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(1, refreshes)
        assertEquals(2, requests)
    }

}
