package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLHandshakeException

class GatewayHttpReachabilityTest {
    private val profile = GatewayProfile("account", "device", "session", "http://gateway.example")
    private val request = WireRequest("GET", "/open-android-intelligence/v2/events")
    private fun connection(status: Int, failure: Throwable? = null) = object : HttpURLConnection(URL(profile.gatewayBaseUrl)) {
        override fun connect() { failure?.let { throw it } }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
        override fun getInputStream() = byteArrayOf().inputStream()
        override fun getErrorStream() = byteArrayOf().inputStream()
    }

    @Test fun authorizationRejectionsStillProveHttpReachabilityAcrossAllThreeEntryPoints() = runBlocking {
        val responses = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val transport = GatewayTransport(profile, GatewayConnectionFactory { connection(401) },
            onHttpResponse = { responses += it }, onHttpFailure = { failures += it })
        assertEquals(401, transport.execute(request).status)
        assertEquals(401, transport.consumeResponse(request) { status, _, _ -> status })
        assertTrue(runCatching { transport.eventStream(request).toList() }.exceptionOrNull() is EventSessionRejectedException)
        assertEquals(listOf(401, 401, 401), responses)
        assertTrue(failures.isEmpty())
    }

    @Test fun tlsFailureReportsOnlyFailureAndCallbackErrorsDoNotReplaceIt() = runBlocking {
        val failure = SSLHandshakeException("test certificate")
        var responses = 0
        val failures = mutableListOf<Throwable>()
        val transport = GatewayTransport(profile, GatewayConnectionFactory { connection(200, failure) },
            onHttpResponse = { responses++ }, onHttpFailure = { failures += it; error("observer") })
        listOf(
            runCatching { transport.execute(request) }.exceptionOrNull(),
            runCatching { transport.consumeResponse(request) { _, _, _ -> Unit } }.exceptionOrNull(),
            runCatching { transport.eventStream(request).toList() }.exceptionOrNull(),
        ).forEach {
            assertTrue(it is SSLHandshakeException)
            assertEquals("test certificate", it?.message)
        }
        assertEquals(0, responses)
        assertEquals(3, failures.size)
        assertTrue(failures.all { GatewayConnectionSecurity.isSecurityFailure(it) && it.message == "test certificate" })
    }

    @Test fun responseObserverCannotChangeACompletedRequest() = runBlocking {
        val transport = GatewayTransport(profile, GatewayConnectionFactory { connection(403) },
            onHttpResponse = { error("observer") })
        assertEquals(403, transport.execute(request).status)
    }

    @Test fun cancellationDoesNotBecomeAReachabilityFailure() = runBlocking {
        var failures = 0
        val transport = GatewayTransport(profile, GatewayConnectionFactory { connection(200, CancellationException("cancelled")) },
            onHttpFailure = { failures++ })
        assertTrue(runCatching { transport.execute(request) }.exceptionOrNull() is CancellationException)
        assertEquals(0, failures)
    }

    @Test fun connectionCreationFailuresAreObservedOnEveryEntryPoint() = runBlocking {
        var failures = 0
        val transport = GatewayTransport(profile, GatewayConnectionFactory { throw IOException("offline") },
            onHttpFailure = { failures++ })
        assertTrue(runCatching { transport.execute(request) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { transport.consumeResponse(request) { _, _, _ -> Unit } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { transport.eventStream(request).toList() }.exceptionOrNull() is IOException)
        assertEquals(3, failures)
    }

}
