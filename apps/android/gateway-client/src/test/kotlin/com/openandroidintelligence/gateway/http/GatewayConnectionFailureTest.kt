package com.openandroidintelligence.gateway.http

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class GatewayConnectionFailureTest {
    @Test fun securityFailuresRemainRecognizableThroughIoWrappers() {
        listOf(SSLHandshakeException("test"), SSLPeerUnverifiedException("test"), CertificateException("test"), java.security.cert.CertPathValidatorException("test")).forEach {
            assertTrue(GatewayConnectionSecurity.isSecurityFailure(IOException("transport", it)))
        }
        assertFalse(GatewayConnectionSecurity.isSecurityFailure(IOException("network unavailable")))
    }

    @Test fun tlsWrappedSocketOutagesRemainRecoverableWithoutBypassingCertificateErrors() {
        val disconnected = SSLHandshakeException("synthetic handshake interruption").apply {
            initCause(java.io.EOFException("synthetic peer restart"))
        }
        assertFalse(GatewayConnectionSecurity.isSecurityFailure(disconnected))
        val untrusted = SSLHandshakeException("synthetic identity refusal").apply {
            initCause(CertificateException("synthetic certificate", java.io.EOFException()))
        }
        assertTrue(GatewayConnectionSecurity.isSecurityFailure(untrusted))
        assertTrue(GatewayConnectionSecurity.isSecurityFailure(GatewaySecurityException("PIN_MISMATCH").apply {
            initCause(java.net.SocketException())
        }))
    }

    @Test fun pinMismatchHasAStableCodeAndCannotBecomeANetworkRetry() {
        val failure = assertThrows(GatewaySecurityException::class.java) {
            SpkiPinning.verify(emptyArray<Certificate>(), setOf("sha256:" + "1".repeat(64)))
        }
        assertEquals("PIN_MISMATCH", failure.code)
        assertTrue(GatewayConnectionSecurity.isSecurityFailure(failure))
    }
}
