package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.GenerationSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RemoteGenerationStateTest {
    @Test fun `旧轮次捕获与发布持有同一锁不能越过新轮次发布`() {
        val source = RemoteGenerationState()
        val current = AtomicReference(GenerationSnapshot("G1", GenerationState.RUNNING, "conversation"))
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>()
        val first = thread(start = true) {
            try { source.publish {
                val old = current.get()
                captured.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                old
            } } catch (failure: Throwable) { failures.set(failure) }
        }
        assertTrue(captured.await(5, TimeUnit.SECONDS))
        current.set(GenerationSnapshot("G2", GenerationState.RUNNING, "conversation"))
        val second = thread(start = true) {
            try {
                attempted.countDown()
                source.publish { current.get() }
            } catch (failure: Throwable) { failures.set(failure) }
        }
        try {
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            awaitPublicationBlocked(second)
        } finally { release.countDown(); first.join(5_000); second.join(5_000) }
        assertFalse(first.isAlive); assertFalse(second.isAlive)
        failures.get()?.let { throw it }
        assertEquals(current.get(), source.snapshot.value)
        assertEquals("G2", source.generationId.value)
    }

    @Test fun `关闭后迟到发布不能恢复旧轮次`() {
        val source = RemoteGenerationState()
        source.publish { GenerationSnapshot("G1", GenerationState.RUNNING, "conversation") }
        source.close()
        source.publish { fail("关闭后不能重新读取旧投影"); GenerationSnapshot("G1", GenerationState.RUNNING) }
        assertEquals(GenerationSnapshot(null, GenerationState.IDLE), source.snapshot.value)
    }
}

/** 等待真实监视器竞争，完成或超时均失败；不靠睡眠创造时序。 */
internal fun awaitPublicationBlocked(worker: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
    assertEquals("后续发布必须等待读取中的原发布", Thread.State.BLOCKED, worker.state)
}
