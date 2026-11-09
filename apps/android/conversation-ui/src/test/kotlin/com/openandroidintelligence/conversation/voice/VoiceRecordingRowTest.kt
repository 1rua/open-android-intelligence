package com.openandroidintelligence.conversation.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.workbench.ComposerBar
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音录音界面的共享回归。
 *
 * 主会话输入栏与默认数字助手的紧凑输入栏都渲染同一个 [VoiceRecordingRow]：
 * 1. 录音态显示波形、计时与「取消 / 发送语音」，不再出现输入框与麦克风入口；
 * 2. 取消与发送各自只触发自己的回调；
 * 3. 不打开发送两次——重复点击不会产生第二条语音消息（由录音器状态机兜底，
 *    这里只断言界面把每次点击如实转发给宿主）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class VoiceRecordingRowTest {
    @get:Rule val compose = createComposeRule()

    private var cancels = 0
    private var sends = 0

    private fun renderRow(elapsedMs: Long = 7_400L, level: Float = 0.5f) {
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    VoiceRecordingRow(
                        elapsedMs = elapsedMs,
                        level = level,
                        onCancel = { cancels++ },
                        onSend = { sends++ },
                    )
                }
            }
        }
    }

    @Test
    fun recordingRowShowsTheDurationTheWaveformAndBothActions() {
        renderRow()

        compose.onNodeWithContentDescription("正在录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("录音波形", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0:07").assertExists()
        compose.onNodeWithContentDescription("取消录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送语音").assertIsDisplayed()
    }

    @Test
    fun cancelAndSendTriggerOnlyTheirOwnCallbacks() {
        renderRow()

        compose.onNodeWithContentDescription("取消录音").performClick()
        assertEquals(1, cancels)
        assertEquals(0, sends)

        compose.onNodeWithContentDescription("发送语音").performClick()
        assertEquals(1, sends)
    }

    @Test
    fun durationTextSharesOneFormatWithTheTimeline() {
        renderRow(elapsedMs = 65_000L)

        // 与时间线语音附件同一个格式化函数：不会出现「1:05」与「1:5」两种写法。
        compose.onNodeWithText(formatVoiceDuration(65_000L)).assertExists()
        assertEquals("1:05", formatVoiceDuration(65_000L))
    }

    @Test
    fun composerReplacesTheInputRowWhileRecordingInsteadOfAddingACardAboveIt() {
        var recording by mutableStateOf<ActiveVoiceRecording?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    ComposerBar(
                        draft = "",
                        onDraftChange = {},
                        generation = GenerationState.IDLE,
                        canSend = true,
                        onSend = {},
                        onStop = {},
                        onPickCamera = {},
                        onPickGallery = {},
                        onPickDocument = {},
                        onVoiceInput = {},
                        recording = recording,
                        onCancelRecording = { cancels++ },
                        onFinishRecording = { sends++ },
                    )
                }
            }
        }

        // 没有录音：普通输入行在位，没有录音控件。
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送语音").assertDoesNotExist()

        compose.runOnIdle { recording = ActiveVoiceRecording(elapsedMs = 3_200L, level = 0.3f) }
        compose.waitForIdle()

        // 录音中：主行原地替换为共享录音行，输入入口与普通发送都不在。
        compose.onNodeWithContentDescription("正在录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送").assertDoesNotExist()

        compose.onNodeWithContentDescription("取消录音").performClick()
        assertEquals(1, cancels)

        compose.runOnIdle { recording = null }
        compose.waitForIdle()
        // 取消后回到输入态：输入入口恢复，录音控件消失。
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送语音").assertDoesNotExist()
    }

    @Test
    fun onlyAnActiveRecordingMapsToTheRecordingUi() {
        assertEquals(null, VoiceRecorderState.Idle.activeRecording())
        assertEquals(null, VoiceRecorderState.Sending("thread_a").activeRecording())

        val active = VoiceRecorderState.Recording(sessionKey = "thread_a", elapsedMs = 2_400L, level = 0.4f)
            .activeRecording()
        assertEquals(2_400L, requireNotNull(active).elapsedMs)
        assertEquals(0.4f, active.level, 0f)
    }
}
