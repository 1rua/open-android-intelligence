package com.openandroidintelligence.gateway.remote.network

import android.util.Log
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 原生方法可能同步等待另一个线程的回调。生命周期锁只保护准入和在途计数。 */
internal class RemoteRtcCallbackBoundary(
    private val cleanup: () -> Unit,
    private val onClosing: () -> Unit,
    private val executor: Executor,
) {
    private val lifecycle = Any()
    private var inFlight = 0
    @Volatile var isClosed = false
        private set

    fun callback(action: () -> Unit): Boolean {
        if (!enter()) return false
        runCallback(action)
        return true
    }

    /** 接收回调移交原生句柄后，等待异步接收结束才释放 PeerConnection。 */
    fun dispatchCallback(dispatcher: Executor, action: () -> Unit): Boolean {
        if (!enter()) return false
        try {
            dispatcher.execute { runCallback(action) }
        } catch (_: RejectedExecutionException) {
            try { close() } finally { leave() }
            return false
        }
        return true
    }

    private fun runCallback(action: () -> Unit) {
        try { action() }
        catch (_: Exception) { close() }
        finally { leave() }
    }

    fun <T> native(action: () -> T): T {
        if (!enter()) throw RemoteRpcException("RTC_CLOSED")
        try { return action() }
        catch (failure: Exception) { close(); throw failure }
        finally { leave() }
    }

    fun close() {
        synchronized(lifecycle) {
            if (isClosed) return
            isClosed = true
            // 关闭通知也占一个在途名额，确保清理发生在通知结束后。
            inFlight++
        }
        try { onClosing() }
        catch (_: Exception) { Log.e("RemoteRtcPeer", "RTC_CLOSE_NOTIFICATION_FAILED") }
        finally { leave() }
    }

    private fun enter(): Boolean = synchronized(lifecycle) {
        if (isClosed) return@synchronized false
        inFlight++
        true
    }

    private fun leave() {
        val scheduleCleanup = synchronized(lifecycle) {
            inFlight--
            isClosed && inFlight == 0
        }
        if (scheduleCleanup) executor.execute {
            try { cleanup() }
            catch (_: Exception) { Log.e("RemoteRtcPeer", "RTC_CLEANUP_FAILED") }
        }
    }
}
