package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.NotificationCaptureResult
import com.openandroidintelligence.core.model.NotificationCaptureStatus
import com.openandroidintelligence.core.model.NotificationCollector
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationRecordV1
import com.openandroidintelligence.core.model.OnDemandNotificationRead
import com.openandroidintelligence.core.model.NotificationRuleMode
import com.openandroidintelligence.core.model.compareNotificationPackageIds
import com.openandroidintelligence.policy.NotificationAuthoritySnapshot
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotificationQueryFilter(
    val packageIds: List<String> = emptyList(),
    val fieldAccess: NotificationFieldAccess = NotificationFieldAccess.METADATA,
    val receivedAfterEpochMs: Long? = null,
    val textQuery: String = "",
) {
    init {
        if (packageIds.any { !PACKAGE_NAME.matches(it) }) {
            throw NotificationQueryRejected("PACKAGE_ID_INVALID")
        }
        if (packageIds.size != packageIds.toSet().size) {
            throw NotificationQueryRejected("PACKAGE_IDS_DUPLICATE")
        }
        if (packageIds.zipWithNext().any { (left, right) -> compareNotificationPackageIds(left, right) >= 0 }) {
            throw NotificationQueryRejected("PACKAGE_IDS_UNSORTED")
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

data class NotificationAgentQueryRequest(
    val operationId: String,
    val policyRevision: ULong,
    val limit: Int,
    val filter: NotificationQueryFilter = NotificationQueryFilter(),
) {
    init {
        if (operationId.isBlank()) throw NotificationQueryRejected("OPERATION_ID_INVALID")
        if (limit !in 1..100) throw NotificationQueryRejected("LIMIT_INVALID")
    }
}

class NotificationQueryRejected(val code: String) : IllegalArgumentException(code)

class NotificationAgentQueryGateway(
    private val collector: NotificationCollector,
    private val authority: PersistentNotificationPolicyAuthority,
) {
    private val operationLock = Mutex()
    private val completedOperations = mutableMapOf<String, CachedOperation>()

    suspend fun query(request: NotificationAgentQueryRequest): NotificationCaptureResult = operationLock.withLock {
        // Fixed order: snapshot, operation identity, revision/corruption/grant/content/package
        // pre-gates, cache compatibility, capture, then the same gates after capture.
        val snapshot = authority.snapshot()
        val cached = completedOperations[request.operationId]
        if (cached != null && cached.request != request) {
            throw NotificationQueryRejected("OPERATION_IDENTITY_MISMATCH")
        }

        authorizationFailure(request, snapshot)?.let { reason ->
            return@withLock failed(reason, snapshot.policy.policyRevision).also { result ->
                completedOperations[request.operationId] = CachedOperation(
                    request,
                    result,
                    snapshot.authorizationRevision,
                )
            }
        }

        cached?.let {
            if (cached.authorizationRevision != snapshot.authorizationRevision) {
                return@withLock failed(
                    "AUTHORIZATION_REVISION_STALE",
                    snapshot.policy.policyRevision,
                ).also { result ->
                    completedOperations[request.operationId] = CachedOperation(
                        request,
                        result,
                        snapshot.authorizationRevision,
                    )
                }
            }
            return@withLock cached.result
        }

        val captured = collector.captureOnDemand(
            OnDemandNotificationRead(request.operationId, request.policyRevision, request.limit,
                request.filter.packageIds, request.filter.receivedAfterEpochMs, request.filter.textQuery),
        )
        val current = authority.snapshot()
        val currentFailure = authorizationFailure(request, current)
        val result = when {
            currentFailure != null -> failed(currentFailure, current.policy.policyRevision)
            current.authorizationRevision != snapshot.authorizationRevision -> failed(
                "AUTHORIZATION_REVISION_STALE",
                current.policy.policyRevision,
            )
            else -> filterCaptured(request, captured)
        }

        completedOperations[request.operationId] = CachedOperation(
            request,
            result,
            current.authorizationRevision,
        )
        result
    }

    private fun authorizationFailure(
        request: NotificationAgentQueryRequest,
        snapshot: NotificationAuthoritySnapshot,
    ): String? {
        return when {
            request.policyRevision != snapshot.policy.policyRevision -> "AUTHORIZATION_REVISION_STALE"
            snapshot.corrupted -> "LOCAL_POLICY_CORRUPTED"
            !snapshot.granted -> "LOCAL_GRANT_REQUIRED"
            request.filter.fieldAccess == NotificationFieldAccess.CONTENT &&
                snapshot.policy.fieldAccess != NotificationFieldAccess.CONTENT -> "FIELD_ACCESS_NOT_GRANTED"
            request.filter.packageIds.isNotEmpty() &&
                request.filter.packageIds.any { !matches(snapshot, it) } ->
                "PACKAGE_NOT_ALLOWED"
            else -> null
        }
    }

    private fun matches(snapshot: NotificationAuthoritySnapshot, packageName: String): Boolean = when (
        snapshot.policy.mode
    ) {
        NotificationRuleMode.ALLOWLIST -> packageName in snapshot.policy.packageIds
        NotificationRuleMode.DENYLIST -> packageName !in snapshot.policy.packageIds
    }

    private fun filterCaptured(
        request: NotificationAgentQueryRequest,
        captured: NotificationCaptureResult,
    ): NotificationCaptureResult {
        return captured.copy(records = captured.records.mapNotNull { record ->
            when (record) {
                is NotificationRecordV1.Upsert -> {
                    if (request.filter.packageIds.isNotEmpty() && record.metadata.packageName !in request.filter.packageIds) {
                        null
                    } else if (request.filter.fieldAccess == NotificationFieldAccess.METADATA) {
                        record.copy(content = null)
                    } else {
                        record
                    }
                }
                is NotificationRecordV1.DeleteTombstone -> {
                    if (request.filter.packageIds.isEmpty() || record.metadata.packageName in request.filter.packageIds) record else null
                }
                is NotificationRecordV1.LossMarker -> {
                    if (request.filter.packageIds.isEmpty()) record else null
                }
            }
        }.filter { record ->
            val after = request.filter.receivedAfterEpochMs
            val query = request.filter.textQuery
            (after == null || (record.metadata?.postedAtEpochMs ?: Long.MIN_VALUE) >= after) &&
                (query.isEmpty() || record.content?.title.orEmpty().contains(query, true) ||
                    record.content?.body.orEmpty().contains(query, true))
        }.take(request.limit))
    }

    private fun failed(reason: String, revision: ULong): NotificationCaptureResult = NotificationCaptureResult(
        records = emptyList(),
        status = NotificationCaptureStatus.FAILED,
        failureReason = reason,
        policyRevision = revision,
    )

    private data class CachedOperation(
        val request: NotificationAgentQueryRequest,
        val result: NotificationCaptureResult,
        val authorizationRevision: ULong,
    )
}
