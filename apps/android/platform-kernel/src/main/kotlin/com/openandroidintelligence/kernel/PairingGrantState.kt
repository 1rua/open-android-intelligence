package com.openandroidintelligence.kernel

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
) {
    init {
        require(pairingId.isNotBlank()) { "pairing grant ID must not be blank" }
        require(revision >= 0L) { "pairing grant revision must not be negative" }
    }

    fun asKernelGrant(): PairingGrant = PairingGrant(
        pairingId = pairingId,
        granted = granted,
        revision = revision,
    )
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
            states[binding.storageKey] = state
        }
    }

    override fun clear(binding: PairingGrantBinding) {
        synchronized(lock) {
            states.remove(binding.storageKey)
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
            .getOrElse { PairingGrantState(nextBinding.pairingId) }
        _state.value = loaded.takeIf { it.pairingId == nextBinding.pairingId }
            ?: PairingGrantState(nextBinding.pairingId)
    }

    @Synchronized
    fun updatePrimitive(primitive: String, enabled: Boolean): PairingGrantState =
        update { current ->
            val nextGranted = if (enabled) current.granted + primitive else current.granted - primitive
            current.copy(granted = nextGranted)
        }

    @Synchronized
    fun updateScreenSelection(enabled: Boolean): PairingGrantState =
        update { current -> current.copy(screenSelectionEnabled = enabled) }

    @Synchronized
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

    private fun update(
        transform: (PairingGrantState) -> PairingGrantState,
    ): PairingGrantState {
        val currentBinding = binding ?: error("PAIRING_GRANT_NOT_BOUND")
        val current = _state.value ?: PairingGrantState(currentBinding.pairingId)
        val next = transform(current)
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
