package com.openandroidintelligence.mobile.notifications

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通知采集设置子页的界面契约：
 * - 未装配（registry deny-first）时整组如实渲染「本机暂不支持读取通知」，无任何开关；
 * - 已装配时所有控件走 NotificationPolicyPort 真实读写，绑定入口走
 *   NotificationBindingPort，绝不出现本地假状态。
 */
@RunWith(RobolectricTestRunner::class)
// 界面契约注入两个端口，避免生产启动过程中的无关生命周期工作。
@Config(sdk = [34], application = Application::class)
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

    private class FakeBindingPort(bound: Boolean = false, val canOpenSettings: Boolean = true) : NotificationBindingPort {
        var isBound: Boolean = bound
        var openCalls: Int = 0

        override fun isListenerBound(): Boolean = isBound
        override fun openSystemListenerSettings(context: Context): Boolean {
            openCalls += 1
            return canOpenSettings
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

    private val localApps = listOf(
        InstalledNotificationApp("com.example.chat", "聊天"),
        InstalledNotificationApp("com.example.mail", "邮件"),
        InstalledNotificationApp("com.example.othermail", "邮件"),
    )

    private fun render(
        policy: NotificationPolicyPort,
        binding: NotificationBindingPort,
        appLoader: () -> List<InstalledNotificationApp> = { localApps },
    ) {
        compose.setContent {
            NotificationCollectionScreen(
                policy = policy,
                binding = binding,
                onBack = {},
                loadInstalledApps = appLoader,
            )
        }
        compose.waitForIdle()
    }

    /** 窗口放不下整页：交互前把目标滚进可视区。 */
    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNodeWithTag("notification-collection-content")
            .performScrollToNode(matcher)
    }

    private fun openPicker() {
        scrollTo(hasTestTag("notification-select-apps"))
        compose.onNodeWithTag("notification-select-apps").performClick()
        compose.waitForIdle()
    }

    private fun waitForApp(packageId: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("notification-app-list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("notification-app-list")
            .performScrollToNode(hasTestTag("notification-app-$packageId"))
    }

    @Test
    fun uninstalled_policy_renders_honest_unavailable_state_without_any_switch() {
        NotificationControlRegistry.reset()
        render(NotificationControlRegistry.policyPort(), NotificationControlRegistry.bindingPort())

        compose.onNodeWithText("本机暂不支持读取通知").assertIsDisplayed()
        compose.onNodeWithText("允许读取通知").assertDoesNotExist()
        compose.onNodeWithText("前往系统设置开启通知使用权").assertDoesNotExist()
        compose.onNodeWithText("读取通知的标题与正文").assertDoesNotExist()
        compose.onNodeWithText("自动把通知发送给 Gateway").assertDoesNotExist()
    }

    @Test
    fun installed_policy_renders_grant_switch_and_listener_settings_entry() {
        render(FakePolicyPort(installedSnapshot(granted = false)), FakeBindingPort(bound = false))

        compose.onNodeWithText("允许读取通知").assertIsDisplayed()

        scrollTo(hasText("前往系统设置开启通知使用权"))
        compose.onNodeWithText("前往系统设置开启通知使用权").assertIsDisplayed()

        scrollTo(hasText("读取通知的标题与正文"))
        compose.onNodeWithText("读取通知的标题与正文").assertIsDisplayed()

        scrollTo(hasText("自动把通知发送给 Gateway"))
        compose.onNodeWithText("自动把通知发送给 Gateway").assertIsDisplayed()
    }

    @Test
    fun tapping_grant_switch_sends_update_with_current_revision() {
        val policy = FakePolicyPort(installedSnapshot(granted = false, revision = 5u))
        render(policy, FakeBindingPort())

        compose.onNodeWithText("允许读取通知").performClick()

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

        scrollTo(hasText("前往系统设置开启通知使用权"))
        compose.onNodeWithText("前往系统设置开启通知使用权").performClick()

        assertEquals(1, binding.openCalls)
        assertEquals("打开系统设置页不得携带任何策略更新", 0, policy.updates.size)
    }

    @Test fun unavailable_system_settings_are_reported_without_granting_access() {
        val policy = FakePolicyPort(installedSnapshot())
        val binding = FakeBindingPort(canOpenSettings = false)
        render(policy, binding)
        scrollTo(hasText("前往系统设置开启通知使用权"))
        compose.onNodeWithText("前往系统设置开启通知使用权").performClick()
        compose.onNodeWithText("无法打开系统通知使用权设置，请到 Android 系统设置里手动开启本应用的通知使用权。").assertExists()
        assertEquals(0, policy.updates.size)
    }

    @Test
    fun field_access_toggle_sends_content_access_update() {
        val policy = FakePolicyPort(installedSnapshot(fieldAccess = NotificationFieldAccess.METADATA))
        render(policy, FakeBindingPort())

        scrollTo(hasText("读取通知的标题与正文"))
        compose.onNodeWithText("读取通知的标题与正文").performClick()

        val update = policy.updates.single()
        assertEquals(NotificationFieldAccess.CONTENT, update.fieldAccess)
    }

    @Test
    fun delivery_mode_toggle_sends_auto_send_update() {
        val policy = FakePolicyPort(installedSnapshot(mode = NotificationDeliveryMode.ON_DEMAND))
        render(policy, FakeBindingPort())

        scrollTo(hasText("自动把通知发送给 Gateway"))
        compose.onNodeWithText("自动把通知发送给 Gateway").performClick()

        val update = policy.updates.single()
        assertEquals(NotificationDeliveryMode.AUTO_SEND, update.mode)
    }

    @Test
    fun selecting_installed_apps_saves_packages_and_allows_removal() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf("com.example.mail")))
        render(policy, FakeBindingPort())
        compose.onNodeWithTag("notification-package-input").assertDoesNotExist()

        openPicker()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        assertTrue("勾选时不应提前保存策略", policy.updates.isEmpty())
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("com.example.chat", "com.example.mail"), policy.snapshot().packageIds)
        assertEquals(5uL, policy.updates.single().expectedRevision)
        assertEquals(null, policy.updates.single().granted)
        assertEquals(null, policy.updates.single().fieldAccess)
        assertEquals(null, policy.updates.single().mode)
        scrollTo(hasContentDescription("移除 com.example.chat"))
        compose.onNodeWithContentDescription("移除 com.example.chat").performClick()
        compose.waitForIdle()
        assertEquals(2, policy.updates.size)
        assertEquals(listOf("com.example.mail"), policy.snapshot().packageIds)
    }

    @Test
    fun cancelling_picker_discards_changes_and_reopening_uses_saved_selection() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf("com.example.mail")))
        render(policy, FakeBindingPort())
        openPicker()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.onNodeWithContentDescription("取消选择").performClick()
        compose.waitForIdle()

        assertTrue(policy.updates.isEmpty())
        openPicker()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOff()
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOn()
    }

    @Test
    fun search_matches_labels_and_package_ids_without_merging_same_name_apps() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = emptyList()))
        render(policy, FakeBindingPort())
        openPicker()
        waitForApp("com.example.mail")
        compose.onNodeWithTag("notification-app-search").performTextInput("邮件")
        compose.onNodeWithTag("notification-app-com.example.chat").assertDoesNotExist()
        compose.onNodeWithTag("notification-app-com.example.mail").performClick()
        compose.onNodeWithTag("notification-app-com.example.othermail").performClick()
        compose.onNodeWithTag("notification-app-search").performTextClearance()
        compose.onNodeWithTag("notification-app-search").performTextInput("COM.EXAMPLE.CHAT")
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsDisplayed()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("com.example.mail", "com.example.othermail"), policy.snapshot().packageIds)
    }

    @Test
    fun missing_saved_packages_are_retained_until_explicitly_deselected() {
        val missing = "com.example.removed"
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf(missing)))
        render(policy, FakeBindingPort())
        openPicker()
        waitForApp(missing)
        compose.onNodeWithTag("notification-app-$missing").assertIsOn()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("com.example.chat", missing), policy.snapshot().packageIds)

        openPicker()
        waitForApp(missing)
        compose.onNodeWithTag("notification-app-$missing").performClick()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()

        assertTrue(policy.snapshot().packageIds.isEmpty())
        scrollTo(hasText("还没有选择任何应用：没有选择就不会读取任何通知。", substring = true))
        compose.onNodeWithText("还没有选择任何应用：没有选择就不会读取任何通知。", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun failed_application_query_is_explicit_and_can_be_retried() {
        val shouldFail = AtomicBoolean(true)
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf("com.example.mail")))
        render(policy, FakeBindingPort()) {
            if (shouldFail.get()) throw IllegalStateException("测试读取失败")
            localApps
        }
        openPicker()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("无法读取本机应用列表，请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        assertTrue(policy.updates.isEmpty())

        shouldFail.set(false)
        compose.onNodeWithText("重试").performClick()
        waitForApp("com.example.mail")
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOn()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("com.example.mail"), policy.snapshot().packageIds)
    }

    @Test
    fun empty_application_query_has_an_honest_empty_state() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = emptyList()))
        render(policy, FakeBindingPort(), appLoader = { emptyList() })
        openPicker()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("未找到可选择的已安装应用。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("未找到可选择的已安装应用。").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消选择").performClick()
        assertTrue(policy.updates.isEmpty())
    }

    @Test
    fun picker_draft_and_search_survive_saved_state_restoration() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf("com.example.mail")))
        val binding = FakeBindingPort()
        val loader = { localApps }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            NotificationCollectionScreen(policy, binding, onBack = {}, loadInstalledApps = loader)
        }
        openPicker()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.onNodeWithTag("notification-app-search").performTextInput("聊天")

        restoration.emulateSavedInstanceStateRestore()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.mail").assertDoesNotExist()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("com.example.chat", "com.example.mail"), policy.snapshot().packageIds)
        assertEquals(5uL, policy.updates.single().expectedRevision)
    }

    @Test
    fun concurrent_policy_change_rejects_old_draft_and_can_reload_saved_selection() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf("com.example.mail")))
        render(policy, FakeBindingPort())
        openPicker()
        waitForApp("com.example.chat")
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.runOnIdle {
            policy.update(NotificationPolicyUpdate(expectedRevision = 5u, packageIds = listOf("com.example.othermail")))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("设置没有保存成功：REVISION_STALE").assertIsDisplayed()
        assertEquals(listOf("com.example.othermail"), policy.snapshot().packageIds)
        assertEquals(5uL, policy.updates.last().expectedRevision)
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOn()
        compose.onNodeWithText("重新载入已保存选择").performClick()
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOff()
        compose.onNodeWithTag("notification-app-com.example.othermail").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()
        assertEquals(6uL, policy.updates.last().expectedRevision)
        assertEquals(listOf("com.example.chat", "com.example.othermail"), policy.snapshot().packageIds)
    }

    @Test
    fun rejected_update_surfaces_the_explicit_reason() {
        val policy = FakePolicyPort(installedSnapshot()).apply { nextRejection = "REVISION_STALE" }
        render(policy, FakeBindingPort())

        compose.onNodeWithText("允许读取通知").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("设置没有保存成功：REVISION_STALE").assertIsDisplayed()
    }

    @Test
    fun select_all_and_invert_selection_work_correctly() {
        val policy = FakePolicyPort(installedSnapshot(packageIds = listOf("com.example.mail")))
        render(policy, FakeBindingPort())
        openPicker()
        waitForApp("com.example.chat")

        // 初始已选 mail，未选 chat 与 othermail
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOff()
        compose.onNodeWithTag("notification-app-com.example.othermail").assertIsOff()

        // 点击全选
        compose.onNodeWithTag("notification-app-select-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.othermail").assertIsOn()

        // 点击反选（全选状态反选后应全部取消勾选）
        compose.onNodeWithTag("notification-app-invert-selection").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOff()
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOff()
        compose.onNodeWithTag("notification-app-com.example.othermail").assertIsOff()

        // 勾选 chat，再点击反选，chat 变 off，mail 和 othermail 变 on
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOn()
        compose.onNodeWithTag("notification-app-invert-selection").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("notification-app-com.example.chat").assertIsOff()
        compose.onNodeWithTag("notification-app-com.example.mail").assertIsOn()
        compose.onNodeWithTag("notification-app-com.example.othermail").assertIsOn()

        // 保存并验证策略
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("com.example.mail", "com.example.othermail"), policy.snapshot().packageIds)
    }
}
