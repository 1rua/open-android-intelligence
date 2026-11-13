package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The platform primitive IDs currently surfaced by the pairing settings UI. */
object PairingGrantCapabilities {
    const val SMS = "org.openandroidintelligence.sms.query@1.0.0"
    const val NOTIFICATIONS = "org.openandroidintelligence.notifications.query@1.0.0"
}

/** Authenticated transport relationship; never a partition of capability policy storage. */
data class PairingGrantBinding(
    val gatewayId: String,
    val accountId: String,
    val installationId: String,
) {
    init {
        val validGateway = runCatching {
            val uri = URI(gatewayId)
            (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        require(validGateway) { "pairing grant gateway must be http or https" }
        require(accountId.isNotBlank()) { "pairing grant account must not be blank" }
        require(installationId.isNotBlank()) { "pairing grant installation must not be blank" }
    }

    /** Opaque transport/journal key. Kept for migration and request ownership, not policy storage. */
    val storageKey: String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        listOf(gatewayId, accountId, installationId)
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8),
    )

    /** A local identity for the binding; it is not a Gateway wire identity. */
    val pairingId: String = "pairing_" + sha256Hex(storageKey).take(32)
}

/** Installation-wide decisions projected onto a transport binding; pairingId is never a storage scope. */
data class PairingGrantState(
    val pairingId: String,
    val granted: Set<String> = emptySet(),
    val revision: Long = 0L,
    val authorizationModelVersion: Int = CURRENT_AUTHORIZATION_MODEL,
    val explicitDisabled: Set<String> = emptySet(),
    val capabilityPolicies: Map<String, CapabilityPolicy> = emptyMap(),
    val localError: String? = null,
) {
    val migrationRequired: Boolean get() = authorizationModelVersion != CURRENT_AUTHORIZATION_MODEL

    init {
        require(pairingId.isNotBlank()) { "pairing grant ID must not be blank" }
        require(revision >= 0L) { "pairing grant revision must not be negative" }
        require(authorizationModelVersion in 0..CURRENT_AUTHORIZATION_MODEL) { "unknown local authorization model" }
    }

    fun asKernelGrant(): PairingGrant = PairingGrant(
        pairingId = pairingId,
        granted = if (localError == null) granted else emptySet(),
        revision = revision,
        backgroundSync = "kernel.background.run" in granted && "kernel.background.run" !in explicitDisabled,
        defaultEnabled = !migrationRequired && localError == null,
        disabled = stablePluginCapabilityDenials(explicitDisabled),
    )

    fun permits(capability: String): Boolean = asKernelGrant().permits(capability)

    fun withStablePluginCapabilityDenials(): PairingGrantState {
        val disabled = stablePluginCapabilityDenials(explicitDisabled)
        return if (disabled == explicitDisabled) this else copy(explicitDisabled = disabled)
    }

    companion object {
        const val CURRENT_AUTHORIZATION_MODEL = 4

        /** Legacy representation accepted only by migration readers, never an execution permit. */
        fun legacy(pairingId: String, revision: Long = 0L) = PairingGrantState(
            pairingId = pairingId, revision = revision, authorizationModelVersion = 1,
        )
    }
}

interface PairingGrantStore {
    fun load(binding: PairingGrantBinding): PairingGrantState

    fun save(binding: PairingGrantBinding, state: PairingGrantState)

}

/** Deterministic store used by JVM tests and by no Android UI code. */
class InMemoryPairingGrantStore : PairingGrantStore {
    private var global = PairingGrantState(INSTALLATION_POLICY_ID)
    @Synchronized override fun load(binding: PairingGrantBinding): PairingGrantState =
        global.copy(pairingId = binding.pairingId)
    @Synchronized override fun save(binding: PairingGrantBinding, state: PairingGrantState) {
        require(state.pairingId == binding.pairingId) { "pairing grant binding mismatch" }
        global = state.withStablePluginCapabilityDenials().copy(pairingId = INSTALLATION_POLICY_ID)
    }
}

const val INSTALLATION_POLICY_ID = "android-installation-capability-policy"
private val LOCAL_POLICY_BINDING = PairingGrantBinding("https://android.invalid", "local-policy", "installation")

/**
 * Owns the current local grant and exposes it both to Compose and to the kernel.
 * Every mutation is persisted before it becomes observable and is audited once.
 */
