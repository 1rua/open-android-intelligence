package com.openandroidintelligence.conversation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsNavigationItemRendersHeadlineAndRespondsToClick() {
        var clicked = false
        compose.setContent {
            MaterialTheme {
                SettingsSectionCard(contentPadding = PaddingValues()) {
                    SettingsNavigationItem(
                        headline = "外观与动效",
                        supporting = "主题模式、动态取色与动画微调",
                        icon = Icons.Default.Palette,
                        onClick = { clicked = true },
                    )
                }
            }
        }
        compose.onNodeWithText("外观与动效").performClick()
        assertTrue("点击条目必须触发 onClick 回调", clicked)
    }

    @Test
    fun settingsSwitchItemRendersAndToggles() {
        val checkedState = mutableStateOf(false)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                SettingsSectionCard(contentPadding = PaddingValues()) {
                    SettingsSwitchItem(
                        headline = "系统动态取色",
                        modifier = Modifier.testTag("switch-row"),
                        checked = checkedState.value,
                        onCheckedChange = { checkedState.value = it; changes++ },
                    )
                }
            }
        }
        val row = compose.onNodeWithTag("switch-row")
        row.assertIsOff().performTouchInput { click(centerLeft + androidx.compose.ui.geometry.Offset(24f, 0f)) }
        row.assertIsOn()
        assertEquals("点击文字一侧只切换一次", 1, changes)
        row.performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(24f, 0f)) }
        row.assertIsOff()
        assertEquals("点击开关一侧也只切换一次", 2, changes)
    }

    @Test
    fun disabledSettingsItemsDoNotInvokeCallbacks() {
        var clicks = 0
        var changes = 0
        compose.setContent {
            MaterialTheme {
                SettingsSectionCard {
                    SettingsNavigationItem("不可用的导航", enabled = false, onClick = { clicks++ })
                    SettingsSwitchItem("不可用的开关", checked = false, enabled = false, onCheckedChange = { changes++ })
                }
            }
        }
        compose.onNodeWithText("不可用的导航").performTouchInput { click() }
        compose.onNodeWithText("不可用的开关").assertIsNotEnabled().performTouchInput { click() }
        assertEquals("禁用导航不得调用回调", 0, clicks)
        assertEquals("禁用开关不得调用回调", 0, changes)
    }

    @Test
    fun settingsIconBadgeRendersWithoutCrashing() {
        compose.setContent {
            MaterialTheme {
                SettingsIconBadge(
                    icon = Icons.Default.Palette,
                    tone = SettingsTone.PRIMARY,
                )
            }
        }
        compose.waitForIdle()
    }
}

