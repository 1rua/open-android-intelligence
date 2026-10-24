package com.openandroidintelligence.mobile.onboarding.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlin.math.hypot
import kotlin.math.min

/*
 * 「按钮展开 → 全屏 → 登录页浮现」的覆盖层实现。
 *
 * 设计取舍：
 * 1. **不做页面淡入淡出**。需求明确要求登录界面「从按钮中生长出来」。因此覆盖面以被点击
 *    按钮的位置与形状为起点，形变为覆盖整页的大圆；登录内容只在覆盖面前沿之下出现，
 *    形成「生长」而不是「切换」。
 * 2. **本文件不驱动动画**。进度由调用方（OnboardingFlow）通过 [RevealPhase.Expanding.progress]
 *    注入，这里只回答「给定进度画成什么样」。进度不在此处组合阶段读取，避免每帧重组。
 * 3. **颜色只用语义角色**。覆盖面必须与页面同色（surface），否则会闪白；扩散前沿用
 *    outlineVariant 描边，网格用 outline 低透明度。全部走 MaterialTheme，禁止硬编码色值。
 */

/**
 * 页面级转场时长（毫秒）。
 *
 * 比 [MotionSpecs.Emphasized]（500ms）长：几何展开要横跨整块屏幕，500ms 会让扩散显得
 * 仓促、像「闪一下」；780ms 让视线有时间跟随前沿从按钮走到页面四角。仍然是单次、非循环
 * 的转场，不会拖慢交互（点击后内容从 42% 就开始浮现）。
 */
internal const val RevealDurationMillis = 780

/** 登录内容开始浮现的进度点：0.42 之前仍是引导页主导，之后覆盖面已推过页面中部。 */
private const val ContentRevealStart = 0.42f

/** 放射网格只在这段进度内出现：再往后覆盖面已接近满屏，网格会被吃掉。 */
private const val GridWindowEnd = 0.34f

/** 同心方框条数上限。9 条已足够表现「扩散」，再多只是填充率开销。 */
private const val GridRectCount = 9

/** 登录内容上浮距离（dp）：小位移制造「落位」感，不做夸张弹跳。 */
private const val ContentRiseDp = 14f

/** 登录内容起始缩放：0.985 → 1.0，只做极轻微的「靠近」。 */
private const val ContentStartScale = 0.985f

/**
 * 记录触发转场的按钮几何信息。
 *
 * 只保存「位置 + 尺寸」两个客观量：展开动画不依赖按钮的文案、颜色或内部状态，
 * 因此回调方不需要把整个按钮节点传出来。
 */
@Immutable
data class RevealOrigin(
    /** 按钮中心在根容器坐标系中的位置。 */
    val center: Offset,
    /** 按钮尺寸。 */
    val size: IntSize,
)

/**
 * 转场状态机。
 *
 * 三态而不是「一个布尔 + 一个浮点」：调用方可以据此区分「还没开始」「正在展开」
 * 与「已落定」，避免在 Settled 之后继续驱动无意义的绘制。
 */
sealed interface RevealPhase {
    /** 未开始：覆盖层不应参与合成。 */
    data object Idle : RevealPhase

    /**
     * 正在展开。
     * @param origin 触发转场的按钮几何。
     * @param progress 0..1 的展开进度，由调用方的动画驱动（缓动在调用方完成）。
     */
    data class Expanding(val origin: RevealOrigin, val progress: Float) : RevealPhase

    /** 已铺满整页：登录内容完全接管。 */
    data object Settled : RevealPhase
}

/**
 * 核心转场覆盖层，放在被覆盖内容之上。
 *
 * 绘制顺序（自下而上）：扩展面 → 前沿描边 → 放射网格 → [content]。
 * 进度只在绘制阶段读取，因此每帧只重绘、不重组。
 *
 * @param phase 当前阶段。
 * @param modifier 外部修饰符；调用方通常传 `Modifier.fillMaxSize()`。
 * @param content 转场期间/之后要显示的内容（登录页）；在 [ContentRevealStart] 之后淡入，
 *   并被裁剪在扩展面内部，所以看起来是从按钮里长出来的。
 */
