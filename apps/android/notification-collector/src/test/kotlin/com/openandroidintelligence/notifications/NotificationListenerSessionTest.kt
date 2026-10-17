package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.*
import com.openandroidintelligence.policy.InMemoryNotificationPolicyPersistence
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import org.junit.Assert.*
import org.junit.Test

class NotificationListenerSessionTest {
    @Test
    fun connection_and_each_query_reconcile_current_os_notifications_after_a_late_grant() {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val collector = AndroidNotificationCollector(authority)
        val initial = raw("existing", 100)
        var active = listOf(initial)
        val session = NotificationListenerSession(collector) { active }

        assertNull(session.refresh())
        session.connect()
        assertTrue(session.refresh()!!.captureOnDemandBlocking(OnDemandNotificationRead("denied", 0u, 10)).records.isEmpty())

        val policy = NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("com.mail"), NotificationFieldAccess.CONTENT, 1u)
        authority.localController().apply(policy, 1u, true)
        collector.applyPolicyBlocking(policy)
        val granted = session.refresh()!!.captureOnDemandBlocking(OnDemandNotificationRead("granted", 1u, 10))
        assertEquals("existing", granted.records.single().recordKey)

        active = listOf(raw("replacement", 200))
        val updated = session.refresh()!!.captureOnDemandBlocking(OnDemandNotificationRead("updated", 1u, 10))
        assertEquals("replacement", updated.records.single().recordKey)

        session.disconnect()
        session.posted(initial)
        assertNull(session.refresh())
        assertTrue(collector.captureOnDemandBlocking(OnDemandNotificationRead("disconnected", 1u, 10)).records.isEmpty())
        session.connect()
        assertEquals("replacement", session.refresh()!!.captureOnDemandBlocking(OnDemandNotificationRead("reconnected", 1u, 10)).records.single().recordKey)
    }

    @Test
    fun permission_loss_or_unavailable_os_snapshot_clears_cached_records() {
        val collector = AndroidNotificationCollector(authorization = { _, _, _ -> AuthorizationDecision.allow() })
        collector.applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("com.mail"), NotificationFieldAccess.CONTENT, 1u))
        var permitted = true
        val session = NotificationListenerSession(collector) {
            if (!permitted) throw SecurityException("revoked")
            listOf(raw("secret", 100))
        }
        session.connect()
        assertNotNull(session.refresh())
        permitted = false
        assertNull(session.refresh())
        assertTrue(collector.captureOnDemandBlocking(OnDemandNotificationRead("revoked", 1u, 10)).records.isEmpty())
    }

    @Test
    fun package_text_and_time_filters_apply_before_limit_and_return_newest_matching_record() {
        val collector = AndroidNotificationCollector(authorization = { _, _, _ -> AuthorizationDecision.allow() })
        collector.applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("com.chat", "com.mail"), NotificationFieldAccess.CONTENT, 1u))
        collector.onPosted(raw("old", 10))
        collector.onPosted(raw("match", 200))
        collector.onPosted(raw("wrong-package", 300).copy(packageName = "com.chat"))
        collector.onPosted(raw("wrong-text", 400).copy(body = "unrelated"))

        val result = collector.captureOnDemandBlocking(OnDemandNotificationRead("filtered", 1u, 1, listOf("com.mail"), 100, "body"))

        assertEquals("match", result.records.single().recordKey)
    }

    private fun raw(key: String, time: Long) = RawNotification("com.mail", key, "Mail", "subject", "body", "channel", time)
}
