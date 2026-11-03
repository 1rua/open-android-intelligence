package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class GatewayTransportWriteTimeoutTest {
    @Test
    fun progressingWritesMayTakeLongerThanOneTimeoutBudget() = kotlinx.coroutines.runBlocking {
        val server = ServerSocket(0)
        val bodyReceived = CompletableDeferred<Int>()
        val serverThread = thread(isDaemon = true, name = "gateway-progressing-write-server") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    requireNotNull(readAsciiLine(input))
                    val headers = buildList {
                        while (true) {
                            val line = readAsciiLine(input) ?: error("request ended before headers")
                            if (line.isEmpty()) break
                            add(line)
                        }
                    }
                    val contentLength = headers
                        .single { it.startsWith("Content-Length:", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    val body = ByteArray(contentLength)
                    var offset = 0
                    while (offset < body.size) {
                        val read = input.read(body, offset, body.size - offset)
                        check(read > 0) { "request body ended at $offset/${body.size}" }
                        offset += read
                    }
                    bodyReceived.complete(offset)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                        flush()
                    }
                }
            } catch (failure: Throwable) {
                bodyReceived.completeExceptionally(failure)
            }
        }

        val timeoutMillis = 250
        val writeDelayMillis = 100L
        val writeCalls = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val profile = GatewayProfile(
            "account",
            "device",
            "login",
            "http://127.0.0.1:${server.localPort}",
            accessToken = "synthetic",
        )
        val transport = GatewayTransport(
            profile = profile,
            factory = GatewayConnectionFactory { url ->
                ShortTimeoutConnection(
                    url,
                    url.openConnection() as HttpURLConnection,
                    timeoutMillis,
                    writeDelayMillis,
                    writeCalls,
                )
            },
            onHttpFailure = { failures += it },
        )
        val payload = ByteArray(4 * GatewayTransport.STREAM_CHUNK_BYTES) { 0x36 }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val request = WireRequest(
            method = "PUT",
            target = "/open-android-intelligence/v2/attachments/att_progress/content",
            headers = listOf(
                RawHeader("Content-Length", payload.size.toString()),
                RawHeader("Digest", "sha-256=${Base64.getEncoder().encodeToString(digest)}"),
            ),
            streamBody = GatewayRequestBody(
                contentLength = payload.size.toLong(),
                sha256Hex = digest.joinToString("") { "%02x".format(it) },
                openStream = { payload.inputStream() },
            ),
        )

        try {
            val startedAt = System.nanoTime()
            val response = withTimeout(3_000) { transport.execute(request) }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertEquals(200, response.status)
            assertEquals(payload.size, withTimeout(1_000) { bodyReceived.await() })
            assertEquals(4, writeCalls.get())
            assertTrue("total upload time $elapsedMillis ms must exceed $timeoutMillis ms", elapsedMillis > timeoutMillis)
            assertTrue("each write must stay within its budget", writeDelayMillis < timeoutMillis)
            assertTrue(failures.isEmpty())
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun fixedLengthWriteStopsWhenPeerKeepsHeadersOpenWithoutReadingBody() = kotlinx.coroutines.runBlocking {
        assertStalledUploadIsClosed(timeoutMillis = 250, cancelCaller = false)
    }

    @Test
    fun callerCancellationDisconnectsAStalledRealSocketAndKeepsCancellationCause() = kotlinx.coroutines.runBlocking {
        assertStalledUploadIsClosed(timeoutMillis = 5_000, cancelCaller = true)
    }

    private suspend fun assertStalledUploadIsClosed(timeoutMillis: Int, cancelCaller: Boolean) {
        val server = ServerSocket(0).apply { receiveBufferSize = 1024 }
        val headersReceived = CompletableDeferred<Pair<String, List<String>>>()
        val observePeerClose = CountDownLatch(1)
        val peerClosed = CompletableDeferred<Unit>()
        val serverThread = thread(isDaemon = true, name = "gateway-write-timeout-server") {
            try {
                server.accept().use { socket ->
                    socket.receiveBufferSize = 1024
                    val input = socket.getInputStream()
                    val requestLine = readAsciiLine(input) ?: error("missing request line")
                    val headers = buildList {
                        while (true) {
                            val line = readAsciiLine(input) ?: error("request ended before headers")
                            if (line.isEmpty()) break
                            add(line)
                        }
                    }
                    headersReceived.complete(requestLine to headers)
                    check(observePeerClose.await(5, TimeUnit.SECONDS))
                    // A FIN follows bytes already queued by the client. Drain
                    // them in blocks with a normal receive window before
                    // observing EOF; one-byte reads with a tiny TCP window
                    // measure draining speed rather than client cancellation.
                    socket.receiveBufferSize = 256 * 1024
                    socket.soTimeout = 1_000
                    val remaining = ByteArray(64 * 1024)
                    try {
                        while (input.read(remaining) >= 0) Unit
                    } catch (_: java.net.SocketException) {
                        // A reset is also proof that the client closed its request socket.
                        // A read timeout is not proof and must fail the test.
                    }
                    peerClosed.complete(Unit)
                }
            } catch (failure: Throwable) {
                headersReceived.completeExceptionally(failure)
                peerClosed.completeExceptionally(failure)
            }
        }

        val previousEvidenceEnabled = GatewayLog.protocolEvidenceEnabled
        val previousSink = GatewayLog.sink
        val evidence = CopyOnWriteArrayList<String>()
        val reportedFailures = CopyOnWriteArrayList<Throwable>()
        val writeStarted = CompletableDeferred<Unit>()
        GatewayLog.protocolEvidenceEnabled = true
        GatewayLog.sink = { _, line ->
            evidence += line
            if (line.contains("\"type\":\"transport.write_started\"")) writeStarted.complete(Unit)
        }

        val profile = GatewayProfile(
            "account",
            "device",
            "login",
            "http://127.0.0.1:${server.localPort}",
            accessToken = "synthetic",
        )
        val transport = GatewayTransport(
            profile = profile,
            factory = GatewayConnectionFactory { url ->
                ShortTimeoutConnection(url, url.openConnection() as HttpURLConnection, timeoutMillis)
            },
            onHttpFailure = { reportedFailures += it },
        )
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val payload = ByteArray(3_800_000) { 0x5a }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val writtenBytes = AtomicLong(0)
        val lastProgressNs = AtomicLong(System.nanoTime())
        val body = GatewayRequestBody(
            contentLength = payload.size.toLong(),
            sha256Hex = digest.joinToString("") { "%02x".format(it) },
            openStream = { payload.inputStream() },
            onBytesWritten = {
                writtenBytes.set(it)
                lastProgressNs.set(System.nanoTime())
            },
        )
        val request = WireRequest(
            method = "PUT",
            target = "/open-android-intelligence/v2/attachments/att_test/content",
            headers = listOf(
                RawHeader("Content-Length", payload.size.toString()),
                RawHeader("Digest", "sha-256=${Base64.getEncoder().encodeToString(digest)}"),
            ),
            streamBody = body,
        )
        val execution = owner.async { transport.execute(request) }

        try {
            val (requestLine, headers) = withTimeout(2_000) { headersReceived.await() }
            assertTrue(requestLine.startsWith("PUT "))
            assertEquals(
                payload.size.toString(),
                headers.single { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim(),
            )
            withTimeout(1_000) { writeStarted.await() }

            if (cancelCaller) {
                withTimeout(2_000) {
                    while (System.nanoTime() - lastProgressNs.get() < TimeUnit.MILLISECONDS.toNanos(200)) {
                        delay(20)
                    }
                }
                assertTrue("large body must still be incomplete before cancellation", writtenBytes.get() < payload.size)
                val cancellation = CancellationException("cancel stalled attachment write")
                val cancellationStartedAt = System.nanoTime()
                execution.cancel(cancellation)
                withTimeout(1_500) { execution.join() }
                val cancellationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStartedAt)
                assertTrue("cancellation blocked for $cancellationMillis ms", cancellationMillis < 1_500)
                val cause = runCatching { execution.await() }.exceptionOrNull()
                assertTrue("caller cancellation was replaced by $cause", cause is CancellationException)
                assertEquals(cancellation.message, cause?.message)
                assertTrue("caller cancellation must not be reported as a network failure", reportedFailures.isEmpty())
            } else {
                val observationStartedAt = System.nanoTime()
                var operationFailure: Throwable? = null
                val completedWithinBudget = withTimeoutOrNull(1_500) {
                    try {
                        execution.await()
                        true
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        operationFailure = failure
                        true
                    }
                } ?: false

                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observationStartedAt)
                assertTrue(
                    "write must exit within the shortened existing timeout; elapsed=$elapsedMillis ms; evidence=$evidence",
                    completedWithinBudget && elapsedMillis < 1_500,
                )
                // Disconnecting a blocked fixed-length write may surface the
                // socket's IOException before the coroutine timeout exception.
                // The contract is bounded I/O failure with no successful write.
                assertTrue("expected a bounded I/O failure, got $operationFailure", operationFailure is IOException)
                assertTrue("stalled upload must not report all bytes sent", writtenBytes.get() < payload.size)
                assertEquals(1, reportedFailures.size)
                assertTrue(reportedFailures.single() is IOException)
                assertSame(operationFailure, reportedFailures.single())
            }

            assertTrue(hasPhase(evidence, "transport.write_started"))
            assertFalse(hasPhase(evidence, "transport.write_completed"))
            observePeerClose.countDown()
            assertTrue("connection.disconnect() did not close the real socket", withTimeoutOrNull(1_500) {
                peerClosed.await()
                true
            } == true)
        } finally {
            execution.cancel()
            owner.cancel()
            observePeerClose.countDown()
            server.close()
            serverThread.join(1_000)
            GatewayLog.protocolEvidenceEnabled = previousEvidenceEnabled
            GatewayLog.sink = previousSink
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII)
            if (next == '\n'.code) {
                val line = bytes.toByteArray()
                return String(
                    line,
                    0,
                    line.size - if (line.lastOrNull() == '\r'.code.toByte()) 1 else 0,
                    Charsets.US_ASCII,
                )
            }
            bytes.write(next)
        }
    }

    private fun hasPhase(lines: List<String>, phase: String): Boolean =
        lines.any { it.contains("\"type\":\"$phase\"") }

    private class ShortTimeoutConnection(
        url: URL,
        private val delegate: HttpURLConnection,
        private val timeoutMillis: Int,
        private val writeDelayMillis: Long = 0,
        private val writeCalls: AtomicInteger? = null,
    ) : HttpURLConnection(url) {
        override fun connect() = delegate.connect()
        override fun disconnect() = delegate.disconnect()
        override fun usingProxy() = delegate.usingProxy()

        override fun setConnectTimeout(timeout: Int) {
            delegate.connectTimeout = minOf(timeout, timeoutMillis)
        }

        override fun getConnectTimeout() = delegate.connectTimeout

        override fun setReadTimeout(timeout: Int) {
            delegate.readTimeout = minOf(timeout, timeoutMillis)
        }

        override fun getReadTimeout() = delegate.readTimeout

        override fun setRequestMethod(method: String) {
            delegate.requestMethod = method
        }

        override fun getRequestMethod() = delegate.requestMethod

        override fun setDoInput(value: Boolean) {
            delegate.doInput = value
        }

        override fun getDoInput() = delegate.doInput

        override fun setDoOutput(value: Boolean) {
            delegate.doOutput = value
        }

        override fun getDoOutput() = delegate.doOutput

        override fun setInstanceFollowRedirects(value: Boolean) {
            delegate.instanceFollowRedirects = value
        }

        override fun getInstanceFollowRedirects() = delegate.instanceFollowRedirects

        override fun setRequestProperty(key: String, value: String) {
            delegate.setRequestProperty(key, value)
        }

        override fun addRequestProperty(key: String, value: String) {
            delegate.addRequestProperty(key, value)
        }

        override fun getRequestProperties() = delegate.requestProperties

        override fun setFixedLengthStreamingMode(contentLength: Long) {
            delegate.setFixedLengthStreamingMode(contentLength)
        }

        override fun getOutputStream(): OutputStream {
            val output = delegate.outputStream
            if (writeDelayMillis <= 0) return output
            return object : FilterOutputStream(output) {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    Thread.sleep(writeDelayMillis)
                    writeCalls?.incrementAndGet()
                    out.write(buffer, offset, length)
                }

                override fun write(value: Int) {
                    Thread.sleep(writeDelayMillis)
                    writeCalls?.incrementAndGet()
                    out.write(value)
                }
            }
        }
        override fun getResponseCode() = delegate.responseCode
        override fun getHeaderFields() = delegate.headerFields
        override fun getHeaderField(name: String) = delegate.getHeaderField(name)
        override fun getInputStream() = delegate.inputStream
        override fun getErrorStream() = delegate.errorStream
        override fun getContentLengthLong() = delegate.contentLengthLong
    }
}
