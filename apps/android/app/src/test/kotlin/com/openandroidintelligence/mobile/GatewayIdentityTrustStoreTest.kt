package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.negotiation.NegotiationResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GatewayIdentityTrustStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val endpoint = GatewayEndpoint.parse("https://gateway.example:8443")!!
    private val negotiated = NegotiationResult("neg_one", 2, 1, "deploy_one", "sha256:" + "a".repeat(64), "chat-v1", "staged-sha256-v1", "sse-cursor-v1", "risk-queue-v1")

    @Before fun clear() { context.getSharedPreferences("gateway-identities", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun aNewRuntimeLoadsTheApprovedPinBeforeItsFirstRequest() {
        GatewayIdentityTrustStore(context).remember(endpoint, "alice", negotiated)
        val restored = GatewayIdentityTrustStore(context)
        assertEquals(setOf(negotiated.tlsSpkiSha256), restored.pinsBeforeConnect(endpoint, "alice", restoring = true))
        assertEquals(negotiated.tlsSpkiSha256, restored.verifyNegotiation(endpoint, "alice", negotiated))
    }

    @Test fun neitherNegotiationNorRememberCanReplaceTheApprovedIdentity() {
        val store = GatewayIdentityTrustStore(context)
        store.remember(endpoint, "alice", negotiated)
        for (changed in listOf(negotiated.copy(deploymentId = "deploy_other"), negotiated.copy(tlsSpkiSha256 = "sha256:" + "b".repeat(64)))) {
            refused("GATEWAY_IDENTITY_CHANGED") { store.verifyNegotiation(endpoint, "alice", changed) }
            refused("GATEWAY_IDENTITY_CHANGED") { store.remember(endpoint, "alice", changed) }
        }
        assertEquals(negotiated.tlsSpkiSha256, store.retained(endpoint, "alice")!!.spki)
    }

    @Test fun legacyRefreshWithoutAnApprovedIdentityRequiresLogin() {
        refused("TLS_IDENTITY_RECONFIRMATION_REQUIRED") { GatewayIdentityTrustStore(context).pinsBeforeConnect(endpoint, "alice", restoring = true) }
    }

    @Test fun changingTheSchemeCannotDowngradeAnApprovedAccount() {
        val store = GatewayIdentityTrustStore(context)
        store.remember(endpoint, "alice", negotiated)
        refused("TLS_DOWNGRADE_REFUSED") { store.pinsBeforeConnect(GatewayEndpoint.parse("http://gateway.example:8443")!!, "alice") }
        store.remember(GatewayEndpoint.parse("https://default.example:443")!!, "alice", negotiated)
        refused("TLS_DOWNGRADE_REFUSED") { store.pinsBeforeConnect(GatewayEndpoint.parse("http://default.example")!!, "alice") }
        assertTrue(store.pinsBeforeConnect(endpoint, "bob").isEmpty())
    }

    @Test fun zeroPlaceholdersAreNeverApprovedAsTlsIdentities() {
        refused("NEGOTIATION_FAILED:missing-tls-identity") { GatewayIdentityTrustStore(context).verifyNegotiation(endpoint, "alice", negotiated.copy(tlsSpkiSha256 = "sha256:" + "0".repeat(64))) }
    }

    @Test fun explicitDefaultPortCannotBypassTheApprovedTlsAuthority() {
        val store = GatewayIdentityTrustStore(context)
        store.remember(GatewayEndpoint.parse("https://alias.example")!!, "alice", negotiated)
        assertEquals(setOf(negotiated.tlsSpkiSha256), store.pinsBeforeConnect(GatewayEndpoint.parse("https://alias.example:443")!!, "alice", restoring = true))
        refused("TLS_DOWNGRADE_REFUSED") { store.pinsBeforeConnect(GatewayEndpoint.parse("http://alias.example:443")!!, "alice") }
        refused("TLS_DOWNGRADE_REFUSED") { store.pinsBeforeConnect(GatewayEndpoint.parse("http://alias.example")!!, "alice") }
    }

    @Test fun separateTlsPortsCanKeepSeparateDeploymentIdentities() {
        val store = GatewayIdentityTrustStore(context)
        val default = GatewayEndpoint.parse("https://ports.example")!!
        val alternate = GatewayEndpoint.parse("https://ports.example:80")!!
        store.remember(default, "alice", negotiated)
        val other = negotiated.copy(deploymentId = "deploy_other", tlsSpkiSha256 = "sha256:" + "b".repeat(64))
        store.remember(alternate, "alice", other)
        assertEquals(setOf(negotiated.tlsSpkiSha256), store.pinsBeforeConnect(default, "alice", restoring = true))
        assertEquals(setOf(other.tlsSpkiSha256), store.pinsBeforeConnect(alternate, "alice", restoring = true))
    }

    private fun refused(code: String, operation: () -> Unit) {
        try { operation(); fail("expected $code") } catch (cause: IllegalStateException) { assertEquals(code, cause.message) }
    }
}
