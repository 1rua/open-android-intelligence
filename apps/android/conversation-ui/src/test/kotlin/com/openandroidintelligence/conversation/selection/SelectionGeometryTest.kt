package com.openandroidintelligence.conversation.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGeometryTest {
    @Test
    fun normalizeSelectionHandlesDragInAnyDirection() {
        val normal = normalizeSelection(10f, 20f, 100f, 150f)
        assertEquals(10f, normal.left, 0.01f)
        assertEquals(20f, normal.top, 0.01f)
        assertEquals(100f, normal.right, 0.01f)
        assertEquals(150f, normal.bottom, 0.01f)

        val reversed = normalizeSelection(100f, 150f, 10f, 20f)
        assertEquals(10f, reversed.left, 0.01f)
        assertEquals(20f, reversed.top, 0.01f)
        assertEquals(100f, reversed.right, 0.01f)
        assertEquals(150f, reversed.bottom, 0.01f)
    }

    @Test
    fun clampKeepsCoordinatesWithinCanvas() {
        val overflow = SelectionBounds(-20f, -30f, 500f, 600f)
        val clamped = overflow.clamp(canvasWidth = 400f, canvasHeight = 400f)

        assertEquals(0f, clamped.left, 0.01f)
        assertEquals(0f, clamped.top, 0.01f)
        assertEquals(400f, clamped.right, 0.01f)
        assertEquals(400f, clamped.bottom, 0.01f)
    }

    @Test
    fun validationRejectsTooSmallOrOutOfBoundsSelections() {
        val valid = SelectionBounds(10f, 10f, 100f, 100f)
        assertTrue(valid.isValid(canvasWidth = 400f, canvasHeight = 400f, minimumSize = 48f))

        val tooSmall = SelectionBounds(10f, 10f, 20f, 20f)
        assertFalse(tooSmall.isValid(canvasWidth = 400f, canvasHeight = 400f, minimumSize = 48f))

        val outOfBounds = SelectionBounds(10f, 10f, 450f, 100f)
        assertFalse(outOfBounds.isValid(canvasWidth = 400f, canvasHeight = 400f, minimumSize = 48f))
    }

    @Test
    fun normalizeSelectionRejectsNonFiniteInputs() {
        val withNaN = normalizeSelection(Float.NaN, 10f, 100f, 100f)
        assertFalse(withNaN.isValid(canvasWidth = 400f, canvasHeight = 400f, minimumSize = 10f))

        val withInf = normalizeSelection(10f, Float.POSITIVE_INFINITY, 100f, 100f)
        assertFalse(withInf.isValid(canvasWidth = 400f, canvasHeight = 400f, minimumSize = 10f))
    }

    @Test
    fun validationRejectsNegativeOrZeroDimensions() {
        val bounds = SelectionBounds(10f, 10f, 100f, 100f)
        assertFalse(bounds.isValid(canvasWidth = -100f, canvasHeight = 400f, minimumSize = 10f))
        assertFalse(bounds.isValid(canvasWidth = 400f, canvasHeight = 0f, minimumSize = 10f))
        assertFalse(bounds.isValid(canvasWidth = 400f, canvasHeight = 400f, minimumSize = -5f))
    }

    @Test
    fun clampHandlesInvertedAndNegativeCanvas() {
        val bounds = SelectionBounds(100f, 100f, 50f, 50f)
        val clamped = bounds.clamp(canvasWidth = 200f, canvasHeight = 200f)
        assertEquals(50f, clamped.left, 0.01f)
        assertEquals(50f, clamped.top, 0.01f)
        assertEquals(100f, clamped.right, 0.01f)
        assertEquals(100f, clamped.bottom, 0.01f)

        val clampedNegativeCanvas = bounds.clamp(canvasWidth = -50f, canvasHeight = -50f)
        assertEquals(0f, clampedNegativeCanvas.left, 0.01f)
        assertEquals(0f, clampedNegativeCanvas.right, 0.01f)
    }
}

