package com.openandroidintelligence.mobile

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** 一条到达回环 Gateway 的请求，按 HTTP 层原样记录。 */
internal data class RecordedGatewayRequest(
    val method: String,
    val target: String,
    val body: String,
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
    private val recorded = CopyOnWriteArrayList<RecordedGatewayRequest>()

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** 已到达 server 的请求（按时间顺序）。 */
    val requests: List<RecordedGatewayRequest> get() = recorded.toList()

    init {
        thread(isDaemon = true, name = "loopback-gateway") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { handle(socket) }
            }
        }
    }

    /** 注册一个路径的响应；未注册的路径返回 404。 */
    fun respond(path: String, body: String, status: Int = 200) {
        routes[path] = StubResponse(status, body)
    }

    fun targetsOf(method: String): List<String> =
        requests.filter { it.method == method }.map { it.target }

    fun closed() = server.close()

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
            recorded += RecordedGatewayRequest(method, target, String(body, 0, read))

            val path = target.substringBefore('?')
            val response = routes[path] ?: StubResponse(404, """{"error":{"code":"NOT_FOUND"}}""")
            write(open, response)
        }
    }

    private fun write(socket: Socket, response: StubResponse) {
        val payload = response.body.toByteArray(Charsets.UTF_8)
        val reason = if (response.status in 200..299) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(payload)
            flush()
        }
    }

    private data class StubResponse(val status: Int, val body: String)
}
