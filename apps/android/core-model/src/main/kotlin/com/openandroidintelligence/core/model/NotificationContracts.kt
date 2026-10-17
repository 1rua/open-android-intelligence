package com.openandroidintelligence.core.model

import kotlinx.coroutines.flow.Flow

class PolicyRevisionRace(message: String) : IllegalStateException(message)

/** The two closed package matching modes exposed by the device policy. */
enum class NotificationRuleMode { ALLOWLIST, DENYLIST }

/** Metadata is safe-to-share notification identity; content is title/body. */
enum class NotificationFieldAccess { METADATA, CONTENT }

/**
 * The policy is deliberately a value object.  Package IDs are compared by
 * Unicode code point (not UTF-16 code unit) so every implementation agrees on
 * canonical ordering before a policy revision can be persisted or attested.
 */
data class NotificationCollectionPolicyV1(
    val mode: NotificationRuleMode,
    val packageIds: List<String>,
    val fieldAccess: NotificationFieldAccess,
    val policyRevision: ULong,
) {
    val packages: List<String> get() = packageIds
    val access: NotificationFieldAccess get() = fieldAccess

    init {
        require(packageIds.none { it.isBlank() }) { "package IDs must not be blank" }
        require(packageIds == sortNotificationPackageIds(packageIds)) {
            "package IDs must be sorted by Unicode code point"
        }
    }

    companion object {
        /** Empty allowlist is the safe fresh-install policy. */
        fun default(): NotificationCollectionPolicyV1 = NotificationCollectionPolicyV1(
            mode = NotificationRuleMode.ALLOWLIST,
            packageIds = emptyList(),
            fieldAccess = NotificationFieldAccess.METADATA,
            policyRevision = 0u,
        )
    }
}

/** Task-6 authorization result consumed by the deterministic Android policy. */
data class AuthorizationDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    init {
        require(allowed || !reason.isNullOrBlank()) { "denials need a reason" }
        require(!allowed || reason == null) { "allowed decisions cannot carry a reason" }
    }

    companion object {
        fun allow(): AuthorizationDecision = AuthorizationDecision(true)
        fun deny(reason: String): AuthorizationDecision = AuthorizationDecision(false, reason)
        val Allowed: AuthorizationDecision get() = allow()
    }
}

fun interface NotificationAuthorization {
    fun decide(
        packageName: String,
        fieldAccess: NotificationFieldAccess,
        policyRevision: ULong,
    ): AuthorizationDecision
}

data class NotificationMetadata(
    val packageName: String,
    val appLabel: String?,
    val channelId: String?,
    val postedAtEpochMs: Long,
) {
    init { require(packageName.isNotBlank()) { "package name must not be blank" } }
}

data class NotificationContent(
    val title: String?,
    val body: String?,
)

data class NotificationLoss(
    val lostFromCursor: ULong,
    val lostToCursor: ULong,
    val reason: String,
) {
    init {
        require(lostToCursor >= lostFromCursor) { "loss range must be ordered" }
        require(reason.isNotBlank()) { "loss reason must not be blank" }
    }
}

enum class NotificationEventKind { UPSERT, DELETE_TOMBSTONE, LOSS_MARKER }

const val NOTIFICATION_SOURCE_CAPABILITY: String = "mobile.notifications"

/**
 * Closed Task-9 event union.  Agent/session identity is intentionally absent;
 * Bridge routes these events through its authenticated subscription only.
 */
sealed interface NotificationRecordV1 {
    val eventKind: String
    val sourceCapability: String
    val sourceEpoch: ULong
    val occurrenceId: String
    val recordKey: String
    val recordRevision: ULong
    val cursor: ULong
    val capturedAtEpochMs: Long
    val captureRevision: ULong
    val metadata: NotificationMetadata?
    val content: NotificationContent?
    val loss: NotificationLoss?

