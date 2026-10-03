package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Raised when an invocation exceeds a resource the plugin declared. */
class BudgetExceeded(code: String) : IllegalArgumentException("BUDGET_EXCEEDED:$code")

/** Raised when an operation names a plugin the kernel has not registered. */
class PluginNotRegistered(val pluginId: String) :
    IllegalArgumentException("PLUGIN_NOT_REGISTERED:$pluginId")

data class ResourceBudget(
    val maxInvocationMillis: Long,
    val maxMemoryBytes: Long,
    val maxOutputBytes: Long,
    val maxConcurrentInvocations: Int,
    val maxDailyNetworkBytes: Long,
)

data class PluginResult(
    val output: ByteArray,
    val correlationId: String,
)

/** The host side of a plugin runtime. The WASM runtime implements this. */
interface PluginRuntime {
    fun invoke(
        identity: PluginIdentity,
        budget: ResourceBudget,
        input: ByteArray,
    ): ByteArray
}

data class PluginRegistration(
    val identity: PluginIdentity,
    val runtimeType: String,
    val declaredPrimitives: Set<String>,
    val budget: ResourceBudget,
    val network: NetworkAllowlist? = null,
    val state: PluginStateMachine = PluginStateMachine(PluginState.INSTALLED_DISABLED),
    val identityScopedGrants: Boolean = false,
)

/**
 * The platform kernel: the only component that may run a plugin.
 *
 * Every invocation is resolved through the same six-term intersection, so a
 * capability is reachable only when the host build owns it, the phone allows
 * it, the plugin declared it, the plugin is enabled, the pairing granted it and
 * the current session still permits it. Missing any term fails closed.
 */
