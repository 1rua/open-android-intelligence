package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** 页面在导航/侧滑转场中承担的图层角色。 */
enum class NavPageTransitionRole {
    /** 上层前景页：全宽全速横向滑入/滑出，左侧外缘携带 Elevation 阴影。 */
    FOREGROUND,

    /** 下层背景页：28% 视差位移 + 轻微纵深缩放 + 柔和 Scrim 压暗，全程 100% 不透明。 */
    BACKGROUND,
}

/**
 * 页面级与组件级转场令牌。
 *
 * 遵循 Material 3 Expressive 弹簧物理系统与分层视差规范：
 * - 页面导航转场（`navEnter` / `navExit` / `navPopEnter` / `navPopExit` / `modal*`）在非减少动态下
 *   彻底移除全页透明淡化（`fadeIn`/`fadeOut`），通过 **100% 实色背景 + Z-Index 层叠 + 视差位移 +
 *   轻微缩放与 Scrim/边缘阴影** 杜绝下层视图穿透；
 * - 常规路由进场位移与缩放使用 [MotionSpecs.spatialDefault]，退场使用更轻快的 [MotionSpecs.spatialFast]；
 *   全屏模态设置面板进场使用舒展的 [MotionSpecs.ExpressiveSpatialDefaultSpring]（阻尼 0.8f、刚度 380f），
 *   退场使用平滑平稳的 [MotionSpecs.ModalExitSpring]（阻尼 0.9f、刚度 600f），
 *   保持离开节奏快于进入并支持 `SeekableTransitionState`（预测性返回）逐帧拖拽同步与随时打断；
 * - 用户开启「减少动态」或系统动画时长缩放为 0 时统一降级为极短的无位移线性交叉淡化。
 */
object AppTransitions {
    /** 背景页视差位移比例（位于 MD3 25%–30% 规范区间内）。 */
    const val ParallaxOffsetFraction: Float = 0.28f

    /** 背景页退至下层时的纵深缩放比例。 */
    const val BackgroundDepthScale: Float = 0.96f

    /** 背景页退至下层时的最大 Scrim 压暗透明度（仅叠色遮罩，页面本身保持 100% 不透明）。 */
    const val BackgroundScrimMaxAlpha: Float = 0.16f

    /** 前景进场/退场页左侧外缘阴影最大透明度。 */
    const val EdgeShadowMaxAlpha: Float = 0.24f

    /** 前景进场/退场页左侧外缘阴影宽度。 */
    val EdgeShadowWidth: Dp = 20.dp

    /** 精细缩放弹簧阈值：防止默认 0.01f 阈值在 `0.96f <-> 1.0f` 尾段提前截断跳变。 */
    const val ScaleVisibilityThreshold: Float = 0.0005f

    /**
     * 基础同级表面切换进入过渡（例如登录页 ↔ 工作台，宿主底层已有实色 Surface）。
     */
    fun enter(reduced: Boolean, forward: Boolean = true): EnterTransition =
        sharedAxisEnter(
            reduced = reduced,
            initialOffsetX = { full -> (if (forward) 1 else -1) * full / 6 },
            initialScale = 0.95f,
        )

    /**
     * 基础同级表面切换退出过渡。
     */
    fun exit(reduced: Boolean, forward: Boolean = true): ExitTransition =
        sharedAxisExit(
            reduced = reduced,
            targetOffsetX = { full -> (if (forward) -1 else 1) * full / 6 },
            targetScale = BackgroundDepthScale,
        )

