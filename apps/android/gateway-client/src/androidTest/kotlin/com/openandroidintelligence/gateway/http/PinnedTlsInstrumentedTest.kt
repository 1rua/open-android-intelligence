package com.openandroidintelligence.gateway.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory

/**
 * On-device TLS evidence.
 *
 * Pin enforcement is only meaningful against the platform trust store and real
 * certificates: the local JVM suite has neither, and asserting that a pin
 * "would" have fired is exactly the kind of unverified claim this migration
 * exists to remove.
 *
 * Everything here runs offline against certificates the device already trusts,
 * so the results do not depend on network reachability.
 *
 * A plaintext address is a supported, explicitly warned configuration
 * (ADR 0047); what must never happen is a *pinned* identity quietly losing its
 * certificate check, which is what the downgrade tests below hold.
 */
@RunWith(AndroidJUnit4::class)
class PinnedTlsInstrumentedTest {

    @Test
    fun plaintextHttpIsOpenedWithoutTlsOnDevice() {
        val connection = GatewayConnectionFactory()
            .open(URL("http://gateway.example.com/open-android-intelligence/v2"))

        assertTrue("a plaintext address must stay plaintext", connection !is HttpsURLConnection)
        assertEquals(
            "redirects must not be followed automatically; the signature covers one target",
            false,
            connection.instanceFollowRedirects,
        )
    }

    @Test
    fun connectTimeoutsAreBoundedAndRedirectsAreNotFollowed() {
        val connection = GatewayConnectionFactory().open(URL("https://gateway.example.com/open-android-intelligence/v2"))

        assertTrue(connection.connectTimeout > 0)
        assertTrue(connection.readTimeout > 0)
        assertEquals(
            "redirects must not be followed automatically; the signature covers one target",
            false,
            connection.instanceFollowRedirects,
        )
    }

    @Test
    fun aMatchingPinIsAccepted() {
        val certificate = trustAnchor()
        val pin = SpkiPinning.spkiSha256Base64(certificate)

        SpkiPinning.verify(arrayOf(certificate), setOf(pin))
    }

    @Test
    fun aProtocolPrefixedSpkiPinIsAccepted() {
        val certificate = trustAnchor()
        val pin = SpkiPinning.spkiSha256PrefixedHex(certificate)

        SpkiPinning.verify(arrayOf(certificate), setOf(pin))
    }

    @Test
    fun aChangedFingerprintFailsClosed() {
        val certificate = trustAnchor()

        // A pin derived from a different certificate stands in for a rotated or
        // substituted server key: it must be rejected, not downgraded.
        val otherPin = SpkiPinning.spkiSha256Base64(trustAnchor(1))

        val failure = assertThrows(java.io.IOException::class.java) {
            SpkiPinning.verify(arrayOf(certificate), setOf(otherPin))
        }
        assertTrue(failure.message!!.contains("PIN_MISMATCH"))
    }

    @Test
    fun aPinnedProfileDoesNotFallBackToSystemTrust() {
        val certificate = trustAnchor()

        val failure = assertThrows(java.io.IOException::class.java) {
            SpkiPinning.verify(arrayOf(certificate), setOf(NON_MATCHING_PIN))
        }
        assertTrue(failure.message!!.contains("PIN_MISMATCH"))
    }

    @Test
    fun aPinSetAcceptsAnyOfItsEntries() {
        val certificate = trustAnchor()
        val correctPin = SpkiPinning.spkiSha256Base64(certificate)

        SpkiPinning.verify(arrayOf(certificate), setOf(NON_MATCHING_PIN, correctPin))
    }

    @Test
    fun emptyPinSetMeansSystemTrustAndVerifies() {
        val certificate = trustAnchor()
        SpkiPinning.verify(arrayOf(certificate), emptySet())
    }

    @Test
    fun spkiDigestsAreDeterministic() {
        val certificate = trustAnchor()

        assertEquals(
            SpkiPinning.spkiSha256Base64(certificate),
            SpkiPinning.spkiSha256Base64(certificate),
        )
    }

    private fun trustAnchor(index: Int = 0): X509Certificate {
        val anchors = systemTrustAnchors()
        assertTrue("the platform trust store must be readable on device", anchors.size > index)
        return anchors[index]
    }

    private fun systemTrustAnchors(): List<X509Certificate> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val certificates = mutableListOf<X509Certificate>()
        for (trustManager in factory.trustManagers) {
            if (trustManager !is javax.net.ssl.X509TrustManager) continue
            certificates += trustManager.acceptedIssuers
        }
        return certificates
    }

    private companion object {
        /** Base64 of a 32-byte value that cannot match any real SPKI. */
        const val NON_MATCHING_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
