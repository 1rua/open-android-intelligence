package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.SpkiPinning
import com.openandroidintelligence.gateway.negotiation.NegotiationResult
import java.net.URI
import java.security.MessageDigest

/** Approved public identity survives logout; refresh secrets never enter this store. */
class GatewayIdentityTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences("gateway-identities", Context.MODE_PRIVATE)
    data class Identity(val deploymentId: String, val spki: String?)

    fun retained(endpoint: GatewayEndpoint, username: String): Identity? = retained(key(endpoint, username))

    private fun retained(key: String): Identity? {
        val pin = preferences.getString("$key.pin", null)
        val deployment = preferences.getString("$key.deployment", null)
        if (pin == null && deployment == null) return null
        val validPin = pin == null || SpkiPinning.isProtocolPin(pin)
        check(validPin && !deployment.isNullOrBlank()) { "TLS_IDENTITY_RECONFIRMATION_REQUIRED" }
        return Identity(deployment, pin)
    }

    fun pinsBeforeConnect(endpoint: GatewayEndpoint, username: String, restoring: Boolean = false): Set<String> {
        val identity = retained(endpoint, username)
        check(endpoint.isTls || identity == null) { "TLS_DOWNGRADE_REFUSED" }
        if (!endpoint.isTls && URI(endpoint.baseUrl).port in listOf(-1, 80)) {
            check(retained(key(endpoint, username, portOverride = 443)) == null) { "TLS_DOWNGRADE_REFUSED" }
        }
        check(!restoring || !endpoint.isTls || identity != null) { "TLS_IDENTITY_RECONFIRMATION_REQUIRED" }
        return identity?.spki?.let { setOf(it) } ?: emptySet()
    }

    fun verifyNegotiation(endpoint: GatewayEndpoint, username: String, result: NegotiationResult): String? {
        pinsBeforeConnect(endpoint, username)
        if (!endpoint.isTls) return null
        val pin = result.tlsSpkiSha256?.takeIf { it.isNotBlank() }
        val deployment = result.deploymentId
        check(!deployment.isNullOrBlank()) { "NEGOTIATION_FAILED:missing-deployment" }
        if (pin != null) {
            check(SpkiPinning.isProtocolPin(pin)) { "NEGOTIATION_FAILED:missing-tls-identity" }
        }
        val identity = retained(endpoint, username)
        check(identity == null || identity == Identity(deployment, pin)) { "GATEWAY_IDENTITY_CHANGED" }
        return pin
    }

    /** 仅在通过协商得到的 pin 或标准 CA 证书认证成功后调用。 */
    fun remember(endpoint: GatewayEndpoint, username: String, result: NegotiationResult) {
        val pin = verifyNegotiation(endpoint, username, result)
        val deployment = result.deploymentId
        if (!endpoint.isTls || deployment.isNullOrBlank()) return
        val key = key(endpoint, username)
        val editor = preferences.edit().putString("$key.deployment", deployment)
        if (pin != null) {
            editor.putString("$key.pin", pin)
        } else {
            editor.remove("$key.pin")
        }
        check(editor.commit()) { "TLS_IDENTITY_PERSISTENCE_FAILED" }
    }

    fun forget(endpoint: GatewayEndpoint, username: String) {
        val key = key(endpoint, username)
        check(preferences.edit().remove("$key.pin").remove("$key.deployment").commit()) { "TLS_IDENTITY_CLEANUP_FAILED" }
    }

    private fun key(endpoint: GatewayEndpoint, username: String, portOverride: Int? = null): String {
        val uri = URI(endpoint.baseUrl)
        // Scheme is absent so HTTP cannot bypass a retained HTTPS identity.
        val defaultPort = if (endpoint.isTls) 443 else 80
        val port = portOverride ?: uri.port.takeUnless { it == -1 } ?: defaultPort
        val scope = "${endpoint.host.lowercase()}|$port|${uri.rawPath.orEmpty()}|${username.trim()}"
        return MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
