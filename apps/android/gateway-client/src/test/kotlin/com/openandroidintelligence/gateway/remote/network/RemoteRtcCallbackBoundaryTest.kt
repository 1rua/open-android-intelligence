package com.openandroidintelligence.gateway.remote.network

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RemoteRtcCallbackBoundaryTest {
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
        val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val closing = AtomicInteger(); val disposed = AtomicInteger()
        val boundary = RemoteRtcCallbackBoundary({ disposed.incrementAndGet() }, { closing.incrementAndGet() }, Executor { pending.add(it) })
        val threads = (1..12).map { Thread { boundary.close() }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(1, closing.get()); assertEquals(1, pending.size)
        pending.single().run(); assertEquals(1, disposed.get())
    }

    @Test fun 接收通道尚未发布时关闭必须等待发布后再清理() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val publish = java.util.concurrent.CountDownLatch(1)
        val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var published = false
        var disposed = false
        val boundary = RemoteRtcCallbackBoundary({ assertTrue(published); disposed = true }, {}, Executor { pending.add(it) })
        val adopter = Thread { boundary.callback { entered.countDown(); check(publish.await(3, java.util.concurrent.TimeUnit.SECONDS)); published = true } }
        adopter.start(); assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        val closer = Thread { boundary.close() }.apply { start() }
        assertTrue(pending.isEmpty())
        publish.countDown(); adopter.join(3000); closer.join(3000)
        assertFalse(adopter.isAlive); assertFalse(closer.isAlive)
        assertEquals(1, pending.size); pending.single().run(); assertTrue(disposed)
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
