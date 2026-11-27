package com.openandroidintelligence.gateway.remote.network

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RemoteRtcCallbackBoundaryTest {
    @Test fun SDK等待另一线程回调时回调能先于原生调用完成() {
        val boundary = RemoteRtcCallbackBoundary({}, {}, Executor { it.run() })
        val callbackEntered = CountDownLatch(1)
        val callbackCompleted = CountDownLatch(1)
        lateinit var callbackThread: Thread
        try {
            boundary.native {
                callbackThread = thread(isDaemon = true, name = "模拟 WebRTC 回调") {
                    callbackEntered.countDown()
                    boundary.callback { callbackCompleted.countDown() }
                }
                assertTrue("原生调用期间已触发回调", callbackEntered.await(2, TimeUnit.SECONDS))
                assertTrue("原生调用返回前回调必须完成", callbackCompleted.await(1, TimeUnit.SECONDS))
            }
        } finally {
            callbackThread.join(2_000)
        }
        assertFalse(callbackThread.isAlive)
    }

    @Test fun 原生回调中失败只排队且退出回调后清理一次() {
        val pending = mutableListOf<Runnable>()
        var callbackActive = false
        var waitingFailed = false
        var disposed = 0
        val boundary = RemoteRtcCallbackBoundary({ assertFalse(callbackActive); disposed++ },
            { waitingFailed = true }, Executor { pending += it })
        callbackActive = true
        boundary.callback { throw IllegalStateException("模拟控制连接关闭后的信令失败") }
        assertTrue(waitingFailed); assertTrue(boundary.isClosed); assertEquals(0, disposed)
        callbackActive = false
        boundary.close(); boundary.callback { fail("关闭后不得继续发送原生信令") }
        assertEquals(1, pending.size)
        pending.single().run()
        assertEquals(1, disposed)
    }

    @Test fun 多个关闭与ICE失败竞争只产生一个清理任务() {
        val pending = ConcurrentLinkedQueue<Runnable>()
        val closing = AtomicInteger(); val disposed = AtomicInteger()
        val boundary = RemoteRtcCallbackBoundary({ disposed.incrementAndGet() }, { closing.incrementAndGet() }, Executor { pending.add(it) })
        val threads = (1..12).map { Thread { boundary.close() }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(1, closing.get()); assertEquals(1, pending.size)
        pending.single().run(); assertEquals(1, disposed.get())
    }

    @Test fun 关闭立即通知且原生清理等待在途回调退出() {
        val entered = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val notified = CountDownLatch(1)
        val pending = ConcurrentLinkedQueue<Runnable>()
        var published = false
        var disposed = false
        val boundary = RemoteRtcCallbackBoundary(
            { assertTrue(published); disposed = true }, { notified.countDown() }, Executor { pending.add(it) },
        )
        val adopter = thread(isDaemon = true) {
            boundary.callback {
                entered.countDown()
                check(publish.await(5, TimeUnit.SECONDS))
                published = true
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread(isDaemon = true) { boundary.close() }
        try {
            assertTrue("关闭通知不等待通道接收", notified.await(2, TimeUnit.SECONDS))
            assertFalse(boundary.callback { fail("关闭后不允许新回调") })
            assertTrue(pending.isEmpty())
        } finally {
            publish.countDown()
            adopter.join(2_000)
            closer.join(2_000)
        }
        assertFalse(adopter.isAlive)
        assertFalse(closer.isAlive)
        pending.single().run()
        assertTrue(disposed)
    }

    @Test fun 关闭等待已经移交的异步通道并拒绝迟到回调() {
        val adoption = mutableListOf<Runnable>()
        val cleanup = mutableListOf<Runnable>()
        var completed = false
        val boundary = RemoteRtcCallbackBoundary({ assertTrue(completed) }, {}, Executor { cleanup += it })
        val dispatcher = Executor { adoption += it }
        assertTrue(boundary.dispatchCallback(dispatcher) { completed = true })
        boundary.close()
        assertFalse(boundary.dispatchCallback(dispatcher) { fail("迟到通道不得进入已关闭的连接") })
        assertTrue(cleanup.isEmpty())
        adoption.single().run()
        cleanup.single().run()
    }

    @Test fun 主动创建与发布受同一门保护且关闭后不再创建原生通道() {
        val pending = mutableListOf<Runnable>()
        val order = mutableListOf<String>()
        lateinit var boundary: RemoteRtcCallbackBoundary
        boundary = RemoteRtcCallbackBoundary({ order += "dispose" }, {}, Executor { pending += it })
        boundary.native {
            order += "create"
            boundary.close()
            order += "publish"
        }
        assertEquals(listOf("create", "publish"), order)
        pending.single().run()
        assertEquals(listOf("create", "publish", "dispose"), order)
        assertFalse(boundary.callback { fail("关闭后不得创建新通道") })
    }

    @Test fun 同步创建失败保留异常并拒绝后续Offer和Answer入口() {
        val pending = mutableListOf<Runnable>()
        var cleanup = 0
        var subsequentCalls = 0
        val failure = IllegalStateException("模拟通道创建失败")
        val boundary = RemoteRtcCallbackBoundary({ cleanup++ }, {}, Executor { pending += it })
        val seen = assertThrows(IllegalStateException::class.java) {
            boundary.native<Unit> { throw failure }
            boundary.native { subsequentCalls++ }
        }
        assertSame(failure, seen)
        for (operation in listOf("createOffer", "createAnswer", "setLocalDescription", "setRemoteDescription", "addIceCandidate", "getStats")) {
            assertThrows(com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException::class.java) {
                boundary.native { subsequentCalls++; operation }
            }
        }
        assertEquals(0, subsequentCalls)
        boundary.close(); assertEquals(1, pending.size)
        pending.single().run(); assertEquals(1, cleanup)
    }
}