@Composable
fun ButtonRevealTransition(
    phase: RevealPhase,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val surface = MaterialTheme.colorScheme.surface
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val outline = MaterialTheme.colorScheme.outline

    // 裁剪路径只分配一次，在绘制阶段原地复用：每帧 new Path 会在高刷设备上产生抖动。
    val clipCache = remember { Path() }
    // 只有展开过程中才拦截触摸，否则登录表单会在 Settled 之后仍然点不动。
    val intercepting = phase is RevealPhase.Expanding

    Box(modifier = modifier.fillMaxSize()) {
        // 扩展面：透明容器上直接画，progress=0 时已经是与页面同色的按钮形状，不出白帧。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRevealCover(phase, surface, outlineVariant, outline, reduceMotion)
                },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = phase.progressOrComplete()
                    if (reduceMotion) {
                        // 减少动态：退化为纯 alpha 交叉淡化，不做位移与缩放。
                        alpha = progress
                    } else {
                        // 内容缓动与展开分离：展开用 Emphasized（先缓后急），内容浮现用
                        // EmphasizedDecelerate（先快后缓），避免两者同曲线导致「一起卡住」。
                        val appear = MotionSpecs.EmphasizedDecelerateEasing
                            .transform(contentAlphaAt(progress))
                        alpha = appear
                        translationY = ContentRiseDp.dp.toPx() * (1f - appear)
                        val scale = ContentStartScale + (1f - ContentStartScale) * appear
                        scaleX = scale
                        scaleY = scale
                    }
                }
                .drawWithContent {
                    val progress = phase.progressOrComplete()
                    val origin = (phase as? RevealPhase.Expanding)?.origin
                    if (reduceMotion || origin == null) {
                        drawContent()
                        return@drawWithContent
                    }
                    // 内容只在「已经长出来的区域」内出现：这是「从按钮生长」的关键，
                    // 否则登录表单会浮在还没被覆盖的引导页上。
                    val radius = revealExtentAt(origin, progress, size.width, size.height)
                    if (radius <= 0f) return@drawWithContent
                    clipCache.reset()
                    clipCache.addOval(Rect(center = origin.center, radius = radius))
                    clipPath(clipCache) { this@drawWithContent.drawContent() }
                },
        ) {
            content()
        }

        // 拦截层放最上层：消费 Initial 通道的指针事件，避免展开期间误点到下层按钮。
        if (intercepting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

// ===== 纯函数几何：不依赖 Android 框架，单元测试直接覆盖 =====

/**
 * 计算从按钮中心扩散到容器最远角的半径。
 *
 * 必须取四个角的**最大值**：只取对角会让靠近边缘的按钮（例如底部整宽按钮）
 * 在另一侧留下未覆盖的空隙，展开结束的瞬间就会看到底下页面「闪」一下。
 *
 * @param centerX 按钮中心 X（容器坐标系）。
 * @param centerY 按钮中心 Y（容器坐标系）。
 * @param width 容器宽度。
 * @param height 容器高度。
 */
internal fun revealRadius(centerX: Float, centerY: Float, width: Float, height: Float): Float {
    if (width <= 0f || height <= 0f) return 0f
    val toTopLeft = hypot(centerX, centerY)
    val toTopRight = hypot(width - centerX, centerY)
    val toBottomLeft = hypot(centerX, height - centerY)
    val toBottomRight = hypot(width - centerX, height - centerY)
    return maxOf(toTopLeft, toTopRight, toBottomLeft, toBottomRight)
}

/**
 * 引导页残留的淡出 alpha：progress 0 → 1，在 [ContentRevealStart] 处正好归零。
 *
 * 与 [contentAlphaAt] 使用同一条进度轴，保证「引导页消失」和「登录内容出现」在
 * 时间上衔接，不会出现两边都半透明的灰糊帧。覆盖层本身不需要它（扩展面是实色），
 * 它提供给调用方淡出被覆盖的引导页内容。
 */
internal fun revealAlphaAt(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return (1f - clamped / ContentRevealStart).coerceIn(0f, 1f)
}

/**
 * 登录内容的淡入 alpha：progress ≤ [ContentRevealStart] 时为 0，之后线性升到 1。
 *
 * 线性即可：观感上的「先快后缓」由调用方在绘制阶段套一层
 * [MotionSpecs.EmphasizedDecelerateEasing] 完成，几何计算本身保持可测、可预测。
 */
internal fun contentAlphaAt(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return ((clamped - ContentRevealStart) / (1f - ContentRevealStart)).coerceIn(0f, 1f)
}

/** 线性插值并夹取 fraction，避免调用方传入越界进度造成反向形变。 */
internal fun lerpRevealCornerRadius(from: Float, to: Float, progress: Float): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return from + (to - from) * fraction
}

/** 放射网格的 alpha：在 0..[GridWindowEnd] 内先淡入后淡出，两端都必须归零。 */
internal fun gridAlphaAt(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    if (clamped >= GridWindowEnd) return 0f
    val half = GridWindowEnd / 2f
    val fadeIn = (clamped / half).coerceAtMost(1f)
    val fadeOut = (1f - (clamped - half) / half).coerceIn(0f, 1f)
    return min(fadeIn, fadeOut)
}

/** 当前进度对应的覆盖半径（形变中的外接圆），供绘制与内容裁剪共用一套几何。 */
private fun revealExtentAt(
    origin: RevealOrigin,
    progress: Float,
    width: Float,
    height: Float,
): Float {
    val maxRadius = revealRadius(origin.center.x, origin.center.y, width, height)
    val startRadius = min(origin.size.width, origin.size.height) / 2f
    return lerpRevealCornerRadius(startRadius, maxRadius, progress)
}

/** 进度取值：Idle 为 0，Settled 视为已完成，避免「落定」后覆盖面缩回去。 */
private fun RevealPhase.progressOrComplete(): Float = when (this) {
    is RevealPhase.Expanding -> progress.coerceIn(0f, 1f)
    RevealPhase.Settled -> 1f
    RevealPhase.Idle -> 0f
}

