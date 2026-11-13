package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 定格截图上的「松手即确认」圈选层。
 *
 * 锁住四件事：
 * 1. **有效手势自动产出附件**：画一个闭合回路，松手即裁剪成 PNG 并写入本进程预览，
 *    不需要任何「确认选区」按钮；
 * 2. **误触不产出**：点数不足或面积过小只清空轨迹；
 * 3. **控件区域不参与圈选**：起点落在排除区（输入栏 / 横条）内的手势被放弃；
 * 4. **连续圈选**：一次成功不清空定格态，可以继续画下一个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantLiveSelectionSurfaceTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(width: Int = 200, height: Int = 400): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }

    private class Results {
        val values = mutableListOf<AssistantSelectionResult>()
        val rejections = AtomicInteger()
    }

    private fun render(
        previews: AssistantLocalPreviews = AssistantLocalPreviews(),
        enabled: Boolean = true,
        exclusionRects: List<Rect> = emptyList(),
    ): Pair<Results, AssistantLocalPreviews> {
        val results = Results().also { this.results = it }
        compose.setContent {
            CompositionLocalProvider(
                LocalMotionPolicy provides MotionPolicy(reduceMotion = true),
            ) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        AssistantLiveSelectionSurface(
                            screenshot = screenshot(),
                            previews = previews,
                            localKey = "live-selection",
                            enabled = enabled,
                            exclusionRects = { exclusionRects },
                            onSelectionReady = { results.values += it },
                            onRejected = { results.rejections.incrementAndGet() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        return results to previews
    }

    /**
     * 画一个闭合回路：四个角足以形成正面积，松手后自动闭合。
     *
     * 用 [compose.waitUntil] 等真实交付：裁剪发生在后台线程，`waitForIdle` 只代表
     * 主线程静止，并不等待这次后台任务。
     */
    private fun drawClosedLoop(expect: Int = 1) {
        compose.onNodeWithTag(AssistantLiveSelectionTags.GESTURE).performTouchInput {
            down(Offset(60f, 60f))
            moveTo(Offset(260f, 60f))
            moveTo(Offset(260f, 260f))
            moveTo(Offset(60f, 260f))
            up()
        }
        compose.waitUntil(5_000) { results!!.values.size >= expect }
    }

    private var results: Results? = null

    @Test
    fun releasingAValidLoopAutomaticallyProducesAnAttachment() {
        val (results, previews) = render()

        drawClosedLoop()

        val result = results.values.singleOrNull()
        assertNotNull("松手即确认，不应还需要按确认按钮", result)
        assertTrue("裁出的附件必须有像素尺寸", result!!.widthPx > 0 && result.heightPx > 0)
        assertTrue("预览必须写入本进程存储", previews.thumbnail("live-selection") != null)
        val decoded = BitmapFactory.decodeByteArray(result.pngBytes(), 0, result.byteSize)
        assertNotNull("产物必须是可解码的 PNG", decoded)
    }

    @Test
    fun aStraightLineIsTreatedAsAnAccidentalTouchAndProducesNothing() {
        val (results, _) = render()

        compose.onNodeWithTag(AssistantLiveSelectionTags.GESTURE).performTouchInput {
            down(Offset(40f, 100f))
            moveTo(Offset(140f, 100f))
            moveTo(Offset(240f, 100f))
            up()
        }
        compose.waitForIdle()

        assertTrue("共线路径没有面积，不得产出附件", results.values.isEmpty())
        // 误触不弹错误：拒绝回调只用于「槽位已满」这类需要解释的情况。
        assertEquals(0, results.rejections.get())
    }

    @Test
    fun aTapWithoutDragProducesNothing() {
        val (results, _) = render()

        compose.onNodeWithTag(AssistantLiveSelectionTags.GESTURE).performTouchInput {
            down(Offset(100f, 120f))
            up()
        }
        compose.waitForIdle()

        assertTrue(results.values.isEmpty())
    }

    /**
     * 排除区判定是纯函数：跨模块的控件矩形与画布同一坐标系，起点落在其中就放弃本次圈选。
     *
     * 这里用纯函数而不是再来一次真实手势：连续多次「被放弃的拖动」会在同一个测试 JVM 里
     * 留下未完成的协程调度（Robolectric 的 idle 检测随后永远等不到静止），
     * 而判定逻辑本身与 Compose 渲染无关。
     */
    @Test
    fun aPointInsideAnExcludedRectangleIsRejected() {
        val control = Rect(left = 0f, top = 700f, right = 1200f, bottom = 800f)

        assertTrue(isExcludedPoint(Offset(200f, 720f), listOf(control)))
        assertTrue("边界点也属于控件", isExcludedPoint(Offset(0f, 700f), listOf(control)))
        assertFalse(isExcludedPoint(Offset(200f, 600f), listOf(control)))
        assertFalse(isExcludedPoint(Offset(200f, 600f), emptyList()))
        assertTrue("非有限坐标不能当作圈选起点", isExcludedPoint(Offset(Float.NaN, 600f), emptyList()))
    }

    @Test
    fun aFullAttachmentSlotRejectsInsteadOfSilentlySwallowingTheStroke() {
        val (results, _) = render(enabled = false)

        compose.onNodeWithTag(AssistantLiveSelectionTags.GESTURE).performTouchInput {
            down(Offset(60f, 60f))
            moveTo(Offset(260f, 60f))
            moveTo(Offset(260f, 260f))
            up()
        }
        compose.waitUntil(5_000) { results.rejections.get() == 1 }

        assertTrue(results.values.isEmpty())
        assertEquals("达到上限时必须给出提示，而不是静默吞掉", 1, results.rejections.get())
    }

    @Test
    fun drawingTwiceProducesTwoSeparateAttachments() {
        val (results, _) = render()

        drawClosedLoop(expect = 1)
        drawClosedLoop(expect = 2)

        assertEquals("一次成功不清空定格态，可以连续圈选", 2, results.values.size)
        assertEquals(
            "两个选区必须各有独立的本地键，否则缩略图会互相覆盖",
            2,
            results.values.map { it.localKey }.distinct().size,
        )
    }
}
