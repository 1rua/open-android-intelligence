package com.openandroidintelligence.gateway.http

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * SPKI pin enforcement.
 *
 * Verification runs against the certificates the connection actually negotiated,
 * after `connect()`. A pin is a set of SPKI SHA-256 hashes and any certificate
 * in the chain may match any pin.
 *
 * A profile that declares a pin must never be able to degrade to "any
 * system-trusted certificate": that is what makes a fingerprint change on a
 * pinned account a hard failure rather than a warning.
 *
 * The certificate-level entry point exists so the rule can be proven against
 * real certificates without a network round trip.
 */
object SpkiPinning {

    private val PROTOCOL_PIN = Regex("^sha256:[0-9a-f]{64}$")

    fun isProtocolPin(value: String): Boolean = PROTOCOL_PIN.matches(value) && value != "sha256:" + "0".repeat(64)

    fun verify(connection: HttpsURLConnection, pins: Set<String>) {
        verify(connection.serverCertificates, pins)
    }

    fun verify(certificates: Array<out Certificate>, pins: Set<String>) {
        if (pins.isEmpty()) return

        for (certificate in certificates) {
            if (certificate !is X509Certificate) continue
            if (spkiSha256PrefixedHex(certificate) in pins || spkiSha256Base64(certificate) in pins) return
        }
        throw IOException(
            "PIN_MISMATCH: none of ${certificates.size} certificate(s) matched ${pins.size} pin(s)",
        )
    }

    fun spkiSha256Base64(certificate: X509Certificate): String {
        val spki = digest(certificate)
        return java.util.Base64.getEncoder().encodeToString(spki)
    }

    /** The canonical wire representation used by Gateway Protocol v2. */
    fun spkiSha256PrefixedHex(certificate: X509Certificate): String =
        "sha256:" + digest(certificate).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun digest(certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
}
