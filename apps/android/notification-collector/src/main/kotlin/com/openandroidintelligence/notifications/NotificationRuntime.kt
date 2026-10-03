package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.AuthorizationDecision
import com.openandroidintelligence.core.model.NotificationCaptureResult
import com.openandroidintelligence.core.model.NotificationAuthorization
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationOutbox
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-local composition seam for the notification listener.  The runtime
 * only forwards policy-accepted auto-send records to an injected encrypted
 * outbox port; it has no Bridge, HTTP, or endpoint knowledge.
 */
class NotificationRuntime(
    initialCollector: AndroidNotificationCollector,
    private val outbox: NotificationOutbox?,
    private val scope: CoroutineScope,
    private val egressGate: NotificationRecordEgressGate = NotificationRecordEgressGate { true },
    private val dispatcher: NotificationBridgeDispatcher? = null,
    private val policyAuthority: PersistentNotificationPolicyAuthority? = null,
    private val failureReporter: NotificationRuntimeFailureReporter = NotificationRuntimeFailureReporter { },
) {
    private val lock = Any()
    private var collector: AndroidNotificationCollector = initialCollector
    private var subscription: Job? = null
    private var recovery: Job? = null
    private var policyRegistration: AutoCloseable? = null

    fun currentCollector(): AndroidNotificationCollector = synchronized(lock) { collector }

    /** Idempotent: a listener lifecycle can call start more than once safely. */
    fun start() = synchronized(lock) {
        if (subscription?.isActive == true) return@synchronized
        policyAuthority?.let { authority ->
            policyRegistration?.close()
            policyRegistration = authority.addListener { state ->
                synchronized(lock) { collector.applyPolicyBlocking(state.policy) }
            }
            reconcileStartupPolicy(authority)
        }
        if (outbox == null) return@synchronized
        subscription = scope.launch {
            collector.observeAutoSend().collect { capture ->
                persistAndDispatch(capture)
            }
        }
        recovery = dispatcher?.let { value -> scope.launch { value.dispatchPending() } }
    }

    fun stop() = synchronized(lock) {
        subscription?.cancel()
        subscription = null
        recovery?.cancel()
        recovery = null
        policyRegistration?.close()
        policyRegistration = null
    }

    /** Shared deterministic boundary used by the flow and revoke-race tests. */
    internal suspend fun persistAndDispatch(capture: NotificationCaptureResult) {
        val sink = outbox ?: return
        capture.records.forEach { record ->
            val permit = policyAuthority?.acquireDeliveryAdmissionPermit()
            try {
                if (egressGate.allows(record) &&
                    (policyAuthority == null || policyAuthority.snapshot().deliveryMode == NotificationDeliveryMode.AUTO_SEND)
                ) {
                    sink.enqueueAccepted(record)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failureReporter.report(NotificationRuntimeFailure.OUTBOX_WRITE_FAILED)
                return@forEach
            } finally {
                permit?.close()
            }
        }
        try {
            dispatcher?.dispatchPending()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            failureReporter.report(NotificationRuntimeFailure.DISPATCH_RETRY_PENDING)
        }
    }

    /** Keep the test/device injection seam while preserving the outbox sink. */
    fun replaceCollector(value: AndroidNotificationCollector) = synchronized(lock) {
        val wasActive = subscription?.isActive == true
        subscription?.cancel()
        collector = value
        policyAuthority?.let { collector.applyPolicyBlocking(it.snapshot().policy) }
        subscription = null
        if (wasActive && outbox != null) {
            subscription = scope.launch {
                collector.observeAutoSend().collect { capture ->
                    persistAndDispatch(capture)
                }
            }
        }
    }

    /**
     * The listener closes the registration gap, while this bounded loop closes
     * the snapshot/apply gap during startup. The caller holds [lock], so a
     * listener callback cannot apply an older policy between these reads.
     */
    private fun reconcileStartupPolicy(authority: PersistentNotificationPolicyAuthority) {
        repeat(MAX_STARTUP_POLICY_RECONCILIATION_ATTEMPTS) {
            val policy = authority.snapshot().policy

            try {
                collector.applyPolicyBlocking(policy)
            } catch (race: PolicyRevisionRace) {
                // A newer authority snapshot can legitimately win a concurrent
                // listener callback; retry only when its revision advanced.
                if (authority.snapshot().policy.policyRevision > policy.policyRevision) return@repeat
                throw race
            }

            val latest = authority.snapshot().policy
            if (latest.policyRevision == policy.policyRevision && latest == policy) return
        }
    }

    private companion object {
        const val MAX_STARTUP_POLICY_RECONCILIATION_ATTEMPTS = 4
    }
}

enum class NotificationRuntimeFailure { OUTBOX_WRITE_FAILED, DISPATCH_RETRY_PENDING }

fun interface NotificationRuntimeFailureReporter {
    fun report(failure: NotificationRuntimeFailure)
}

fun interface NotificationRuntimeFactory {
    fun create(scope: CoroutineScope): NotificationRuntime
}

/**
 * The Android service cannot receive constructor injection. This registry is
 * the explicit local composition point; its default factory is deny-first and
 * has no outbox, so an unconfigured install cannot emit notification data.
 */
object NotificationRuntimeFactoryRegistry {
    @Volatile var activeCollector: AndroidNotificationCollector? = null
        internal set
    private val defaultFactory = NotificationRuntimeFactory { scope ->
        NotificationRuntime(
            initialCollector = AndroidNotificationCollector(
                authorization = NotificationAuthorization { _, _, _ ->
                    AuthorizationDecision.deny("NO_AUTHORIZATION")
                },
            ),
            outbox = null,
            scope = scope,
        )
    }

    @Volatile
    private var factory: NotificationRuntimeFactory = defaultFactory

    fun install(value: NotificationRuntimeFactory) {
        factory = value
    }

    fun create(scope: CoroutineScope): NotificationRuntime = factory.create(scope)

    fun reset() {
        factory = defaultFactory
    }
}
