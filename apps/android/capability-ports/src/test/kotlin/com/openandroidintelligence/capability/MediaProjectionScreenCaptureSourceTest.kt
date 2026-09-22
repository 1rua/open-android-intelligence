package com.openandroidintelligence.capability

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 屏幕采集源的授权与会话编排测试。
 *
 * 依据任务铁律，MediaProjection 这类依赖系统服务的能力，测试落在接口与
 * 决策逻辑（seam）上：[ScreenProjectionRuntime]/[ProjectionDisplaySession]
 * 由 fake 供给，不驱动真实投屏。真实系统壳 [MediaProjectionRuntime] 与
 * 前台服务只做系统 API 的薄封装，交由设备验证（剩余项）。
 *
 * 这些测试钉住的是 fail-closed 矩阵：未经系统对话框显式授权时明确不可用；
 * 授权被拒/会话建立失败/会话被系统回收/截帧失败一律返回 null 或 false，
 * 绝不抛异常冒充成功、绝不静默重试、绝不伪造截图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaProjectionScreenCaptureSourceTest {

    private val authorizedIntent = Intent("com.openandroidintelligence.test.SCREEN_CAPTURE_AUTH")

    private class FakeSession(
        val frame: ScreenCapture? = null,
        startActive: Boolean = true,
    ) : ProjectionDisplaySession {
        @Volatile var active: Boolean = startActive
        var grabCalls: Int = 0
            private set
        var closed: Boolean = false
            private set

        override val isActive: Boolean
            get() = active && !closed

        override fun grabFrame(): ScreenCapture? {
            grabCalls++
            return if (isActive) frame else null
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeRuntime(
        private val serviceAvailable: Boolean = true,
        private val intent: Intent? = Intent("com.openandroidintelligence.test.SCREEN_CAPTURE_AUTH"),
        var session: FakeSession? = null,
    ) : ScreenProjectionRuntime {
        var openCalls: Int = 0
            private set
        var lastResultCode: Int? = null
            private set
        var lastResultData: Intent? = null
            private set

        override fun hasProjectionService(): Boolean = serviceAvailable

        override fun createAuthorizationIntent(): Intent? = intent

        override fun openSession(resultCode: Int, resultData: Intent?): ProjectionDisplaySession? {
            openCalls++
            lastResultCode = resultCode
            lastResultData = resultData
            return session
        }

        override fun close() {}
    }

    private fun frame(width: Int = 1080, height: Int = 2400): ScreenCapture =
        ScreenCapture.copyOf(PNG_MAGIC_BYTES, width, height)

    private companion object {
        val PNG_MAGIC_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }

    // ------------------------------------------------------------------
    // 未授权基线：明确不可用
    // ------------------------------------------------------------------

    @Test
    fun isAvailable_isFalseBeforeAnyAuthorization() {
        val source = MediaProjectionScreenCaptureSource(FakeRuntime())
        assertFalse(source.isAvailable)
    }

    @Test
    fun capture_beforeAuthorization_returnsNullWithoutOpeningSession() = runBlocking {
        val runtime = FakeRuntime()
        val source = MediaProjectionScreenCaptureSource(runtime)
        assertNull(source.capture())
        assertEquals(0, runtime.openCalls)
    }

    // ------------------------------------------------------------------
    // 授权请求：由宿主 Activity 发起，来源缺失时没有可发起的东西
    // ------------------------------------------------------------------

    @Test
    fun createAuthorizationIntent_returnsNullWhenProjectionServiceMissing() {
        val source = MediaProjectionScreenCaptureSource(FakeRuntime(serviceAvailable = false))
        assertNull(source.createAuthorizationIntent())
    }

    @Test
    fun createAuthorizationIntent_delegatesToRuntimeWhenServicePresent() {
        val runtime = FakeRuntime(intent = authorizedIntent)
        val source = MediaProjectionScreenCaptureSource(runtime)
        assertSame(authorizedIntent, source.createAuthorizationIntent())
    }

    // ------------------------------------------------------------------
    // 授权结果：fail-closed 矩阵
    // ------------------------------------------------------------------

    @Test
    fun onAuthorizationResult_rejectedByUser_failsClosed() = runBlocking {
        val runtime = FakeRuntime(session = FakeSession(frame()))
        val source = MediaProjectionScreenCaptureSource(runtime)
        assertFalse(source.onAuthorizationResult(Activity.RESULT_CANCELED, authorizedIntent))
        assertFalse(source.isAvailable)
        assertEquals(0, runtime.openCalls)
        assertNull(source.capture())
    }

    @Test
    fun onAuthorizationResult_nullResultData_failsClosed() = runBlocking {
        val runtime = FakeRuntime(session = FakeSession(frame()))
        val source = MediaProjectionScreenCaptureSource(runtime)
        assertFalse(source.onAuthorizationResult(Activity.RESULT_OK, null))
        assertFalse(source.isAvailable)
        assertEquals(0, runtime.openCalls)
        assertNull(source.capture())
    }

    @Test
    fun onAuthorizationResult_systemSessionFails_failsClosed() = runBlocking {
        val runtime = FakeRuntime(session = null)
        val source = MediaProjectionScreenCaptureSource(runtime)
        assertFalse(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))
        assertFalse(source.isAvailable)
        assertNull(source.capture())
    }

    // ------------------------------------------------------------------
    // 授权成功：能力激活、意图如实转交、帧来自真实会话
    // ------------------------------------------------------------------

    @Test
    fun onAuthorizationResult_granted_activatesSourceAndDelegatesResult() = runBlocking {
        val session = FakeSession(frame())
        val runtime = FakeRuntime(session = session)
        val source = MediaProjectionScreenCaptureSource(runtime)

        assertTrue(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))
        assertTrue(source.isAvailable)
        assertEquals(1, runtime.openCalls)
        assertEquals(Activity.RESULT_OK, runtime.lastResultCode)
        assertSame(authorizedIntent, runtime.lastResultData)
        // capture() 交付的是会话里的那一帧，不是会话对象本身
        assertSame(session.frame, source.capture())
    }

    @Test
    fun capture_afterSystemRevocation_returnsNullWithoutGrabbing() = runBlocking {
        val session = FakeSession(frame())
        val source = MediaProjectionScreenCaptureSource(FakeRuntime(session = session))
        assertTrue(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))

        // 系统回收投屏会话（MediaProjection.Callback.onStop 的等价物）
        session.active = false

        assertFalse(source.isAvailable)
        assertNull(source.capture())
        assertEquals(0, session.grabCalls)
    }

    @Test
    fun capture_whenFrameGrabFails_returnsNullWithoutPretendingSuccess() = runBlocking {
        val session = FakeSession(frame = null)
        val source = MediaProjectionScreenCaptureSource(FakeRuntime(session = session))
        assertTrue(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))
        assertNull(source.capture())
        // 能力本身仍在线；这一帧没有拿到，不能拿上一帧或假帧充数
        assertTrue(source.isAvailable)
        assertEquals(1, session.grabCalls)
    }

    @Test
    fun onAuthorizationResult_reAuthorization_closesPreviousSessionBeforeTakingOver() = runBlocking {
        val first = FakeSession(frame())
        val second = FakeSession(frame(width = 640, height = 480))
        val runtime = FakeRuntime(session = first)
        val source = MediaProjectionScreenCaptureSource(runtime)
        assertTrue(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))

        runtime.session = second
        assertTrue(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))

        assertTrue(first.closed)
        assertFalse(second.closed)
        assertSame(second.frame, source.capture())
    }

    // ------------------------------------------------------------------
    // 释放：会话资源必须释放，能力随之失效，且幂等
    // ------------------------------------------------------------------

    @Test
    fun release_closesSessionAndFailsClosed() = runBlocking {
        val session = FakeSession(frame())
        val source = MediaProjectionScreenCaptureSource(FakeRuntime(session = session))
        assertTrue(source.onAuthorizationResult(Activity.RESULT_OK, authorizedIntent))

        source.release()

        assertFalse(source.isAvailable)
        assertNull(source.capture())
        assertTrue(session.closed)
    }

    @Test
    fun release_isIdempotentEvenWithoutSession() {
        val source = MediaProjectionScreenCaptureSource(FakeRuntime())
        source.release()
        source.release()
        assertFalse(source.isAvailable)
    }

    // ------------------------------------------------------------------
    // 截图值对象的 owned-copy 边界
    // ------------------------------------------------------------------

    @Test
    fun screenCapture_copyOf_rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCapture.copyOf(byteArrayOf(1), 0, 2400)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCapture.copyOf(byteArrayOf(1), 1080, -1)
        }
    }

    @Test
    fun screenCapture_copyOf_rejectsEmptyBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCapture.copyOf(byteArrayOf(), 1080, 2400)
        }
    }

    @Test
    fun screenCapture_pngBytes_isDefensiveCopy() {
        val original = byteArrayOf(1, 2, 3)
        val capture = ScreenCapture.copyOf(original, 8, 6)
        val copy = capture.pngBytes()
        copy[0] = 0x7F
        assertNotSame(copy, capture.pngBytes())
        assertEquals(1.toByte(), capture.pngBytes()[0])
    }
}
