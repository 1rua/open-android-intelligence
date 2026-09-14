package com.openandroidintelligence.gateway.http

import java.io.IOException
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection

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

    const val PIN_REQUIRES_HTTPS =
        "PIN_REQUIRES_HTTPS: a pinned profile cannot use a plaintext connection"

    fun classify(connection: HttpURLConnection, pins: Set<String>): TransportSecurity {
        val https = connection as? HttpsURLConnection
        if (https == null) {
            if (pins.isNotEmpty()) {
                throw IOException(PIN_REQUIRES_HTTPS)
            }
            return TransportSecurity.PLAINTEXT
        }
        SpkiPinning.verify(https, pins)
        return if (pins.isEmpty()) TransportSecurity.TLS_SYSTEM_TRUST else TransportSecurity.TLS_PINNED
    }
}
