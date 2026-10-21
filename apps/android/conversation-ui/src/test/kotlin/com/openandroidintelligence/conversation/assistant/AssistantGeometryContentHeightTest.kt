package com.openandroidintelligence.conversation.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 展开高度的「内容驱动」语义（C6 / C17）。
 *
 * 展开高度不再由「球径 × 3」拍脑袋决定：调用方传入真实内容期望高度，
 * 结果夹在 `[最小高度, 可用高度 60%]` 之间。不传内容高度时沿用既有
 * metrics 的 preferred 值，保持原有公式与测试语义。
 */
class AssistantGeometryContentHeightTest {

    private val metrics = AssistantGeometryMetrics(
        ballSizePx = 56f,
        dockMarginPx = 8f,
        expandedMarginPx = 16f,
        maxExpandedWidthPx = 840f,
        minimumExpandedHeightPx = 240f,
        preferredExpandedHeightPx = 100f,
    )

    @Test
    fun contentHeightDrivesExpandedHeight() {
        val geom = assistantGeometry(
            expanded = true,
            availableWidth = 400f,
            availableHeight = 1000f,
            dockOnLeft = false,
            metrics = metrics,
            contentHeightPx = 300f,
        )
        assertEquals(300f, geom.height, 0.01f)
    }

    @Test
    fun contentHeightIsRaisedToTheMinimum() {
        val geom = assistantGeometry(
            expanded = true,
            availableWidth = 400f,
            availableHeight = 1000f,
            dockOnLeft = false,
            metrics = metrics,
            contentHeightPx = 50f,
        )
        assertEquals(240f, geom.height, 0.01f)
    }

    @Test
    fun contentHeightIsCappedAtSixtyPercentOfAvailableHeight() {
        val geom = assistantGeometry(
            expanded = true,
            availableWidth = 400f,
            availableHeight = 1000f,
            dockOnLeft = false,
            metrics = metrics,
            contentHeightPx = 900f,
        )
        assertEquals(600f, geom.height, 0.01f)
    }

    @Test
    fun omittedContentHeightKeepsMetricFallback() {
        val geom = assistantGeometry(
            expanded = true,
            availableWidth = 400f,
            availableHeight = 1000f,
            dockOnLeft = false,
            metrics = metrics,
        )
        assertEquals(240f, geom.height, 0.01f)
    }

    @Test
    fun invalidContentHeightFallsBackInsteadOfProducingNaN() {
        val geom = assistantGeometry(
            expanded = true,
            availableWidth = 400f,
            availableHeight = 1000f,
            dockOnLeft = false,
            metrics = metrics,
            contentHeightPx = Float.NaN,
        )
        assertEquals(240f, geom.height, 0.01f)
    }
}
