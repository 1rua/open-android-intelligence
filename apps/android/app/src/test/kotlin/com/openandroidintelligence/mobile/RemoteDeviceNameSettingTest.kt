package com.openandroidintelligence.mobile

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteDeviceNameSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun 设置改名保留取消校验与保存行为并说明远端生效时间() {
        val saved = mutableListOf<String>()
        compose.setContent {
            var name by remember { mutableStateOf("系统手机名") }
            MaterialTheme { Surface { RemoteDeviceNameSetting(name) { saved += it; name = it.trim() } } }
        }
        compose.onNodeWithText("设备名称").performClick()
        compose.onNodeWithText("保存后在下次账号登录或扫码配对时更新远端名称").assertExists()
        compose.onNodeWithTag("remote-device-name-input").performTextReplacement(" ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("系统手机名").assertExists()
        compose.onNodeWithText("设备名称").performClick()
        compose.onNodeWithTag("remote-device-name-input").performTextReplacement("我的新手机")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals(listOf("我的新手机"), saved)
        compose.onNodeWithText("我的新手机").assertExists()
        compose.onNodeWithTag("remote-device-name-input").assertDoesNotExist()
    }

    @Test fun 保存失败保留编辑内容且不能假报成功() {
        compose.setContent { MaterialTheme { Surface { RemoteDeviceNameSetting("系统手机名") { error("模拟持久化失败") } } } }
        compose.onNodeWithText("设备名称").performClick()
        compose.onNodeWithTag("remote-device-name-input").performTextReplacement("尚未保存的名称")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("设备名称保存失败，请重试").assertExists()
        compose.onNodeWithTag("remote-device-name-input").assertTextContains("尚未保存的名称")
    }
}
