package com.openandroidintelligence.conversation.selection

/** Coordinates are expressed in the measured overlay pixels. */
internal data class SelectionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isValid(canvasWidth: Float, canvasHeight: Float, minimumSize: Float): Boolean {
        if (!canvasWidth.isFinite() || !canvasHeight.isFinite() ||
            !minimumSize.isFinite() || canvasWidth < 0f || canvasHeight < 0f ||
            minimumSize < 0f
        ) {
            return false
        }
        return left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            width.isFinite() && height.isFinite() &&
            width > 0f && height > 0f &&
            left >= 0f && top >= 0f && right <= canvasWidth && bottom <= canvasHeight &&
            width >= minimumSize && height >= minimumSize
    }

    /** Clamps both corners and restores the left/top to right/bottom ordering. */
    fun clamp(canvasWidth: Float, canvasHeight: Float): SelectionBounds {
        val safeWidth = nonNegativeFinite(canvasWidth)
        val safeHeight = nonNegativeFinite(canvasHeight)
        val bounded = SelectionBounds(
            left = finiteOrZero(left).coerceIn(0f, safeWidth),
            top = finiteOrZero(top).coerceIn(0f, safeHeight),
            right = finiteOrZero(right).coerceIn(0f, safeWidth),
            bottom = finiteOrZero(bottom).coerceIn(0f, safeHeight),
        )
        return SelectionBounds(
            left = minOf(bounded.left, bounded.right),
            top = minOf(bounded.top, bounded.bottom),
            right = maxOf(bounded.left, bounded.right),
            bottom = maxOf(bounded.top, bounded.bottom),
        )
    }
}

/**
 * Normalizes a drag regardless of its direction. Non-finite input remains
 * invalid so it cannot accidentally become a confirmable selection.
 */
internal fun normalizeSelection(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
): SelectionBounds {
    if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) {
        return invalidSelection()
    }
    return SelectionBounds(
        left = minOf(startX, endX),
        top = minOf(startY, endY),
        right = maxOf(startX, endX),
        bottom = maxOf(startY, endY),
    )
}

internal fun normalizeSelection(
    start: androidx.compose.ui.geometry.Offset,
    end: androidx.compose.ui.geometry.Offset,
): SelectionBounds = normalizeSelection(start.x, start.y, end.x, end.y)

private fun invalidSelection() = SelectionBounds(
    left = Float.NaN,
    top = Float.NaN,
    right = Float.NaN,
    bottom = Float.NaN,
)

private fun nonNegativeFinite(value: Float): Float =
    if (value.isFinite() && value >= 0f) value else 0f

private fun finiteOrZero(value: Float): Float =
    if (value.isFinite()) value else 0f
