package com.openandroidintelligence.gateway.ws

import com.openandroidintelligence.gateway.http.GatewayProfile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWebSocketBackpressureTest {
    @Test fun slowMessageDeliveryDoesNotTurnAMatchingPongIntoANetworkFailure(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val replied = AtomicInteger()
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = DataInputStream(socket.getInputStream())
                    val headers = ByteArrayOutputStream()
                    while (!headers.toString("US-ASCII").endsWith("\r\n\r\n")) headers.write(input.readUnsignedByte())
                    val key = Regex("Sec-WebSocket-Key: ([^\r\n]+)").find(headers.toString("US-ASCII"))!!.groupValues[1]
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${GatewayWebSocketTransport.computeSecWebSocketAccept(key)}\r\n\r\n").toByteArray())
                    // 超过 Flow 的默认缓冲，真实读取者会在本地投递处暂停。
                    repeat(80) { index ->
                        val payload = """{"id":"event$index","event":"gateway.notice","data":{}}""".toByteArray()
                        output.write(byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload)
                    }
                    output.flush()
                    while (input.read() >= 0) {
                        val size = input.readUnsignedByte() and 127
                        val mask = ByteArray(4).also(input::readFully)
                        val nonce = ByteArray(size).also(input::readFully)
                        nonce.indices.forEach { nonce[it] = (nonce[it].toInt() xor mask[it % 4].toInt()).toByte() }
                        output.write(byteArrayOf(0x8A.toByte(), size.toByte()) + nonce)
                        output.flush()
                        replied.incrementAndGet()
                    }
                }
            }
            val received = CompletableDeferred<Unit>()
            val ws = GatewayWebSocketTransport(
                GatewayProfile("account", "device", "session", "http://127.0.0.1:${server.localPort}", accessToken = "test"),
                { ByteArray(64) }, healthCheckTimeoutMillis = 300,
            )
            ws.setAppForeground(true)
            var delivered = 0
            val collector = async {
                ws.events().collect {
                    if (++delivered == 1) delay(1_000)
                    if (delivered == 80) received.complete(Unit)
                }
            }
            try {
                withTimeout(2_500) { received.await() }
                assertEquals(80, delivered)
                assertTrue("本地缓冲阻塞时，健康连接仍应保留", collector.isActive)
                assertTrue("服务器确实已回复控制 Pong", replied.get() > 0)
            } finally { collector.cancelAndJoin() }
            peer.get(2, TimeUnit.SECONDS)
        }
    }
}
