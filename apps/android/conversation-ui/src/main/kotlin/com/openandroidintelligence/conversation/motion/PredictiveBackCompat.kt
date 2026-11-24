package com.openandroidintelligence.conversation.motion

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
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
            val state = stateProvider()
            val clamped = state.progress.coerceIn(0f, 1f)
            if (clamped <= 0f) {
                placeable.place(0, 0)
            } else {
                placeable.placeWithLayer(x = 0, y = 0) {
                    val latestState = stateProvider()
                    val latestClamped = latestState.progress.coerceIn(0f, 1f)
                    if (latestClamped <= 0f) {
                        translationX = 0f
                        scaleX = 1f
                        scaleY = 1f
                    } else {
                        val resolvedDirection = direction
                            ?: resolvePredictiveBackDirection(latestState.swipeEdge, currentLayoutDirection)
                        val targetScale = (1f - latestClamped * scaleReduction).coerceIn(0.1f, 1f)
                        translationX = size.width * latestClamped * resolvedDirection
                        scaleX = targetScale
                        scaleY = targetScale
                    }
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

/** 向全屏覆盖层内部的根路由暴露外层覆盖层预测性返回控制器，避免内外层冲突与松手弹回。 */
val LocalPredictiveOverlayBackController =
    compositionLocalOf<PredictiveOverlayBackController?> { null }

/**
 * 全屏侧滑覆盖层与预测性返回协同控制器。
 *
 * 维护覆盖层的单一真实开合进度 `openProgress`（`0f` 关闭、`1f` 全开）：
 * - 手势拖拽期间仅更新 `snapTo(1f - progress)` 与退出方向，零组合重构；
 * - 手势提交时**不将进度归零/归 1 弹回**，直接由宿主退出动画从当前拖出位置顺滑续滑完成；
 * - 手势取消时以临界阻尼 [MotionSpecs.morph] 平滑回弹至全开态，并用代际令牌防止竞态覆写。
 */
@Stable
class PredictiveOverlayBackController internal constructor(
    initialOpen: Boolean,
    enterFromStart: Boolean = true,
) {
    private val defaultExitDirection: Float = if (enterFromStart) -1f else 1f
    internal val openProgress = Animatable(
        initialValue = if (initialOpen) 1f else 0f,
        visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
    )
    internal var exitDirection by mutableFloatStateOf(defaultExitDirection)
    internal var overlayMounted by mutableStateOf(initialOpen)
    internal var latestOpenState: Boolean = initialOpen
    private var gestureToken: Int = 0
    private var reboundJob: Job? = null

    private fun resetExitDirection() {
        exitDirection = defaultExitDirection
    }

    internal fun beginGesture(): Int {
        val token = ++gestureToken
        reboundJob?.cancel()
        reboundJob = null
        return token
    }

    internal suspend fun onDragProgress(
        token: Int,
        progress: Float,
        swipeEdge: Int,
        layoutDirection: LayoutDirection,
    ) {
        if (gestureToken != token) return
        overlayMounted = true
        exitDirection = resolvePredictiveBackDirection(swipeEdge, layoutDirection)
        val targetOpen = 1f - progress.coerceIn(0f, 1f)
        if (gestureToken == token) {
            openProgress.snapTo(targetOpen)
        }
    }

    private fun launchReboundToOpen(
        token: Int,
        scope: CoroutineScope,
        reduced: Boolean,
        waitFramesBeforeCheck: Int = 0,
    ) {
        reboundJob = scope.launch {
            repeat(waitFramesBeforeCheck) {
                withFrameNanos { }
            }
            if (gestureToken != token || !latestOpenState) return@launch
            val spec = if (reduced) MotionSpecs.reducedFade<Float>() else MotionSpecs.morph(false)
            if (openProgress.value < 1f) {
                openProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spec,
                )
            }
            openProgress.snapTo(1f)
            if (gestureToken == token) {
                resetExitDirection()
            }
        }
    }

    internal fun onDragCommit(
        token: Int,
        scope: CoroutineScope,
        reduced: Boolean,
        onDismiss: () -> Unit,
    ) {
        if (gestureToken != token) return
        onDismiss()
        // 若宿主未因此关闭覆盖层（例如拦截状态未变更），等待数帧确认重组/异步状态传播后再平滑复原。
        launchReboundToOpen(
            token = token,
            scope = scope,
            reduced = reduced,
            waitFramesBeforeCheck = 3,
        )
    }

    internal fun onDragCancel(
        token: Int,
        scope: CoroutineScope,
        reduced: Boolean,
    ) {
        if (gestureToken != token) return
        launchReboundToOpen(
            token = token,
            scope = scope,
            reduced = reduced,
            waitFramesBeforeCheck = 0,
        )
    }

    internal suspend fun syncOpenState(open: Boolean, reduced: Boolean) {
        latestOpenState = open
        gestureToken++
        reboundJob?.cancel()
        reboundJob = null
        if (open) {
            overlayMounted = true
            if (openProgress.value == 0f || reduced) {
                resetExitDirection()
            }
            if (openProgress.value < 1f) {
                val spec = if (reduced) {
                    MotionSpecs.reducedFade<Float>()
                } else {
                    MotionSpecs.modalEnter(
                        reduced = false,
                        visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
                    )
                }
                openProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spec,
                )
                openProgress.snapTo(1f)
            }
            resetExitDirection()
        } else {
            if (overlayMounted && openProgress.value > 0f) {
                val spec = if (reduced) {
                    MotionSpecs.reducedFade<Float>()
                } else {
                    MotionSpecs.modalExit(
                        reduced = false,
                        visibilityThreshold = AppTransitions.ScaleVisibilityThreshold,
                    )
                }
                openProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = spec,
                )
                openProgress.snapTo(0f)
            }
            overlayMounted = false
            resetExitDirection()
        }
    }

    internal fun dispose() {
        gestureToken++
        reboundJob?.cancel()
        reboundJob = null
    }
}

