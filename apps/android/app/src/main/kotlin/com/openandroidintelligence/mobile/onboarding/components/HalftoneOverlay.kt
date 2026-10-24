package com.openandroidintelligence.mobile.onboarding.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 网点半径与间距的比例：对齐原型 6px 网格里 1px 半径的点。 */
private const val HalftoneDotRatio = 1f / 6f

/** 单个网点半径上限（dp）：像素密度很高时不至于糊成一个实心斑块。 */
private const val MaxHalftoneDotRadiusDp = 0.8f

/** 颗粒噪点的固定种子：让颗粒位置在每一帧、每台设备上完全一致，避免滚动时闪烁。 */
private const val GrainSeed = 0xA11E_0A57

/** 颗粒密度：每平方 dp 约 0.9 个颗粒，再按面积上限裁剪。 */
private const val GrainPerSquareDp = 0.9f

/** 颗粒数量硬上限：宁可在超大区域上稀疏一点，也不能掉帧。 */
private const val MaxGrainRects = 600

/** 单个颗粒边长（dp）：1dp 已足够模拟纸面微噪点，再大就变成脏点。 */
private const val GrainRectSideDp = 1f

/**
 * 印刷网点（Halftone）：以固定间距铺一层圆点阵，给局部区块加「纸面 / 印刷」质感。
 *
 * 对应原型的 `.halftone`（`radial-gradient` + `background-size: 6px 6px`）：
 * 只用于**局部装饰区**（卡片一角、大图形旁边），不要铺满整屏——
 * 满屏网点会和正文抢注意力，绘制量也随面积线性增长。
 *
 * 实现要点：
 * 1. [drawWithCache] 缓存间距、半径、颜色与混合模式，只有尺寸或主题色变化时才重算；
 * 2. 点间距由 [dotSpacing] 控制，点半径随间距等比收放，改间距不会破坏疏密比例；
 * 3. 颜色取 `onSurface` 乘 [alpha]：语义角色保证深浅主题与动态取色下自动正确，
 *    不用为浅色/深色各写一套网点色，也不出现任何硬编码色值；
 * 4. 深色底用 [BlendMode.Screen]（浅色叠加不发灰），浅色底用 [BlendMode.SrcOver]；
 * 5. **纯静态**：没有任何动画，因此减少动态（reduceMotion）时无需特殊处理。
 *
 * @param alpha 网点整体不透明度。默认 0.55 对齐原型；局部叠加建议 0.3 左右。
 * @param dotSpacing 相邻网点的中心距，默认 6dp。越小越密。
 */
fun Modifier.halftone(
    alpha: Float = 0.55f,
    dotSpacing: Dp = 6.dp,
): Modifier = composed {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = remember(onSurface) { onSurface.luminance() < 0.5f }

    drawWithCache {
        val spacingPx = dotSpacing.toPx().coerceAtLeast(1f)
        val dotRadiusPx = (spacingPx * HalftoneDotRatio)
            .coerceAtMost(MaxHalftoneDotRadiusDp.dp.toPx())
            .coerceAtLeast(0.5f)
        val blendMode = if (isDark) BlendMode.Screen else BlendMode.SrcOver
        val dotColor = onSurface.copy(alpha = alpha)

        onDrawBehind {
            // 网格从左上角起步（偏移一个半径，让点完整落在画布内）。
            // 多画一行一列，右/下边缘才不会留出空缺条带。
            var y = dotRadiusPx
            while (y <= size.height + dotRadiusPx) {
                var x = dotRadiusPx
                while (x <= size.width + dotRadiusPx) {
                    drawCircle(
                        color = dotColor,
                        radius = dotRadiusPx,
                        center = Offset(x, y),
                        blendMode = blendMode,
                    )
                    x += spacingPx
                }
                y += spacingPx
            }
        }
    }
}

/**
 * 印刷颗粒（Print Grain）：用固定种子的伪随机小矩形模拟纸面微噪点。
 *
 * 对应原型的 `.grain`（一张 SVG turbulence 噪点图）。这里刻意**不用位图**：
 * 位图要打包资源、要为深浅主题各备一份，还会在极端缩放下露出拉伸纹理；
 * 噪点本身没有语义，用确定性随机现画更小、更可控，也不引入任何新依赖。
 *
 * 实现要点：
 * 1. 种子固定（[GrainSeed]），颗粒位置在重组、滚动、设备之间完全一致；
 * 2. 数量由面积推算并硬性封顶 [MaxGrainRects]（≤600），大区域只会变稀疏，不掉帧；
 * 3. 位置随机抖动而不落网格，避免规则点阵那种机械感；
 * 4. 深色主题用 [BlendMode.Screen]、浅色主题用 [BlendMode.Multiply]，
 *    与原型 `mix-blend-mode` 一致，两种底色下颗粒都不会变成脏斑；
 * 5. 几何在 [drawWithCache] 里一次算好并缓存，绘制阶段只重复画矩形。
 *
 * @param alpha 颗粒整体不透明度。默认 0.035 是「几乎看不见、去掉又少一层」的量级。
 */
fun Modifier.printGrain(alpha: Float = 0.035f): Modifier = composed {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = remember(onSurface) { onSurface.luminance() < 0.5f }

    drawWithCache {
        val count = grainRectCount(size.width, size.height, density)
        val sidePx = GrainRectSideDp.dp.toPx().coerceAtLeast(1f)
        val blendMode = if (isDark) BlendMode.Screen else BlendMode.Multiply
        val grainColor = onSurface.copy(alpha = alpha)

        val random = Random(GrainSeed)
        val points = List(count) {
            Offset(
                x = random.nextFloat() * size.width,
                y = random.nextFloat() * size.height,
            )
        }

        onDrawBehind {
            points.forEach { point ->
                drawRect(
                    color = grainColor,
                    topLeft = point,
                    size = Size(sidePx, sidePx),
                    blendMode = blendMode,
                )
            }
        }
    }
}

/**
 * 由绘制区域面积推算颗粒数量。
 *
 * 抽成独立函数是为了让「密度 × 面积 → 封顶」这条规则只有一处实现，
 * 调整颗粒密度时不会出现两套算法互相漂移。
 *
 * @param widthPx 区域宽（像素）。
 * @param heightPx 区域高（像素）。
 * @param density 当前屏幕的像素密度（px / dp），用于把像素面积换算成 dp²。
 */
private fun grainRectCount(widthPx: Float, heightPx: Float, density: Float): Int {
    val areaSquareDp = (widthPx / density) * (heightPx / density)
    return min(MaxGrainRects, max(0, (areaSquareDp * GrainPerSquareDp).toInt()))
}
