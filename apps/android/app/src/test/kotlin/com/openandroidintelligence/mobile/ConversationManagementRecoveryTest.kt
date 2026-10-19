package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class ConversationManagementRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {
        override fun getOrCreate()=SecretKeySpec(ByteArray(32) { 37 },"AES")
        override fun delete()=Unit
    }
    private fun scope(name:String)=ConversationScope("management_$name","https://gateway.example","account","install")
    private fun mirror(scope:ConversationScope)=EncryptedConversationMirror(context,scope,key,Dispatchers.Unconfined)
    private fun repository(host:NativeManagementHost,store:ConversationManagementOperationStore)=GatewayConversationRepository(
        ConversationClient(GatewayHttpClient(GatewayProfile("account","device","session","https://gateway.example"),
            host,{ByteArray(64)},InMemoryEventCursorStore())),supportsConversationManagement=true,managementOperations=store)

    @Test fun archiveAndDeleteResumeTheExactDurableRequestBeforeTheFirstListAfterProcessRestart() = runBlocking {
        for (delete in listOf(false,true)) {
            val account=scope("restart_$delete")
            val original=mirror(account)
            original.saveThreads(listOf(ConversationSummary(ConversationId("conv_a"),"A",1)))
            val host=NativeManagementHost().apply { failAfterNative="network" }
            val first=repository(host,original)
            assertTrue(runCatching { if (delete) first.deleteConversation("conv_a") else first.setArchived("conv_a",true) }.exceptionOrNull() is IOException)
            val pending=original.pendingManagementOperations().single()
            assertEquals(host.writes.single().requestId,pending.requestId)
            original.closeStore()

            val reopened=mirror(account)
            assertEquals(listOf(pending),reopened.pendingManagementOperations())
            val page=repository(host,reopened).listConversations(account,PageRequest())
            assertEquals(2,host.writes.size)
            assertEquals(host.writes[0],host.writes[1])
            assertEquals(1,host.nativeMutations)
            assertTrue(reopened.pendingManagementOperations().isEmpty())
            if (delete) {
                assertTrue(page.conversations.isEmpty())
                assertTrue(reopened.threads()!!.isEmpty())
            } else {
                assertTrue(page.conversations.single().archived)
                assertTrue(reopened.threads()!!.single().archived)
            }
            reopened.closeStore()
            val committed=mirror(account)
            assertTrue(committed.pendingManagementOperations().isEmpty())
            assertEquals(delete,committed.threads()!!.isEmpty())
            committed.wipe()
        }
    }

    @Test fun oppositeArchiveIntentFirstRecoversTheLostReceiptWithItsOriginalPayload() = runBlocking {
        val account=scope("opposite_intent")
        val original=mirror(account)
        val host=NativeManagementHost().apply { failAfterNative="unknown" }
        assertNotNull(runCatching { repository(host,original).setArchived("conv_a",true) }.exceptionOrNull())
        val pending=original.pendingManagementOperations().single()
        original.closeStore()
        val reopened=mirror(account)
        repository(host,reopened).setArchived("conv_a",false)
        assertEquals(listOf("{\"archived\":true}","{\"archived\":true}","{\"archived\":false}"),host.writes.map { it.body })
        assertEquals(pending.requestId,host.writes[1].requestId)
        assertNotEquals(pending.requestId,host.writes[2].requestId)
        assertFalse(host.archived)
        assertTrue(reopened.pendingManagementOperations().isEmpty())
        reopened.wipe()
    }

    @Test fun unknownOperationsSurviveAuthenticationBusyServerMalformedAndCancelledRetries() = runBlocking {
        for (failure in listOf("auth","device","busy","server","malformed","cancel")) {
            val account=scope("retained_$failure")
            val original=mirror(account)
            val host=NativeManagementHost().apply { failAfterNative="network" }
            assertNotNull(runCatching { repository(host,original).deleteConversation("conv_a") }.exceptionOrNull())
            val pending=original.pendingManagementOperations().single()
            original.closeStore()
            val reopened=mirror(account)
            host.nextFailure=failure
            assertNotNull(runCatching { repository(host,reopened).setArchived("conv_a",false) }.exceptionOrNull())
            assertEquals(listOf(pending),reopened.pendingManagementOperations())
            assertEquals(listOf("DELETE","DELETE"),host.writes.map { it.method })
            assertEquals(pending.requestId,host.writes.last().requestId)
            // A later first-page read can recover a deleted identity even though
            // that identity is already absent from the native conversation list.
            assertTrue(repository(host,reopened).listConversations(account,PageRequest()).conversations.isEmpty())
            assertTrue(reopened.pendingManagementOperations().isEmpty())
            reopened.wipe()
        }
    }

    @Test fun freshExplicitPreflightRefusalRetiresItsIdAndLeavesTheMirrorUntouched() = runBlocking {
        val account=scope("definite_rejection")
        val store=mirror(account)
        store.saveThreads(listOf(ConversationSummary(ConversationId("conv_a"),"A",1)))
        val host=NativeManagementHost().apply { nextFailure="busy" }
        val repository=repository(host,store)
        assertNotNull(runCatching { repository.setArchived("conv_a",true) }.exceptionOrNull())
        assertTrue(store.pendingManagementOperations().isEmpty())
        assertFalse(store.threads()!!.single().archived)
        repository.setArchived("conv_a",false)
        assertNotEquals(host.writes.first().requestId,host.writes.last().requestId)
        assertEquals(1,host.nativeMutations)
        store.wipe()
    }

    @Test fun mainAndBubbleRepositoriesSerializeAcrossMirrorInstancesAndRefreshCachedPendingRows() = runBlocking {
        val account=scope("shared_workbenches")
        val mainStore=mirror(account)
        val bubbleStore=mirror(account)
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val host=NativeManagementHost().apply {
            beforeWrite={ if (writes.size == 1) { entered.complete(Unit); release.await() } }
        }
        val main=launch { repository(host,mainStore).setArchived("conv_a",true) }
        entered.await()
        // Prime the other SQLite handle's plaintext cache before main commits.
        val pending=bubbleStore.pendingManagementOperations().single()
        val bubble=launch(start=CoroutineStart.UNDISPATCHED) { repository(host,bubbleStore).deleteConversation("conv_a") }
        assertEquals(1,host.writes.size)
        assertEquals(listOf(pending),mainStore.pendingManagementOperations())
        release.complete(Unit)
        joinAll(main,bubble)
        assertEquals(listOf("PATCH","DELETE"),host.writes.map { it.method })
        assertNotEquals(host.writes[0].requestId,host.writes[1].requestId)
        assertTrue(mainStore.pendingManagementOperations().isEmpty())
        assertTrue(bubbleStore.pendingManagementOperations().isEmpty())
        val reopened=mirror(account)
        assertTrue(reopened.pendingManagementOperations().isEmpty())
        reopened.closeStore()
        bubbleStore.closeStore()
        mainStore.wipe()
    }

    @Test fun aConflictingReservationCannotReplaceAnUnknownIntentAndOtherAccountsCannotSeeIt() = runBlocking {
        val account=scope("scope_isolation")
        val original=mirror(account)
        val pending=original.reserveManagementOperation(ConversationManagementIntent.Archive("conv_a",true))
        val sibling=mirror(account)
        assertEquals("CONVERSATION_MANAGEMENT_PENDING",runCatching {
            sibling.reserveManagementOperation(ConversationManagementIntent.Delete("conv_a"))
        }.exceptionOrNull()?.message)
        assertEquals(listOf(pending),sibling.pendingManagementOperations())
        val other=mirror(account.copy(accountId="another_account"))
        assertTrue(other.pendingManagementOperations().isEmpty())
        other.wipe()
        sibling.closeStore()
        original.wipe()
    }

    @Test fun anOlderWrapperReturnCannotOverwriteTheNewerSiblingArchiveReceipt() = runBlocking {
        val account=scope("late_wrapper")
        val mainStore=mirror(account)
        val bubbleStore=mirror(account)
        mainStore.saveThreads(listOf(ConversationSummary(ConversationId("conv_a"),"A",1)))
        val host=NativeManagementHost()
        val main=repository(host,mainStore)
        val committed=CompletableDeferred<Unit>()
        val returnToWrapper=CompletableDeferred<Unit>()
        val delayed=object:ConversationRepository by main {
            override suspend fun setArchived(conversationId:String,archived:Boolean) {
                main.setArchived(conversationId,archived)
                committed.complete(Unit)
                returnToWrapper.await()
            }
        }
        val wrapped=MirroredConversationRepository(delayed,mainStore,managementCommittedByDelegate=true)
        val older=launch { wrapped.setArchived("conv_a",true) }
        committed.await()
        repository(host,bubbleStore).setArchived("conv_a",false)
        assertFalse(mainStore.threads()!!.single().archived)
        returnToWrapper.complete(Unit)
        older.join()
        assertFalse(mainStore.threads()!!.single().archived)
        val reopened=mirror(account)
        assertFalse(reopened.threads()!!.single().archived)
        reopened.closeStore()
        bubbleStore.closeStore()
        mainStore.wipe()
    }

    @Test fun persistenceFailurePreventsTheFirstNativeWrite() = runBlocking {
        val account=scope("persistence_failure")
        val unavailable=object:AesGcmKeyProvider {
            override fun getOrCreate():javax.crypto.SecretKey=throw IOException("KEY_UNAVAILABLE")
            override fun delete()=Unit
        }
        val store=EncryptedConversationMirror(context,account,unavailable,Dispatchers.Unconfined)
        val host=NativeManagementHost()
        assertTrue(runCatching { repository(host,store).deleteConversation("conv_a") }.exceptionOrNull() is IOException)
        assertTrue(host.writes.isEmpty())
        assertTrue(store.pendingManagementOperations().isEmpty())
        store.wipe()
    }

    @Test fun laterPagesDoNotRetryPendingMutationsAndUnknownRecoveryDoesNotHideHistory() = runBlocking {
        val account=scope("list_recovery")
        val store=mirror(account)
        val host=NativeManagementHost().apply { failAfterNative="network" }
        val repository=repository(host,store)
        assertNotNull(runCatching { repository.setArchived("conv_a",true) }.exceptionOrNull())
        repository.listConversations(account,PageRequest(cursor="next_page"))
        assertEquals(1,host.writes.size)
        host.nextFailure="server"
        val first=repository.listConversations(account,PageRequest())
        assertTrue(first.conversations.single().archived)
        assertEquals(2,host.writes.size)
        assertEquals(1,store.pendingManagementOperations().size)
        store.wipe()
    }

    private class NativeManagementHost : GatewayByteTransport {
        data class Write(val method:String,val target:String,val body:String,val requestId:String)
        val writes=mutableListOf<Write>()
        var nativeMutations=0
        var archived=false
        var deleted=false
        var failAfterNative:String?=null
        var nextFailure:String?=null
        var beforeWrite:(suspend (WireRequest)->Unit)?=null
        private var pending:Write?=null
        private val receipts=mutableMapOf<String,WireResponse>()

        override suspend fun execute(request:WireRequest):WireResponse {
            if (request.method == "GET") return data(if (deleted) """{"conversations":[],"snapshotRevision":9}""" else
                """{"conversations":[{"conversationId":"conv_a","title":"A","archived":$archived}],"snapshotRevision":9}""")
            val id=request.headers.single { it.name == "Idempotency-Key" }.value
            assertEquals(id,request.headers.single { it.name == "X-Open-Android-Intelligence-Request-Id" }.value)
            val write=Write(request.method,request.target,request.body.decodeToString(),id)
            writes+=write
            beforeWrite?.invoke(request)
            nextFailure?.let { nextFailure=null; return failure(it) }
            receipts[id]?.let { return it }
            if (pending != null && pending != write) return error(409,"OUTCOME_UNKNOWN")
            if (pending == null) {
                pending=write
                nativeMutations++
                if (write.method == "DELETE") deleted=true else archived=write.body.contains("true")
            }
            failAfterNative?.let { failAfterNative=null; return failure(it) }
            return data(if (write.method == "DELETE") """{"conversationId":"conv_a","deleted":true}""" else
                """{"conversation":{"conversationId":"conv_a","archived":$archived}}""").also {
                receipts[id]=it
                pending=null
            }
        }
        private fun failure(kind:String):WireResponse=when(kind) {
            "network" -> throw IOException("connection lost after native commit")
            "cancel" -> throw CancellationException("waiter cancelled after dispatch")
            "auth" -> error(401,"SESSION_INVALID")
            "device" -> error(403,"DEVICE_REVOKED")
            "busy" -> error(409,"CONVERSATION_BUSY")
            "unknown" -> error(409,"OUTCOME_UNKNOWN")
            "server" -> error(503,"INTERNAL_ERROR")
            "malformed" -> WireResponse(200,emptyList(),"{truncated".toByteArray())
            else -> error("unexpected failure")
        }
        private fun data(body:String)=WireResponse(200,emptyList(),"""{"protocol":"2.1","data":$body}""".toByteArray())
        private fun error(status:Int,code:String)=WireResponse(status,emptyList(),"""{"protocol":"2.1","error":{"code":"$code"}}""".toByteArray())
        override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
    }
}
