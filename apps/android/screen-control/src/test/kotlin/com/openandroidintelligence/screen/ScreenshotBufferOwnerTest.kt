package com.openandroidintelligence.screen

import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenshotBufferOwnerTest {
    @Test fun cancelledBeforeCallbackClosesLateBufferOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        request.cancel()
        capture.callbacks.runAll()
        runCurrent()
        assertTrue(request.isCompleted)

        val buffer = Buffer()
        capture.succeed(buffer)
        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
    }

    @Test fun cancellationBetweenActiveCheckAndResumeClosesOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        assertTrue(capture.continuation.isActive)
        request.cancel()
        val buffer = Buffer()
        capture.succeed(buffer)
        capture.callbacks.runAll()
        runCurrent()

        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
        assertFalse(capture.events.contains("resumed"))
    }

    @Test fun cancelledAfterResumeBeforeContinuationDispatchClosesOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        val buffer = Buffer()
        capture.succeed(buffer)
        assertEquals(1, capture.callbacks.size)
        assertEquals(0, buffer.closeCount)
        request.cancel()
        capture.callbacks.runAll()
        runCurrent()

        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
        assertFalse(capture.events.contains("resumed"))
    }

    @Test fun cancelledAfterResumeBeforeMainContextReturnsClosesOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        val buffer = Buffer()
        capture.succeed(buffer)
        capture.callbacks.runNext()
        assertTrue(capture.events.contains("resumed"))
        assertFalse(capture.events.contains("ready-to-encode"))
        request.cancel()
        runCurrent()

        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
        assertEquals(0, capture.encoding.size)
        assertFalse(capture.events.contains("encode-finally"))
    }

    @Test fun cancelledBeforeSwitchingToEncodingClosesOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        capture.beforeEncoding = { request.cancel() }
        val buffer = Buffer()
        deliverToEncoding(capture, buffer)

        assertTrue(capture.events.contains("ready-to-encode"))
        assertTrue(capture.events.contains("encode-finally"))
        assertEquals(0, capture.encoding.size)
        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
    }

    @Test fun cancelledAfterEncodingIsQueuedBeforeItStartsClosesOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        val buffer = Buffer()
        deliverToEncoding(capture, buffer)
        assertEquals(1, capture.encoding.size)
        assertEquals(0, buffer.closeCount)
        request.cancel()
        capture.encoding.runAll()
        runCurrent()

        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
        assertTrue(capture.events.contains("encode-finally"))
    }

    @Test fun cancelledDuringEncodingClosesAfterEncoderStops() = runTest {
        val capture = Capture().apply { suspendEncoding = true }
        val request = startCapture(capture)
        val buffer = Buffer()
        deliverToEncoding(capture, buffer)
        capture.encoding.runNext()
        assertEquals(1, buffer.readCount)
        assertEquals(0, buffer.closeCount)
        request.cancel()
        // 取消不应在编码协程退出前提前释放仍在使用的缓冲区。
        assertEquals(0, buffer.closeCount)
        capture.encoding.runAll()
        runCurrent()

        assertEquals(1, buffer.closeCount)
        assertTrue(capture.events.contains("encode-finally"))
    }

    @Test fun successfulEncodingKeepsBufferOpenUntilEncodeFinally() = runTest {
        val capture = Capture()
        startCapture(capture)
        val buffer = Buffer()
        deliverToEncoding(capture, buffer)
        assertEquals(0, buffer.closeCount)
        capture.encoding.runAll()
        assertEquals(0, buffer.closeCount)
        runCurrent()

        assertEquals("png", capture.result)
        assertEquals(1, buffer.readCount)
        assertEquals(1, buffer.closeCount)
        assertEquals(1, capture.events.count { it == "encode-finally" })
    }

    @Test fun encodingFailureStillClosesOnce() = runTest {
        val capture = Capture().apply { failEncoding = true }
        launch {
            try {
                capture.capture()
            } catch (failure: IllegalStateException) {
                capture.result = failure.message
            }
        }
        runCurrent()
        capture.callbacks.runNext()
        val buffer = Buffer()
        deliverToEncoding(capture, buffer)
        capture.encoding.runAll()
        runCurrent()

        assertEquals("encoding failed", capture.result)
        assertEquals(1, buffer.closeCount)
        assertEquals(1, buffer.readCount)
    }

    @Test fun timedOutRequestClosesLateCallbackBufferOnce() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        advanceTimeBy(1_001)
        capture.callbacks.runAll()
        runCurrent()
        assertTrue(request.isCompleted)
        assertEquals(null, capture.result)
        val buffer = Buffer()
        capture.succeed(buffer)

        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
    }

    @Test fun timeoutAfterContinuationResumesBeforeContextReturnsClosesOnce() = runTest {
        val capture = Capture().apply { afterResume = { advanceTimeBy(1_001) } }
        val request = startCapture(capture)
        val buffer = Buffer()
        capture.succeed(buffer)
        capture.callbacks.runNext()
        runCurrent()

        assertTrue(capture.events.contains("resumed"))
        assertTrue(request.isCompleted)
        assertEquals(null, capture.result)
        assertEquals(1, buffer.closeCount)
        assertEquals(0, buffer.readCount)
        assertEquals(0, capture.encoding.size)
        assertFalse(capture.events.contains("encode-finally"))
    }

    @Test fun synchronousSuccessDuringRequestRegistrationClosesOnce() = runTest {
        val buffer = Buffer()
        val capture = Capture().apply { synchronousBuffer = buffer }
        startCapture(capture)
        runCurrent()
        assertEquals(1, capture.encoding.size)
        capture.encoding.runAll()
        runCurrent()

        assertEquals("png", capture.result)
        assertEquals(1, buffer.closeCount)
        assertEquals(1, buffer.readCount)
    }

    @Test fun closeFailureDoesNotEscapeCancellationHandlerOrRetryClose() = runTest {
        val capture = Capture()
        val request = startCapture(capture)
        request.cancel()
        capture.callbacks.runAll()
        runCurrent()
        val buffer = Buffer().apply { failClose = true }
        capture.succeed(buffer)

        assertEquals(1, buffer.closeCount)
        assertTrue(request.isCancelled)
    }

    private fun TestScope.startCapture(capture: Capture): Job {
        val request = launch { capture.result = capture.capture() }
        runCurrent()
        capture.callbacks.runNext()
        return request
    }

    private fun TestScope.deliverToEncoding(capture: Capture, buffer: Buffer) {
        capture.succeed(buffer)
        capture.callbacks.runNext()
        runCurrent()
    }

    /** 与系统壳相同的真实挂起、Main 返回、超时和 IO 切换；仅把 Android 缓冲区换成 Closeable。 */
    private class Capture {
        val callbacks = QueuedDispatcher()
        val encoding = QueuedDispatcher()
        val events = mutableListOf<String>()
        lateinit var continuation: CancellableContinuation<Buffer>
        lateinit var succeed: (Buffer) -> Unit
        var afterResume: (() -> Unit)? = null
        var beforeEncoding: (() -> Unit)? = null
        var synchronousBuffer: Buffer? = null
        var suspendEncoding = false
        var failEncoding = false
        var result: String? = "pending"

        suspend fun capture(): String? = ScreenshotBufferOwner().use { owner ->
            val buffer = withTimeoutOrNull(1_000) {
                withContext(callbacks) {
                    val received = suspendCancellableCoroutine<Buffer> { waiting ->
                        continuation = waiting
                        succeed = { frame -> owner.resume(waiting, frame, frame) }
                        synchronousBuffer?.let { succeed(it) }
                    }
                    events += "resumed"
                    afterResume?.invoke()
                    received
                }
            } ?: return@use null
            events += "ready-to-encode"
            beforeEncoding?.invoke()
            try {
                withContext(encoding) {
                    buffer.read()
                    if (suspendEncoding) awaitCancellation()
                    check(!failEncoding) { "encoding failed" }
                    "png"
                }
            } finally {
                events += "encode-finally"
                owner.close()
            }
        }
    }

    private class Buffer : Closeable {
        var closeCount = 0
        var readCount = 0
        var failClose = false

        fun read() {
            check(closeCount == 0) { "buffer already closed" }
            readCount++
        }

        override fun close() {
            closeCount++
            check(closeCount == 1) { "buffer closed twice" }
            check(!failClose) { "close failed" }
        }
    }

    /** 手工放行调度队列，逐段停在实际协程边界；不依赖线程竞争、sleep 或 Android 图形驱动。 */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "no pending coroutine" }
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }
}
