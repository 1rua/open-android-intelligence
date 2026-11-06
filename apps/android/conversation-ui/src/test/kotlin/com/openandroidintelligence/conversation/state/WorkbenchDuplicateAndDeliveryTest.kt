package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 回归两个现场问题：
 *
 * 1. 收到回复后，回复下方偶尔多出一条"上一轮消息"，切会话后消失；
 * 2. 原生会话下"等待 Agent 接收"必须等到首个 token，网关的 delivered 到不了工作台。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchDuplicateAndDeliveryTest {
    @Test fun repeatedSteeringKeepsEveryOriginalUserInputWhenHistoryAndReceiptsRepeat() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        val originals = listOf("先检查第一项", "再检查第二项", "最后保留结果")
        for (text in originals) {
            controller.editDraft(text)
            controller.sendDraft(MessageDeliveryMode.STEER)
            runCurrent()
        }
        val local = rows(controller).filter { it.isUser }
        assertEquals(originals, local.map { it.text })
        assertEquals(3, local.map { it.clientMessageId }.toSet().size)
        for ((index, entry) in local.withIndex()) {
            val event = VerifiedConversationEvent.TimelineUpsert("history-steer-$index", entry.timestamp, 1,
                TimelineMessage("native-steer-$index", "user", listOf(MessagePart.Text(originals[index])), entry.timestamp,
                    conversationId = ConversationId("conv_a"), clientMessageId = entry.clientMessageId, generationId = "gen_live"))
            repository.events.emit(event); repository.events.emit(event); runCurrent()
        }
        assertEquals(originals, rows(controller).filter { it.isUser }.map { it.text })
        assertEquals(3, rows(controller).count { it.isUser })
        assertTrue(rows(controller).all { it.generationId == "gen_live" })
        assertEquals(GenerationState.RUNNING, controller.state.value.generation)
        assertEquals(3, repository.steered.size)
        controller.close()
    }


    @Test
    fun serverAcceptanceOnlyPreservesWaitingForAgentUntilItsDeliveredReceipt() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        try {
            runCurrent()
            controller.editDraft("等待 Agent 接收")
            controller.sendDraft()
            runCurrent()
            val prompt = rows(controller).single { it.isUser }
            assertEquals(AgentMessageStatus.QUEUED, prompt.messageStatus)
            repository.events.emit(VerifiedConversationEvent.MessageAccepted(
                "server-only", 100, prompt.key, prompt.clientMessageId!!.value, ConversationId("conv_a"),
                agentReceived = false,
            ))
            runCurrent()
            assertEquals(AgentMessageStatus.QUEUED, rows(controller).single { it.isUser }.messageStatus)
            repository.events.emit(VerifiedConversationEvent.MessageStatus(
                "native-delivered", 101, ConversationId("conv_a"), prompt.key, prompt.clientMessageId!!,
                AgentMessageStatus.DELIVERED, 1, null, "native-generation",
            ))
            runCurrent()
            assertEquals(AgentMessageStatus.DELIVERED, rows(controller).single { it.isUser }.messageStatus)
        } finally { controller.close() }
    }

    @Test
    fun aPlatformUserRowWithAnotherIdCollapsesIntoTheAcceptedRow() = runTest {
        val repository = Repository()
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        controller.editDraft("在吗在吗")
        controller.sendDraft()
        runCurrent()
        val accepted = rows(controller).first { it.isUser }

        repository.generationId.value = "gen_a"
        // 回复用真实时钟且晚于发送时刻：这样"平台行时间戳更晚"才能体现排序影响。
        repository.events.emit(stream("reply", "gen_a", "在的", timestamp = accepted.timestamp + 60_000L))
        runCurrent()
        // 同一条 prompt 又以上报端 id 到达，且时间戳更晚：不能变成回复下方的第二条气泡。
        repository.events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                "user_platform", 9, 2,
                TimelineMessage(
                    "user_from_platform", "user", listOf(MessagePart.Text("在吗在吗")),
                    accepted.timestamp + 5_000L, "CONFIRMED", ConversationId("conv_a"),
                    clientMessageId = accepted.clientMessageId,
                ),
            ),
        )
        runCurrent()

        val rendered = rows(controller)
        assertEquals(1, rendered.count { it.isUser })
        val user = rendered.first { it.isUser }
        val reply = rendered.first { it.text == "在的" }
        // 只保留一行，并沿用发送时刻：不会再跑到回复下方。
        assertEquals(accepted.clientMessageId, user.clientMessageId)
        assertTrue("用户消息必须仍在回复之上", user.timestamp < reply.timestamp)
        controller.close()
    }

    @Test
    fun duplicateUserRowsAreCollapsedEvenWhenNotAdjacent() = runTest {
        val repository = Repository()
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        repository.generationId.value = "gen_a"
        repository.events.emit(stream("reply", "gen_a", "回答"))
        runCurrent()
        val clientId = ClientMessageId("cm_repeated")
        repository.events.emit(userUpsert("u_early", clientId, "重复的问题", 100L))
        runCurrent()
        repository.events.emit(userUpsert("u_late", clientId, "重复的问题", 900_000L))
        runCurrent()

        assertEquals(1, rows(controller).count { it.isUser })
        controller.close()
    }

    @Test
    fun gatewayDeliveredEventPromotesThePromptWithoutBlockingTheTerminalStatus() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        assertEquals(AgentMessageStatus.QUEUED, prompt.messageStatus)

        repository.generationId.value = "gen_a"
        controller.onGatewayDeliveryStatus(
            VerifiedConversationEvent.MessageStatus(
                "gw_delivered", 5, ConversationId("conv_a"), "server_side_id",
                prompt.clientMessageId!!, AgentMessageStatus.DELIVERED, 1, null, "gen_a",
            ),
        )
        runCurrent()
        assertEquals(AgentMessageStatus.DELIVERED, rows(controller).first { it.isUser }.messageStatus)

        // 平台事件不写 statusRevision，会话流的终态（revision 1）仍必须生效。
        repository.events.emit(
            VerifiedConversationEvent.MessageStatus(
                "native_completed", 6, ConversationId("conv_a"), prompt.key,
                prompt.clientMessageId!!, AgentMessageStatus.COMPLETED, 1, null, "gen_a",
            ),
        )
        runCurrent()
        assertEquals(AgentMessageStatus.COMPLETED, rows(controller).first { it.isUser }.messageStatus)
        controller.close()
    }

    @Test
    fun acceptedMessagePromotesThePromptWithoutWaitingForTheFirstToken() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("查看设备状态")
        controller.sendDraft()
        runCurrent()
        val prompt = rows(controller).first { it.isUser }
        assertEquals(AgentMessageStatus.QUEUED, prompt.messageStatus)

        // 会话流确认身份（gateway 已接收），此时还没有任何正文。
        repository.generationId.value = "gen_a"
        repository.events.emit(
            VerifiedConversationEvent.MessageAccepted(
                "accepted_1", 3, "server_side_id", prompt.clientMessageId!!.value, ConversationId("conv_a"),
            ),
        )
        runCurrent()
        assertEquals(AgentMessageStatus.DELIVERED, rows(controller).first { it.isUser }.messageStatus)

        repository.events.emit(
            VerifiedConversationEvent.MessageStatus(
                "native_completed", 6, ConversationId("conv_a"), prompt.key,
                prompt.clientMessageId!!, AgentMessageStatus.COMPLETED, 1, null, "gen_a",
            ),
        )
        runCurrent()
        assertEquals(AgentMessageStatus.COMPLETED, rows(controller).first { it.isUser }.messageStatus)
        controller.close()
    }

    @Test
    fun retryAfterABusyFailureNeverLeavesDuplicateTimelineKeys() = runTest {
        val repository = Repository()
        repository.failNextSend = true
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("在吗")
        controller.sendDraft()
        runCurrent()
        // 失败后原子以 local_<clientId> 呈现，这就是重试时最容易撞 key 的一行。
        assertEquals(1, rows(controller).count { it.isUser })

        controller.editDraft("在吗")
        controller.sendDraft()
        runCurrent()

        val rendered = rows(controller)
        assertEquals("重复 key 会让 LazyColumn 直接崩溃", rendered.size, rendered.map { it.key }.distinct().size)
        assertEquals(1, rendered.count { it.isUser })
        controller.close()
    }

    @Test
    fun queueChoiceCreatesASeparateCardUntilTheHostAcceptsTheMessage() = runTest {
        val repository = Repository()
        repository.queueNextSend = true
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        controller.editDraft("等当前任务结束后再发")
        controller.sendDraft(MessageDeliveryMode.QUEUE)
        runCurrent()

        val queued = controller.state.value.pendingDeliveries.single()
        assertEquals("等当前任务结束后再发", queued.text)
        assertEquals(PendingDeliveryState.QUEUED, queued.state)
        assertEquals("", controller.state.value.draft)
        assertFalse("排队项不能提前变成普通用户气泡", rows(controller).any { it.isUser })

        repository.events.emit(
            VerifiedConversationEvent.MessageAccepted(
                eventId = "accepted_queue",
                occurredAt = 100,
                messageId = "server_queue",
                correlationId = queued.clientMessageId.value,
                conversationId = ConversationId("conv_a"),
            ),
        )
        runCurrent()

        assertTrue(controller.state.value.pendingDeliveries.isEmpty())
        assertEquals(1, rows(controller).count { it.isUser })
        controller.close()
    }

    @Test
    fun steeringChoiceUsesTheLiveTurnAndNeverBecomesAQueuedNewTurn() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        controller.editDraft("先检查失败用例")
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        assertEquals(listOf("先检查失败用例"), repository.steered)
        assertTrue(repository.submitted.isEmpty())
        assertTrue(controller.state.value.pendingDeliveries.isEmpty())
        assertEquals("STEERED:CURRENT_TURN", controller.state.value.notice)
        // 新规格：引导始终保留用户身份，原文不拼接系统前缀，同任务不结束运行状态
        val userRow = rows(controller).single { it.isUser }
        assertEquals("先检查失败用例", userRow.text)
        assertTrue("引导输入不得拼接已引导系统前缀", rows(controller).none { it.text.contains("已引导") })
        assertEquals("任务保持运行，引导不提前终态", GenerationState.RUNNING, repository.generationState.value)
        controller.close()
    }

    @Test
    fun steeringWithProvisionalAcceptanceKeepsUserRowAndPreservesGenerationForRecovery() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        controller.editDraft("引导保留用户原文且记住generation")
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        val userRow = rows(controller).single { it.isUser }
        assertEquals("引导保留用户原文且记住generation", userRow.text)
        assertEquals(AgentMessageStatus.DELIVERED, userRow.messageStatus)
        // 验证迟到的远端权威用户行到达后能够原位升级且不会拆分为两行
        repository.events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                "user_remote_authoritative", 50, 1,
                TimelineMessage(
                    id = "msg_server_authoritative_steer_1",
                    sender = "user",
                    parts = listOf(MessagePart.Text("引导保留用户原文且记住generation")),
                    timestamp = userRow.timestamp,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_a"),
                    clientMessageId = userRow.clientMessageId,
                    generationId = "gen_live",
                )
            )
        )
        runCurrent()

        val updatedUserRow = rows(controller).single { it.isUser }
        assertEquals("msg_server_authoritative_steer_1", updatedUserRow.key)
        assertFalse("获得权威服务端消息后不应是占位符", updatedUserRow.pendingAcceptance)
        assertEquals("gen_live", updatedUserRow.generationId)
        controller.close()
    }

    @Test
    fun steeringFailureOrOutcomeUnknownRetainsUserBubbleIdentity() = runTest {
        for (loseReceipt in listOf(false, true)) {
            val repository = Repository()
            repository.generationId.value = "gen_live"
            repository.generationState.value = GenerationState.RUNNING
            if (loseReceipt) {
                repository.loseNextSteerReceipt = true
            } else {
                repository.failNextSteer = true
            }
            val controller = controller(repository, resetGenerationOnOpen = false)
            runCurrent()
            controller.editDraft(if (loseReceipt) "网络丢失回执的引导" else "明确失败的引导")
            controller.sendDraft(MessageDeliveryMode.STEER)
            runCurrent()

            // 无论明确失败还是回执丢失/结果未知，用户气泡身份必须始终为 user，且原任务不被中断退出
            val userRow = rows(controller).single { it.isUser }
            assertEquals(if (loseReceipt) "网络丢失回执的引导" else "明确失败的引导", userRow.text)
            assertEquals("user", userRow.sender)
            assertTrue("气泡身份必须始终为 user", userRow.isUser)
            assertEquals("原任务保持运行", GenerationState.RUNNING, repository.generationState.value)
            controller.close()
        }
    }

    @Test
    fun remoteAuthoritativeUserArrivingBeforeSteerAcceptanceResolvesCleanly() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val gate = kotlinx.coroutines.CompletableDeferred<MessageAcceptance>()
        repository.steerGate = gate
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        controller.editDraft("乱序先到引导")
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        // 此时 steerMessage 仍在等待 gate，本地已记录 clientMessageId 并上屏占位行
        assertEquals(1, repository.steeringClientIds.size)
        val clientId = repository.steeringClientIds.single()
        val prompt = rows(controller).single { it.isUser }
        assertEquals(clientId, prompt.clientMessageId)

        // 远端真实权威 user 消息在 acceptance 前先到达
        repository.events.emit(
            VerifiedConversationEvent.TimelineUpsert(
                "user_remote_early", 60, 1,
                TimelineMessage(
                    id = "msg_server_early_steer",
                    sender = "user",
                    parts = listOf(MessagePart.Text("乱序先到引导")),
                    timestamp = prompt.timestamp,
                    state = "CONFIRMED",
                    conversationId = ConversationId("conv_a"),
                    clientMessageId = clientId,
                    generationId = "gen_live",
                ),
            ),
        )
        runCurrent()

        // 随后 acceptance 终于到达并 complete
        gate.complete(
            MessageAcceptance(
                clientId.value,
                clientId.value,
                "gen_live",
                MessageDeliveryMode.STEER,
                authoritative = false,
            ),
        )
        runCurrent()

        val rendered = rows(controller)
        assertEquals("乱序先到的远端行与本地引导记录必须唯一合并，不得拆分为两行", 1, rendered.count { it.isUser })
        val finalUser = rendered.single { it.isUser }
        assertEquals("msg_server_early_steer", finalUser.key)
        assertFalse("获得权威服务端消息后不应是占位符", finalUser.pendingAcceptance)
        assertEquals("gen_live", finalUser.generationId)
        assertEquals("原任务保持运行", GenerationState.RUNNING, repository.generationState.value)
        controller.close()
    }

    @Test
    fun steeringAcceptedPersistsAcceptedStateAndRealGenerationWithoutResendingOnRestart() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val persistence = MemoryPersistence()
        val controller = controller(repository, resetGenerationOnOpen = false, persistence = persistence)
        runCurrent()

        controller.editDraft("持久化保存的引导")
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()
        controller.flushPendingPersistence()

        // 1. 验证 LocalSendAtom 明确为 ACCEPTED，且无合成的假服务器 ID（仍然保留 clientId 占位），记录 generation
        val atoms = persistence.loadSendAtoms("conv_a")
        val steerAtom = atoms.single { it.clientMessageId.value in repository.steeringClientIds.map { id -> id.value } }
        assertEquals("已受理工件必须持久化为 ACCEPTED", LocalSubmissionState.ACCEPTED, steerAtom.submissionState)
        assertFalse("禁止产生 steer_ 假服务器 ID", steerAtom.messageId?.startsWith("steer_") == true)
        assertEquals("steer 投递模式正确", MessageDeliveryMode.STEER, steerAtom.delivery)

        // 2. 验证 checkpoint 中持久化记录了真实 generationId
        val checkpoint = persistence.checkpoint!!
        assertTrue(
            "checkpoint 必须持久记住 clientId 与真实 generationId 的关联",
            checkpoint.messageGenerations.any {
                it.conversationId == "conv_a" &&
                it.messageId == steerAtom.clientMessageId.value &&
                it.generationId == "gen_live"
            },
        )

        controller.close()

        // 3. 重启 Controller，验证不会重新触发第二轮 steerMessage 投递
        val restartedRepo = Repository()
        restartedRepo.generationId.value = "gen_live"
        restartedRepo.generationState.value = GenerationState.RUNNING
        val restarted = controller(restartedRepo, resetGenerationOnOpen = false, persistence = persistence)
        runCurrent()

        assertEquals("重启后已 ACCEPTED 的引导绝不二次投递", 0, restartedRepo.steered.size)
        val reopenedRow = rows(restarted).single { it.isUser }
        assertEquals("持久化恢复后用户正文完整", "持久化保存的引导", reopenedRow.text)
        assertEquals("gen_live", reopenedRow.generationId)
        restarted.close()
    }

    @Test
    fun steeringDoesNotRequireAClientCapabilityAndKeepsTheOriginalCommandText() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        val text = " /host-command 'a b'\n 保留末尾空白  "
        controller.editDraft(text)
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        assertEquals(listOf(text), repository.steered)
        assertTrue(repository.submitted.isEmpty())
        assertTrue(controller.state.value.pendingDeliveries.isEmpty())
        controller.close()
    }

    @Test
    fun selectedSteeringIsNotChangedIntoANormalMessageWhenLocalStateIsIdle() = runTest {
        val repository = Repository()
        val controller = controller(repository)
        runCurrent()
        controller.editDraft("将已选择的引导交给宿主处理")
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        assertEquals(listOf("将已选择的引导交给宿主处理"), repository.steered)
        assertTrue(repository.submitted.isEmpty())
        controller.close()
    }

    @Test
    fun aKnownSteeringFailureCanBeSentAsTheNewlySelectedQueueMode() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        repository.failNextSteer = true
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        val text = "保留内容并改选排队"
        controller.editDraft(text)
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()
        assertEquals(text, controller.state.value.draft)
        val failedClientId = repository.steeringClientIds.single()

        repository.queueNextSend = true
        controller.sendDraft(MessageDeliveryMode.QUEUE)
        runCurrent()

        assertEquals(listOf(MessageDeliveryMode.QUEUE), repository.submittedModes)
        assertEquals(listOf(text), repository.steered)
        assertNotEquals("改变发送方式后不得复用已提交的请求身份", failedClientId, repository.submittedClientIds.single())
        controller.close()
    }

    @Test
    fun retryingSteeringAfterAnExplicitNotForwardedResponseUsesANewIdentity() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        repository.failNextSteer = true
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        val text = "宿主尚未开始当前轮次，稍后再次引导"
        controller.editDraft(text)
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()
        assertEquals(text, controller.state.value.draft)
        val rejectedClientId = repository.steeringClientIds.single()

        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        assertEquals(listOf(text, text), repository.steered)
        assertNotEquals("明确未转交的重试必须创建新请求", rejectedClientId, repository.steeringClientIds.last())
        assertEquals("明确未转交不得查询为旧操作", 0, repository.messageQueries)
        assertTrue(repository.submitted.isEmpty())
        controller.close()
    }

    @Test
    fun anUnknownSteeringOutcomeDoesNotBlockANewSendUnderAnotherDeliveryMode() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        repository.loseNextSteerReceipt = true
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        val text = "先确认当前引导是否已被接收"
        controller.editDraft(text)
        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()
        assertTrue(controller.state.value.hasUnknownSubmissionOutcome)
        assertEquals(text, controller.state.value.draft)
        val unknownClientId = repository.steeringClientIds.single()

        controller.sendDraft(MessageDeliveryMode.QUEUE)
        runCurrent()

        assertEquals(listOf(text), repository.steered)
        assertEquals(listOf(text), repository.submitted)
        assertEquals(listOf(MessageDeliveryMode.QUEUE), repository.submittedModes)
        assertNotEquals(unknownClientId, repository.submittedClientIds.single())
        assertTrue(controller.state.value.pendingDeliveries.isEmpty())
        assertEquals("", controller.state.value.draft)
        assertTrue("旧 steer 结果仍保留为未知", controller.state.value.hasUnknownSubmissionOutcome)
        assertEquals(setOf(unknownClientId, repository.submittedClientIds.single()),
            rows(controller).filter { it.isUser }.mapNotNull { it.clientMessageId }.toSet())
        assertEquals(0, repository.messageQueries)
        controller.close()
    }

    @Test
    fun multipleUnknownSubmissionsWithTheSameTextDoNotBlockAnotherExplicitSend() = runTest {
        val repository = Repository()
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val controller = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        val text = "重复正文也代表一次新的用户操作"

        repeat(2) {
            repository.loseNextSteerReceipt = true
            controller.editDraft(text)
            controller.sendDraft(MessageDeliveryMode.STEER)
            runCurrent()
        }
        val unknownIds = repository.steeringClientIds.toList()
        assertEquals(2, unknownIds.size)
        assertEquals(2, unknownIds.toSet().size)
        assertTrue(controller.state.value.hasUnknownSubmissionOutcome)

        controller.sendDraft(MessageDeliveryMode.STEER)
        runCurrent()

        assertEquals(listOf(text, text, text), repository.steered)
        assertEquals(3, repository.steeringClientIds.toSet().size)
        assertTrue("新发送不得复用任一未知提交身份", repository.steeringClientIds.last() !in unknownIds)
        assertEquals(3, rows(controller).count { it.isUser })
        assertEquals(0, repository.messageQueries)
        controller.close()
    }

    @Test
    fun queuedMessagesSurviveSessionChangeAndFailedStateIsRetriable() = runTest {
        val repository = Repository()
        repository.queueNextSend = true
        repository.generationId.value = "gen_live"
        repository.generationState.value = GenerationState.RUNNING
        val first = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        first.editDraft("重连后仍需保留")
        first.sendDraft(MessageDeliveryMode.QUEUE)
        runCurrent()
        val queued = first.state.value.pendingDeliveries.single()
        first.close()

        val reopened = controller(repository, resetGenerationOnOpen = false)
        runCurrent()
        assertEquals(
            listOf("重连后仍需保留"),
            reopened.state.value.pendingDeliveries.map { it.text },
        )

        repository.events.emit(
            VerifiedConversationEvent.PendingDeliveryUpdated(
                eventId = "queue_failed",
                occurredAt = 200,
                delivery = queued.copy(
                    state = PendingDeliveryState.FAILED,
                    errorCode = "AGENT_OFFLINE",
                ),
            ),
        )
        runCurrent()
        assertEquals(PendingDeliveryState.FAILED, reopened.state.value.pendingDeliveries.single().state)

        reopened.retryPendingDeliveries()
        runCurrent()
        assertEquals(1, repository.retryCalls)
        reopened.close()
    }

    private fun userUpsert(id: String, clientId: ClientMessageId, text: String, timestamp: Long) =
        VerifiedConversationEvent.TimelineUpsert(
            "evt_$id", timestamp, 1,
            TimelineMessage(
                id, "user", listOf(MessagePart.Text(text)), timestamp, "CONFIRMED",
                ConversationId("conv_a"), clientMessageId = clientId,
            ),
        )

    private fun rows(controller: WorkbenchController) = (controller.state.value.timeline as Loadable.Ready).value

    private var eventNumber = 0

    private fun stream(
        id: String,
        generation: String?,
        text: String,
        state: String = "STREAMING",
        timestamp: Long = 2L,
    ) = VerifiedConversationEvent.TimelineUpsert(
        "event_${++eventNumber}_$id", timestamp, 1,
        TimelineMessage(id, "assistant", listOf(MessagePart.Text(text)), timestamp, state,
            ConversationId("conv_a"), generationId = generation),
    )

    private class MemoryPersistence : WorkbenchPersistence {
        var checkpoint: WorkbenchCheckpoint? = null
        private val atoms = mutableMapOf<String, List<LocalSendAtom>>()
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
        override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) { this.atoms[conversationId] = atoms }
    }

    private fun TestScope.controller(
        repository: Repository,
        resetGenerationOnOpen: Boolean = true,
        persistence: WorkbenchPersistence? = null,
    ) = WorkbenchController(
        workbenchTestScope(), repository, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("p", "g", "a", "i") },
        persistence = persistence,
        persistenceDispatcher = StandardTestDispatcher(testScheduler),
        onActiveThreadChanged = {
            if (resetGenerationOnOpen) {
                repository.generationId.value = null
                repository.generationState.value = GenerationState.IDLE
            }
        },
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private class Repository : ConversationRepository, GenerationTracker, MessageOutcomeQuery {
        var messageQueries = 0
        override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? {
            messageQueries++
            return null
        }
        override val generationId = MutableStateFlow<String?>(null)
        override val generationState = MutableStateFlow(GenerationState.IDLE)
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf(ConversationSummary(ConversationId("conv_a"), "A", 1)), null,
        )
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_new"), "新对话", 1)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        val submitted = mutableListOf<String>()
        val submittedModes = mutableListOf<MessageDeliveryMode>()
        val submittedClientIds = mutableListOf<ClientMessageId>()
        val steered = mutableListOf<String>()
        val steeringClientIds = mutableListOf<ClientMessageId>()
        private val pendingByConversation = mutableMapOf<String, MutableList<PendingDelivery>>()
        var queueNextSend = false
        var retryCalls = 0
        /** 首次发送模拟"会话忙"，用于覆盖失败后重试的重复 key 场景。 */
        var failNextSend = false
        var failNextSteer = false
        var loseNextSteerReceipt = false
        var steerGate: kotlinx.coroutines.CompletableDeferred<MessageAcceptance>? = null
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            if (failNextSend) {
                failNextSend = false
                throw IllegalStateException("SESSION_BUSY")
            }
            submitted += message.text
            submittedModes += message.delivery
            submittedClientIds += message.clientMessageId
            if (queueNextSend) {
                pendingByConversation.getOrPut("conv_a") { mutableListOf() } += PendingDelivery(
                    message.clientMessageId,
                    ConversationId("conv_a"),
                    message.text,
                    message.attachmentIds.size,
                    PendingDeliveryState.QUEUED,
                )
            }
            return MessageAcceptance(
                "server_side_id",
                message.clientMessageId.value,
                delivery = if (queueNextSend) MessageDeliveryMode.QUEUE else MessageDeliveryMode.NORMAL,
            )
        }
        override suspend fun steerMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            steered += message.text
            steeringClientIds += message.clientMessageId
            steerGate?.let { return it.await() }
            if (loseNextSteerReceipt) {
                loseNextSteerReceipt = false
                throw java.io.IOException("STEERING_RECEIPT_LOST")
            }
            if (failNextSteer) {
                failNextSteer = false
                error("STEER_UNAVAILABLE")
            }
            return MessageAcceptance(
                message.clientMessageId.value,
                message.clientMessageId.value,
                "gen_live",
                MessageDeliveryMode.STEER,
                authoritative = false,
            )
        }
        override suspend fun pendingDeliveries(conversationId: String): List<PendingDelivery> =
            pendingByConversation[conversationId].orEmpty().toList()
        override suspend fun retryPendingDeliveries(conversationId: String) {
            retryCalls++
        }
        override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(
            batch.batchId, batch.messages.associate { it.clientMessageId.value to "server_side_id" },
        )
        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override suspend fun cancelGeneration(conversationId: String, generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
