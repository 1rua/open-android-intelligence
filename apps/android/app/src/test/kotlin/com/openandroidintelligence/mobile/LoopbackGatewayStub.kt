package com.openandroidintelligence.mobile

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 一条到达回环 Gateway 的请求，按 HTTP 层原样记录。 */
internal data class RecordedGatewayRequest(
    val method: String,
    val target: String,
    val body: String,
    val headers: List<String> = emptyList(),
)

/**
 * 回环 Gateway 桩：真实 TCP socket 上手工写 HTTP 帧。
 *
 * 目的在于验证「App 真的把请求发到了契约规定的地址」，而不是只调用了某个内部
 * 方法。它只做 HTTP 收发，不含任何协议判断——凡是需要被验证的协议语义，都由
 * 被测代码与真实 Gateway 契约共同决定。
 */
internal class LoopbackGatewayStub {

    private val server = ServerSocket(0)
    private val routes = ConcurrentHashMap<String, StubResponse>()
    private val responders = ConcurrentHashMap<String, (RecordedGatewayRequest) -> StubResponse>()
    private val recorded = CopyOnWriteArrayList<RecordedGatewayRequest>()
    private val streamReleases = CopyOnWriteArrayList<java.util.concurrent.CountDownLatch>()
    private val controlledStreams = CopyOnWriteArrayList<ControlledEventStream>()

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** 已到达 server 的请求（按时间顺序）。 */
    val requests: List<RecordedGatewayRequest> get() = recorded.toList()

    init {
        thread(isDaemon = true, name = "loopback-gateway") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "loopback-gateway-request") {
                    runCatching { handle(socket) }
                }
            }
        }
    }

    /** 注册一个路径的响应；未注册的路径返回 404。SSE 路径需自定义 Content-Type。 */
    fun respond(path: String, body: String, status: Int = 200, contentType: String = "application/json", headers: Map<String, String> = emptyMap()) {
        responders.remove(path)
        routes[path] = StubResponse(status, body, contentType, headers = headers)
    }

    fun respond(path: String, contentType: String = "application/json", handler: (RecordedGatewayRequest) -> Pair<Int, String>) {
        responders[path] = { request ->
            val (status, body) = handler(request)
            StubResponse(status, body, contentType)
        }
    }

    /** 暂停一个响应，供取消测试在请求已到达后确定性地中断客户端协程。 */
    fun holdResponse(path: String, body: String, status: Int = 200): java.util.concurrent.CountDownLatch {
        val release = java.util.concurrent.CountDownLatch(1)
        routes[path] = StubResponse(status, body, "application/json", release)
        return release
    }

    fun targetsOf(method: String): List<String> =
        requests.filter { it.method == method }.map { it.target }

    /** Real chunked SSE whose lifetime the test controls after headers arrive. */
    fun openEventStream(path: String): java.util.concurrent.CountDownLatch {
        val release = java.util.concurrent.CountDownLatch(1)
        streamReleases += release
        responders.remove(path)
        routes[path] = StubResponse(200, ": heartbeat\n\n", "text/event-stream", streamRelease = release)
        return release
    }

    /** 心跳停止只影响当前连接；重连后的 socket 继续正常提供心跳。 */
    fun controllableEventStream(path: String): ControlledEventStream {
        val stream = ControlledEventStream()
        controlledStreams += stream
        responders.remove(path)
        routes[path] = StubResponse(200, "", "text/event-stream", controlledStream = stream)
        return stream
    }

    internal class ControlledEventStream {
        private val release = java.util.concurrent.CountDownLatch(1)
        private val opened = java.util.concurrent.atomic.AtomicInteger()
        private val silencedThrough = java.util.concurrent.atomic.AtomicInteger()
        val heartbeatCount = java.util.concurrent.atomic.AtomicInteger()

        val connectionCount: Int get() = opened.get()

        fun pauseCurrentConnections() { silencedThrough.set(opened.get()) }
        fun close() { release.countDown() }

        fun write(socket: Socket) {
            val connection = opened.incrementAndGet()
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n".toByteArray())
            output.flush()
            while (release.count != 0L) {
                if (connection > silencedThrough.get()) {
                    val heartbeat = ": heartbeat\n\n".toByteArray()
                    output.write((heartbeat.size.toString(16) + "\r\n").toByteArray())
                    output.write(heartbeat)
                    output.write("\r\n".toByteArray())
                    output.flush()
                    heartbeatCount.incrementAndGet()
                }
                if (release.await(100, TimeUnit.MILLISECONDS)) break
            }
            output.write("0\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    fun closed() {
        controlledStreams.forEach { it.close() }
        streamReleases.forEach { it.countDown() }
        server.close()
    }

    private fun handle(socket: Socket) {
        socket.use { open ->
            open.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(open.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            val contentLength = headers
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val chunk = reader.read(body, read, contentLength - read)
                if (chunk < 0) break
                read += chunk
            }
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val method = requestLine.substringBefore(' ')
            val request = RecordedGatewayRequest(method, target, String(body, 0, read), headers)
            recorded += request

            val path = target.substringBefore('?')
            val response = responders[path]?.invoke(request) ?: routes[path]
                ?: StubResponse(404, """{"error":{"code":"NOT_FOUND"}}""", "application/json")
            check(response.release?.await(10, TimeUnit.SECONDS) != false) { "回环响应等待释放超时" }
            write(open, response)
        }
    }

    private fun write(socket: Socket, response: StubResponse) {
        if (response.status == 0) return // Commit succeeded, but the HTTP response was lost.
        response.controlledStream?.let { it.write(socket); return }
        response.streamRelease?.let { release ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                val heartbeat = response.body.toByteArray(Charsets.UTF_8)
                write((heartbeat.size.toString(16) + "\r\n").toByteArray())
                write(heartbeat)
                write("\r\n".toByteArray())
                flush()
                check(release.await(20, TimeUnit.SECONDS)) { "SSE test owner was not released" }
                write("0\r\n\r\n".toByteArray())
                flush()
            }
            return
        }
        val payload = response.body.toByteArray(Charsets.UTF_8)
        val reason = if (response.status in 200..299) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${payload.size}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(payload)
            flush()
        }
    }

    private data class StubResponse(
        val status: Int,
        val body: String,
        val contentType: String,
        val release: java.util.concurrent.CountDownLatch? = null,
        val headers: Map<String, String> = emptyMap(),
        val streamRelease: java.util.concurrent.CountDownLatch? = null,
        val controlledStream: ControlledEventStream? = null,
    )
}

