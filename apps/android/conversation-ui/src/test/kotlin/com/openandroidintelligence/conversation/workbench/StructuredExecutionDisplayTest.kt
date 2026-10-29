package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("execution:g-one", run.segmentKey)
        assertEquals(2, run.stepCount)
        assertTrue(!run.hasFinalAnswer)
        assertEquals("final", (items[1] as TimelineDisplayItem.Single).entry.key)
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
        assertEquals(listOf("execution:g-one", "execution:g-two"), runs.map { it.segmentKey })
        assertEquals(listOf("call-one", "call-two"), runs.map { (it.steps.single() as ExecutionStep.ToolCall).nativeStepId })
        assertEquals(2, items.filterIsInstance<TimelineDisplayItem.Single>().size)
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
    fun approvalBetweenToolSourcesStaysIndependentAndRunningStepUpdatesInPlace() {
        val approval = TimelineEntry("approval", "system", "", false, 0L, false, null,
            approval = ApprovalCardState.Waiting(ApprovalRequest(approvalId = ApprovalId("approval"), conversationId = null, command = "synthetic", reason = "reason", options = emptyList(), timeoutSeconds = 30L, requestedAt = 0L)))
        val items = TimelineDisplayModel().build(listOf(
            entry("tool-one", "g", steps = listOf(step("call-one", status = NativeExecutionStatus.RUNNING))),
            approval,
            entry("tool-two", "g", steps = listOf(step("call-two"))),
            entry("final", "g", "final"),
        ))
        assertEquals(3, items.size)
        val run = (items[0] as TimelineDisplayItem.Execution).run
        assertTrue(run.isStreaming)
        assertEquals(2, run.stepCount)
        assertEquals("approval", (items[1] as TimelineDisplayItem.Single).entry.key)
        assertEquals("final", (items[2] as TimelineDisplayItem.Single).entry.key)
    }

}