class PluginKernel(
    private val hostEnvelope: HostEnvelope,
    private val phoneLimits: PhoneLimits,
    private val runtimes: Map<String, PluginRuntime>,
    private val audit: AndroidAuditStore,
    private val trustMode: DeveloperTrustMode,
    private val nativeLoader: NativePluginLoader,
    private val providerSelector: CapabilityProviderSelector,
    private val grants: (String) -> PairingGrant?,
    private val mediate: (KernelCallContext, String, ByteArray) -> ByteArray = { _, id, _ -> throw CapabilityDenied(id) },
) {
    companion object {
        const val RUNTIME_PROTECTED_WASM = "protected-wasm"
        const val RUNTIME_DEVELOPER_NATIVE = "developer-native"
        const val RUNTIME_COMPANION = "companion"
    }

    private val registrations = ConcurrentHashMap<String, PluginRegistration>()
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    @Volatile
    private var emergencyStopped = false
    private val emergencyStopListeners = mutableListOf<(Boolean) -> Unit>()
    private val currentCall = ThreadLocal<KernelCallContext>()

    /** Recheck the current local grant before each privileged host call. */
    fun call(identity: PluginIdentity, primitive: String, input: ByteArray): ByteArray {
        val context = currentCall.get() ?: throw CapabilityDenied(primitive)
        if (grants(context.pairingId)?.revision != context.grantRevision) throw CapabilityDenied(primitive)
        val registration = registrations[identity.pluginId] ?: throw CapabilityDenied(primitive)
        if (context.identity != identity || registration.identity != identity || emergencyStopped) throw CapabilityDenied(primitive)
        EffectiveCapabilities.require(CapabilityInputs(hostEnvelope.primitives, phoneLimits.primitives,
            registration.declaredPrimitives, registration.state.isExecutable(), grants(context.pairingId), context.session), primitive)
        requireIdentityGrant(registration, context.pairingId, primitive)
        val result = mediate(context, primitive, input.copyOf())
        if (result.size > context.budget.maxOutputBytes) throw BudgetExceeded("OUTPUT")
        audit.record(identity.pluginId, context.accountId, context.pairingId, primitive, AuditOutcome.ALLOWED, context.session.correlationId)
        return result
    }

    /**
     * The one-way system-level cut-off behind the settings' red button.
     *
     * It quarantines every enabled plugin, turns developer trust mode off (its
     * own listeners unload native code immediately), and re-checks at invoke
     * time so a plugin already mid-flight cannot start another capability. The
     * only way back is a process restart with a fresh, unauthorised kernel.
     *
     * @return how many enabled plugins were quarantined.
     */
    fun emergencyStop(correlationId: String): Int {
        emergencyStopped = true
        var stopped = 0
        for (registration in registrations.values) {
            if (registration.state.state == PluginState.ENABLED) {
                registration.state.transition(PluginState.QUARANTINED)
                stopped++
            }
        }
        trustMode.disable()
        audit.record(
            "platform", "platform", "platform",
            "emergency.stop", AuditOutcome.ALLOWED, correlationId,
        )
        synchronized(emergencyStopListeners) {
            emergencyStopListeners.toList().forEach { it(true) }
        }
        return stopped
    }

    fun isEmergencyStopped(): Boolean = emergencyStopped

    /**
     * Observes the cut-off, invoked immediately on registration so a surface
     * created after the stop cannot miss that it already happened.
     */
    fun onEmergencyStop(listener: (Boolean) -> Unit) {
        synchronized(emergencyStopListeners) {
            emergencyStopListeners += listener
        }
        listener(emergencyStopped)
    }

    fun register(registration: PluginRegistration) {
        val previous = registrations[registration.identity.pluginId]
        if (previous != null && previous.identity != registration.identity) throw ProviderRejected("IDENTITY_CONFLICT")
        registrations[registration.identity.pluginId] = registration
        semaphores[registration.identity.pluginId] =
            Semaphore(registration.budget.maxConcurrentInvocations.coerceAtLeast(1))
    }

    fun unregister(pluginId: String) {
        registrations.remove(pluginId)
        semaphores.remove(pluginId)
    }

    fun registrationFor(pluginId: String): PluginRegistration? = registrations[pluginId]

    private fun requireIdentityGrant(registration: PluginRegistration, pairingId: String, capability: String) {
        if (registration.identityScopedGrants && identityGrantKey(registration.identity, capability) !in grants(pairingId)?.granted.orEmpty()) {
            throw CapabilityDenied(capability)
        }
    }

    fun enable(pluginId: String) {
        val registration = registrations[pluginId]
            ?: throw PluginNotRegistered(pluginId)
        if (registration.runtimeType == RUNTIME_DEVELOPER_NATIVE && !trustMode.isEnabled()) {
            throw NativePluginRejected("TRUST_MODE_DISABLED")
        }
        registration.state.transition(PluginState.ENABLED)
    }

    fun disable(pluginId: String) {
        val registration = registrations[pluginId] ?: return
        if (registration.state.state == PluginState.ENABLED) {
            registration.state.transition(PluginState.INSTALLED_DISABLED)
        }
    }

    fun quarantine(pluginId: String) {
        val registration = registrations[pluginId] ?: return
        if (registration.state.state == PluginState.ENABLED) {
            registration.state.transition(PluginState.QUARANTINED)
        }
    }

    /**
     * The effective capability set for one plugin under one pairing and session.
     *
     * Exposed so the settings surface and the kernel agree on what a plugin can
     * do: both read the same computation instead of maintaining parallel views.
     */
    fun effectiveCapabilities(
        pluginId: String,
        pairingId: String,
        session: SessionConstraints,
    ): EffectiveCapabilitySet {
        val registration = registrations[pluginId] ?: return EffectiveCapabilitySet(emptySet(), false)
        return EffectiveCapabilities.compute(
            CapabilityInputs(
                hostEnvelope = hostEnvelope.primitives,
                phoneLimits = phoneLimits.primitives,
                manifestRequests = registration.declaredPrimitives,
                pluginEnabled = registration.state.isExecutable(),
                pairingGrant = grants(pairingId),
                session = session,
            ),
        )
    }

    /**
     * Runs one capability.
     *
     * Deviation from the plan's parameter list: [session] is passed in rather
     * than held as ambient state, because "current session constraints" is one
     * of the six terms in the intersection and a term that cannot be supplied
     * cannot be intersected.
     */
    fun invoke(
        identity: PluginIdentity,
        accountId: String,
        pairingId: String,
        capability: String,
        input: ByteArray,
        session: SessionConstraints,
    ): PluginResult {
        val correlationId = session.correlationId
        // A tripped cut-off denies everything before any other term is even
        // evaluated: the intersection cannot resurrect what the user stopped.
        if (emergencyStopped) {
            audit.record(
                identity.pluginId, accountId, pairingId,
                "invoke", AuditOutcome.DENIED, correlationId,
            )
            throw CapabilityDenied(capability)
        }
        val registration = registrations[identity.pluginId]
        if (registration == null || registration.identity != identity) {
            audit.record(
                identity.pluginId, accountId, pairingId,
                "invoke", AuditOutcome.DENIED, correlationId,
            )
            throw CapabilityDenied(capability)
        }

        try {
            // "Plugin enabled" is one of the six terms in the intersection, so
            // a plugin that is installed but not enabled is denied by the
            // intersection rather than by a separate check. That keeps the
            // failure mode identical to every other denial: no capability, no
            // information about why, no separate path to reason about.
            //
            // A plugin may only be reached as the provider this pairing
            // actually selected; a plugin that merely declares the capability
            // is not thereby authorised to serve it.
            val selected = providerSelector.select(capability, pairingId)
            if (selected.identity != registration.identity) {
                throw ProviderRejected("NOT_PROVIDER:$capability")
            }

            EffectiveCapabilities.require(
                CapabilityInputs(
                    hostEnvelope = hostEnvelope.primitives,
                    phoneLimits = phoneLimits.primitives,
                    manifestRequests = registration.declaredPrimitives,
                    pluginEnabled = registration.state.isExecutable(),
                    pairingGrant = grants(pairingId),
                    session = session,
                    ),
                    capability,
                    )

            val semaphore = semaphores[identity.pluginId]!!
            requireIdentityGrant(registration, pairingId, capability)
            if (session.background && !EffectiveCapabilities.compute(CapabilityInputs(hostEnvelope.primitives,
                phoneLimits.primitives, registration.declaredPrimitives, true, grants(pairingId), session)).backgroundAllowed) {
                throw CapabilityDenied("kernel.background.run")
            }
            val grantRevision=grants(pairingId)?.revision ?: error("PAIRING_REQUIRED")
            if (!semaphore.tryAcquire()) throw BudgetExceeded("CONCURRENCY")
            val output = try {
                currentCall.set(KernelCallContext(identity, accountId, pairingId, session, registration.budget,grantRevision))
                when (registration.runtimeType) {
                    RUNTIME_PROTECTED_WASM -> runProtected(registration, registration.identity, input)
                    RUNTIME_DEVELOPER_NATIVE -> runNative(registration, registration.identity, input)
                    else -> throw ProviderRejected("UNSUPPORTED_RUNTIME:${registration.runtimeType}")
                }
            } finally {
                currentCall.remove()
                semaphore.release()
            }
            if (grants(pairingId)?.revision!=grantRevision || !registration.state.isExecutable() || emergencyStopped) throw CapabilityDenied(capability)

            if (output.size > registration.budget.maxOutputBytes) {
                throw BudgetExceeded("OUTPUT")
            }

            audit.record(
                identity.pluginId, accountId, pairingId,
                "invoke", AuditOutcome.ALLOWED, correlationId,
            )
            return PluginResult(output = output, correlationId = correlationId)
        } catch (cause: CapabilityDenied) {
            audit.record(
                identity.pluginId, accountId, pairingId,
                "invoke", AuditOutcome.DENIED, correlationId,
            )
            throw cause
        } catch (cause: Exception) {
            audit.record(
                identity.pluginId, accountId, pairingId,
                "invoke", AuditOutcome.FAILED, correlationId,
            )
            throw cause
        }
    }

    private fun runProtected(
        registration: PluginRegistration,
        identity: PluginIdentity,
        input: ByteArray,
    ): ByteArray {
        val runtime = runtimes[RUNTIME_PROTECTED_WASM]
            ?: throw ProviderRejected("NO_RUNTIME:protected-wasm")
        return runtime.invoke(identity, registration.budget, input)
    }

    /**
     * Native execution is re-checked at call time, not only at load time: trust
     * mode can be switched off after a plugin was loaded.
     */
    private fun runNative(
        registration: PluginRegistration,
        identity: PluginIdentity,
        input: ByteArray,
    ): ByteArray {
        if (!trustMode.isEnabled()) throw NativePluginRejected("TRUST_MODE_DISABLED")
        if (!nativeLoader.isLoaded(identity.pluginId)) {
            throw NativePluginRejected("NOT_LOADED")
        }
        val runtime = runtimes[RUNTIME_DEVELOPER_NATIVE]
            ?: throw ProviderRejected("NO_RUNTIME:developer-native")
        return runtime.invoke(identity, registration.budget, input)
    }
}

/** Grants bind the author, but survive an update signed by the same author. */
fun identityGrantKey(identity: PluginIdentity, capability: String): String =
    "plugin:${identity.pluginId}:${identity.authorKeyFingerprint}:$capability"