/**
 * One-request binary capture used by streaming transport tests. The capture
 * keeps only request headers and a rolling digest; its working buffer is fixed
 * at 64 KiB regardless of the body length.
 */
internal data class RecordedStreamingGatewayRequest(
    val headers: List<String>,
    val contentLength: Long,
    val bodyLength: Long,
    val bodySha256Hex: String,
)

internal class LoopbackStreamingGatewayStub : Closeable {
    private val server = ServerSocket(0)
    private val result = CompletableFuture<RecordedStreamingGatewayRequest>()

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "loopback-streaming-gateway") {
            runCatching { serveOne() }
                .onFailure(result::completeExceptionally)
        }
    }

    fun awaitRequest(): RecordedStreamingGatewayRequest = result.get(2, TimeUnit.MINUTES)

    override fun close() {
        server.close()
    }

    private fun serveOne() {
        server.accept().use { socket ->
            socket.soTimeout = 60_000
            val input = socket.getInputStream()
            readAsciiLine(input) // request line
            val headers = generateSequence { readAsciiLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            val lengthHeaders = headers.filter { it.startsWith("Content-Length:", ignoreCase = true) }
            val contentLength = lengthHeaders.single().substringAfter(':').trim().toLong()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < contentLength) {
                val wanted = minOf(buffer.size.toLong(), contentLength - total).toInt()
                val count = input.read(buffer, 0, wanted)
                if (count < 0) throw java.io.EOFException("short request body")
                digest.update(buffer, 0, count)
                total += count
            }
            result.complete(
                RecordedStreamingGatewayRequest(
                    headers = headers,
                    contentLength = contentLength,
                    bodyLength = total,
                    bodySha256Hex = digest.digest().toHex(),
                ),
            )
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                flush()
            }
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII)
            if (value == '\n'.code) {
                val bytes = line.toByteArray()
                val length = bytes.size - if (bytes.lastOrNull() == '\r'.code.toByte()) 1 else 0
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            line.write(value)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
