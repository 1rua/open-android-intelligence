package com.openandroidintelligence.mobile.onboarding.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按钮展开转场的几何纯函数测试。
 *
 * 只测数学，不碰 Compose/Android 框架：这些函数决定了「展开是否真的覆盖全屏」，
 * 一旦算错，用户会看到展开结束的瞬间底下页面闪一下——这是最难在真机上复现的缺陷，
 * 所以放在纯 JVM 测试里守住。
 */
class ButtonRevealGeometryTest {

    private val epsilon = 1e-3f

    @Test
    fun bottomCenter_buttonRadiusReachesBothTopCorners() {
        // 360x800 的竖屏，按钮位于底部居中、高 56dp。
        val width = 360f
        val height = 800f
        val centerX = width / 2f
        val centerY = height - 28f

        val radius = revealRadius(centerX, centerY, width, height)

        // 到左上角与右上角的距离相等，且就是需要覆盖的最远点之一。
        val toTopLeft = kotlin.math.hypot(centerX, centerY)
        val toTopRight = kotlin.math.hypot(width - centerX, centerY)
        assertEquals(toTopLeft, toTopRight, epsilon)
        assertEquals(toTopLeft, radius, epsilon)
        // 底部两角更近，半径绝不能由它们决定。
        assertTrue(radius >= kotlin.math.hypot(centerX, height - centerY))
    }

    @Test
    fun exactCenter_radiusIsHalfTheDiagonal() {
        val width = 400f
        val height = 600f
        val diagonal = kotlin.math.hypot(width, height)

        val radius = revealRadius(width / 2f, height / 2f, width, height)

        assertEquals(diagonal / 2f, radius, epsilon)
    }

    @Test
    fun topLeftCorner_radiusCoversFullPageDiagonal() {
        val width = 300f
        val height = 500f

        val radius = revealRadius(0f, 0f, width, height)

        assertEquals(kotlin.math.hypot(width, height), radius, epsilon)
    }

    @Test
    fun rightEdgeMiddle_radiusCoversOppositeCorners() {
        val width = 300f
        val height = 400f
        val radius = revealRadius(width, height / 2f, width, height)
        // 最远点是左侧两个角。
        assertEquals(kotlin.math.hypot(width, height / 2f), radius, epsilon)
    }

    @Test
    fun degenerateContainer_radiusIsZero() {
        // 首帧尺寸尚未测量时容器可能是 0，不能除零或返回 NaN。
        assertEquals(0f, revealRadius(0f, 0f, 0f, 0f), epsilon)
        assertEquals(0f, revealRadius(10f, 10f, -5f, 100f), epsilon)
    }

    @Test
    fun cornerRadiusInterpolation_hitsBothEndpoints() {
        val from = 28f
        val to = 400f

        assertEquals(from, lerpRevealCornerRadius(from, to, 0f), epsilon)
        assertEquals(to, lerpRevealCornerRadius(from, to, 1f), epsilon)
        assertEquals((from + to) / 2f, lerpRevealCornerRadius(from, to, 0.5f), epsilon)
    }

    @Test
    fun cornerRadiusInterpolation_clampsOutOfRangeProgress() {
        val from = 10f
        val to = 90f

        // 越界进度必须夹取，否则动画收尾时几何会反向跳变。
        assertEquals(from, lerpRevealCornerRadius(from, to, -0.5f), epsilon)
        assertEquals(to, lerpRevealCornerRadius(from, to, 2f), epsilon)
    }

    @Test
    fun onboardingFadeOut_reachesZeroAtContentStart() {
        assertEquals(1f, revealAlphaAt(0f), epsilon)
        assertEquals(0f, revealAlphaAt(0.42f), epsilon)
        // 之后保持全透明，不能出现负 alpha。
        assertEquals(0f, revealAlphaAt(1f), epsilon)
        assertEquals(0f, revealAlphaAt(5f), epsilon)
    }

    @Test
    fun contentFadeIn_startsAfterOnboardingFadesOut() {
        assertEquals(0f, contentAlphaAt(0f), epsilon)
        assertEquals(0f, contentAlphaAt(0.42f), epsilon)
        assertEquals(1f, contentAlphaAt(1f), epsilon)
        // 两条曲线在 0.42 交接：引导页已完全消失，内容才开始出现。
        val mid = 0.71f
        assertTrue(contentAlphaAt(mid) > 0f)
        assertTrue(revealAlphaAt(mid) == 0f)
        // 单调不减。
        assertTrue(contentAlphaAt(0.6f) <= contentAlphaAt(0.9f))
    }

    @Test
    fun grid_fadesInAndOutWithinItsWindow() {
        // 两端必须归零，否则展开开始/结束时网格会突兀出现或残留。
        assertEquals(0f, gridAlphaAt(0f), epsilon)
        assertEquals(0f, gridAlphaAt(0.34f), epsilon)
        assertEquals(0f, gridAlphaAt(1f), epsilon)
        // 窗口中部有可见值。
        assertTrue(gridAlphaAt(0.17f) > 0f)
    }

    @Test
    fun radiusGrowsMonotonicallyAcrossProgress() {
        val origin = RevealOriginFixture(180f, 772f, 328, 56)
        val width = 360f
        val height = 800f
        val start = minOf(origin.width, origin.height) / 2f
        val target = revealRadius(origin.centerX, origin.centerY, width, height)

        var previous = start
        for (step in 0..20) {
            val progress = step / 20f
            val current = lerpRevealCornerRadius(start, target, progress)
            // 半径只能变大：回缩会让覆盖面在屏幕上「倒吸」，产生闪动。
            assertTrue(current >= previous - epsilon)
            previous = current
        }
        assertEquals(target, previous, 0.05f)
    }

    /** 轻量夹具，避免在纯 JVM 测试里构造 Compose 的 Offset/IntSize。 */
    private data class RevealOriginFixture(
        val centerX: Float,
        val centerY: Float,
        val width: Int,
        val height: Int,
    )
}
