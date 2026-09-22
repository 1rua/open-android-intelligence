package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Coming back to the foreground is the one moment the phone knows its stream may
 * have missed frames without the socket ever breaking: the Gateway keeps
 * heartbeating, so the channel still looks live while its bounded subscriber
 * queue drops frames, and a drop is only ever repaid by a cursor replay.
 *
 * These tests pin both halves of that recovery — the subscription is re-opened
 * from the cursor and the authoritative timeline is re-read — and pin that the
 * recovery stays a recovery: one subscription at a time, no frame applied twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchForegroundResumeTest {

    @Test fun foregroundReturnReopensTheSubscriptionWhileTheRunningOneIsStillAlive() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()
        assertEquals("打开会话应建立一次订阅", 1, repository.subscriptions)

        // The channel never failed and never went non-live: only visibility changed.
        controller.onForegrounded()
        advanceUntilIdle()

        assertEquals("回到前台必须重开订阅，而不是信任一条仍然活着的通道", 2, repository.subscriptions)
        assertEquals("重开期间不得让两个订阅并存", 1, repository.maxConcurrentSubscriptions)
        controller.cancel()
    }

    @Test fun repeatedForegroundReturnsKeepOneSubscriptionAtATime() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        controller.onForegrounded()
        advanceUntilIdle()
        controller.onForegrounded()
        advanceUntilIdle()

        assertEquals("每次回到前台重开一次订阅", 3, repository.subscriptions)
        assertEquals("连续切前台也不得出现重叠的订阅", 1, repository.maxConcurrentSubscriptions)
        controller.cancel()
    }

    @Test fun foregroundReturnReloadsTheAuthoritativeTimeline() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()
        val reloadsBefore = repository.timelineCalls

        controller.onForegrounded()
        advanceUntilIdle()

        assertEquals("回到前台必须重新拉取当前会话的权威时间线", reloadsBefore + 1, repository.timelineCalls)
        assertEquals("conv_1", repository.lastTimelineConversationId)
        controller.cancel()
    }

    @Test fun foregroundReturnWithoutAnOpenThreadRefreshesTheThreadList() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        val refreshesBefore = repository.threadListCalls

        controller.onForegrounded()
        advanceUntilIdle()

        assertEquals("没有打开的会话时退回刷新会话列表", refreshesBefore + 1, repository.threadListCalls)
        assertEquals("没有打开的会话就没有时间线可拉", 0, repository.timelineCalls)
        controller.cancel()
    }

    @Test fun aCursorReplayAfterReopeningDoesNotApplyTheSameFrameTwice() = runTest {
        // Every subscription offers the same frame again, which is exactly what a
        // replay from the stored cursor looks like to the workbench.
        val repository = RecordingRepository(eventsFor = {
            flow { emit(assistantReply(eventId = "evt_replay")) }
        })
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()
        assertEquals(1, assistantRows(controller))

        controller.onForegrounded()
        advanceUntilIdle()

        assertEquals("重开订阅确实让宿主重放了积压", 2, repository.subscriptions)
        assertEquals("重放帧不得第二次上屏", 1, assistantRows(controller))
        controller.cancel()
    }

    @Test fun aRecoveredChannelRetiresItsOwnFailureNotice() = runTest {
        val health = MutableStateFlow(StreamHealth.IDLE)
        val repository = RecordingRepository(health = health)
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        health.value = StreamHealth.FAILED
        advanceUntilIdle()
        assertTrue(
            "通道失败必须如实写进提示",
            controller.state.value.notice.orEmpty().startsWith("EVENTS_FAILED:"),
        )

        health.value = StreamHealth.LIVE
        advanceUntilIdle()

        assertNull("通道恢复后不得继续谎报实时通道已断开", controller.state.value.notice)
        controller.cancel()
    }

    @Test fun aRecoveredChannelKeepsANoticeThatIsNotAboutTheStream() = runTest {
        val health = MutableStateFlow(StreamHealth.IDLE)
        val repository = RecordingRepository(health = health)
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()

        health.value = StreamHealth.FAILED
        advanceUntilIdle()
        // A notice the stream owns nothing about: renaming failed, which is a
        // fact about the Gateway, not about the event channel.
        controller.renameActiveThread("重命名不成功")
        advanceUntilIdle()
        val renameNotice = "CONVERSATION_RENAME_FAILED:CONVERSATION_RENAME_REJECTED"
        assertEquals(renameNotice, controller.state.value.notice)

        health.value = StreamHealth.LIVE
        advanceUntilIdle()

        assertEquals("恢复只退休实时通道自己的提示", renameNotice, controller.state.value.notice)
        controller.cancel()
    }

    @Test fun foregroundReturnAfterCloseIsIgnored() = runTest {
        val repository = RecordingRepository()
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_1")
        advanceUntilIdle()
        val subscriptions = repository.subscriptions
        val timelineCalls = repository.timelineCalls

        controller.cancel()
        controller.onForegrounded()
        advanceUntilIdle()

        assertEquals("已释放的控制器不得重新建立订阅", subscriptions, repository.subscriptions)
        assertEquals(timelineCalls, repository.timelineCalls)
    }

    private fun TestScope.controller(repository: RecordingRepository) = WorkbenchController(
        this,
        repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        // A virtual clock would run the reply watchdog's real minutes instantly,
        // so the watchdog is off here: it has tests of its own.
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        streamHealthSource = repository,
    )

    private fun assistantReply(eventId: String, conversationId: String = "conv_1") =
        VerifiedConversationEvent.TimelineUpsert(
            eventId = eventId,
            occurredAt = 1_000L,
            revision = 1L,
            message = TimelineMessage(
                id = "msg_reply",
                sender = "assistant",
                parts = listOf(MessagePart.Text("来自 Agent 的回复")),
                timestamp = 1_000L,
                state = "CONFIRMED",
                conversationId = ConversationId(conversationId),
            ),
        )

    private fun assistantRows(controller: WorkbenchController): Int =
        (controller.state.value.timeline as Loadable.Ready).value.count { it.sender == "assistant" }

    /**
     * A repository whose interesting behaviour is the subscription itself: how
     * many times it was opened, whether two were ever alive at once, and how
     * often the authoritative timeline was re-read.
     */
    private class RecordingRepository(
        /** The frames one subscription offers; attempt numbers start at 1. */
        private val eventsFor: (attempt: Int) -> Flow<VerifiedConversationEvent> = { flow { awaitCancellation() } },
        private val health: MutableStateFlow<StreamHealth> = MutableStateFlow(StreamHealth.IDLE),
    ) : ConversationRepository, GenerationTracker, StreamHealthSource {

        var subscriptions = 0
        var maxConcurrentSubscriptions = 0
        var timelineCalls = 0
        var threadListCalls = 0
        var lastTimelineConversationId: String? = null
        private var liveSubscriptions = 0

        override val generationId = MutableStateFlow<String?>(null)
        override val streamHealth: Flow<StreamHealth> = health

        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = flow {
            subscriptions++
            liveSubscriptions++
            maxConcurrentSubscriptions = maxOf(maxConcurrentSubscriptions, liveSubscriptions)
            try {
                emitAll(eventsFor(subscriptions))
            } finally {
                liveSubscriptions--
            }
        }

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
            threadListCalls++
            return ConversationPage(emptyList(), null)
        }

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_created"), "新对话", 0L)

        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
            timelineCalls++
            lastTimelineConversationId = conversationId
            return TimelinePage(emptyList(), null)
        }

        override suspend fun submitBatch(batch: MessageBatch) =
            BatchAcceptance(batch.batchId, batch.messages.associate { it.clientMessageId.value to "msg_server" })

        override suspend fun submitMessage(message: OutgoingMessage) =
            MessageAcceptance("msg_server", message.clientMessageId.value)

        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
