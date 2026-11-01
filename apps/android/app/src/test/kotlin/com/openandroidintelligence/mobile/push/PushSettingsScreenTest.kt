package com.openandroidintelligence.mobile.push

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import org.robolectric.Shadows.shadowOf
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val endpoint = PushEndpoint("core", "account", "endpoint", 1)
    private fun snapshot(config: PushConfiguration) = PushSnapshot(config, 0, false, 0, 0, 0, 0, 0, 0)

    @Test fun userCanEnableNotificationPushOfflineSelectStableEndpointAndNarrowBody() {
        var saved = PushConfiguration()
        compose.setContent {
            var config by remember { mutableStateOf(saved) }
            MaterialTheme { PushSettingsContent(snapshot(config), listOf(PushEndpointOption(endpoint, "Agent", true)), false, null,
                { saved = it; config = it }, {}, {}) }
        }
        compose.onNodeWithTag("push-notification-enabled").assertIsOff().assertIsEnabled().performClick()
        assertTrue(saved.notifications.enabled)
        assertFalse(saved.sms.enabled)
        compose.onNodeWithTag("push-notification-content").performScrollTo().assertIsOn().performClick()
        assertFalse(saved.notifications.scope.includeContent)
        compose.onNodeWithTag("push-notification-endpoint:core:account:endpoint:1").performScrollTo().performClick()
        assertEquals(setOf(endpoint), saved.notifications.endpoints)
        assertTrue(saved.sms.endpoints.isEmpty())
    }

    @Test fun retentionInputSavesOneHour() {
        var saved = PushConfiguration()
        compose.setContent {
            var config by remember { mutableStateOf(saved) }
            MaterialTheme { PushSettingsContent(snapshot(config), emptyList(), false, null, { saved = it; config = it }, {}, {}) }
        }
        compose.onNodeWithTag("push-retention").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("push-save-retention").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saved.retentionHours)
    }
    @Test fun newerGenerationShowsManualReselectionAndPreservesTheOldCheckedRoute() {
        val selected = PushConfiguration(sms = SmsPushSettings(true, SmsPushScope(allSenders = true), setOf(endpoint)))
        var changes = 0
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(selected),
                listOf(PushEndpointOption(endpoint.copy(generation = 2), "same label", true)), false, null,
                { changes++ }, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-endpoint:core:account:endpoint:1").performScrollTo().assertIsOn()
        compose.onNodeWithText("接收端身份已更新，原选择和待发记录已保留。请手动重选；旧记录不会转给新接收端。").assertExists()
        compose.onNodeWithTag("push-sms-endpoint:core:account:endpoint:2").performScrollTo().assertIsOff()
        assertEquals(0, changes)
    }

    @Test fun newSmsScopeIsAllWhilePushStaysOffAndModeSelectionOnlyEditsTheDraft() {
        var commits = 0
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(PushConfiguration()), emptyList(), false, null,
                { commits++ }, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-enabled").performScrollTo().assertIsOff()
        compose.onNodeWithTag("push-sms-mode-all").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement(" ,，\r\n ")
        compose.onNodeWithTag("push-save-senders").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().performTextReplacement(" ,，\r\n ")
        compose.onNodeWithTag("push-save-senders").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("push-sms-mode-all").performScrollTo().performClick()
        compose.onNodeWithTag("push-save-senders").performScrollTo().assertIsEnabled()
        assertEquals(0, commits)
    }

    @Test fun smsKeywordsAndPhoneNumbersAreSavedWithTheModeAndLegacyFlagTogether() {
        val original = PushConfiguration(
            notifications = NotificationPushSettings(true, NotificationPushScope(setOf("com.example.mail"), false), setOf(endpoint)),
            sms = SmsPushSettings(false, SmsPushScope(includeContent = false), setOf(endpoint)),
            retentionHours = 24,
        )
        var saved = original
        var commits = 0
        compose.setContent {
            var config by remember { mutableStateOf(original) }
            MaterialTheme { PushSettingsContent(snapshot(config), emptyList(), false, null,
                { saved = it; config = it; commits++ }, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement(" 验证码，快递\n验证码 ")
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().performTextReplacement(" +15550000001,+15550000002\n+15550000001 ")
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().assertTextContains(" 验证码，快递\n验证码 ")
        assertEquals(0, commits)
        compose.onNodeWithTag("push-save-senders").performScrollTo().performClick()
        assertEquals(original.copy(sms = original.sms.copy(scope = original.sms.scope.copy(
            mode = SmsScopeMode.KEYWORDS, allSenders = false, keywords = setOf("验证码", "快递"),
            senderAddresses = setOf("+15550000001", "+15550000002"),
        ))), saved)
        assertEquals(1, commits)

        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-save-senders").performScrollTo().performClick()
        assertEquals(SmsScopeMode.PHONE_NUMBERS, saved.sms.scope.mode)
        assertFalse(saved.sms.scope.allSenders)
        assertEquals(setOf("+15550000001", "+15550000002"), saved.sms.scope.senderAddresses)
        compose.onNodeWithTag("push-sms-mode-all").performScrollTo().performClick()
        compose.onNodeWithTag("push-save-senders").performScrollTo().performClick()
        assertEquals(SmsScopeMode.ALL, saved.sms.scope.mode)
        assertTrue(saved.sms.scope.allSenders)
        assertFalse(saved.sms.enabled)
        assertFalse(saved.sms.scope.includeContent)
        assertEquals(original.notifications, saved.notifications)
        assertEquals(original.sms.endpoints, saved.sms.endpoints)
        assertEquals(original.retentionHours, saved.retentionHours)
    }

    @Test fun bodyAndEndpointChangesDoNotCommitAnUnfinishedSmsScopeDraft() {
        val original = PushConfiguration(sms = SmsPushSettings(scope = SmsPushScope(senderAddresses = setOf("+15550000001"))))
        var saved = original
        compose.setContent {
            var config by remember { mutableStateOf(original) }
            MaterialTheme { PushSettingsContent(snapshot(config), listOf(PushEndpointOption(endpoint, "Agent", true)), false, null,
                { saved = it; config = it }, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-save-senders").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("push-sms-content").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("push-sms-endpoint:core:account:endpoint:1").performScrollTo().performClick()
        assertEquals(original.sms.scope.copy(includeContent = false), saved.sms.scope)
        assertEquals(setOf(endpoint), saved.sms.endpoints)
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-save-senders").performScrollTo().assertIsNotEnabled()
    }

    @Test fun queueFieldsAndRefreshActionsKeepTheirOriginalBehavior() {
        val original = PushConfiguration()
        var saved = original
        var saves = 0
        var endpointRefreshes = 0
        var stateRefreshes = 0
        compose.setContent {
            var config by remember { mutableStateOf(original) }
            MaterialTheme { PushSettingsContent(snapshot(config), emptyList(), false, null,
                { saved = it; config = it; saves++ }, { endpointRefreshes++ }, { stateRefreshes++ }) }
        }
        for (invalid in listOf("0", "169", "invalid")) {
            compose.onNodeWithTag("push-retention").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithTag("push-save-retention").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("push-retention").performScrollTo().performTextReplacement("168")
        compose.onNodeWithTag("push-max-events").performScrollTo().performTextReplacement("4097")
        compose.onNodeWithTag("push-save-retention").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("push-max-events").performScrollTo().performTextReplacement("64")
        compose.onNodeWithTag("push-max-mib").performScrollTo().performTextReplacement("9")
        compose.onNodeWithTag("push-save-retention").performScrollTo().assertIsNotEnabled()
        assertEquals(0, saves)
        compose.onNodeWithTag("push-max-mib").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("push-save-retention").performScrollTo().performClick()
        assertEquals(original.copy(retentionHours = 168, maxEvents = 64, maxQueueBytes = 2 * 1_048_576), saved)
        compose.onNodeWithText("队列满时拒绝新事件并保留已有未确认事件；接收端消费确认后清除正文。").performScrollTo().assertExists()
        compose.onNodeWithText("刷新队列状态").performScrollTo().performClick()
        compose.onNodeWithTag("push-refresh-endpoints").performScrollTo().performClick()
        assertEquals(1, endpointRefreshes)
        assertEquals(1, stateRefreshes)
        assertEquals(1, saves)
    }

    @Test fun savingDisablesScopeBodyEndpointsAndQueueWrites() {
        var saves = 0
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(PushConfiguration()), listOf(PushEndpointOption(endpoint, "Agent", true)), true,
                "推送配置保存失败，尚未应用本次修改。", { saves++ }, {}, {}) }
        }
        compose.onNodeWithTag("push-error").assertTextContains("推送配置保存失败，尚未应用本次修改。")
        for (tag in listOf("push-notification-enabled", "push-save-packages", "push-notification-content",
            "push-sms-enabled", "push-sms-mode-all", "push-sms-mode-keywords", "push-sms-mode-phone_numbers",
            "push-save-senders", "push-sms-content", "push-sms-endpoint:core:account:endpoint:1", "push-save-retention")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        assertEquals(0, saves)
    }

    @Test fun productionScreenRejectsAConcurrentRuntimeConfigurationAndPreservesThePickerDraft() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = context.packageManager
        val chat = "com.example.chat"
        val mail = "com.example.mail"
        for ((id, label) in listOf(chat to "聊天", mail to "邮件")) {
            shadowOf(packageManager).installPackage(PackageInfo().apply {
                packageName = id
                applicationInfo = ApplicationInfo().apply {
                    packageName = id
                    nonLocalizedLabel = label
                    flags = ApplicationInfo.FLAG_INSTALLED
                    enabled = true
                }
            })
            shadowOf(packageManager).setApplicationIcon(id, packageManager.defaultActivityIcon)
        }
        val runtime = DevicePushRuntime(
            EncryptedPushStateStore("ui-test", InMemoryOutboxPersistence(), SecretKeySpec(ByteArray(32) { 1 }, "AES")),
            PushSystemAccess { true },
        )
        val original = PushConfiguration(notifications = NotificationPushSettings(scope = NotificationPushScope(setOf(mail))))
        runtime.configure(original)
        compose.setContent { MaterialTheme { PushSettingsScreen(runtime, emptyList(), {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("push-save-packages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("push-save-packages").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notification-app-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-$chat"))
        compose.onNodeWithTag("notification-app-$chat").performClick()
        val concurrent = original.copy(retentionHours = 48)
        compose.runOnIdle { runtime.configure(concurrent) }
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("设置没有保存成功：推送配置已在其他位置修改，请重新载入已保存选择后再保存。").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(concurrent, runtime.snapshot().configuration)
        compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-$chat"))
        compose.onNodeWithTag("notification-app-$chat").assertIsOn()
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        compose.onNodeWithText("重新载入已保存选择").performClick()
        compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-$chat"))
        compose.onNodeWithTag("notification-app-$chat").assertIsOff().performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notification-app-picker").fetchSemanticsNodes().isEmpty() }
        assertEquals(concurrent.copy(notifications = concurrent.notifications.copy(
            scope = concurrent.notifications.scope.copy(packageIds = setOf(chat, mail)),
        )), runtime.snapshot().configuration)
    }

}
