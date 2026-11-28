package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredExecutionDisplayTest {
    private fun entry(key: String, generation: String?, text: String = "", steps: List<NativeExecutionStep> = emptyList()) =
        TimelineEntry(key, "assistant", text, false, 0L, false, null,
            generationId = generation, executionSteps = steps)

    private fun step(id: String, revision: Long = 1, status: NativeExecutionStatus = NativeExecutionStatus.COMPLETED) =
        NativeExecutionStep(id, "event-$revision", "read_file", "{}", "synthetic result", status, revision)

    @Test
    fun nativeStepsAreOneCardBeforeIndependentFinalAnswer() {
        val items = TimelineDisplayModel().build(listOf(
            entry("call-message-one", "g-one", steps = listOf(step("call-one"))),
            entry("call-message-two", "g-one", steps = listOf(step("call-two"))),
            entry("final", "g-one", "final answer"),
        ))
        assertEquals(2, items.size)
        val run = (items[0] as TimelineDisplayItem.Execution).run
        assertEquals("call-message-one", run.segmentKey)
        assertEquals("g-one", run.generationId)
        assertEquals(2, run.stepCount)
        assertTrue(!run.hasFinalAnswer)
        assertEquals("final", (items[1] as TimelineDisplayItem.Single).entry.key)
    }

    @Test
    fun steeringBetweenNativeToolSourcesKeepsTwoOrderedSegmentsOfTheSameGeneration() {
        val firstSource = entry(
            "execution:gen1:source1",
            "gen1",
            steps = listOf(step("step1")),
        ).copy(timestamp = 1_000L, executionFact = true)
        val steering = TimelineEntry(
            key = "user-steer",
            sender = "user",
            text = "继续检查下一项",
            isUser = true,
            timestamp = 2_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "gen1",
        )
        val secondSource = entry(
            "execution:gen1:source2",
            "gen1",
            steps = listOf(step("step2")),
        ).copy(timestamp = 3_000L, executionFact = true)

        val items = TimelineDisplayModel().build(listOf(firstSource, steering, secondSource))

        assertEquals(3, items.size, "引导前后的工具必须留在各自位置，形成两个连续展示段")
        val firstRun = (items[0] as TimelineDisplayItem.Execution).run
        assertEquals(steering, (items[1] as TimelineDisplayItem.Single).entry)
        val secondRun = (items[2] as TimelineDisplayItem.Execution).run
        assertEquals(listOf("gen1", "gen1"), listOf(firstRun.finalEntry.generationId, secondRun.finalEntry.generationId))
        assertEquals(listOf("step1"), firstRun.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId })
        assertEquals(listOf("step2"), secondRun.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId })
        assertEquals(3, items.map { it.key }.distinct().size, "同一任务的不同展示段必须有各自稳定的列表身份")
    }

    @Test
    fun nativeSegmentsShareGenerationLifecycleAcrossSteeringAndCompletion() {
        val before = entry(
            "execution-fact:g:source-before",
            "g",
            steps = listOf(step("step-before").copy(sourceMessageId = "source-before", occurredAt = 1_000L)),
        ).copy(timestamp = 1_000L, executionFact = true, executionGenerationState = GenerationState.RUNNING)
        val steering = TimelineEntry(
            key = "steering",
            sender = "user",
            text = "继续下一项",
            isUser = true,
            timestamp = 2_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g",
        )
        val after = entry(
            "execution-fact:g:source-after",
            "g",
            steps = listOf(step("step-after", status = NativeExecutionStatus.RUNNING)
                .copy(sourceMessageId = "source-after", occurredAt = 3_000L)),
        ).copy(timestamp = 3_000L, executionFact = true, executionGenerationState = GenerationState.RUNNING)
        val model = TimelineDisplayModel()

        val runningItems = model.build(listOf(before, steering, after))
        val runningRuns = runningItems.filterIsInstance<TimelineDisplayItem.Execution>().map { it.run }
        assertEquals(3, runningItems.size)
        assertEquals(listOf("g", "g"), runningRuns.map { it.generationId })
        assertEquals(listOf(true, true), runningRuns.map { it.isStreaming }, "步骤完成不能提前结束引导前的同任务展示段")
        assertEquals(steering, (runningItems[1] as TimelineDisplayItem.Single).entry)
        assertEquals(NativeExecutionStatus.COMPLETED,
            (runningRuns.first().steps.single() as ExecutionStep.ToolCall).block.executionStatus)
        assertFalse((runningRuns.first().steps.single() as ExecutionStep.ToolCall).entryIsStreaming,
            "任务仍运行不代表已完成的步骤仍在执行")
        assertTrue(runningRuns.none { it.finalEntry.isStreaming }, "任务生命周期不能改写原消息的正文流式状态")

        // 终态只结束所属任务；即使最后一步的旧快照仍为 RUNNING，也不能重开任务。
        val completedItems = model.build(listOf(
            before.copy(executionGenerationState = GenerationState.COMPLETED),
            steering,
            after.copy(executionGenerationState = GenerationState.COMPLETED),
        ))
        val completedRuns = completedItems.filterIsInstance<TimelineDisplayItem.Execution>().map { it.run }
        assertEquals(listOf("g", "g"), completedRuns.map { it.generationId })
        assertEquals(listOf(false, false), completedRuns.map { it.isStreaming })
        assertTrue(completedRuns.flatMap { it.steps }.filterIsInstance<ExecutionStep.ToolCall>().none { it.entryIsStreaming })
        assertEquals(runningItems.map { it.key }, completedItems.map { it.key })
        assertEquals(runningRuns.flatMap { it.steps }.map { it.stepKey }, completedRuns.flatMap { it.steps }.map { it.stepKey })
    }

    @Test
    fun sameToolNameAndTextNeverMovesStepsAcrossGenerations() {
        val items = TimelineDisplayModel().build(listOf(
            entry("tool-one", "g-one", steps = listOf(step("call-one"))),
            entry("answer-one", "g-one", "same answer"),
            entry("tool-two", "g-two", steps = listOf(step("call-two"))),
            entry("answer-two", "g-two", "same answer"),
        ))
        val runs = items.filterIsInstance<TimelineDisplayItem.Execution>().map { it.run }
        assertEquals(listOf("tool-one", "tool-two"), runs.map { it.segmentKey })
        assertEquals(listOf("g-one", "g-two"), runs.map { it.generationId })
        assertEquals(listOf("call-one", "call-two"), runs.map { (it.steps.single() as ExecutionStep.ToolCall).nativeStepId })
        assertEquals(listOf("answer-one", "answer-two"), items.filterIsInstance<TimelineDisplayItem.Single>().map { it.entry.key })
    }

    @Test
    fun duplicateStepSnapshotsRetainNewestRevisionAndStableKey() {
        val items = TimelineDisplayModel().build(listOf(
            entry("latest", "g", steps = listOf(step("call", 5))),
            entry("late-old", "g", steps = listOf(step("call", 2, NativeExecutionStatus.RUNNING))),
        ))
        val run = (items.single() as TimelineDisplayItem.Execution).run
        val tool = run.steps.single() as ExecutionStep.ToolCall
        assertEquals("execution:g:call", tool.stepKey)
        assertEquals(NativeExecutionStatus.COMPLETED, tool.block.executionStatus)
        assertTrue(!run.isStreaming)
    }

    @Test
    fun repeatedStepAcrossSteeringUpdatesOriginalCardWithoutMergingOtherGenerations() {
        val runningStep = step("shared-step", status = NativeExecutionStatus.RUNNING)
            .copy(output = null, sourceMessageId = "first-source", occurredAt = 1_000L)
        val first = entry("execution-fact:g-one:first-source", "g-one", steps = listOf(runningStep))
            .copy(sender = "system", timestamp = 1_000L, executionFact = true,
                executionGenerationState = GenerationState.RUNNING)
        val steering = TimelineEntry("steering", "user", "继续检查", true, 2_000L, false, null,
            generationId = "g-one")
        val completedStep = runningStep.copy(eventId = "completed-event", output = "第一任务的结果",
            status = NativeExecutionStatus.COMPLETED, revision = 2L)
        val repeated = first.copy(key = "later-step-snapshot", timestamp = 3_000L,
            executionSteps = listOf(completedStep))
        val model = TimelineDisplayModel()
        val initialRun = (model.build(listOf(first, steering)).first() as TimelineDisplayItem.Execution).run

        val updated = model.build(listOf(first, steering, repeated))

        assertEquals(2, updated.size, "同一步骤的迟到结果只更新原位卡片，不在引导后新增执行段")
        val updatedRun = (updated[0] as TimelineDisplayItem.Execution).run
        val updatedTool = updatedRun.steps.single() as ExecutionStep.ToolCall
        assertEquals(initialRun.segmentKey, updatedRun.segmentKey)
        assertEquals(initialRun.steps.single().stepKey, updatedTool.stepKey)
        assertEquals(NativeExecutionStatus.COMPLETED, updatedTool.block.executionStatus)
        assertEquals("第一任务的结果", updatedTool.block.output)
        assertFalse(updatedTool.entryIsStreaming)
        assertEquals(steering, (updated[1] as TimelineDisplayItem.Single).entry)

        val nextSource = entry("execution-fact:g-one:next-source", "g-one", steps = listOf(
            step("next-step").copy(sourceMessageId = "next-source", occurredAt = 4_000L),
        )).copy(sender = "system", timestamp = 4_000L, executionFact = true,
            executionGenerationState = GenerationState.RUNNING)
        val otherGeneration = entry("execution-fact:g-two:other-source", "g-two", steps = listOf(
            step("shared-step", revision = 9L).copy(output = "第二任务的结果",
                sourceMessageId = "other-source", occurredAt = 5_000L),
        )).copy(sender = "system", timestamp = 5_000L, executionFact = true,
            executionGenerationState = GenerationState.COMPLETED)
        val continued = model.build(listOf(first, steering, repeated, nextSource, otherGeneration))
        val runs = continued.filterIsInstance<TimelineDisplayItem.Execution>().map { it.run }
        assertEquals(4, continued.size)
        assertEquals(steering, (continued[1] as TimelineDisplayItem.Single).entry)
        assertEquals(listOf("g-one", "g-one", "g-two"), runs.map { it.generationId })
        assertEquals(listOf(listOf("shared-step"), listOf("next-step"), listOf("shared-step")),
            runs.map { run -> run.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId } })
        assertEquals(listOf("execution:g-one:shared-step", "execution:g-one:next-step", "execution:g-two:shared-step"),
            runs.flatMap { it.steps }.map { it.stepKey })
        assertEquals("第一任务的结果", (runs.first().steps.single() as ExecutionStep.ToolCall).block.output)
        assertEquals("第二任务的结果", (runs.last().steps.single() as ExecutionStep.ToolCall).block.output)
        assertEquals(initialRun.segmentKey, runs.first().segmentKey)
    }

    @Test
    fun executionSourceKeepsSegmentKeyWhenLiveFactBecomesHistoryMessage() {
        val running = step("call", status = NativeExecutionStatus.RUNNING)
            .copy(output = null, sourceMessageId = "source", occurredAt = 1_000L)
        val fact = entry("execution-fact:g:source", "g", steps = listOf(running))
            .copy(sender = "system", timestamp = 1_000L, executionFact = true,
                executionGenerationState = GenerationState.RUNNING)
        val history = entry("source", "g", text = "源消息中的合法正文", steps = listOf(
            running.copy(eventId = "result-event", output = "完整工具结果",
                status = NativeExecutionStatus.COMPLETED, revision = 2L),
        )).copy(timestamp = 1_000L, executionGenerationState = GenerationState.COMPLETED)
        val model = TimelineDisplayModel()

        val liveRun = (model.build(listOf(fact)).single() as TimelineDisplayItem.Execution).run
        val historyItems = model.build(listOf(history))
        val historyRun = (historyItems.first() as TimelineDisplayItem.Execution).run

        assertEquals("execution:g:source", liveRun.segmentKey, "段身份应取任务与公共 source，不随投影行 key 改变")
        assertEquals(liveRun.segmentKey, historyRun.segmentKey)
        assertEquals("g", historyRun.generationId)
        assertEquals("execution:g:call", liveRun.steps.single().stepKey)
        assertEquals(liveRun.steps.single().stepKey, historyRun.steps.single().stepKey)
        assertEquals("完整工具结果", (historyRun.steps.single() as ExecutionStep.ToolCall).block.output)
        assertEquals(2, historyItems.size)
        assertEquals(history.copy(executionSteps = emptyList()), (historyItems[1] as TimelineDisplayItem.Single).entry,
            "真实来源消息的合法正文仍须独立保留，且已由执行段消费的步骤不重复下发")
        assertFalse(historyRun.hasFinalAnswer)
    }

    @Test
    fun unboundMetadataStaysOrdinaryAndNeverGuessesGeneration() {
        val items = TimelineDisplayModel().build(listOf(entry("unbound", null, "ordinary", listOf(step("call")))))
        assertEquals("ordinary", (items.single() as TimelineDisplayItem.Single).entry.text)
    }

    @Test
    fun nativeResultCompletionDoesNotClaimSuccessAndMissingResultStaysUnknown() {
        val items = TimelineDisplayModel().build(listOf(entry("tools", "g", steps = listOf(
            step("done"), step("missing", status = NativeExecutionStatus.UNKNOWN),
        ))))
        val run = (items.single() as TimelineDisplayItem.Execution).run
        assertEquals(0, run.failedStepCount)
        assertTrue(!run.interrupted)
        assertEquals(listOf(NativeExecutionStatus.COMPLETED, NativeExecutionStatus.UNKNOWN),
            run.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.block.executionStatus })
    }
    @Test
    fun approvalBetweenToolSourcesKeepsOrderedSegmentsOfTheSameGeneration() {
        val approval = TimelineEntry("approval", "system", "", false, 0L, false, null,
            approval = ApprovalCardState.Waiting(ApprovalRequest(approvalId = ApprovalId("approval"), conversationId = null, command = "synthetic", reason = "reason", options = emptyList(), timeoutSeconds = 30L, requestedAt = 0L)))
        val items = TimelineDisplayModel().build(listOf(
            entry("tool-one", "g", steps = listOf(step("call-one", status = NativeExecutionStatus.RUNNING))),
            approval,
            entry("tool-two", "g", steps = listOf(step("call-two"))),
            entry("final", "g", "final"),
        ))
        assertEquals(4, items.size)
        val before = (items[0] as TimelineDisplayItem.Execution).run
        val after = (items[2] as TimelineDisplayItem.Execution).run
        assertTrue(before.isStreaming)
        assertEquals(listOf("g", "g"), listOf(before.generationId, after.generationId))
        assertEquals(listOf("call-one"), before.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId })
        assertEquals(listOf("call-two"), after.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId })
        assertEquals(approval, (items[1] as TimelineDisplayItem.Single).entry)
        val final = (items[3] as TimelineDisplayItem.Single).entry
        assertEquals("final", final.key)
        assertEquals("final", final.text)
    }

    @Test
    fun completedNativeRunFollowedBySameTurnFinalAnswerMarksHasCompletedFinalAnswer() {
        val model = TimelineDisplayModel()
        val runningFinal = model.build(
            listOf(
                entry("call-1", "g-one", steps = listOf(step("s1"))),
                entry("final", "g-one", "正在输出最终回答").copy(isStreaming = true),
            ),
            generation = GenerationState.RUNNING,
        )
        assertFalse((runningFinal[0] as TimelineDisplayItem.Execution).run.hasCompletedFinalAnswer)

        val completed = model.build(
            listOf(
                entry("call-1", "g-one", steps = listOf(step("s1"))),
                entry("final", "g-one", "完整最终回答"),
            ),
            generation = GenerationState.COMPLETED,
        )
        assertEquals(2, completed.size)
        val run = (completed[0] as TimelineDisplayItem.Execution).run
        assertFalse(run.hasFinalAnswer, "兼容既有 [Execution, Single(final)] 拆分契约")
        assertTrue(run.hasCompletedFinalAnswer, "紧随同回合已完成独立回答时应允许回合折叠")
    }

    @Test
    fun sameGenerationInterleavedNarrativeAndMultipleToolsAggregateIntoOneRun() {
        val items = TimelineDisplayModel().build(
            listOf(
                entry("fact-1", "g-multi", steps = listOf(step("s1"))).copy(executionFact = true),
                entry("mid-note", "g-multi", "先读取配置，继续执行第二项检查。"),
                entry("fact-2", "g-multi", steps = listOf(step("s2"))).copy(executionFact = true),
                entry("final-note", "g-multi", "全部检查完毕。"),
            ),
            generation = GenerationState.COMPLETED,
        )
        assertEquals(1, items.size, "同一回合穿插中间正文与多次工具调用必须聚合为唯一执行段")
        val run = (items.single() as TimelineDisplayItem.Execution).run
        assertTrue(run.hasFinalAnswer)
        assertTrue(run.hasCompletedFinalAnswer)
        assertEquals(
            listOf("s1", "s2"),
            run.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId },
        )
        assertTrue(run.steps.any { it is ExecutionStep.Narrative })
    }

    @Test
    fun duplicateStepIdAcrossEphemeralGenerationMergesNonDestructivelyWithoutDuplicatingCard() {
        val initial = NativeExecutionStep(
            stepId = "  call_abc  ",
            eventId = "ev-1",
            toolName = "read_file",
            arguments = """{"path":"README.md"}""",
            output = null,
            status = NativeExecutionStatus.RUNNING,
            revision = 1L,
        )
        val degradedLater = NativeExecutionStep(
            stepId = "call_abc",
            eventId = "ev-2",
            toolName = "tool",
            arguments = "",
            output = "README content",
            status = NativeExecutionStatus.COMPLETED,
            revision = 2L,
        )
        val degradedBlankOutput = NativeExecutionStep(
            stepId = "call_abc",
            eventId = "ev-3",
            toolName = "",
            arguments = "",
            output = null,
            status = NativeExecutionStatus.UNKNOWN,
            revision = 3L,
        )
        val items = TimelineDisplayModel().build(
            listOf(
                entry("live-exec", "remote-execution:tmp-1", steps = listOf(initial)).copy(executionFact = true),
                entry("hist-exec", "g-authoritative", text = "读取结果如上。", steps = listOf(degradedLater)),
                entry("late-noise", "history-turn:tmp-2", steps = listOf(degradedBlankOutput)).copy(executionFact = true),
            ),
            generation = GenerationState.COMPLETED,
        )
        val runs = items.filterIsInstance<TimelineDisplayItem.Execution>().map { it.run }
        assertEquals(1, runs.size, "跨临时 generationId 别名不得拆出第二张执行卡")
        val tools = runs.single().steps.filterIsInstance<ExecutionStep.ToolCall>()
        assertEquals(1, tools.size, "同 stepId 去空白后必须归并为唯一工具卡")
        val merged = tools.single().block
        assertEquals("read_file", merged.toolName, "不得以占位名 tool 或空白覆盖有效工具名")
        assertEquals("""{"path":"README.md"}""", merged.command, "不得以空白覆盖已有入参")
        assertEquals("README content", merged.output, "不得以 null 覆盖已有工具输出")
        assertEquals(NativeExecutionStatus.COMPLETED, merged.executionStatus, "已终态不得退化为 UNKNOWN")
        assertTrue(runs.single().hasCompletedFinalAnswer)
    }

    @Test
    fun acpProvisionalGenerationMergesWithToolCallPairingAndMarksSplitFinalAnswerCompleted() {
        val callStep = NativeExecutionStep(
            stepId = "call_acp_1",
            eventId = "ev-acp-1",
            toolName = "shell_exec",
            arguments = """{"cmd":"uname -a"}""",
            output = null,
            status = NativeExecutionStatus.RUNNING,
            revision = 1L,
        )
        val resultWithWrapperNameAndEmptyJsonArgs = NativeExecutionStep(
            stepId = " call_acp_1 ",
            eventId = "ev-acp-2",
            toolName = "tool_result",
            arguments = "{}",
            output = "Linux android",
            status = NativeExecutionStatus.COMPLETED,
            revision = 2L,
        )
        val items = TimelineDisplayModel().build(
            listOf(
                entry("acp-exec", "acp-turn:7", steps = listOf(callStep)).copy(executionFact = true),
                entry("acp-result", "g-acp-auth", steps = listOf(resultWithWrapperNameAndEmptyJsonArgs)).copy(executionFact = true),
                entry("acp-final", "g-acp-auth", text = "系统版本已确认。"),
            ),
            generation = GenerationState.COMPLETED,
        )
        assertEquals(2, items.size)
        val run = (items[0] as TimelineDisplayItem.Execution).run
        val tools = run.steps.filterIsInstance<ExecutionStep.ToolCall>()
        assertEquals(1, tools.size, "acp-turn 临时 generation 与权威 generation 的同 stepId 必须归并")
        assertEquals("shell_exec", tools.single().block.toolName, "不得以 ToolCallPairing 占位名 tool_result 覆盖真实工具名")
        assertEquals("""{"cmd":"uname -a"}""", tools.single().block.command, "不得以退化空对象 {} 覆盖已有有效入参")
        assertEquals("Linux android", tools.single().block.output)
        assertTrue(run.hasCompletedFinalAnswer, "拆分独立最终回答到达后应允许回合折叠")
    }

    @Test
    fun provisionalNativeExecutionFollowedByAuthoritativeSingleFinalAnswerCollapses() {
        val items = TimelineDisplayModel().build(
            listOf(
                entry("fact-tmp", "remote-execution:s1", steps = listOf(step("s-only"))).copy(executionFact = true),
                entry("final-auth", "g-server-42", text = "最终回答。"),
            ),
            generation = GenerationState.COMPLETED,
        )
        assertEquals(2, items.size)
        val run = (items[0] as TimelineDisplayItem.Execution).run
        assertTrue(run.hasCompletedFinalAnswer, "临时 generation 的纯工具段紧随权威 generation 最终回答也应折叠")
    }

    @Test
    fun completedNativeRunFollowedByAttachmentOnlyFinalAnswerCollapses() {
        val items = TimelineDisplayModel().build(
            listOf(
                entry("fact-img", "g-img", steps = listOf(step("s-img"))).copy(executionFact = true),
                entry("final-img", "g-img", text = "").copy(
                    attachments = listOf(TimelineAttachment(draftId = "att-1", filename = "result.png", mediaType = "image/png")),
                ),
            ),
            generation = GenerationState.COMPLETED,
        )
        assertEquals(2, items.size)
        val run = (items[0] as TimelineDisplayItem.Execution).run
        assertTrue(run.hasCompletedFinalAnswer, "纯原生工具段紧跟仅含附件的最终回答也应允许折叠")
    }

    @Test
    fun thinkingToolsIntermediateNarrativeSecondToolThenAttachmentOnlyFinalAnswerSelectsAttachmentAsFinal() {
        val finalAttachment = TimelineAttachment(draftId = "att-final", filename = "result.png", mediaType = "image/png")
        val items = TimelineDisplayModel().build(
            listOf(
                entry("step-1", "g-att-mid", text = "", steps = listOf(step("tool-1"))).copy(
                    reasoning = listOf(MessageReasoning("r1", "先分析日志", 0, complete = true)),
                ),
                entry("mid-body", "g-att-mid", text = "已完成第一阶段排查，继续生成结果图。"),
                entry("step-2", "g-att-mid", text = "", steps = listOf(step("tool-2"))).copy(executionFact = true),
                entry("final-att", "g-att-mid", text = "").copy(attachments = listOf(finalAttachment)),
            ),
            generation = GenerationState.COMPLETED,
        )

        assertEquals(1, items.size, "含思考、中间正文、二次工具与纯附件终答的同一回合必须聚合为唯一执行段")
        val run = (items.single() as TimelineDisplayItem.Execution).run
        assertEquals("final-att", run.finalEntry.key, "段内最终回答必须指向末尾纯附件消息，而非前序中间正文")
        assertEquals(listOf(finalAttachment), run.finalEntry.attachments)
        assertTrue(run.hasFinalAnswer)
        assertTrue(run.hasCompletedFinalAnswer)
        assertTrue(run.finalBlocks.isEmpty())
        assertTrue(shouldCollapseTurnProcessCard(run), "回合结束且存在纯附件最终回答时应进入默认折叠")
        assertTrue(
            run.steps.filterIsInstance<ExecutionStep.Attachments>().isEmpty(),
            "最终附件不得进入 ExecutionStep.Attachments 被过程折叠卡隐藏",
        )
        assertEquals(
            listOf("tool-1", "tool-2"),
            run.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId },
        )
        assertTrue(run.steps.any { it is ExecutionStep.Thought })
        assertTrue(
            run.steps.filterIsInstance<ExecutionStep.Narrative>().any {
                (it.block as? TimelineBlock.Paragraph)?.text?.contains("第一阶段排查") == true
            },
            "前序中间正文必须收进过程步骤内，而非被误选为最终回答",
        )
    }

    @Test
    fun thinkingAndToolThenAttachmentOnlyFinalAnswerWithoutIntermediateNarrativeCollapses() {
        val finalAttachment = TimelineAttachment(draftId = "att-only", filename = "chart.png", mediaType = "image/png")
        val items = TimelineDisplayModel().build(
            listOf(
                entry("step-1", "g-att-direct", text = "", steps = listOf(step("tool-1"))).copy(
                    reasoning = listOf(MessageReasoning("r1", "直接生成图表", 0, complete = true)),
                ),
                entry("final-att", "g-att-direct", text = "").copy(attachments = listOf(finalAttachment)),
            ),
            generation = GenerationState.COMPLETED,
        )

        assertEquals(1, items.size)
        val run = (items.single() as TimelineDisplayItem.Execution).run
        assertEquals("final-att", run.finalEntry.key)
        assertTrue(run.hasFinalAnswer)
        assertTrue(run.hasCompletedFinalAnswer)
        assertTrue(run.finalBlocks.isEmpty())
        assertTrue(shouldCollapseTurnProcessCard(run), "无中间正文时，思考+工具+纯附件终答也必须正常折叠过程卡")
        assertTrue(run.steps.filterIsInstance<ExecutionStep.Attachments>().isEmpty())
        assertEquals(listOf("tool-1"), run.steps.filterIsInstance<ExecutionStep.ToolCall>().map { it.nativeStepId })
        assertTrue(run.steps.any { it is ExecutionStep.Thought })
    }

    @Test
    fun streamingAttachmentOnlyTrailingEntryDoesNotPrematurelyFoldTurn() {
        val attachment = TimelineAttachment(draftId = "att-live", filename = "draft.png", mediaType = "image/png")
        val items = TimelineDisplayModel().build(
            listOf(
                entry("step-1", "g-att-live", text = "", steps = listOf(step("tool-1"))).copy(
                    reasoning = listOf(MessageReasoning("r1", "生成中", 0, complete = true)),
                ),
                entry("live-att", "g-att-live", text = "").copy(
                    attachments = listOf(attachment),
                    isStreaming = true,
                ),
            ),
            generation = GenerationState.RUNNING,
        )

        assertEquals(1, items.size)
        val run = (items.single() as TimelineDisplayItem.Execution).run
        assertFalse(run.hasFinalAnswer)
        assertFalse(run.hasCompletedFinalAnswer)
        assertFalse(shouldCollapseTurnProcessCard(run))
        assertEquals(listOf("live-att"), run.steps.filterIsInstance<ExecutionStep.Attachments>().map { it.entry.key })
    }

}
