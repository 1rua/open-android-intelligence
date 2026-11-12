package com.openandroidintelligence.mobile.conversations

import android.app.Application
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用真实双镜像句柄复现 checkpoint 与事件提交争用 SQLite 写锁。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeMirrorScopeTransactionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 23 }, "AES")
        override fun delete() = Unit
    }
    private val thread = ConversationId("synthetic-chat")
    private fun scope() = ConversationScope("合成配置", "https://synthetic.invalid", UUID.randomUUID().toString(), "合成安装")
    private fun mirror(scope: ConversationScope) = NativeConversationMirror(context, scope, { "合成Agent" }, key)
    private fun checkpoint(draft: String) = WorkbenchCheckpoint(
        listOf(ConversationSummary(thread, "合成会话", 1)), thread.value, "合成会话", emptyList(),
        emptyMap(), draft, 1, emptyList(), null, emptySet(), emptySet())
    private fun event(id: String) = VerifiedConversationEvent.TimelineUpsert(id, 2, 1,
        TimelineMessage(id, "assistant", listOf(MessagePart.Text("合成回复")), 2, "CONFIRMED",
            conversationId = thread, contentRevision = 1))
    private fun records(mirror: NativeConversationMirror): SqliteConversationRecords {
        val real = mirror.offlineMirror()
        return EncryptedConversationMirror::class.java.getDeclaredField("documents").apply { isAccessible = true }
            .get(real) as SqliteConversationRecords
    }
    private fun shortenBusyWait(records: SqliteConversationRecords) {
        val helper = SqliteConversationRecords::class.java.getDeclaredField("helper").apply { isAccessible = true }
            .get(records) as SQLiteOpenHelper
        helper.writableDatabase.rawQuery("PRAGMA busy_timeout=50", null).use { it.moveToFirst() }
    }
    private fun databaseLock(records: SqliteConversationRecords): Any =
        SqliteConversationRecords::class.java.getDeclaredField("databaseLock").apply { isAccessible = true }
            .get(records) as Any

    @Test fun checkpointAndEventCommitAcrossHandlesWaitForOneScope() = runBlocking {
        overlappingWrites(checkpointFirst = true)
    }

    @Test fun eventCommitAndCheckpointAcrossHandlesWaitForOneScope() = runBlocking {
        overlappingWrites(checkpointFirst = false)
    }

    @Test fun differentDatabasesKeepIndependentTransactions() = runBlocking<Unit> {
        val first = mirror(scope())
        val second = mirror(scope())
        val workers = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            first.saveCheckpoint(checkpoint("首个范围"))
            second.saveCheckpoint(checkpoint("第二个范围"))
            val owner = workers.submit {
                records(first).transaction {
                    held.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "合成事务释放超时" }
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val independent = workers.submit {
                second.offlineMirror().saveNativeCheckpoint(checkpoint("异范围无需等待"))
            }
            independent.get(1, TimeUnit.SECONDS)
            assertEquals("异范围无需等待", second.load()!!.draft)
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            first.closeStore()
            second.closeStore()
        }
    }

    @Test fun rollbackReleasesScopeAndAnotherHandlePersists() = runBlocking {
        val account = scope()
        val first = mirror(account)
        val second = mirror(account)
        val workers = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val expected = IllegalStateException("合成事务回滚")
        val failures = CopyOnWriteArrayList<Throwable>()
        try {
            first.saveCheckpoint(checkpoint("回滚前草稿"))
            second.load()
            val owner = workers.submit {
                try {
                    records(first).transaction {
                        records(first).transaction {
                            first.offlineMirror().saveNativeCheckpoint(checkpoint("不应提交的草稿"))
                        }
                        held.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "合成事务释放超时" }
                        throw expected
                    }
                } catch (failure: Throwable) { failures += failure }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val contender = workers.submit {
                attempted.countDown()
                second.offlineMirror().commitNativeEvents(listOf(event("after-rollback")))
            }
            assertTrue(attempted.await(1, TimeUnit.SECONDS))
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            contender.get(5, TimeUnit.SECONDS)
            assertEquals("真实事务失败必须传播，不能吞掉或转成成功", listOf(expected), failures.toList())
            first.closeStore()
            first.closeStore()
            assertEquals("回滚前草稿", second.load()!!.draft)
            assertEquals("after-rollback", second.loadTimeline(thread.value)!!.messages.single().id)
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            first.closeStore()
            second.closeStore()
        }
    }

    @Test fun openingAndClosingAnotherHandleWaitsWithoutDeadlock() = runBlocking {
        val account = scope()
        val first = mirror(account)
        val second = mirror(account)
        val workers = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opening = CountDownLatch(1)
        val completed = CountDownLatch(1)
        try {
            first.saveCheckpoint(checkpoint("关闭前草稿"))
            second.load()
            val owner = workers.submit {
                synchronized(databaseLock(records(first))) {
                    first.offlineMirror().commitNativeEvents(listOf(event("before-close")))
                    held.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "合成事务释放超时" }
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val lifecycle = workers.submit {
                opening.countDown()
                second.close()
                second.close()
                val reopened = mirror(account)
                try {
                    reopened.offlineMirror().saveNativeCheckpoint(checkpoint("重开后草稿"))
                    assertEquals("before-close", reopened.offlineMirror().timeline(thread.value)!!.messages.single().id)
                } finally { reopened.close(); reopened.close() }
                completed.countDown()
            }
            assertTrue(opening.await(1, TimeUnit.SECONDS))
            assertFalse("关闭同库句柄须等当前事务收尾", completed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            lifecycle.get(5, TimeUnit.SECONDS)
            assertEquals("重开后草稿", first.load()!!.draft)
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            first.closeStore()
            second.closeStore()
        }
    }

    @Test fun initializingAnotherHandleWaitsForTheActiveTransaction() = runBlocking<Unit> {
        val account = scope()
        val first = mirror(account)
        val workers = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opening = CountDownLatch(1)
        val initialized = CountDownLatch(1)
        try {
            first.saveCheckpoint(checkpoint("初始化前草稿"))
            val owner = workers.submit {
                synchronized(databaseLock(records(first))) {
                    first.offlineMirror().commitNativeEvents(listOf(event("before-open")))
                    held.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "合成事务释放超时" }
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val initializer = workers.submit {
                val newHandle = mirror(account)
                try {
                    opening.countDown()
                    assertEquals("before-open", newHandle.offlineMirror().timeline(thread.value)!!.messages.single().id)
                    initialized.countDown()
                } finally { newHandle.close() }
            }
            assertTrue(opening.await(1, TimeUnit.SECONDS))
            assertFalse("首次打开及 onOpen 索引必须在同库事务后执行", initialized.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            initializer.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            first.closeStore()
        }
    }

    private suspend fun overlappingWrites(checkpointFirst: Boolean) {
        val account = scope()
        val first = mirror(account)
        val second = mirror(account)
        val workers = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempting = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        try {
            first.saveCheckpoint(checkpoint("初始草稿"))
            second.load()
            val firstRecords = records(first)
            shortenBusyWait(records(second))
            val owner = workers.submit {
                try {
                    synchronized(databaseLock(firstRecords)) {
                        if (checkpointFirst) first.offlineMirror().saveNativeCheckpoint(checkpoint("保存后的草稿"))
                        else first.offlineMirror().commitNativeEvents(listOf(event("synthetic-event")))
                        held.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "合成事务释放超时" }
                    }
                } catch (failure: Throwable) { failures += failure }
            }
            assertTrue("首个真实事务必须持有数据库写锁", held.await(5, TimeUnit.SECONDS))
            val contender = workers.submit {
                attempting.countDown()
                try {
                    if (checkpointFirst) second.offlineMirror().commitNativeEvents(listOf(event("synthetic-event")))
                    else second.offlineMirror().saveNativeCheckpoint(checkpoint("保存后的草稿"))
                } catch (failure: Throwable) { failures += failure }
            }
            assertTrue(attempting.await(1, TimeUnit.SECONDS))
            // 只缩短测试 SQLite 的忙等待；同范围保存须在 Java 边界排队，而非耗尽 SQLite 超时。
            Thread.sleep(200)
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            contender.get(5, TimeUnit.SECONDS)
            assertTrue("并发保存不应产生存储异常：" + failures.joinToString { failure ->
                generateSequence(failure) { it.cause }.take(4).joinToString("->") { it.javaClass.simpleName }
            }, failures.isEmpty())
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            first.closeStore()
            second.closeStore()
        }
        val reopened = mirror(account)
        try {
            assertEquals("保存后的草稿", reopened.load()!!.draft)
            assertEquals("合成回复", (reopened.loadTimeline(thread.value)!!.messages.single().parts.single() as MessagePart.Text).value)
        } finally { reopened.closeStore() }
    }
}
