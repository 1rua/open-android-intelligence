/* 无障碍截图缓冲区的单次交接与释放；不依赖 Android，便于验证真实取消时序。 */
package com.openandroidintelligence.screen

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation

/**
 * 每次截图请求只登记一次成功缓冲区。
 *
 * 回调先登记再 resume；尚未恢复时由 resume 的取消回调释放。
 * 恢复后，外层 use 覆盖 Main/超时上下文返回及进入编码调度前的取消；
 * 编码的 finally 覆盖 IO 入口、编码失败和正常结束。
 * 所有清理共用原子取走操作，避免 finally 和取消回调重复关闭同一缓冲区。
 */
internal class ScreenshotBufferOwner : AutoCloseable {
    private val buffer = AtomicReference<AutoCloseable?>()

    fun <T> resume(continuation: CancellableContinuation<T>, value: T, buffer: AutoCloseable) {
        this.buffer.set(buffer)
        continuation.resume(value) { _, _, _ -> close() }
    }

    override fun close() {
        // close 也可能由任意线程上的取消回调调用；释放异常不能逃出该回调。
        buffer.getAndSet(null)?.let { owned -> runCatching { owned.close() } }
    }
}
