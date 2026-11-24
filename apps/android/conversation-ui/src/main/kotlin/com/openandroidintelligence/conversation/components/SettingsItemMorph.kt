package com.openandroidintelligence.conversation.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 列表项按压态进入形变/波纹前的滚动消抖阈值，与系统点击判定超时对齐（约 100ms），
 * 避免手指落屏准备纵向滑动时立即触发胶囊裁剪与图形层重绘。
 */
internal val DefaultSettingsItemPressDelayMillis: Long
    get() = ViewConfiguration.getTapTimeout().toLong()

@Stable
internal class SettingsItemPressFeedback(
    val pressed: State<Boolean>,
    val visualInteractionSource: MutableInteractionSource?,
)

private val IdleSettingsPressState: State<Boolean> = mutableStateOf(false)
private val EmptySettingsPressFeedback = SettingsItemPressFeedback(
    pressed = IdleSettingsPressState,
    visualInteractionSource = null,
)

/**
 * 桥接原始点击交互源与视觉反馈交互源，为长按/滑动起手增加 [pressDelayMillis] 消抖：
 * - 若按压在消抖窗口内被取消（例如进入纵向滚动），全程不触发形变与波纹；
 * - 若在消抖窗口内迅速松手完成点击，则补发一次完整波纹与短促胶囊脉冲反馈；
 * - 若按住超过阈值，波纹与胶囊形变同拍激活，松手后立即复原。
 */
@Composable
internal fun rememberSettingsItemPressFeedback(
    clickSource: MutableInteractionSource?,
    enabled: Boolean,
    pressDelayMillis: Long = DefaultSettingsItemPressDelayMillis,
): SettingsItemPressFeedback {
    if (clickSource == null) {
        return EmptySettingsPressFeedback
    }
    val pressedState = remember(clickSource) { mutableStateOf(false) }
    val visualSource = remember(clickSource) { MutableInteractionSource() }

    LaunchedEffect(clickSource, visualSource, enabled, pressDelayMillis) {
        val scope = this
        var pendingPress: PressInteraction.Press? = null
        var emittedPress: PressInteraction.Press? = null
        var delayJob: Job? = null
        var releasePulseJob: Job? = null

        try {
            clickSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        delayJob?.cancel()
                        delayJob = null
                        releasePulseJob?.cancel()
                        releasePulseJob = null
                        pendingPress = null
                        emittedPress?.let { prev ->
                            visualSource.tryEmit(PressInteraction.Cancel(prev))
                            emittedPress = null
                        }
                        if (!enabled) {
                            pressedState.value = false
                            return@collect
                        }
                        if (pressDelayMillis <= 0L) {
                            emittedPress = interaction
                            pressedState.value = true
                            visualSource.emit(interaction)
                        } else {
                            pendingPress = interaction
                            delayJob = scope.launch {
                                delay(pressDelayMillis)
                                if (pendingPress === interaction) {
                                    pendingPress = null
                                    emittedPress = interaction
                                    pressedState.value = true
                                    visualSource.emit(interaction)
                                }
                            }
                        }
                    }

                    is PressInteraction.Release -> {
                        val press = interaction.press
                        when {
                            pendingPress === press -> {
                                delayJob?.cancel()
                                delayJob = null
                                pendingPress = null
                                if (enabled) {
                                    // 消抖窗口内完成短按点击：补发同拍波纹与短促胶囊脉冲，再平滑收回。
                                    visualSource.tryEmit(press)
                                    visualSource.tryEmit(interaction)
                                    pressedState.value = true
                                    releasePulseJob?.cancel()
                                    releasePulseJob = scope.launch {
                                        delay(MotionSpecs.Instant.toLong())
                                        if (pendingPress == null && emittedPress == null) {
                                            pressedState.value = false
                                        }
                                    }
                                }
                            }

                            emittedPress === press -> {
                                emittedPress = null
                                pressedState.value = false
                                visualSource.tryEmit(interaction)
                            }
                        }
                    }

                    is PressInteraction.Cancel -> {
                        val press = interaction.press
                        when {
                            pendingPress === press -> {
                                delayJob?.cancel()
                                delayJob = null
                                pendingPress = null
                                pressedState.value = false
                            }

                            emittedPress === press -> {
                                emittedPress = null
                                pressedState.value = false
                                visualSource.tryEmit(interaction)
                            }
                        }
                    }

                    else -> {
                        visualSource.tryEmit(interaction)
                    }
                }
            }
        } finally {
            delayJob?.cancel()
            releasePulseJob?.cancel()
            pendingPress = null
            emittedPress?.let { prev ->
                visualSource.tryEmit(PressInteraction.Cancel(prev))
            }
            emittedPress = null
            pressedState.value = false
        }
    }

    return remember(pressedState, visualSource) {
        SettingsItemPressFeedback(
            pressed = pressedState,
            visualInteractionSource = visualSource,
        )
    }
}

/**
 * 设置条目的背景和波纹共用同一动画边界，点击修饰符必须接在返回值之后。
 *
 * 静止态零图形层、零 `clip` 裁剪、零 `Shape` 分配；仅在按压/选中或收敛动画期间
 * 挂载单个图形层并在绘制阶段惰性读取进度，避免引发组合阶段重组。
 */
