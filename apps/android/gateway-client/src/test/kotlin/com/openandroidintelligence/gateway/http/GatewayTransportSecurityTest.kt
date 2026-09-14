package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * The rules that separate "the user typed a plaintext address" from "a verified
 * identity was silently dropped".
 *
 * The classification and profile rules are decidable before any handshake,
 * which is why a reachable server cannot talk the app into a downgrade. The
 * last test drives the real transport against a loopback plaintext Gateway, so
 * "http pairing works" is evidence from a live socket rather than a claim.
 */
class GatewayTransportSecurityTest {

    @Test
    fun plaintextAddressIsUsableAndReportedAsUnencrypted() {
        val endpoint = GatewayEndpoint.parse("http://192.168.1.20:8080")

        assertNotNull(endpoint)
        assertEquals(GatewayEndpoint.HTTP, endpoint!!.scheme)
        assertFalse(endpoint.isTls)
        assertEquals(TransportSecurity.PLAINTEXT, endpoint.securityFor(emptySet()))
        assertFalse(endpoint.securityFor(emptySet()).isEncrypted)
    }

    @Test
    fun tlsAddressIsPinnedOrSystemTrustedAndNeverPlaintext() {
        val endpoint = GatewayEndpoint.parse("https://gateway.example.com:8443")!!

        assertTrue(endpoint.isTls)
        assertEquals(TransportSecurity.TLS_PINNED, endpoint.securityFor(setOf(PROTOCOL_PIN)))
        assertEquals(TransportSecurity.TLS_SYSTEM_TRUST, endpoint.securityFor(emptySet()))
        assertTrue(endpoint.securityFor(emptySet()).isEncrypted)
    }

    @Test
    fun trailingSlashAndSurroundingSpaceAreNormalizedOnce() {
        val endpoint = GatewayEndpoint.parse("  https://gateway.example.com/  ")

        assertEquals("https://gateway.example.com", endpoint!!.baseUrl)
    }

    @Test
    fun addressesTheTransportCannotCarryAreRefused() {
        assertNull(GatewayEndpoint.parse("ftp://gateway.example.com"))
        assertNull(GatewayEndpoint.parse("file:///etc/hosts"))
        assertNull(GatewayEndpoint.parse("gateway.example.com:8443"))
        assertNull(GatewayEndpoint.parse("https://"))
        assertNull(GatewayEndpoint.parse(""))
        assertNull(GatewayEndpoint.parse("   "))
    }

    @Test
    fun aPlaintextProfileIsBuiltWithoutPins() {
        val profile = profile(gatewayBaseUrl = "http://gateway.example.com")

        assertTrue(profile.pinnedSpkiSha256.isEmpty())
        assertEquals(TransportSecurity.PLAINTEXT, endpointOf(profile).securityFor(profile.pinnedSpkiSha256))
    }

    @Test
    fun aPinnedProfileCannotBeBuiltOnAPlaintextAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(gatewayBaseUrl = "http://gateway.example.com", pins = setOf(PROTOCOL_PIN))
        }
    }

    @Test
    fun aProfileCannotBeBuiltOnASchemeTheTransportCannotCarry() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(gatewayBaseUrl = "ftp://gateway.example.com")
        }
    }

    @Test
    fun aPlaintextConnectionIsClassifiedAsPlaintext() {
        assertEquals(
            TransportSecurity.PLAINTEXT,
            GatewayConnectionSecurity.classify(plaintextConnection(), emptySet()),
        )
    }

    @Test
    fun aPlaintextConnectionIsRefusedForAPinnedProfile() {
        val failure = assertThrows(IOException::class.java) {
            GatewayConnectionSecurity.classify(plaintextConnection(), setOf(PROTOCOL_PIN))
        }

        assertTrue(failure.message!!.contains("PIN_REQUIRES_HTTPS"))
    }

    @Test
    fun theFactoryOpensPlaintextWithoutTlsAndKeepsTheHardening() {
        val connection = GatewayConnectionFactory()
            .open(URL("http://gateway.example.invalid/open-android-intelligence/v2"))

        assertFalse("plaintext must not be upgraded or wrapped", connection is HttpsURLConnection)
        assertTrue(connection.connectTimeout > 0)
        assertTrue(connection.readTimeout > 0)
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test
    fun theFactoryStillOpensTlsConnections() {
        val connection = GatewayConnectionFactory()
            .open(URL("https://gateway.example.invalid/open-android-intelligence/v2"))

        assertTrue(connection is HttpsURLConnection)
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test
    fun theFactoryRefusesSchemesTheTransportCannotCarry() {
        val failure = runCatching {
            GatewayConnectionFactory().open(URL("file:///etc/hosts"))
        }.exceptionOrNull()

        assertTrue(failure != null)
    }

    /**
     * The plaintext pairing path end to end: a real socket carries a real
     * signed-shape request to a real (loopback) Gateway and the response comes
     * back with its body intact.
     */
    @Test
    fun aPlaintextRequestReachesALoopbackGatewayAndReturnsItsResponse() {
        ServerSocket(0).use { server ->
            val received = CompletableFuture<String>()
            thread(isDaemon = true) { serveOneRequest(server, received) }

            val profile = profile(gatewayBaseUrl = "http://127.0.0.1:${server.localPort}")
            val response = runBlocking {
                GatewayTransport(profile).execute(
                    WireRequest(
                        method = "POST",
                        target = "/open-android-intelligence/v2/negotiate",
                        headers = listOf(RawHeader("Content-Type", "application/json")),
                        body = REQUEST_BODY.toByteArray(),
                    ),
                )
            }

            assertEquals(200, response.status)
            assertEquals(RESPONSE_BODY, String(response.body, Charsets.UTF_8))
            val request = received.get(5, TimeUnit.SECONDS)
            assertTrue("the request line must reach the plaintext Gateway", request.contains("POST /open-android-intelligence/v2/negotiate"))
            assertTrue("the request body must reach the plaintext Gateway", request.endsWith(REQUEST_BODY))
        }
    }

    private fun serveOneRequest(server: ServerSocket, received: CompletableFuture<String>) {
        try {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine().orEmpty()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val contentLength = headers
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val body = CharArray(contentLength).also { reader.read(it) }.concatToString()
                received.complete("$requestLine\n$body")

                val payload = RESPONSE_BODY.toByteArray()
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${payload.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    write(payload)
                    flush()
                }
            }
        } catch (cause: Exception) {
            received.completeExceptionally(cause)
        }
    }

    private fun profile(gatewayBaseUrl: String, pins: Set<String> = emptySet()) = GatewayProfile(
        accountId = "account-test",
        deviceId = "device-test",
        sessionId = "session-test",
        gatewayBaseUrl = gatewayBaseUrl,
        pinnedSpkiSha256 = pins,
    )

    private fun endpointOf(profile: GatewayProfile): GatewayEndpoint =
        requireNotNull(GatewayEndpoint.parse(profile.gatewayBaseUrl))

    private fun plaintextConnection(): HttpURLConnection =
        URL("http://gateway.example.invalid/open-android-intelligence/v2").openConnection()
            as HttpURLConnection

    private companion object {
        const val PROTOCOL_PIN = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REQUEST_BODY = """{"negotiationId":"neg_test"}"""
        const val RESPONSE_BODY = """{"data":{"negotiationId":"neg_test"}}"""
    }
}
