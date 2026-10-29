package com.openandroidintelligence.mobile.plugins

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class DeviceCapabilitySyncStateTest {
    private fun entry(plugin: String, version: String, status: String) = JsonFields.obj(Json.of(mapOf(
        "pluginId" to plugin, "capabilityId" to "org.example.query", "capabilityVersion" to version, "status" to status,
    )))!!

    @Test fun providersAndVersionsKeepTheirOwnStatusRegardlessOfCatalogOrder() {
        val entries = listOf(entry("org.example.a", "1.0.0", "READY"),
            entry("org.example.z", "1.0.0", "PROVIDER_NOT_SELECTED"), entry("org.example.a", "2.0.0", "PLUGIN_DISABLED"))
        for (catalog in listOf(entries, entries.reversed())) {
            val state = DeviceCapabilitySyncState("SYNCED", capabilities = catalog)
            assertEquals("READY", state.diagnostic(DeviceCapabilityIdentity("org.example.a", "org.example.query", "1.0.0")).status)
            assertEquals("PROVIDER_NOT_SELECTED", state.diagnostic(DeviceCapabilityIdentity("org.example.z", "org.example.query", "1.0.0")).status)
            assertEquals("PLUGIN_DISABLED", state.diagnostic(DeviceCapabilityIdentity("org.example.a", "org.example.query", "2.0.0")).status)
        }
    }

    @Test fun aNewLocalRevisionImmediatelyHidesAnOlderSuccessAndItsReadyCatalog() {
        val state = DeviceCapabilitySyncState("SYNCED", localRevision = 3, hostRevision = 5,
            capabilities = listOf(entry("org.example.a", "1.0.0", "READY")))
        val current = state.forLocalState(4, 5)
        assertEquals("SYNC_REQUIRED", current.status)
        assertTrue(current.capabilities.isEmpty())
        assertEquals("SYNC_REQUIRED", current.diagnostic(DeviceCapabilityIdentity("org.example.a", "org.example.query", "1.0.0")).status)
        assertEquals("SYNC_REQUIRED", state.forLocalState(3, 6).status)
    }

    @Test fun anOlderFailureCannotReplaceTheRecoveryStateOfANewerRevision() {
        val failed = DeviceCapabilitySyncState("SYNC_FAILED", localRevision = 1, hostRevision = 2, errorCode = "GRANT_STALE")
        val current = failed.forLocalState(2, 2)
        assertEquals("SYNC_REQUIRED", current.status)
        assertNull(current.errorCode)
    }

    @Test fun failedAndRetryingStatesShowTheirRealCodeInsteadOfAnOldReadyCatalog() {
        val identity = DeviceCapabilityIdentity("org.example.a", "org.example.query", "1.0.0")
        for (phase in listOf("SYNC_FAILED", "SYNC_RETRYING")) {
            val state = DeviceCapabilitySyncState(phase, errorCode = "GRANT_STALE",
                capabilities = listOf(entry("org.example.a", "1.0.0", "READY")))
            assertEquals(DeviceCapabilityDiagnostic(phase, "GRANT_STALE"), state.diagnostic(identity))
        }
    }

    @Test fun syncFailuresRetainOnlyProtocolCodesWithoutLeakingNetworkMessages() {
        assertEquals("GRANT_STALE", capabilitySyncErrorCode(IllegalStateException("CAPABILITY_BINDING_FAILED:GRANT_STALE")))
        assertEquals("NETWORK_ERROR", capabilitySyncErrorCode(IOException("transport message containing private details")))
        assertEquals("CAPABILITY_SYNC_FAILED", capabilitySyncErrorCode(IllegalStateException("arbitrary message")))
    }

    @Test fun aDelayedSuccessFromThePreviousOwnerCannotOverwriteTheNewConnectionsFailure() {
        val target = MutableStateFlow(DeviceCapabilitySyncState("SYNC_FAILED", errorCode = "NETWORK_ERROR"))
        val delayed = DeviceCapabilitySyncState("SYNCED", localRevision = 1, hostRevision = 2)
        assertFalse(delayed.publishTo(target, { false }, { 1L }, { 2L }))
        assertEquals("SYNC_FAILED", target.value.status)
        assertEquals("NETWORK_ERROR", target.value.errorCode)
    }

    @Test fun losingOwnershipDuringTheFinalRevisionReadCannotWriteSynced() {
        val target = MutableStateFlow(DeviceCapabilitySyncState("SYNC_RETRYING", errorCode = "GRANT_STALE"))
        val delayed = DeviceCapabilitySyncState("SYNCED", localRevision = 1, hostRevision = 2)
        var owned = true
        assertFalse(delayed.publishTo(target, { owned }, { owned = false; 1L }, { 2L }))
        assertEquals("SYNC_RETRYING", target.value.status)
        assertEquals("GRANT_STALE", target.value.errorCode)
    }

    @Test fun finalWriteRechecksRevisionsInsteadOfTrustingTheEarlierFlowEmission() {
        val target = MutableStateFlow(DeviceCapabilitySyncState())
        val queued = DeviceCapabilitySyncState("SYNCED", localRevision = 1, hostRevision = 2,
            capabilities = listOf(entry("org.example.a", "1.0.0", "READY")))
        assertTrue(queued.publishTo(target, { true }, { 2L }, { 2L }))
        assertEquals("SYNC_REQUIRED", target.value.status)
        assertTrue(target.value.capabilities.isEmpty())
        assertTrue(queued.publishTo(target, { true }, { 1L }, { 3L }))
        assertEquals("SYNC_REQUIRED", target.value.status)
        assertTrue(queued.publishTo(target, { true }, { 1L }, { 2L }))
        assertEquals("SYNCED", target.value.status)
    }
}
