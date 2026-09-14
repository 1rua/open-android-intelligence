package com.openandroidintelligence.gateway.account

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The address and the trust id describe the same fact, so they are checked
 * against each other: a plaintext Gateway has no certificate to trust, and an
 * HTTPS Gateway may not leave its trust unstated.
 */
class AccountProfileTest {

    @Test
    fun anHttpsProfileCarriesATrustId() {
        profile(gatewayBaseUrl = "https://gateway.example.com", tlsTrustId = "trust-a")
    }

    @Test
    fun aPlaintextProfileCarriesNoTrustId() {
        profile(gatewayBaseUrl = "http://gateway.example.com", tlsTrustId = "")
    }

    @Test
    fun aPlaintextProfileMayNotClaimTlsTrust() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(gatewayBaseUrl = "http://gateway.example.com", tlsTrustId = "trust-a")
        }
    }

    @Test
    fun anHttpsProfileMayNotLeaveItsTrustUnstated() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(gatewayBaseUrl = "https://gateway.example.com", tlsTrustId = "")
        }
    }

    @Test
    fun anAddressTheTransportCannotCarryIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(gatewayBaseUrl = "ftp://gateway.example.com", tlsTrustId = "")
        }
    }

    private fun profile(gatewayBaseUrl: String, tlsTrustId: String) = AccountProfile(
        localProfileId = "profile-test",
        gatewayBaseUrl = gatewayBaseUrl,
        username = "user-test",
        tlsTrustId = tlsTrustId,
    )
}
