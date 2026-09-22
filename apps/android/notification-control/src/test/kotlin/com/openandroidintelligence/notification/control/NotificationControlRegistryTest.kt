package com.openandroidintelligence.notification.control

import com.openandroidintelligence.core.model.NotificationDeliveryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 装配委托与 deny-first 恢复：registry 与 NotificationRuntimeFactoryRegistry 同构。 */
class NotificationControlRegistryTest {

    private class InstalledPolicyPort : NotificationPolicyPort {
        val state = MutableStateFlow(
            NotificationPolicySnapshot(
                installed = true,
                granted = true,
                packageIds = listOf("com.example.mail"),
                fieldAccess = com.openandroidintelligence.core.model.NotificationFieldAccess.CONTENT,
                mode = NotificationDeliveryMode.AUTO_SEND,
                revision = 3u,
            ),
        )

        val updates = mutableListOf<NotificationPolicyUpdate>()

        override fun snapshot(): NotificationPolicySnapshot = state.value
        override fun observe(): Flow<NotificationPolicySnapshot> = state
        override fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome {
            updates += request
            return NotificationPolicyUpdateOutcome.Accepted(state.value)
        }
    }

    private class InstalledBindingPort : NotificationBindingPort {
        var bound = true
        var launched = 0

        override fun isListenerBound(): Boolean = bound
        override fun openSystemListenerSettings(context: android.content.Context): Boolean {
            launched += 1
            return true
        }
    }

    @Test
    fun install_delegates_snapshot_update_and_binding_to_installed_ports() {
        NotificationControlRegistry.reset()
        val policy = InstalledPolicyPort()
        val binding = InstalledBindingPort()
        NotificationControlRegistry.install(policy, binding)

        val snapshot = NotificationControlRegistry.policyPort().snapshot()
        assertTrue(snapshot.installed)
        assertTrue(snapshot.granted)
        assertEquals(listOf("com.example.mail"), snapshot.packageIds)
        assertEquals(3uL, snapshot.revision)

        val outcome = NotificationControlRegistry.policyPort().update(
            NotificationPolicyUpdate(expectedRevision = 3u, granted = false),
        )
        assertTrue(outcome is NotificationPolicyUpdateOutcome.Accepted)
        assertEquals(1, policy.updates.size)
        assertEquals(false, policy.updates.single().granted)

        assertTrue(NotificationControlRegistry.bindingPort().isListenerBound())
        assertEquals(0, binding.launched)
    }

    @Test
    fun reset_restores_the_deny_first_defaults() {
        NotificationControlRegistry.install(InstalledPolicyPort(), InstalledBindingPort())
        NotificationControlRegistry.reset()

        val snapshot = NotificationControlRegistry.policyPort().snapshot()
        assertFalse(snapshot.installed)
        assertFalse(NotificationControlRegistry.bindingPort().isListenerBound())
    }
}
