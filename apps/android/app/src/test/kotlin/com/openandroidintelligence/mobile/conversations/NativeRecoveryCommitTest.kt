package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 合成传输调用真实 native 仓储；故障注入真实仓储的持久提交端口。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeRecoveryCommitTest {
    @Test fun normalRecoveryCommitsHistoryAndNeverResends() = runTest { scenario(false) }
    @Test fun consumerCommitFailureMustRemainRecoverableWithoutResending() = runTest { scenario(true) }

    @Test fun aLiveCriticalCommitFailureRecoversByReadsWithoutCancellingOrResending() = runTest { scenario(true, true) }

    @Test fun persistentCommitFailureHasAFiniteBudgetAndCanResumeAfterStorageRecovers()=runTest {
        scenario(true,persistentFailure=true)
    }

    private suspend fun TestScope.scenario(failCommit: Boolean, liveFailure: Boolean = false, persistentFailure: Boolean = false) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val failures = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> failures += failure })
        val requests = mutableListOf<WireRequest>()
        val agent = CoreAgent("hermes", "synthetic", "hermes", true, "account",
            AgentCapabilities(list=true, history=true, send=true, stream=true, generationRecovery=true))
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.method == "POST" -> "event: message\ndata: {\"type\":\"generation\",\"generationId\":\"gen\",\"clientMessageId\":\"cm\",\"userMessageId\":\"user\",\"state\":\"running\",\"revision\":1}\n\n"
                    request.target.contains("/generation?") -> """{"generation":{"generationId":"gen","conversationId":"chat","clientMessageId":"cm","userMessageId":"user","state":"completed","revision":7}}"""
                    request.target.endsWith("/history") -> """{"messages":[{"id":"user","role":"user","text":"synthetic prompt","clientMessageId":"cm","generationId":"gen","contentRevision":1},{"id":"reply","role":"assistant","text":"synthetic final","clientMessageId":"cm","generationId":"gen","contentRevision":7}]}"""
                    else -> error("unexpected synthetic request")
                }
                val completeBody = if (request.method == "POST" && liveFailure) body +
                    "event: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\",\"generationId\":\"gen\",\"revision\":7}\n\n" else body
                return WireResponse(200, listOf(RawHeader("Content-Type", if (request.method == "POST") "text/event-stream" else "application/json")), completeBody.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val http = GatewayHttpClient(GatewayProfile("account","device","session","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher)
        var commitAttempts = 0
        var failNext = failCommit
        var injectedFailures = 0
        var rejectStorage = persistentFailure
        val store = object : NativeConversationStore by NativeEventSynchronizerTest.Storage() {
            override suspend fun commitEvents(events: List<VerifiedConversationEvent>) {
                for (event in events) {
                    if ((!liveFailure && event is VerifiedConversationEvent.TimelineUpsert && event.eventId.startsWith("history:")) ||
                        (liveFailure && event is VerifiedConversationEvent.MessageAccepted)) {
                        commitAttempts++
                        if (failNext || rejectStorage) { failNext=false; injectedFailures++; throw IOException("SYNTHETIC_LOCAL_COMMIT_FAILED") }
                    }
                }
            }
        }
        val repository = CoreAgentConversationRepository(AgentSessionClient(http),{agent},{"chat"},owner,dispatcher,storeForAgent={store})
        var commits = 0
        owner.launch {
            repository.observeEvents(ConversationScope("profile","https://core.example","account","install")).collect { event ->
                if (event is VerifiedConversationEvent.TimelineUpsert && event.eventId.startsWith("history:")) {
                    if (event.message.id == "reply") commits++
                }
            }
        }
        try {
            repository.submitMessage("chat", OutgoingMessage(ClientMessageId("cm"),"synthetic prompt"))
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            if(persistentFailure) {
                advanceTimeBy(20000);runCurrent()
                assertEquals("读取成功不能重置后续落盘故障的预算",6,injectedFailures)
                assertEquals(StreamHealth.FAILED,repository.streamHealth.first())
                assertEquals(0,commits)
                rejectStorage=false
            }
            val getsBefore = requests.count { it.target.contains("/generation?") }
            repository.connectionRestored()
            runCurrent()
            val getsAfter = requests.count { it.target.contains("/generation?") }
            val posts = requests.count { it.method == "POST" }
            println("BUG08_NATIVE failCommit=$failCommit consumerFailures=${failures.size} commitAttempts=$commitAttempts finalCommits=$commits generationGETs=$getsBefore->$getsAfter POSTs=$posts state=${repository.generationState.value}")
            assertEquals("原任务始终只 POST 一次",1,posts)
            assertEquals("合成写盘故障必须实际发生",if(persistentFailure) 6 else if(failCommit) 1 else 0,injectedFailures)
            assertTrue("恢复不能在消费者提交失败后永久丢失最终历史；重连必须补齐同一消息",commits>0)
        } finally { owner.cancel() }
    }
}
