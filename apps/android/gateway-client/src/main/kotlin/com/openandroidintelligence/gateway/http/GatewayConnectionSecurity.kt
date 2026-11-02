package com.openandroidintelligence.gateway.http

import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection

/** A stable code without peer supplied text or trust material. */
class GatewaySecurityException(val code: String) : SSLException(code)

/**
 * The transport rule that holds whatever scheme the user typed.
 *
 * Plaintext is reachable, but it is never reachable *by downgrading a stronger
 * identity*: a profile that declares a pin is a claim about a TLS certificate,
 * so the connection is refused — after the handshake, before a single response
 * byte or request body is trusted — instead of dropping the certificate check.
 * `GatewayProfile` refuses that combination earlier, at construction, so this
 * is the rule at the point where it is actually used rather than a copy of the
 * type boundary.
 *
 * An unpinned plaintext connection is permitted and reported as
 * [TransportSecurity.PLAINTEXT], so no screen can present it as a verified
 * identity.
 */
object GatewayConnectionSecurity {

    /** Identity failures stop recovery; a TLS wrapper around a socket outage does not change its cause. */
    fun isSecurityFailure(failure: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        var tlsFailure = false
        var socketFailure = false
        while (current != null && seen.add(current)) {
            if (current is GatewaySecurityException || current is javax.net.ssl.SSLPeerUnverifiedException ||
                current is GeneralSecurityException || current is SecurityException) return true
            if (current is SSLException) tlsFailure = true
            if (current is java.io.EOFException || current is java.net.SocketException ||
                current is java.net.SocketTimeoutException) socketFailure = true
            current = current.cause
        }
        return tlsFailure && !socketFailure
    }

    const val PIN_REQUIRES_HTTPS =
        "PIN_REQUIRES_HTTPS: a pinned profile cannot use a plaintext connection"

    fun classify(connection: HttpURLConnection, pins: Set<String>): TransportSecurity {
        val https = connection as? HttpsURLConnection
        if (https == null) {
            if (pins.isNotEmpty()) {
                throw GatewaySecurityException("PIN_REQUIRES_HTTPS")
            }
            return TransportSecurity.PLAINTEXT
        }
        SpkiPinning.verify(https, pins)
        return if (pins.isEmpty()) TransportSecurity.TLS_SYSTEM_TRUST else TransportSecurity.TLS_PINNED
    }

    fun classify(socket: java.net.Socket, pins: Set<String>): TransportSecurity {
        val sslSocket = socket as? javax.net.ssl.SSLSocket
        if (sslSocket == null) {
            if (pins.isNotEmpty()) {
                throw GatewaySecurityException("PIN_REQUIRES_HTTPS")
            }
            return TransportSecurity.PLAINTEXT
        }
        sslSocket.startHandshake()
        SpkiPinning.verify(sslSocket.session.peerCertificates, pins)
        return if (pins.isEmpty()) TransportSecurity.TLS_SYSTEM_TRUST else TransportSecurity.TLS_PINNED
    }
}
