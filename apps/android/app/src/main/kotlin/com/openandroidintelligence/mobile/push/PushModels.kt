package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.kernel.SmsScopeMode

/** Names are deliberately absent: a display name is never a routing authority. */
data class PushEndpoint(
    val coreInstanceId: String,
    val accountScope: String,
    val endpointId: String,
    val generation: Long,
) {
    init {
        listOf(coreInstanceId, accountScope, endpointId).forEach {
            require(it.isNotBlank() && it.length <= 256) { "PUSH_ENDPOINT_INVALID" }
        }
        require(generation > 0) { "PUSH_ENDPOINT_GENERATION_INVALID" }
    }
}

data class NotificationPushScope(
    val packageIds: Set<String> = emptySet(),
    val includeContent: Boolean = true,
) {
    init { require(packageIds.size <= 256 && packageIds.all { it.isNotBlank() && it.length <= 256 }) }
    internal fun frozen() = copy(packageIds = packageIds.toSet())
}

data class SmsPushScope(
    val senderAddresses: Set<String> = emptySet(),
    val allSenders: Boolean = senderAddresses.isEmpty(),
    val includeContent: Boolean = true,
    val mode: SmsScopeMode = if (allSenders) SmsScopeMode.ALL else SmsScopeMode.PHONE_NUMBERS,
    val keywords: Set<String> = emptySet(),
) {
    init {
        require(senderAddresses.size <= 256 && senderAddresses.all { it.isNotBlank() && it.length <= 256 })
        require(keywords.size <= 256 && keywords.all { it.isNotBlank() && it.length <= 256 })
    }
    internal fun frozen() = copy(senderAddresses = senderAddresses.toSet(), keywords = keywords.toSet())
}

data class NotificationPushSettings(
    val enabled: Boolean = false,
    val scope: NotificationPushScope = NotificationPushScope(),
    val endpoints: Set<PushEndpoint> = emptySet(),
) {
    init { require(endpoints.size <= MAX_PUSH_ENDPOINTS) }
    internal fun frozen() = copy(scope = scope.frozen(), endpoints = endpoints.toSet())
}

data class SmsPushSettings(
    val enabled: Boolean = false,
    val scope: SmsPushScope = SmsPushScope(),
    val endpoints: Set<PushEndpoint> = emptySet(),
) {
    init { require(endpoints.size <= MAX_PUSH_ENDPOINTS) }
    internal fun frozen() = copy(scope = scope.frozen(), endpoints = endpoints.toSet())
}

/** Installation-owned settings. No pairing, account, or Core selects a storage partition. */
data class PushConfiguration(
    val notifications: NotificationPushSettings = NotificationPushSettings(),
    val sms: SmsPushSettings = SmsPushSettings(),
    val retentionHours: Int = 72,
    val maxEvents: Int = 1_000,
    val maxQueueBytes: Int = 4 * 1_024 * 1_024,
) {
    init {
        require(retentionHours in 1..168) { "PUSH_RETENTION_INVALID" }
        require(maxEvents in 1..4_096) { "PUSH_CAPACITY_INVALID" }
        require(maxQueueBytes in 4_096..8 * 1_024 * 1_024) { "PUSH_BYTE_CAPACITY_INVALID" }
    }
    internal fun frozen() = copy(notifications = notifications.frozen(), sms = sms.frozen())
}

enum class PushKind { NOTIFICATION, SMS }

data class PushNotification(
    val notificationKey: String,
    val packageName: String,
    val postedAtEpochMs: Long,
    val title: String? = null,
    val body: String? = null,
    val appLabel: String? = null,
    val channelId: String? = null,
) {
    init {
        require(notificationKey.isNotBlank() && notificationKey.length <= 2_048)
        require(packageName.isNotBlank() && packageName.length <= 256)
        require(postedAtEpochMs >= 0)
    }
}

data class PushSms(
    val providerId: Long,
    val receivedAtEpochMs: Long,
    val senderAddress: String?,
    val body: String? = null,
    val threadId: String? = null,
    val subscriptionId: Int? = null,
    // Internal native-collector proof; never serialized into the delivered event payload.
    val matchedKeywords: Set<String> = emptySet(),
) {
    init { require(providerId > 0 && receivedAtEpochMs >= 0) }
}

/** Exact immutable bytes (canonical JSON) are replayed for this stable event ID. */
data class PushEvent(
    val eventId: String,
    val kind: PushKind,
    val capturedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val payloadJson: String,
)

data class PushDelivery(val event: PushEvent, val endpoint: PushEndpoint)

/** Exact cleanup batch: stable endpoint plus event IDs, never a route-wide revocation. */
data class PushRevocation(val endpoint: PushEndpoint, val eventIds: Set<String>) {
    init {
        require(eventIds.isNotEmpty() && eventIds.size <= MAX_PUSH_REVOCATION_BATCH) { "PUSH_REVOCATION_BATCH_INVALID" }
        require(eventIds.all { it.isNotBlank() && it.toByteArray().size <= 128 }) { "PUSH_REVOCATION_EVENT_INVALID" }
    }
    internal fun frozen() = copy(eventIds = eventIds.toSet())
}

