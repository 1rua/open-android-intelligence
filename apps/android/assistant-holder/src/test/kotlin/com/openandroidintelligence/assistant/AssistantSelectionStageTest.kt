package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 全屏圈选舞台的回归。
 *
 * 断言三类用户可见的事实：
 * 1. **圈选流程**：拖动 → 确认 → 后台裁剪编码 → 生成 PNG 并写入本进程预览，交给宿主发送；
 *    取消不会产出任何附件；
 * 2. **裁剪坐标**：画布坐标按 `截图像素 / 画布像素` 换算回帧像素，裁出的尺寸与位置正确，
 *    且原始截图仍归会话所有、不被回收；
 * 3. **失败边界**：没有截图 / 采集中 / 选区退化 / 编码不可用时，绝不以“空附件”冒充提交。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantSelectionStageTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(width: Int = 100, height: Int = 200): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    // -----------------------------------------------------------------------
    // 裁剪坐标
    // -----------------------------------------------------------------------

    /**
     * 画布 50×100、截图 100×200：选区 (10,10)-(30,40) 必须换算成
     * 像素 (20,20)-(60,80)，即 40×60 的裁剪，而不是把画布坐标当成像素。
     */
    @Test
    fun rectangularSelectionIsScaledBackToFramePixelsAndCachedUnderTheLocalKey() {
        val source = screenshot()
        val previews = AssistantLocalPreviews()

        val result = createAssistantSelection(
            source = source,
            left = 10f,
            top = 10f,
            right = 30f,
            bottom = 40f,
            canvasWidth = 50f,
            canvasHeight = 100f,
            localKey = "draft_screen_1",
            previews = previews,
        )

        assertNotNull("有效选区必须产出附件", result)
        result!!
        assertEquals("draft_screen_1", result.localKey)
        assertEquals(40, result.widthPx)
        assertEquals(60, result.heightPx)
        assertEquals(result.byteSize, result.pngBytes().size)

        val cached = previews.thumbnail("draft_screen_1")
        assertNotNull("本进程预览应按 localKey 保存裁剪结果", cached)
        decode(cached!!)?.let { decoded ->
            assertEquals(40, decoded.width)
            assertEquals(60, decoded.height)
            assertEquals(Color.BLUE, decoded.getPixel(20, 30))
            decoded.recycle()
        }
    }

    /** 圈选不能把会话手里的截图弄废：同一会话里可以连续圈选多次。 */
    @Test
    fun theScreenScreenshotStaysOwnedByTheSessionAfterCropping() {
        val source = screenshot()
        val previews = AssistantLocalPreviews()

        val result = createAssistantSelection(
            source = source,
            left = 0f,
            top = 0f,
            right = 50f,
            bottom = 100f,
            canvasWidth = 50f,
            canvasHeight = 100f,
            localKey = "draft_full",
            previews = previews,
        )

        assertNotNull(result)
        assertFalse("裁剪不得回收会话持有的截图", source.isRecycled)
        assertEquals(Color.BLUE, source.getPixel(50, 100))
        // 整图选区会返回复制件，再次圈选仍然可用。
        assertNotNull(
            createAssistantSelection(source, 0f, 0f, 25f, 50f, 50f, 100f, "draft_second", previews),
        )
    }

    @Test
    fun aDegenerateOrRecycledSelectionNeverProducesAnAttachment() {
        val previews = AssistantLocalPreviews()
        val source = screenshot()

        assertNull(
            "画布未测量时不能裁图",
            createAssistantSelection(source, 0f, 0f, 10f, 10f, 0f, 0f, "draft_none", previews),
        )
        assertNull(
            "面积过小的选区不能变成附件",
            createAssistantSelection(source, 0f, 0f, 1f, 1f, 100f, 200f, "draft_tiny", previews),
        )
        source.recycle()
        assertNull(
            "已回收的截图不能再裁",
            createAssistantSelection(source, 0f, 0f, 50f, 100f, 100f, 200f, "draft_dead", previews),
        )
        assertEquals("失败的确认不能往预览里写任何东西", 0, previews.size)
    }

    /** 自由圈选只保留路径内部的像素，其余透明。 */
    @Test
    fun freehandSelectionKeepsOnlyThePathInteriorAndIsCached() {
        val source = screenshot(width = 100, height = 100)
        val previews = AssistantLocalPreviews()

        val result = createAssistantFreehandSelection(
            source = source,
            points = listOf(Offset(10f, 10f), Offset(80f, 10f), Offset(10f, 80f)),
            canvasWidth = 100f,
            canvasHeight = 100f,
            localKey = "draft_freehand",
            previews = previews,
        )

        assertNotNull(result)
        result!!
        assertEquals(70, result.widthPx)
        assertEquals(70, result.heightPx)
        val decoded = decode(previews.thumbnail("draft_freehand")!!)
        assertNotNull(decoded)
        decoded!!
        assertTrue("路径内部保留原图", Color.alpha(decoded.getPixel(2, 2)) > 0)
        assertEquals("路径外部被蒙版抠掉", 0, Color.alpha(decoded.getPixel(60, 60)))
        decoded.recycle()

        assertNull(
            "少于三个点的自由圈选无效",
            createAssistantFreehandSelection(
                source = source,
                points = listOf(Offset(1f, 1f), Offset(9f, 9f)),
                canvasWidth = 100f,
                canvasHeight = 100f,
                localKey = "draft_bad",
                previews = previews,
            ),
        )
        assertNull("无效圈选不能留下预览", previews.thumbnail("draft_bad"))
    }

    /**
     * 预览写入失败（会话已结束）不应吞掉已经裁好的字节：
     * 宿主仍然可以发送，只是这一条没有本地缩略图。
     */
    @Test
    fun aClosedPreviewStoreDoesNotBlockDeliveringTheSelectedImage() {
        val source = screenshot()
        val previews = AssistantLocalPreviews().apply { close() }

        val result = createAssistantSelection(
            source = source,
            left = 0f,
            top = 0f,
            right = 50f,
            bottom = 100f,
            canvasWidth = 50f,
            canvasHeight = 100f,
            localKey = "draft_closed",
            previews = previews,
        )

        assertNotNull("字节仍然可用", result)
        assertNull("关闭后的存储不应保留屏幕内容", previews.thumbnail("draft_closed"))
        assertTrue(result!!.pngBytes().isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // 圈选流程（组合层）
    // -----------------------------------------------------------------------

    private val delivered = AtomicInteger()
    private val rejected = AtomicInteger()

    private fun showStage(
        screenshot: Bitmap?,
        onCancel: () -> Unit = {},
        onCropRejected: () -> Unit = {},
        capturing: Boolean = false,
        ready: AtomicReference<AssistantSelectionResult?> = AtomicReference(null),
        previews: AssistantLocalPreviews = AssistantLocalPreviews(),
    ): AtomicReference<AssistantSelectionResult?> {
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    AssistantSelectionStage(
                        screenshot = screenshot,
                        previews = previews,
                        localKey = "draft_stage",
                        onSelectionReady = {
                            delivered.incrementAndGet()
                            ready.set(it)
                        },
                        onCancel = onCancel,
                        capturing = capturing,
                        onCropRejected = {
                            rejected.incrementAndGet()
                            onCropRejected()
                        },
                    )
                }
            }
        }
        return ready
    }

    private fun dragSelection(distance: androidx.compose.ui.unit.Dp) {
        val px = with(compose.density) { distance.toPx() }
        compose.onNodeWithContentDescription("屏幕圈选").performTouchInput {
            swipe(
                start = Offset(40f, 300f),
                end = Offset(40f + px, 300f + px),
                durationMillis = 200,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun draggingConfirmingAndEncodingDeliversAPngAttachmentBackedByTheLocalPreview() {
        val previews = AssistantLocalPreviews()
        val ready = showStage(screenshot(), previews = previews)

        dragSelection(60.dp)
        compose.onNodeWithText("确认选区").performClick()

        compose.waitUntil(5_000) { ready.get() != null }
        compose.waitForIdle()
        val result = ready.get()!!
        assertEquals("一次确认只交付一次", 1, delivered.get())
        assertEquals("成功的确认不应报拒绝", 0, rejected.get())
        assertEquals("draft_stage", result.localKey)
        assertTrue("交付的必须是真实 PNG 字节", result.pngBytes().isNotEmpty())
        val cached = previews.thumbnail("draft_stage")
        assertNotNull("确认后应写入本进程预览", cached)
        decode(cached!!)?.let { decoded ->
            assertEquals(result.widthPx, decoded.width)
            assertEquals(result.heightPx, decoded.height)
            decoded.recycle()
        }
    }

    @Test
    fun cancellingClosesTheStageWithoutProducingAnySelection() {
        val cancelled = AtomicInteger()
        val ready = showStage(screenshot(), onCancel = { cancelled.incrementAndGet() })

        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        assertEquals(1, cancelled.get())
        assertNull("取消不得产出附件", ready.get())
    }

    // -----------------------------------------------------------------------
    // 截图供给与降级
    // -----------------------------------------------------------------------

    @Test
    fun aMissingScreenshotKeepsTheExplicitUnavailableState() {
        showStage(screenshot = null)

        compose.onNodeWithTag(AssistantSelectionStageTags.ROOT).assertExists()
        compose.onNodeWithText("系统没有提供可用的 Assist 截图，无法进行屏幕圈选。")
            .assertIsDisplayed()
        compose.onNodeWithText("确认选区").assertDoesNotExist()
    }

    @Test
    fun anAlreadyRecycledScreenshotIsTreatedAsUnavailable() {
        val dead = screenshot().apply { recycle() }
        showStage(dead)

        compose.onNodeWithText("系统没有提供可用的 Assist 截图，无法进行屏幕圈选。")
            .assertIsDisplayed()
        compose.onNodeWithText("确认选区").assertDoesNotExist()
    }

    @Test
    fun capturingShowsTheIntermediateStateInsteadOfFailure() {
        showStage(screenshot = null, capturing = true)

        compose.onNodeWithText("正在截取屏幕…").assertIsDisplayed()
        compose.onNodeWithText("系统没有提供可用的 Assist 截图，无法进行屏幕圈选。")
            .assertDoesNotExist()
    }
}