    /**
     * NavHost 路由推进进入动画（Z = 上层前景）：右侧 100% 全宽全速不透明滑入。
     */
    fun navEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(true))
        else -> slideInHorizontally(
            animationSpec = MotionSpecs.spatialDefault(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            initialOffsetX = { full -> full },
        )
    }

    /**
     * NavHost 路由推进退出动画（Z = 下层背景）：向左侧做 28% 视差位移与轻微缩放，
     * 全程保持 100% 不透明（无 `fadeOut(0f)`），绝不暴露更下层宿主界面。
     */
    fun navExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(true))
        else -> slideOutHorizontally(
            animationSpec = MotionSpecs.spatialFast(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            targetOffsetX = { full -> -(full * ParallaxOffsetFraction).toInt() },
        ) + scaleOut(
            animationSpec = MotionSpecs.spatialFast(
                reduced = false,
                visibilityThreshold = ScaleVisibilityThreshold,
            ),
            targetScale = BackgroundDepthScale,
        )
    }

    /**
     * NavHost 路由出栈/预测性返回进入动画（Z = 上一级背景页面）：从左侧 `-28%` 视差位
     * 与 `0.96f` 缩放实时跟手滑回并复原，全程保持 100% 不透明。
     */
    fun navPopEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(true))
        else -> slideInHorizontally(
            animationSpec = MotionSpecs.spatialDefault(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            initialOffsetX = { full -> -(full * ParallaxOffsetFraction).toInt() },
        ) + scaleIn(
            animationSpec = MotionSpecs.spatialDefault(
                reduced = false,
                visibilityThreshold = ScaleVisibilityThreshold,
            ),
            initialScale = BackgroundDepthScale,
        )
    }

    /**
     * NavHost 路由出栈/预测性返回退出动画（Z = 当前前景页）：100% 不透明整页向右全速滑出。
     */
    fun navPopExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(true))
        else -> slideOutHorizontally(
            animationSpec = MotionSpecs.spatialFast(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            targetOffsetX = { full -> full },
        )
    }

    /**
     * 模态层/全屏设置面板打开动画：从左向右（+X）100% 不透明全宽滑入。
     *
     * 采用 [MotionSpecs.ExpressiveSpatialDefaultSpring]（阻尼 0.8f、刚度 380f），
     * 使全屏滑入自然舒展至约 350–400ms，消除极速弹簧带来的瞬移感。
     */
    fun modalEnter(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(true))
        else -> slideInHorizontally(
            animationSpec = MotionSpecs.modalEnter(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            initialOffsetX = { full -> -full },
        )
    }

    /**
     * 模态层/全屏设置面板关闭动画：向左（-X）100% 不透明全宽滑出屏幕。
     *
     * 采用 [MotionSpecs.ModalExitSpring] 平稳退场弹簧（刚度 600f），既维持离开快于进入的节奏，
     * 又保持顺滑自然的视觉收束。
     */
    fun modalExit(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(true))
        else -> slideOutHorizontally(
            animationSpec = MotionSpecs.modalExit(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            targetOffsetX = { full -> -full },
        )
    }

    /**
     * 折叠容器统一垂直展开过渡：供工具卡片、思考卡片、回合折叠大卡片与工作区折叠列表共享。
     *
     * 标准态沿用工具卡片的无过冲临界阻尼尺寸弹簧（[MotionSpecs.disclosureSize]）+
     * 临界阻尼透明度弹簧（[MotionSpecs.disclosureFade]）；减少动态时降级为纯无位移线性淡入，
     * 不叠加任何垂直位移或高度裁剪动画。
     */
    fun disclosureExpandVertical(reduced: Boolean): EnterTransition = when {
        reduced -> fadeIn(animationSpec = MotionSpecs.disclosureFade(reduced = true))
        else -> expandVertically(
            animationSpec = MotionSpecs.disclosureSize(
                reduced = false,
                visibilityThreshold = IntSize.VisibilityThreshold,
            ),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = MotionSpecs.disclosureFade(reduced = false))
    }

    /**
     * 折叠容器统一垂直收起过渡：与 [disclosureExpandVertical] 互为反向；减少动态时降级为纯无位移线性淡出。
     */
    fun disclosureCollapseVertical(reduced: Boolean): ExitTransition = when {
        reduced -> fadeOut(animationSpec = MotionSpecs.disclosureFade(reduced = true))
        else -> shrinkVertically(
            animationSpec = MotionSpecs.disclosureSize(
                reduced = false,
                visibilityThreshold = IntSize.VisibilityThreshold,
            ),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = MotionSpecs.disclosureFade(reduced = false))
    }

    private fun sharedAxisEnter(
        reduced: Boolean,
        initialOffsetX: (fullWidth: Int) -> Int,
        initialScale: Float,
    ): EnterTransition = when {
        reduced -> fadeIn(MotionSpecs.fade(true))
        else -> slideInHorizontally(
            animationSpec = MotionSpecs.spatialDefault(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            initialOffsetX = initialOffsetX,
        ) + fadeIn(
            animationSpec = MotionSpecs.effectsDefault(false),
            initialAlpha = 0.0f,
        ) + scaleIn(
            animationSpec = MotionSpecs.spatialDefault(false),
            initialScale = initialScale,
        )
    }

    private fun sharedAxisExit(
        reduced: Boolean,
        targetOffsetX: (fullWidth: Int) -> Int,
        targetScale: Float,
    ): ExitTransition = when {
        reduced -> fadeOut(MotionSpecs.fade(true))
        else -> slideOutHorizontally(
            animationSpec = MotionSpecs.spatialFast(
                reduced = false,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            targetOffsetX = targetOffsetX,
        ) + fadeOut(
            animationSpec = MotionSpecs.effectsFast(false),
            targetAlpha = 0.0f,
        ) + scaleOut(
            animationSpec = MotionSpecs.spatialFast(false),
            targetScale = targetScale,
        )
    }
}

/**
 * 为处于 `NavHost` / `AnimatedContent` 过渡中的页面叠加 MD3 背景 Scrim 压暗与前景左侧边缘阴影。
 *
 * 在绘制阶段惰性读取角色与渐变系数，静止页面零 GPU 过绘，减少动态或缺少作用域时直接退化为无装饰。
 */
@Composable
fun Modifier.navPageTransitionDecoration(
    roleProvider: () -> NavPageTransitionRole?,
    reduced: Boolean,
): Modifier {
    if (reduced) return this
    val visibilityScope = LocalAnimatedVisibilityScope.current ?: return this
    val scrimColor = MaterialTheme.colorScheme.scrim
    val transition = visibilityScope.transition
    val visibilityFactor by transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                MotionSpecs.effectsSlow(reduced = false)
            } else {
                MotionSpecs.effectsDefault(reduced = false)
            }
        },
        label = "nav-page-visibility-factor",
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return this.drawWithContent {
        drawContent()
        val isTransitionActive = transition.currentState != transition.targetState || transition.isRunning
        if (!isTransitionActive) return@drawWithContent
        val factor = visibilityFactor.coerceIn(0f, 1f)
        when (roleProvider()) {
            NavPageTransitionRole.BACKGROUND -> {
                val scrimAlpha = AppTransitions.BackgroundScrimMaxAlpha * (1f - factor)
                if (scrimAlpha > 0.001f) {
                    drawRect(color = scrimColor.copy(alpha = scrimAlpha))
                }
            }
            NavPageTransitionRole.FOREGROUND -> {
                drawTransitionEdgeShadow(
                    scrimColor = scrimColor,
                    factor = factor,
                    onStartEdge = true,
                )
            }
            null -> Unit
        }
    }
}

