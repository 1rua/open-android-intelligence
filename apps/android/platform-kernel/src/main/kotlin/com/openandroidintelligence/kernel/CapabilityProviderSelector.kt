package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity

/** Raised when a provider selection would bypass authorisation. */
class ProviderRejected(code: String) : IllegalArgumentException("PROVIDER_REJECTED:$code")

/**
 * The plugin chosen to serve one capability for one pairing.
 *
 * [grantRevision] is the authorisation generation this selection was made
 * under. A selection made under an older revision is no longer evidence that
 * the user authorised anything.
 */
data class ProviderSelection(
    val capability: String,
    val pairingId: String,
    val identity: PluginIdentity,
    val grantRevision: Long,
    val inheritedPermissions: Boolean,
)

/**
 * Chooses which plugin serves a capability.
 *
 * There is a phone-level default and an optional per-Gateway override. An
 * override is a fresh authorisation decision: the new provider must not inherit
 * the previous provider's permissions, so switching always moves the grant
 * revision forward.
 */
class CapabilityProviderSelector(
    private val phoneDefaults: Map<String, PluginIdentity>,
) {
    private val overrides = HashMap<Pair<String, String>, ProviderSelection>()
    private var revisionCounter = 0L

    @Synchronized fun select(capability: String, pairingId: String): ProviderSelection {
        val key = capability to pairingId
        overrides[key]?.let { return it }
        val identity = phoneDefaults[capability]
            ?: throw ProviderRejected("NO_PROVIDER:$capability")
        return ProviderSelection(
            capability = capability,
            pairingId = pairingId,
            identity = identity,
            grantRevision = 0L,
            inheritedPermissions = false,
        )
    }

    /**
     * Points a capability at a different plugin for one pairing.
     *
     * Selecting the plugin that already serves the capability is a no-op:
     * nothing changed, so no re-authorisation is due. Selecting a different
     * plugin always allocates a new grant revision, which is what makes the
     * "switching providers requires re-authorisation" rule enforceable rather
     * than documentary.
     */
    @Synchronized fun setOverride(
        capability: String,
        pairingId: String,
        identity: PluginIdentity,
    ): ProviderSelection {
        val key = capability to pairingId
        val current = overrides[key]
        if (current != null && current.identity == identity) return current

        val revision = ++revisionCounter
        val selection = ProviderSelection(
            capability = capability,
            pairingId = pairingId,
            identity = identity,
            grantRevision = revision,
            inheritedPermissions = false,
        )
        overrides[key] = selection
        return selection
    }

    /** Retain the chosen author/provider when its verified package version changes. */
    @Synchronized fun refreshOverrides(providers: Map<String,List<PluginIdentity>>) {
        overrides.replaceAll { _, selection ->
            val updated = providers[selection.capability].orEmpty().firstOrNull {
                it.pluginId == selection.identity.pluginId &&
                    it.authorKeyFingerprint == selection.identity.authorKeyFingerprint
            }
            // An absent or differently signed provider must not fall back to another plugin.
            if (updated == null) selection else selection.copy(identity = updated)
        }
    }

    @Synchronized fun clearOverride(capability: String, pairingId: String) {
        overrides.remove(capability to pairingId)
    }

    @Synchronized fun revisionFor(capability: String, pairingId: String): Long =
        overrides[capability to pairingId]?.grantRevision ?: 0L
}
