package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayAuthEnvelopeTest {

    @Test
    fun readsSessionCredentialsFromTheProtocolDataEnvelope() {
        val body = Json.parse(
            """
            {
              "requestId":"req-1",
              "correlationId":"cor-1",
              "protocol":"2.1",
              "data":{
                "accountId":"account-1",
                "deviceId":"device-1",
                "sessionId":"session-1",
                "accessToken":"access-1",
                "refreshCredential":"refresh-1",
                "pairingSummary":"pairing-summary"
              }
            }
            """.trimIndent(),
        ) as JsonValue.JObject

        val session = parseSessionCredentials(body, "TEST")

        assertEquals("account-1", session.accountId)
        assertEquals("device-1", session.deviceId)
        assertEquals("session-1", session.sessionId)
        assertEquals("access-1", session.accessToken)
        assertArrayEquals("refresh-1".toByteArray(), session.refreshCredential)
        assertEquals("pairing-summary", session.pairingSummary)
    }
}
