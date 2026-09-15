package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.http.GatewayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayLoginSanitizerTest {

    @Test
    fun stripsDuplicateAndNestedProtocolSchemes() {
        assertEquals("http://10.0.2.2:8045", sanitizeGatewayUrl("https://http://10.0.2.2:8045"))
        assertEquals("http://127.0.0.1:11451", sanitizeGatewayUrl("http://http://127.0.0.1:11451"))
        assertEquals("https://gateway.example.com", sanitizeGatewayUrl("https://https://gateway.example.com"))
        assertEquals("https://gateway.example.com:8443", sanitizeGatewayUrl("http://https://gateway.example.com:8443"))
        assertEquals("http://10.0.2.2:8045", sanitizeGatewayUrl("https://http://http://10.0.2.2:8045"))
    }

    @Test
    fun autoPrefixesHttpForLocalAndEmulatorAddresses() {
        assertEquals("http://10.0.2.2:8045", sanitizeGatewayUrl("10.0.2.2:8045"))
        assertEquals("http://10.0.2.2:11451", sanitizeGatewayUrl("10.0.2.2:11451"))
        assertEquals("http://127.0.0.1:8045", sanitizeGatewayUrl("127.0.0.1:8045"))
        assertEquals("http://localhost:8045", sanitizeGatewayUrl("localhost:8045"))
        assertEquals("http://192.168.1.50:8045", sanitizeGatewayUrl("192.168.1.50:8045"))
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
        assertEquals("http", endpoint1?.scheme)
        assertEquals("10.0.2.2", endpoint1?.host)
        assertEquals(false, endpoint1?.isTls)

        val sanitizedCorrupted = sanitizeGatewayUrl("https://http://127.0.0.1:11451")
        val endpoint2 = GatewayEndpoint.parse(sanitizedCorrupted)
        assertNotNull(endpoint2)
        assertEquals("http", endpoint2?.scheme)
        assertEquals("127.0.0.1", endpoint2?.host)
        assertEquals(false, endpoint2?.isTls)

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