// ===== 绘制实现：全部在 draw 阶段读进度 =====

/**
 * 画扩展面本身。
 *
 * 形变路径：以按钮为中心的圆角矩形（尺寸 = 按钮、圆角 ≈ 按钮胶囊半径）→ 大圆。
 * 用**同一个**圆角矩形同时插值尺寸与圆角，是为了让「矩形化 → 圆形化」连续，
 * 不会出现从矩形突然切成圆的跳帧；当圆角等于半边长时几何上就是圆，此处改用
 * drawCircle 收尾，避免描边在极端圆角下出现毛边。
 */
private fun DrawScope.drawRevealCover(
    phase: RevealPhase,
    surface: Color,
    outlineVariant: Color,
    outline: Color,
    reduceMotion: Boolean,
) {
    val progress = phase.progressOrComplete()

    if (reduceMotion) {
        // 减少动态：不展开、不画网格，整页做一次短交叉淡化即可。
        drawRect(color = surface, alpha = progress)
        return
    }

    val origin = (phase as? RevealPhase.Expanding)?.origin
    if (origin == null) {
        // Settled：整页实色，保证「展开结束」与登录页背景之间没有透明度断层。
        drawRect(color = surface)
        return
    }

    val maxRadius = revealRadius(origin.center.x, origin.center.y, size.width, size.height)
    val startHalfWidth = origin.size.width / 2f
    val startHalfHeight = origin.size.height / 2f
    val halfWidth = lerpRevealCornerRadius(startHalfWidth, maxRadius, progress)
    val halfHeight = lerpRevealCornerRadius(startHalfHeight, maxRadius, progress)
    // 起点圆角取按钮短边的胶囊半径：Compose 不向外部暴露按钮实际 shape，
    // 引导页主 CTA 是胶囊按钮，这是最接近的等效值。
    val corner = lerpRevealCornerRadius(
        min(origin.size.width, origin.size.height) / 2f,
        maxRadius,
        progress,
    ).coerceAtMost(min(halfWidth, halfHeight))

    val topLeft = Offset(origin.center.x - halfWidth, origin.center.y - halfHeight)
    val coverSize = Size(halfWidth * 2f, halfHeight * 2f)

    if (corner >= min(halfWidth, halfHeight) - 0.5f) {
        drawCircle(color = surface, radius = halfWidth, center = origin.center)
    } else {
        drawRoundRect(
            color = surface,
            topLeft = topLeft,
            size = coverSize,
            cornerRadius = CornerRadius(corner, corner),
        )
    }

    drawRevealGrid(origin, maxRadius, progress, outline)

    // 前沿描边：让扩散边界在纯色背景上可辨识；progress 0 时正好是按钮轮廓，
    // 随后随推进淡出，避免落定后屏幕边缘残留一圈线。
    val edgeAlpha = revealAlphaAt(progress) * 0.55f
    if (edgeAlpha > 0.01f) {
        val stroke = Stroke(width = 1.dp.toPx())
        val edgeColor = outlineVariant.copy(alpha = edgeAlpha)
        if (corner >= min(halfWidth, halfHeight) - 0.5f) {
            drawCircle(color = edgeColor, radius = halfWidth, center = origin.center, style = stroke)
        } else {
            drawRoundRect(
                color = edgeColor,
                topLeft = topLeft,
                size = coverSize,
                cornerRadius = CornerRadius(corner, corner),
                style = stroke,
            )
        }
    }
}

/**
 * 放射网格：以按钮中心为原点的一组同心方框 + 十字坐标线，暗示「坐标系统从按钮扩散」。
 *
 * 只在 [GridWindowEnd] 之前出现，且用 1px 描边逐条叠画，不产生图层（无 SaveLayer）。
 */
private fun DrawScope.drawRevealGrid(
    origin: RevealOrigin,
    maxRadius: Float,
    progress: Float,
    outline: Color,
) {
    val gridAlpha = gridAlphaAt(progress)
    if (gridAlpha <= 0.01f) return

    // 网格随展开一起长大，但略快于覆盖面，视觉上像被「推」出去而不是被盖住。
    val extent = maxRadius * (0.25f + 0.75f * progress) * 1.15f
    val thin = 1.dp.toPx()
    for (index in 1..GridRectCount) {
        val radius = extent * index / GridRectCount
        drawRect(
            color = outline.copy(alpha = gridAlpha * 0.10f),
            topLeft = Offset(origin.center.x - radius, origin.center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = thin),
        )
    }
    val crossColor = outline.copy(alpha = gridAlpha * 0.16f)
    drawLine(
        color = crossColor,
        start = Offset(origin.center.x - extent, origin.center.y),
        end = Offset(origin.center.x + extent, origin.center.y),
        strokeWidth = thin,
    )
    drawLine(
        color = crossColor,
        start = Offset(origin.center.x, origin.center.y - extent),
        end = Offset(origin.center.x, origin.center.y + extent),
        strokeWidth = thin,
    )
}
