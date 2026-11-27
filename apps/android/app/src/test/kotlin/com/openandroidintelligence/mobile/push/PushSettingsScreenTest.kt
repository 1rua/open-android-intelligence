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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.SettingsPresentation
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

    @Test
    @Config(qualifiers = "w320dp-h600dp")
    fun sidebarPushPickerKeepsTheSameDraftAndSavesOnlyAfterConfirmation() {
        var saved = PushConfiguration()
        val apps = listOf(com.openandroidintelligence.mobile.notifications.InstalledNotificationApp("com.example.chat", "聊天"))
        val loader = { apps }
        compose.setContent {
            var packages by remember { mutableStateOf(emptySet<String>()) }
            MaterialTheme {
                NotificationPushScopeSettings(packages, false, null,
                    { packages = it; saved = saved.copy(notifications = saved.notifications.copy(scope = saved.notifications.scope.copy(packageIds = it))) },
                    loadInstalledApps = loader, presentation = SettingsPresentation.SIDEBAR)
            }
        }
        compose.onNodeWithTag("push-save-packages").performClick()
        compose.onNodeWithTag("notification-picker-sidebar").assertExists()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-com.example.chat")) }.isSuccess
        }
        compose.onNodeWithTag("notification-app-com.example.chat").performClick()
        assertTrue(saved.notifications.scope.packageIds.isEmpty())
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitForIdle()
        assertEquals(setOf("com.example.chat"), saved.notifications.scope.packageIds)
        assertFalse(saved.sms.enabled)
    }

    @Test fun queueCapacityOptionsAreNoLongerUserSettings() {
        // 加密离线队列的容量与保留时长不再作为设置项；底层按既有默认值继续运行。
        var saved: PushConfiguration? = null
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(PushConfiguration()), emptyList(), false, null, { saved = it }, {}, {}) }
        }
        compose.onNodeWithTag("push-retention").assertDoesNotExist()
        compose.onNodeWithTag("push-max-events").assertDoesNotExist()
        compose.onNodeWithTag("push-max-mib").assertDoesNotExist()
        compose.onNodeWithTag("push-save-retention").assertDoesNotExist()
        assertEquals(null, saved)
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

    @Test fun endpointRefreshAndDraftEditingKeepTheirOriginalBehavior() {
        val original = PushConfiguration()
        var saved = original
        var saves = 0
        var endpointRefreshes = 0
        compose.setContent {
            var config by remember { mutableStateOf(original) }
            MaterialTheme { PushSettingsContent(snapshot(config), emptyList(), false, null,
                { saved = it; config = it; saves++ }, { endpointRefreshes++ }, {}) }
        }
        compose.onNodeWithTag("push-notification-content").performScrollTo().performClick()
        assertFalse(saved.notifications.scope.includeContent)
        compose.onNodeWithTag("push-refresh-endpoints").performScrollTo().performClick()
        assertEquals(1, endpointRefreshes)
        assertEquals(1, saves)
        // 队列设置项退场后，保存动作只来自范围与推送目标。
        assertTrue(saved.sms.scope.mode == SmsScopeMode.ALL)
    }

    @Test fun smsDraftsSurviveStateRestorationWithoutBeingSaved() {
        var config by mutableStateOf(PushConfiguration())
        val saved = mutableListOf<PushConfiguration>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                PushSettingsContent(snapshot(config), emptyList(), false, null,
                    { config = it; saved += it }, {}, {})
            }
        }

        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement("验证码，快递\n 代收 ")
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().performTextReplacement(" +15550000001\n+15550000002 ")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().assertTextContains(" +15550000001\n+15550000002 ")
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().assertTextContains("验证码，快递\n 代收 ")
        assertTrue("恢复草稿不能自动保存设置", saved.isEmpty())

        compose.onNodeWithTag("push-save-senders").performScrollTo().assertIsEnabled().performClick()
        assertEquals(SmsScopeMode.KEYWORDS, saved.single().sms.scope.mode)
        assertEquals(setOf("验证码", "快递", "代收"), saved.single().sms.scope.keywords)
        assertEquals(setOf("+15550000001", "+15550000002"), saved.single().sms.scope.senderAddresses)
    }

    @Test fun changesToIndividualConfigurationFieldsResetOnlyTheirOwnDrafts() {
        var config by mutableStateOf(PushConfiguration())
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(config), emptyList(), false, null, {}, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement("未保存关键词")
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().performTextReplacement("+15550000001")

        compose.runOnIdle {
            config = config.copy(
                sms = config.sms.copy(scope = config.sms.scope.copy(keywords = setOf("外部关键词"))),
                notifications = config.notifications.copy(enabled = true),
            )
        }

        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().assertTextContains("+15550000001")
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().assertTextContains("外部关键词")
        compose.onNodeWithTag("push-notification-enabled").performScrollTo().assertIsOn()

        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement("第二次草稿")
        compose.runOnIdle {
            config = config.copy(
                sms = config.sms.copy(scope = config.sms.scope.copy(
                    mode = SmsScopeMode.PHONE_NUMBERS,
                    allSenders = false,
                    senderAddresses = setOf("+15550000002"),
                )),
                notifications = config.notifications.copy(enabled = false),
            )
        }
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().assertTextContains("+15550000002")
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().assertTextContains("第二次草稿")
        compose.onNodeWithTag("push-notification-enabled").performScrollTo().assertIsOff()
    }

    @Test fun restorationRejectsDraftsWhoseConfigurationChangedWhileAway() {
        var authority = PushConfiguration()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { PushSettingsContent(snapshot(authority), emptyList(), false, null, {}, {}, {}) }
        }
        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().performTextReplacement("旧草稿")
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().performTextReplacement("+15550000001")

        // 普通变量不会触发重组：模拟界面保存后、重建前权威配置发生了变化。
        compose.runOnIdle {
            authority = authority.copy(
                sms = authority.sms.copy(scope = authority.sms.scope.copy(
                    mode = SmsScopeMode.KEYWORDS,
                    allSenders = false,
                    keywords = setOf("新配置"),
                )),
            )
        }
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("push-sms-mode-keywords").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("push-sms-keywords").performScrollTo().assertTextContains("新配置")
        compose.onNodeWithTag("push-sms-mode-phone_numbers").performScrollTo().performClick()
        compose.onNodeWithTag("push-sms-senders").performScrollTo().assertTextContains("+15550000001")
    }

    @Test fun savingDisablesScopeBodyAndEndpointWrites() {
        var saves = 0
        compose.setContent {
            MaterialTheme { PushSettingsContent(snapshot(PushConfiguration()), listOf(PushEndpointOption(endpoint, "Agent", true)), true,
                "推送配置保存失败，尚未应用本次修改。", { saves++ }, {}, {}) }
        }
        compose.onNodeWithTag("push-error").assertTextContains("推送配置保存失败，尚未应用本次修改。")
        for (tag in listOf("push-notification-enabled", "push-save-packages", "push-notification-content",
            "push-sms-enabled", "push-sms-mode-all", "push-sms-mode-keywords", "push-sms-mode-phone_numbers",
            "push-save-senders", "push-sms-content", "push-sms-endpoint:core:account:endpoint:1")) {
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
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-$chat"))
            }.isSuccess
        }
        compose.onNodeWithTag("notification-app-$chat").performClick()
        val concurrent = original.copy(retentionHours = 48)
        compose.runOnIdle { runtime.configure(concurrent) }
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("notification-app-list").performScrollToNode(
                    hasText("设置没有保存成功：推送配置已在其他位置修改，请重新载入已保存选择后再保存。"),
                )
            }.isSuccess
        }
        assertEquals(concurrent, runtime.snapshot().configuration)
        compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-$chat"))
        compose.onNodeWithTag("notification-app-$chat").assertIsOn()
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        compose.onNodeWithTag("notification-app-list").performScrollToNode(hasText("重新载入已保存选择"))
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
