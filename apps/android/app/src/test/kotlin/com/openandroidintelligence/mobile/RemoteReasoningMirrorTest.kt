package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteReasoningMirrorTest {
    @Test fun `加密镜像重开后保留思考块且不混入回答正文`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = ConversationScope("reasoning-profile", "endpoint", "account", "install")
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 19 }, "AES")
            override fun delete() = Unit
        }
        val original = TimelineMessage("message", "assistant", listOf(MessagePart.Text("回答")), 100,
            conversationId = ConversationId("session"), contentRevision = 1, generationId = "turn",
            reasoning = listOf(MessageReasoning("r", "原生思考", 0, false)))
        val mirror = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        mirror.saveTimeline("session", TimelinePage(listOf(original), null))
        val reopened = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        val restored = reopened.timeline("session")!!.messages.single()
        assertEquals(original.reasoning, restored.reasoning)
        assertEquals(listOf(MessagePart.Text("回答")), restored.parts)
    }

    @Test fun `加密镜像重开后保留isMeta标志`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = ConversationScope("meta-profile", "endpoint", "account", "install")
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 23 }, "AES")
            override fun delete() = Unit
        }
        val metaMessage = TimelineMessage(
            id = "meta_1",
            sender = "user",
            parts = listOf(MessagePart.Text("internal meta")),
            timestamp = 100L,
            conversationId = ConversationId("session"),
            isMeta = true,
        )
        val mirror = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        mirror.saveTimeline("session", TimelinePage(listOf(metaMessage), null))
        val reopened = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        val restored = reopened.timeline("session")!!.messages.single()
        assertTrue(restored.isMeta)
    }

    @Test fun `加密镜像重开后保留交错timelineSteps与orderSequence`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = ConversationScope("timeline-steps-profile", "endpoint", "account", "install")
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 29 }, "AES")
            override fun delete() = Unit
        }
        val toolA = NativeExecutionStep(
            stepId = "tool-a",
            eventId = "ev-a",
            toolName = "read_file",
            arguments = """{"path":"a.kt"}""",
            output = "content",
            status = NativeExecutionStatus.COMPLETED,
            revision = 2L,
            orderSequence = 101L,
        )
        val toolB = NativeExecutionStep(
            stepId = "tool-b",
            eventId = "ev-b",
            toolName = "bash",
            arguments = """{"cmd":"test"}""",
            output = "pass",
            status = NativeExecutionStatus.COMPLETED,
            revision = 3L,
            orderSequence = 103L,
        )
        val original = TimelineMessage(
            id = "turn-msg",
            sender = "assistant",
            parts = listOf(MessagePart.Text("最终回答")),
            timestamp = 200L,
            conversationId = ConversationId("session"),
            contentRevision = 4L,
            generationId = "turn-1",
            reasoning = listOf(
                MessageReasoning("r1", "思考一", 0, true),
                MessageReasoning("r2", "思考二", 0, true),
            ),
            executionSteps = listOf(toolA, toolB),
            timelineSteps = listOf(
                ExecutionTimelineStep.ThinkingStep("r1", "思考一", 0, true, 0),
                ExecutionTimelineStep.ToolCallStep(toolA, 0, 1),
                ExecutionTimelineStep.ThinkingStep("r2", "思考二", 0, true, 2),
                ExecutionTimelineStep.ToolCallStep(toolB, 0, 3),
            ),
            orderSequence = 100L,
        )
        val mirror = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        mirror.saveTimeline("session", TimelinePage(listOf(original), null))
        val reopened = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        val restored = reopened.timeline("session")!!.messages.single()
        assertEquals(original.orderSequence, restored.orderSequence)
        assertEquals(original.timelineSteps, restored.timelineSteps)
        assertEquals(original.executionSteps, restored.executionSteps)
        assertEquals(original.reasoning, restored.reasoning)
    }

    @Test fun `加密镜像同时间戳更新工具消息后仍按orderSequence保序不后移到后续思考之后`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = ConversationScope("same-ts-order-profile", "endpoint", "account", "install")
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 31 }, "AES")
            override fun delete() = Unit
        }
        val mirror = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        val thought1 = TimelineMessage(
            id = "m-t1",
            sender = "assistant",
            parts = emptyList(),
            timestamp = 500L,
            conversationId = ConversationId("session"),
            contentRevision = 1L,
            generationId = "turn-1",
            timelineSteps = listOf(ExecutionTimelineStep.ThinkingStep("r1", "思考一", 0, true, 0)),
            orderSequence = 1L,
        )
        val toolRunning = NativeExecutionStep("c1", "e1", "read_file", "{}", null, NativeExecutionStatus.RUNNING, 1L, orderSequence = 2L)
        val toolMsg = TimelineMessage(
            id = "m-tool",
            sender = "assistant",
            parts = emptyList(),
            timestamp = 500L,
            conversationId = ConversationId("session"),
            contentRevision = 1L,
            generationId = "turn-1",
            executionSteps = listOf(toolRunning),
            timelineSteps = listOf(ExecutionTimelineStep.ToolCallStep(toolRunning, 0, 0)),
            orderSequence = 2L,
        )
        val thought2 = TimelineMessage(
            id = "m-t2",
            sender = "assistant",
            parts = listOf(MessagePart.Text("结论")),
            timestamp = 500L,
            conversationId = ConversationId("session"),
            contentRevision = 1L,
            generationId = "turn-1",
            timelineSteps = listOf(ExecutionTimelineStep.ThinkingStep("r2", "思考二", 0, true, 0)),
            orderSequence = 3L,
        )
        mirror.saveTimeline("session", TimelinePage(listOf(thought1, toolMsg, thought2), null))

        // 模拟同时间戳工具行更新结果，验证不会因“先删后尾插”跑到 thought2 之后
        val toolDone = toolRunning.copy(output = "done", status = NativeExecutionStatus.COMPLETED, revision = 2L)
        mirror.apply(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "update-tool",
                occurredAt = 600L,
                revision = 2L,
                message = toolMsg.copy(
                    executionSteps = listOf(toolDone),
                    timelineSteps = listOf(ExecutionTimelineStep.ToolCallStep(toolDone, 0, 0)),
                ),
            ),
        )

        val reopened = EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)
        val restored = reopened.timeline("session")!!.messages
        assertEquals(listOf("m-t1", "m-tool", "m-t2"), restored.map { it.id })
        assertEquals(NativeExecutionStatus.COMPLETED, (restored[1].timelineSteps.single() as ExecutionTimelineStep.ToolCallStep).step.status)
    }
}
