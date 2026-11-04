package com.openandroidintelligence.mobile.settings.advanced

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.updates.InstalledAppBuild
import com.openandroidintelligence.mobile.updates.installedAppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdvancedSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun fourGroupsExposeRealNavigationWithoutStartingUpdateCheck() {
        var updates = 0
        var build = 0
        var licenses = 0
        var replay = 0
        var back = 0
        compose.setContent {
            MaterialTheme {
                AdvancedSettingsScreen(
                    uiState = SettingsUiState(), runtime = null, presentation = SettingsPresentation.SIDEBAR,
                    onBack = { back++ }, onOpenUpdates = { updates++ }, onOpenBuildDetails = { build++ },
                    onOpenLicenses = { licenses++ }, onReplayOnboarding = { replay++ },
                )
            }
        }
        compose.onNodeWithText("消息处理").assertExists()
        compose.onNodeWithText("应用维护").performScrollTo().assertExists()
        compose.onNodeWithText("帮助与反馈").performScrollTo().assertExists()
        compose.onNodeWithText("关于 OAI").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, updates) }
        compose.onNodeWithText("应用更新").performScrollTo().performClick()
        compose.onNodeWithText("新手引导与部署教程").performScrollTo().performClick()
        compose.onNodeWithText("构建详情").performScrollTo().performClick()
        compose.onNodeWithText("开源许可证").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(1, updates)
            assertEquals(1, replay)
            assertEquals(1, build)
            assertEquals(1, licenses)
            assertEquals(1, back)
        }
        compose.onNodeWithText(installedAppBuild(context).versionName).performScrollTo().assertExists()
    }

    @Test
    fun buildDetailsDoesNotInventMissingMetadata() {
        val installed = InstalledAppBuild(
            versionName = "1.2.3", versionCode = 123L, commit = "", channel = "unknown-channel",
            applicationId = "com.example.test", distribution = "full", signingCertificateSha256 = null,
        )
        compose.setContent {
            MaterialTheme { BuildDetailsContent(installed, SettingsPresentation.SIDEBAR, onBack = {}) }
        }
        compose.onNodeWithText("1.2.3").assertExists()
        compose.onNodeWithText("123").assertExists()
        compose.onNodeWithText("unknown-channel").assertExists()
        compose.onAllNodesWithText("未知", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("com.example.test").performScrollTo().assertExists()
    }

    @Test
    fun licenseAssetsMatchProjectTextAndDisplayOffline() {
        val rootLicense = context.assets.open("licenses/oai/LICENSE").bufferedReader().use { it.readText() }
        val katexLicense = context.assets.open("katex/LICENSE").bufferedReader().use { it.readText() }
        assertTrue(rootLicense.contains("Copyright (c) 2026 Open Android Intelligence"))
        assertTrue(katexLicense.contains("Copyright (c) 2013-2020 Khan Academy and other contributors"))
        assertNull(readNotice(context.assets, "missing-license"))
        compose.setContent {
            MaterialTheme { OpenSourceNoticesScreen(SettingsPresentation.SIDEBAR, onBack = {}) }
        }
        compose.onNodeWithText("此处展示项目许可证及随应用分发的 KaTeX 许可证原文；并非全部第三方依赖清单。").assertExists()
        compose.onNodeWithText(rootLicense).performScrollTo().assertExists()
        compose.onNodeWithText(katexLicense).performScrollTo().assertExists()
    }
}
