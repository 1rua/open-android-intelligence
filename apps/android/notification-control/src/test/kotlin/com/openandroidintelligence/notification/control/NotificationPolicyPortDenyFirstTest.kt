package com.openandroidintelligence.notification.control

import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未装配时 registry 的 deny-first 契约：每个字段都必须诚实——
 * 绝不假装已授权、绝不静默假成功。这是设置页「通知采集未装配」
 * 如实降级呈现的唯一数据来源。
 */
class NotificationPolicyPortDenyFirstTest {

    @Test
    fun uninstalled_snapshot_is_deny_first_honest() {
        NotificationControlRegistry.reset()
        val snapshot = NotificationControlRegistry.policyPort().snapshot()

        assertFalse("未装配的快照不得假装已装配", snapshot.installed)
        assertFalse("未装配的快照不得假装已授权", snapshot.granted)
        assertEquals("未装配的快照不得伪造白名单", emptyList<String>(), snapshot.packageIds)
        assertEquals(NotificationFieldAccess.METADATA, snapshot.fieldAccess)
        assertEquals(NotificationDeliveryMode.ON_DEMAND, snapshot.mode)
        assertEquals(0uL, snapshot.revision)
    }

    @Test
    fun uninstalled_observe_emits_the_same_honest_snapshot() = runTest {
        NotificationControlRegistry.reset()
        val snapshot = NotificationControlRegistry.policyPort().observe().first()

        assertFalse(snapshot.installed)
        assertFalse(snapshot.granted)
    }

    @Test
    fun uninstalled_update_is_rejected_with_explicit_reason() {
        NotificationControlRegistry.reset()
        val outcome = NotificationControlRegistry.policyPort().update(
            NotificationPolicyUpdate(expectedRevision = 0u, granted = true),
        )

        assertTrue(outcome is NotificationPolicyUpdateOutcome.Rejected)
        assertEquals("NOT_INSTALLED", (outcome as NotificationPolicyUpdateOutcome.Rejected).reason)
    }

    @Test
    fun uninstalled_update_rejects_even_a_no_op_request() {
        NotificationControlRegistry.reset()
        val outcome = NotificationControlRegistry.policyPort().update(
            NotificationPolicyUpdate(expectedRevision = 0u, packageIds = listOf("com.example.app")),
        )

        assertTrue(outcome is NotificationPolicyUpdateOutcome.Rejected)
    }
}
