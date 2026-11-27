package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.snapshots.Snapshot
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R5 第一层非门面单测：
 * 验证思维链字素推进引擎 [LocalThoughtPresentationDriver] 与 [StreamingPresentationRegistry] 思考槽位
 * 能够真实向 [StreamingDecorationState] 写入非空渐显批次（绝不被 clear），并守护正文 `frameFor` 契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThoughtStreamingPresentationTest {

    @get:Rule
    val isolation = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val snapshot = Snapshot.takeMutableSnapshot()
                try {
                    snapshot.enter { base.evaluate() }
                } finally {
                    snapshot.dispose()
                }
            }
        }
    }

    @Test
    fun t_Unit_01_incrementalThoughtAdvanceAddsNonEmptyFadeBatchesWithoutClearing() {
        val driver = LocalThoughtPresentationDriver(initialText = "", initialLive = true)
        val decoration = StreamingDecorationState()
        val cache = StreamingMarkdownCache()

        driver.sync("首先分析设备网络", live = true, nowNanos = 0L)
        val firstFrame = driver.frame
        assertTrue("首帧推进必须产生严格正区间", firstFrame.end > firstFrame.start)

        decoration.update(
            document = cache.parse(firstFrame.text, narrative = true),
            next = StreamingTextPresentation(
                batchId = firstFrame.batchId,
                sourceRevealStart = firstFrame.start,
                sourceRevealEnd = firstFrame.end,
                active = firstFrame.active,
                reduceMotion = false,
            ),
        )
        assertTrue("StreamingDecorationState.batches 绝不可为空", decoration.batches.isNotEmpty())
        assertEquals(firstFrame.start, decoration.batches.first().sourceStart)
        assertEquals(firstFrame.end, decoration.batches.first().sourceEnd)

        // 连续推进多帧并追加增量，验证渐显批次累积而不会被 batches.clear() 清空
        var now = 0L
        repeat(3) { step ->
            now += 34_000_000L
            if (step == 1) {
                driver.sync("首先分析设备网络状态与日志", live = true, nowNanos = now)
            } else {
                driver.advance(now)
            }
            val nextFrame = driver.frame
            if (nextFrame.batchId != decoration.presentation.batchId) {
                assertTrue(
                    "增量帧必须满足 sourceRevealEnd > sourceRevealStart",
                    nextFrame.end > nextFrame.start,
                )
                decoration.update(
                    document = cache.parse(nextFrame.text, narrative = true),
                    next = StreamingTextPresentation(
                        batchId = nextFrame.batchId,
                        sourceRevealStart = nextFrame.start,
                        sourceRevealEnd = nextFrame.end,
                        active = nextFrame.active,
                        reduceMotion = false,
                    ),
                )
            }
        }
        assertTrue("连续增量后 batches 应保留多批渐显记录: ${decoration.batches.size}", decoration.batches.size >= 2)
    }

    @Test
    fun t_Unit_02_burstThoughtRevealsProgressivelyAndDrainsOnCompletion() {
        val fullText = "推".repeat(120)
        val driver = LocalThoughtPresentationDriver(initialText = "", initialLive = true)

        driver.sync(fullText, live = true, nowNanos = 0L)
        assertTrue("突发大块思考首帧不能瞬间全显", driver.frame.text.length < fullText.length)
        assertTrue("突发缓冲期间 hasPending 必须为 true", driver.hasPending)

        driver.sync(fullText, live = false, nowNanos = 1_000_000L)
        var now = 1_000_000L
        while (driver.hasPending && now < 400_000_000L) {
            now += 34_000_000L
            driver.advance(now)
        }
        assertEquals("250ms 排空窗结束后应完整呈现思考全文", fullText, driver.frame.text)
        assertFalse("排空结束后 active 必须收起", driver.frame.active)
        assertFalse("排空结束后 hasPending 必须为 false", driver.hasPending)
    }

    @Test
    fun t_Unit_03_registryThoughtChannelAdvancesWithoutPollutingReplyFrameOrBaseline() {
        val registry = StreamingPresentationRegistry(emptyList())
        val thinkingOnly = TimelineEntry(
            key = "thought_only_turn",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 1L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
            generationId = "gen_thought",
            reasoning = listOf(MessageReasoning("r1", "推".repeat(80), 0, false)),
            presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 1L),
        )

        registry.accept(listOf(thinkingOnly), pageVisible = true, now = 0L, generation = GenerationState.RUNNING)

        // 守护 StructuredReasoningDisplayTest 契约：仅思考时正文 frameFor 必须仍为 null
        assertNull(registry.frameFor(thinkingOnly))
        val thoughtKey = "${thinkingOnly.key}:thought:0"
        val initialThought = registry.thoughtFrameFor(thoughtKey)
        assertNotNull("宿主思考槽位必须生成流式帧", initialThought)
        assertTrue("首帧不能瞬间全显 80 字", initialThought!!.text.length < 80)
        assertTrue(initialThought.active)
        assertTrue(initialThought.end > initialThought.start)
        assertTrue("思考缓冲未排空时 registry.hasPending 必须为 true", registry.hasPending)

        registry.advance(50_000_000L)
        val advancedThought = registry.thoughtFrameFor(thoughtKey)
        assertNotNull(advancedThought)
        assertTrue(advancedThought!!.text.length > initialThought.text.length)
        assertTrue(advancedThought.batchId > initialThought.batchId)
    }
}
