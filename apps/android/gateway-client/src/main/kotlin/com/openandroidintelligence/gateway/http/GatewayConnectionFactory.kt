package com.openandroidintelligence.gateway.http

import java.net.HttpURLConnection
import java.net.URL

/**
 * The single owned outbound transport surface of the mobile app.
 *
 * It opens both schemes the app supports: HTTPS, whose identity is checked by
 * [GatewayConnectionSecurity] against the profile's pins, and plaintext HTTP,
 * which the user can reach deliberately for a self-hosted gateway without a
 * certificate (ADR 0047). Allowing the plaintext scheme here is not a licence
 * to hide it — the caller classifies the endpoint with
 * [GatewayEndpoint.securityFor] and the UI shows the plaintext warning — and a
 * profile that declares pins is refused over plaintext before the connection is
 * used.
 *
 * Redirects are never followed: the request signature covers exactly one
 * canonical target, so a redirect could only move the request to a target the
 * signature does not describe.
 */
class GatewayConnectionFactory {

    fun open(url: URL, readTimeoutMillis: Int = READ_TIMEOUT_MILLIS): HttpURLConnection {
        val scheme = url.protocol.lowercase()
        require(scheme == GatewayEndpoint.HTTP || scheme == GatewayEndpoint.HTTPS) {
            "gateway transport supports http or https, got $scheme"
        }
        val connection = url.openConnection() as? HttpURLConnection
            ?: error("gateway transport requires an HTTP connection")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = readTimeoutMillis
        return connection
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
