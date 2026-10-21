package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大图预览的纯几何契约。
 *
 * 这些函数决定「图片能放大到多少、能拖到哪里、双击去哪、松手停在哪」；
 * 把它们从手势代码里拆出来，就能用普通 JVM 测试覆盖全部边界，而不是靠
 * 真机手感发现越界或回弹错误。
 */
class ImagePreviewGeometryTest {

    private val viewport = Size(1000f, 1000f)

    @Test
    fun scaleIsClampedBetweenFitAndFiveTimes() {
        assertEquals(ImageViewerGeometry.MinScale, ImageViewerGeometry.clampScale(0.2f), 0.0001f)
        assertEquals(ImageViewerGeometry.MaxScale, ImageViewerGeometry.clampScale(9f), 0.0001f)
        assertEquals(2.5f, ImageViewerGeometry.clampScale(2.5f), 0.0001f)
        assertEquals("NaN 不能算出不可见的界面", ImageViewerGeometry.MinScale, ImageViewerGeometry.clampScale(Float.NaN), 0.0001f)
        assertEquals(ImageViewerGeometry.MinScale, ImageViewerGeometry.clampScale(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun fittedContentSizeKeepsAspectRatioInsideTheViewport() {
        val wide = ImageViewerGeometry.fittedContentSize(Size(1000f, 500f), Size(200f, 100f))
        assertEquals(1000f, wide.width, 0.001f)
        assertEquals(500f, wide.height, 0.001f)

        val tall = ImageViewerGeometry.fittedContentSize(Size(1000f, 500f), Size(100f, 400f))
        assertEquals(125f, tall.width, 0.001f)
        assertEquals(500f, tall.height, 0.001f)

        assertEquals(Size.Zero, ImageViewerGeometry.fittedContentSize(Size.Zero, Size(10f, 10f)))
        assertEquals(Size.Zero, ImageViewerGeometry.fittedContentSize(viewport, Size.Zero))
    }

    @Test
    fun contentThatStillFitsCannotBePannedAway() {
        val content = Size(600f, 400f)
        assertEquals(Offset.Zero, ImageViewerGeometry.clampOffset(Offset(300f, 300f), viewport, content, 1f))
        assertEquals(Offset.Zero, ImageViewerGeometry.maxOffset(viewport, content, 1f))
    }

    @Test
    fun panningIsBoundedByTheScaledContentEdges() {
        val content = Size(800f, 400f)
        // 放大 2 倍：1600x800 的图片比 1000x1000 的视口宽，也只能横向移动 300px。
        val limit = ImageViewerGeometry.maxOffset(viewport, content, 2f)
        assertEquals(300f, limit.x, 0.001f)
        assertEquals(0f, limit.y, 0.001f)

        val clamped = ImageViewerGeometry.clampOffset(Offset(999f, -999f), viewport, content, 2f)
        assertEquals(300f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndTwoAndAHalfTimes() {
        assertEquals(ImageViewerGeometry.DoubleTapScale, ImageViewerGeometry.doubleTapTarget(1f), 0.001f)
        // 浮点误差不能让双击在 1x 附近反复判定成「已放大」。
        assertEquals(ImageViewerGeometry.DoubleTapScale, ImageViewerGeometry.doubleTapTarget(1.005f), 0.001f)
        assertEquals(ImageViewerGeometry.MinScale, ImageViewerGeometry.doubleTapTarget(2.5f), 0.001f)
        assertEquals(ImageViewerGeometry.MinScale, ImageViewerGeometry.doubleTapTarget(5f), 0.001f)
    }

    @Test
    fun zoomKeepsThePinchCentreFixed() {
        val centre = Offset(500f, 500f)
        // 在视口右侧 300px 处放大 2 倍：图片必须向左补偿，让焦点停在指下。
        val zoomed = ImageViewerGeometry.zoomedOffset(
            currentOffset = Offset.Zero,
            centroid = Offset(800f, 500f),
            viewportCenter = centre,
            scaleFactor = 2f,
        )
        assertEquals(-300f, zoomed.x, 0.001f)
        assertEquals(0f, zoomed.y, 0.001f)
    }

    @Test
    fun releaseProjectsVelocityIntoBoundariesWhenMotionIsAllowed() {
        val content = Size(1000f, 1000f)
        // 2 倍时横向可移动 500px；4000px/s 的初速度投影远超边界，必须钳在边界上。
        val flungRight = ImageViewerGeometry.releaseTarget(
            offset = Offset.Zero,
            velocity = Offset(4000f, 0f),
            viewport = viewport,
            content = content,
            scale = 2f,
            reduceMotion = false,
        )
        assertEquals(500f, flungRight.x, 0.001f)

        val flungLeft = ImageViewerGeometry.releaseTarget(
            offset = Offset.Zero,
            velocity = Offset(-4000f, 0f),
            viewport = viewport,
            content = content,
            scale = 2f,
            reduceMotion = false,
        )
        assertEquals(-500f, flungLeft.x, 0.001f)
    }

    @Test
    fun releaseWithReducedMotionSnapsToTheNearestLegalOffset() {
        val content = Size(1000f, 1000f)
        // 越界状态在减少动态下直接吸附回边界，不保留任何继续运动的距离。
        val snapped = ImageViewerGeometry.releaseTarget(
            offset = Offset(900f, -900f),
            velocity = Offset(4000f, -4000f),
            viewport = viewport,
            content = content,
            scale = 2f,
            reduceMotion = true,
        )
        assertEquals(500f, snapped.x, 0.001f)
        assertEquals(-500f, snapped.y, 0.001f)

        val centred = ImageViewerGeometry.releaseTarget(
            offset = Offset.Zero,
            velocity = Offset(4000f, 0f),
            viewport = viewport,
            content = content,
            scale = ImageViewerGeometry.MinScale,
            reduceMotion = true,
        )
        assertEquals("1x 时只能居中", Offset.Zero, centred)
    }

    @Test
    fun degenerateViewportsNeverProduceNonFiniteOffsets() {
        val empty = ImageViewerGeometry.clampOffset(Offset(10f, 10f), Size.Zero, Size(100f, 100f), 3f)
        assertEquals(Offset.Zero, empty)
        val zeroContent = ImageViewerGeometry.releaseTarget(
            offset = Offset(10f, 10f),
            velocity = Offset(1000f, 1000f),
            viewport = viewport,
            content = Size.Zero,
            scale = 1f,
            reduceMotion = false,
        )
        assertEquals(Offset.Zero, zeroContent)
        assertTrue(zeroContent.x.isFinite() && zeroContent.y.isFinite())
    }
}
