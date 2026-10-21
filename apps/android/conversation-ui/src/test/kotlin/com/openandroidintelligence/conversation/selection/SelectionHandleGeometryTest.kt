package com.openandroidintelligence.conversation.selection

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 选区控制柄的几何行为（C11 假可交互角柄的修复）。
 *
 * 命中测试与拖动都是纯函数：角优先于边、1:1 跟手、夹在画布内、
 * 不穿过最小尺寸。面板里的四个边界滑块仍作为无障碍替代路径。
 */
class SelectionHandleGeometryTest {

    private val bounds = SelectionBounds(left = 100f, top = 100f, right = 300f, bottom = 200f)

    @Test
    fun cornersWinOverEdgesWhenBothAreWithinHitRadius() {
        assertEquals(SelectionHandle.TopLeft, selectionHandleAt(Offset(102f, 98f), bounds, 24f))
        assertEquals(SelectionHandle.TopRight, selectionHandleAt(Offset(298f, 102f), bounds, 24f))
        assertEquals(SelectionHandle.BottomLeft, selectionHandleAt(Offset(98f, 202f), bounds, 24f))
        assertEquals(SelectionHandle.BottomRight, selectionHandleAt(Offset(302f, 198f), bounds, 24f))
    }

    @Test
    fun edgeHandlesAreHitAlongTheWholeEdge() {
        assertEquals(SelectionHandle.Left, selectionHandleAt(Offset(100f, 150f), bounds, 24f))
        assertEquals(SelectionHandle.Right, selectionHandleAt(Offset(300f, 150f), bounds, 24f))
        assertEquals(SelectionHandle.Top, selectionHandleAt(Offset(200f, 100f), bounds, 24f))
        assertEquals(SelectionHandle.Bottom, selectionHandleAt(Offset(200f, 200f), bounds, 24f))
        // 边上的非中点位置同样可拖（离两个角都超过命中半径）。
        assertEquals(SelectionHandle.Left, selectionHandleAt(Offset(104f, 150f), bounds, 24f))
    }

    @Test
    fun missesOutsideHitRadiusAndRejectsInvalidInput() {
        assertNull(selectionHandleAt(Offset(500f, 500f), bounds, 24f))
        assertNull(selectionHandleAt(Offset(Float.NaN, 100f), bounds, 24f))
        assertNull(selectionHandleAt(Offset(100f, 100f), bounds, 0f))
        assertNull(selectionHandleAt(Offset(100f, 100f), bounds, Float.NaN))
    }

    @Test
    fun draggingLeftEdgeKeepsOppositeEdgeFixed() {
        val resized = resizeSelection(
            bounds = bounds,
            handle = SelectionHandle.Left,
            deltaX = 40f,
            deltaY = 0f,
            canvasWidth = 400f,
            canvasHeight = 400f,
            minimumSize = 24f,
        )
        assertEquals(140f, resized.left, 0.01f)
        assertEquals(300f, resized.right, 0.01f)
        assertEquals(100f, resized.top, 0.01f)
        assertEquals(200f, resized.bottom, 0.01f)
    }

    @Test
    fun draggingPastTheMinimumStopsAtTheMinimum() {
        val clamped = resizeSelection(
            bounds = bounds,
            handle = SelectionHandle.Left,
            deltaX = 500f,
            deltaY = 0f,
            canvasWidth = 400f,
            canvasHeight = 400f,
            minimumSize = 24f,
        )
        assertEquals(276f, clamped.left, 0.01f)
        assertEquals(300f, clamped.right, 0.01f)
        assertEquals(24f, clamped.width, 0.01f)
    }

    @Test
    fun draggingNeverLeavesTheCanvas() {
        val resized = resizeSelection(
            bounds = bounds,
            handle = SelectionHandle.BottomRight,
            deltaX = 500f,
            deltaY = 500f,
            canvasWidth = 400f,
            canvasHeight = 400f,
            minimumSize = 24f,
        )
        assertEquals(400f, resized.right, 0.01f)
        assertEquals(400f, resized.bottom, 0.01f)
        assertEquals(100f, resized.left, 0.01f)
        assertEquals(100f, resized.top, 0.01f)

        val negative = resizeSelection(
            bounds = bounds,
            handle = SelectionHandle.TopLeft,
            deltaX = -500f,
            deltaY = -500f,
            canvasWidth = 400f,
            canvasHeight = 400f,
            minimumSize = 24f,
        )
        assertEquals(0f, negative.left, 0.01f)
        assertEquals(0f, negative.top, 0.01f)
        assertEquals(300f, negative.right, 0.01f)
        assertEquals(200f, negative.bottom, 0.01f)
    }

    @Test
    fun invalidDeltasAreIgnoredInsteadOfProducingNaN() {
        val resized = resizeSelection(
            bounds = bounds,
            handle = SelectionHandle.Right,
            deltaX = Float.NaN,
            deltaY = Float.POSITIVE_INFINITY,
            canvasWidth = 400f,
            canvasHeight = 400f,
            minimumSize = 24f,
        )
        assertEquals(100f, resized.left, 0.01f)
        assertEquals(300f, resized.right, 0.01f)
        assertEquals(100f, resized.top, 0.01f)
        assertEquals(200f, resized.bottom, 0.01f)
    }
}
