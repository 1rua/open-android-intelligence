package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.NotificationAuthorization
import com.openandroidintelligence.core.model.NotificationCaptureResult
import com.openandroidintelligence.core.model.NotificationCaptureStatus
import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.NotificationContent
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationMetadata
import com.openandroidintelligence.core.model.NotificationRecordV1
import com.openandroidintelligence.core.model.NotificationRuleMode
import com.openandroidintelligence.core.model.NotificationLoss
import com.openandroidintelligence.core.model.NotificationCollector
import com.openandroidintelligence.core.model.OnDemandNotificationRead
import com.openandroidintelligence.policy.NotificationPolicyEvaluator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

typealias PolicyRevisionRace = com.openandroidintelligence.core.model.PolicyRevisionRace

/** Data extracted from a platform callback before any policy-bound record exists. */
data class RawNotification(
    val packageName: String,
    val notificationKey: String,
    val appLabel: String?,
    val title: String?,
    val body: String?,
    val channelId: String?,
    val postedAtEpochMs: Long,
) {
    init {
        require(packageName.isNotBlank()) { "package name must not be blank" }
        require(notificationKey.isNotBlank()) { "notification key must not be blank" }
    }
}

private data class AcceptedNotification(
    val raw: RawNotification,
    val recordRevision: ULong,
    val cursor: ULong,
)

/**
 * Policy-first collector.  A callback that fails either local package
 * matching or Task-6 authorization is dropped before it reaches the active
 * map, the auto-send flow, or an outbox consumer.
 */
