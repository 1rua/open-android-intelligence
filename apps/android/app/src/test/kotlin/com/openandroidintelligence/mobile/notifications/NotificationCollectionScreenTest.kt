package com.openandroidintelligence.mobile.notifications

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知采集设置子页的界面契约：
 * - 未装配（registry deny-first）时整组如实渲染「通知采集未装配」，无任何开关；
 * - 已装配时所有控件走 NotificationPolicyPort 真实读写，绑定入口走
 *   NotificationBindingPort，绝不出现本地假状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationCollectionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private class FakePolicyPort(initial: NotificationPolicySnapshot) : NotificationPolicyPort {
        private val state = MutableStateFlow(initial)
        val updates = mutableListOf<NotificationPolicyUpdate>()
        var nextRejection: String? = null

        override fun snapshot(): NotificationPolicySnapshot = state.value
        override fun observe(): Flow<NotificationPolicySnapshot> = state

        override fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome {
            updates += request
            nextRejection?.let { return NotificationPolicyUpdateOutcome.Rejected(it) }
            if (request.expectedRevision != state.value.revision) {
                return NotificationPolicyUpdateOutcome.Rejected("REVISION_STALE")
            }
            val current = state.value
            val next = current.copy(
                granted = request.granted ?: current.granted,
                packageIds = request.packageIds
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.sorted()
                    ?: current.packageIds,
                fieldAccess = request.fieldAccess ?: current.fieldAccess,
                mode = request.mode ?: current.mode,
                revision = current.revision + 1u,
            )
            state.value = next
            return NotificationPolicyUpdateOutcome.Accepted(next)
        }
    }

    private class FakeBindingPort(bound: Boolean = false) : NotificationBindingPort {
        var isBound: Boolean = bound
        var openCalls: Int = 0

        override fun isListenerBound(): Boolean = isBound
        override fun openSystemListenerSettings(context: Context): Boolean {
            openCalls += 1
            return true
        }
    }

    private fun installedSnapshot(
        granted: Boolean = false,
        packageIds: List<String> = listOf("com.example.chat", "com.example.mail"),
        fieldAccess: NotificationFieldAccess = NotificationFieldAccess.METADATA,
        mode: NotificationDeliveryMode = NotificationDeliveryMode.ON_DEMAND,
        revision: ULong = 5u,
    ): NotificationPolicySnapshot = NotificationPolicySnapshot(
        installed = true,
        granted = granted,
        packageIds = packageIds,
        fieldAccess = fieldAccess,
        mode = mode,
        revision = revision,
    )

    private fun render(policy: NotificationPolicyPort, binding: NotificationBindingPort) {
        compose.setContent {
            NotificationCollectionScreen(policy = policy, binding = binding, onBack = {})
        }
        compose.waitForIdle()
    }

    /** 窗口放不下整页：交互前把目标滚进可视区。 */
    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNodeWithTag("notification-collection-content")
            .performScrollToNode(matcher)
    }

    @Test
    fun uninstalled_policy_renders_honest_unavailable_state_without_any_switch() {
        NotificationControlRegistry.reset()
        render(NotificationControlRegistry.policyPort(), NotificationControlRegistry.bindingPort())

        compose.onNodeWithText("通知采集未装配").assertIsDisplayed()
        compose.onNodeWithText("允许通知采集").assertDoesNotExist()
        compose.onNodeWithText("打开系统通知使用权设置").assertDoesNotExist()
        compose.onNodeWithText("包含通知内容（标题与正文）").assertDoesNotExist()
        compose.onNodeWithText("采集后自动发送到已配对网关").assertDoesNotExist()
    }

    @Test
    fun installed_policy_renders_grant_switch_and_listener_settings_entry() {
        render(FakePolicyPort(installedSnapshot(granted = false)), FakeBindingPort(bound = false))

        compose.onNodeWithText("允许通知采集").assertIsDisplayed()

        scrollTo(hasText("打开系统通知使用权设置"))
        compose.onNodeWithText("打开系统通知使用权设置").assertIsDisplayed()

        scrollTo(hasText("包含通知内容（标题与正文）"))
        compose.onNodeWithText("包含通知内容（标题与正文）").assertIsDisplayed()

        scrollTo(hasText("采集后自动发送到已配对网关"))
        compose.onNodeWithText("采集后自动发送到已配对网关").assertIsDisplayed()
    }

    @Test
    fun tapping_grant_switch_sends_update_with_current_revision() {
        val policy = FakePolicyPort(installedSnapshot(granted = false, revision = 5u))
        render(policy, FakeBindingPort())

        compose.onNodeWithText("允许通知采集").performClick()

        assertEquals(1, policy.updates.size)
        val update = policy.updates.single()
        assertEquals(true, update.granted)
        assertEquals(5uL, update.expectedRevision)
    }

    @Test
    fun listener_settings_button_routes_through_binding_port_only() {
        val binding = FakeBindingPort()
        val policy = FakePolicyPort(installedSnapshot())
        render(policy, binding)

        scrollTo(hasText("打开系统通知使用权设置"))
        compose.onNodeWithText("打开系统通知使用权设置").performClick()

        assertEquals(1, binding.openCalls)
        assertEquals("打开系统设置页不得携带任何策略更新", 0, policy.updates.size)
    }

    @Test
    fun field_access_toggle_sends_content_access_update() {
        val policy = FakePolicyPort(installedSnapshot(fieldAccess = NotificationFieldAccess.METADATA))
        render(policy, FakeBindingPort())

        scrollTo(hasText("包含通知内容（标题与正文）"))
        compose.onNodeWithText("包含通知内容（标题与正文）").performClick()

        val update = policy.updates.single()
        assertEquals(NotificationFieldAccess.CONTENT, update.fieldAccess)
    }

    @Test
    fun delivery_mode_toggle_sends_auto_send_update() {
        val policy = FakePolicyPort(installedSnapshot(mode = NotificationDeliveryMode.ON_DEMAND))
        render(policy, FakeBindingPort())

        scrollTo(hasText("采集后自动发送到已配对网关"))
        compose.onNodeWithText("采集后自动发送到已配对网关").performClick()

        val update = policy.updates.single()
        assertEquals(NotificationDeliveryMode.AUTO_SEND, update.mode)
    }

    @Test
    fun allowlist_editor_adds_sorted_and_removes_packages() {
        val policy = FakePolicyPort(
            installedSnapshot(packageIds = listOf("com.example.mail")),
        )
        render(policy, FakeBindingPort())

        scrollTo(hasText("添加"))
        compose.onNodeWithTag("notification-package-input").performTextInput("com.example.chat")
        compose.onNodeWithText("添加").performClick()
        compose.waitForIdle()
        // 请求携带「当前集合 + 新包」；码点排序是端口实现的职责（host 测试覆盖），
        // 这里断言端口状态与请求次数。
        assertEquals(1, policy.updates.size)
        assertEquals(
            listOf("com.example.chat", "com.example.mail"),
            policy.snapshot().packageIds,
        )

        scrollTo(hasContentDescription("移除 com.example.chat"))
        compose.onNodeWithContentDescription("移除 com.example.chat").performClick()
        compose.waitForIdle()
        assertEquals(2, policy.updates.size)
        assertEquals(listOf("com.example.mail"), policy.snapshot().packageIds)
    }

    @Test
    fun blank_package_input_is_never_submitted() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = emptyList()))
        render(policy, FakeBindingPort())

        scrollTo(hasText("添加"))
        compose.onNodeWithTag("notification-package-input").performTextInput("   ")
        compose.onNodeWithText("添加").performClick()
        compose.waitForIdle()

        assertTrue("空白输入不得产生任何策略更新", policy.updates.isEmpty())
    }

    @Test
    fun rejected_update_surfaces_the_explicit_reason() {
        val policy = FakePolicyPort(installedSnapshot()).apply { nextRejection = "REVISION_STALE" }
        render(policy, FakeBindingPort())

        compose.onNodeWithText("允许通知采集").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("更新被拒绝：REVISION_STALE").assertIsDisplayed()
    }

    @Test
    fun text_input_is_cleared_after_an_accepted_add() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = emptyList()))
        render(policy, FakeBindingPort())

        scrollTo(hasText("添加"))
        compose.onNodeWithTag("notification-package-input").performTextInput("com.example.mail")
        compose.onNodeWithText("添加").performClick()
        compose.waitForIdle()

        assertEquals(1, policy.updates.size)

        // 输入框已被清空：第二次输入不会叠加在旧文本上
        compose.onNodeWithTag("notification-package-input").performTextInput("com.example.chat")
        compose.onNodeWithText("添加").performClick()
        compose.waitForIdle()
        assertEquals(2, policy.updates.size)
        assertEquals(
            listOf("com.example.chat", "com.example.mail"),
            policy.snapshot().packageIds,
        )
    }
}
