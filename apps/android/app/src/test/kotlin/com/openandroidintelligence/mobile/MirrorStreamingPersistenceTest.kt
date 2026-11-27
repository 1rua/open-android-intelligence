package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 中间流式快照按间隔合并落盘，终态与非流式事件始终立即落盘。
 *
 * 逐帧加密事务会把上游读流压成背压，而中间快照不是最终事实：崩溃最多丢一个间隔内的
 * 中间文本，终态快照必然持久化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MirrorStreamingPersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {override fun getOrCreate()=SecretKeySpec(ByteArray(32){11},"AES");override fun delete(){}}
    private val scope=ConversationScope("profile_stream_persist","https://stream.example","acct_stream","install_stream")

    @Test fun intermediateStreamingSnapshotsAreCoalescedAndTerminalIsPersisted() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val mirror=EncryptedConversationMirror(context,scope,key,dispatcher)
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        var nowNanos=0L
        val repository=MirroredConversationRepository(
            delegate=Host(events),
            mirror=mirror,
            media=null,
            streamingPersistIntervalMillis=500L,
            clock={nowNanos},
        )
        val collector=launch { repository.observeEvents(scope).collect { } }
        runCurrent()

        events.emit(upsert("s1","a",1,"STREAMING")); runCurrent()
        assertEquals("a",assistantText(mirror))
        // 假时钟没走：同一间隔内的后续快照不得再落盘。
        events.emit(upsert("s2","ab",2,"STREAMING")); runCurrent()
        events.emit(upsert("s3","abc",3,"STREAMING")); runCurrent()
        assertEquals("a",assistantText(mirror))

        // 越过间隔后下一个快照可以落盘。
        nowNanos+=600_000_000L
        events.emit(upsert("s4","abcd",4,"STREAMING")); runCurrent()
        assertEquals("abcd",assistantText(mirror))

        // 终态不受间隔限制：即使时钟不动也必须立即落盘。
        events.emit(upsert("s5","abcde",5,"CONFIRMED")); runCurrent()
        assertEquals("abcde",assistantText(mirror))
        collector.cancel()
        mirror.wipe()
    }

    private fun assistantText(mirror:EncryptedConversationMirror):String =
        mirror.timeline("conv_a")?.messages?.firstOrNull { it.sender=="assistant" }?.parts
            ?.filterIsInstance<MessagePart.Text>()?.joinToString("") { it.value }.orEmpty()

    private fun upsert(eventId:String,text:String,revision:Long,state:String)=VerifiedConversationEvent.TimelineUpsert(
        eventId,revision,revision,
        TimelineMessage("stream_reply","assistant",listOf(MessagePart.Text(text)),2,state,ConversationId("conv_a"),generationId="gen_a"),
    )

    private class Host(private val events:Flow<VerifiedConversationEvent>) : ConversationRepository {
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(emptyList(),null)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("测试不创建会话")
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("测试不发送消息")
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("测试不发送批次")
        override fun observeEvents(scope:ConversationScope)=events
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
