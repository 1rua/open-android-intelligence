package com.openandroidintelligence.conversation.selection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions
import kotlin.math.roundToInt

/**
 * A rectangle selector over the screenshot supplied by the Assist session.
 *
 * A screenshot is deliberately an input, rather than something this UI
 * creates. When [screenshot] is absent the overlay renders an explicit
 * unavailable state and never invokes [onCropConfirmed]. A drag only creates
 * a draft rectangle; the caller is notified after the user presses the
 * confirm button.
 */
@Composable
fun ScreenSelectionOverlay(
    onCropConfirmed: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    screenshot: ImageBitmap? = null,
) {
    val hasScreenshot = screenshot != null && screenshot.width > 0 && screenshot.height > 0
    var canvasSize by remember(screenshot) { mutableStateOf(IntSize.Zero) }
    var selection by remember(screenshot) { mutableStateOf<SelectionBounds?>(null) }
    var dragStart by remember(screenshot) { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember(screenshot) { mutableStateOf<Offset?>(null) }

    val gestureSelection = if (dragStart != null && dragCurrent != null) {
        normalizeSelection(dragStart!!, dragCurrent!!)
    } else {
        selection
    }
    val minimumSize = Dimensions.SpaceLarge.value
    val validSelection = selection?.isValid(
        canvasWidth = canvasSize.width.toFloat(),
        canvasHeight = canvasSize.height.toFloat(),
        minimumSize = minimumSize,
    ) == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "屏幕圈选" },
    ) {
        if (!hasScreenshot) {
            UnavailableSelectionState(
                onCancel = onCancel,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val primaryColor = MaterialTheme.colorScheme.primary
            val tertiaryColor = MaterialTheme.colorScheme.tertiary
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(screenshot) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragCurrent = offset
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragCurrent = (dragCurrent ?: change.position) + dragAmount
                            },
                            onDragEnd = {
                                val start = dragStart
                                val current = dragCurrent
                                if (start != null && current != null) {
                                    selection = normalizeSelection(start, current)
                                }
                                dragStart = null
                                dragCurrent = null
                            },
                            onDragCancel = {
                                dragStart = null
                                dragCurrent = null
                            },
                        )
                    },
            ) {
                val image = screenshot ?: return@Canvas
                val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
                drawImage(image, dstSize = destination)
                drawRect(color = Color.Black.copy(alpha = 0.52f))

                gestureSelection?.let { bounds ->
                    // Draw the chosen portion at full brightness so a user
                    // can verify the crop before it enters the draft.
                    clipRect(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                    ) {
                        drawImage(image, dstSize = destination)
                    }
                    drawRect(
                        color = primaryColor,
                        topLeft = Offset(bounds.left, bounds.top),
                        size = Size(bounds.width, bounds.height),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val handleRadius = 5.dp.toPx()
                    drawCircle(tertiaryColor, handleRadius, Offset(bounds.left, bounds.top))
                    drawCircle(tertiaryColor, handleRadius, Offset(bounds.right, bounds.top))
                    drawCircle(tertiaryColor, handleRadius, Offset(bounds.left, bounds.bottom))
                    drawCircle(tertiaryColor, handleRadius, Offset(bounds.right, bounds.bottom))
                }
            }

            SelectionControls(
                selection = selection,
                canvasSize = canvasSize,
                minimumSize = minimumSize,
                validSelection = validSelection,
                onSelectionChange = { updated ->
                    selection = updated.clamp(
                        canvasWidth = canvasSize.width.toFloat(),
                        canvasHeight = canvasSize.height.toFloat(),
                    )
                },
                onConfirm = {
                    selection?.takeIf {
                        it.isValid(
                            canvasWidth = canvasSize.width.toFloat(),
                            canvasHeight = canvasSize.height.toFloat(),
                            minimumSize = minimumSize,
                        )
                    }?.let { bounds ->
                        onCropConfirmed(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    }
                },
                onCancel = onCancel,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun UnavailableSelectionState(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(Dimensions.SpaceLarge),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(32.dp),
            )
            Text("当前内容不允许共享", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "系统没有提供可用的 Assist 截图，无法进行屏幕圈选。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun SelectionControls(
    selection: SelectionBounds?,
    canvasSize: IntSize,
    minimumSize: Float,
    validSelection: Boolean,
    onSelectionChange: (SelectionBounds) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(Dimensions.SpaceMedium),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(Dimensions.SpaceMedium)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.CropFree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = if (selection == null) "拖动选择要分享的区域" else "调整选区边界后确认",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (selection == null) {
                    "松手后仍可调整，确认后才会加入当前对话。"
                } else {
                    "可用下方四个边界滑块精确调整矩形。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selection != null && canvasSize != IntSize.Zero) {
                BoundarySlider(
                    label = "左边界",
                    value = selection.left,
                    maximum = canvasSize.width.toFloat(),
                    onValueChange = { value ->
                        onSelectionChange(selection.copy(left = value.coerceAtMost(selection.right)))
                    },
                )
                BoundarySlider(
                    label = "右边界",
                    value = selection.right,
                    maximum = canvasSize.width.toFloat(),
                    onValueChange = { value ->
                        onSelectionChange(selection.copy(right = value.coerceAtLeast(selection.left)))
                    },
                )
                BoundarySlider(
                    label = "上边界",
                    value = selection.top,
                    maximum = canvasSize.height.toFloat(),
                    onValueChange = { value ->
                        onSelectionChange(selection.copy(top = value.coerceAtMost(selection.bottom)))
                    },
                )
                BoundarySlider(
                    label = "下边界",
                    value = selection.bottom,
                    maximum = canvasSize.height.toFloat(),
                    onValueChange = { value ->
                        onSelectionChange(selection.copy(bottom = value.coerceAtLeast(selection.top)))
                    },
                )
            }

            if (selection != null && !validSelection) {
                Text(
                    text = "选区至少需要 ${minimumSize.roundToInt()}dp，并且必须位于截图范围内。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onConfirm,
                    enabled = validSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("确认选区")
                }
            }
        }
    }
}

@Composable
private fun BoundarySlider(
    label: String,
    value: Float,
    maximum: Float,
    onValueChange: (Float) -> Unit,
) {
    val safeMaximum = maximum.coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(0f, safeMaximum),
            onValueChange = onValueChange,
            valueRange = 0f..safeMaximum,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimensions.MinimumTouchTarget)
                .semantics {
                    contentDescription = "$label，可调节"
                    stateDescription = "${value.roundToInt()}像素"
                },
        )
    }
}
