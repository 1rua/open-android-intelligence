package com.openandroidintelligence.mobile

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production settings graph with local configuration only; no remote data is requested. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoriesOpenAndReturnThroughTheRealGraphWithoutGrantingPermissions() {
        val app = ApplicationProvider.getApplicationContext<Application>() as OpenAndroidIntelligenceApplication
        compose.setContent {
            MaterialTheme { SettingsScreen(environment = app.platformSettingsEnvironment(), onBack = {}) }
        }
        val categories = listOf("外观与交互", "连接与账号", "通知与提醒", "设备能力与授权", "安全与隐私", "高级设置与关于")
        categories.forEach { name ->
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("设置").assertIsDisplayed()
        }
    }
}
