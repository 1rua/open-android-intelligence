package com.openandroidintelligence.gateway.http

import java.net.URL

/**
 * How the phone is really talking to a Gateway.
 *
 * The app supports TLS endpoints and plaintext endpoints, and it never blurs
 * them: a TLS endpoint has a verified identity (pinned to the SPKI digest the
 * Gateway returned during negotiation, or system trust when no pin exists), a
 * plaintext endpoint has none. Every surface that reports connection state must
 * name which one it is, because "connected" alone says nothing about whether
 * the credentials crossed the network in the clear.
 */
enum class TransportSecurity {
    /** HTTPS whose certificate chain matched the negotiated SPKI pin. */
    TLS_PINNED,

    /** HTTPS relying on the platform trust store; no pin was negotiated. */
    TLS_SYSTEM_TRUST,

    /**
     * Plaintext HTTP: credentials, messages and attachment bytes are visible to
     * anyone on the path. Allowed only because the user typed the address
     * themselves (ADR 0047) and always accompanied by a visible warning.
     */
    PLAINTEXT,
    ;

    val isEncrypted: Boolean get() = this != PLAINTEXT
}

/**
 * A parsed Gateway base URL.
 *
 * This is the only place that decides which schemes the app can talk to, so the
 * login form, the session runtime and the transport itself cannot disagree
 * about what "a usable gateway address" means.
 */
data class GatewayEndpoint(
    val baseUrl: String,
    val scheme: String,
    val host: String,
) {
    val isTls: Boolean get() = scheme == HTTPS

    /** What this endpoint can honestly claim, given the pins the profile carries. */
    fun securityFor(pins: Set<String>): TransportSecurity = when {
        !isTls -> TransportSecurity.PLAINTEXT
        pins.isNotEmpty() -> TransportSecurity.TLS_PINNED
        else -> TransportSecurity.TLS_SYSTEM_TRUST
    }

    companion object {
        const val HTTPS = "https"
        const val HTTP = "http"

        /**
         * Parses a user-supplied Gateway address, or returns `null` when the
         * HTTP transport cannot carry it.
         *
         * An unsupported scheme is a refusal: the app never upgrades, rewrites
         * or guesses a scheme the user did not type, because the address is part
         * of the pairing decision the user is making.
         */
        fun parse(value: String): GatewayEndpoint? {
            val candidate = value.trim().removeSuffix("/")
            if (candidate.isEmpty()) return null
            return runCatching {
                val url = URL(candidate)
                val scheme = url.protocol.lowercase()
                require(scheme == HTTP || scheme == HTTPS) { "unsupported gateway scheme: $scheme" }
                require(url.host.isNotBlank()) { "gateway address must contain a host" }
                GatewayEndpoint(baseUrl = candidate, scheme = scheme, host = url.host)
            }.getOrNull()
        }
    }
}
