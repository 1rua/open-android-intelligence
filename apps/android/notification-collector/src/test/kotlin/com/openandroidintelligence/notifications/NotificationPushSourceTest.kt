package com.openandroidintelligence.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationPushSourceTest {
    @Test fun sourceHasNoDependencyOnPassiveCollectorAndExtractsOnlyAfterItsOwnGate() {
        var allow = false
        var reads = 0
        val records = mutableListOf<RawNotification>()
        val observer = object : NotificationPushObserver {
            override fun accepts(packageName: String) = allow && packageName == "allowed.app"
            override fun includesContent() = false
            override fun onPosted(notification: RawNotification) { records += notification }
        }
        NotificationPushSource.install(observer)
        try {
            val raw: (Boolean) -> RawNotification = { content ->
                reads++
                assertFalse(content)
                RawNotification("allowed.app", "key", null, null, null, null, 100)
            }
            NotificationPushSource.posted("allowed.app", raw)
            assertEquals(0, reads)
            allow = true
            NotificationPushSource.posted("other.app", raw)
            assertEquals(0, reads)
            NotificationPushSource.posted("allowed.app", raw)
            assertEquals(1, records.size)
        } finally { NotificationPushSource.clear(observer) }
    }

    @Test fun revokeBetweenGateAndExtractionPreventsDurableAdmission() {
        var allow = true
        var writes = 0
        val observer = object : NotificationPushObserver {
            override fun accepts(packageName: String) = allow
            override fun includesContent() = true
            override fun onPosted(notification: RawNotification) { writes++ }
        }
        NotificationPushSource.install(observer)
        try {
            NotificationPushSource.posted("app") {
                allow = false
                RawNotification("app", "key", null, "private", "text", null, 100)
            }
            assertEquals(0, writes)
        } finally { NotificationPushSource.clear(observer) }
    }
    @Test fun activeSnapshotReconcilesOnlyWhileTheSameOsListenerIsConnected() {
        val owner = Any()
        var reads = 0
        var loggedIn = false
        NotificationCollectionAccess.install { loggedIn }
        NotificationPushSource.connected(owner) { reads++ }
        try {
            NotificationPushSource.captureActive()
            assertEquals(0, reads)
            loggedIn = true
            NotificationPushSource.captureActive()
            assertEquals(1, reads)
            NotificationPushSource.disconnected(Any())
            NotificationPushSource.captureActive()
            assertEquals(2, reads)
            loggedIn = false
            NotificationPushSource.captureActive()
            assertEquals(2, reads)
            NotificationPushSource.disconnected(owner)
            loggedIn = true
            NotificationPushSource.captureActive()
            assertEquals(2, reads)
        } finally {
            NotificationPushSource.disconnected(owner)
            NotificationCollectionAccess.install { false }
        }
    }

}
