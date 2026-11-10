package com.openandroidintelligence.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 整个助理唯一的顶部拖动横条。
 *
 * 结构上从输入栏里搬了出来，由 [AssistantSessionRoot] 在会话树最顶部渲染一次，
 * 紧凑输入与展开会话共用同一个实例：
 * - 常显：与输入框焦点、草稿、键盘都无关（需求 5.1）；
 * - 上滑：跟手位移（向下做边缘阻尼），超过阈值或足够快的上甩触发「进入主 App」；
 *   未达阈值回弹，回调交回宿主处理交接成败；
 * - 减少动态：不做弹簧回弹，直接落位。
 *
 * 手势只在这个 48dp 高的命中区内生效，不覆盖消息列表、输入框或圈选层。
 * 位移只作用于 `graphicsLayer`，不参与布局测量：横条不会把下方内容推来推去。
 *
 * @param onRequestHandoff 达到阈值后请求宿主把当前会话交接给主 App。
 * @param onDragProgress 拖拽进度（0..1，向上为正），宿主据此让整个浮层跟手上移；
 *   松手回弹时会回调 0，因此宿主不需要自己维护状态。
 */
@Composable
internal fun AssistantDragHandle(
    onRequestHandoff: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 当前形态的朗读摘要，例如「紧凑输入」或「展开会话」。 */
    stateDescription: String = "紧凑输入",
    onDragProgress: (Float) -> Unit = {},
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 速度阈值按 dp 换算：不同密度下「甩一下」的手感必须一致。
    val flingThresholdPx = with(density) { HANDOFF_FLING_VELOCITY_DP.toPx() }
    val progressDistancePx = with(density) { HANDOFF_DISTANCE_DP.toPx() }
    val scope = rememberCoroutineScope()
    val currentOnRequestHandoff by rememberUpdatedState(onRequestHandoff)
    val currentOnDragProgress by rememberUpdatedState(onDragProgress)

    // 跟手位移用普通状态同步更新，松手回弹才交给 Animatable：
    // 在拖拽回调里 launch + snapTo 会让位移落后于手指一两帧。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 手势累计位移（不做视觉跟手时也要有）：减少动态下横条不动，但阈值判定仍要看
    // 用户实际拖了多远，否则「上滑进入主 App」在减少动态下会彻底失效。
    var dragTravel by remember { mutableFloatStateOf(0f) }
    val rebound = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.98f else 1f,
        animationSpec = MotionSpecs.press(reduceMotion),
        label = "assistant-handle-press",
    )

    fun reportProgress(offsetPx: Float) {
        currentOnDragProgress(progressFraction(offsetPx, progressDistancePx))
    }

    fun settleToRest() {
        // 减少动态：不做弹簧回弹，直接落位。横条本来就没有位移，这里只复位状态，
        // 同步完成、不经过动画帧，用户看不到任何移动。
        if (reduceMotion) {
            dragOffset = 0f
            dragTravel = 0f
            settling = false
            currentOnDragProgress(0f)
            return
        }
        settling = true
        scope.launch {
            // 从当前位移开始回弹，避免松手瞬间跳回原点。
            rebound.snapTo(dragOffset)
            dragOffset = 0f
            // 回弹过程逐帧上报进度：父级整层位移与透明度都读这个值，只在结束时归零
            // 会让面板停在拖拽终点、横条单独弹回去，最后整层突然跳回原位。
            rebound.animateTo(0f, MotionSpecs.MomentumSpring) {
                currentOnDragProgress(progressFraction(rebound.value, progressDistancePx))
            }
            dragTravel = 0f
            settling = false
            currentOnDragProgress(0f)
        }
    }

    val translationY = if (settling) rebound.value else dragOffset

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.MinimumTouchTarget)
            .testTag(AssistantCompactBarTags.HANDLE)
            .semantics {
                contentDescription = "向上滑动在 App 中打开"
                this.stateDescription = stateDescription
            }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                this.translationY = translationY
            }
            .draggable(
                orientation = Orientation.Vertical,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    // 新手势打断回弹：从回弹中的位置继续跟手，不跳回旧端点。
                    if (settling) {
                        dragOffset = rebound.value
                        settling = false
                        scope.launch { rebound.stop() }
                    }
                    // 减少动态：横条不产生任何位移（不跟手），但仍累计手势行程，
                    // 松手时阈值判定照常生效。
                    dragOffset = dragOffsetStep(dragOffset, delta, reduceMotion, screenHeightPx)
                    dragTravel += dragTravelStep(delta, screenHeightPx)
                    // 减少动态下 dragOffset 恒为 0，进度自然也是 0（父级不做整层位移）。
                    reportProgress(dragOffset)
                },
                onDragStarted = { pressed = true },
                onDragStopped = { velocity ->
                    pressed = false
                    val reached = enabled &&
                        shouldHandoffDrag(dragTravel, velocity, screenHeightPx, flingThresholdPx)
                    settleToRest()
                    if (reached) currentOnRequestHandoff()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(top = Dimensions.SpaceSmall, bottom = Dimensions.SpaceTiny)
                .width(AssistantHandleWidth)
                .height(AssistantHandleHeight)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AssistantHandleAlpha),
                ),
        )
    }
}

