package com.openandroidintelligence.mobile.settings.advanced

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.batch.DebounceMode
import com.openandroidintelligence.mobile.conversations.DebouncePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private val coreA = "https://core-a.example.test"
    private val coreB = "https://core-b.example.test"

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
    fun globalAndCoreRemainIndependentlyEditableAndCanReturnToGlobal() {
        preferences.write(null, 1500, true)
        show(coreA)
        compose.onNodeWithText("覆盖当前 Core 的设置").performScrollTo().assertIsOff().performClick()
        compose.runOnIdle {
            assertTrue(preferences.hasOverride(coreA))
            assertEquals(1500L, preferences.read(coreA).delay.inWholeMilliseconds)
        }
        setDelay("当前 Core 覆盖 等待时间", 10000f)
        compose.runOnIdle {
            assertEquals(10000L, preferences.read(coreA).delay.inWholeMilliseconds)
            assertEquals(1500L, preferences.read(null).delay.inWholeMilliseconds)
        }
        setDelay("全局默认 等待时间", 0f)
        compose.runOnIdle {
            assertEquals(0L, preferences.read(null).delay.inWholeMilliseconds)
            assertEquals(10000L, preferences.read(coreA).delay.inWholeMilliseconds)
        }
        compose.onNodeWithText("当前 Core 覆盖：新消息延长等待").performScrollTo().assertIsOn().performClick()
        compose.runOnIdle { assertEquals(DebounceMode.FIXED_WINDOW, preferences.read(coreA).mode) }
        compose.onNodeWithText("覆盖当前 Core 的设置").performScrollTo().assertIsOn().performClick()
        compose.runOnIdle {
            assertFalse(preferences.hasOverride(coreA))
            assertEquals(0L, preferences.read(coreA).delay.inWholeMilliseconds)
            assertEquals(30L, preferences.read(coreA).maximumWait.inWholeSeconds)
            assertEquals(20, preferences.read(coreA).maximumMembers)
        }
    }

    @Test
    fun coreChangeReadsCorrectSavedOverrideAndOfflineUrlStillWorks() {
        preferences.write(coreA, 500, false)
        preferences.write(coreB, 10000, true)
        var current by mutableStateOf<String?>(coreA)
        show(core = { current })
        compose.onNodeWithText("等待 0.5 秒").assertExists()
        compose.runOnIdle { current = coreB }
        compose.onNodeWithText("等待 10.0 秒").performScrollTo().assertExists()
        compose.onNodeWithText("当前 Core：$coreB").performScrollTo().assertExists()
        compose.onNodeWithText("覆盖当前 Core 的设置").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(preferences.hasOverride(coreB))
            assertTrue(preferences.hasOverride(coreA))
        }
    }

    @Test
    fun writeFailureIsVisibleAndDoesNotClaimAnOverride() {
        show(coreA, update = { _, _, _ -> error("write failed") })
        compose.onNodeWithText("覆盖当前 Core 的设置").performScrollTo().performClick()
        compose.onNodeWithText("消息处理设置未能保存，重启后可能恢复原设置，请重试。").performScrollTo().assertExists()
        compose.onNodeWithText("覆盖当前 Core 的设置").performScrollTo().assertIsOff()
        compose.runOnIdle { assertFalse(preferences.hasOverride(coreA)) }
    }

    @Test
    fun missingRuntimeShowsDisabledControlsWithoutFakeSuccess() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 700.dp).verticalScroll(rememberScrollState())) {
                    MessageProcessingEditor(preferences, coreA, null, null)
                }
            }
        }
        compose.onNodeWithText("消息处理暂不可编辑：运行时未连接。").assertExists()
        compose.onNodeWithText("覆盖当前 Core 的设置").performScrollTo().assertIsNotEnabled()
    }

    private fun setDelay(label: String, value: Float) {
        compose.onNodeWithContentDescription(label).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(value) }
    }

    private fun show(core: String?, update: ((String?, Int, Boolean) -> Unit)? = null) = show({ core }, update)

    private fun show(core: @Composable () -> String?, update: ((String?, Int, Boolean) -> Unit)? = null) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 700.dp).verticalScroll(rememberScrollState())) {
                    MessageProcessingEditor(
                        preferences = preferences,
                        gatewayUrl = core(),
                        onUpdateSettings = update ?: { gateway, delay, extend -> preferences.write(gateway, delay, extend) },
                        onUseGlobalSettings = preferences::useGlobal,
                    )
                }
            }
        }
    }
}
