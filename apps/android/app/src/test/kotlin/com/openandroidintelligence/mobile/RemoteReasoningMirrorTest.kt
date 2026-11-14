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
}
