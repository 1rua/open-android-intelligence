package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One approval card's whole life, driven through the real controller.
 *
 * The Gateway owns every outcome: a press is allowed to say "受理中" and nothing
 * more, a failed press has to come back as a live card, and a settled card must
 * never come back as a question — not after a replay, not after leaving the
 * thread and returning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalStateMachineTest {

    private val opened = mutableListOf<WorkbenchController>()

    /**
     * The clock every controller in this test reads.
     *
     * A test advances it to run the Gateway's window down instead of sleeping,
     * and it is reset per test so one test's expiry cannot leak into the next.
     */
    private var now = REQUESTED_AT

    private fun runWorkbench(body: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        now = REQUESTED_AT
        try {
            body()
        } finally {
            opened.forEach { it.cancel() }
            opened.clear()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        repository: ConversationRepository,
        supportsApprovalCards: Boolean = true,
    ): WorkbenchController = WorkbenchController(
        scope = this,
        repository = repository,
        catalogRepository = object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        },
        scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        newConversationTimeouts = WorkbenchController.NewConversationTimeouts(enabled = false),
        supportsApprovalCards = supportsApprovalCards,
        clock = { now },
    ).also { opened += it }

    private fun requested(
        approvalId: String = APPROVAL_ID,
        eventId: String = "evt_requested",
        requestedAt: Long = REQUESTED_AT,
        options: List<ApprovalOption> = DEFAULT_OPTIONS,
    ): VerifiedConversationEvent.ApprovalRequested = VerifiedConversationEvent.ApprovalRequested(
        eventId = eventId,
        occurredAt = requestedAt,
        request = ApprovalRequest(
            approvalId = ApprovalId(approvalId),
            conversationId = ConversationId(THREAD_ID),
            command = "python3 -c \"print(1)\"",
            reason = "内联解释器执行",
            severity = "elevated",
            options = options,
            timeoutSeconds = TIMEOUT_SECONDS,
            requestedAt = requestedAt,
        ),
        conversationId = ConversationId(THREAD_ID),
    )

    private fun resolved(
        approvalId: String = APPROVAL_ID,
        outcome: ApprovalOutcome,
        eventId: String = "evt_resolved_$outcome",
        decidedAt: Long = REQUESTED_AT + 5_000L,
    ): VerifiedConversationEvent.ApprovalResolved = VerifiedConversationEvent.ApprovalResolved(
        eventId = eventId,
        occurredAt = decidedAt,
        approvalId = ApprovalId(approvalId),
        outcome = outcome,
        decidedAt = decidedAt,
        conversationId = ConversationId(THREAD_ID),
    )

    private fun cardRow(controller: WorkbenchController, approvalId: String = APPROVAL_ID): TimelineEntry? {
        val timeline = controller.state.value.timeline
        assertTrue(timeline is Loadable.Ready, "时间线必须已就绪：$timeline")
        val rows = (timeline as Loadable.Ready).value
        return rows.firstOrNull { it.approval?.request?.approvalId?.value == approvalId }
    }

    @Test
    fun anApprovalRequestBecomesACardInTheConversation() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()

        repository.events.emit(requested())
        advanceUntilIdle()

        val row = cardRow(controller)
        assertNotNull(row, "审批必须作为一行出现在会话里")
        val card = row!!.approval
        assertTrue(card is ApprovalCardState.Waiting, "审批卡片必须先处于等待态：$card")
        val waiting = card as ApprovalCardState.Waiting
        assertEquals("python3 -c \"print(1)\"", waiting.request.command)
        assertEquals("内联解释器执行", waiting.request.reason)
        assertEquals(REQUESTED_AT + TIMEOUT_SECONDS * 1_000L, waiting.request.expiresAt)
        // Only the tiers the Gateway offered may be drawn.
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.ALWAYS, ApprovalChoice.DENY),
            waiting.request.options.map { it.choice },
        )
        assertEquals("approval_$APPROVAL_ID", row.key)
    }

    @Test
    fun aPressLocksTheCardAndOnlyTheGatewayMaySayItWasAllowed() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()

        val pressed = cardRow(controller)!!.approval
        assertTrue(pressed is ApprovalCardState.Submitting, "按下后必须立刻进入受理态：$pressed")
        val submitting = pressed as ApprovalCardState.Submitting
        assertEquals(ApprovalChoice.ONCE, submitting.choice)
        assertFalse("受理态不是结果，不得显示为已允许", submitting.isSettled)
        assertEquals(listOf(APPROVAL_ID to ApprovalChoice.ONCE), repository.decisions)

        // A second press while the first is in flight is not a second decision.
        controller.decideApproval(APPROVAL_ID, ApprovalChoice.DENY)
        advanceUntilIdle()
        assertEquals(1, repository.decisions.size, "在飞行中的决策不得被第二次点击变成两条")

        repository.events.emit(resolved(outcome = ApprovalOutcome.ALLOWED_ONCE))
        advanceUntilIdle()

        val answered = cardRow(controller)!!.approval
        assertTrue(answered is ApprovalCardState.Resolved, "网关的事件才能落定卡片：$answered")
        val settled = answered as ApprovalCardState.Resolved
        assertEquals(ApprovalOutcome.ALLOWED_ONCE, settled.outcome)
        assertEquals(ApprovalChoice.ONCE, settled.settledChoice)
    }

    @Test
    fun aFailedPressComesBackAsALiveCardWithAStructuredNotice() = runWorkbench {
        val repository = FakeRepository().apply { submissionOutcome = ApprovalSubmissionOutcome.FAILED }
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.DENY)
        advanceUntilIdle()

        val returned = cardRow(controller)!!.approval
        assertTrue(returned is ApprovalCardState.Waiting, "失败的提交必须退回可重试的等待态：$returned")
        assertTrue(
            controller.state.value.notice.orEmpty().contains(ApprovalNotices.FAILED),
            "失败必须如实提示：${controller.state.value.notice}",
        )

        repository.submissionOutcome = ApprovalSubmissionOutcome.SUBMITTED
        controller.decideApproval(APPROVAL_ID, ApprovalChoice.DENY)
        advanceUntilIdle()
        assertEquals(2, repository.decisions.size, "退回等待态后必须允许重试")
    }

    @Test
    fun aReplayedRequestDoesNotProduceASecondCard() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()

        repository.events.emit(requested())
        advanceUntilIdle()
        repository.events.emit(requested(eventId = "evt_requested_again"))
        advanceUntilIdle()

        val timeline = controller.state.value.timeline as Loadable.Ready
        assertEquals(
            1,
            timeline.value.count { it.approval != null },
            "重放的审批事件不得让同一张卡片出现两次",
        )
    }

    @Test
    fun aSettledCardIsNotReopenedByALateRequest() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.DENY)
        repository.events.emit(resolved(outcome = ApprovalOutcome.DENIED))
        advanceUntilIdle()
        // A reconnect replays the stream; the request arrives again after the
        // answer, which must not put a live button under a settled command.
        repository.events.emit(requested(eventId = "evt_requested_replay"))
        advanceUntilIdle()

        val replayed = cardRow(controller)!!.approval
        assertTrue(replayed is ApprovalCardState.Resolved, "重连重放不得让已落定的卡片复活：$replayed")
        assertEquals(ApprovalOutcome.DENIED, (replayed as ApprovalCardState.Resolved).outcome)
    }

    @Test
    fun leavingTheThreadAndComingBackKeepsTheSameCardAndCountdown() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.openThread(OTHER_THREAD_ID)
        advanceUntilIdle()
        assertNull(cardRow(controller), "别的会话不得显示这张卡片")

        controller.openThread(THREAD_ID)
        advanceUntilIdle()

        val card = cardRow(controller)?.approval
        assertNotNull(card, "切回会话必须还是同一张卡片")
        val restored = card!!
        assertEquals(APPROVAL_ID, restored.request.approvalId.value)
        // The countdown is derived from the Gateway's own timestamps, so it does
        // not restart when the row is drawn again.
        assertEquals(REQUESTED_AT, restored.request.requestedAt)
        assertEquals(REQUESTED_AT + TIMEOUT_SECONDS * 1_000L, restored.request.expiresAt)
    }

    @Test
    fun anExpiredCardRefusesThePressInsteadOfAskingTheGateway() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        now = REQUESTED_AT + TIMEOUT_SECONDS * 1_000L + 1L
        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()

        assertTrue(
            repository.decisions.isEmpty(),
            "本地已超时的卡片不得再向 Gateway 提交一个必然被拒的决策",
        )
        assertTrue(
            controller.state.value.notice.orEmpty().contains(ApprovalNotices.EXPIRED),
            "超时必须如实提示：${controller.state.value.notice}",
        )
        assertTrue(
            cardRow(controller)?.approval is ApprovalCardState.Waiting,
            "本地倒计时不得把卡片写成终态，只有网关的超时事件可以",
        )

        // The Gateway's own timeout is what settles it.
        repository.events.emit(resolved(outcome = ApprovalOutcome.TIMED_OUT))
        advanceUntilIdle()
        val timedOut = cardRow(controller)!!.approval
        assertTrue(timedOut is ApprovalCardState.Resolved, "网关的超时事件必须落定卡片：$timedOut")
        assertEquals(ApprovalOutcome.TIMED_OUT, (timedOut as ApprovalCardState.Resolved).outcome)
    }

    @Test
    fun aGatewayAlreadySettledElsewhereIsReportedNotRewritten() = runWorkbench {
        val repository = FakeRepository().apply {
            submissionOutcome = ApprovalSubmissionOutcome.ALREADY_RESOLVED
            recordedChoice = ApprovalChoice.DENY
        }
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()

        val answered = cardRow(controller)!!.approval
        assertTrue(answered is ApprovalCardState.Resolved, "他处已决的卡片必须显示 Gateway 记录的结果：$answered")
        assertEquals(ApprovalOutcome.DENIED, (answered as ApprovalCardState.Resolved).outcome)
    }

    @Test
    fun aGatewayWithoutTheCapabilityGetsNoCardAndSaysSo() = runWorkbench {
        val repository = FakeRepository()
        val controller = controller(repository, supportsApprovalCards = false)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()

        assertNull(
            cardRow(controller),
            "不支持卡片的网关不得画出卡片：一张按钮点不动的卡片等于伪造了一个不存在的能力",
        )
        assertTrue(repository.decisions.isEmpty(), "没有决策端点的 Gateway 不得被假装提交")
        assertTrue(
            controller.state.value.notice.orEmpty().contains(ApprovalNotices.UNSUPPORTED),
            "不支持必须明示：${controller.state.value.notice}",
        )
        assertFalse(controller.state.value.approvalCardsSupported)
    }

    @Test
    fun anApprovalTheGatewayDoesNotKnowIsNotClaimedAsWithdrawn() = runWorkbench {
        val repository = FakeRepository().apply { submissionOutcome = ApprovalSubmissionOutcome.NOT_FOUND }
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()

        val answered = cardRow(controller)!!.approval
        assertTrue(answered is ApprovalCardState.Resolved, "无论如何这张卡片不能继续可点：$answered")
        assertEquals(
            "网关不认识这个审批时，命令的下场是本机未知的，不得替它宣称已撤回",
            ApprovalOutcome.UNKNOWN,
            (answered as ApprovalCardState.Resolved).outcome,
        )
    }

    @Test
    fun aSettledVerdictTheGatewayNamesIsShownInsteadOfUnknown() = runWorkbench {
        val repository = FakeRepository().apply {
            submissionOutcome = ApprovalSubmissionOutcome.ALREADY_RESOLVED
            recordedChoice = null
            recordedOutcome = ApprovalOutcome.TIMED_OUT
        }
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()

        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()

        val answered = cardRow(controller)!!.approval
        assertTrue(answered is ApprovalCardState.Resolved, "网关给出的终态必须直接落到卡片上：$answered")
        assertEquals(ApprovalOutcome.TIMED_OUT, (answered as ApprovalCardState.Resolved).outcome)
    }

    @Test
    fun anUnknownVerdictIsCorrectedByTheGatewaysOwnEvent() = runWorkbench {
        val repository = FakeRepository().apply {
            submissionOutcome = ApprovalSubmissionOutcome.ALREADY_RESOLVED
            recordedChoice = null
        }
        val controller = controller(repository)
        advanceUntilIdle()
        repository.events.emit(requested())
        advanceUntilIdle()
        controller.decideApproval(APPROVAL_ID, ApprovalChoice.ONCE)
        advanceUntilIdle()
        assertEquals(ApprovalOutcome.UNKNOWN, (cardRow(controller)?.approval as ApprovalCardState.Resolved).outcome)

        repository.events.emit(resolved(outcome = ApprovalOutcome.TIMED_OUT))
        advanceUntilIdle()

        assertEquals(
            "「未知」不是终态：网关随后给出的事实必须能落到卡片上",
            ApprovalOutcome.TIMED_OUT,
            (cardRow(controller)?.approval as ApprovalCardState.Resolved).outcome,
        )
    }

    private class FakeRepository : ConversationRepository {
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        val decisions = mutableListOf<Pair<String, ApprovalChoice>>()
        var submissionOutcome: ApprovalSubmissionOutcome = ApprovalSubmissionOutcome.SUBMITTED
        var recordedChoice: ApprovalChoice? = ApprovalChoice.ONCE

        /** The terminal verdict the Gateway already had, when it names one. */
        var recordedOutcome: ApprovalOutcome? = null

        override suspend fun listConversations(
            scope: ConversationScope,
            page: PageRequest,
        ): ConversationPage = ConversationPage(
            listOf(
                ConversationSummary(ConversationId(THREAD_ID), "审批会话", 1L),
                ConversationSummary(ConversationId(OTHER_THREAD_ID), "另一个会话", 2L),
            ),
            null,
        )

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            Conversation(ConversationId(clientConversationId), "新对话", 0L)

        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
            TimelinePage(
                listOf(
                    TimelineMessage(
                        id = "msg_seed_$conversationId",
                        sender = "user",
                        parts = listOf(MessagePart.Text("开场")),
                        timestamp = 1L,
                        conversationId = ConversationId(conversationId),
                    ),
                ),
                null,
            )

        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
            BatchAcceptance(batch.batchId, emptyMap())

        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
            MessageAcceptance("msg_server", message.clientMessageId.value)

        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events

        override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)

        override suspend fun submitApprovalDecision(
            approvalId: ApprovalId,
            choice: ApprovalChoice,
        ): ApprovalSubmissionResult {
            decisions += approvalId.value to choice
            return ApprovalSubmissionResult(
                outcome = submissionOutcome,
                choice = if (submissionOutcome == ApprovalSubmissionOutcome.SUBMITTED) choice else recordedChoice,
                settledOutcome = recordedOutcome,
            )
        }
    }

    private companion object {
        const val THREAD_ID = "conv_approval"
        const val OTHER_THREAD_ID = "conv_other"
        const val APPROVAL_ID = "apr_1"
        const val REQUESTED_AT = 1_780_000_000_000L
        const val TIMEOUT_SECONDS = 300L

        val DEFAULT_OPTIONS = listOf(
            ApprovalOption(ApprovalChoice.ONCE, style = ApprovalOptionStyle.PRIMARY),
            ApprovalOption(ApprovalChoice.ALWAYS, style = ApprovalOptionStyle.SECONDARY),
            ApprovalOption(ApprovalChoice.DENY, style = ApprovalOptionStyle.DANGER),
        )
    }
}
