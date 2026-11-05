package com.openandroidintelligence.mobile.settings.appearance

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.mobile.AppearanceSettings
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.onboarding.screens.AppearanceSetupScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppearanceSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsDisablesPresetsWhileDynamicOnAndExposesOtherSettings() {
        var settings by mutableStateOf(AppearanceSettings(dynamicColor = true))
        compose.setContent {
            MaterialTheme {
                AppearanceSettingsScreen(
                    uiState = SettingsUiState(appearance = settings),
                    presentation = SettingsPresentation.SIDEBAR,
                    onBack = {},
                    onSetTheme = { settings = settings.copy(theme = it) },
                    onSetDynamicColor = { settings = settings.copy(dynamicColor = it) },
                    onSetDynamicIconColor = { settings = settings.copy(dynamicIconColor = it) },
                    onSetPalette = { settings = settings.copy(palette = it) },
                    onSetReduceMotion = { settings = settings.copy(reduceMotion = it) },
                )
            }
        }
        compose.onNodeWithText("靛蓝").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("系统动态取色").performScrollTo().performClick()
        compose.onNodeWithText("靛蓝").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(settings.dynamicColor)
            assertEquals(ThemePalette.INDIGO, settings.palette)
        }
        compose.onNodeWithText("靛蓝").assertIsSelected()
        compose.onNodeWithText("图标动态取色").performScrollTo().performClick()
        compose.onNodeWithText("减少动画").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(settings.dynamicIconColor)
            assertTrue(settings.reduceMotion)
        }
    }

    @Test
    fun onboardingPresetTurnsDynamicOffWithoutAddingIconOrMotionSettings() {
        var settings by mutableStateOf(AppearanceSettings(dynamicColor = true))
        var next = 0
        var back = 0
        compose.setContent {
            MaterialTheme {
                AppearanceSetupScreen(
                    settings = settings,
                    onSetTheme = { settings = settings.copy(theme = it) },
                    onSetDynamicColor = { settings = settings.copy(dynamicColor = it) },
                    onSetPalette = { settings = settings.copy(palette = it, dynamicColor = false) },
                    onNext = { next++ },
                    onBack = { back++ },
                )
            }
        }
        compose.onNodeWithText("色彩主题").performScrollTo().assertExists()
        compose.onNodeWithText("图标同款").performScrollTo().assertExists()
        compose.onNodeWithText("靛蓝").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ThemePalette.INDIGO, settings.palette)
            assertFalse(settings.dynamicColor)
        }
        compose.onNodeWithText("图标动态取色").assertDoesNotExist()
        compose.onNodeWithText("减少动画").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-appearance-next").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(1, next)
            assertEquals(1, back)
        }
    }

    @Test
    fun failedSaveIsVisibleAndDoesNotChangeSelectedTheme() {
        compose.setContent {
            MaterialTheme {
                AppearanceSettingsScreen(
                    uiState = SettingsUiState(), presentation = SettingsPresentation.SCREEN, onBack = {},
                    onSetTheme = { error("cannot save") }, onSetDynamicColor = {},
                    onSetDynamicIconColor = {}, onSetPalette = {}, onSetReduceMotion = {},
                )
            }
        }
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("外观设置未能保存，请重试。").assertExists()
        compose.onNodeWithText("跟随系统").assertIsSelected()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun sidebarAtLargeFontKeepsThemeLabelsReadable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 700.dp).testTag("appearance-sidebar")) {
                        AppearanceSettingsScreen(
                            uiState = SettingsUiState(), presentation = SettingsPresentation.SIDEBAR, onBack = {},
                            onSetTheme = {}, onSetDynamicColor = {}, onSetDynamicIconColor = {},
                            onSetPalette = {}, onSetReduceMotion = {},
                        )
                    }
                }
            }
        }
        listOf("跟随系统", "浅色", "深色", "系统动态取色", "图标动态取色", "减少动画").forEach { label ->
            val bounds = compose.onNodeWithText(label).performScrollTo().fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("appearance-sidebar").fetchSemanticsNode().boundsInRoot
            assertTrue("$label 超出侧栏", bounds.left >= panel.left && bounds.right <= panel.right)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label 缺少文本布局", layouts.isNotEmpty())
            layouts.forEach { layout ->
                val lineCount = layout.lineCount
                assertTrue("$label 必须包含至少一行", lineCount > 0)
                for (line in 0 until lineCount) {
                    assertFalse("$label 第 $line 行不应被省略", layout.isLineEllipsized(line))
                }
            }
        }
    }
}
