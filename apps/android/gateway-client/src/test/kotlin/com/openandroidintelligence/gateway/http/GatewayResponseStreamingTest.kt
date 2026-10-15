package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GatewayResponseStreamingTest {
    @Test fun largeOriginalIsConsumedBeforeTheServerFinishesItsBody(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val prefixConsumed = CompletableFuture<Unit>()
            val serverDone = CompletableFuture<Unit>()
            val size = 33L * 1024 * 1024
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: $size\r\n" +
                            "Content-Type: application/json\r\nX-Open-Android-Intelligence-Media-Format: stream-v1\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(byteArrayOf(1))
                        output.flush()
                        // 消费者先收到第一个字节，服务器才继续发送，整文件预读会在这里失败。
                        prefixConsumed.get(10, TimeUnit.SECONDS)
                        val buffer = ByteArray(64 * 1024) { 1 }
                        var left = size - 1
                        while (left > 0) {
                            val count = minOf(buffer.size.toLong(), left).toInt()
                            output.write(buffer, 0, count)
                            left -= count
                        }
                        output.flush()
                    }
                    serverDone.complete(Unit)
                } catch (cause: Throwable) { serverDone.completeExceptionally(cause) }
            }
            val profile = GatewayProfile("acct_stream", "dev_stream", "sess_stream", "http://127.0.0.1:${server.localPort}")
            val count = GatewayTransport(profile).consumeResponse(WireRequest("GET", "/open-android-intelligence/v2/conversations/conv/attachments/media/content?format=stream-v1")) { status, headers, input ->
                assertEquals(200, status)
                assertEquals("stream-v1", RawHeaders.single(headers, "X-Open-Android-Intelligence-Media-Format"))
                assertEquals(1, input.read())
                prefixConsumed.complete(Unit)
                val buffer = ByteArray(64 * 1024)
                var total = 1L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    for (i in 0 until read) assertTrue(buffer[i] == 1.toByte())
                    total += read
                }
                total
            }
            assertEquals(size, count)
            serverDone.get(10, TimeUnit.SECONDS)
        }
    }
}