/**
 * 创建并同步全屏覆盖层预测性返回控制器，在调用位置稳定注册兜底返回处理器。
 *
 * 调用方应将其放置在宽/窄屏条件分支之前，以维持 `OnBackPressedDispatcher` LIFO 栈底语义，
 * 确保跨 `840dp` 断点折叠时绝不抢占内部子页导航的返回键。
 */
@Composable
fun rememberPredictiveOverlayBackController(
    overlayOpen: Boolean,
    enabled: Boolean,
    reduced: Boolean,
    onDismiss: () -> Unit,
    enterFromStart: Boolean = true,
    sdkInt: Int = Build.VERSION.SDK_INT,
): PredictiveOverlayBackController {
    val controller = remember {
        PredictiveOverlayBackController(
            initialOpen = overlayOpen,
            enterFromStart = enterFromStart,
        )
    }
    SideEffect { controller.latestOpenState = overlayOpen }
    LaunchedEffect(overlayOpen, reduced) {
        controller.syncOpenState(open = overlayOpen, reduced = reduced)
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    PredictiveBackOverlayBridge(
        controller = controller,
        enabled = enabled,
        onDismiss = onDismiss,
        reduced = reduced,
        sdkInt = sdkInt,
    )
    return controller
}

/**
 * 将当前 Compose 层次中的预测性返回手势（API 34+）或传统返回键（API 31–33）桥接到
 * [PredictiveOverlayBackController]，供宿主兜底与内层根路由共同驱动同一动画进度。
 */
@Composable
fun PredictiveBackOverlayBridge(
    controller: PredictiveOverlayBackController,
    enabled: Boolean,
    onDismiss: () -> Unit,
    reduced: Boolean = LocalMotionPolicy.current.isReduced,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val currentReduced by rememberUpdatedState(reduced)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentLayoutDirection by rememberUpdatedState(layoutDirection)

    if (supportsNativePredictiveBack(sdkInt)) {
        PredictiveBackHandler(enabled = enabled) { events ->
            val token = controller.beginGesture()
            try {
                events.collect { event ->
                    if (!currentReduced) {
                        controller.onDragProgress(
                            token = token,
                            progress = event.progress,
                            swipeEdge = event.swipeEdge,
                            layoutDirection = currentLayoutDirection,
                        )
                    }
                }
                controller.onDragCommit(
                    token = token,
                    scope = scope,
                    reduced = currentReduced,
                    onDismiss = currentOnDismiss,
                )
            } catch (cancelled: CancellationException) {
                controller.onDragCancel(
                    token = token,
                    scope = scope,
                    reduced = currentReduced,
                )
                throw cancelled
            }
        }
    } else {
        BackHandler(enabled = enabled) {
            currentOnDismiss()
        }
    }
}

/**
 * 全屏侧滑覆盖层的双层视差宿主容器：
 * - `Z = 0`（底层背景页）：在绘制期惰性读取 `openProgress`，叠加 28% 视差位移、`0.96f` 纵深缩放与 Scrim 压暗；
 *   当覆盖层静止全开完全遮挡底层时跳过背景内容与遮罩绘制，零过绘；
 * - `Z = 1`（顶层覆盖层）：强制实色 `surface` 背景与命中阻断（仅拦截向底层穿透，不消费未处理事件以免干扰内层滚动），
 *   静止全开态移除独立图形层包装，仅在转场/预测返回期间启用图形层并绘制 `20.dp` Elevation 渐变阴影。
 */
@Composable
fun FullscreenPredictiveSlideOverlay(
    controller: PredictiveOverlayBackController,
    reduced: Boolean,
    modifier: Modifier = Modifier,
    overlayTestTag: String? = null,
    backgroundContent: @Composable () -> Unit,
    overlayContent: @Composable () -> Unit,
) {
    val scrimColor = MaterialTheme.colorScheme.scrim
    val surfaceColor = MaterialTheme.colorScheme.surface
    val bgNeedsLayer = remember(controller, reduced) {
        derivedStateOf { !reduced && controller.openProgress.value > 0f }
    }
    val fgNeedsLayer = remember(controller) {
        derivedStateOf { controller.openProgress.value < 0.999f }
    }
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
                .conditionalGraphicsLayer(needsLayer = bgNeedsLayer) {
                    val p = controller.openProgress.value.coerceIn(0f, 1f)
                    if (!reduced && p > 0f) {
                        translationX = -size.width * AppTransitions.ParallaxOffsetFraction * p * controller.exitDirection
                        val scale = 1f - (1f - AppTransitions.BackgroundDepthScale) * p
                        scaleX = scale
                        scaleY = scale
                    } else {
                        translationX = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
                .drawWithContent {
                    val p = controller.openProgress.value.coerceIn(0f, 1f)
                    if (controller.overlayMounted && p >= 0.999f) {
                        return@drawWithContent
                    }
                    drawContent()
                    if (!reduced) {
                        val scrimAlpha = AppTransitions.BackgroundScrimMaxAlpha * p
                        if (scrimAlpha > 0.001f) {
                            drawRect(color = scrimColor.copy(alpha = scrimAlpha))
                        }
                    }
                },
        ) {
            backgroundContent()
        }

        if (controller.overlayMounted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .then(if (overlayTestTag != null) Modifier.testTag(overlayTestTag) else Modifier)
                    .conditionalGraphicsLayer(needsLayer = fgNeedsLayer) {
                        val p = controller.openProgress.value.coerceIn(0f, 1f)
                        if (reduced) {
                            translationX = 0f
                            alpha = p
                        } else {
                            translationX = size.width * (1f - p) * controller.exitDirection
                            alpha = 1f
                        }
                    }
                    .then(
                        if (!reduced) {
                            Modifier.drawWithContent {
                                drawContent()
                                val p = controller.openProgress.value.coerceIn(0f, 1f)
                                if (p > 0.001f && p < 0.999f) {
                                    drawTransitionEdgeShadow(
                                        scrimColor = scrimColor,
                                        factor = p,
                                        onStartEdge = controller.exitDirection >= 0f,
                                    )
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .background(surfaceColor)
                    .pointerInput(Unit) {},
            ) {
                CompositionLocalProvider(LocalPredictiveOverlayBackController provides controller) {
                    overlayContent()
                }
            }
        }
    }
}

private fun Modifier.conditionalGraphicsLayer(
    needsLayer: State<Boolean>,
    layerBlock: GraphicsLayerScope.() -> Unit,
): Modifier = this then ConditionalGraphicsLayerElement(needsLayer = needsLayer, layerBlock = layerBlock)

private class ConditionalGraphicsLayerElement(
    private val needsLayer: State<Boolean>,
    private val layerBlock: GraphicsLayerScope.() -> Unit,
) : ModifierNodeElement<ConditionalGraphicsLayerNode>() {
    override fun create(): ConditionalGraphicsLayerNode = ConditionalGraphicsLayerNode(
        needsLayer = needsLayer,
        layerBlock = layerBlock,
    )

    override fun update(node: ConditionalGraphicsLayerNode) {
        val changed = node.needsLayer != needsLayer || node.layerBlock != layerBlock
        node.needsLayer = needsLayer
        node.layerBlock = layerBlock
        if (changed) {
            node.invalidatePlacement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "conditionalGraphicsLayer"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConditionalGraphicsLayerElement) return false
        return needsLayer == other.needsLayer && layerBlock == other.layerBlock
    }

    override fun hashCode(): Int = 31 * needsLayer.hashCode() + layerBlock.hashCode()
}

private class ConditionalGraphicsLayerNode(
    var needsLayer: State<Boolean>,
    var layerBlock: GraphicsLayerScope.() -> Unit,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (!needsLayer.value) {
                placeable.place(0, 0)
            } else {
                placeable.placeWithLayer(x = 0, y = 0, layerBlock = layerBlock)
            }
        }
    }
}