class AndroidNotificationCollector(
    authorization: NotificationAuthorization = NotificationAuthorization { _, _, _ ->
        com.openandroidintelligence.core.model.AuthorizationDecision.deny("NO_AUTHORIZATION")
    },
    private val sourceEpoch: ULong = 1uL,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : NotificationCollector {
    private val evaluator = NotificationPolicyEvaluator(authorization)
    private val lock = Any()
    private val active = LinkedHashMap<String, AcceptedNotification>()
    private val revisions = HashMap<String, ULong>()
    private var cursor: ULong = 0uL
    private val autoSend = MutableSharedFlow<NotificationCaptureResult>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun applyPolicy(policy: NotificationCollectionPolicyV1) = applyPolicyBlocking(policy)

    fun applyPolicyBlocking(policy: NotificationCollectionPolicyV1) {
        synchronized(lock) {
            evaluator.apply(policy)
            // Revocation immediately removes records; they cannot be surfaced
            // by a later on-demand call after the revision changes.
            active.entries.removeIf { !evaluator.evaluate(it.value.raw.packageName).accepted }
            if (policy.fieldAccess == NotificationFieldAccess.METADATA) {
                active.replaceAll { _, accepted ->
                    accepted.copy(raw = accepted.raw.copy(title = null, body = null))
                }
            }
        }
    }

    override suspend fun captureOnDemand(request: OnDemandNotificationRead): NotificationCaptureResult =
        captureOnDemandBlocking(request)

    fun captureOnDemandBlocking(request: OnDemandNotificationRead): NotificationCaptureResult = synchronized(lock) {
        val policy = evaluator.policy
        if (request.policyRevision != policy.policyRevision) {
            throw PolicyRevisionRace(
                "request revision ${request.policyRevision} does not match ${policy.policyRevision}",
            )
        }
        val records = active.values.asSequence()
            .mapNotNull { accepted ->
                val decision = evaluator.evaluate(accepted.raw.packageName)
                if (!decision.accepted) return@mapNotNull null
                toUpsert(accepted, policy, decision.fieldAccess!!)
            }
            .take(request.limit)
            .toList()
        NotificationCaptureResult(
            records = records,
            status = NotificationCaptureStatus.COMPLETE,
            policyRevision = policy.policyRevision,
        )
    }

    override fun observeAutoSend(): Flow<NotificationCaptureResult> = autoSend

    /** Feed a NotificationListenerService callback through the policy gate. */
    fun onPosted(notification: RawNotification): Boolean = synchronized(lock) {
        val decision = evaluator.evaluate(notification.packageName)
        if (!decision.accepted) {
            // Do not retain even a previously accepted key after revocation.
            active.remove(notification.notificationKey)
            return@synchronized false
        }
        cursor += 1uL
        val nextRevision = (revisions[notification.notificationKey] ?: 0uL) + 1uL
        revisions[notification.notificationKey] = nextRevision
        // Metadata policy strips title/body before anything is retained in the
        // active map; a policy decision is therefore also a memory boundary.
        val normalized = if (decision.fieldAccess == NotificationFieldAccess.METADATA) {
            notification.copy(title = null, body = null)
        } else {
            notification
        }
        val accepted = AcceptedNotification(normalized, nextRevision, cursor)
        active[notification.notificationKey] = accepted
        val record = toUpsert(accepted, evaluator.policy, decision.fieldAccess!!)
        autoSend.tryEmit(NotificationCaptureResult(listOf(record), policyRevision = evaluator.policy.policyRevision))
        true
    }

    /** Emit a tombstone only for a key that was previously policy-accepted. */
    fun onRemoved(notificationKey: String): Boolean = synchronized(lock) {
        val accepted = active[notificationKey] ?: return@synchronized false
        // Authorization can be revoked independently of a callback; re-check
        // before exposing even metadata in the deletion tombstone.
        if (!evaluator.evaluate(accepted.raw.packageName).accepted) {
            active.remove(notificationKey)
            return@synchronized false
        }
        active.remove(notificationKey)
        cursor += 1uL
        val policy = evaluator.policy
        val tombstoneRevision = accepted.recordRevision + 1uL
        revisions[notificationKey] = tombstoneRevision
        val tombstone = NotificationRecordV1.DeleteTombstone(
            sourceEpoch = sourceEpoch,
            occurrenceId = "$notificationKey:${accepted.recordRevision}:delete",
            recordKey = notificationKey,
            recordRevision = tombstoneRevision,
            cursor = cursor,
            capturedAtEpochMs = nowEpochMs(),
            captureRevision = policy.policyRevision,
            metadata = metadata(accepted.raw),
        )
        autoSend.tryEmit(NotificationCaptureResult(listOf(tombstone), policyRevision = policy.policyRevision))
        true
    }

    /** Loss is explicit and never disguised as an empty successful result. */
    fun onQueueLoss(fromCursor: ULong, toCursor: ULong, reason: String): NotificationRecordV1.LossMarker = synchronized(lock) {
        cursor = maxOf(cursor, toCursor)
        val policy = evaluator.policy
        NotificationRecordV1.LossMarker(
            sourceEpoch = sourceEpoch,
            occurrenceId = "loss:$fromCursor:$toCursor",
            recordKey = "loss:$fromCursor:$toCursor",
            recordRevision = toCursor,
            cursor = toCursor,
            capturedAtEpochMs = nowEpochMs(),
            captureRevision = policy.policyRevision,
            loss = NotificationLoss(fromCursor, toCursor, reason),
        ).also {
            // An empty allowlist denies even loss telemetry; otherwise a loss
            // marker would bypass the same default-deny send boundary.
            val allowLoss = active.values.any { evaluator.evaluate(it.raw.packageName).accepted }
            if (allowLoss) {
                autoSend.tryEmit(NotificationCaptureResult(listOf(it), policyRevision = policy.policyRevision))
            }
        }
    }

    private fun toUpsert(
        accepted: AcceptedNotification,
        policy: NotificationCollectionPolicyV1,
        fieldAccess: NotificationFieldAccess,
    ): NotificationRecordV1.Upsert = NotificationRecordV1.Upsert(
        sourceEpoch = sourceEpoch,
        occurrenceId = "${accepted.raw.notificationKey}:${accepted.recordRevision}",
        recordKey = accepted.raw.notificationKey,
        recordRevision = accepted.recordRevision,
        cursor = accepted.cursor,
        capturedAtEpochMs = nowEpochMs(),
        captureRevision = policy.policyRevision,
        metadata = metadata(accepted.raw),
        content = if (fieldAccess == NotificationFieldAccess.CONTENT) {
            NotificationContent(accepted.raw.title, accepted.raw.body)
        } else {
            null
        },
    )

    private fun metadata(raw: RawNotification): NotificationMetadata = NotificationMetadata(
        packageName = raw.packageName,
        appLabel = raw.appLabel,
        channelId = raw.channelId,
        postedAtEpochMs = raw.postedAtEpochMs,
    )
}

/** Android adapter kept thin so the policy/normalizer remains JVM-testable. */
class OpenAndroidIntelligenceNotificationListenerService : android.service.notification.NotificationListenerService() {
    private var collector: AndroidNotificationCollector? = null
    private var runtime: NotificationRuntime? = null
    private var runtimeScope: CoroutineScope? = null
    private var preinstalledCollector: AndroidNotificationCollector? = null

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runtimeScope = scope
        val created = NotificationRuntimeFactoryRegistry.create(scope)
        runtime = created
        preinstalledCollector?.let(created::replaceCollector)
        collector = created.currentCollector()
        NotificationRuntimeFactoryRegistry.activeCollector = collector
        runtime?.start()
    }

    fun installCollector(value: AndroidNotificationCollector) {
        preinstalledCollector = value
        runtime?.replaceCollector(value)
        collector = value
        NotificationRuntimeFactoryRegistry.activeCollector = value
    }

    override fun onDestroy() {
        if (NotificationRuntimeFactoryRegistry.activeCollector === collector) NotificationRuntimeFactoryRegistry.activeCollector = null
        runtime?.stop()
        runtime = null
        collector = null
        preinstalledCollector = null
        runtimeScope?.cancel()
        runtimeScope = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        val extras = sbn.notification.extras
        collector?.onPosted(
            RawNotification(
                packageName = sbn.packageName,
                notificationKey = sbn.key,
                appLabel = null,
                title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
                body = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
                channelId = sbn.notification.channelId,
                postedAtEpochMs = sbn.postTime,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        collector?.onRemoved(sbn.key)
    }
}
