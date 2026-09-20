package com.openandroidintelligence.conversation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                SettingsNavigationItem(
                    headline = "外观与动效",
                    supporting = "主题模式、动态取色与动画微调",
                    icon = Icons.Default.Palette,
                    onClick = { clicked = true },
                )
            }
        }
        compose.onNodeWithText("外观与动效").performClick()
        assertTrue("点击条目必须触发 onClick 回调", clicked)
    }

    @Test
    fun settingsSwitchItemRendersAndToggles() {
        var checkedState = false
        compose.setContent {
            MaterialTheme {
                SettingsSwitchItem(
                    headline = "系统动态取色",
                    checked = checkedState,
                    onCheckedChange = { checkedState = it },
                )
            }
        }
        compose.onNodeWithText("系统动态取色").performClick()
        assertTrue("点击整行必须反转开关状态", checkedState)
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

