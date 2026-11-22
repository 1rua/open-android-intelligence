package com.openandroidintelligence.conversation.motion

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 预测性返回手势实时状态快照。
 *
 * @param progress 归一化返回进度 `[0f, 1f]`；减少动态或未开启时保持 `0f`。
 * @param swipeEdge 系统侧滑起始边缘（对应 `BackEventCompat.EDGE_LEFT` / `EDGE_RIGHT`）。
 * @param inProgress 手势是否正处于拖拽追踪中。
 */
@Immutable
data class PredictiveBackState(
    val progress: Float = 0f,
    val swipeEdge: Int = 0,
    val inProgress: Boolean = false,
)

/** Android 14（API 34）及以上平台原生支持预测性返回手势进度流。 */
internal fun supportsNativePredictiveBack(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * 注册跨版本兼容的预测性返回处理器，并返回当前归一化进度 `[0f, 1f]`。
 *
 * - API 34+：通过 [PredictiveBackHandler] 实时追踪系统跟手进度；若用户在半途取消返回，
 *   会在 [NonCancellable] 上下文中用 [MotionSpecs.spatialDefault] 弹簧平滑回弹至 `0f` 后
 *   重抛 [CancellationException]。
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
        onProgressChange = null,
        onBack = onBack,
        sdkInt = sdkInt,
    )
    return progress.asState()
}

/**
 * 跨版本兼容的预测性返回处理组件，适合需要同时观察 [PredictiveBackState.swipeEdge] 或
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
        onProgressChange = onProgressChange,
        onBack = onBack,
        sdkInt = sdkInt,
    )
}

@Composable
private fun PredictiveBackCompatEffect(
    enabled: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    onProgressChange: ((PredictiveBackState) -> Unit)?,
    onBack: () -> Unit,
    sdkInt: Int,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentReduced by rememberUpdatedState(reduced)

    LaunchedEffect(enabled, reduced) {
        if ((!enabled || reduced) && progress.value != 0f) {
            progress.snapTo(0f)
            currentOnProgressChange?.invoke(PredictiveBackState())
        }
    }

    if (supportsNativePredictiveBack(sdkInt)) {
        PredictiveBackHandler(enabled = enabled) { events ->
            var lastEdge = 0
            try {
                events.collect { event ->
                    lastEdge = event.swipeEdge
                    val next = if (currentReduced) 0f else event.progress.coerceIn(0f, 1f)
                    progress.snapTo(next)
                    currentOnProgressChange?.invoke(
                        PredictiveBackState(
                            progress = next,
                            swipeEdge = lastEdge,
                            inProgress = !currentReduced,
                        ),
                    )
                }
                currentOnProgressChange?.invoke(
                    PredictiveBackState(
                        progress = progress.value,
                        swipeEdge = lastEdge,
                        inProgress = false,
                    ),
                )
                currentOnBack()
            } catch (cancelled: CancellationException) {
                // 手势取消时必须保证面板平滑弹回原位，随后按协程约定重抛取消异常。
                withContext(NonCancellable) {
                    if (currentReduced || progress.value <= 0f) {
                        progress.snapTo(0f)
                        currentOnProgressChange?.invoke(
                            PredictiveBackState(progress = 0f, swipeEdge = lastEdge, inProgress = false),
                        )
                    } else {
                        progress.animateTo(
                            targetValue = 0f,
                            animationSpec = MotionSpecs.spatialDefault(false),
                        ) {
                            currentOnProgressChange?.invoke(
                                PredictiveBackState(
                                    progress = value.coerceIn(0f, 1f),
                                    swipeEdge = lastEdge,
                                    inProgress = false,
                                ),
                            )
                        }
                    }
                }
                throw cancelled
            } finally {
                withContext(NonCancellable) {
                    if (progress.value != 0f) {
                        progress.snapTo(0f)
                    }
                }
            }
        }
    } else {
        BackHandler(enabled = enabled) {
            currentOnProgressChange?.invoke(PredictiveBackState())
            currentOnBack()
        }
    }
}

/**
 * 将预测性返回手势进度映射为仅作用于绘制层的横向滑移与微缩反馈。
 *
 * 只修改 `graphicsLayer`，不触发布局重新测量；当 [reduced] 为 `true` 时直接返回原 [Modifier]。
 */
fun Modifier.predictiveBackSlideTransform(
    progress: Float,
    reduced: Boolean,
    direction: Float = -1f,
    maxScaleReduction: Float = 0.04f,
): Modifier {
    if (reduced) return this
    val clamped = progress.coerceIn(0f, 1f)
    if (clamped <= 0f) return this
    val scaleReduction = maxScaleReduction.coerceIn(0f, 0.9f)
    val targetScale = (1f - clamped * scaleReduction).coerceIn(0.1f, 1f)
    return graphicsLayer {
        translationX = size.width * clamped * direction
        scaleX = targetScale
        scaleY = targetScale
    }
}
