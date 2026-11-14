package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.assistant.toDto
import com.openandroidintelligence.conversation.assistant.toTimelineEntry
import com.openandroidintelligence.conversation.assistant.AssistantStateDto
import com.openandroidintelligence.conversation.assistant.encodeState
import com.openandroidintelligence.conversation.assistant.decodeState
import com.openandroidintelligence.conversation.assistant.decodeTimeline
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StructuredReasoningDisplayTest {
    private fun entry(complete: Boolean, text: String = "回答") = TimelineEntry("reply", "assistant", text, false, 0,
        false, null, isStreaming = !complete, generationId = "turn",
        reasoning = listOf(MessageReasoning("reasoning", "真实思考", 0, complete)),
        presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 1))

    @Test fun `结构化思考直接复用原执行卡片且回答正文不含标记`() {
        val entry = entry(false)
        val result = TimelineDisplayModel().build(listOf(entry)).single() as TimelineDisplayItem.Execution
        val step = result.run.steps.filterIsInstance<ExecutionStep.Thought>().single()
        assertEquals("真实思考", step.block.thought)
        assertFalse(step.block.isComplete)
        assertTrue(step.entryIsStreaming)
        assertEquals("回答", entry.text)
        val settled = TimelineDisplayModel().build(listOf(entry(true))).single() as TimelineDisplayItem.Execution
        assertTrue(settled.run.steps.filterIsInstance<ExecutionStep.Thought>().single().block.isComplete)
    }

    @Test fun `思考增量不进入回答来源映射或触觉推进`() {
        val registry = StreamingPresentationRegistry(emptyList())
        val thinking = entry(false, "")
        assertNull(registry.frameFor(thinking))
        val blocks = structuredTimelineBlocks(thinking)
        assertEquals(listOf(TimelineBlock.ThoughtBlock("真实思考", false)), blocks)
        val completed = entry(true, "回答正文")
        val frame = registry.frameFor(completed)
        assertEquals(0, frame?.sourceStart)
    }

    @Test fun `助手投影往返保留思考身份顺序与完成状态`() {
        val original = entry(false).copy(reasoning = listOf(MessageReasoning("r1", "先想", 0, true), MessageReasoning("r2", "再想", 2, false)))
        val restored = decodeTimeline(requireNotNull(decodeState(encodeState(AssistantStateDto(timeline = listOf(original.toDto())))))).single()
        assertEquals(original.reasoning, restored.reasoning)
        assertEquals(original.text, restored.text)
    }
}
