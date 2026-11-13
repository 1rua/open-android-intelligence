package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class NativeLocalSnapshotReadTest {
    @Test fun oldNativeHistoryReturnsTheNewerDurableEventBody() = runBlocking {
        val scope=ConversationScope("native-read","https://core.example","account","install")
        val key=object:AesGcmKeyProvider {
            override fun getOrCreate()=SecretKeySpec(ByteArray(32){17},"AES")
            override fun delete()=Unit
        }
        val mirror=NativeConversationMirror(ApplicationProvider.getApplicationContext(),scope,{"agent"},key)
        val lifecycle=SupervisorJob()
        try {
            mirror.commitEvents(listOf(VerifiedConversationEvent.TimelineUpsert("new-live",1,5,
                TimelineMessage("message","assistant",listOf(MessagePart.Text("实时修订5")),1,
                    conversationId=ConversationId("chat"),contentRevision=5))))
            val transport=object:GatewayByteTransport {
                override suspend fun execute(request:WireRequest)=WireResponse(200,emptyList(),
                    """{"messages":[{"id":"message","role":"assistant","text":"旧历史修订3","contentRevision":3}]}""".toByteArray())
                override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
            }
            val client=AgentSessionClient(GatewayHttpClient(GatewayProfile("account","device","session","https://core.example"),
                transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=Dispatchers.Unconfined))
            val agent=CoreAgent("agent","Agent","synthetic",true,"account",AgentCapabilities(list=true,history=true))
            val repository=CoreAgentConversationRepository(client,{agent},{"chat"},CoroutineScope(lifecycle+Dispatchers.Unconfined),
                ioDispatcher=Dispatchers.Unconfined,storeForAgent={mirror})
            val page=repository.timeline("chat",PageRequest())
            assertEquals("旧历史不能覆盖已经持久化的新事件",listOf(MessagePart.Text("实时修订5")),page.messages.single().parts)
            assertEquals(5L,page.messages.single().contentRevision)
        } finally { lifecycle.cancelAndJoin(); mirror.closeStore() }
    }
}