enum class PushReceiptState { ACCEPTED, CONSUMED, MISSING, RETRY }

data class PushReceipt(
    val endpoint: PushEndpoint,
    val eventId: String,
    val deliveryId: String,
    val state: PushReceiptState,
)

/** Discovery never changes local consent. REVOKED needs positive, irreversible route evidence. */
enum class EndpointVerification { VALID, UNAVAILABLE, REVOKED }

internal fun PushEndpoint.replaces(previous: PushEndpoint): Boolean =
    coreInstanceId == previous.coreInstanceId && accountScope == previous.accountScope &&
        endpointId == previous.endpointId && generation > previous.generation

/**
 * Authenticated Core adapter, implemented by the production composition root.
 * verifyEndpoint must validate the complete stable key against the CURRENT login/connection.
 * send/poll must call stillAuthorized immediately before EACH actual HTTP attempt, including
 * session refresh/replay. They must cooperate with coroutine cancellation.
 * CONSUMED means an authenticated receiver ACK, never HTTP 2xx acceptance.
 */
interface PushTransport {
    suspend fun verifyEndpoint(endpoint: PushEndpoint): EndpointVerification
    suspend fun send(delivery: PushDelivery, stillAuthorized: () -> Boolean): PushReceipt
    suspend fun poll(delivery: PushDelivery, deliveryId: String, stillAuthorized: () -> Boolean): PushReceipt
}

/** Must reflect current Android system permission/special-access state, never remote grants. */
fun interface PushSystemAccess {
    fun isGranted(kind: PushKind): Boolean
}

data class PushSmsCursor(val providerId: Long, val receivedAtEpochMs: Long) : Comparable<PushSmsCursor> {
    init { require(providerId > 0 && receivedAtEpochMs >= 0) }
    override fun compareTo(other: PushSmsCursor) = compareValuesBy(this, other, PushSmsCursor::receivedAtEpochMs, PushSmsCursor::providerId)
}

interface PushClock {
    fun epochMs(): Long
    fun elapsedMs(): Long
}

object SystemPushClock : PushClock {
    override fun epochMs() = System.currentTimeMillis()
    override fun elapsedMs() = System.nanoTime() / 1_000_000
}

enum class PushCaptureStatus { ENQUEUED, DUPLICATE, DISABLED, LOGGED_OUT, OUT_OF_SCOPE, QUEUE_FULL, TOO_LARGE }
data class PushCaptureResult(val status: PushCaptureStatus, val eventId: String? = null)

data class PushSnapshot(
    val configuration: PushConfiguration,
    val revision: Long,
    val hasLoggedInAccount: Boolean,
    val queuedEvents: Int,
    val pendingDeliveries: Int,
    val encryptedPlaintextBytes: Int,
    val expiredEvents: Long,
    val revokedDeliveries: Long,
    val capacityRejections: Long,
    val pendingRevocations: Int = 0,
)

data class PushDeliveryResult(
    val attempted: Int,
    val accepted: Int,
    val acknowledged: Int,
    val pending: Int,
    val failures: Int,
    val paused: Boolean,
)

internal const val MAX_PUSH_ENDPOINTS = 32
internal const val MAX_PUSH_SEEN_EVENTS = 16_384
internal const val MAX_PUSH_EVENT_BYTES = 64 * 1_024
internal const val PUSH_HOUR_MS = 3_600_000L
internal const val MAX_PUSH_REVOCATION_BATCH = 128
internal const val MAX_PUSH_REVOCATION_ROUTES = 4_096
internal const val MAX_PUSH_REVOCATION_IDS = 131_072
internal const val MAX_PUSH_STATE_BYTES = 12 * 1_024 * 1_024

internal data class QueuedPushEvent(
    val event: PushEvent,
    val targets: Map<PushEndpoint, String?>,
    val retentionDeadlineEpochMs: Long = event.expiresAtEpochMs,
    // Only matched configured keywords, never an undisclosed SMS body.
    val smsMatchedKeywords: Set<String> = emptySet(),
)
internal data class PushStoredState(
    val installationId: String,
    val revision: Long = 0,
    val observedAtEpochMs: Long = 0,
    val configuration: PushConfiguration = PushConfiguration(),
    val events: List<QueuedPushEvent> = emptyList(),
    // ACK/revocation tombstones contain only a hash and deadline, no SMS/notification content.
    val seen: Map<String, Long> = emptyMap(),
    val expiredEvents: Long = 0,
    val revokedDeliveries: Long = 0,
    val capacityRejections: Long = 0,
    val smsCursor: PushSmsCursor? = null,
    // Retried independently of login, enabled capabilities, and the sensitive-content queue.
    val revocations: Map<PushEndpoint, Set<String>> = emptyMap(),
)
