package com.openandroidintelligence.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助理界面状态机的回归用例。
 *
 * 只断言"用户看到哪种形态"，不依赖 Compose：状态机是纯函数，这些规则在进程重建、
 * 系统助理会话和 App 内兜底页上都应完全一致。
 */
class AssistantUiStateMachineTest {

    /** 连续转移；一旦中途退出会话就说明用例前提被破坏，直接失败。 */
    private fun reduced(state: AssistantUiState, vararg events: AssistantUiEvent): AssistantUiState {
        var current = state
        events.forEach { event ->
            current = reduce(current, event)
            check(current !is AssistantUiState.Terminated) { "$event 意外结束了会话" }
        }
        return current
    }

    private fun visible(state: AssistantUiState): AssistantUiState.Visible =
        requireNotNull(state as? AssistantUiState.Visible) { "期望可见状态，实际是 $state" }

    private fun expandedState(): AssistantUiState =
        reduced(AssistantUiState.Visible(), AssistantUiEvent.SendRequested(accepted = true))

    // ---- 唤起重置 ----

    @Test
    fun sessionShownAlwaysRestartsFromCompactInput() {
        val stale = AssistantUiState.Visible(
            surface = AssistantSurfaceState.EXPANDED_CONVERSATION,
            overlay = AssistantOverlay.SELECTING_SCREEN,
            expandedSince = 42L,
        )

        val shown = visible(reduce(stale, AssistantUiEvent.SessionShown))

        assertEquals(AssistantSurfaceState.COMPACT_INPUT, shown.surface)
        assertEquals(AssistantOverlay.NONE, shown.overlay)
        assertNull(shown.expandedSince)
    }

    @Test
    fun sessionShownRestartsAfterTermination() {
        val terminated = reduce(AssistantUiState.Visible(), AssistantUiEvent.BackPressed)
        assertTrue(terminated is AssistantUiState.Terminated)

        val shown = visible(reduce(terminated, AssistantUiEvent.SessionShown))

        assertEquals(AssistantSurfaceState.COMPACT_INPUT, shown.surface)
        assertEquals(AssistantOverlay.NONE, shown.overlay)
    }

    // ---- 发送：只有真正接收才展开 ----

    @Test
    fun acceptedSendExpandsConversation() {
        val state = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.SendRequested(accepted = true),
        )

