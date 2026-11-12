package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewaySessionRecoveryTest {
    private fun profile() = GatewayProfile("account", "device", "session_old", "https://gateway.example", accessToken = "old")
    private fun WireRequest.header(name: String) = headers.single { it.name.equals(name, true) }.value

    @Test fun rejectedResponseReportsTheSignedAttemptInsteadOfTheAlreadyRotatedCredential() = runTest {
        val initial = profile()
        val versions = mutableListOf<String>()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                assertEquals("session_old", request.header("X-Open-Android-Intelligence-Session"))
                renewal.refresh(initial)
                return WireResponse(403, emptyList(), "{}".toByteArray())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("非预期事件流")
        }
        try {
            val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
            assertEquals(403, client.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/test"),
                onCredentialVersion = versions::add).status)
            assertEquals(listOf("session_old"), versions)
            assertEquals("session_new", client.credentialVersion())
        } finally { renewal.close() }
    }

    @Test fun aRefreshedStreamReportsBothActualAttemptsBeforeConsumingItsFinalError() = runTest {
        val initial = profile()
        val versions = mutableListOf<String>()
        val wires = mutableListOf<WireRequest>()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                wires += request
                return WireResponse(if (wires.size == 1) 401 else 200, emptyList(), "error".toByteArray())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("非预期事件流")
        }
        try {
            val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
            val consumed = client.consumeResponse(SignedGatewayRequest("GET", "/open-android-intelligence/v2/test"),
                onCredentialVersion = versions::add) { _, _, stream ->
                assertEquals("session_new", versions.last())
                stream.readBytes().decodeToString()
            }
            assertEquals("error", consumed)
            assertEquals(listOf("session_old", "session_new"), versions)
            assertEquals(versions, wires.map { it.header("X-Open-Android-Intelligence-Session") })
        } finally { renewal.close() }
    }

    @Test fun anAuthorizationGuardFailureDoesNotPublishAnAttemptThatNeverRan() = runTest {
        val versions = mutableListOf<String>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("不应发送")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("不应订阅")
        }
        val client = GatewayHttpClient(profile(), transport, { ByteArray(64) }, InMemoryEventCursorStore())
        val request = SignedGatewayRequest("GET", "/open-android-intelligence/v2/test",
            beforeSend = { error("LOCAL_AUTHORIZATION_CHANGED") })
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", runCatching {
            client.consumeResponse(request, onCredentialVersion = versions::add) { _, _, _ -> Unit }
        }.exceptionOrNull()?.message)
        assertTrue(versions.isEmpty())
    }

    @Test fun deviceResultIsNotReplayedWhenRenewalRevokesTheAuthorizationAgreement() = runTest {
        val initial = profile()
        var allowed = true
        val wires = mutableListOf<WireRequest>()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            allowed = false
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                wires += request
                return WireResponse(401, emptyList(), byteArrayOf())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        val devices = com.openandroidintelligence.gateway.device.HttpDeviceRequestTransport(client) { requestId ->
            assertEquals("req_result", requestId)
            check(allowed) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
        }
        val failure = runCatching { devices.submitResult("req_result", mapOf("result" to "private-result")) }.exceptionOrNull()
        assertEquals("DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED", failure?.message)
        assertEquals(1, wires.size)
        assertEquals("Bearer old", wires.single().header("Authorization"))
    }

    @Test fun responseConsumptionRechecksItsGuardBeforeARefreshReplay() = runTest {
        val initial = profile()
        var allowed = true
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            allowed = false
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                attempts++
                return WireResponse(401, emptyList(), byteArrayOf())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        val failure = runCatching {
            client.consumeResponse(SignedGatewayRequest("GET", "/open-android-intelligence/v2/protected",
                beforeSend = { check(allowed) { "LOCAL_AUTHORIZATION_CHANGED" } })) { _, _, _ -> error("must not consume") }
        }.exceptionOrNull()
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", failure?.message)
        assertEquals(1, attempts)
    }

    @Test fun signingKeepsOneSnapshotWhenAnotherRotationCompletesInsideTheSigner() = kotlinx.coroutines.runBlocking {
        val initial = profile()
        val renewal = GatewaySessionRenewal(initial, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) {
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        var wire: WireRequest? = null
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                wire = request
                return WireResponse(200, emptyList(), byteArrayOf())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        try {
            val client = GatewayHttpClient(initial, transport, { preimage ->
                assertTrue(preimage.decodeToString().contains("session_old"))
                kotlinx.coroutines.runBlocking { renewal.refresh(initial) }
                ByteArray(64)
            }, InMemoryEventCursorStore(), sessionRenewal = renewal)
            client.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/test"))
            assertEquals("Bearer old", requireNotNull(wire).header("Authorization"))
            assertEquals("session_old", requireNotNull(wire).header("X-Open-Android-Intelligence-Session"))
            assertEquals("new", renewal.current.accessToken)
        } finally { renewal.close() }
    }

    @Test fun unauthorizedMutationIsReplayedOnceWithNewSessionAndSameIdempotencyKey() = runTest {
        val wires = mutableListOf<WireRequest>()
        var refreshes = 0
        val initial = profile()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                wires += request
                return WireResponse(if (wires.size == 1) 401 else 200, emptyList(), byteArrayOf())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertEquals(200, client.execute(SignedGatewayRequest("POST", "/open-android-intelligence/v2/mutate", "payload".toByteArray())).status)
        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer old", "Bearer new"), wires.map { it.header("Authorization") })
        assertEquals(listOf("session_old", "session_new"), wires.map { it.header("X-Open-Android-Intelligence-Session") })
        assertEquals(wires[0].header("Idempotency-Key"), wires[1].header("Idempotency-Key"))
        assertNotEquals(wires[0].header("X-Open-Android-Intelligence-Nonce"), wires[1].header("X-Open-Android-Intelligence-Nonce"))
        assertArrayEquals(wires[0].body, wires[1].body)
    }

    @Test fun replayableRequestBodyIsReopenedAfterAuthenticationRenewal() = runTest {
        val initial = profile()
        var opened = 0
        var calls = 0
        val bytes = "payload".toByteArray()
        val measured = GatewayRequestBody.fromBytes(bytes)
        val body = GatewayRequestBody(measured.contentLength, measured.sha256Hex, { opened++; ByteArrayInputStream(bytes) })
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { it.copy(sessionId = "session_new", accessToken = "new") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                request.streamBody!!.openStream().use { assertArrayEquals(bytes, it.readBytes()) }
                calls++
                return WireResponse(if (calls == 1) 401 else 200, emptyList(), "ok".toByteArray())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertEquals(200, client.execute(SignedGatewayRequest("PUT", "/open-android-intelligence/v2/upload", streamBody = body)).status)
        assertEquals(2, opened)
    }

    @Test fun responseConsumerOnlySeesTheFinalResponse() = runTest {
        val initial = profile()
        var calls = 0
        var consumed = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { it.copy(sessionId = "session_new", accessToken = "new") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                calls++
                return WireResponse(if (calls == 1) 401 else 200, emptyList(), "ok".toByteArray())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        val result = client.consumeResponse(SignedGatewayRequest("GET", "/open-android-intelligence/v2/download")) { status, _, input ->
            consumed++
            assertEquals(200, status)
            input.readBytes().decodeToString()
        }
        assertEquals("ok", result)
        assertEquals(1, consumed)
        assertEquals(2, calls)
    }

    @Test fun repeatedUnauthorizedResponseStopsAfterOneRefreshAndIoDoesNotRefresh() = runTest {
        val initial = profile()
        var refreshes = 0
        var calls = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse { calls++; return WireResponse(401, emptyList(), byteArrayOf()) }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertEquals(401, client.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/test")).status)
        assertEquals(2, calls)
        assertEquals(1, refreshes)
        val failing = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = throw IOException("offline")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val failed = GatewayHttpClient(initial, failing, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertTrue(runCatching { failed.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/test")) }.exceptionOrNull() is IOException)
        assertEquals(1, refreshes)
    }

    @Test fun upstreamBridgeAuthenticationFailureDoesNotRefreshSessionOrReplayThePost() = runTest {
        val initial = profile()
        var refreshes = 0
        val wires = mutableListOf<WireRequest>()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            error("an upstream 502 must not refresh Core credentials")
        }
        val responseBody = """{"error":{"code":"AGENT_UNAVAILABLE","details":{"source":"agent_bridge","reason":"authentication_failed","upstreamStatus":401}}}"""
            .toByteArray()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                wires += request
                return WireResponse(502, emptyList(), responseBody)
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("unused")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        val response = client.execute(SignedGatewayRequest(
            "POST", "/open-android-intelligence/v2/agents/hermes/sessions/chat/events/ack", """{"cursor":"base"}""".toByteArray()))

        assertEquals(502, response.status)
        assertArrayEquals(responseBody, response.body)
        assertEquals(1, wires.size)
        assertEquals("POST", wires.single().method)
        assertEquals("Bearer old", wires.single().header("Authorization"))
        assertEquals(0, refreshes)
    }

    @Test fun eventAuthenticationRecoveryIsBoundedUntilRealStreamDataArrives() = runTest {
        val initial = profile()
        var refreshes = 0
        val wires = mutableListOf<WireRequest>()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new", sessionId = "session_new") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                wires += request
                throw EventSessionRejectedException()
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(2, wires.size)
        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer old", "Bearer new"), wires.map { it.header("Authorization") })
    }

    @Test fun repeatedCollectionCannotRestartFailedAuthenticationRotation() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            it.copy(accessToken = "new_$refreshes", sessionId = "session_$refreshes")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                throw EventSessionRejectedException()
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        repeat(3) {
            assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        }
        assertEquals(1, refreshes)
        assertEquals("re-collection must not resend the rejected session", 2, attempts)
        renewal.refresh()
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals("manual rotation allows one connection attempt, not another rotation", 2, refreshes)
        assertEquals(3, attempts)
    }

    @Test fun newEventClientsShareTheUnhealthySessionRecoveryEpisode() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new_$refreshes") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { attempts++; throw EventSessionRejectedException() }
        }
        repeat(3) {
            val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
            assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        }
        assertEquals(1, refreshes)
        assertEquals(4, attempts)
    }

    @Test fun aPausedRecoveryDoesNotResendTheSameSessionOnRecollection() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val paused = com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException("REFRESH_OUTCOME_UNKNOWN")
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; throw paused }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                throw EventSessionRejectedException()
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        repeat(3) {
            val failure = runCatching { client.events().toList() }.exceptionOrNull()
            assertTrue(failure is com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException)
            assertEquals(paused.message, failure?.message)
        }
        assertEquals(1, attempts)
        assertEquals(1, refreshes)
    }

    @Test fun healthySiblingHeartbeatCannotRechargeARejectedClient() = runTest {
        val initial = profile()
        var refreshes = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new_$refreshes") }
        val rejecting = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw EventSessionRejectedException() }
        }
        val broken = GatewayHttpClient(initial, rejecting, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertTrue(runCatching { broken.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        val healthy = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { emit(": heartbeat\n\n".toByteArray()) }
        }
        val sibling = GatewayHttpClient(initial, healthy, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        repeat(3) {
            sibling.events(autoReconnect = false).toList()
            assertTrue(runCatching { broken.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        }
        assertEquals(1, refreshes)
    }

    @Test fun healthySiblingHeartbeatCannotRechargeNewRejectedSseClients() = runTest {
        val initial = profile()
        var refreshes = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            it.copy(accessToken = "new_$refreshes", sessionId = "session_$refreshes")
        }
        val rejecting = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw EventSessionRejectedException() }
        }
        val healthy = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { emit(": heartbeat\n\n".toByteArray()) }
        }
        val sibling = GatewayHttpClient(initial, healthy, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        repeat(3) {
            val broken = GatewayHttpClient(initial, rejecting, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
            assertTrue(runCatching { broken.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
            sibling.events(autoReconnect = false).toList()
        }
        assertEquals("New clients cannot turn sibling heartbeats into fresh rotation attempts", 1, refreshes)
    }

    @Test fun healthySiblingHeartbeatCannotRechargeNewRejectedWebSocketClients() = runTest {
        val initial = profile()
        var refreshes = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            it.copy(accessToken = "new_$refreshes", sessionId = "session_$refreshes")
        }
        val healthy = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { emit(": heartbeat\n\n".toByteArray()) }
        }
        val sibling = GatewayHttpClient(initial, healthy, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        repeat(3) {
            val websocket = object : com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport(initial, { ByteArray(64) }) {
                override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (com.openandroidintelligence.gateway.ws.GatewayWebSocketSignal) -> Unit): Flow<com.openandroidintelligence.gateway.events.GatewayEvent> = flow {
                    throw EventSessionRejectedException(requireNotNull(sessionSnapshot))
                }
            }
            val broken = GatewayHttpClient(initial, healthy, { ByteArray(64) }, InMemoryEventCursorStore(),
                webSocketTransport = websocket, sessionRenewal = renewal)
            assertTrue(runCatching { broken.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
            sibling.events(autoReconnect = false).toList()
        }
        assertEquals(1, refreshes)
    }

    @Test fun anIdleSseHeartbeatReportsLiveOnlyAfterTheWholeFrameAndKeepsTheCursor() = runTest {
        val status = EventStreamStatusSink()
        val cursors = InMemoryEventCursorStore().apply { save("account", "before") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                assertEquals(EventStreamStatus.CONNECTING, status.status.value)
                emit(": heart".toByteArray())
                assertEquals(EventStreamStatus.CONNECTING, status.status.value)
                emit("beat\n".toByteArray())
                assertEquals(EventStreamStatus.CONNECTING, status.status.value)
                emit("\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(profile(), transport, { ByteArray(64) }, cursors, statusSink = status)
        assertTrue(client.events(autoReconnect = false).toList().isEmpty())
        assertEquals(EventStreamStatus.LIVE, status.status.value)
        assertEquals("before", cursors.load("account"))
    }

    @Test fun oneHeartbeatCannotResetTheBackoffBeforeSixtySecondsOfStableActivity() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val status = EventStreamStatusSink()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                if (attempts == 3) emit(": heartbeat\n\n".toByteArray())
                if (attempts < 4) throw IOException("network changed")
                emit("id: recovered\nevent: notice\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(profile(), transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            jitterFn = { it }, delayFn = { delays += it }, maxConsecutiveFailures = 3, statusSink = status)
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(listOf(1_000L, 2_000L, 5_000L), delays)
        assertEquals(EventStreamStatus.LIVE, status.status.value)
    }

    @Test fun cancellingAndRecollectingTheStreamJoinsItsOngoingRotation() = runTest {
        val initial = profile()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var refreshes = 0
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            release.await()
            it.copy(accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { attempts++; throw EventSessionRejectedException() }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        val first = async { runCatching { client.events().toList() } }
        runCurrent()
        first.cancel()
        val second = async { runCatching { client.events().toList() } }
        runCurrent()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertTrue(second.await().exceptionOrNull() is EventSessionRejectedException)
        assertEquals(1, refreshes)
        assertEquals(3, attempts)
    }

    @Test fun failedSharedRotationRetriesInTheSameStream() = runTest {
        val initial = profile()
        var refreshes = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            if (refreshes == 1) throw IOException("response lost")
            it.copy(accessToken = "new")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                if (request.header("Authorization") == "Bearer old") throw EventSessionRejectedException()
                emit("id: recovered\nevent: notice\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            sessionRenewal = renewal, delayFn = {}, jitterFn = { it })
        assertEquals("recovered", client.events().take(1).toList().single().id)
        assertEquals(2, refreshes)
    }

    @Test fun swallowedPlatformEventDoesNotRestoreAuthenticationRecoveryBudget() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new_$refreshes") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                if (attempts in 2..3) emit("event: device.requested\ndata: {}\n\n".toByteArray())
                throw EventSessionRejectedException()
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            handlePlatformEvent = { true }, sessionRenewal = renewal)
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(1, refreshes)
        assertEquals(2, attempts)
    }

    @Test fun partialHeartbeatDoesNotRestoreAuthenticationRecoveryBudget() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                emit(": partial".toByteArray())
                throw EventSessionRejectedException()
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(2, attempts)
        assertEquals(1, refreshes)
    }

    @Test fun websocketAuthenticationRecoveryIsAlsoBounded() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new") }
        val websocket = object : com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport(initial, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (com.openandroidintelligence.gateway.ws.GatewayWebSocketSignal) -> Unit): Flow<com.openandroidintelligence.gateway.events.GatewayEvent> = flow {
                attempts++
                throw EventSessionRejectedException(renewal.current)
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("authentication rejection must not downgrade")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            webSocketTransport = websocket, sessionRenewal = renewal)
        assertTrue(runCatching { client.events().toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(2, attempts)
        assertEquals(1, refreshes)
    }

    @Test fun websocketActivityBelongsToTheSnapshotHandedToItsIoAttempt() = runTest {
        val initial = profile()
        val snapshots = mutableListOf<GatewayProfile>()
        var refreshes = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            refreshes++
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val websocket = object : com.openandroidintelligence.gateway.ws.GatewayWebSocketTransport(initial, { ByteArray(64) }) {
            override fun events(cursor: String?, onActivity: () -> Unit, sessionSnapshot: GatewayProfile?,
                onSignal: (com.openandroidintelligence.gateway.ws.GatewayWebSocketSignal) -> Unit): Flow<com.openandroidintelligence.gateway.events.GatewayEvent> = flow {
                val snapshot = requireNotNull(sessionSnapshot)
                snapshots += snapshot
                if (snapshots.size == 1) {
                    renewal.refresh(initial)
                    // The old handshake can fail after a sibling's rotation.
                    throw EventSessionRejectedException(snapshot)
                }
                onActivity()
                emit(com.openandroidintelligence.gateway.events.GatewayEvent("evt_current", "heartbeat", "{}"))
            }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("no fallback expected")
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            webSocketTransport = websocket, sessionRenewal = renewal)
        assertEquals("evt_current", client.events().take(1).toList().single().id)
        assertEquals(listOf("old", "new"), snapshots.map { it.accessToken })
        assertEquals(1, refreshes)
    }

    @Test fun heartbeatRestoresEventAuthenticationRecoveryBudgetWithoutLosingCursor() = runTest {
        val initial = profile()
        var refreshes = 0
        var attempts = 0
        val cursors = InMemoryEventCursorStore().apply { save("account", "before") }
        val renewal = GatewaySessionRenewal(initial, backgroundScope) { refreshes++; it.copy(accessToken = "new_$refreshes", sessionId = "session_$refreshes") }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                attempts++
                assertTrue(request.target.endsWith("cursor=before"))
                if (attempts == 2) emit(": heartbeat\n\n".toByteArray())
                if (attempts < 3) throw EventSessionRejectedException()
                emit("id: after\nevent: notice\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, cursors, sessionRenewal = renewal)
        assertEquals("after", client.events().take(1).toList().single().id)
        assertEquals(2, refreshes)
    }
    @Test fun eventFailureLogCarriesOnlyKnownAuthenticationMetadata() = runTest {
        assertEquals(1, EventSessionRejectedException::class.java.getConstructor(GatewayProfile::class.java).parameterCount)
        assertEquals(0, EventSessionRejectedException::class.java.getConstructor().parameterCount)
        assertEquals(0, EventStreamForbiddenException::class.java.getConstructor().parameterCount)
        val oldSink = com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink
        val logs = mutableListOf<String>()
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { _, line -> logs += line }
        try {
            val initial = profile()
            val cases = listOf(
                Triple(EventSessionRejectedException(httpStatus = 401), 401, "authentication"),
                Triple(EventStreamForbiddenException(httpStatus = 403), 403, "terminal"),
                Triple(EventSessionRejectedException(), null, "authentication"),
            )
            cases.forEach { (failure, expectedStatus, expectedAction) ->
                logs.clear()
                val transport = object : GatewayByteTransport {
                    override suspend fun execute(request: WireRequest): WireResponse = error("unused")
                    override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw failure }
                }
                val client = GatewayHttpClient(initial, transport, { ByteArray(64) }, InMemoryEventCursorStore())
                assertSame(failure, runCatching { client.events().toList() }.exceptionOrNull())
                val line = logs.single { it.contains("stage=AUTH") }
                assertTrue(line, line.contains("status=${expectedStatus ?: "null"}"))
                assertTrue(line, line.contains("closeCode=null"))
                assertTrue(line, line.contains("action=$expectedAction"))
                assertFalse(line, line.contains("Bearer old"))
            }
        } finally {
            com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = oldSink
        }
    }

    @Test fun sseHandshakePreservesKnownHttpStatus() = kotlinx.coroutines.runBlocking {
        listOf(401, 403).forEach { status ->
            ServerSocket(0).use { server ->
                server.soTimeout = 5_000
                val served = CompletableFuture<Unit>()
                thread(isDaemon = true) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            socket.getOutputStream().write(
                                "HTTP/1.1 $status Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            socket.getOutputStream().flush()
                        }
                        served.complete(Unit)
                    } catch (failure: Throwable) {
                        served.completeExceptionally(failure)
                    }
                }
                val bound = profile().copy(gatewayBaseUrl = "http://127.0.0.1:${server.localPort}")
                val failure = runCatching {
                    GatewayTransport(bound).eventStream(WireRequest("GET", "/open-android-intelligence/v2/events")).toList()
                }.exceptionOrNull()
                served.get(5, TimeUnit.SECONDS)
                if (status == 401) {
                    assertTrue(failure is EventSessionRejectedException)
                    assertEquals(401, (failure as EventSessionRejectedException).httpStatus)
                    assertNull((failure as EventSessionRejectedException).closeCode)
                } else {
                    assertTrue(failure is EventStreamForbiddenException)
                    assertEquals(403, (failure as EventStreamForbiddenException).httpStatus)
                    assertNull((failure as EventStreamForbiddenException).closeCode)
                }
            }
        }
    }

}