/** 减少动态时横条不产生任何视觉位移；手势行程仍由 [dragTravelStep] 累计。 */
internal fun dragOffsetStep(
    current: Float,
    delta: Float,
    reduceMotion: Boolean,
    screenHeightPx: Float,
): Float {
    if (reduceMotion) return 0f
    // 只允许向上拖：向下的分量做阻尼，越拉越沉。
    val step = if (delta < 0f) delta else MotionSpecs.rubberband(delta, screenHeightPx)
    return current + step
}

/**
 * 位移 → 整层进度（0..1，向上为正）。
 *
 * 拖拽与回弹必须用**同一套换算**：回弹只是把位移逐帧送回 0，父级整层位移读的正是
 * 这个进度，两处各写一份会让回弹不再带动整层。
 */
internal fun progressFraction(offsetPx: Float, progressDistancePx: Float): Float =
    if (progressDistancePx <= 0f) 0f else (-offsetPx / progressDistancePx).coerceIn(0f, 1f)

/**
 * 手势行程：无论是否跟手都要累计，阈值判定看的是用户实际拖了多远。
 *
 * 减少动态下横条不动，但「上滑进入主 App」必须照常可用——否则这项能力在减少动态
 * 用户那里会彻底消失。向下分量同样做阻尼，避免「向下甩」被当成上滑。
 */
internal fun dragTravelStep(delta: Float, screenHeightPx: Float): Float =
    if (delta < 0f) delta else MotionSpecs.rubberband(delta, screenHeightPx)

/**
 * 上滑是否达到「进入主 App」的阈值。
 *
 * 两个条件任一成立即可：
 * - 向上位移达到屏幕高度的 18%（约 115dp / 640dp）；
 * - 上甩速度超过 [flingVelocityPxPerSec]（默认 1200dp/s 换算成的像素值）。
 *
 * 向下拖动、屏幕高度未知或非有限值一律不触发；不会把「向下甩」误判成上滑。
 */
internal fun shouldHandoffDrag(
    verticalDragPx: Float,
    velocityPxPerSec: Float,
    screenHeightPx: Float,
    flingVelocityPxPerSec: Float = HANDOFF_FLING_VELOCITY_DP.value * 3f,
): Boolean {
    if (!verticalDragPx.isFinite() || !velocityPxPerSec.isFinite()) return false
    if (screenHeightPx <= 0f || !screenHeightPx.isFinite()) return false
    if (verticalDragPx > 0f || velocityPxPerSec > 0f) return false
    val distanceThreshold = screenHeightPx * HANDOFF_DISTANCE_FRACTION
    return abs(verticalDragPx) >= distanceThreshold || abs(velocityPxPerSec) >= flingVelocityPxPerSec
}

/** 达到交接判定所需的向上位移比例（屏幕高度 × 0.18）。 */
internal const val HANDOFF_DISTANCE_FRACTION = 0.18f

/** 上甩速度阈值（dp/s）；比较前由调用方按屏幕密度换算成像素。 */
internal val HANDOFF_FLING_VELOCITY_DP = 1200.dp

/** 宿主让整层跟手上移时使用的参考距离（dp）：拖满即整层上移这么多。 */
internal val HANDOFF_DISTANCE_DP = 96.dp

private val AssistantHandleWidth = 32.dp
private val AssistantHandleHeight = 4.dp
private const val AssistantHandleAlpha = 0.4f
