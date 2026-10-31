package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class NativeConversationMirrorTest {
    @Test fun aNewNativeThreadMustSurviveTheNextOfflineLoad() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val key=object:AesGcmKeyProvider {
            override fun getOrCreate()=SecretKeySpec(ByteArray(32){19},"AES")
            override fun delete()=Unit
        }
        val scope=ConversationScope("bug08_native_threads","https://synthetic.example","account","install")
        val mirror=NativeConversationMirror(context,scope,{"hermes"},key)
        fun checkpoint(ids:List<String>)=WorkbenchCheckpoint(ids.map { ConversationSummary(ConversationId(it),it,1) },
            "old","old",emptyList(),emptyMap(),"",0,emptyList(),null,emptySet(),emptySet(),threadsHydrated=true)
        mirror.saveCheckpoint(checkpoint(listOf("old")))
        mirror.saveCheckpoint(checkpoint(listOf("old","new")))
        mirror.flush()
        val reopened=NativeConversationMirror(context,scope,{"hermes"},key).load()!!.threads.map { it.id.value }
        println("BUG08_NATIVE_MIRROR saved=[old,new] reopened=$reopened")
        assertEquals("native 直接仓储没有单独刷新 mirror 列表；最新工作台快照不能保留旧列表",listOf("old","new"),reopened)
    }
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {
        override fun getOrCreate()=SecretKeySpec(ByteArray(32){29},"AES")
        override fun delete()=Unit
    }
    private fun scope(account:String="account")=ConversationScope("native_event_mirror","https://synthetic.example",account,"install")
    private fun checkpoint(ids:List<String>)=WorkbenchCheckpoint(ids.map { ConversationSummary(ConversationId(it),it,1) },
        "old","old",emptyList(),emptyMap(),"",0,emptyList(),null,emptySet(),emptySet(),threadsHydrated=true)

    @Test fun authoritativeCreationAndNotificationIdentitySurviveOwnerRecreationWithoutListRefresh()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key)
        val receipt=NativeCreationReceipt(com.openandroidintelligence.gateway.agents.NativeAgentSession("created", "real title", null, false),"creation-event",17)
        mirror.commitCreation("original-create",receipt)
        mirror.closeStore()
        val reopened=NativeConversationMirror(context,scope(),{"hermes"},key)
        val otherAgent=NativeConversationMirror(context,scope(),{"other"},key)
        val otherAccount=NativeConversationMirror(context,scope("other"),{"hermes"},key)
        try {
            assertEquals(receipt,reopened.creation("original-create"))
            assertEquals("created",reopened.offlineMirror().threads()!!.single().id.value)
            assertNull(otherAgent.creation("original-create"))
            assertNull(otherAccount.creation("original-create"))
        } finally { reopened.closeStore(); otherAgent.closeStore(); otherAccount.closeStore() }
    }

    @Test fun anAuthoritativeNativeListRejectsALaterOldWorkbenchCheckpoint()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key)
        try {
            mirror.saveCheckpoint(checkpoint(listOf("old")))
            mirror.saveConversations(ConversationPage(listOf("old","new").map { ConversationSummary(ConversationId(it),it,2) },null))
            mirror.saveCheckpoint(checkpoint(listOf("old")))
            assertEquals(listOf("old","new"),mirror.load()!!.threads.map {it.id.value})
        } finally {mirror.closeStore()}
    }

    @Test fun nativeListAndAckUseTheSameLockOrder()=runBlocking {
        val testScope=scope("lock-order-${UUID.randomUUID()}")
        val mirror=NativeConversationMirror(context,testScope,{"hermes"},key,observerId="phone:1")
        val delegate=mirror.offlineMirror()
        val records=EncryptedConversationMirror::class.java.getDeclaredField("documents").let { field ->
            field.isAccessible=true
            checkNotNull(field.get(delegate))
        }
        val receipt=NativeCreationReceipt(com.openandroidintelligence.gateway.agents.NativeAgentSession("created", "title", null, false),"event",17)
        val page=ConversationPage(listOf("old","created").map { ConversationSummary(ConversationId(it),it,1) },null)
        try {
            mirror.commitCreation("original-create",receipt)
            mirror.commitPage("chat",emptyList(),emptyList(),"cursor_one")

            // Block each real monitor separately so both production paths can finish even on the old lock order.
            val listWrite=async(Dispatchers.IO,start=CoroutineStart.LAZY) { mirror.saveConversations(page) }
            val recordsProbe=monitorProbe(records)
            val recordsAvailable=synchronized(delegate) {
                listWrite.start()
                check(waitForBlockedCall("saveNativeConversations")) { "NATIVE_LIST_WAIT_NOT_OBSERVED" }
                recordsProbe.start()
                recordsProbe.canAcquire()
            }
            withTimeout(5_000) { listWrite.await() }
            recordsProbe.thread.join(5_000)
            assertFalse(recordsProbe.thread.isAlive)

            val ack=async(Dispatchers.IO,start=CoroutineStart.LAZY) { mirror.confirmAck("chat","cursor_one") }
            val mirrorProbe=monitorProbe(delegate)
            val mirrorAvailable=synchronized(records) {
                ack.start()
                check(waitForBlockedCall("confirmNativeAck")) { "NATIVE_ACK_WAIT_NOT_OBSERVED" }
                mirrorProbe.start()
                mirrorProbe.canAcquire()
            }
            withTimeout(5_000) { ack.await() }
            mirrorProbe.thread.join(5_000)
            assertFalse(mirrorProbe.thread.isAlive)

            assertFalse("ACK 确认应在持有镜像锁时等待真实数据库锁",mirrorAvailable)
            assertTrue("会话列表保存不能持有数据库锁等待镜像锁，否则与 ACK 确认死锁",recordsAvailable)
            mirror.closeStore()
            val reopened=NativeConversationMirror(context,testScope,{"hermes"},key,observerId="phone:1")
            try {
                assertEquals(receipt,reopened.creation("original-create"))
                assertEquals("cursor_one",reopened.eventCursor("chat"))
                assertNull(reopened.pendingAck("chat"))
                assertEquals(listOf("old","created"),reopened.offlineMirror().threads()!!.map { it.id.value })
            } finally { reopened.closeStore() }
        } finally { mirror.closeStore() }
    }

    private class MonitorProbe(monitor:Any) {
        val started=CountDownLatch(1)
        val acquired=CountDownLatch(1)
        val thread=Thread({
            started.countDown()
            synchronized(monitor) { acquired.countDown() }
        },"native-mirror-lock-probe").apply { isDaemon=true }
        fun start()=thread.start()
        fun canAcquire():Boolean {
            check(started.await(5,TimeUnit.SECONDS)) { "MONITOR_PROBE_NOT_STARTED" }
            val deadline=System.nanoTime()+5_000_000_000L
            while (System.nanoTime()<deadline) {
                if (acquired.count==0L) return true
                if (thread.state==Thread.State.BLOCKED) return false
                check(thread.isAlive) { "MONITOR_PROBE_STOPPED" }
                Thread.sleep(5)
            }
            error("MONITOR_PROBE_TIMED_OUT")
        }
    }

    private fun monitorProbe(monitor:Any)=MonitorProbe(monitor)

    private fun waitForBlockedCall(method:String):Boolean {
        val deadline=System.nanoTime()+5_000_000_000L
        while (System.nanoTime()<deadline) {
            if (Thread.getAllStackTraces().any { (thread,stack) ->
                thread.state==Thread.State.BLOCKED && stack.any { frame ->
                    frame.className.startsWith(EncryptedConversationMirror::class.java.name) &&
                        (frame.methodName==method || frame.className.contains("\$$method\$"))
                }
            }) return true
            Thread.sleep(5)
        }
        return false
    }

    @Test fun ownerIsFixedBeforeTheIoWriteAndOtherAgentsStaySeparate()=runBlocking {
        var selected="agent-a"
        val old=NativeConversationMirror(context,scope(),{selected},key)
        selected="agent-b"
        val next=NativeConversationMirror(context,scope(),{selected},key)
        try {
            old.saveCheckpoint(checkpoint(listOf("only-a")))
            assertNull(next.load())
            assertEquals(listOf("only-a"),old.load()!!.threads.map {it.id.value})
        } finally {old.closeStore();next.closeStore()}
    }

    @Test fun pendingAckApprovalsAndToolFactsSurviveReopenAndPairingChangesCannotBorrowTheCursor()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:1")
        val resolved=NativeEventApproval("approval_resolved",JsonFields.obj(Json.parse(
            """{"approvalId":"approval","conversationId":"chat","decision":"once","decidedAt":2}"""))!!)
        val requested=NativeEventApproval("approval_requested",JsonFields.obj(Json.parse(
            """{"approvalId":"approval","conversationId":"chat","command":"synthetic","requestedAt":1,"options":[{"choice":"once","label":"once"}]}"""))!!)
        val event=VerifiedConversationEvent.TimelineUpsert("tool-history",1,1,TimelineMessage("answer","assistant",
            listOf(MessagePart.Text("final")),1,conversationId=ConversationId("chat"),generationId="generation",
            executionSteps=listOf(NativeExecutionStep("step","step-event","synthetic","{}",status=NativeExecutionStatus.COMPLETED,revision=4))))
        mirror.commitPage("chat",listOf(event),listOf(resolved),"cursor_one")
        mirror.closeStore()
        val reopened=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:1")
        val otherPairing=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:2")
        val otherAccount=NativeConversationMirror(context,scope("other"),{"hermes"},key,observerId="phone:1")
        try {
            assertEquals("cursor_one",reopened.pendingAck("chat"))
            assertNull(otherPairing.pendingAck("chat"))
            assertNull(otherAccount.pendingAck("chat"))
            assertNull(reopened.pendingAck("other-chat"))
            reopened.commitApprovals("chat",listOf(requested))
            assertEquals(listOf("approval_resolved"),reopened.loadApprovals("chat").map {it.type})
            assertEquals(4L,reopened.loadTimeline("chat")!!.messages.single().executionSteps.single().revision)
            reopened.confirmAck("chat","cursor_one")
            assertEquals("cursor_one",reopened.eventCursor("chat"))
            assertNull(reopened.pendingAck("chat"))
        } finally {reopened.closeStore();otherPairing.closeStore();otherAccount.closeStore()}
    }

    @Test fun onlyDurableUnknownSubmissionsAndGenerationAssociationsAreRecoveryCandidates()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:1")
        try {
            val parts=listOf(MessagePart.Text("synthetic"))
            mirror.saveSendAtomsDurably("chat",listOf(
                com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"),
                    com.openandroidintelligence.conversation.model.ClientMessageId("local-unknown"),parts,1,
                    submissionState=com.openandroidintelligence.conversation.model.LocalSubmissionState.OUTCOME_UNKNOWN),
                com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"),
                    com.openandroidintelligence.conversation.model.ClientMessageId("steering"),parts,1,
                    submissionState=com.openandroidintelligence.conversation.model.LocalSubmissionState.OUTCOME_UNKNOWN,
                    delivery=MessageDeliveryMode.STEER),
                com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"),
                    com.openandroidintelligence.conversation.model.ClientMessageId("imported"),parts,1,messageId="native-user",
                    submissionState=com.openandroidintelligence.conversation.model.LocalSubmissionState.ACCEPTED)
            ))
            assertEquals(listOf(NativeRecoveryIdentity("local-unknown")),mirror.recoveryIdentities("chat"))
        } finally {mirror.closeStore()}
    }

}
