package com.openandroidintelligence.mobile.settings.advanced

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.conversations.DebouncePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageProcessingSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var preferences: DebouncePreferences
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        context.getSharedPreferences("conversation-debounce-v1", 0).edit().clear().commit()
        preferences = DebouncePreferences(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("conversation-debounce-v1", 0).edit().clear().commit()
    }

    @Test
    fun freshInstallDefaultsToImmediateSendWithoutMerging() {
        show()
        compose.onNodeWithText("合并连续消息").performScrollTo().assertExists()
        compose.onNodeWithText("当前设置：立即发送（不合并）").performScrollTo().assertExists()
        compose.onNodeWithText("设置为 0 秒时立即发送；大于 0 秒时合并短时间内连续发送的消息。")
            .performScrollTo().assertExists()
        assertFalse("不得提供覆盖 Core 配置的入口", screenContains("覆盖当前 Core 的设置"))
        assertFalse(screenContains("当前 Core 使用全局默认。"))
        compose.runOnIdle { assertEquals(0L, preferences.read().delay.inWholeMilliseconds) }
    }

    @Test
    fun sliderOnlyAcceptsTheDocumentedZeroToThirtySeconds() {
        show()
        setDelay(30_000f)
        compose.runOnIdle { assertEquals(30_000L, preferences.read().delay.inWholeMilliseconds) }
        compose.onNodeWithText("当前设置：等待 30.0 秒合并").performScrollTo().assertExists()
        setDelay(0f)
        compose.runOnIdle { assertEquals(0L, preferences.read().delay.inWholeMilliseconds) }
        compose.onNodeWithText("当前设置：立即发送（不合并）").performScrollTo().assertExists()
    }

    @Test
    fun storedValueIsWhatTheScreenShowsOnReturn() {
        preferences.write(5_000)
        show()
        compose.onNodeWithText("当前设置：等待 5.0 秒合并").performScrollTo().assertExists()
    }

    @Test
    fun writeFailureIsVisibleAndDoesNotClaimTheValueWasSaved() {
        preferences.write(0)
        show(update = { error("write failed") })
        setDelay(9_000f)
        compose.onNodeWithText("消息处理设置未能保存，重启后可能恢复原设置，请重试。").performScrollTo().assertExists()
        compose.onNodeWithText("当前设置：立即发送（不合并）").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0L, preferences.read().delay.inWholeMilliseconds) }
    }

    @Test
    fun missingRuntimeDisablesTheSliderWithoutFakeSuccess() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 700.dp).verticalScroll(rememberScrollState())) {
                    MessageProcessingEditor(preferences, null)
                }
            }
        }
        compose.onNodeWithText("消息处理暂不可编辑：运行时未连接。").assertExists()
        compose.onNodeWithContentDescription("合并等待时间").performScrollTo().assertIsNotEnabled()
    }

    /**
     * 旧版本按 Core 覆盖留下的键（`gateway:<sha256>:delay`）不再被界面读取：
     * 只有用户自己操作过的 `global:delay` 才是这里的权威。
     */
    @Test
    fun legacyPerCoreKeysDoNotChangeTheClientSetting() {
        context.getSharedPreferences("conversation-debounce-v1", 0).edit()
            .putInt("global:delay", 1_500)
            .putInt("gateway:" + "a".repeat(64) + ":delay", 9_000)
            .putInt("gateway:" + "a".repeat(64) + ":extend", 0)
            .commit()
        show()
        compose.onNodeWithText("当前设置：等待 1.5 秒合并").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(1_500L, preferences.read().delay.inWholeMilliseconds) }
    }

    /** 只有旧版按 Core 覆盖、没有任何全局值时，界面仍是默认的立即发送。 */
    @Test
    fun upgradesWithOnlyLegacyCoreKeysStillSendImmediately() {
        context.getSharedPreferences("conversation-debounce-v1", 0).edit()
            .putInt("gateway:" + "a".repeat(64) + ":delay", 9_000)
            .commit()
        show()
        compose.onNodeWithText("当前设置：立即发送（不合并）").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0L, preferences.read().delay.inWholeMilliseconds) }
    }

    private fun screenContains(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun setDelay(value: Float) {
        compose.onNodeWithContentDescription("合并等待时间").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(value) }
    }

    private fun show(update: ((Int) -> Unit)? = null) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 700.dp).verticalScroll(rememberScrollState())) {
                    MessageProcessingEditor(
                        preferences = preferences,
                        onSaveDelay = update ?: { delay -> preferences.write(delay) },
                    )
                }
            }
        }
    }
}
