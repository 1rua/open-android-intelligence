package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity

/** Raised when the requested primitive is not in the effective capability set. */
class CapabilityDenied(val primitive: String) :
    IllegalArgumentException("CAPABILITY_DENIED:$primitive")

/**
 * What this build of the host is compiled to offer. A capability the host does
 * not own can never be granted, whatever the manifest, the user or the pairing
 * says.
 */
data class HostEnvelope(val primitives: Set<String>)

/**
 * Restrictions applied on this phone: user settings, device policy or a global
 * kill switch. They narrow the envelope but can never widen it.
 */
data class PhoneLimits(val primitives: Set<String>)

/**
 * Android-local capability state for an authenticated pairing.
 *
 * [defaultEnabled] is true only after the client has established the current
 * local model. Legacy explicit grants remain readable until a local migration
 * review; they are not another server permission source. Background execution
 * always needs its own explicit local grant.
 */
data class PairingGrant(
    val pairingId: String,
    val granted: Set<String>,
    val revision: Long,
    val backgroundSync: Boolean = false,
    val defaultEnabled: Boolean = false,
    val disabled: Set<String> = emptySet(),
) {
    fun permits(capability: String): Boolean {
        val pluginDecision = pluginCapabilityDecisionKey(capability)
        return capability !in disabled && (pluginDecision == null || pluginDecision !in disabled) &&
            (capability in granted || (defaultEnabled && !requiresExplicitLocalGrant(capability)))
    }
}

private fun requiresExplicitLocalGrant(capability: String): Boolean =
    capability == "kernel.background.run" || capability.endsWith(":kernel.background.run")

/** A local off decision follows this plugin author through declaration and version changes. */
fun pluginCapabilityDecisionKey(identity: PluginIdentity): String =
    identityGrantKey(identity, "capabilities")

/** Background consent has its own explicit decision and is never inferred from this switch. */
internal fun pluginCapabilityDecisionKey(capability: String): String? {
    if (!capability.startsWith("plugin:") || requiresExplicitLocalGrant(capability)) return null
    val scope = capability.split(':', limit = 4)
    if (scope.size != 4 || scope.any(String::isBlank)) return null
    return "plugin:${scope[1]}:${scope[2]}:capabilities"
}

/** Preserve the meaning of v2 per-declaration off decisions even after those declarations disappear. */
internal fun stablePluginCapabilityDenials(disabled: Set<String>): Set<String> =
    disabled + disabled.mapNotNull(::pluginCapabilityDecisionKey)

/** Constraints carried by the current session or single request. */
data class SessionConstraints(
    val primitives: Set<String>,
    val background: Boolean,
    val correlationId: String,
)

data class CapabilityInputs(
    val hostEnvelope: Set<String>,
    val phoneLimits: Set<String>,
    val manifestRequests: Set<String>,
    val pluginEnabled: Boolean,
    val pairingGrant: PairingGrant?,
    val session: SessionConstraints,
)

data class EffectiveCapabilitySet(
    val primitives: Set<String>,
    val backgroundAllowed: Boolean,
)

/**
 * The single place where the effective capability set is computed.
 *
 *   effective = host envelope
 *             ∩ phone limits
 *             ∩ manifest requests
 *             ∩ plugin enabled
 *             ∩ Android-local effective state (defaults plus explicit decisions)
 *             ∩ session constraints
 *
 * Every term is computed separately and every one of them is required: a
 * missing authenticated pairing state or a disabled plugin yields the empty
 * set. Client defaults never grant an undeclared primitive or background access.
 */
object EffectiveCapabilities {
    fun compute(inputs: CapabilityInputs): EffectiveCapabilitySet {
        val grant = inputs.pairingGrant
        if (!inputs.pluginEnabled || grant == null) {
            return EffectiveCapabilitySet(emptySet(), backgroundAllowed = false)
        }

        val primitives = inputs.hostEnvelope
            .intersect(inputs.phoneLimits)
            .intersect(inputs.manifestRequests)
            .filterTo(linkedSetOf(), grant::permits)
            .intersect(inputs.session.primitives)

        // Background execution needs the session to ask for it, the plugin to
        // have declared it and the pairing to have granted sync.
        val backgroundAllowed = inputs.session.background &&
            grant.backgroundSync &&
            "kernel.background.run" in primitives

        return EffectiveCapabilitySet(primitives, backgroundAllowed)
    }

    fun require(inputs: CapabilityInputs, primitive: String) {
        val effective = compute(inputs)
        if (primitive !in effective.primitives) throw CapabilityDenied(primitive)
    }
}
