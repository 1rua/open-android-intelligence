package com.openandroidintelligence.conversation.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantGeometryTest {
    private val metrics = AssistantGeometryMetrics(
        ballSizePx = 56f,
        dockMarginPx = 8f,
        expandedMarginPx = 16f,
        maxExpandedWidthPx = 840f,
        minimumExpandedHeightPx = 200f,
        preferredExpandedHeightPx = 400f,
    )

    @Test
    fun dockedBallStaysWithinBoundsOnRight() {
        val geom = assistantGeometry(
            expanded = false,
            availableWidth = 400f,
            availableHeight = 800f,
            dockOnLeft = false,
            metrics = metrics,
        )
        assertEquals(56f, geom.width, 0.01f)
        assertEquals(56f, geom.height, 0.01f)
        assertEquals(400f - 56f - 8f, geom.x, 0.01f)
        assertTrue(geom.y <= 800f - 56f)
    }

    @Test
    fun dockedBallStaysWithinBoundsOnLeft() {
        val geom = assistantGeometry(
            expanded = false,
            availableWidth = 400f,
            availableHeight = 800f,
            dockOnLeft = true,
            metrics = metrics,
        )
        assertEquals(56f, geom.width, 0.01f)
        assertEquals(56f, geom.height, 0.01f)
        assertEquals(8f, geom.x, 0.01f)
    }

    @Test
    fun expandedGeometryRespectsMaxReadingWidth() {
        val geom = assistantGeometry(
            expanded = true,
            availableWidth = 1200f,
            availableHeight = 900f,
            dockOnLeft = false,
            metrics = metrics,
        )
        assertEquals(840f, geom.width, 0.01f)
        assertEquals((1200f - 840f) / 2f, geom.x, 0.01f)
        assertTrue(geom.height >= 200f)
    }

    @Test
    fun dockingDecisionSelectsCorrectSide() {
        assertTrue(shouldDockOnLeft(position = 100f, velocityPxPerSecond = -50f, availableWidth = 400f))
        assertFalse(shouldDockOnLeft(position = 300f, velocityPxPerSecond = 50f, availableWidth = 400f))
    }

    @Test
    fun rubberbandingAppliesResistanceOutsideBounds() {
        val inside = rubberbanded(value = 50f, minimum = 0f, maximum = 100f, dimension = 200f)
        assertEquals(50f, inside, 0.01f)

        val below = rubberbanded(value = -50f, minimum = 0f, maximum = 100f, dimension = 200f)
        assertTrue(below < 0f && below > -50f)

        val above = rubberbanded(value = 150f, minimum = 0f, maximum = 100f, dimension = 200f)
        assertTrue(above > 100f && above < 150f)
    }

    @Test
    fun geometryNeverProducesNaNWithInvalidOrZeroAvailableArea() {
        val zeroGeom = assistantGeometry(
            expanded = true,
            availableWidth = 0f,
            availableHeight = 0f,
            dockOnLeft = false,
            metrics = metrics,
        )
        assertFalse("x 不能为 NaN", zeroGeom.x.isNaN())
        assertFalse("y 不能为 NaN", zeroGeom.y.isNaN())
        assertFalse("width 不能为 NaN", zeroGeom.width.isNaN())
        assertFalse("height 不能为 NaN", zeroGeom.height.isNaN())

        val nanGeom = assistantGeometry(
            expanded = false,
            availableWidth = Float.NaN,
            availableHeight = Float.NEGATIVE_INFINITY,
            dockOnLeft = true,
            metrics = metrics,
        )
        assertFalse("x 不能为 NaN", nanGeom.x.isNaN())
        assertFalse("y 不能为 NaN", nanGeom.y.isNaN())
    }

    @Test
    fun rubberbandHandlesNaNAndInvertedBoundsSafely() {
        val nanResult = rubberbanded(Float.NaN, minimum = 0f, maximum = 100f, dimension = 200f)
        assertEquals(0f, nanResult, 0.01f)

        val invertedResult = rubberbanded(50f, minimum = 100f, maximum = 0f, dimension = 200f)
        assertEquals(100f, invertedResult, 0.01f)
    }
}

