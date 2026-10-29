package com.openandroidintelligence.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingGrantStateTest {

    private val binding = PairingGrantBinding(
        gatewayId = "https://gateway.example.com",
        accountId = "account-a",
        installationId = "install-a",
    )

    @Test
    fun aNewBindingUsesClientDefaultsWithoutExplicitGrants() {
        val holder = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(InMemoryAuditSink()),
        )

        holder.bind(binding)

        val state = holder.state.value
        assertEquals(0L, state?.revision)
        assertTrue(state?.granted.isNullOrEmpty())
        assertFalse(state?.migrationRequired ?: true)
        assertTrue(state?.permits(PairingGrantCapabilities.SMS) == true)
        assertFalse(state?.screenSelectionEnabled ?: true)
    }

    @Test
    fun grantChangesAreMonotonicAuditedAndRestoredForTheSameBinding() {
        val store = InMemoryPairingGrantStore()
        val sink = InMemoryAuditSink()
        val holder = PairingGrantStateHolder(store, AndroidAuditStore(sink))
        holder.bind(binding)

        val granted = holder.updatePrimitive(PairingGrantCapabilities.SMS, enabled = true)

        assertEquals(1L, granted.revision)
        assertTrue(PairingGrantCapabilities.SMS in granted.granted)
        assertEquals(1, sink.events().size)
        assertEquals("pairing.grant.changed", sink.events().single().action)

        val restored = PairingGrantStateHolder(store, AndroidAuditStore(InMemoryAuditSink()))
        restored.bind(binding)

        assertEquals(granted, restored.state.value)

        val revoked = restored.updatePrimitive(PairingGrantCapabilities.SMS, enabled = false)
        assertEquals(2L, revoked.revision)
        assertFalse(PairingGrantCapabilities.SMS in revoked.granted)
    }

    @Test
    fun differentGatewayAccountOrInstallationCannotSeeAnotherGrant() {
        val store = InMemoryPairingGrantStore()
        val holder = PairingGrantStateHolder(store, AndroidAuditStore(InMemoryAuditSink()))
        holder.bind(binding)
        holder.updatePrimitive(PairingGrantCapabilities.NOTIFICATIONS, enabled = true)

        val other = PairingGrantStateHolder(store, AndroidAuditStore(InMemoryAuditSink()))
        other.bind(
            PairingGrantBinding(
                gatewayId = "https://other.example.com",
                accountId = "account-b",
                installationId = "install-b",
            ),
        )

        assertTrue(other.state.value?.granted.isNullOrEmpty())
        assertNull(other.currentKernelGrant(binding.pairingId))
    }

    @Test
    fun kernelProjectionUsesOnlyTheBoundPairing() {
        val holder = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = AndroidAuditStore(InMemoryAuditSink()),
        )
        holder.bind(binding)
        holder.updatePrimitive(PairingGrantCapabilities.SMS, enabled = true)

        val grant = holder.currentKernelGrant(binding.pairingId)

        assertEquals(
            PairingGrant(
                pairingId = binding.pairingId,
                granted = setOf(PairingGrantCapabilities.SMS),
                revision = 1L,
                defaultEnabled = true,
            ),
            grant,
        )
        assertNull(holder.currentKernelGrant("pairing-other"))
    }

    @Test
    fun clearingTheCurrentPairingKeepsARevocationTombstone() {
        val store = InMemoryPairingGrantStore()
        val holder = PairingGrantStateHolder(store, AndroidAuditStore(InMemoryAuditSink()))
        holder.bind(binding)
        holder.updatePrimitive(PairingGrantCapabilities.SMS, enabled = true)

        holder.clearCurrent()

        val restored = PairingGrantStateHolder(store, AndroidAuditStore(InMemoryAuditSink()))
        restored.bind(binding)
        assertEquals(2L, restored.state.value?.revision)
        assertTrue(restored.state.value?.granted.isNullOrEmpty())
        assertTrue(restored.state.value?.migrationRequired == true)
    }
}
