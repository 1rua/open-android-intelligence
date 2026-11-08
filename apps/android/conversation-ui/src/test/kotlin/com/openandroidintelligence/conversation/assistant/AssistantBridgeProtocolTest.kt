package com.openandroidintelligence.conversation.assistant

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.ApprovalSeverity
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 助理桥接协议 v2 的回归：编解码往返、字段不丢失、以及线上预算的截断规则。
 *
 * 使用 Robolectric 只因为被测实现基于 Android 自带的 `org.json`；这些断言全部是
 * 纯数据映射，不渲染界面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantBridgeProtocolTest {

    private fun assistantEntry(
        key: String,
        text: String,
        streaming: Boolean = false,
    ): TimelineEntry = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = 0L,
        pendingAcceptance = false,
        batchGroupId = null,
        isStreaming = streaming,
    )

    private fun approvalRequest(
        id: String,
        expiresAt: Long = 1_700_000_030_000L,
        options: List<ApprovalOption> = listOf(
            ApprovalOption(ApprovalChoice.ONCE, "允许一次", ApprovalOptionStyle.PRIMARY),
            ApprovalOption(ApprovalChoice.DENY, null, ApprovalOptionStyle.DANGER),
        ),
    ): ApprovalRequest = ApprovalRequest(
        approvalId = ApprovalId(id),
        conversationId = null,
        command = "adb shell rm -rf /sdcard/tmp",
        reason = "该命令会删除文件",
        severity = ApprovalSeverity.ELEVATED,
        options = options,
        timeoutSeconds = 30L,
        requestedAt = expiresAt - 30_000L,
        expiresAt = expiresAt,
    )

    // ------------------------------------------------------------------
    // 往返
    // ------------------------------------------------------------------

    @Test
    fun v2FullFieldStateSurvivesRoundTrip() {
        val state = AssistantStateDto(
            revision = 42L,
            connected = true,
            account = AssistantAccountDto("profile-1", "account-1", "OAI Agent"),
            sessionId = "session-1",
            generation = GenerationState.RUNNING.name,
            composer = ComposerState.SUBMITTING.name,
            creatingThread = true,
            draft = "帮我看看这个通知",
            activeThread = "thread-1",
            timelineTruncated = false,
            attachments = listOf(
                AssistantAttachmentDto("draft-1", "shot.png", "image/png", true, "draft-1", "SAVED"),
                AssistantAttachmentDto("draft-2", "log.txt", "text/plain", false, "draft-2", "PENDING"),
            ),
            notice = AssistantNoticeDto("APPROVAL_UNSUPPORTED:GATEWAY_UNSUPPORTED", "当前网关不支持审批卡片"),
            appearance = AssistantAppearanceDto("DARK", dynamicColor = true, palette = "AZURE", reduceMotion = true),
            timeline = listOf(
                AssistantTimelineEntryDto(
                    id = "m1",
                    sender = "user",
                    text = "你好",
                    messageStatus = "delivered",
                    clientMessageId = "client-1",
                ),
                AssistantTimelineEntryDto(
                    id = "m2",
                    sender = "assistant",
                    text = "你好，需要我做什么？",
                    streaming = true,
                ),
            ),
        )

        val decoded = requireNotNull(decodeState(encodeState(state))) { "v2 状态应当可以解析" }

        assertEquals(state, decoded)
        assertEquals(ASSISTANT_BRIDGE_VERSION, decoded.version)
    }

    @Test
    fun decodeTimelineMapsSenderAndIdentityBackToEntries() {
        val dto = AssistantStateDto(
            timeline = listOf(
                AssistantTimelineEntryDto(
                    id = "m1",
                    sender = "user",
                    text = "你好",
                    messageStatus = "delivered",
                    submissionFailureCode = "AGENT_UNAVAILABLE",
                    clientMessageId = "client-1",
                    localPlaceholder = true,
                ),
                AssistantTimelineEntryDto(id = "m2", sender = "assistant", text = "在的", streaming = true),
            ),
        )

        val entries = decodeTimeline(dto)

        assertEquals(2, entries.size)
        assertEquals("m1", entries[0].key)
        assertTrue(entries[0].isUser)
        assertEquals("delivered", entries[0].messageStatus?.wireValue)
        assertEquals("AGENT_UNAVAILABLE", entries[0].submissionFailureCode)
        assertEquals(ClientMessageId("client-1"), entries[0].clientMessageId)
        assertTrue(entries[0].localPlaceholder)
        assertFalse(entries[1].isUser)
        assertTrue(entries[1].isStreaming)
    }

    @Test
    fun executionStepsAndApprovalAreNotLost() {
        val options = listOf(
            ApprovalOption(ApprovalChoice.ONCE, "允许一次", ApprovalOptionStyle.PRIMARY),
            ApprovalOption(ApprovalChoice.SESSION, "本会话允许", ApprovalOptionStyle.SECONDARY),
            ApprovalOption(ApprovalChoice.DENY, "拒绝", ApprovalOptionStyle.DANGER),
        )
        val request = approvalRequest("approval-1", options = options)
        val step = NativeExecutionStep(
            stepId = "step-1",
            eventId = "step-1",
            toolName = "device.shell",
            arguments = """{"command":"ls"}""",
            output = "a.txt\nb.txt",
            status = NativeExecutionStatus.COMPLETED,
        )
        val original = TimelineEntry(
            key = "approval_approval-1",
            sender = "system",
            text = "",
            isUser = false,
            timestamp = 0L,
            pendingAcceptance = false,
            batchGroupId = null,
            approval = ApprovalCardState.Waiting(request),
            executionSteps = listOf(step),
        )

        val roundTripped = original.toDto().toTimelineEntry()

        assertEquals(original.key, roundTripped.key)
        assertEquals(original.executionSteps, roundTripped.executionSteps)
        // 协议只承诺这些审批字段；severity、conversationId 不在线上投影里，不能断言整卡相等。
        val card = roundTripped.approval as? ApprovalCardState.Waiting
        assertEquals(request.approvalId, card?.request?.approvalId)
        assertEquals(request.command, card?.request?.command)
        assertEquals(request.reason, card?.request?.reason)
        assertEquals(request.expiresAt, card?.request?.expiresAt)
        assertEquals(options.map { it.choice }, card?.request?.options?.map { it.choice })
        assertEquals(options.map { it.style }, card?.request?.options?.map { it.style })
        assertEquals(listOf("允许一次", "本会话允许", "拒绝"), card?.request?.options?.map { it.label })
    }

    @Test
    fun approvalCardLifecycleStatesArePreserved() {
        val request = approvalRequest("approval-2")
        val states = listOf<Pair<ApprovalCardState, String>>(
            ApprovalCardState.Waiting(request) to "WAITING",
            ApprovalCardState.Submitting(request, ApprovalChoice.SESSION) to "SUBMITTING:session",
            ApprovalCardState.Resolved(request, ApprovalOutcome.ALLOWED_ONCE) to "RESOLVED:once",
            // 协议只传一条 command；TextOnly 的命令列表由它还原。
            ApprovalCardState.TextOnly(request, commands = listOf(request.command)) to "TEXT_ONLY",
        )

        for ((card, expectedState) in states) {
            val dto = TimelineEntry(
                key = "approval_approval-2",
                sender = "system",
                text = "",
                isUser = false,
                timestamp = 0L,
                pendingAcceptance = false,
                batchGroupId = null,
                approval = card,
            ).toDto()
            assertEquals("编码后的卡片生命周期不得丢失", expectedState, dto.approval?.state)
            assertEquals(request.approvalId.value, dto.approval?.approvalId)
            assertEquals(request.expiresAt, dto.approval?.expiresAt)
            // 解码后必须是同一类生命周期，而不是被折成 WAITING。
            val restored = requireNotNull(decodeTimeline(AssistantStateDto(timeline = listOf(dto))).single().approval)
            assertEquals(card::class, restored::class)
            when (card) {
                is ApprovalCardState.Submitting ->
                    assertEquals(card.choice, (restored as ApprovalCardState.Submitting).choice)
                is ApprovalCardState.Resolved ->
                    assertEquals(card.outcome, (restored as ApprovalCardState.Resolved).outcome)
                is ApprovalCardState.TextOnly ->
                    assertEquals(card.commands, (restored as ApprovalCardState.TextOnly).commands)
                is ApprovalCardState.Waiting -> Unit
            }
        }
    }

    @Test
    fun attachmentMetadataRoundTripsWithoutBytes() {
        val attachment = TimelineAttachment(
            draftId = "draft-9",
            filename = "photo.jpg",
            mediaType = "image/jpeg",
            previewBytes = byteArrayOf(1, 2, 3),
        )
        val entry = TimelineEntry(
            key = "m9",
            sender = "user",
            text = "",
            isUser = true,
            timestamp = 0L,
            pendingAcceptance = false,
            batchGroupId = null,
            attachments = listOf(attachment),
        )

        val dto = entry.toDto()
        assertEquals(1, dto.attachments.size)
        assertTrue(dto.attachments.single().isImage)
        assertEquals("draft-9", dto.attachments.single().localKey)

        val restored = dto.toTimelineEntry().attachments.single()
        assertEquals("draft-9", restored.draftId)
        assertEquals("photo.jpg", restored.filename)
        assertEquals("image/jpeg", restored.mediaType)
        assertNull("线上协议不传字节", restored.previewBytes)
    }

    // ------------------------------------------------------------------
    // 版本与坏数据
    // ------------------------------------------------------------------

    @Test
    fun unknownVersionIsDroppedInsteadOfMisread() {
        assertNull(decodeState("""{"version":1,"connected":true}"""))
        assertNull(decodeState("""{"version":3,"connected":true}"""))
        assertNull(decodeState("""{"connected":true}"""))
    }

    @Test
    fun malformedPayloadReturnsNull() {
        assertNull(decodeState("not json at all"))
        assertNull(decodeState(""))
        assertNull(decodeState("""[{"version":2}]"""))
    }

    @Test
    fun unknownEnumerationsDegradeToNeutralDefaults() {
        val decoded = requireNotNull(
            decodeState(
                """
                {"version":2,"timeline":[
                  {"id":"m1","sender":"assistant","text":"x","messageStatus":"future-status",
                   "executionSteps":[{"stepId":"s1","toolName":"t","status":"FUTURE","arguments":"{}"}],
                   "approval":{"approvalId":"a1","state":"FUTURE_STATE","command":"c","reason":"r","expiresAt":1,
                     "options":[{"choice":"future","style":"future"}]}
                  }
                ]}
                """.trimIndent(),
            ),
        )
        val entry = decodeTimeline(decoded).single()
        assertNull("未知消息状态不得冒充已知状态", entry.messageStatus)
        assertEquals(NativeExecutionStatus.UNKNOWN, entry.executionSteps.single().status)
        val card = entry.approval as ApprovalCardState.Waiting
        assertEquals(ApprovalChoice.UNKNOWN, card.request.options.single().choice)
        assertEquals(ApprovalOptionStyle.NEUTRAL, card.request.options.single().style)
    }

    @Test
    fun approvalWithoutUsableIdIsDropped() {
        val decoded = requireNotNull(
            decodeState(
                """{"version":2,"timeline":[{"id":"m1","sender":"assistant","text":"x",
                   "approval":{"approvalId":"bad id with spaces","state":"WAITING","command":"c"}}]}""",
            ),
        )
        assertNull(decodeTimeline(decoded).single().approval)
    }

    // ------------------------------------------------------------------
    // 32k 单条截断
    // ------------------------------------------------------------------

    @Test
    fun oversizedSingleEntryIsTruncatedHeadAndTailWithMarker() {
        val head = "H".repeat(20_000)
        val middle = "M".repeat(20_000)
        val tail = "T".repeat(20_000)
        val original = head + middle + tail

        val (budgeted, truncated) = AssistantWireBudget.applyBudget(listOf(assistantEntry("m1", original)))

        assertFalse("单条正文截断不算时间线丢弃", truncated)
        assertEquals(1, budgeted.size)
        val text = budgeted.single().text
        assertTrue("长度应受 32k 上限约束", text.length <= AssistantWireBudget.MAX_TEXT_CHARS)
        assertTrue("应插入截断标记", text.contains(TEXT_TRUNCATION_MARKER))
        assertTrue("开头应保留", text.startsWith("H"))
        assertTrue("结尾应保留", text.endsWith("T"))
        assertFalse("中间部分应被移除", text.contains("M".repeat(20_000)))
        assertTrue(budgeted.single().toDto().textTruncated)
    }

    @Test
    fun textAtExactlyTheLimitIsNotTouched() {
        val text = "x".repeat(AssistantWireBudget.MAX_TEXT_CHARS)
        val (budgeted, truncated) = AssistantWireBudget.applyBudget(listOf(assistantEntry("m1", text)))
        assertEquals(text, budgeted.single().text)
        assertFalse(truncated)
        assertFalse(budgeted.single().toDto().textTruncated)
    }

    @Test
    fun truncationNeverSplitsSurrogatePairs() {
        val emoji = "😀"
        val text = emoji.repeat(30_000)
        val (budgeted, _) = AssistantWireBudget.applyBudget(listOf(assistantEntry("m1", text)))
        val result = budgeted.single().text
        assertTrue(result.length <= AssistantWireBudget.MAX_TEXT_CHARS)
        for (index in result.indices) {
            if (result[index].isHighSurrogate()) {
                assertTrue("高代理必须紧跟低代理", index + 1 < result.length && result[index + 1].isLowSurrogate())
            }
            if (result[index].isLowSurrogate()) {
                assertTrue("低代理必须有前导高代理", index > 0 && result[index - 1].isHighSurrogate())
            }
        }
    }

    // ------------------------------------------------------------------
    // 512KB 总预算与流式尾条
    // ------------------------------------------------------------------

    @Test
    fun totalPayloadBudgetDropsOldestEntriesUntilItFits() {
        val entries = (0 until 30).map { index -> assistantEntry("m$index", "x".repeat(50_000)) }

        val (budgeted, truncated) = AssistantWireBudget.applyBudget(entries)

        assertTrue("总预算应触发丢弃", truncated)
        assertTrue(budgeted.size < entries.size)
        assertEquals("应保留最新条目", "m29", budgeted.last().key)
        assertFalse("应从最旧开始丢弃", budgeted.any { it.key == "m0" })
        assertEquals(
            "保留下来的条目必须连续且是原列表的后缀",
            entries.takeLast(budgeted.size).map { it.key },
            budgeted.map { it.key },
        )
        assertTrue(
            "截断后的时间线必须落在 512KB 内",
            AssistantWireBudget.timelinePayloadBytes(budgeted) <= AssistantWireBudget.MAX_PAYLOAD_BYTES,
        )
    }

    @Test
    fun entryCountIsCappedAtTwoHundred() {
        val entries = (0 until 250).map { index -> assistantEntry("m$index", "hi") }

        val (budgeted, truncated) = AssistantWireBudget.applyBudget(entries)

        assertTrue(truncated)
        assertEquals(AssistantWireBudget.MAX_ENTRIES, budgeted.size)
        assertEquals("m249", budgeted.last().key)
        assertEquals("m50", budgeted.first().key)
    }

    @Test
    fun streamingTailIsNeverTruncatedNorDropped() {
        val hugeTail = "T".repeat(60_000)
        val entries = (0 until 20).map { index -> assistantEntry("m$index", "x".repeat(50_000)) } +
            assistantEntry("tail", hugeTail, streaming = true)

        val (budgeted, truncated) = AssistantWireBudget.applyBudget(entries)

        assertTrue("前面的条目会被丢弃", truncated)
        val tail = budgeted.single { it.key == "tail" }
        assertEquals("流式尾条正文不得截断", hugeTail, tail.text)
        assertEquals("流式尾条必须留在最后", "tail", budgeted.last().key)
        assertFalse(tail.toDto().textTruncated)
    }

    @Test
    fun singleStreamingEntryIsLeftAlone() {
        val huge = "S".repeat(80_000)

        val (budgeted, truncated) = AssistantWireBudget.applyBudget(listOf(assistantEntry("only", huge, streaming = true)))

        assertFalse(truncated)
        assertEquals(huge, budgeted.single().text)
    }

    @Test
    fun budgetWithinLimitsReportsNoTruncation() {
        val entries = (0 until 5).map { index -> assistantEntry("m$index", "短消息 $index") }

        val (budgeted, truncated) = AssistantWireBudget.applyBudget(entries)

        assertFalse(truncated)
        assertEquals(entries, budgeted)
        assertFalse(budgeted.any { it.toDto().textTruncated })
    }
}
