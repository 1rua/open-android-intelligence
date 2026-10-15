package com.openandroidintelligence.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 CursorWindow 和 AndroidKeyStore，验证大记录不会使整个账号无法重启。 */
@RunWith(AndroidJUnit4::class)
class ConversationMirrorInstrumentedTest {
    @Test fun aLegalMessageBeyondTheCursorWindowReopensWithTheSameContent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = ConversationScope("large_record_${UUID.randomUUID()}", "https://fixture.example", "acct_window", "install_window")
        val body = "A".repeat(3 * 1024 * 1024)
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val message = TimelineMessage("msg_large", "assistant", listOf(MessagePart.Text(body)), 1, "CONFIRMED", ConversationId("conv_large"), contentRevision = 1)
        EncryptedConversationMirror(context, scope).also { mirror ->
            mirror.commitTimeline("conv_large", TimelinePage(listOf(message), null), null)
            mirror.closeStore()
        }
        val reopened = EncryptedConversationMirror(context, scope)
        val restored = (reopened.loadTimeline("conv_large")!!.messages.single().parts.single() as MessagePart.Text).value
        assertEquals(body.length, restored.length)
        assertEquals(digest(body), digest(restored))
        reopened.wipe()
    }
}
