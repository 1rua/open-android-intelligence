package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class EncryptedMirrorRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {override fun getOrCreate()=SecretKeySpec(ByteArray(32){7},"AES");override fun delete(){}}
    private val scope=ConversationScope("profile_mirror","https://first.example","acct_same","install_one")
    @Test fun reopensEncryptedDraftFrozenIdentitiesAllPagesAndGatewayPartitions() {
        val mirror=EncryptedConversationMirror(context,scope,key)
        val threads=listOf(ConversationSummary(ConversationId("conv_one"),"会话",1))
        val messages=(0..120).map { TimelineMessage("msg_$it","user",listOf(MessagePart.Text("private-$it")),it.toLong(),batchId="batch_one") }
        val submission=SavedSubmission("private-draft",emptyList(),8,"conv_one","cm_frozen")
        mirror.save(WorkbenchCheckpoint(threads,"conv_one","会话",messages,mapOf("msg_120" to 3),"private-draft",8,emptyList(),submission,emptySet(),setOf("evt_last"),
            listOf(SavedBatch("cb_frozen","conv_one",listOf(OutgoingMessage(ClientMessageId("cm_member"),"pending"))))))
        mirror.saveThreads(threads);mirror.saveTimeline("conv_one",TimelinePage(messages,null,123))
        val restored=EncryptedConversationMirror(context,scope,key)
        assertEquals("cm_frozen",restored.load()!!.submission!!.clientMessageId);assertEquals("cb_frozen",restored.load()!!.batches.single().batchId)
        assertEquals(121,restored.timeline("conv_one")!!.messages.size);assertEquals("batch_one",restored.timeline("conv_one")!!.messages.first().batchId)
        assertNull(EncryptedConversationMirror(context,scope.copy(gatewayId="https://second.example"),key).load())
        File(context.noBackupFilesDir,"private-documents").walkTopDown().filter{it.isFile}.forEach { assertFalse(it.readBytes().decodeToString().contains("private-draft")) }
        restored.wipe();assertNull(mirror.load())
    }
    @Test fun snapshotRecoveryRemovesObsoleteThreadsAndRetainsTheCompleteBaseline() {
        val mirror=EncryptedConversationMirror(context,scope,key)
        mirror.saveTimeline("conv_obsolete",TimelinePage(listOf(TimelineMessage("old","user",listOf(MessagePart.Text("old")),1)),null))
        val threads=listOf(ConversationSummary(ConversationId("conv_new"),"New",2))
        mirror.installBaseline(threads,mapOf("conv_new" to TimelinePage(listOf(TimelineMessage("new","assistant",listOf(MessagePart.Text("fresh")),2)),null,9)),"evt_baseline")
        val reopened=EncryptedConversationMirror(context,scope,key);reopened.finishBaselineRecovery()
        assertNull(reopened.timeline("conv_obsolete"));assertEquals("new",reopened.timeline("conv_new")!!.messages.single().id);assertEquals(threads,reopened.threads())
        reopened.wipe()
    }
}
