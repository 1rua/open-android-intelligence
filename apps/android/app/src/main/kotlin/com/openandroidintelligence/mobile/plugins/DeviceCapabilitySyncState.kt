package com.openandroidintelligence.mobile.plugins

import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow

/** Matches the identity used by Core's capability inventory; providers never share a UI status. */
data class DeviceCapabilityIdentity(val pluginId: String, val capabilityId: String, val version: String)

data class DeviceCapabilityDiagnostic(val status: String, val reason: String? = null)

/** One Android-local observation, including the exact local snapshot and Core acknowledgement. */
data class DeviceCapabilitySyncState(
    val status: String = "SYNC_REQUIRED",
    val localRevision: Long? = null,
    val hostRevision: Long? = null,
    val grantRevision: Int? = null,
    val grantDigest: String? = null,
    val capabilities: List<JsonValue.JObject> = emptyList(),
    val errorCode: String? = null,
) {
    val statuses: Map<DeviceCapabilityIdentity, DeviceCapabilityDiagnostic> = capabilities.mapNotNull { entry ->
        val plugin = JsonFields.string(entry, "pluginId") ?: return@mapNotNull null
        val capability = JsonFields.string(entry, "capabilityId") ?: return@mapNotNull null
        val version = JsonFields.string(entry, "capabilityVersion") ?: return@mapNotNull null
        DeviceCapabilityIdentity(plugin, capability, version) to DeviceCapabilityDiagnostic(
            JsonFields.string(entry, "status") ?: "SYNC_REQUIRED", JsonFields.string(entry, "reason"),
        )
    }.toMap()

    fun forLocalState(local: Long, host: Long): DeviceCapabilitySyncState =
        if ((localRevision != null && localRevision != local) || (hostRevision != null && hostRevision != host)) {
            DeviceCapabilitySyncState(localRevision = local, hostRevision = host)
        } else this

    /** Called on Runtime's main dispatcher, with ownership checked around the final local read. */
    internal fun publishTo(
        target: MutableStateFlow<DeviceCapabilitySyncState>,
        ownerCurrent: () -> Boolean,
        currentLocalRevision: () -> Long,
        currentHostRevision: () -> Long,
    ): Boolean {
        if (!ownerCurrent()) return false
        val current = forLocalState(currentLocalRevision(), currentHostRevision())
        if (!ownerCurrent()) return false
        target.value = current
        return true
    }

    fun diagnostic(identity: DeviceCapabilityIdentity): DeviceCapabilityDiagnostic =
        if (status == "SYNCED") statuses[identity] ?: DeviceCapabilityDiagnostic("STATUS_UNAVAILABLE")
        else DeviceCapabilityDiagnostic(status, errorCode)
}

/** Persist only bounded protocol codes, never arbitrary network messages or response bodies. */
internal fun capabilitySyncErrorCode(cause: Exception): String {
    val code = cause.message?.substringAfterLast(':')
    if (code != null && Regex("[A-Z][A-Z0-9_]{0,127}").matches(code)) return code
    return if (cause is IOException) "NETWORK_ERROR" else "CAPABILITY_SYNC_FAILED"
}
