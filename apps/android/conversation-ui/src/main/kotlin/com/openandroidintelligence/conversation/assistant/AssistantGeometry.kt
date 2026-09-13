package com.openandroidintelligence.conversation.assistant

import com.openandroidintelligence.conversation.motion.MotionSpecs

/**
 * Pixel measurements used by [assistantGeometry]. Keeping these values as
 * primitives makes the layout calculation independent from Compose state and
 * straightforward to exercise in JVM tests.
 */
internal data class AssistantGeometryMetrics(
    val ballSizePx: Float,
    val dockMarginPx: Float,
    val expandedMarginPx: Float,
    val maxExpandedWidthPx: Float,
    val minimumExpandedHeightPx: Float,
    val preferredExpandedHeightPx: Float,
)

internal data class AssistantGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Calculates the resting bounds for the docked ball or expanded assistant.
 * Every input is treated as untrusted so a transient invalid measurement
 * cannot produce NaN coordinates or a rectangle outside the available area.
 */
internal fun assistantGeometry(
    expanded: Boolean,
    availableWidth: Float,
    availableHeight: Float,
    dockOnLeft: Boolean,
    metrics: AssistantGeometryMetrics,
): AssistantGeometry {
    val safeWidth = nonNegativeFinite(availableWidth)
    val safeHeight = nonNegativeFinite(availableHeight)
    val ball = nonNegativeFinite(metrics.ballSizePx)
    val dockMargin = nonNegativeFinite(metrics.dockMarginPx)
    val expandedMargin = nonNegativeFinite(metrics.expandedMarginPx)
    val maxExpandedWidth = positiveFiniteOr(metrics.maxExpandedWidthPx, safeWidth)
    val minimumExpandedHeight = nonNegativeFinite(metrics.minimumExpandedHeightPx)
    val preferredExpandedHeight = nonNegativeFinite(
        metrics.preferredExpandedHeightPx,
    ).coerceAtLeast(minimumExpandedHeight)

    if (!expanded) {
        val width = ball.coerceAtMost(safeWidth)
        val height = ball.coerceAtMost(safeHeight)
        val maxX = (safeWidth - width - dockMargin).coerceAtLeast(dockMargin)
        val maxY = (safeHeight - height - expandedMargin).coerceAtLeast(dockMargin)
        val x = if (dockOnLeft) dockMargin else maxX
        return AssistantGeometry(
            x = x.coerceIn(0f, (safeWidth - width).coerceAtLeast(0f)),
            y = maxY.coerceIn(0f, (safeHeight - height).coerceAtLeast(0f)),
            width = width,
            height = height,
        )
    }

    val preferredWidth = safeWidth - expandedMargin * 2f
    val width = preferredWidth
        .coerceAtLeast(ball)
        .coerceAtMost(maxExpandedWidth)
        .coerceAtMost(safeWidth)
    val minimumHeight = minimumExpandedHeight.coerceAtMost(safeHeight)
    val preferredHeight = preferredExpandedHeight.coerceAtMost(safeHeight * 0.6f)
    val height = preferredHeight
        .coerceAtLeast(minimumHeight)
        .coerceAtMost(safeHeight)
    val x = ((safeWidth - width) / 2f).coerceAtLeast(0f)
    val y = (safeHeight - height - expandedMargin).coerceAtLeast(0f)
    return AssistantGeometry(
        x = x.coerceIn(0f, (safeWidth - width).coerceAtLeast(0f)),
        y = y.coerceIn(0f, (safeHeight - height).coerceAtLeast(0f)),
        width = width,
        height = height,
    )
}

/** Applies progressively stronger resistance outside the dock bounds. */
internal fun rubberbanded(
    value: Float,
    minimum: Float,
    maximum: Float,
    dimension: Float,
): Float {
    if (!value.isFinite()) return nonNegativeFinite(minimum)
    val lower = finite(minimum)
    val upper = finite(maximum)
    if (upper <= lower) return lower
    val safeDimension = positiveFiniteOr(dimension, 1f)
    return when {
        value < lower -> lower + MotionSpecs.rubberband(value - lower, safeDimension)
        value > upper -> upper + MotionSpecs.rubberband(value - upper, safeDimension)
        else -> value
    }
}

/** Projects a measured fling velocity while keeping invalid samples harmless. */
internal fun projectedPosition(position: Float, velocityPxPerSecond: Float): Float {
    if (!position.isFinite()) return 0f
    if (!velocityPxPerSecond.isFinite()) return position
    return (position + MotionSpecs.project(velocityPxPerSecond)).takeIf(Float::isFinite) ?: position
}

internal fun shouldDockOnLeft(
    position: Float,
    velocityPxPerSecond: Float,
    availableWidth: Float,
): Boolean {
    val safeWidth = nonNegativeFinite(availableWidth)
    return projectedPosition(position, velocityPxPerSecond) < safeWidth / 2f
}

internal fun settledPosition(
    position: Float,
    velocityPxPerSecond: Float,
    minimum: Float,
    maximum: Float,
): Float {
    val lower = finite(minimum)
    val upper = finite(maximum).coerceAtLeast(lower)
    return projectedPosition(position, velocityPxPerSecond).coerceIn(lower, upper)
}

private fun nonNegativeFinite(value: Float): Float =
    if (value.isFinite() && value >= 0f) value else 0f

private fun finite(value: Float): Float =
    if (value.isFinite()) value else 0f

private fun positiveFiniteOr(value: Float, fallback: Float): Float =
    if (value.isFinite() && value > 0f) value else nonNegativeFinite(fallback)
