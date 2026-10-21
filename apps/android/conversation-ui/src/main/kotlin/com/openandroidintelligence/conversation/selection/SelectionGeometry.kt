package com.openandroidintelligence.conversation.selection

import androidx.compose.ui.geometry.Offset

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

/**
 * 选区可拖动的控制柄：4 个角 + 4 条边。
 *
 * 角与边都能 1:1 跟手改形状；控件面板里的四个边界滑块仍然保留，作为
 * TalkBack / 无法精确拖动时的无障碍替代路径。
 */
internal enum class SelectionHandle(
    val affectsLeft: Boolean = false,
    val affectsRight: Boolean = false,
    val affectsTop: Boolean = false,
    val affectsBottom: Boolean = false,
) {
    TopLeft(affectsLeft = true, affectsTop = true),
    Top(affectsTop = true),
    TopRight(affectsRight = true, affectsTop = true),
    Right(affectsRight = true),
    BottomRight(affectsRight = true, affectsBottom = true),
    Bottom(affectsBottom = true),
    BottomLeft(affectsLeft = true, affectsBottom = true),
    Left(affectsLeft = true),
}

/**
 * 命中测试：返回 [position] 命中的控制柄，角优先于边。
 *
 * [hitRadius] 是屏幕像素（调用方按 24dp 换算）；位置或半径非法时返回 null，
 * 让手势落到「新建选区」分支而不是误拖一个不存在的柄。
 */
internal fun selectionHandleAt(
    position: Offset,
    bounds: SelectionBounds,
    hitRadius: Float,
): SelectionHandle? {
    if (!position.x.isFinite() || !position.y.isFinite()) return null
    if (!hitRadius.isFinite() || hitRadius <= 0f) return null
    val corners = arrayOf(
        SelectionHandle.TopLeft to Offset(bounds.left, bounds.top),
        SelectionHandle.TopRight to Offset(bounds.right, bounds.top),
        SelectionHandle.BottomRight to Offset(bounds.right, bounds.bottom),
        SelectionHandle.BottomLeft to Offset(bounds.left, bounds.bottom),
    )
    corners.forEach { (handle, corner) ->
        if ((position - corner).getDistance() <= hitRadius) return handle
    }
    val edges = arrayOf(
        SelectionHandle.Top to (Offset(bounds.left, bounds.top) to Offset(bounds.right, bounds.top)),
        SelectionHandle.Right to (Offset(bounds.right, bounds.top) to Offset(bounds.right, bounds.bottom)),
        SelectionHandle.Bottom to (Offset(bounds.left, bounds.bottom) to Offset(bounds.right, bounds.bottom)),
        SelectionHandle.Left to (Offset(bounds.left, bounds.top) to Offset(bounds.left, bounds.bottom)),
    )
    edges.forEach { (handle, segment) ->
        if (distanceToSegment(position, segment.first, segment.second) <= hitRadius) return handle
    }
    return null
}

/**
 * 拖动控制柄后的新选区：1:1 跟手、夹在画布内、并保证宽高不小于 [minimumSize]。
 *
 * 对边保持不动；当拖动会让选区小于下限时，被拖动的那条边停在临界位置，
 * 因此用户不会把选区「拖穿」成 0 宽高再看见它突然跳回来。
 */
internal fun resizeSelection(
    bounds: SelectionBounds,
    handle: SelectionHandle,
    deltaX: Float,
    deltaY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    minimumSize: Float,
): SelectionBounds {
    val safeWidth = nonNegativeFinite(canvasWidth)
    val safeHeight = nonNegativeFinite(canvasHeight)
    val minimum = nonNegativeFinite(minimumSize).coerceAtMost(minOf(safeWidth, safeHeight))
    var left = finiteOrZero(bounds.left)
    var top = finiteOrZero(bounds.top)
    var right = finiteOrZero(bounds.right)
    var bottom = finiteOrZero(bounds.bottom)
    val dx = if (deltaX.isFinite()) deltaX else 0f
    val dy = if (deltaY.isFinite()) deltaY else 0f
    if (handle.affectsLeft) {
        left = (left + dx).coerceIn(0f, (right - minimum).coerceAtLeast(0f))
    }
    if (handle.affectsRight) {
        right = (right + dx).coerceIn((left + minimum).coerceAtMost(safeWidth), safeWidth)
    }
    if (handle.affectsTop) {
        top = (top + dy).coerceIn(0f, (bottom - minimum).coerceAtLeast(0f))
    }
    if (handle.affectsBottom) {
        bottom = (bottom + dy).coerceIn((top + minimum).coerceAtMost(safeHeight), safeHeight)
    }
    return SelectionBounds(left = left, top = top, right = right, bottom = bottom)
        .clamp(canvasWidth = safeWidth, canvasHeight = safeHeight)
}

/** 点到线段的最短距离；退化线段按点距处理。 */
private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) return (point - start).getDistance()
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    val projection = Offset(start.x + t * dx, start.y + t * dy)
    return (point - projection).getDistance()
}


/**
 * 自由圈选最多保留的采样点。
 *
 * 裁剪端（`cropPngSelection` / 助理裁剪）只接受 3..4096 个点，超出会整体判为无效；
 * 长手势如果不截断，用户画完却提交不了，还会丢掉整帧截图。
 */
internal const val MAX_FREEHAND_SAMPLES = 4096
