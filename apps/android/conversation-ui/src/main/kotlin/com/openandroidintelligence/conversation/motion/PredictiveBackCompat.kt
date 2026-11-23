package com.openandroidintelligence.conversation.motion

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 预测性返回手势实时状态快照。
 *
 * @param progress 归一化返回进度 `[0f, 1f]`；减少动态或未开启时保持 `0f`。
 * @param swipeEdge 系统侧滑起始边缘（对应 `BackEventCompat.EDGE_LEFT` / `EDGE_RIGHT`；非边缘触发时为 `-1`）。
 * @param inProgress 手势是否正处于拖拽追踪中。
 */
@Immutable
data class PredictiveBackState(
    val progress: Float = 0f,
    val swipeEdge: Int = -1,
    val inProgress: Boolean = false,
)

/** Android 14（API 34）及以上平台原生支持预测性返回手势进度流。 */
internal fun supportsNativePredictiveBack(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** 根据侧滑边缘与布局方向计算横向位移方向（左边缘右拉向右移，右边缘左拉向左移）。 */
internal fun resolvePredictiveBackDirection(
    swipeEdge: Int,
    layoutDirection: LayoutDirection,
): Float = when (swipeEdge) {
    BackEventCompat.EDGE_LEFT -> 1f
    BackEventCompat.EDGE_RIGHT -> -1f
    else -> if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
}

/**
 * 注册跨版本兼容的预测性返回处理器，并返回包含进度与起滑边缘的 [State]。
 *
 * 建议容器层在绘制阶段通过 `predictiveBackSlideTransform(stateProvider = { state.value }, ...)`
 * 惰性读取状态，避免手势拖拽/回弹期间在组合期每帧触发整棵子树重组。
 */
@Composable
fun rememberPredictiveBackState(
    enabled: Boolean,
    onBack: () -> Unit,
    sdkInt: Int = Build.VERSION.SDK_INT,
): State<PredictiveBackState> {
    val state = remember { mutableStateOf(PredictiveBackState()) }
    val progress = remember { Animatable(0f) }
    PredictiveBackCompatEffect(
        enabled = enabled,
        progress = progress,
        onStateChange = { state.value = it },
        onProgressChange = null,
        onBack = onBack,
        sdkInt = sdkInt,
    )
    return state
}

/**
 * 注册跨版本兼容的预测性返回处理器，并返回当前归一化进度 `[0f, 1f]`。
 *
 * - API 34+：通过 [PredictiveBackHandler] 实时追踪系统跟手进度；若用户在半途取消返回，
 *   会通过绑定组合生命周期的回弹协程以临界阻尼 [MotionSpecs.morph] 平滑回弹至 `0f`，
 *   并在新一次手势开始或宿主销毁时立即取消旧回弹作业。
 * - API 31-33：自动降级为传统 [BackHandler]，进度保持 `0f`。
 * - 减少动态开启时：不追踪手势位移，进度恒为 `0f`，同时完整保留返回触发回调。
 */
@Composable
fun rememberPredictiveBackProgress(
    enabled: Boolean,
    onBack: () -> Unit,
    sdkInt: Int = Build.VERSION.SDK_INT,
): State<Float> {
    val progress = remember { Animatable(0f) }
    PredictiveBackCompatEffect(
        enabled = enabled,
        progress = progress,
        onStateChange = null,
        onProgressChange = null,
        onBack = onBack,
        sdkInt = sdkInt,
    )
    return progress.asState()
}

/**
 * 跨版本兼容的预测性返回处理组件，适合需要通过回调观察 [PredictiveBackState.swipeEdge] 或
 * [PredictiveBackState.inProgress] 的容器层。
 */
@Composable
fun PredictiveBackCompatHandler(
    enabled: Boolean,
    onProgressChange: ((PredictiveBackState) -> Unit)? = null,
    onBack: () -> Unit,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val progress = remember { Animatable(0f) }
    PredictiveBackCompatEffect(
        enabled = enabled,
        progress = progress,
        onStateChange = null,
        onProgressChange = onProgressChange,
        onBack = onBack,
        sdkInt = sdkInt,
    )
}

@Composable
private fun PredictiveBackCompatEffect(
    enabled: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    onStateChange: ((PredictiveBackState) -> Unit)?,
    onProgressChange: ((PredictiveBackState) -> Unit)?,
    onBack: () -> Unit,
    sdkInt: Int,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val reboundScope = rememberCoroutineScope()
    val gestureToken = remember { intArrayOf(0) }
    val reboundJob = remember { arrayOfNulls<Job>(1) }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnStateChange by rememberUpdatedState(onStateChange)
    val currentReduced by rememberUpdatedState(reduced)

    fun emitState(next: PredictiveBackState) {
        currentOnStateChange?.invoke(next)
        currentOnProgressChange?.invoke(next)
    }

    LaunchedEffect(enabled, reduced) {
        if (!enabled || reduced) {
            gestureToken[0]++
            reboundJob[0]?.cancel()
            reboundJob[0] = null
            if (progress.value != 0f) {
                progress.snapTo(0f)
            }
            emitState(PredictiveBackState())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            gestureToken[0]++
            reboundJob[0]?.cancel()
            reboundJob[0] = null
        }
    }

    if (supportsNativePredictiveBack(sdkInt)) {
        PredictiveBackHandler(enabled = enabled) { events ->
            val myToken = ++gestureToken[0]
            reboundJob[0]?.cancel()
            reboundJob[0] = null
            var lastEdge = -1
            try {
                events.collect { event ->
                    if (gestureToken[0] != myToken) return@collect
                    lastEdge = event.swipeEdge
                    val next = if (currentReduced) 0f else event.progress.coerceIn(0f, 1f)
                    if (gestureToken[0] != myToken) return@collect
                    progress.snapTo(next)
                    if (gestureToken[0] == myToken) {
                        emitState(
                            PredictiveBackState(
                                progress = next,
                                swipeEdge = lastEdge,
                                inProgress = !currentReduced,
                            ),
                        )
                    }
                }
                if (gestureToken[0] == myToken) {
                    if (progress.value != 0f) {
                        progress.snapTo(0f)
                    }
                    emitState(
                        PredictiveBackState(
                            progress = 0f,
                            swipeEdge = lastEdge,
                            inProgress = false,
                        ),
                    )
                    currentOnBack()
                }
            } catch (cancelled: CancellationException) {
                // 使用绑定组合生命周期的回弹协程 + 代际令牌，避免旧手势在新手势或退场后覆写状态。
                if (gestureToken[0] == myToken) {
                    reboundJob[0] = reboundScope.launch {
                        if (gestureToken[0] != myToken) return@launch
                        if (currentReduced || progress.value <= 0f) {
                            if (progress.value != 0f) {
                                progress.snapTo(0f)
                            }
                            if (gestureToken[0] == myToken) {
                                emitState(
                                    PredictiveBackState(
                                        progress = 0f,
                                        swipeEdge = lastEdge,
                                        inProgress = false,
                                    ),
                                )
                            }
                        } else {
                            progress.animateTo(
                                targetValue = 0f,
                                animationSpec = MotionSpecs.morph(false),
                            ) {
                                if (gestureToken[0] == myToken) {
                                    emitState(
                                        PredictiveBackState(
                                            progress = value.coerceIn(0f, 1f),
                                            swipeEdge = lastEdge,
                                            inProgress = false,
                                        ),
                                    )
                                }
                            }
                            if (gestureToken[0] == myToken) {
                                if (progress.value != 0f) {
                                    progress.snapTo(0f)
                                }
                                emitState(
                                    PredictiveBackState(
                                        progress = 0f,
                                        swipeEdge = lastEdge,
                                        inProgress = false,
                                    ),
                                )
                            }
                        }
                    }
                }
                throw cancelled
            }
        }
    } else {
        BackHandler(enabled = enabled) {
            emitState(PredictiveBackState())
            currentOnBack()
        }
    }
}

/**
 * 将预测性返回手势状态映射为仅作用于绘制层的横向滑移与微缩反馈。
 *
 * 在 `graphicsLayer` 绘制阶段通过 [stateProvider] 惰性读取进度，全程不触发组合重构与布局重测；
 * 未显式指定 [direction] 时，依据 `swipeEdge` 与当前 `layoutDirection` 自动跟随手指滑动方向。
 * 当 [reduced] 为 `true` 时直接返回原 [Modifier]。
 */
fun Modifier.predictiveBackSlideTransform(
    stateProvider: () -> PredictiveBackState,
    reduced: Boolean,
    direction: Float? = null,
    maxScaleReduction: Float = 0.04f,
): Modifier {
    if (reduced) return this
    val scaleReduction = maxScaleReduction.coerceIn(0f, 0.9f)
    return this then PredictiveBackSlideElement(
        stateProvider = stateProvider,
        direction = direction,
        scaleReduction = scaleReduction,
    )
}

private class PredictiveBackSlideElement(
    private val stateProvider: () -> PredictiveBackState,
    private val direction: Float?,
    private val scaleReduction: Float,
) : ModifierNodeElement<PredictiveBackSlideNode>() {
    override fun create(): PredictiveBackSlideNode = PredictiveBackSlideNode(
        stateProvider = stateProvider,
        direction = direction,
        scaleReduction = scaleReduction,
    )

    override fun update(node: PredictiveBackSlideNode) {
        val changed = node.stateProvider != stateProvider ||
            node.direction != direction ||
            node.scaleReduction != scaleReduction
        node.stateProvider = stateProvider
        node.direction = direction
        node.scaleReduction = scaleReduction
        if (changed) {
            node.invalidatePlacement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "predictiveBackSlideTransform"
        properties["direction"] = direction
        properties["scaleReduction"] = scaleReduction
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PredictiveBackSlideElement) return false
        return stateProvider == other.stateProvider &&
            direction == other.direction &&
            scaleReduction == other.scaleReduction
    }

    override fun hashCode(): Int {
        var result = stateProvider.hashCode()
        result = 31 * result + (direction?.hashCode() ?: 0)
        result = 31 * result + scaleReduction.hashCode()
        return result
    }
}

private class PredictiveBackSlideNode(
    var stateProvider: () -> PredictiveBackState,
    var direction: Float?,
    var scaleReduction: Float,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val currentLayoutDirection = layoutDirection
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(x = 0, y = 0) {
                val state = stateProvider()
                val clamped = state.progress.coerceIn(0f, 1f)
                if (clamped <= 0f) {
                    translationX = 0f
                    scaleX = 1f
                    scaleY = 1f
                } else {
                    val resolvedDirection = direction
                        ?: resolvePredictiveBackDirection(state.swipeEdge, currentLayoutDirection)
                    val targetScale = (1f - clamped * scaleReduction).coerceIn(0.1f, 1f)
                    translationX = size.width * clamped * resolvedDirection
                    scaleX = targetScale
                    scaleY = targetScale
                }
            }
        }
    }
}

/**
 * 基于惰性进度读取器的便捷重载，默认依据当前 `layoutDirection` 决定滑移方向。
 */
@JvmName("predictiveBackProgressSlideTransform")
fun Modifier.predictiveBackSlideTransform(
    progressProvider: () -> Float,
    reduced: Boolean,
    direction: Float? = null,
    maxScaleReduction: Float = 0.04f,
): Modifier = predictiveBackSlideTransform(
    stateProvider = { PredictiveBackState(progress = progressProvider()) },
    reduced = reduced,
    direction = direction,
    maxScaleReduction = maxScaleReduction,
)
