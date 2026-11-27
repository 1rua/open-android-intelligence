package com.openandroidintelligence.conversation.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.conditionalGraphicsLayer
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
    val clipActive: State<Boolean>,
    val visualInteractionSource: MutableInteractionSource?,
)

private val IdleSettingsPressState: State<Boolean> = mutableStateOf(false)
private val EmptySettingsPressFeedback = SettingsItemPressFeedback(
    pressed = IdleSettingsPressState,
    clipActive = IdleSettingsPressState,
    visualInteractionSource = null,
)

/**
 * 桥接原始点击交互源与视觉反馈交互源，为长按/滑动起手增加 [pressDelayMillis] 消抖：
 * - 若按压在消抖窗口内被取消（例如进入纵向滚动），全程不触发形变与波纹；
 * - 若在消抖窗口内迅速松手完成点击，先触发按下交互并在脉冲期结束后配对释放，避免同帧抵消波纹状态层；
 * - Focus / Hover 与水波纹尾段期间保留圆形/静息圆角裁剪边界，彻底收敛后再自动卸层。
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
    val currentEnabled by rememberUpdatedState(enabled)
    val pressedState = remember(clickSource) { mutableStateOf(false) }
    val clipActiveState = remember(clickSource) { mutableStateOf(false) }
    val visualSource = remember(clickSource) { MutableInteractionSource() }

    LaunchedEffect(clickSource, visualSource, pressDelayMillis) {
        val scope = this
        val pendingPresses = LinkedHashMap<PressInteraction.Press, Job>()
        val activePresses = LinkedHashSet<PressInteraction.Press>()
        var pulsePress: PressInteraction.Press? = null
        var releasePulseJob: Job? = null
        val activeFocuses = LinkedHashSet<FocusInteraction.Focus>()
        val activeHovers = LinkedHashSet<HoverInteraction.Enter>()
        var rippleTailActive = false
        var rippleTailJob: Job? = null

        fun syncPressed() {
            pressedState.value = activePresses.isNotEmpty() || pulsePress != null
        }

        fun syncClipActive() {
            clipActiveState.value = activeFocuses.isNotEmpty() || activeHovers.isNotEmpty() || rippleTailActive
        }

        fun startRippleTail() {
            rippleTailActive = true
            syncClipActive()
            rippleTailJob?.cancel()
            rippleTailJob = scope.launch {
                // 使用 MotionSpecs.Enter（400ms）覆盖 M3 Ripple 扩散与淡出尾段，避免手写时长。
                delay(MotionSpecs.Enter.toLong())
                rippleTailActive = false
                syncClipActive()
            }
        }

        fun finishPulse(releaseInteraction: PressInteraction.Release? = null) {
            releasePulseJob?.cancel()
            releasePulseJob = null
            val currentPulse = pulsePress ?: return
            pulsePress = null
            visualSource.tryEmit(releaseInteraction ?: PressInteraction.Release(currentPulse))
            startRippleTail()
            syncPressed()
        }

        try {
            clickSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        if (!currentEnabled) {
                            return@collect
                        }
                        if (pulsePress != null) {
                            finishPulse()
                        }
                        if (pressDelayMillis <= 0L) {
                            activePresses.add(interaction)
                            pressedState.value = true
                            visualSource.emit(interaction)
                        } else {
                            // 进入消抖等待前显式校准按下态，防止短按脉冲期内二次按下卡住高亮与缩放。
                            pressedState.value = activePresses.isNotEmpty()
                            val delayJob = scope.launch {
                                delay(pressDelayMillis)
                                if (pendingPresses.remove(interaction) != null) {
                                    activePresses.add(interaction)
                                    pressedState.value = true
                                    visualSource.emit(interaction)
                                }
                            }
                            pendingPresses[interaction] = delayJob
                        }
                    }

                    is PressInteraction.Release -> {
                        val press = interaction.press
                        val pendingJob = pendingPresses.remove(press)
                        when {
                            pendingJob != null -> {
                                pendingJob.cancel()
                                if (pulsePress != null) {
                                    finishPulse()
                                }
                                pulsePress = press
                                pressedState.value = true
                                // 先发射 Press，延后到脉冲结束后再发 Release，防止同帧抵消 M3 StateLayer 并保留尾段裁剪。
                                visualSource.emit(press)
                                releasePulseJob = scope.launch {
                                    delay(MotionSpecs.Instant.toLong())
                                    if (pulsePress === press) {
                                        finishPulse(interaction)
                                    }
                                }
                            }

                            activePresses.remove(press) -> {
                                syncPressed()
                                startRippleTail()
                                visualSource.tryEmit(interaction)
                            }
                        }
                    }

                    is PressInteraction.Cancel -> {
                        val press = interaction.press
                        val pendingJob = pendingPresses.remove(press)
                        when {
                            pendingJob != null -> {
                                pendingJob.cancel()
                                syncPressed()
                            }

                            activePresses.remove(press) -> {
                                syncPressed()
                                startRippleTail()
                                visualSource.tryEmit(interaction)
                            }
                        }
                    }

                    is FocusInteraction.Focus -> {
                        activeFocuses.add(interaction)
                        syncClipActive()
                        visualSource.tryEmit(interaction)
                    }

                    is FocusInteraction.Unfocus -> {
                        activeFocuses.remove(interaction.focus)
                        syncClipActive()
                        visualSource.tryEmit(interaction)
                    }

                    is HoverInteraction.Enter -> {
                        activeHovers.add(interaction)
                        syncClipActive()
                        visualSource.tryEmit(interaction)
                    }

                    is HoverInteraction.Exit -> {
                        activeHovers.remove(interaction.enter)
                        syncClipActive()
                        visualSource.tryEmit(interaction)
                    }

                    else -> {
                        visualSource.tryEmit(interaction)
                    }
                }
            }
        } finally {
            pendingPresses.values.forEach { it.cancel() }
            pendingPresses.clear()
            releasePulseJob?.cancel()
            rippleTailJob?.cancel()
            pulsePress?.let { visualSource.tryEmit(PressInteraction.Cancel(it)) }
            pulsePress = null
            activePresses.forEach { visualSource.tryEmit(PressInteraction.Cancel(it)) }
            activePresses.clear()
            activeFocuses.clear()
            activeHovers.clear()
            rippleTailActive = false
            pressedState.value = false
            clipActiveState.value = false
        }
    }

    return remember(pressedState, clipActiveState, visualSource) {
        SettingsItemPressFeedback(
            pressed = pressedState,
            clipActive = clipActiveState,
            visualInteractionSource = visualSource,
        )
    }
}

/**
 * 设置条目的背景和波纹共用同一动画边界，点击修饰符必须接在返回值之后。
 *
 * 静止态零图形层、零 `clip` 裁剪、零 `Shape` 分配；仅在按压/选中、波纹尾段或 Focus/Hover 期间
 * 挂载单个图形层并在绘制阶段惰性读取进度，避免引发组合阶段重组。
 */
