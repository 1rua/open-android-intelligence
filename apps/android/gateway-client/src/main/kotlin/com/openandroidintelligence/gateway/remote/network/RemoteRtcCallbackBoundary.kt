package com.openandroidintelligence.gateway.remote.network

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException

/** JNI 回调只标记失败并排队清理；PeerConnection.dispose 不能在其 observer 回调栈内调用。 */
internal class RemoteRtcCallbackBoundary(
    private val cleanup: () -> Unit,
    private val onClosing: () -> Unit,
    private val executor: Executor = cleanupExecutor,
) {
    private val closed = AtomicBoolean(false)
    private val lifecycle = Any()
    val isClosed: Boolean get() = closed.get()

    fun callback(action: () -> Unit): Boolean = synchronized(lifecycle) {
        if (closed.get()) return@synchronized false
        try { action() } catch (_: Exception) { close() }
        true
    }

    /** 同步调用保留原异常；不能把创建失败伪装成已成功进入 SDK。锁只覆盖调用，不跨异步等待。 */
    fun <T> native(action: () -> T): T = synchronized(lifecycle) {
        if (closed.get()) throw RemoteRpcException("RTC_CLOSED")
        try { action() } catch (failure: Exception) { close(); throw failure }
    }

    fun close(): Unit = synchronized(lifecycle) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        // 等待者立即失败；所有 native dispose 移到独立执行器，且只提交一次。
        try { onClosing() } catch (_: Exception) {
            android.util.Log.e("RemoteRtcPeer", "RTC_CLOSE_NOTIFICATION_FAILED")
        } finally { executor.execute {
            // 只用锁等待在途接收/发布结束；执行 native close 时不持锁，避免其回调反向等锁。
            synchronized(lifecycle) { }
            try { cleanup() } catch (_: Exception) {
                android.util.Log.e("RemoteRtcPeer", "RTC_CLEANUP_FAILED")
            }
        } }
        Unit
    }

    companion object {
        private val cleanupExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "oai-remote-peer-cleanup").apply { isDaemon = true }
        }
    }
}