        assertEquals(AssistantSurfaceState.EXPANDED_CONVERSATION, visible(state).surface)
    }

    @Test
    fun rejectedSendKeepsCompactInput() {
        val state = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.SendRequested(accepted = false),
        )

        assertEquals(AssistantSurfaceState.COMPACT_INPUT, visible(state).surface)
    }

    @Test
    fun rejectedSendDoesNotCollapseAnAlreadyExpandedConversation() {
        val state = reduced(
            expandedState(),
            AssistantUiEvent.SendRequested(accepted = false),
        )

        assertEquals(AssistantSurfaceState.EXPANDED_CONVERSATION, visible(state).surface)
    }

    @Test
    fun voiceSentExpandsAndLeavesRecordingOverlay() {
        val state = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.RecordingStarted,
            AssistantUiEvent.VoiceSent,
        )

        val resolved = visible(state)
        assertEquals(AssistantSurfaceState.EXPANDED_CONVERSATION, resolved.surface)
        assertEquals(AssistantOverlay.NONE, resolved.overlay)
    }

    // ---- 覆盖层 ----

    @Test
    fun selectingThenCroppingClearsOverlayWithoutChangingSurface() {
        val state = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.SelectionRequested,
            AssistantUiEvent.SelectionCropped,
        )

        val resolved = visible(state)
        assertEquals(AssistantOverlay.NONE, resolved.overlay)
        assertEquals(AssistantSurfaceState.COMPACT_INPUT, resolved.surface)
    }

    @Test
    fun cancellingSelectionOrRecordingClearsOverlay() {
        val selectionCancelled = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.SelectionRequested,
            AssistantUiEvent.SelectionCancelled,
        )
        val recordingCancelled = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.RecordingStarted,
            AssistantUiEvent.RecordingCancelled,
        )

        assertEquals(AssistantOverlay.NONE, visible(selectionCancelled).overlay)
        assertEquals(AssistantOverlay.NONE, visible(recordingCancelled).overlay)
    }

    @Test
    fun startingRecordingReplacesSelectionOverlay() {
        val state = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.SelectionRequested,
            AssistantUiEvent.RecordingStarted,
        )

        assertEquals(AssistantOverlay.RECORDING_VOICE, visible(state).overlay)
    }

    // ---- 返回键优先级：圈选 → 录音 → 退出 ----

    @Test
    fun backCancelsSelectionBeforeEndingSession() {
        val selecting = reduced(
            AssistantUiState.Visible(surface = AssistantSurfaceState.EXPANDED_CONVERSATION),
            AssistantUiEvent.SelectionRequested,
        )

        val state = reduce(selecting, AssistantUiEvent.BackPressed)

        // 取消圈选不影响对话形态：展开的仍保持展开，而不是结束会话。
        assertEquals(AssistantOverlay.NONE, visible(state).overlay)
        assertEquals(AssistantSurfaceState.EXPANDED_CONVERSATION, visible(state).surface)
    }

    @Test
    fun backCancelsRecordingBeforeEndingSession() {
        val recording = reduced(
            AssistantUiState.Visible(),
            AssistantUiEvent.RecordingStarted,
        )

        val state = reduce(recording, AssistantUiEvent.BackPressed)

        assertEquals(AssistantOverlay.NONE, visible(state).overlay)
    }

    @Test
    fun backEndsSessionOnlyWhenNoOverlayIsPending() {
        val terminated = reduce(expandedState(), AssistantUiEvent.BackPressed)

        // 展开态没有覆盖层可取消：返回键结束会话，而不是把对话折叠回紧凑输入。
        assertTrue(terminated is AssistantUiState.Terminated)
    }

    @Test
    fun backPriorityIsSelectionThenRecordingThenExit() {
        val selecting = reduced(AssistantUiState.Visible(), AssistantUiEvent.SelectionRequested)
        val afterFirstBack = reduced(selecting, AssistantUiEvent.BackPressed)
        val recording = reduced(afterFirstBack, AssistantUiEvent.RecordingStarted)
        val afterSecondBack = reduced(recording, AssistantUiEvent.BackPressed)
        val afterThirdBack = reduce(afterSecondBack, AssistantUiEvent.BackPressed)

        assertEquals(AssistantOverlay.NONE, visible(afterFirstBack).overlay)
        assertEquals(AssistantOverlay.NONE, visible(afterSecondBack).overlay)
        assertTrue(afterThirdBack is AssistantUiState.Terminated)
    }

    @Test
    fun terminatedStateIgnoresOrdinaryEvents() {
        val terminated = reduce(AssistantUiState.Visible(), AssistantUiEvent.BackPressed)

        val events = listOf(
            AssistantUiEvent.DraftChanged("迟到的草稿"),
            AssistantUiEvent.AttachmentAdded,
            AssistantUiEvent.SendRequested(accepted = true),
            AssistantUiEvent.SelectionRequested,
            AssistantUiEvent.RecordingStarted,
            AssistantUiEvent.VoiceSent,
            AssistantUiEvent.SessionHidden,
        )
        events.forEach { event ->
            assertTrue(
                "$event 不应复活已结束的会话",
                reduce(terminated, event) is AssistantUiState.Terminated,
            )
        }
    }

    @Test
    fun sessionHiddenTerminates() {
        val state = reduce(AssistantUiState.Visible(), AssistantUiEvent.SessionHidden)

        assertTrue(state is AssistantUiState.Terminated)
    }

    // ---- 回复终态不折叠 ----

    /**
     * 回复走完终态时界面必须停在展开的对话上。
     *
     * 状态机里没有任何"本地事件"会把展开态收回紧凑输入：草稿变化、附件增加、
     * 覆盖层开关与一次失败的重发都只影响自己的字段。真正能离开展开态的只有
     * 新的会话唤起（重置）和会话结束（Terminated）。
     */
    @Test
    fun replyTerminalStateNeverCollapsesExpandedConversation() {
        val eventsThatDoNotEndSession = listOf(
            AssistantUiEvent.DraftChanged(""),
            AssistantUiEvent.AttachmentAdded,
            AssistantUiEvent.SendRequested(accepted = false),
            AssistantUiEvent.SelectionRequested,
            AssistantUiEvent.SelectionCropped,
            AssistantUiEvent.SelectionCancelled,
            AssistantUiEvent.RecordingStarted,
            AssistantUiEvent.RecordingCancelled,
            AssistantUiEvent.VoiceSent,
        )

        eventsThatDoNotEndSession.forEach { event ->
            val state = reduced(expandedState(), event)
            assertEquals(
                "$event 不应把已展开的对话折叠回紧凑输入",
                AssistantSurfaceState.EXPANDED_CONVERSATION,
                visible(state).surface,
            )
        }
    }

    @Test
    fun draftAndAttachmentEventsDoNotTouchSurfaceOrOverlay() {
        val base = AssistantUiState.Visible(
            surface = AssistantSurfaceState.EXPANDED_CONVERSATION,
            overlay = AssistantOverlay.NONE,
        )

        val afterDraft = reduced(base, AssistantUiEvent.DraftChanged("继续输入"))
        val afterAttachment = reduced(base, AssistantUiEvent.AttachmentAdded)

        assertEquals(base, afterDraft)
        assertEquals(base, afterAttachment)
    }

    // ---- 展开时间 ----

    @Test
    fun expandedSinceIsRecordedOnceForTheFirstExpansion() {
        val expanded = visible(expandedState())
        val recorded = expanded.recordExpandedAt(1_000L)

        assertEquals(1_000L, recorded.expandedSince)
        // 重复观察不会刷新起点，"展开多久"从第一次展开算起。
        assertEquals(1_000L, recorded.recordExpandedAt(9_999L).expandedSince)
    }

    @Test
    fun compactStateNeverRecordsExpandedSince() {
        val compact = visible(AssistantUiState.Visible())

        assertNull(compact.recordExpandedAt(1_000L).expandedSince)
    }
}
