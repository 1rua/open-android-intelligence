package com.openandroidintelligence.mobile.push

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.mobile.notifications.InstalledNotificationApp
import com.openandroidintelligence.mobile.notifications.NotificationCollectionScreen
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPushScopeSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val chat = "com.example.chat"
    private val mail = "com.example.mail"
    private val otherMail = "com.example.othermail"
    private val apps = listOf(
        InstalledNotificationApp(chat, "聊天"),
        InstalledNotificationApp(mail, "邮件"),
        InstalledNotificationApp(otherMail, "邮件"),
    )
    private val appLoader = { apps }

    private fun openPicker() {
        compose.onNodeWithTag("push-save-packages").performClick()
        waitForApps()
    }

    private fun waitForApps() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("notification-app-list").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun app(packageId: String) = compose.onNodeWithTag("notification-app-$packageId").also {
        compose.onNodeWithTag("notification-app-list").performScrollToNode(hasTestTag("notification-app-$packageId"))
    }

    @Test fun notificationScopeUsesTheOriginalSearchAndSelectionDraftWithoutSavingOnCancel() {
        var saved = setOf(chat)
        var saves = 0
        compose.setContent {
            var packages by remember { mutableStateOf(saved) }
            MaterialTheme {
                NotificationPushScopeSettings(packages, false, null, { saved = it; packages = it; saves++ }, appLoader)
            }
        }
        openPicker()
        app(chat).assertIsOn()
        compose.onNodeWithTag("notification-app-search").performTextReplacement("邮件")
        app(mail).assertIsOff()
        app(otherMail).assertIsOff()
        compose.onNodeWithTag("notification-app-select-all").performClick()
        app(mail).assertIsOn()
        app(otherMail).assertIsOn()
        compose.onNodeWithTag("notification-app-invert-selection").performClick()
        app(mail).assertIsOff()
        app(otherMail).assertIsOff()
        compose.onNodeWithTag("notification-app-search").performTextReplacement(chat)
        app(chat).assertIsOn().performClick().assertIsOff()
        assertEquals(setOf(chat), saved)
        compose.onNodeWithContentDescription("取消选择").performClick()
        assertEquals(0, saves)
        openPicker()
        app(chat).assertIsOn()
        app(mail).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.onNodeWithTag("notification-app-picker").assertDoesNotExist()
        assertEquals(setOf(chat, mail), saved)
        assertEquals(1, saves)
        openPicker()
        compose.onNodeWithTag("notification-app-select-all").performClick()
        compose.onNodeWithTag("notification-app-select-all").performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertTrue(saved.isEmpty())
        assertEquals(2, saves)
    }

    @Test fun moreThan256ApplicationsCanBeSelectedSavedSearchedAndReopenedWithoutTruncation() {
        val largeList = (0..1024).map { InstalledNotificationApp("com.example.app$it", "应用$it") }
        val loader = { largeList }
        val allIds = largeList.map { it.packageId }.toSet()
        var saved = emptySet<String>()
        var saves = 0
        compose.setContent {
            var packages by remember { mutableStateOf(saved) }
            MaterialTheme {
                NotificationPushScopeSettings(packages, false, null, { saved = it; packages = it; saves++ }, loader)
            }
        }
        openPicker()
        compose.onNodeWithTag("notification-app-select-all").performClick()
        compose.onNodeWithTag("notification-app-save").assertIsEnabled().performClick()
        assertEquals(allIds, saved)
        assertEquals(1, saves)
        compose.onNodeWithTag("notification-app-picker").assertDoesNotExist()

        openPicker()
        compose.onNodeWithTag("notification-app-search").performTextReplacement(largeList.last().packageId)
        app(largeList.last().packageId).assertIsOn()
        compose.onNodeWithTag("notification-app-invert-selection").performClick()
        app(largeList.last().packageId).assertIsOff()
        compose.onNodeWithContentDescription("取消选择").performClick()
        assertEquals(allIds, saved)
        assertEquals(1, saves)

        openPicker()
        compose.onNodeWithTag("notification-app-search").performTextReplacement(largeList.last().packageId)
        app(largeList.last().packageId).assertIsOn()
        compose.onNodeWithTag("notification-app-invert-selection").performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(allIds - largeList.last().packageId, saved)
        assertEquals(2, saves)
    }

    @Test fun invisibleSavedApplicationsAreKeptUntilTheUserExplicitlyClearsThem() {
        val missing = "com.example.uninstalled"
        var saved = setOf(missing)
        compose.setContent {
            var packages by remember { mutableStateOf(saved) }
            MaterialTheme { NotificationPushScopeSettings(packages, false, null, { saved = it; packages = it }, appLoader) }
        }
        openPicker()
        app(missing).assertIsOn()
        compose.onNodeWithText("未安装或当前不可见，可以保留或取消原有选择").assertExists()
        app(mail).performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(setOf(missing, mail), saved)
        openPicker()
        app(missing).performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(setOf(mail), saved)
    }

    @Test fun failedApplicationQueryDisablesSaveAndRetryKeepsTheSavedSelection() {
        val attempts = AtomicInteger()
        var fail = true
        val loader = {
            attempts.incrementAndGet()
            if (fail) error("PACKAGE_QUERY_FAILED")
            apps
        }
        var saves = 0
        compose.setContent {
            MaterialTheme { NotificationPushScopeSettings(setOf(chat), false, null, { saves++ }, loader) }
        }
        compose.waitUntil(5_000) { attempts.get() > 0 }
        compose.onNodeWithTag("push-save-packages").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("notification-app-save").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("无法读取本机应用列表，请重试。").assertExists()
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("重试").performClick()
        waitForApps()
        app(chat).assertIsOn()
        compose.onNodeWithContentDescription("取消选择").performClick()
        assertEquals(0, saves)
    }

    @Test fun returningToForegroundRefreshesInstalledAppsWithoutDroppingAnUninstalledDraftSelection() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        var available = apps
        val attempts = AtomicInteger()
        val loader = { attempts.incrementAndGet(); available }
        var saved = setOf(chat)
        compose.setContent {
            var packages by remember { mutableStateOf(saved) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { NotificationPushScopeSettings(packages, false, null, { saved = it; packages = it }, loader) }
            }
        }
        openPicker()
        app(mail).performClick()
        val previousLoads = attempts.get()
        compose.runOnIdle {
            assertTrue("The settings page must observe its lifecycle owner", owner.lifecycle.observerCount > 0)
            available = listOf(InstalledNotificationApp(otherMail, "新邮件名称"))
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
        }
        compose.waitForIdle()
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { attempts.get() > previousLoads }
        waitForApps()
        app(mail).assertIsOn()
        app(chat).assertIsOn()
        app(otherMail).assertIsOff()
        compose.onNodeWithText("新邮件名称").assertExists()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(setOf(chat, mail), saved)
    }

    @Test fun concurrentRangeChangeRetainsDraftAndRequiresReloadBeforeSaving() {
        var current by mutableStateOf(setOf(chat))
        var saved = current
        var saves = 0
        compose.setContent {
            MaterialTheme { NotificationPushScopeSettings(current, false, null, { saved = it; current = it; saves++ }, appLoader) }
        }
        openPicker()
        app(mail).performClick()
        compose.runOnIdle { current = setOf(otherMail) }
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.onNodeWithText("设置没有保存成功：应用范围已在其他位置修改，请重新载入已保存选择后再保存。").assertExists()
        app(chat).assertIsOn()
        app(mail).assertIsOn()
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        assertEquals(0, saves)
        assertEquals(setOf(otherMail), current)
        compose.onNodeWithText("重新载入已保存选择").performClick()
        app(chat).assertIsOff()
        app(mail).assertIsOff()
        app(otherMail).assertIsOn()
        app(mail).performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(setOf(mail, otherMail), saved)
        assertEquals(1, saves)
    }

    @Test fun rejectedAsynchronousSaveKeepsThePickerDraftUntilExplicitReload() {
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var submitted: Set<String>? = null
        compose.setContent {
            MaterialTheme { NotificationPushScopeSettings(setOf(chat), saving, error, {
                submitted = it
                error = null
                saving = true
            }, appLoader) }
        }
        openPicker()
        app(mail).performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        app(mail).assertIsOn().assertIsNotEnabled()
        assertEquals(setOf(chat, mail), submitted)
        compose.runOnIdle { error = "存储拒绝本次保存"; saving = false }
        compose.onNodeWithText("设置没有保存成功：存储拒绝本次保存").assertExists()
        app(chat).assertIsOn()
        app(mail).assertIsOn()
        compose.onNodeWithText("重新载入已保存选择").performClick()
        app(mail).assertIsOff()
        app(chat).assertIsOn()
    }

    @Test fun unchangedSelectionWaitsForTheActualSaveResultAndDoesNotHideARejection() {
        var completion by mutableStateOf(0L)
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var calls = 0
        compose.setContent {
            MaterialTheme { NotificationPushScopeSettings(setOf(chat), saving, error,
                { calls++; saving = true }, appLoader, saveCompletion = completion) }
        }
        openPicker()
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.onNodeWithTag("notification-app-picker").assertExists()
        compose.onNodeWithTag("notification-app-save").assertIsNotEnabled()
        compose.runOnIdle { error = "推送配置已在其他位置修改"; saving = false; completion++ }
        compose.onNodeWithText("设置没有保存成功：推送配置已在其他位置修改").assertExists()
        app(chat).assertIsOn()
        assertEquals(1, calls)
    }

    @Test fun pickerDraftAndSearchSurviveStateRestorationWithoutChangingTheOpeningRange() {
        var packages by mutableStateOf(setOf(chat))
        var saves = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { NotificationPushScopeSettings(packages, false, null, { packages = it; saves++ }, appLoader) }
        }
        openPicker()
        compose.onNodeWithTag("notification-app-search").performTextReplacement("mail")
        app(mail).performClick()
        restoration.emulateSavedInstanceStateRestore()
        waitForApps()
        compose.onNodeWithTag("notification-app-search").assertTextContains("mail")
        app(mail).assertIsOn()
        compose.runOnIdle { packages = setOf(otherMail) }
        compose.onNodeWithTag("notification-app-save").performClick()
        compose.onNodeWithText("设置没有保存成功：应用范围已在其他位置修改，请重新载入已保存选择后再保存。").assertExists()
        assertEquals(0, saves)
    }

    @Test fun passiveAndPushUseSeparateSaveCallbacksWhileRetainingOriginalPassiveControls() {
        val packageManager = ApplicationProvider.getApplicationContext<Application>().packageManager
        apps.forEach { app ->
            shadowOf(packageManager).installPackage(PackageInfo().apply {
                packageName = app.packageId
                applicationInfo = ApplicationInfo().apply {
                    packageName = app.packageId
                    nonLocalizedLabel = app.label
                    flags = ApplicationInfo.FLAG_INSTALLED
                    enabled = true
                }
            })
            shadowOf(packageManager).setApplicationIcon(app.packageId, packageManager.defaultActivityIcon)
        }
        val passive = FakePolicy()
        val original = passive.snapshot()
        val endpoint = PushEndpoint("core", "account", "endpoint", 1)
        var pushed = PushConfiguration(
            notifications = NotificationPushSettings(true, NotificationPushScope(setOf(mail), false), setOf(endpoint)),
            sms = SmsPushSettings(true, SmsPushScope(senderAddresses = setOf("LEGACY-SENDER")), setOf(endpoint)),
            retentionHours = 24,
        )
        val originalPush = pushed
        var showPush by mutableStateOf(true)
        compose.setContent {
            var config by remember { mutableStateOf(pushed) }
            MaterialTheme {
                if (showPush) {
                    Column {
                        TextButton(onClick = { showPush = false }) { Text("打开被动设置") }
                        PushSettingsContent(
                            PushSnapshot(config, 0, false, 0, 0, 0, 0, 0, 0), emptyList(), false, null,
                            { pushed = it; config = it }, {}, {},
                        )
                    }
                } else {
                    NotificationCollectionScreen(passive, FakeBinding(), {}, loadInstalledApps = appLoader)
                }
            }
        }
        compose.onNodeWithTag("push-save-packages").performScrollTo().performClick()
        waitForApps()
        app(chat).performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(originalPush.copy(notifications = originalPush.notifications.copy(
            scope = originalPush.notifications.scope.copy(packageIds = setOf(chat, mail)),
        )), pushed)
        assertEquals(original, passive.snapshot())
        assertTrue(passive.updates.isEmpty())

        compose.onNodeWithText("打开被动设置").performClick()
        compose.onNodeWithTag("notification-collection-content").performScrollToNode(hasTestTag("notification-select-apps"))
        compose.onNodeWithTag("notification-select-apps").performClick()
        waitForApps()
        app(chat).performClick()
        app(otherMail).performClick()
        compose.onNodeWithTag("notification-app-save").performClick()
        assertEquals(listOf(otherMail), passive.snapshot().packageIds)
        assertEquals(7uL, passive.updates.single().expectedRevision)
        assertEquals(original.fieldAccess, passive.snapshot().fieldAccess)
        assertEquals(original.granted, passive.snapshot().granted)
        assertEquals(original.mode, passive.snapshot().mode)
        assertEquals(setOf(chat, mail), pushed.notifications.scope.packageIds)
    }

    private class FakePolicy : NotificationPolicyPort {
        private val state = MutableStateFlow(NotificationPolicySnapshot(
            true, true, listOf("com.example.chat"), NotificationFieldAccess.METADATA,
            NotificationDeliveryMode.ON_DEMAND, 7uL,
        ))
        val updates = mutableListOf<NotificationPolicyUpdate>()
        override fun snapshot() = state.value
        override fun observe() = state
        override fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome {
            updates += request
            if (request.expectedRevision != state.value.revision) return NotificationPolicyUpdateOutcome.Rejected("REVISION_STALE")
            state.value = state.value.copy(packageIds = request.packageIds.orEmpty(), revision = state.value.revision + 1uL)
            return NotificationPolicyUpdateOutcome.Accepted(state.value)
        }
    }

    private class FakeBinding : NotificationBindingPort {
        override fun isListenerBound() = false
        override fun openSystemListenerSettings(context: Context) = false
    }
}