class PairingGrantStateHolder(
    private val store: PairingGrantStore,
    private val audit: AndroidAuditStore,
) {
    private val _state = MutableStateFlow<PairingGrantState?>(
        loadPolicy(LOCAL_POLICY_BINDING).copy(pairingId = INSTALLATION_POLICY_ID),
    )
    val state: StateFlow<PairingGrantState?> = _state.asStateFlow()

    private data class BoundGrant(val binding: PairingGrantBinding, val state: PairingGrantState,
        val current: () -> Boolean)

    @Volatile private var boundGrant: BoundGrant? = null
    private val verifiedBindings = java.util.concurrent.ConcurrentHashMap<String, PairingGrantBinding>()
    private val connectionChecks = java.util.concurrent.ConcurrentHashMap<String, () -> Boolean>()
    private fun current(check: () -> Boolean): Boolean = runCatching(check).getOrDefault(false)
    val authorization = CapabilityAuthorizationController()

    @Synchronized
    fun bind(nextBinding: PairingGrantBinding, connectionCurrent: () -> Boolean = { true }) {
        boundGrant = null
        _state.value = _state.value?.copy(pairingId = INSTALLATION_POLICY_ID)
        var loaded = loadPolicy(nextBinding)
        if (loaded.localError == "LOCAL_AUTHORIZATION_UPDATE_INCOMPLETE") {
            // Recover an interrupted transaction to manual mode without expanding data access.
            // Explicit off decisions and saved scopes survive; a failed audit remains a deny barrier.
            val recovered = loaded.copy(localError = null, revision = Math.addExact(loaded.revision, 1L),
                capabilityPolicies = loaded.capabilityPolicies.mapValues { (_, policy) ->
                    policy.copy(mode = CapabilityAuthorizationMode.MANUAL)
                })
            loaded = try {
                audit.record("platform", nextBinding.accountId, nextBinding.pairingId,
                    "capability.policy.recovered", AuditOutcome.ALLOWED, "policy-recovery-${recovered.revision}")
                store.save(nextBinding, recovered)
                recovered
            } catch (_: Exception) { loaded }
        }
        if (loaded.localError == null) {
            boundGrant = BoundGrant(nextBinding, loaded, connectionCurrent)
            _state.value = loaded
        } else _state.value = loaded.copy(pairingId = INSTALLATION_POLICY_ID)
    }

    fun capabilityPolicy(key: String): CapabilityPolicy =
        if (_state.value?.localError != null) CapabilityPolicy(enabled = false)
        else _state.value?.capabilityPolicies?.get(key) ?: CapabilityPolicy()

    @Synchronized
    fun updateCapabilityPolicy(key: String, policy: CapabilityPolicy): PairingGrantState {
        require(key.isNotBlank())
        return update { it.copy(capabilityPolicies = it.capabilityPolicies + (key to policy.freeze())) }
    }

    @Synchronized
    fun updateCapabilityPolicies(policies: Map<String, CapabilityPolicy>): PairingGrantState =
        update { it.copy(capabilityPolicies = it.capabilityPolicies + policies.mapValues { (_, p) -> p.freeze() }) }

    /**
     * 用给定映射**整体替换**能力策略表。
     *
     * 只有恢复语义需要它：设置导入失败回滚时，导入期间新建的策略条目必须一并消失，
     * 否则「恢复原状」会留下一条本机从未存在过的（哪怕当前是关闭的）策略。
     * 与其它写入一样走 [update]：先落盘拒绝屏障、再审计、最后提交，失败即拒绝。
     */
    @Synchronized
    fun replaceCapabilityPolicies(policies: Map<String, CapabilityPolicy>): PairingGrantState =
        update { it.copy(capabilityPolicies = policies.mapValues { (_, p) -> p.freeze() }) }

    fun recordRequestDecision(requestId: String, accountId: String, pairingId: String, action: String, allowed: Boolean) {
        audit.record("platform", accountId, pairingId, action,
            if (allowed) AuditOutcome.ALLOWED else AuditOutcome.DENIED, requestId)
    }

    fun revokeTemporaryApprovals() { authorization.revokeAll() }

    @Synchronized
    fun updatePrimitive(primitive: String, enabled: Boolean): PairingGrantState =
        update { current ->
            val nextGranted = if (enabled) current.granted + primitive else current.granted - primitive
            current.copy(granted = nextGranted,
                explicitDisabled = if (enabled) current.explicitDisabled - primitive else current.explicitDisabled + primitive)
        }

    @Synchronized
    fun updatePrimitives(primitives: Set<String>, enabled: Boolean): PairingGrantState = update { current ->
        current.copy(granted = if (enabled) current.granted + primitives else current.granted - primitives,
            explicitDisabled = if (enabled) current.explicitDisabled - primitives else current.explicitDisabled + primitives)
    }

    /** Reopening this author never changes another author's deny or the independent background choice. */
    @Synchronized
    fun updatePluginCapabilities(identity: PluginIdentity, declared: Set<String>, enabled: Boolean): PairingGrantState = update { current ->
        val decision = pluginCapabilityDecisionKey(identity)
        val scoped = declared.map { identityGrantKey(identity, it) }
            .filterTo(linkedSetOf()) { pluginCapabilityDecisionKey(it) == decision }
        val oldDecisions = current.explicitDisabled.filter { pluginCapabilityDecisionKey(it) == decision }.toSet()
        current.copy(
            granted = if (enabled) current.granted else current.granted.filterNot {
                pluginCapabilityDecisionKey(it) == decision
            }.toSet(),
            explicitDisabled = if (enabled) current.explicitDisabled - oldDecisions
                else current.explicitDisabled + scoped + decision,
        )
    }

    /** The shared background primitive only aggregates independently allowed plugin authors. */
    @Synchronized
    fun updatePluginBackground(identity: PluginIdentity, enabled: Boolean): PairingGrantState = update { current ->
        val background = "kernel.background.run"
        val scoped = identityGrantKey(identity, background)
        val granted = if (enabled) current.granted + scoped else current.granted - scoped
        val disabled = if (enabled) current.explicitDisabled - scoped else current.explicitDisabled + scoped
        val anyAllowedAuthor = granted.any {
            it.startsWith("plugin:") && it.endsWith(":$background") && it !in disabled
        }
        current.copy(
            granted = if (anyAllowedAuthor) granted + background else granted - background,
            explicitDisabled = disabled,
        )
    }

    fun currentBinding(): PairingGrantBinding? = boundGrant?.binding

    /** Only the authenticated connection composition can register these transport relationships. */
    fun attachConnection(binding: PairingGrantBinding, connectionCurrent: () -> Boolean = { true }): String {
        check(_state.value?.migrationRequired == false && _state.value?.localError == null) { "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED" }
        val registrationId = UUID.randomUUID().toString()
        connectionChecks[registrationId] = connectionCurrent
        verifiedBindings[registrationId] = binding
        return registrationId
    }

    fun detachConnection(binding: PairingGrantBinding, registrationId: String? = null) {
        if (registrationId != null) {
            if (verifiedBindings.remove(registrationId, binding)) connectionChecks.remove(registrationId)
        } else verifiedBindings.entries.filter { it.value == binding }.forEach {
            if (verifiedBindings.remove(it.key, it.value)) connectionChecks.remove(it.key)
        }
    }
    fun isConnectionCurrent(binding: PairingGrantBinding): Boolean =
        boundGrant?.let { it.binding == binding && current(it.current) } == true ||
            verifiedBindings.entries.any { it.value == binding && connectionChecks[it.key]?.let(::current) == true }

    fun currentKernelGrant(pairingId: String): PairingGrant? =
        _state.value?.takeIf { boundGrant?.let { it.binding.pairingId == pairingId && current(it.current) } == true ||
            verifiedBindings.entries.any { it.value.pairingId == pairingId && connectionChecks[it.key]?.let(::current) == true } }
            ?.copy(pairingId = pairingId)?.asKernelGrant()

    @Synchronized
    fun unbind() {
        boundGrant = null
        _state.value = _state.value?.copy(pairingId = INSTALLATION_POLICY_ID)
    }

    @Synchronized
    fun clearCurrent() {
        unbind()
    }

    @Synchronized
    fun clearFor(target: PairingGrantBinding) {
        detachConnection(target)
        if (boundGrant?.binding == target) unbind()
    }

    private fun loadPolicy(binding: PairingGrantBinding): PairingGrantState = try {
        store.load(binding).also {
            check(it.pairingId == binding.pairingId && !it.migrationRequired) { "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED" }
        }.withStablePluginCapabilityDenials()
    } catch (failure: Exception) {
        PairingGrantState(binding.pairingId, localError = failure.message?.takeIf {
            it.matches(Regex("[A-Z0-9_]{1,128}"))
        } ?: "LOCAL_AUTHORIZATION_STATE_UNAVAILABLE")
    }

    private fun update(
        transform: (PairingGrantState) -> PairingGrantState,
    ): PairingGrantState {
        val bound = boundGrant
        val currentBinding = bound?.binding ?: LOCAL_POLICY_BINDING
        val current = (_state.value ?: store.load(currentBinding)).copy(pairingId = currentBinding.pairingId)
        check(current.localError == null) { current.localError ?: "LOCAL_AUTHORIZATION_STATE_UNAVAILABLE" }
        val next = transform(current).withStablePluginCapabilityDenials()
        if (next == current) return current
        val persisted = next.copy(revision = Math.addExact(current.revision, 1L))
        // Commit a durable deny barrier before auditing. A crash or audit failure must never
        // resurrect the previous automatic grant or complete an unconfirmed permission expansion.
        val barrier = persisted.copy(localError = "LOCAL_AUTHORIZATION_UPDATE_INCOMPLETE")
        store.save(currentBinding, barrier)
        authorization.revokeAll()
        if (bound != null) boundGrant = BoundGrant(currentBinding, barrier, bound.current)
        _state.value = if (bound == null) barrier.copy(pairingId = INSTALLATION_POLICY_ID) else barrier
        try {
            audit.record(
                pluginId = "platform",
                accountId = currentBinding.accountId,
                pairingId = currentBinding.pairingId,
                action = "capability.policy.changed",
                outcome = AuditOutcome.ALLOWED,
                correlationId = "grant-${persisted.revision}-${UUID.randomUUID()}",
            )
        } catch (cause: Exception) {
            throw IllegalStateException("LOCAL_AUTHORIZATION_AUDIT_FAILED", cause)
        }
        store.save(currentBinding, persisted)
        authorization.revokeAll()
        if (bound != null) boundGrant = BoundGrant(currentBinding, persisted, bound.current)
        _state.value = if (bound == null) persisted.copy(pairingId = INSTALLATION_POLICY_ID) else persisted
        return persisted
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
