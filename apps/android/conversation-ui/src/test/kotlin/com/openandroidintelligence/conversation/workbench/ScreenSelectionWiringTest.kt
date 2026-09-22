package com.openandroidintelligence.conversation.workbench

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureSource
import java.io.ByteArrayOutputStream
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 圈选层接线的组合测试：fake 采集源驱动 [ScreenSelectionOverlayHost]，
 * 钉住三条用户可见的行为——
 * ① 来源可用且采集成功 → overlay 收到真实截图，进入可圈选态；
 * ② 来源不可用 → 维持「明确不可用」降级态（基线 B6 不变）；
 * ③ 采集失败（返回 null）/帧损坏 → 同样降级，绝不冒充有截图。
 *
 * 真实的 MediaProjection 投屏不在这里驱动；系统授权后的采集行为由
 * capability-ports 的编排测试与后续设备验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSelectionWiringTest {

    @get:Rule
    val compose = createComposeRule()

    private val unavailableText = "系统没有提供可用的 Assist 截图，无法进行屏幕圈选。"
    private val availableText = "拖动选择要分享的区域"

    private fun pngFrame(width: Int = 128, height: Int = 64): ScreenCapture {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(255, 47, 100, 92))
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return ScreenCapture.copyOf(output.toByteArray(), width, height)
    }

    private class FakeScreenCaptureSource(
        private val available: Boolean,
        private val frame: ScreenCapture?,
    ) : ScreenCaptureSource {
        var captureCalls: Int = 0
            private set

        override val isAvailable: Boolean get() = available

        override fun createAuthorizationIntent(): Intent? = null

        override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean = false

        override suspend fun capture(): ScreenCapture? {
            captureCalls++
            return frame
        }

        override fun release() {}
    }

    /** ① 可用：截图被传给 overlay，可圈选态出现、不可用态消失。 */
    @Test
    fun availableSource_passesCapturedFrameToOverlay() {
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlayHost(
                    screenCaptureSource = FakeScreenCaptureSource(available = true, frame = pngFrame()),
                    onCancel = {},
                    onConfirmCrop = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(availableText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(availableText).assertIsDisplayed()
        compose.onNodeWithText(unavailableText).assertDoesNotExist()
        // 未拖出有效选区时，确认按钮存在但禁用（B6：不以松手冒充提交）
        compose.onNodeWithText("确认选区").assertIsNotEnabled()
    }

    /** ② 不可用：维持降级态，确认按钮不存在（B6：不触发确认回调）。 */
    @Test
    fun unavailableSource_keepsExplicitUnavailableState() {
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlayHost(
                    screenCaptureSource = FakeScreenCaptureSource(available = false, frame = pngFrame()),
                    onCancel = {},
                    onConfirmCrop = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(unavailableText).assertIsDisplayed()
        compose.onNodeWithText(availableText).assertDoesNotExist()
        compose.onNodeWithText("确认选区").assertDoesNotExist()
    }

    /** ③ 采集失败：来源在线但这帧没拿到，同样降级。 */
    @Test
    fun failedCapture_degradesToUnavailableState() {
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlayHost(
                    screenCaptureSource = FakeScreenCaptureSource(available = true, frame = null),
                    onCancel = {},
                    onConfirmCrop = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(unavailableText).assertIsDisplayed()
        compose.onNodeWithText(availableText).assertDoesNotExist()
        compose.onNodeWithText("确认选区").assertDoesNotExist()
    }

    /** 帧损坏（解码失败）也不能冒充截图，落到同一降级态。 */
    @Test
    fun corruptedFrame_degradesToUnavailableState() {
        val corrupted = ScreenCapture.copyOf(byteArrayOf(1, 2, 3, 4), 8, 6)
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlayHost(
                    screenCaptureSource = FakeScreenCaptureSource(available = true, frame = corrupted),
                    onCancel = {},
                    onConfirmCrop = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(unavailableText).assertIsDisplayed()
        compose.onNodeWithText(availableText).assertDoesNotExist()
        compose.onNodeWithText("确认选区").assertDoesNotExist()
    }

    /** 没有任何来源（宿主未装配）时与基线行为一致：不可用态。 */
    @Test
    fun missingSource_keepsBaselineUnavailableState() {
        compose.setContent {
            MaterialTheme {
                ScreenSelectionOverlayHost(
                    screenCaptureSource = null,
                    onCancel = {},
                    onConfirmCrop = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(unavailableText).assertIsDisplayed()
        compose.onNodeWithText("确认选区").assertDoesNotExist()
    }
}
