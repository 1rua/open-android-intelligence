package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.http.GatewayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayLoginSanitizerTest {

    @Test
    fun nestedSchemesAreRejectedWithoutChoosingAnInnerProtocol() {
        for (value in listOf("https://http://10.0.2.2:8045", "http://http://127.0.0.1:8045", "http://https://gateway.example")) {
            assertEquals(value, sanitizeGatewayUrl(value))
            assertNull(GatewayEndpoint.parse(sanitizeGatewayUrl(value)))
        }
    }

    @Test
    fun schemelessAddressesAlwaysDefaultToHttps() {
        for (value in listOf("10.0.2.2:8045", "localhost:8045", "192.168.1.50:8045", "localhost.attacker.example", "172.32.0.1", "gateway.example")) {
            assertEquals("https://$value", sanitizeGatewayUrl(value))
        }
    }

    @Test
    fun preservesExplicitProtocolsAndTrimsSlashes() {
        assertEquals("http://10.0.2.2:8045", sanitizeGatewayUrl("  http://10.0.2.2:8045/  "))
        assertEquals("https://gateway.example.com:8443", sanitizeGatewayUrl("https://gateway.example.com:8443/"))
        assertEquals("https://gateway.example.com", sanitizeGatewayUrl("https://gateway.example.com"))
    }

    @Test
    fun endpointParsesSanitizedUrlsCleanly() {
        val sanitizedEmulator = sanitizeGatewayUrl("10.0.2.2:8045")
        val endpoint1 = GatewayEndpoint.parse(sanitizedEmulator)
        assertNotNull(endpoint1)
        assertEquals("https", endpoint1?.scheme)
        assertEquals("10.0.2.2", endpoint1?.host)
        assertEquals(true, endpoint1?.isTls)

        assertNull(GatewayEndpoint.parse(sanitizeGatewayUrl("https://http://127.0.0.1:11451")))

        val sanitizedTls = sanitizeGatewayUrl("https://gateway.example.com:8443/")
        val endpoint3 = GatewayEndpoint.parse(sanitizedTls)
        assertNotNull(endpoint3)
        assertEquals("https", endpoint3?.scheme)
        assertEquals(true, endpoint3?.isTls)
    }

    @Test
    fun handlesEmptyAndBlankInputs() {
        assertEquals("", sanitizeGatewayUrl(""))
        assertEquals("", sanitizeGatewayUrl("   "))
        assertNull(GatewayEndpoint.parse(sanitizeGatewayUrl("")))
    }
}

