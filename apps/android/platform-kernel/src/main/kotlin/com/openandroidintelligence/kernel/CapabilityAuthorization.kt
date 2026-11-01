package com.openandroidintelligence.kernel

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Installed capabilities start in manual mode; Android system permission remains independent. */
enum class CapabilityAuthorizationMode { MANUAL, READ_ONLY, AUTOMATIC }

data class CapabilityPolicy(
    val enabled: Boolean = true,
    val mode: CapabilityAuthorizationMode = CapabilityAuthorizationMode.MANUAL,
    val allowedSources: Set<String> = emptySet(),
    val includeContent: Boolean = true,
    val smsScopeMode: SmsScopeMode = if (allowedSources.isEmpty()) SmsScopeMode.ALL else SmsScopeMode.PHONE_NUMBERS,
    val smsKeywords: Set<String> = emptySet(),
) {
    init {
        require(allowedSources.size <= 256 && allowedSources.all { it.isNotBlank() && it.length <= 256 })
        require(smsKeywords.size <= 256 && smsKeywords.all { it.isNotBlank() && it.length <= 256 })
    }
    fun freeze() = copy(allowedSources = allowedSources.toSet(), smsKeywords = smsKeywords.toSet())
}

enum class DeviceOperation { READ, WRITE, CONTROL }

/** Exact canonical parameter digest is deliberately narrower than an inferred data scope. */
data class CapabilityRequestScope(
    val capability: String,
    val operation: DeviceOperation,
    val parametersSha256: String,
) {
    init {
        require(capability.isNotBlank())
        require(parametersSha256.matches(Regex("(?:sha256:)?[a-f0-9]{64}")))
    }
}

data class LocalCapabilityApproval(
    val id: String,
    val scope: CapabilityRequestScope,
    val originRequestId: String,
    val onlyRequestId: String?,
    val expiresAtElapsedMillis: Long,
    val policyRevision: Long,
)

sealed interface CapabilityAuthorization {
    data class Allowed(val approval: LocalCapabilityApproval? = null) : CapabilityAuthorization
    data object AwaitingConfirmation : CapabilityAuthorization
    data class Denied(val code: String) : CapabilityAuthorization
}

/**
 * Temporary approvals are installation-wide, use monotonic time, and never survive process death.
 * Gateway/account/Agent display names cannot enter the scope or widen an approval.
 */
class CapabilityAuthorizationController(
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val approvals = linkedMapOf<String, LocalCapabilityApproval>()
    private val cancelled = linkedSetOf<String>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()
    private fun changed() { _changes.value = _changes.value + 1L }

    @Synchronized
    fun evaluate(
        requestId: String,
        scope: CapabilityRequestScope,
        policy: CapabilityPolicy,
        policyRevision: Long,
    ): CapabilityAuthorization {
        if (requestId in cancelled) return CapabilityAuthorization.Denied("DEVICE_REQUEST_CANCELLED")
        if (!policy.enabled) return CapabilityAuthorization.Denied("LOCAL_CAPABILITY_DISABLED")
        prune(policyRevision)
        if (policy.mode == CapabilityAuthorizationMode.AUTOMATIC ||
            policy.mode == CapabilityAuthorizationMode.READ_ONLY && scope.operation == DeviceOperation.READ
        ) return CapabilityAuthorization.Allowed()
        val saved = approvals.values.firstOrNull {
            it.scope == scope && (it.onlyRequestId == null || it.onlyRequestId == requestId)
        }
        return if (saved == null) CapabilityAuthorization.AwaitingConfirmation
            else CapabilityAuthorization.Allowed(saved)
    }

    @Synchronized
    fun approve(
        requestId: String,
        scope: CapabilityRequestScope,
        durationMinutes: Int,
        policyRevision: Long,
        remainingRequestMillis: Long,
    ): LocalCapabilityApproval {
        require(durationMinutes in setOf(0, 5, 30, 60)) { "LOCAL_APPROVAL_DURATION_INVALID" }
        check(requestId !in cancelled && remainingRequestMillis > 0) { "DEVICE_REQUEST_CANCELLED_OR_EXPIRED" }
        prune(policyRevision)
        val ttl = if (durationMinutes == 0) remainingRequestMillis.coerceAtMost(5 * 60_000L)
            else durationMinutes * 60_000L
        val approval = LocalCapabilityApproval(
            UUID.randomUUID().toString(), scope, requestId,
            if (durationMinutes == 0) requestId else null,
            Math.addExact(elapsedMillis(), ttl), policyRevision,
        )
        approvals[approval.id] = approval
        // Bound ephemeral state even if an authenticated caller floods unique scopes.
        while (approvals.size > 512) approvals.remove(approvals.keys.first())
        changed()
        return approval
    }

    @Synchronized
    fun isCurrent(approvalId: String, requestId: String, scope: CapabilityRequestScope, policyRevision: Long): Boolean {
        prune(policyRevision)
        if (requestId in cancelled) return false
        val saved = approvals[approvalId] ?: return false
        return saved.scope == scope && (saved.onlyRequestId == null || saved.onlyRequestId == requestId)
    }

    @Synchronized
    fun cancel(requestId: String) {
        cancelled += requestId
        approvals.entries.removeAll { it.value.originRequestId == requestId || it.value.onlyRequestId == requestId }
        while (cancelled.size > 2048) cancelled.remove(cancelled.first())
        changed()
    }

    @Synchronized
    fun finish(requestId: String) {
        if (approvals.entries.removeAll { it.value.onlyRequestId == requestId }) changed()
    }

    @Synchronized
    fun revokeAll() { if (approvals.isNotEmpty()) { approvals.clear(); changed() } }

    @Synchronized
    fun activeApprovals(policyRevision: Long): List<LocalCapabilityApproval> {
        prune(policyRevision)
        return approvals.values.toList()
    }

    private fun prune(revision: Long) {
        val now = elapsedMillis()
        if (approvals.entries.removeAll { it.value.expiresAtElapsedMillis <= now || it.value.policyRevision != revision }) changed()
    }
}