/**
 * 在页面或全屏侧滑覆盖层暴露出的外缘绘制 MD3 Elevation 渐变阴影。
 *
 * @param factor 当前展开/可见系数 `[0f, 1f]`；在接近完全展开/收起两端衰减，中段接近 `EdgeShadowMaxAlpha`。
 * @param onStartEdge `true` 时画在左缘外侧（`[-shadowWidthPx, 0]`），`false` 时画在右缘外侧（`[width, width + shadowWidthPx]`）。
 */
internal fun ContentDrawScope.drawTransitionEdgeShadow(
    scrimColor: Color,
    factor: Float,
    onStartEdge: Boolean = true,
) {
    val away = (1f - factor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    val shadowAlpha = AppTransitions.EdgeShadowMaxAlpha * (1f - away * away)
    if (shadowAlpha <= 0.001f) return
    val shadowWidthPx = AppTransitions.EdgeShadowWidth.toPx()
    val startX = if (onStartEdge) -shadowWidthPx else size.width
    val endX = if (onStartEdge) 0f else size.width + shadowWidthPx
    val colors = if (onStartEdge) {
        listOf(Color.Transparent, scrimColor.copy(alpha = shadowAlpha))
    } else {
        listOf(scrimColor.copy(alpha = shadowAlpha), Color.Transparent)
    }
    drawRect(
        brush = Brush.horizontalGradient(
            colors = colors,
            startX = startX,
            endX = endX,
        ),
        topLeft = Offset(x = startX, y = 0f),
        size = Size(width = shadowWidthPx, height = size.height),
    )
}
