package com.openandroidintelligence.gateway.account

import com.openandroidintelligence.gateway.http.GatewayEndpoint

/**
 * One local view of one Gateway account.
 *
 * A profile is the boundary of isolation: every account owns its own key
 * material, credential slot, queue and audit directory. Nothing in this class
 * holds a secret.
 *
 * The address may be plaintext (ADR 0047), and `tlsTrustId` says which of the
 * two cases it is: a TLS Gateway has an identity to trust, a plaintext one has
 * none, because there is no certificate to trust. The two fields are therefore
 * checked against each other instead of independently, so a profile can never
 * claim a TLS trust it did not establish.
 */
data class AccountProfile(
    val localProfileId: String,
    val gatewayBaseUrl: String,
    val username: String,
    val tlsTrustId: String,
    /** Bounded, app-owned avatar image. Independent of temporary picker URI grants. */
    val avatarBase64: String? = null,
    /** Last display identity advertised by this account's Gateway. */
    val agentName: String? = null,
    /** 用户在原生多 Agent 列表中最后选择的原生 Agent；重连后据此恢复，而不是回落到列表第一个。 */
    val selectedAgentId: String? = null,
) {
    init {
        val endpoint = GatewayEndpoint.parse(gatewayBaseUrl)
        require(localProfileId.isNotBlank()) { "localProfileId must not be blank" }
        require(endpoint != null) { "gateway base url must be http or https with a host" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(endpoint.isTls == tlsTrustId.isNotBlank()) {
            "tlsTrustId must be set exactly for an https gateway"
        }
    }
}