@Composable
internal fun settingsItemMorphModifier(
    highlighted: Boolean,
    pressed: Boolean,
    scaleOnPress: Boolean,
    visualInteractionSource: MutableInteractionSource?,
    clipActive: State<Boolean> = IdleSettingsPressState,
): Modifier {
    val reduced = LocalMotionPolicy.current.isReduced
    val targetProgress = if (highlighted) 1f else 0f
    val targetScale = if (scaleOnPress && pressed && !reduced) MotionSpecs.PressScaleDefault else 1f
    val progress = remember {
        Animatable(
            initialValue = targetProgress,
            visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
        )
    }
    val scale = remember {
        Animatable(
            initialValue = targetScale,
            visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
        )
    }

    LaunchedEffect(targetProgress, reduced) {
        if (progress.value == targetProgress && !progress.isRunning) return@LaunchedEffect
        if (reduced) {
            progress.snapTo(targetProgress)
        } else {
            progress.animateTo(
                targetValue = targetProgress,
                animationSpec = MotionSpecs.morph(
                    reduced = false,
                    visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
                ),
            )
        }
    }
    LaunchedEffect(targetScale, reduced) {
        if (scale.value == targetScale && !scale.isRunning) return@LaunchedEffect
        if (reduced) {
            scale.snapTo(targetScale)
        } else {
            scale.animateTo(
                targetValue = targetScale,
                animationSpec = MotionSpecs.press(
                    reduced = false,
                    visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
                ),
            )
        }
    }

    val highlightedState = rememberUpdatedState(highlighted)
    val progressState = progress.asState()
    val scaleState = scale.asState()
    val needsLayerState = remember(progressState, scaleState, clipActive) {
        derivedStateOf {
            highlightedState.value ||
                clipActive.value ||
                progressState.value > 0f ||
                scaleState.value != 1f
        }
    }
    val corners = MaterialTheme.shapes.medium
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val indication = LocalIndication.current
    val layerBlock = remember(corners, progressState, scaleState, clipActive) {
        { scope: GraphicsLayerScope ->
            val fraction = progressState.value.coerceIn(0f, 1f)
            val currentScale = scaleState.value
            scope.scaleX = currentScale
            scope.scaleY = currentScale
            scope.shape = if (fraction <= 0f) corners else SettingsMorphShape(corners, fraction)
            scope.clip = fraction > 0f || highlightedState.value || clipActive.value
        }
    }

    return Modifier
        .conditionalGraphicsLayer(
            needsLayer = needsLayerState,
            layerBlock = layerBlock,
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