    data class Upsert(
        override val sourceEpoch: ULong,
        override val occurrenceId: String,
        override val recordKey: String,
        override val recordRevision: ULong,
        override val cursor: ULong,
        override val capturedAtEpochMs: Long,
        override val captureRevision: ULong,
        override val metadata: NotificationMetadata,
        override val content: NotificationContent?,
        override val sourceCapability: String = NOTIFICATION_SOURCE_CAPABILITY,
    ) : NotificationRecordV1 {
        override val eventKind: String get() = "upsert"
        override val loss: NotificationLoss? get() = null
        init {
            requireIdentityFields(occurrenceId, recordKey, recordRevision, cursor)
            require(sourceCapability == NOTIFICATION_SOURCE_CAPABILITY) { "source capability mismatch" }
        }
    }

    data class DeleteTombstone(
        override val sourceEpoch: ULong,
        override val occurrenceId: String,
        override val recordKey: String,
        override val recordRevision: ULong,
        override val cursor: ULong,
        override val capturedAtEpochMs: Long,
        override val captureRevision: ULong,
        override val metadata: NotificationMetadata,
        override val sourceCapability: String = NOTIFICATION_SOURCE_CAPABILITY,
    ) : NotificationRecordV1 {
        override val eventKind: String get() = "delete_tombstone"
        override val content: NotificationContent? get() = null
        override val loss: NotificationLoss? get() = null
        init {
            requireIdentityFields(occurrenceId, recordKey, recordRevision, cursor)
            require(sourceCapability == NOTIFICATION_SOURCE_CAPABILITY) { "source capability mismatch" }
        }
    }

    data class LossMarker(
        override val sourceEpoch: ULong,
        override val occurrenceId: String,
        override val recordKey: String,
        override val recordRevision: ULong,
        override val cursor: ULong,
        override val capturedAtEpochMs: Long,
        override val captureRevision: ULong,
        override val loss: NotificationLoss,
        override val sourceCapability: String = NOTIFICATION_SOURCE_CAPABILITY,
    ) : NotificationRecordV1 {
        override val eventKind: String get() = "loss_marker"
        override val metadata: NotificationMetadata? get() = null
        override val content: NotificationContent? get() = null
        init {
            requireIdentityFields(occurrenceId, recordKey, recordRevision, cursor)
            require(sourceCapability == NOTIFICATION_SOURCE_CAPABILITY) { "source capability mismatch" }
        }
    }
}

private fun requireIdentityFields(
    occurrenceId: String,
    recordKey: String,
    recordRevision: ULong,
    cursor: ULong,
) {
    require(occurrenceId.isNotBlank()) { "occurrence ID must not be blank" }
    require(recordKey.isNotBlank()) { "record key must not be blank" }
}

enum class NotificationCaptureStatus { COMPLETE, WAITING_DEVICE, FAILED }

data class NotificationCaptureResult(
    val records: List<NotificationRecordV1>,
    val status: NotificationCaptureStatus = NotificationCaptureStatus.COMPLETE,
    val failureReason: String? = null,
    val policyRevision: ULong = 0uL,
) {
    init {
        require(status == NotificationCaptureStatus.FAILED || failureReason == null) {
            "only failed captures have a failure reason"
        }
        if (status == NotificationCaptureStatus.FAILED) require(!failureReason.isNullOrBlank())
    }
}

data class OnDemandNotificationRead(
    val operationId: String,
    val policyRevision: ULong,
    val limit: Int,
    val packageIds: List<String> = emptyList(),
    val receivedAfterEpochMs: Long? = null,
    val textQuery: String = "",
) {
    init {
        require(operationId.isNotBlank()) { "operation ID must not be blank" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
    }
}

interface NotificationCollector {
    suspend fun applyPolicy(policy: NotificationCollectionPolicyV1)
    suspend fun captureOnDemand(request: OnDemandNotificationRead): NotificationCaptureResult
    fun observeAutoSend(): Flow<NotificationCaptureResult>
}

data class DurableEvent(
    val eventId: String,
    val record: NotificationRecordV1,
    val eventWire: ByteArray,
) {
    val wire: ByteArray get() = eventWire.copyOf()
    val canonicalWire: ByteArray get() = eventWire.copyOf()
}

interface NotificationOutbox {
    suspend fun enqueueAccepted(record: NotificationRecordV1): DurableEvent
    suspend fun acknowledge(eventId: String, eventAckWire: ByteArray)
    suspend fun recoverUnacknowledged(): List<DurableEvent>
}
