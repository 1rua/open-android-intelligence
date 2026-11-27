package com.openandroidintelligence.mobile.conversations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class RawSqliteLockRedTest {
    private val key=object:AesGcmKeyProvider {
        override fun getOrCreate()=SecretKeySpec(ByteArray(32){31},"AES")
        override fun delete()=Unit
    }
    private fun helper(mirror:NativeConversationMirror):SQLiteOpenHelper {
        val records=EncryptedConversationMirror::class.java.getDeclaredField("documents").apply{isAccessible=true}.get(mirror.offlineMirror())
        return SqliteConversationRecords::class.java.getDeclaredField("helper").apply{isAccessible=true}.get(records) as SQLiteOpenHelper
    }
    @Test fun baselineTwoDatabaseHandlesReallyHitSqliteLock() = runBlocking {
        val scope=ConversationScope("raw-red","https://red.invalid", UUID.randomUUID().toString(),"installation")
        val first=NativeConversationMirror(ApplicationProvider.getApplicationContext(),scope,{"agent"},key)
        val second=NativeConversationMirror(ApplicationProvider.getApplicationContext(),scope,{"agent"},key)
        val firstDb=helper(first).writableDatabase
        val secondDb=helper(second).writableDatabase
        val held=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        try {
            firstDb.beginTransaction();held.countDown()
            val result=executor.submit<Boolean> {
                secondDb.rawQuery("PRAGMA busy_timeout=50",null).use{it.moveToFirst()}
                try { secondDb.beginTransaction(); secondDb.setTransactionSuccessful(); secondDb.endTransaction(); false }
                catch (_:android.database.sqlite.SQLiteDatabaseLockedException) { true }
            }.get(2,TimeUnit.SECONDS)
            assertTrue("未修复的双句柄必须能复现SQLite锁异常",result)
        } finally {
            release.countDown(); if(firstDb.inTransaction()) firstDb.endTransaction(); executor.shutdownNow(); first.closeStore();second.closeStore()
        }
    }
}
