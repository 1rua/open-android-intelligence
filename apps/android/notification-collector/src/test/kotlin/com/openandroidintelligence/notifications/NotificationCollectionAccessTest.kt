package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.AuthorizationDecision
import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationRuleMode
import com.openandroidintelligence.core.model.OnDemandNotificationRead
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationCollectionAccessTest {
    @Before fun noLoginByDefault() { NotificationCollectionAccess.install { false } }
    @After fun resetGate() { NotificationCollectionAccess.install { false } }

    @Test fun uninstalledOrLoggedOutGateDoesNotCallTheRawOrSnapshotFactory() {
        var reads = 0
        val session = NotificationListenerSession(allowedCollector()) { reads++; listOf(raw()) }
        session.connect()
        assertNull(session.refresh())
        assertFalse(session.posted { reads++; raw() })
        assertEquals(0, reads)
        assertTrue(session.isConnected()) // OS listener availability and application login are distinct.
    }

    @Test fun logoutClearsPassiveCacheWithoutReadingNewNotificationsAndLoginRefreshesRealSnapshot() {
        var loggedIn = true
        var reads = 0
        NotificationCollectionAccess.install { loggedIn }
        val collector = allowedCollector()
        val session = NotificationListenerSession(collector) { reads++; listOf(raw("restored")) }
        session.connect()
        assertTrue(session.posted { reads++; raw("before-logout") })
        assertEquals(1, reads)
        loggedIn = false
        assertFalse(session.posted { reads++; raw("must-not-read") })
        assertNull(session.refresh())
        assertEquals(1, reads)
        assertTrue(collector.captureOnDemandBlocking(OnDemandNotificationRead("logged-out", 1u, 10)).records.isEmpty())
        loggedIn = true
        assertNotNull(session.refresh())
        assertEquals(2, reads)
        assertEquals("restored", collector.captureOnDemandBlocking(OnDemandNotificationRead("after-login", 1u, 10)).records.single().recordKey)
    }

    @Test fun logoutDuringRawExtractionPreventsDeliveryToPassiveCollector() {
        var loggedIn = true
        NotificationCollectionAccess.install { loggedIn }
        val collector = allowedCollector()
        val session = NotificationListenerSession(collector) { emptyList() }
        session.connect()
        assertFalse(session.posted { loggedIn = false; raw() })
        assertTrue(collector.captureOnDemandBlocking(OnDemandNotificationRead("raced-post", 1u, 10)).records.isEmpty())
    }

    @Test fun logoutDuringOsSnapshotPreventsReconciliationAndDelivery() {
        var loggedIn = true
        NotificationCollectionAccess.install { loggedIn }
        val collector = allowedCollector()
        val session = NotificationListenerSession(collector) { loggedIn = false; listOf(raw()) }
        session.connect()
        assertNull(session.refresh())
        assertTrue(collector.captureOnDemandBlocking(OnDemandNotificationRead("raced-snapshot", 1u, 10)).records.isEmpty())
    }

    @Test fun aFailingOrReplacedGateCannotDeliverCapturedContent() {
        var reads = 0
        NotificationCollectionAccess.install { throw IllegalStateException("account state unavailable") }
        assertNull(NotificationCollectionAccess.capture { reads++; raw() })
        assertEquals(0, reads)
        NotificationCollectionAccess.install { true }
        assertNull(NotificationCollectionAccess.capture {
            reads++
            NotificationCollectionAccess.install { true }
            raw()
        })
        assertEquals(1, reads)
    }

    @Test fun anEnabledLoginGateDoesNotImplyPassiveAuthorizationOrDisableIndependentPush() {
        NotificationCollectionAccess.install { true }
        val passive = AndroidNotificationCollector()
        val session = NotificationListenerSession(passive) { emptyList() }
        session.connect()
        assertFalse(session.posted { raw() })
        var pushed = 0
        val observer = object : NotificationPushObserver {
            override fun accepts(packageName: String) = packageName == "test.app"
            override fun includesContent() = true
            override fun onPosted(notification: RawNotification) { pushed++ }
        }
        NotificationPushSource.install(observer)
        try {
            NotificationPushSource.posted("test.app") { raw() }
            assertEquals(1, pushed)
        } finally { NotificationPushSource.clear(observer) }
    }

    private fun allowedCollector() = AndroidNotificationCollector(
        authorization = { _, _, _ -> AuthorizationDecision.allow() },
    ).apply { applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST,
        listOf("test.app"), NotificationFieldAccess.CONTENT, 1u)) }
    private fun raw(key: String = "key") = RawNotification("test.app", key, "App", "title", "body", "channel", 100)
}
