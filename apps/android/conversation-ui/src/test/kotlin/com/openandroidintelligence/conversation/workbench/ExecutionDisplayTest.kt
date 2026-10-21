package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.state.TimelineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 纯函数折叠矩阵：覆盖规格 §3.1 的全部分支、跨条聚合、缓存与异常兜底。
 * 不依赖 Compose，纯 JVM 即可运行。
 */
class ExecutionDisplayTest {

    // ==========================================
    // 测试数据构造
    // ==========================================

    private fun assistantEntry(
        key: String,
        text: String,
        streaming: Boolean = false,
        attachments: List<TimelineAttachment> = emptyList(),
        approval: ApprovalCardState? = null,
        systemThreadId: String? = null,
    ) = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = 0L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = attachments,
        isStreaming = streaming,
        approval = approval,
        systemThreadId = systemThreadId,
    )

    private fun userEntry(key: String, text: String) = TimelineEntry(
        key = key,
        sender = "user",
        text = text,
        isUser = true,
        timestamp = 0L,
        pendingAcceptance = false,
        batchGroupId = null,
    )

    private fun waitingApprovalEntry(key: String = "approval-1"): TimelineEntry {
        val request = ApprovalRequest(
            approvalId = ApprovalId("approval-1"),
            conversationId = null,
            command = "rm -rf /tmp/demo",
            reason = "危险命令需要确认",
            options = listOf(ApprovalOption(choice = ApprovalChoice.ONCE)),
            timeoutSeconds = 30L,
            requestedAt = 0L,
        )
        return TimelineEntry(
            key = key,
            sender = "system",
            text = "",
            isUser = false,
            timestamp = 0L,
            pendingAcceptance = false,
            batchGroupId = null,
            approval = ApprovalCardState.Waiting(request),
        )
    }

    private fun executionRun(items: List<TimelineDisplayItem>): AssistantExecutionRun {
        val item = items.single()
        assertTrue(item is TimelineDisplayItem.Execution, "期望单个执行聚合项，实际为 $item")
        return (item as TimelineDisplayItem.Execution).run
    }

    // ==========================================
    // 1. 折叠决策矩阵
    // ==========================================

    @Test
    fun ordinaryAssistantMessagesWithoutExecutionBlocksStaySingle() {
        val items = TimelineDisplayModel().build(
            listOf(
                assistantEntry("a1", "第一条普通回答"),
                assistantEntry("a2", "第二条普通回答"),
            ),
        )

        assertEquals(2, items.size)
        assertEquals("a1", items[0].key)
        assertEquals("a2", items[1].key)
        assertTrue(items[0] is TimelineDisplayItem.Single)
        assertTrue(items[1] is TimelineDisplayItem.Single)
    }

    @Test
    fun streamingExecutionStaysSingleUntilFinalAnswerAppears() {
        val entry = assistantEntry("a1", "<think>正在分析设备状态", streaming = true)

        val items = TimelineDisplayModel().build(listOf(entry))

        assertEquals(1, items.size)
        val single = items.single() as TimelineDisplayItem.Single
        assertEquals("a1", single.entry.key)
    }

    @Test
    fun finalAnswerStartingWhileStreamingAggregatesRun() {
        val first = assistantEntry("a1", "<think>先检查设备</think>")
        val second = assistantEntry("a2", "最终回答：已完成。", streaming = true)

        val run = executionRun(TimelineDisplayModel().build(listOf(first, second)))

        assertEquals("execution_segment:a1", run.segmentKey)
        assertEquals("a2", run.finalEntry.key)
        assertTrue(run.hasFinalAnswer)
        assertTrue(run.isStreaming)
        assertFalse(run.interrupted)
        assertEquals(1, run.stepCount)
        assertEquals(0, run.failedStepCount)
        assertEquals(listOf("a1"), run.steps.map { it.entryKey })
        val finalParagraph = run.finalBlocks.single() as TimelineBlock.Paragraph
        assertEquals("最终回答：已完成。", finalParagraph.text)
    }

    @Test
    fun executionEndingWithoutFinalAnswerAggregatesWithEmptyAnswer() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>pm list packages</command><output>package:a</output></tool_call>",
        )

        val run = executionRun(TimelineDisplayModel().build(listOf(entry)))

        assertFalse(run.hasFinalAnswer)
        assertTrue(run.finalBlocks.isEmpty(), "没有最终正文时不能伪造回答块")
        assertEquals(1, run.stepCount)
        assertEquals("a1", run.finalEntry.key)
        assertFalse(run.isStreaming)
        assertFalse(run.interrupted)
    }

    @Test
    fun interruptedThoughtCountsAsFailureAndInterruption() {
        val entry = assistantEntry("a1", "<think>流式分析到一半就断了")

        val run = executionRun(TimelineDisplayModel().build(listOf(entry)))

        assertTrue(run.interrupted, "流已结束但思考未闭合应判定为中断")
        assertEquals(1, run.stepCount)
        assertEquals(1, run.failedStepCount)
        assertFalse(run.hasFinalAnswer)
    }

    @Test
    fun interruptedToolCallCountsAsFailureAndInterruption() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>sleep 999</command>",
        )

        val run = executionRun(TimelineDisplayModel().build(listOf(entry)))

        assertTrue(run.interrupted)
        assertEquals(1, run.stepCount)
        assertEquals(1, run.failedStepCount)
        val step = run.steps.single() as ExecutionStep.ToolCall
        assertTrue(step.block.isRunning)
    }

    @Test
    fun streamedToolCallStaysRunningWhileEntryStreaming() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>dumpsys battery</command>",
            streaming = true,
        )

        // 仍在流式且没有最终正文：逐条实时呈现，不聚合。
        val items = TimelineDisplayModel().build(listOf(entry))
        assertTrue(items.single() is TimelineDisplayItem.Single)
    }

    @Test
    fun crossMessageAggregationKeepsEarlierContentInSteps() {
        val first = assistantEntry("a1", "先说明一下\n<think>展开分析</think>\n初步结论")
        val second = assistantEntry("a2", "最终回答：完成")

        val run = executionRun(TimelineDisplayModel().build(listOf(first, second)))

        assertEquals("a2", run.finalEntry.key)
        assertEquals(1, run.stepCount)
        assertEquals(0, run.failedStepCount)
        assertEquals(listOf("a1", "a1", "a1"), run.steps.map { it.entryKey })
        assertTrue(run.steps[0] is ExecutionStep.Narrative)
        assertTrue(run.steps[1] is ExecutionStep.Thought)
        assertTrue(run.steps[2] is ExecutionStep.Narrative)
        val finalParagraph = run.finalBlocks.single() as TimelineBlock.Paragraph
        assertEquals("最终回答：完成", finalParagraph.text)
    }

    @Test
    fun finalBlocksExcludeProcessBlocksOfFinalEntry() {
        val entry = assistantEntry("a1", "<think>过程思考</think>\n\n最终答案")

        val run = executionRun(TimelineDisplayModel().build(listOf(entry)))

        assertEquals(1, run.stepCount)
        assertTrue(run.hasFinalAnswer)
        val finalParagraph = run.finalBlocks.single() as TimelineBlock.Paragraph
        assertEquals("最终答案", finalParagraph.text)
        assertTrue((run.steps.single() as ExecutionStep.Thought).block.isComplete)
    }

    // ==========================================
    // 2. 分段：审批 / 系统行 / 用户消息打断
    // ==========================================

    @Test
    fun approvalInterruptsContinuousExecutionSegment() {
        val thought = assistantEntry("a1", "<think>准备执行危险命令</think>")
        val approval = waitingApprovalEntry()
        val finalAnswer = assistantEntry("a3", "已等待你的审批")

        val items = TimelineDisplayModel().build(listOf(thought, approval, finalAnswer))

        assertEquals(3, items.size)
        assertTrue(items[0] is TimelineDisplayItem.Execution)
        assertTrue(items[1] is TimelineDisplayItem.Single)
        assertTrue(items[2] is TimelineDisplayItem.Single)
        assertEquals("approval-1", items[1].key)
        assertEquals("a3", items[2].key)
    }

    @Test
    fun systemRowInterruptsContinuousExecutionSegment() {
        val thought = assistantEntry("a1", "<think>思考</think>")
        val systemRow = TimelineEntry(
            key = "created-thread",
            sender = "system",
            text = "",
            isUser = false,
            timestamp = 0L,
            pendingAcceptance = false,
            batchGroupId = null,
            systemThreadId = "thread-2",
        )
        val finalAnswer = assistantEntry("a3", "回答")

        val items = TimelineDisplayModel().build(listOf(thought, systemRow, finalAnswer))

        assertEquals(3, items.size)
        assertTrue(items[0] is TimelineDisplayItem.Execution)
        assertTrue(items[1] is TimelineDisplayItem.Single)
        assertTrue(items[2] is TimelineDisplayItem.Single)
    }

    @Test
    fun userMessageInterruptsContinuousExecutionSegment() {
        val thought = assistantEntry("a1", "<think>思考</think>")
        val user = userEntry("u1", "打断一下")
        val finalAnswer = assistantEntry("a3", "回答")

        val items = TimelineDisplayModel().build(listOf(thought, user, finalAnswer))

        assertEquals(3, items.size)
        assertTrue(items[0] is TimelineDisplayItem.Execution)
        assertEquals("u1", items[1].key)
        assertTrue(items[2] is TimelineDisplayItem.Single)
    }

    // ==========================================
    // 3. finalEntry / 步骤计数 / 附件承接
    // ==========================================

    @Test
    fun finalEntryPrefersLastMessageWithVisibleFinalText() {
        val first = assistantEntry("a1", "<think>思考一</think>答案一")
        val second = assistantEntry("a2", "<think>思考二</think>")

        val run = executionRun(TimelineDisplayModel().build(listOf(first, second)))

        assertEquals("execution_segment:a1", run.segmentKey)
        assertEquals("a1", run.finalEntry.key)
        assertEquals(2, run.stepCount)
        assertTrue(run.hasFinalAnswer)
        assertEquals("答案一", (run.finalBlocks.single() as TimelineBlock.Paragraph).text)
    }

    @Test
    fun stepCountCountsOnlyThoughtsAndToolCalls() {
        val entry = assistantEntry(
            "a1",
            "<think>思考</think>\n中间叙述\n<tool_call name=\"adb_shell\"><command>ls</command></tool_call>",
        )

        val run = executionRun(TimelineDisplayModel().build(listOf(entry)))

        assertEquals(2, run.stepCount)
        assertEquals(0, run.failedStepCount)
        assertEquals(3, run.steps.size)
        assertTrue(run.steps.any { it is ExecutionStep.Narrative })
        assertTrue(run.steps.any { it is ExecutionStep.Thought })
        assertTrue(run.steps.any { it is ExecutionStep.ToolCall })
    }

    @Test
    fun failedToolCallIncreasesFailedStepCountWithoutInterruption() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>bad</command><result status=\"failed\">boom</result></tool_call>",
        )

        val run = executionRun(TimelineDisplayModel().build(listOf(entry)))

        assertEquals(1, run.stepCount)
        assertEquals(1, run.failedStepCount)
        assertFalse(run.interrupted, "闭合的失败调用是执行失败，不是中断")
    }

    @Test
    fun mixedFailedAndInterruptedStepsAreBothCounted() {
        val failedTool = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>bad</command><result status=\"failed\">boom</result></tool_call>",
        )
        val interruptedThought = assistantEntry("a2", "<think>断流思考")

        val run = executionRun(TimelineDisplayModel().build(listOf(failedTool, interruptedThought)))

        assertEquals(2, run.stepCount)
        assertEquals(2, run.failedStepCount)
        assertTrue(run.interrupted)
    }

    @Test
    fun finalEntryAttachmentsBecomeProcessStepsWhenNoFinalAnswer() {
        val attachment = TimelineAttachment(draftId = "att-1", filename = "log.txt", mediaType = "text/plain")
        val thought = assistantEntry("a1", "<think>思考</think>")
        val attachmentOnly = assistantEntry("a2", "", attachments = listOf(attachment))

        val run = executionRun(TimelineDisplayModel().build(listOf(thought, attachmentOnly)))

        assertFalse(run.hasFinalAnswer)
        assertEquals("a2", run.finalEntry.key)
        assertEquals(1, run.stepCount)
        val attachmentStep = run.steps.filterIsInstance<ExecutionStep.Attachments>().single()
        assertEquals("a2", attachmentStep.entry.key)
        assertEquals(listOf(attachment), attachmentStep.entry.attachments)
    }

    @Test
    fun earlierEntryAttachmentsBecomeProcessSteps() {
        val attachment = TimelineAttachment(draftId = "att-1", filename = "log.txt", mediaType = "text/plain")
        val thought = assistantEntry("a1", "<think>思考</think>", attachments = listOf(attachment))
        val finalAnswer = assistantEntry("a2", "完成")

        val run = executionRun(TimelineDisplayModel().build(listOf(thought, finalAnswer)))

        assertTrue(run.hasFinalAnswer)
        val attachmentStep = run.steps.filterIsInstance<ExecutionStep.Attachments>().single()
        assertEquals("a1", attachmentStep.entry.key)
    }

    @Test
    fun emptyAssistantTextDoesNotCountAsFinalAnswer() {
        val thought = assistantEntry("a1", "<think>思考</think>")
        val empty = assistantEntry("a2", "")

        val run = executionRun(TimelineDisplayModel().build(listOf(thought, empty)))

        assertFalse(run.hasFinalAnswer)
        assertTrue(run.finalBlocks.isEmpty())
        assertEquals("a2", run.finalEntry.key)
    }

    @Test
    fun terminalGenerationFoldsHermesToolOnlySegmentWithoutAFinalAnswer() {
        val entries = listOf(
            userEntry("u1", "查一下"),
            assistantEntry("a1", "调用工具：execute_code"),
            assistantEntry("a2", "调用工具：read_file"),
        )

        val item = TimelineDisplayModel()
            .build(entries, generation = GenerationState.COMPLETED)
            .last()

        assertTrue(
            item is TimelineDisplayItem.Execution,
            "生成终态后即使没有最终正文，Hermes 过程段也必须折叠",
        )
        assertFalse((item as TimelineDisplayItem.Execution).run.hasFinalAnswer)
    }

    @Test
    fun terminalGenerationFoldsHermesCardsEvenWhenEntriesStillSayStreaming() {
        val entries = listOf(
            userEntry("u1", "查一下"),
            assistantEntry("a1", "调用工具：execute_code", streaming = true),
            assistantEntry("a2", "最终答案", streaming = true),
        )

        val run = (TimelineDisplayModel()
            .build(entries, generation = GenerationState.COMPLETED)
            .last() as TimelineDisplayItem.Execution).run

        assertEquals("a2", run.finalEntry.key)
        assertTrue(run.hasFinalAnswer)
        assertFalse(run.finalEntryIsStreaming, "生成已结束，即使快照仍是 STREAMING 也不能常驻光标")
    }

    @Test
    fun completedHermesToolMessagesStayVisibleUntilTheFinalAnswerArrives() {
        val beforeFinal = listOf(
            userEntry("u1", "查一下"),
            assistantEntry("a1", "调用工具：execute_code"),
            assistantEntry("a2", "调用工具：read_file"),
        )
        val model = TimelineDisplayModel()

        val liveItems = model.build(beforeFinal)
        assertTrue(
            liveItems.drop(1).all { it is TimelineDisplayItem.Single },
            "还没有最终回答时，逐步到达的 Hermes 工具消息应保持逐条可见",
        )

        val withFinal = beforeFinal + assistantEntry("a3", "最终答案")
        assertTrue(
            model.build(withFinal).last() is TimelineDisplayItem.Execution,
            "最终回答出现后同一段应收成一个执行面板",
        )
    }

    @Test
    fun hermesToolMessagesFoldIntoOneExecutionRun() {
        val entries = listOf(
            userEntry("u1", "查一下"),
            assistantEntry("a1", "Running code import sqlite3\nReading core.py"),
            assistantEntry("a2", "调用工具：execute_code"),
            assistantEntry("a3", "调用工具：read_file"),
            assistantEntry("a4", "最终答案：插件能力尚未发布。"),
        )

        val run = (TimelineDisplayModel().build(entries).last() as TimelineDisplayItem.Execution).run

        assertEquals(4, run.stepCount)
        assertEquals("a4", run.finalEntry.key)
        assertTrue(run.hasFinalAnswer)
        assertTrue(run.steps.any { it is ExecutionStep.ToolCall && it.block.invocationOnly })
    }

    @Test
    fun finalAnswerCursorOnlyFollowsTheFinalEntryItself() {
        val entries = listOf(
            userEntry("u1", "继续"),
            assistantEntry("a1", "第一段最终答案", streaming = false),
            assistantEntry("a2", "<think>后续思考还在流式", streaming = true),
        )
        val model = TimelineDisplayModel()

        val run = (model.build(entries).last() as TimelineDisplayItem.Execution).run

        assertTrue(run.isStreaming, "整段仍有消息在流式")
        assertFalse(run.finalEntryIsStreaming, "最终回答本身已经完成，不能继续画光标")
    }

    // ==========================================
    // 4. 解析缓存与异常兜底
    // ==========================================

    @Test
    fun parseCacheReusesBlocksForSameEntryAndTextLength() {
        var parseCount = 0
        val model = TimelineDisplayModel { text ->
            parseCount++
            MarkdownParser.parse(text)
        }
        val entries = listOf(assistantEntry("a1", "<think>思考</think>普通文本"))

        model.build(entries)
        model.build(entries)

        assertEquals(1, parseCount)
    }

    @Test
    fun parseCacheReparsesWhenTextLengthChanges() {
        var parseCount = 0
        val model = TimelineDisplayModel { text ->
            parseCount++
            MarkdownParser.parse(text)
        }

        model.build(listOf(assistantEntry("a1", "<think>短</think>")))
        model.build(listOf(assistantEntry("a1", "<think>更长的文本</think>")))

        assertEquals(2, parseCount)
    }

    @Test
    fun parseCacheStaysWithinLimit() {
        var parseCount = 0
        val model = TimelineDisplayModel { text ->
            parseCount++
            MarkdownParser.parse(text)
        }
        val entries = (1..200).map { index ->
            assistantEntry("m$index", "<think>思考 $index</think>")
        }

        model.build(entries)

        assertEquals(200, parseCount)
        assertTrue(model.cachedEntryCount <= 128, "缓存上限应为 128，实际 ${model.cachedEntryCount}")
    }

    @Test
    fun ordinaryHistoryIsNeverParsedByTheDisplayModel() {
        var parseCount = 0
        val model = TimelineDisplayModel { text ->
            parseCount++
            MarkdownParser.parse(text)
        }
        val entries = (1..300).map { index -> assistantEntry("m$index", "普通历史文本 $index") }

        model.build(entries)
        model.build(entries)

        assertEquals(0, parseCount, "没有执行标记的历史消息不应进入 Markdown 解析")
        assertEquals(0, model.cachedEntryCount)
    }

    @Test
    fun parserExceptionFallsBackToPlainText() {
        val model = TimelineDisplayModel { error("病态 Markdown") }
        val entry = assistantEntry("a1", "<think>思考</think>答案")

        val items = model.build(listOf(entry))

        // 回退成纯文本段落：没有执行块，因此保持单条呈现且不崩溃。
        val single = items.single() as TimelineDisplayItem.Single
        assertEquals("a1", single.entry.key)
    }

    @Test
    fun parserNotEmptyButEmptyBlocksFallBackToParagraph() {
        val model = TimelineDisplayModel { emptyList() }
        val entry = assistantEntry("a1", "文本")

        val items = model.build(listOf(entry))

        assertNotNull(items.single() as? TimelineDisplayItem.Single)
    }

    @Test
    fun finalAnswerThatTurnsOutToBePreambleStaysFoldedWhileTheNextToolStreams() {
        val model = TimelineDisplayModel()
        val prefix = "<think>检查设备</think><tool_call name=\"adb_shell\"><command>pm list packages</command></tool_call>我先看一下"
        val streamingPreamble = listOf(
            userEntry("u1", "继续"),
            assistantEntry("a1", prefix, streaming = true),
        )

        val folded = model.build(streamingPreamble).last() as TimelineDisplayItem.Execution
        assertTrue(folded.run.hasFinalAnswer)

        // 下一段工具开始时，刚才的“最终正文”变成过程叙述：已经折叠的面板不能
        // 突然展开回实时卡片，否则用户的阅读位置会来回跳。
        val nextTool = listOf(
            userEntry("u1", "继续"),
            assistantEntry(
                "a1",
                prefix + "<tool_call name=\"adb_shell\"><command>dumpsys battery</command>",
                streaming = true,
            ),
        )
        val stillFolded = model.build(nextTool).last() as TimelineDisplayItem.Execution
        assertFalse(stillFolded.run.hasFinalAnswer)
    }
}
