package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 同一份原生镜像上的两个读取者必须各记各的游标位置。
 *
 * 主界面与气泡同时打开同一个会话时，如果共用读取者身份，两边会互相覆盖
 * 待确认游标，确认时还会报 `NATIVE_EVENT_OWNER_CHANGED`，整条事件通道停摆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeConversationStoreViewTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : com.openandroidintelligence.encrypted.store.AesGcmKeyProvider {
        override fun getOrCreate() = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
        override fun delete() = Unit
    }
    private fun scope() = com.openandroidintelligence.conversation.ports.ConversationScope(
        "profile", "https://synthetic.example", "account-${UUID.randomUUID()}", "install")

    @Test fun twoObserversTrackIndependentCursorsOverTheSameContent() = runBlocking {
        val mirror = NativeConversationMirror(context, scope(), { "hermes" }, key, observerId = "main")
        try {
            val main = NativeConversationStoreView(mirror, "main")
            val bubble = NativeConversationStoreView(mirror, "bubble")
            assertNull(main.eventCursor("chat"))
            assertNull(bubble.eventCursor("chat"))

            main.commitPage("chat", emptyList(), emptyList(), "cursor-main")
            bubble.commitPage("chat", emptyList(), emptyList(), "cursor-bubble")

            // 各自的待确认位置互不覆盖：这是两个读取者能并行的前提。
            assertEquals("cursor-main", mirror.offlineMirror().nativePendingAck("main", "chat"))
            assertEquals("cursor-bubble", mirror.offlineMirror().nativePendingAck("bubble", "chat"))
            main.confirmAck("chat", "cursor-main")
            bubble.confirmAck("chat", "cursor-bubble")
            assertEquals("cursor-main", main.eventCursor("chat"))
            assertEquals("cursor-bubble", bubble.eventCursor("chat"))
            assertNull(mirror.offlineMirror().nativePendingAck("main", "chat"))
            assertNull(mirror.offlineMirror().nativePendingAck("bubble", "chat"))
        } finally {
            mirror.close()
        }
    }

    @Test fun confirmedContentStaysSharedWhilePositionsStayPerObserver() = runBlocking {
        val mirror = NativeConversationMirror(context, scope(), { "hermes" }, key, observerId = "main")
        try {
            val bubble = NativeConversationStoreView(mirror, "bubble")
            val page = ConversationPage(listOf(ConversationSummary(ConversationId("chat"), "会话", 1L)), null)
            bubble.saveConversations(page)
            val events = listOf(VerifiedConversationEvent.TitleUpdated("title-1", 1L, ConversationId("chat"), "新标题"))
            bubble.commitPage("chat", events, emptyList<NativeEventApproval>(), "cursor-bubble")
            // 内容只保存一份：另一个读取者读到的标题同样是新的。
            assertEquals("新标题", mirror.offlineMirror().threads()!!.single { it.id.value == "chat" }.title)
            // 页提交先写“待确认位置”，确认之后才推进已确认位置。
            assertEquals("气泡自己的位置已经推进", "cursor-bubble",
                mirror.offlineMirror().nativePendingAck("bubble", "chat"))
            bubble.confirmAck("chat", "cursor-bubble")
            assertEquals("cursor-bubble", bubble.eventCursor("chat"))
            assertNull("主界面没有被气泡的位置顶替", NativeConversationStoreView(mirror, "main").eventCursor("chat"))
        } finally {
            mirror.close()
        }
    }
}