@Composable
internal fun settingsItemMorphModifier(
    highlighted: Boolean,
    pressed: Boolean,
    scaleOnPress: Boolean,
    visualInteractionSource: MutableInteractionSource?,
): Modifier {
    val reduced = LocalMotionPolicy.current.isReduced
    val targetProgress = if (highlighted) 1f else 0f
    val targetScale = if (scaleOnPress && pressed && !reduced) MotionSpecs.PressScaleDefault else 1f
    val progress = remember { Animatable(targetProgress) }
    val scale = remember { Animatable(targetScale) }

    LaunchedEffect(targetProgress, reduced) {
        if (reduced) {
            progress.snapTo(targetProgress)
        } else {
            progress.animateTo(targetProgress, MotionSpecs.morph(false))
        }
    }
    LaunchedEffect(targetScale, reduced) {
        if (reduced) {
            scale.snapTo(targetScale)
        } else {
            scale.animateTo(targetScale, MotionSpecs.press(false))
        }
    }

    val progressState = progress.asState()
    val scaleState = scale.asState()
    val needsLayerState = remember(progressState, scaleState) {
        derivedStateOf { progressState.value > 0f || scaleState.value != 1f }
    }
    val corners = MaterialTheme.shapes.medium
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val indication = LocalIndication.current

    return Modifier
        .then(
            SettingsMorphLayerElement(
                resting = corners,
                highlighted = highlighted,
                progressState = progressState,
                scaleState = scaleState,
                needsLayerProvider = { needsLayerState.value },
            ),
        )
        .drawBehind {
            val fraction = progressState.value.coerceIn(0f, 1f)
            if (fraction > 0f) {
                drawRect(lerp(Color.Transparent, secondaryContainer, fraction))
            }
        }
        .then(
            if (visualInteractionSource != null) {
                Modifier.indication(visualInteractionSource, indication)
            } else {
                Modifier
            },
        )
}

private class SettingsMorphLayerElement(
    private val resting: CornerBasedShape,
    private val highlighted: Boolean,
    private val progressState: State<Float>,
    private val scaleState: State<Float>,
    private val needsLayerProvider: () -> Boolean,
) : ModifierNodeElement<SettingsMorphLayerNode>() {
    override fun create(): SettingsMorphLayerNode = SettingsMorphLayerNode(
        resting = resting,
        highlighted = highlighted,
        progressState = progressState,
        scaleState = scaleState,
        needsLayerProvider = needsLayerProvider,
    )

    override fun update(node: SettingsMorphLayerNode) {
        val changed = node.resting != resting ||
            node.highlighted != highlighted ||
            node.progressState != progressState ||
            node.scaleState != scaleState ||
            node.needsLayerProvider != needsLayerProvider
        node.resting = resting
        node.highlighted = highlighted
        node.progressState = progressState
        node.scaleState = scaleState
        node.needsLayerProvider = needsLayerProvider
        if (changed) {
            node.invalidatePlacement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "settingsItemMorphLayer"
        properties["highlighted"] = highlighted
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SettingsMorphLayerElement) return false
        return resting == other.resting &&
            highlighted == other.highlighted &&
            progressState == other.progressState &&
            scaleState == other.scaleState &&
            needsLayerProvider == other.needsLayerProvider
    }

    override fun hashCode(): Int {
        var result = resting.hashCode()
        result = 31 * result + highlighted.hashCode()
        result = 31 * result + progressState.hashCode()
        result = 31 * result + scaleState.hashCode()
        result = 31 * result + needsLayerProvider.hashCode()
        return result
    }
}

private class SettingsMorphLayerNode(
    var resting: CornerBasedShape,
    var highlighted: Boolean,
    var progressState: State<Float>,
    var scaleState: State<Float>,
    var needsLayerProvider: () -> Boolean,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (!highlighted && !needsLayerProvider()) {
                placeable.place(0, 0)
            } else {
                placeable.placeWithLayer(0, 0) {
                    val fraction = progressState.value.coerceIn(0f, 1f)
                    val scale = scaleState.value
                    scaleX = scale
                    scaleY = scale
                    shape = SettingsMorphShape(resting, fraction)
                    clip = fraction > 0f || highlighted
                }
            }
        }
    }
}

/** 在绘制尺寸确定后解析主题圆角并向短边一半插值，确保胶囊端点与布局方向一致。 */
private data class SettingsMorphShape(
    val resting: CornerBasedShape,
    val fraction: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val capsuleRadius = (size.minDimension / 2f).coerceAtLeast(0f)
        fun interpolate(corner: CornerSize): Float {
            val start = corner.toPx(size, density).coerceAtLeast(0f)
            return (start + (capsuleRadius - start) * fraction).coerceAtLeast(0f)
        }
        val topStart = interpolate(resting.topStart)
        val topEnd = interpolate(resting.topEnd)
        val bottomEnd = interpolate(resting.bottomEnd)
        val bottomStart = interpolate(resting.bottomStart)
        val topLeft = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        val topRight = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = CornerRadius(topLeft, topLeft),
                topRight = CornerRadius(topRight, topRight),
                bottomRight = CornerRadius(bottomRight, bottomRight),
                bottomLeft = CornerRadius(bottomLeft, bottomLeft),
            ),
        )
    }
}
