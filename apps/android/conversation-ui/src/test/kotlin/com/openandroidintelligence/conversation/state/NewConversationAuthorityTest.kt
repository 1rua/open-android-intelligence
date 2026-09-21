package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.CommandOutcome
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new conversation belongs to whoever can serve it: the Agent host.
 *
 * These tests pin the whole disagreement the old flow produced — the phone used
 * to build a thread of its own and the Gateway never heard of it — by asserting
 * that nothing moves until the Gateway names the conversation it created, and
 * that everything after that move targets the named one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewConversationAuthorityTest {

    /**
     * Controllers this test opened, released when the test body ends.
     *
     * The event subscription is deliberately long-lived — it is the account's
     * stream, not a per-thread one — so it has to be released explicitly
     * instead of being left running past the assertions.
     */
    private val opened = mutableListOf<WorkbenchController>()

    private fun runWorkbench(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.cancel() }
            opened.clear()
        }
    }

    private fun TestScope.controller(
        repository: ConversationRepository,
        supportsAgentCommandNew: Boolean = true,
        timeoutMillis: Long = 60_000L,
        // Off by default: `advanceUntilIdle` runs pending delays, so a live
        // watchdog would give up on every test that is not about giving up.
        watchdogEnabled: Boolean = false,
    ): WorkbenchController = WorkbenchController(
        scope = this,
        repository = repository,
        catalogRepository = object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        supportsAgentCommandNew = supportsAgentCommandNew,
        newConversationTimeouts = WorkbenchController.NewConversationTimeouts(
            timeoutMillis = timeoutMillis,
            enabled = watchdogEnabled,
        ),
    ).also { opened += it }

    private fun commandResult(
        outcome: CommandOutcome = CommandOutcome.CREATED_CONVERSATION,
        command: String = "new",
        newConversationId: String? = CREATED_ID,
    ): VerifiedConversationEvent.CommandResult = VerifiedConversationEvent.CommandResult(
        eventId = "evt_new_${outcome}_${newConversationId ?: "none"}",
        occurredAt = 0L,
        command = command,
        outcome = outcome,
        sourceConversationId = ConversationId(SOURCE_ID),
        sourceMessageId = "msg_cmd",
        conversationId = newConversationId?.let { ConversationId(it) },
    )

    @Test
    fun createSendsNewCommandAndWaitsInsteadOfBuildingAThreadLocally() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)

        controller.createThread()
        advanceUntilIdle()

        assertEquals(listOf(SOURCE_ID to "/new"), repository.sentMessages.map { it.first to it.second.text })
        assertTrue("不得自行调用创建端点", repository.createCalls.isEmpty())
        assertEquals("必须仍停留在来源会话", SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue("必须处于等待态", controller.state.value.creatingThread)
    }

    @Test
    fun switchesOnlyAfterTheGatewayNamesTheConversationAndSyncsItsContext() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(commandResult())
        advanceUntilIdle()

        assertEquals(CREATED_ID, controller.state.value.activeThreadId)
        assertTrue("必须读取新会话的历史", repository.timelineRequests.contains(CREATED_ID))
        assertEquals("标题必须来自 Gateway", "Agent 给的标题", controller.state.value.activeThreadTitle)
        assertFalse(controller.state.value.creatingThread)
        assertTrue(
            "切换后的会话必须是 Agent 端创建的那一个，不是本地生成的",
            repository.createCalls.isEmpty(),
        )
        assertEquals(listOf(SOURCE_ID to "/new"), repository.sentMessages.map { it.first to it.second.text })
    }

    @Test
    fun rejectedAnswerKeepsTheSourceThreadAndExplainsItself() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(commandResult(outcome = CommandOutcome.REJECTED, newConversationId = null))
        advanceUntilIdle()

        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
        assertTrue(
            "拒绝必须如实提示，而不是悄悄切换：${controller.state.value.notice}",
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_FAILED:REJECTED"),
        )
    }

    @Test
    fun unsupportedAnswerRollsBackWithAStructuredCode() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(commandResult(outcome = CommandOutcome.UNSUPPORTED, newConversationId = null))
        advanceUntilIdle()

        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue(
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_UNSUPPORTED"),
        )
    }

    @Test
    fun createdAnswerWithoutAnIdentifierIsRefused() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(commandResult(newConversationId = null))
        advanceUntilIdle()

        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue(
            controller.state.value.notice.orEmpty().contains("MISSING_CONVERSATION_ID"),
        )
    }

    @Test
    fun commandResultFromAnotherThreadIsNotThisRequest() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(commandResult().copy(sourceConversationId = ConversationId("conv_elsewhere")))
        advanceUntilIdle()

        assertEquals("别的线程的结果不得切换或结束等待", SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue(controller.state.value.creatingThread)
    }

    @Test
    fun commandResultForAnotherRequestInTheSameThreadIsNotThisRequest() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(
            commandResult().copy(eventId = "evt_other_device", sourceMessageId = "msg_other_device"),
        )
        advanceUntilIdle()

        assertEquals("同一来源会话但来源消息不同，不得切换", SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue(controller.state.value.creatingThread)
    }

    @Test
    fun answerFromAnotherDeviceThatBeatsTheSendResponseIsNotOurs() = runWorkbench {
        // 把发送应答扣在手里，让事件流真的赢下这次竞速。
        val repository = FakeRepository().apply { holdSend = true }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(
            commandResult().copy(eventId = "evt_other_device", sourceMessageId = "msg_other_device"),
        )
        advanceUntilIdle()
        repository.releaseSend()
        advanceUntilIdle()

        assertEquals("别人的结果不得切换本机会话", SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue("不得替用户放弃等待", controller.state.value.creatingThread)
    }

    @Test
    fun answerThatArrivesBeforeTheSendResponseStillSwitches() = runWorkbench {
        val repository = FakeRepository().apply { holdSend = true }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        repository.events.emit(commandResult())
        advanceUntilIdle()
        assertEquals("应答未到时只能等待", SOURCE_ID, controller.state.value.activeThreadId)

        repository.releaseSend()
        advanceUntilIdle()

        assertEquals("先到的应答不得被丢弃", CREATED_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
    }

    @Test
    fun lateAnswerToAnAbandonedRequestDoesNotReverseTheTimeout() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository, watchdogEnabled = true)
        advanceUntilIdle()
        controller.createThread()

        advanceTimeBy(60_001L)
        assertTrue(controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_TIMEOUT"))

        repository.events.emit(commandResult())
        advanceUntilIdle()

        assertEquals(
            "超时已告知用户停在原会话，迟到的结果不得再把他拽走",
            SOURCE_ID,
            controller.state.value.activeThreadId,
        )
        assertFalse(controller.state.value.creatingThread)
    }

    @Test
    fun aGenuinelyNewAnswerAfterAnAbandonedOneStillSwitches() = runWorkbench {
        val repository = FakeRepository().apply { holdSend = true }
        val controller = controller(repository, watchdogEnabled = true)
        advanceUntilIdle()
        controller.createThread()

        advanceTimeBy(60_001L)
        assertTrue(controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_TIMEOUT"))

        repository.events.emit(commandResult().copy(eventId = "evt_late", sourceMessageId = "msg_late"))
        advanceUntilIdle()
        assertEquals("被放弃那次请求的迟到结果不得切换", SOURCE_ID, controller.state.value.activeThreadId)

        repository.events.emit(commandResult().copy(eventId = "evt_fresh", sourceMessageId = "msg_fresh"))
        advanceUntilIdle()
        assertEquals("一次放弃只能认领一个迟到结果", CREATED_ID, controller.state.value.activeThreadId)
    }

    @Test
    fun lateAnswerAfterAFailedSendDoesNotSwitchEither() = runWorkbench {
        val repository = FakeRepository().apply { failSend = true }
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()
        assertTrue(controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_FAILED"))

        repository.events.emit(commandResult())
        advanceUntilIdle()

        assertEquals("发送失败后的迟到结果同样不得切换", SOURCE_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
    }

    @Test
    fun userTypedNewStillSwitchesWithoutARequestOfOurOwn() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()

        // 用户自己在输入框里敲的 `/new`：本机没有发起过请求。
        repository.events.emit(commandResult())
        advanceUntilIdle()

        assertEquals(CREATED_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
    }

    @Test
    fun unansweredRequestTimesOutAndRollsBack() = runWorkbench {
        // The only test in which the watchdog is armed: silence is the subject.
        val repository = FakeRepository()
        val controller = controller(repository, watchdogEnabled = true)
        advanceUntilIdle()
        controller.createThread()

        advanceTimeBy(60_001L)

        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
        assertTrue(
            "沉默必须变成可读的结论：${controller.state.value.notice}",
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_TIMEOUT"),
        )
    }

    @Test
    fun gatewayWithoutTheCapabilityCreatesNothingAndSaysSo() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository, supportsAgentCommandNew = false)
        advanceUntilIdle()

        controller.createThread()
        advanceUntilIdle()

        assertTrue("不得发送 /new", repository.sentMessages.isEmpty())
        assertTrue("不得本地创建会话", repository.createCalls.isEmpty())
        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
        assertTrue(
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_UNSUPPORTED"),
        )
    }

    @Test
    fun switchingAwayDuringTheWaitIsNotHijackedByALateAnswer() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        assertEquals(SOURCE_ID, controller.state.value.creationSourceThreadId)

        controller.openThread("conv_other")
        advanceUntilIdle()
        assertEquals(
            "等待属于发出请求的那个会话，切换不得把它挂到新会话上",
            SOURCE_ID,
            controller.state.value.creationSourceThreadId,
        )
        repository.events.emit(commandResult())
        advanceUntilIdle()

        assertEquals("conv_other", controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
        assertNull(controller.state.value.creationSourceThreadId)
        assertTrue(controller.state.value.notice.orEmpty().contains("已创建新对话"))
    }

    @Test
    fun messagesAfterTheSwitchAreFiledUnderTheNewConversation() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()
        repository.events.emit(commandResult())
        advanceUntilIdle()

        controller.editDraft("这条必须进新会话")
        controller.sendDraft()
        advanceUntilIdle()

        assertTrue("不得本地创建会话", repository.createCalls.isEmpty())
        val last = repository.sentMessages.last()
        assertEquals(CREATED_ID, last.first)
        assertEquals("这条必须进新会话", last.second.text)
    }

    @Test
    fun sendingWhileTheAgentAnswerIsPendingIsBlockedRatherThanFiledElsewhere() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        controller.editDraft("会被挡下的草稿")
        controller.sendDraft()
        advanceUntilIdle()

        assertEquals("等待期间不应产生第二条外发消息", 1, repository.sentMessages.size)
        assertTrue(
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATING"),
        )
    }

    @Test
    fun cancellingTheWaitStopsItWithoutFakingAConversation() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()
        assertTrue(controller.state.value.creatingThread)
        assertEquals(SOURCE_ID, controller.state.value.creationSourceThreadId)

        controller.cancelThreadCreation()
        advanceUntilIdle()

        assertFalse("取消后必须退出等待态", controller.state.value.creatingThread)
        assertNull(controller.state.value.creationSourceThreadId)
        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertTrue("不得本地创建会话", repository.createCalls.isEmpty())
        assertTrue(
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_CANCELLED"),
        )

        // 用户已经被告知等待结束，迟到的答案不得反过来把他挪走。
        repository.events.emit(commandResult())
        advanceUntilIdle()
        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
    }

    @Test
    fun switchingThreadsDropsTheStateThatBelongedToTheThreadBeingLeft() = runWorkbench {
        val repository = FakeRepository().apply { failSend = true }
        val controller = controller(repository)
        advanceUntilIdle()

        // 来源会话里正在跑的一轮生成。
        repository.events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "evt_streaming",
                occurredAt = 1L,
                revision = 1L,
                message = TimelineMessage(
                    id = "msg_streaming",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("正在回答")),
                    timestamp = 1L,
                    state = "STREAMING",
                    conversationId = ConversationId(SOURCE_ID),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(GenerationState.RUNNING, controller.state.value.generation)

        controller.editDraft("这条会失败")
        controller.sendDraft()
        advanceUntilIdle()
        assertTrue(controller.state.value.notice.orEmpty().contains("SEND_FAILED"))

        // 新会话的时间线读取失败：此时只有切换本身的清理能纠正界面状态。
        repository.failTimeline = true
        controller.openThread("conv_other")
        advanceUntilIdle()

        assertEquals(
            "上一会话运行中的 generation 不得跟着切过去",
            GenerationState.IDLE,
            controller.state.value.generation,
        )
        assertEquals("上一会话的失败提示不得出现在新会话", null, controller.state.value.notice)
        assertEquals(
            "上一会话的输入框失败态不得挡住新会话",
            ComposerState.EDITING,
            controller.state.value.composer,
        )
    }

    @Test
    fun theSourceThreadKeepsAReceiptForWhatTheCommandCreated() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()
        repository.events.emit(commandResult())
        advanceUntilIdle()
        assertEquals(CREATED_ID, controller.state.value.activeThreadId)

        controller.openThread(SOURCE_ID)
        advanceUntilIdle()

        val rows = (controller.state.value.timeline as Loadable.Ready).value
        val receipt = rows.singleOrNull { it.systemThreadId != null }
        assertEquals("来源会话必须留下跳转项", CREATED_ID, receipt?.systemThreadId)
        assertEquals("已创建新对话", receipt?.text)
        assertEquals("跳转项必须排在消息之后", receipt?.key, rows.last().key)

        // 跳转项就是回去的入口。
        controller.openThread(CREATED_ID)
        advanceUntilIdle()
        assertEquals(CREATED_ID, controller.state.value.activeThreadId)
    }

    @Test
    fun aConversationCreatedWhileTheUserWasElsewhereStaysReachable() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        controller.createThread()
        advanceUntilIdle()

        controller.openThread("conv_other")
        advanceUntilIdle()
        repository.events.emit(commandResult())
        advanceUntilIdle()
        assertEquals("用户已离开来源会话时不得劫持页面", "conv_other", controller.state.value.activeThreadId)

        controller.openThread(SOURCE_ID)
        advanceUntilIdle()

        val rows = (controller.state.value.timeline as Loadable.Ready).value
        assertEquals(
            "新会话必须仍能从来源会话进入",
            CREATED_ID,
            rows.singleOrNull { it.systemThreadId != null }?.systemThreadId,
        )
    }

    @Test
    fun failedCommandDeliveryRollsBackWithTheSameHonestyAsARefusal() = runWorkbench {
        val repository = FakeRepository().apply { failSend = true }
        val controller = controller(repository)
        advanceUntilIdle()

        controller.createThread()
        advanceUntilIdle()

        assertEquals(SOURCE_ID, controller.state.value.activeThreadId)
        assertFalse(controller.state.value.creatingThread)
        assertTrue(
            controller.state.value.notice.orEmpty().contains("CONVERSATION_CREATE_FAILED"),
        )
    }

    private class FakeRepository : ConversationRepository {
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        val sentMessages = mutableListOf<Pair<String, OutgoingMessage>>()
        val timelineRequests = mutableListOf<String>()
        val createCalls = mutableListOf<String>()
        var failSend = false
        /** Makes the next timeline reads fail, so only a switch's own cleanup shows. */
        var failTimeline = false
        /**
         * Holds the send reply back so a test can let the event stream win the
         * race — the Gateway pushes results and answers on separate channels.
         */
        var holdSend = false
        private val sendGate = CompletableDeferred<Unit>()

        fun releaseSend() {
            sendGate.complete(Unit)
        }

        override suspend fun listConversations(
            scope: ConversationScope,
            page: PageRequest,
        ): ConversationPage = ConversationPage(
            listOf(ConversationSummary(ConversationId(SOURCE_ID), "来源会话", 1L)),
            null,
        )

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation {
            createCalls += clientConversationId
            return Conversation(ConversationId("conv_bootstrap"), "新对话", 0L)
        }

        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
            timelineRequests += conversationId
            if (failTimeline) error("TIMELINE_FAILED:offline")
            return if (conversationId == CREATED_ID) {
                TimelinePage(
                    listOf(
                        TimelineMessage(
                            id = "msg_history",
                            sender = "user",
                            parts = listOf(MessagePart.Text("新会话里的历史消息")),
                            timestamp = 10L,
                            conversationId = ConversationId(CREATED_ID),
                        ),
                    ),
                    null,
                )
            } else {
                TimelinePage(emptyList(), null)
            }
        }

        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = BatchAcceptance(
            batch.batchId,
            batch.messages.associate { it.clientMessageId.value to "msg_server" },
        )

        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
            submitMessage(SOURCE_ID, message)

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            if (failSend) error("SEND_FAILED:offline")
            if (holdSend) sendGate.await()
            sentMessages += conversationId to message
            return MessageAcceptance("msg_cmd", message.clientMessageId.value)
        }

        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events

        override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)

        override suspend fun readConversation(conversationId: String): Conversation? =
            Conversation(ConversationId(conversationId), "Agent 给的标题", 0L)
    }

    private companion object {
        const val SOURCE_ID = "conv_src"
        const val CREATED_ID = "conv_created"
    }
}
