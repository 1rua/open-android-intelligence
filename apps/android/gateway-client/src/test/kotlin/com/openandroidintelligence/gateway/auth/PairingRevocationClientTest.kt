package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingRevocationClientTest {
    private class Transport(var body: String, var status: Int = 200) : GatewayByteTransport {
        lateinit var request: WireRequest
        override suspend fun execute(request: WireRequest): WireResponse {
            this.request = request
            return WireResponse(status, emptyList(), body.toByteArray())
        }
        override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
    }
    private fun client(transport: Transport) = PairingRevocationClient(GatewayHttpClient(
        GatewayProfile("acct_1", "dev_1", "sess_1", "https://gateway.example", accessToken = "token"),
        transport, { ByteArray(64) }, InMemoryEventCursorStore(),
    ))
    private val receipt = """{"protocol":"2.1","data":{"deviceId":"dev_1","deviceKeysRevoked":true,"refreshRevoked":true,"grantsRevoked":true,"deviceRequestsRevoked":true,"unconfirmedAttachmentsRevoked":true,"sessionsRevoked":true}}"""

    @Test fun sendsSignedDeleteAndAcceptsCompleteReceipt() = runBlocking {
        val transport = Transport(receipt)
        client(transport).revoke("dev_1")
        assertEquals("DELETE", transport.request.method)
        assertEquals("/open-android-intelligence/v2/pairings/current", transport.request.target)
        assertTrue(transport.request.headers.any { it.name == "X-Open-Android-Intelligence-Signature" })
        assertTrue(transport.request.headers.any { it.name == "Idempotency-Key" })
    }
    @Test fun refusesPartialWrongDeviceOrUnversionedReceipt() = runBlocking {
        for (bad in listOf(receipt.replace("\"sessionsRevoked\":true", "\"sessionsRevoked\":false"),
            receipt.replace("dev_1", "dev_other"), receipt.replace("\"protocol\":\"2.1\",", ""),
            receipt.replace(",\"sessionsRevoked\":true", ""))) {
            assertTrue(runCatching { client(Transport(bad)).revoke("dev_1") }.isFailure)
        }
        assertTrue(runCatching { client(Transport(receipt, 401)).revoke("dev_1") }.isFailure)
    }
}
