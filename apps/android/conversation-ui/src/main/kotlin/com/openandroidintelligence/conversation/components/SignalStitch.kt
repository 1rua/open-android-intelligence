package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.openandroidintelligence.conversation.theme.Dimensions

/** 装饰性的品牌缝线，不独立承载连接或授权状态。 */
@Composable
fun SignalStitch(failed: Boolean = false, modifier: Modifier = Modifier) {
    val line = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.width(Dimensions.SpaceCompact).height(Dimensions.MinimumTouchTarget)) {
        val stroke = Dimensions.StrokeStitch.toPx()
        val center = size.width / 2f
        drawLine(line, Offset(center, stroke), Offset(center, (size.height - stroke * 4).coerceAtLeast(stroke)), strokeWidth = stroke)
        drawCircle(dot, radius = stroke, center = Offset(center, size.height - stroke))
    }
}
