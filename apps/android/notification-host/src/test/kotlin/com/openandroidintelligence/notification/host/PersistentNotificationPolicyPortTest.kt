package com.openandroidintelligence.notification.host

import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationRuleMode
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import com.openandroidintelligence.policy.InMemoryNotificationPolicyPersistence
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import java.io.File
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 策略端口必须与 PersistentNotificationPolicyAuthority 同源：
 * snapshot().granted 就是 NotificationAgentQueryGateway 读的那个授权位，
 * 设置页经端口写入的每一笔变更都落在同一个（持久化的）权威上——
 * 消除「设置页 PairingGrantStateHolder 与查询门 LOCAL_GRANT_REQUIRED 两套账本」。
 */
class PersistentNotificationPolicyPortTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun authorityOn(file: File? = null): PersistentNotificationPolicyAuthority {
        val persistence = file?.let { com.openandroidintelligence.policy.FileNotificationPolicyPersistence(it) }
            ?: InMemoryNotificationPolicyPersistence()
        return PersistentNotificationPolicyAuthority(persistence)
    }

    @Test
    fun snapshot_matches_authority_snapshot_field_by_field() {
        val authority = authorityOn()
        val port = PersistentNotificationPolicyPort(authority)

        // 授权前的快照与权威一致且 deny-first
        assertFalse(port.snapshot().granted)

        val policy = NotificationCollectionPolicyV1(
            mode = NotificationRuleMode.ALLOWLIST,
            packageIds = listOf("com.example.chat", "com.example.mail"),
            fieldAccess = NotificationFieldAccess.CONTENT,
            policyRevision = 4u,
        )
        authority.localController().apply(
            policy,
            authorizationRevision = 7u,
            granted = true,
            deliveryMode = NotificationDeliveryMode.AUTO_SEND,
        )

        val authoritySnapshot = authority.snapshot()
        val portSnapshot = port.snapshot()
        assertEquals(authoritySnapshot.granted, portSnapshot.granted)
        assertEquals(authoritySnapshot.policy.packageIds, portSnapshot.packageIds)
        assertEquals(authoritySnapshot.policy.fieldAccess, portSnapshot.fieldAccess)
        assertEquals(authoritySnapshot.deliveryMode, portSnapshot.mode)
        assertEquals(authoritySnapshot.authorizationRevision, portSnapshot.revision)
        assertTrue(portSnapshot.installed)
    }

    @Test
    fun update_grant_persists_and_bumps_revision() {
        val file = File(tempFolder.root, "notification-policy.bin")
        val port = PersistentNotificationPolicyPort(authorityOn(file))
        val before = port.snapshot()

        val outcome = port.update(NotificationPolicyUpdate(expectedRevision = before.revision, granted = true))

        assertTrue(outcome is NotificationPolicyUpdateOutcome.Accepted)
        val accepted = (outcome as NotificationPolicyUpdateOutcome.Accepted).snapshot
        assertTrue(accepted.granted)
        assertTrue("接受后 revision 必须单调前进", accepted.revision > before.revision)

        // 持久化证据：同一文件重新恢复出的权威就是新状态
        val restored = authorityOn(file).snapshot()
        assertTrue(restored.granted)
        assertEquals(accepted.revision, restored.authorizationRevision)
    }

    @Test
    fun update_rewrites_package_ids_field_access_and_mode() {
        val port = PersistentNotificationPolicyPort(authorityOn())
        val revision = port.snapshot().revision

        val outcome = port.update(
            NotificationPolicyUpdate(
                expectedRevision = revision,
                granted = true,
                packageIds = listOf("com.example.mail", "com.example.chat"),
                fieldAccess = NotificationFieldAccess.CONTENT,
                mode = NotificationDeliveryMode.AUTO_SEND,
            ),
        )

        assertTrue(outcome is NotificationPolicyUpdateOutcome.Accepted)
        val snapshot = (outcome as NotificationPolicyUpdateOutcome.Accepted).snapshot
        // 包名按 Unicode 码点重排
        assertEquals(listOf("com.example.chat", "com.example.mail"), snapshot.packageIds)
        assertEquals(NotificationFieldAccess.CONTENT, snapshot.fieldAccess)
        assertEquals(NotificationDeliveryMode.AUTO_SEND, snapshot.mode)
    }

    @Test
    fun update_with_stale_revision_is_rejected() {
        val port = PersistentNotificationPolicyPort(authorityOn())
        val revision = port.snapshot().revision

        val outcome = port.update(
            NotificationPolicyUpdate(expectedRevision = revision + 1uL, granted = true),
        )

        assertTrue(outcome is NotificationPolicyUpdateOutcome.Rejected)
        assertEquals("REVISION_STALE", (outcome as NotificationPolicyUpdateOutcome.Rejected).reason)
    }

    @Test
    fun update_rejects_package_ids_that_cannot_form_a_valid_policy() {
        val port = PersistentNotificationPolicyPort(authorityOn())

        val duplicated = port.update(
            NotificationPolicyUpdate(
                expectedRevision = port.snapshot().revision,
                packageIds = listOf("com.example.app", "com.example.app"),
            ),
        )
        assertTrue(duplicated is NotificationPolicyUpdateOutcome.Rejected)
        assertEquals("INVALID_PACKAGE_IDS", (duplicated as NotificationPolicyUpdateOutcome.Rejected).reason)

        val blank = port.update(
            NotificationPolicyUpdate(
                expectedRevision = port.snapshot().revision,
                packageIds = listOf(" "),
            ),
        )
        assertTrue(blank is NotificationPolicyUpdateOutcome.Rejected)
    }

    @Test
    fun observe_emits_the_new_snapshot_after_every_accepted_update() = runBlocking {
        val authority = authorityOn()
        val port = PersistentNotificationPolicyPort(authority)
        val received = mutableListOf<NotificationPolicySnapshot>()
        val collected = launch { port.observe().take(2).collect { received += it } }

        withTimeout(10_000) { while (received.size < 1) delay(10) }
        port.update(
            NotificationPolicyUpdate(expectedRevision = received.first().revision, granted = true),
        )
        collected.join()

        assertEquals(2, received.size)
        assertTrue(received.last().granted)
    }
}
