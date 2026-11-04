package com.openandroidintelligence.mobile.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The production revoke/collect/deliver cycle, independent of Android source discovery. */
internal suspend fun runDevicePushCycle(
    local: DevicePushRuntime,
    transport: PushTransport,
    revoke: suspend (PushRevocation) -> Unit,
    captureActiveNotifications: () -> Unit,
    captureSmsPage: () -> Boolean,
    onRevocationFailure: () -> Unit,
): Boolean {
    for (revocation in local.pendingRevocations()) {
        try {
            revoke(revocation)
            local.acknowledgeRevocation(revocation)
        } catch (_: CancellationException) {
            // A replaced Core connection can cancel its request without stopping this cycle.
            // JobScheduler/parent cancellation must still stop collection and delivery.
            currentCoroutineContext().ensureActive()
            onRevocationFailure()
        } catch (_: Exception) { onRevocationFailure() }
    }
    // Local consent already removed the withdrawn targets. Failed cleanup remains
    // durable and keeps the scheduler retrying, while other authorized targets advance.
    if (local.isCollectionAllowed(PushKind.NOTIFICATION)) captureActiveNotifications()
    val moreSmsPages = if (local.isCollectionAllowed(PushKind.SMS)) captureSmsPage() else false
    val snapshot = local.snapshot()
    if (snapshot.pendingDeliveries > 0 || snapshot.configuration.sms.enabled || snapshot.configuration.notifications.enabled)
        local.deliver(transport)
    return pushCycleComplete(local.maintain(), moreSmsPages)
}

internal fun pushCycleComplete(snapshot: PushSnapshot, moreSmsPages: Boolean): Boolean =
    !moreSmsPages && snapshot.pendingDeliveries == 0 && snapshot.pendingRevocations == 0
