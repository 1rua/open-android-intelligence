package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 保持两条真实 HTTP 读取连接未结束；普通创建仍必须独立取得权威回执。 */
class GatewayTransportConcurrencyTest {
    @Test fun heldStreamsDoNotSerializeCreateAndEveryTransportBoundaryHasItsOwnClock() = runBlocking {
        val server = ServerSocket(0)
        val release = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val workers = CopyOnWriteArrayList<Thread>()
        val accepting = thread(isDaemon=true, name="transport-concurrency-server") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers += thread(isDaemon=true) { try { serve(socket, release) } catch (failure: Throwable) { failures += failure } }
            }
        }
        val oldEnabled = GatewayLog.protocolEvidenceEnabled
        val oldSink = GatewayLog.sink
        val evidence = CopyOnWriteArrayList<String>()
        GatewayLog.protocolEvidenceEnabled = true
        GatewayLog.sink = { _, line -> evidence += line }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val profile = GatewayProfile("account", "phone", "login", "http://127.0.0.1:${server.localPort}", accessToken="synthetic")
        val transport = GatewayTransport(profile)
        val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore())
        val streamRead = CompletableDeferred<Unit>()
        val responseRead = CompletableDeferred<Unit>()
        val trace = "00-11111111111111111111111111111111-2222222222222222-00"
        val streaming = owner.launch {
            transport.eventStream(WireRequest("GET", "/open-android-intelligence/v2/events", listOf(
                RawHeader("X-Open-Android-Intelligence-Request-Id", "stream"), RawHeader("traceparent",trace)), ByteArray(0))).collect {
                streamRead.complete(Unit)
            }
        }
        val consuming = owner.launch {
            http.consumeResponse(SignedGatewayRequest("GET", "/open-android-intelligence/v2/held", requestId="held", headers=listOf(RawHeader("traceparent",trace)))) { _, _, input ->
                val buffer = ByteArray(64)
                if (input.read(buffer) > 0) responseRead.complete(Unit)
                while (input.read(buffer) >= 0) { /* 仅等待服务端主动释放真实连接。 */ }
            }
        }
        try {
            withTimeout(5000) { streamRead.await(); responseRead.await() }
            val agent = CoreAgent("hermes", "test", "hermes", true, "account", AgentCapabilities(create=true))
            val result = withTimeout(5000) { AgentSessionClient(http, traceEnabled=true).create(agent, requestId="create") }
            assertEquals("authoritative-session", result.id)
            assertTrue(streaming.isActive)
            assertTrue(consuming.isActive)
            val events = evidence.mapNotNull { runCatching { JsonFields.obj(Json.parse(it)) }.getOrNull() }
            for ((id,mode) in listOf("stream" to "eventStream", "held" to "consumeResponse", "create" to "execute")) {
                val phases = events.filter { JsonFields.string(it,"requestId") == id && JsonFields.string(it,"mode") == mode }
                val types = phases.map { JsonFields.string(it,"type") }
                val expected = listOf("transport.io_queued", "transport.io_dequeued", "transport.connect_started", "transport.connect_ready") +
                    (if (id == "create") listOf("transport.write_started", "transport.write_completed") else emptyList()) +
                    listOf("transport.response_headers", "transport.first_byte")
                assertEquals("每个阶段必须有独立的真实采样", expected, types)
                val clocks = phases.map { requireNotNull(JsonFields.long(it,"monotonicNs")) }
                assertEquals(clocks.sorted(), clocks)
                assertTrue(phases.all { JsonFields.string(it,"traceId")?.length == 32 && JsonFields.string(it,"bootId") != null })
            }
        } finally {
            release.countDown()
            owner.cancel()
            server.close()
            withTimeout(5000) { streaming.join(); consuming.join() }
            workers.forEach { it.join(2000) }
            accepting.join(2000)
            GatewayLog.sink = oldSink
            GatewayLog.protocolEvidenceEnabled = oldEnabled
        }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    private fun serve(socket: Socket, release: CountDownLatch) = socket.use {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val line = requireNotNull(reader.readLine())
        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        val length = headers.firstOrNull { it.startsWith("Content-Length:",true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
        repeat(length) { check(reader.read() >= 0) }
        val output = socket.getOutputStream()
        if (line.contains("/sessions ")) {
            val body = """{"session":{"id":"authoritative-session"}}""".toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body); output.flush()
        } else {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n: heartbeat\n\n".toByteArray())
            output.flush()
            check(release.await(10,TimeUnit.SECONDS)) { "held stream was not released" }
        }
    }
}
