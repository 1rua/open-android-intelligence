package com.openandroidintelligence.network

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** 真实TLS隔离服务器覆盖通用下载边界，不依赖旧协议、账号或签名请求。 */
class HttpsResourceClientTest {
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private fun client(trust: Boolean = true): HttpsResourceClient {
        val trustStore = HandshakeCertificates.Builder().apply { if (trust) addTrustedCertificate(certificate.certificate) }.build()
        return HttpsResourceClient(OkHttpClient.Builder().sslSocketFactory(trustStore.sslSocketFactory(), trustStore.trustManager)
            .cookieJar(CookieJar.NO_COOKIES).build())
    }
    private suspend fun server(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        val keys = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(keys.sslSocketFactory(), false)
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
    private fun pin(): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(certificate.certificate.publicKey.encoded)
        .joinToString("") { "%02x".format(it) }

    @Test fun `真实TLS与匹配SPKI成功且不自动携带账号凭据`() = runBlocking {
        server { server ->
            server.enqueue(MockResponse().setBody("public-package").addHeader("Set-Cookie", "server-secret=one"))
            server.enqueue(MockResponse().setBody("next"))
            val client = client()
            assertEquals("public-package", client.exchange(server.url("/package").toString(), pins = setOf(pin())).body.decodeToString())
            client.exchange(server.url("/next").toString())
            repeat(2) {
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                for (header in listOf("Authorization", "Cookie", "Proxy-Authorization", "X-OAI-Signature")) assertNull(request.getHeader(header))
            }
        }
    }
    @Test fun `证书不受信任主机名不匹配与pin错误分别拒绝`() = runBlocking {
        server { server ->
            server.enqueue(MockResponse().setBody("never"))
            assertTrue(runCatching { client(false).exchange(server.url("/").toString()) }.isFailure)
            server.enqueue(MockResponse().setBody("never"))
            assertTrue(runCatching { client().exchange(server.url("/").newBuilder().host("127.0.0.1").build().toString()) }.isFailure)
            server.enqueue(MockResponse().setBody("never"))
            assertTrue(runCatching { client().exchange(server.url("/").toString(), pins = setOf("sha256:" + "0".repeat(64))) }.isFailure)
        }
    }
    @Test fun `重定向不跟随且大小声明与实际分块均受限`() = runBlocking {
        server { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://elsewhere.example/private"))
            val redirect = client().exchange(server.url("/").toString())
            assertEquals(302, redirect.status)
            assertEquals("https://elsewhere.example/private", redirect.headers.single { it.first.equals("Location", true) }.second)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("too-large"))
            assertEquals("HTTPS_RESPONSE_TOO_LARGE", runCatching { client().exchange(server.url("/").toString(), maximumBytes = 3) }.exceptionOrNull()?.message)
            server.enqueue(MockResponse().setChunkedBody("overflow", 2))
            assertEquals("HTTPS_RESPONSE_TOO_LARGE", runCatching { client().exchange(server.url("/").toString(), maximumBytes = 3) }.exceptionOrNull()?.message)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun `未修改状态和响应头交给真实代理逐跳裁决且跨host不泄漏凭据`() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("first.example")
            .addSubjectAlternativeName("first.example").addSubjectAlternativeName("second.example").build()
        val keys = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(keys.sslSocketFactory(), false)
        server.start()
        try {
            val client = HttpsResourceClient(OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> =
                        listOf(java.net.InetAddress.getByName("127.0.0.1"))
                }).build())
            val transport = object : com.openandroidintelligence.kernel.MediatedTransport {
                override fun exchange(request: com.openandroidintelligence.kernel.MediatedRequest) = runBlocking {
                    // 仅把策略的标准HTTPS端口映射到测试服务器；TLS仍验证每跳真实主机名。
                    val result = client.exchange("https://${request.host}:${server.port}${request.pathAndQuery}",
                        request.method, request.headers.toList(), request.body ?: ByteArray(0), maximumBytes = request.maximumResponseBytes)
                    com.openandroidintelligence.kernel.MediatedResponse(result.status, result.headers.toMap(), result.body)
                }
            }
            fun proxy(hosts: Set<String>) = com.openandroidintelligence.kernel.MediatedNetworkProxy(
                com.openandroidintelligence.kernel.NetworkAllowlist(hosts, setOf("GET")), transport, 1_000_000)
            fun request() = com.openandroidintelligence.kernel.MediatedRequest("https", "first.example", 443, "GET", "/start",
                mapOf("Authorization" to "Bearer plugin-owned", "Cookie" to "plugin-owned=one"), null)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/same-host"))
            server.enqueue(MockResponse().setBody("same"))
            assertEquals("same", proxy(setOf("first.example")).exchange(request()).body.decodeToString())
            assertEquals("/start", server.takeRequest().path)
            val sameHost = server.takeRequest()
            assertEquals("/same-host", sameHost.path)
            assertEquals("Bearer plugin-owned", sameHost.getHeader("Authorization"))

            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://second.example/approved"))
            server.enqueue(MockResponse().setBody("approved"))
            assertEquals("approved", proxy(setOf("first.example", "second.example")).exchange(request()).body.decodeToString())
            server.takeRequest()
            val crossHost = server.takeRequest()
            assertEquals("/approved", crossHost.path)
            for (header in listOf("Authorization", "Cookie", "Proxy-Authorization", "X-OAI-Signature")) assertNull(crossHost.getHeader(header))

            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://outside.example/denied"))
            val failure = runCatching { proxy(setOf("first.example")).exchange(request()) }.exceptionOrNull()
            assertEquals("NETWORK_DENIED:HOST_NOT_ALLOWED", failure?.message)
            assertEquals("/start", server.takeRequest().path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

            server.enqueue(MockResponse().setResponseCode(304).addHeader("ETag", "fixed-version"))
            val unchanged = proxy(setOf("first.example")).exchange(request())
            assertEquals(304, unchanged.status)
            assertEquals("fixed-version", unchanged.headers.entries.single { it.key.equals("ETag", ignoreCase = true) }.value)
            assertEquals(0, unchanged.body.size)
        } finally { server.shutdown() }
    }
    @Test fun `重复响应头和无效pin格式不能静默折叠`() = runBlocking {
        server { server ->
            server.enqueue(MockResponse().setBody("a").addHeader("Content-Type", "text/plain").addHeader("Content-Type", "application/json"))
            assertEquals("HTTPS_RESPONSE_HEADERS_INVALID", runCatching { client().exchange(server.url("/").toString()) }.exceptionOrNull()?.message)
            assertTrue(runCatching { client().exchange(server.url("/").toString(), pins = setOf("sha1:abc")) }.isFailure)
            assertTrue(runCatching { client().exchange(server.url("/").toString(), headers = listOf("X-Test" to "first", "x-test" to "second")) }.isFailure)
            server.enqueue(MockResponse().setBody("pins"))
            assertEquals("pins", client().exchange(server.url("/").toString(), pins = setOf("sha256:" + "0".repeat(64), pin())).body.decodeToString())
        }
    }
    @Test fun `取消关闭在途调用且不接受明文或伪造host头`() = runBlocking {
        server { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async { client().exchange(server.url("/").toString()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(runCatching { client().exchange("http://localhost/") }.isFailure)
            assertTrue(runCatching { client().exchange(server.url("/").toString(), headers = listOf("Host" to "foreign.example")) }.isFailure)
            assertTrue(runCatching { client().exchange(server.url("/").toString(), headers = listOf("X-Test" to "a\r\nb")) }.isFailure)
        }
    }
}
