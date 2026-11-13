package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
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
        assertEquals(history, (historyItems[1] as TimelineDisplayItem.Single).entry,
            "真实来源消息的合法正文仍须独立保留")
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

}
