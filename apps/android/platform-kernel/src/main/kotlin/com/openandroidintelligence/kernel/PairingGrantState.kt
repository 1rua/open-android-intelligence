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

/** Stable local scope for one Gateway account on one Android installation. */
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

    /** The storage key is opaque so account or URL characters cannot form a key hierarchy. */
    val storageKey: String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        listOf(gatewayId, accountId, installationId)
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8),
    )

    /** A local identity for the binding; it is not a Gateway wire identity. */
    val pairingId: String = "pairing_" + sha256Hex(storageKey).take(32)
}

/** Persisted user decisions for one local Gateway pairing scope. */
data class PairingGrantState(
    val pairingId: String,
    val granted: Set<String> = emptySet(),
    val revision: Long = 0L,
    val screenSelectionEnabled: Boolean = false,
    val authorizationModelVersion: Int = CURRENT_AUTHORIZATION_MODEL,
    val explicitDisabled: Set<String> = emptySet(),
) {
    val migrationRequired: Boolean get() = authorizationModelVersion != CURRENT_AUTHORIZATION_MODEL

    init {
        require(pairingId.isNotBlank()) { "pairing grant ID must not be blank" }
        require(revision >= 0L) { "pairing grant revision must not be negative" }
        require(authorizationModelVersion in 0..CURRENT_AUTHORIZATION_MODEL) { "unknown local authorization model" }
    }

    fun asKernelGrant(): PairingGrant = PairingGrant(
        pairingId = pairingId,
        granted = granted,
        revision = revision,
        backgroundSync = "kernel.background.run" in granted && "kernel.background.run" !in explicitDisabled,
        defaultEnabled = !migrationRequired,
        disabled = stablePluginCapabilityDenials(explicitDisabled),
    )

    fun permits(capability: String): Boolean = asKernelGrant().permits(capability)

    fun withStablePluginCapabilityDenials(): PairingGrantState {
        val disabled = stablePluginCapabilityDenials(explicitDisabled)
        return if (disabled == explicitDisabled) this else copy(explicitDisabled = disabled)
    }

    companion object {
        const val CURRENT_AUTHORIZATION_MODEL = 2

        /** Missing or unreadable legacy state is ambiguous, never fresh consent. */
        fun legacy(pairingId: String, revision: Long = 0L) = PairingGrantState(
            pairingId = pairingId, revision = revision, authorizationModelVersion = 1,
        )
    }
}

interface PairingGrantStore {
    fun load(binding: PairingGrantBinding): PairingGrantState

    fun save(binding: PairingGrantBinding, state: PairingGrantState)

    fun clear(binding: PairingGrantBinding)
}

/** Deterministic store used by JVM tests and by no Android UI code. */
class InMemoryPairingGrantStore : PairingGrantStore {
    private val lock = Any()
    private val states = mutableMapOf<String, PairingGrantState>()

    override fun load(binding: PairingGrantBinding): PairingGrantState = synchronized(lock) {
        states[binding.storageKey] ?: PairingGrantState(binding.pairingId)
    }

    override fun save(binding: PairingGrantBinding, state: PairingGrantState) {
        require(state.pairingId == binding.pairingId) { "pairing grant binding mismatch" }
        synchronized(lock) {
            states[binding.storageKey] = state.withStablePluginCapabilityDenials()
        }
    }

    override fun clear(binding: PairingGrantBinding) {
        synchronized(lock) {
            val current = states[binding.storageKey]
            states[binding.storageKey] = PairingGrantState.legacy(binding.pairingId, (current?.revision ?: 0L) + 1L)
                .copy(explicitDisabled = current?.explicitDisabled.orEmpty())
        }
    }
}

/**
 * Owns the current local grant and exposes it both to Compose and to the kernel.
 * Every mutation is persisted before it becomes observable and is audited once.
 */
class PairingGrantStateHolder(
    private val store: PairingGrantStore,
    private val audit: AndroidAuditStore,
) {
    private val _state = MutableStateFlow<PairingGrantState?>(null)
    val state: StateFlow<PairingGrantState?> = _state.asStateFlow()

    private var binding: PairingGrantBinding? = null

    @Synchronized
    fun bind(nextBinding: PairingGrantBinding) {
        binding = nextBinding
        val loaded = runCatching { store.load(nextBinding) }
            .getOrElse { PairingGrantState.legacy(nextBinding.pairingId) }
        _state.value = loaded.takeIf { it.pairingId == nextBinding.pairingId }?.withStablePluginCapabilityDenials()
            ?: PairingGrantState.legacy(nextBinding.pairingId)
    }

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

    /** One local review enables defaults without granting background or system permissions. */
    @Synchronized
    fun completeCapabilityMigration(): PairingGrantState = update { current ->
        current.copy(authorizationModelVersion = PairingGrantState.CURRENT_AUTHORIZATION_MODEL)
    }

    @Synchronized
    fun updateScreenSelection(enabled: Boolean): PairingGrantState =
        update { current -> current.copy(screenSelectionEnabled = enabled) }

    @Synchronized
    fun currentBinding(): PairingGrantBinding? = binding

    fun currentKernelGrant(pairingId: String): PairingGrant? =
        _state.value?.takeIf { binding?.pairingId == pairingId }?.asKernelGrant()

    @Synchronized
    fun unbind() {
        binding = null
        _state.value = null
    }

    @Synchronized
    fun clearCurrent() {
        val currentBinding = binding ?: return
        store.clear(currentBinding)
        unbind()
    }

    @Synchronized
    fun clearFor(target: PairingGrantBinding) {
        store.clear(target)
        if (binding == target) unbind()
    }

    private fun update(
        transform: (PairingGrantState) -> PairingGrantState,
    ): PairingGrantState {
        val currentBinding = binding ?: error("PAIRING_GRANT_NOT_BOUND")
        val current = _state.value ?: PairingGrantState(currentBinding.pairingId)
        val next = transform(current).withStablePluginCapabilityDenials()
        if (next == current) return current
        val persisted = next.copy(revision = current.revision + 1L)
        store.save(currentBinding, persisted)
        try {
            audit.record(
                pluginId = "platform",
                accountId = currentBinding.accountId,
                pairingId = currentBinding.pairingId,
                action = "pairing.grant.changed",
                outcome = AuditOutcome.ALLOWED,
                correlationId = "grant-${persisted.revision}-${UUID.randomUUID()}",
            )
        } catch (cause: Exception) {
            runCatching { store.save(currentBinding, current) }
            throw cause
        }
        _state.value = persisted
        return persisted
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
