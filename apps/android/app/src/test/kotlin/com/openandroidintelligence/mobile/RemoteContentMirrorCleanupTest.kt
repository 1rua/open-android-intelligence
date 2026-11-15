package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 模拟旧版本已经落盘的系统注入，重开镜像时不能再次显示。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteContentMirrorCleanupTest {
    @Test fun `旧镜像纯提醒隐藏且混合正文与附件保留`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val owner = ConversationScope("cleanup-profile", "endpoint", "account", "install")
        val key = object : AesGcmKeyProvider {
            override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 27 }, "AES")
            override fun delete() = Unit
        }
        val reminder = "<system-reminder>Instructions from ~/.dsh/AGENTS.md</system-reminder>"
        val rows = listOf(
            TimelineMessage("hidden", "user", listOf(MessagePart.Text(reminder)), 1),
            TimelineMessage("mixed", "assistant", listOf(MessagePart.Text(reminder + "正常回复")), 2),
            TimelineMessage("image", "assistant", listOf(MessagePart.Text(reminder), MessagePart.Attachment(
                AttachmentDraftId("image"), "附件", "image/png")), 3),
            TimelineMessage("user", "user", listOf(MessagePart.Text("你好")), 4),
        )
        val mirror = EncryptedConversationMirror(context, owner, key, Dispatchers.Unconfined)
        try { mirror.saveTimeline("session", TimelinePage(rows, null)) } finally { mirror.closeStore() }
        val reopened = EncryptedConversationMirror(context, owner, key, Dispatchers.Unconfined)
        try {
            val restored = reopened.timeline("session")!!.messages
            assertEquals(setOf("mixed", "image", "user"), restored.map { it.id }.toSet())
            assertEquals(listOf(MessagePart.Text("正常回复")), restored.single { it.id == "mixed" }.parts)
            assertEquals(1, restored.single { it.id == "image" }.parts.size)
            assertTrue(restored.single { it.id == "image" }.parts.single() is MessagePart.Attachment)
            assertEquals(listOf(MessagePart.Text("你好")), restored.single { it.id == "user" }.parts)
        } finally { reopened.closeStore() }
    }
}
