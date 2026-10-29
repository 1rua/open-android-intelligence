package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchOptimisticBootstrapTest {
    @Test
    fun firstMessageBubbleSurvivesConversationCreationWhileDurableSaveIsPending() = runTest {
        val created = CompletableDeferred<Conversation>()
        val writesStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val posted = mutableListOf<OutgoingMessage>()
        val repository = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = created.await()
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
                posted += message
                return MessageAcceptance("server-user", message.clientMessageId.value)
            }
            override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("batch is outside this scenario")
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
            override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        }
        val persistence = object : AwaitableWorkbenchPersistence {
            private var checkpoint: WorkbenchCheckpoint? = null
            private val atoms = mutableMapOf<String, List<LocalSendAtom>>()
            override fun load() = checkpoint
            override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
            override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
            override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) { this.atoms[conversationId] = atoms.toList() }
            override suspend fun loadTimeline(conversationId: String): TimelinePage? = null
            override suspend fun loadKnownMediaMessages() = emptyList<TimelineMessage>()
            override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
            override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) {
                writesStarted.complete(Unit)
                releaseWrite.await()
                this.atoms[conversationId] = atoms.toList()
            }
            override suspend fun flush() = Unit
            override suspend fun closeStore() = Unit
        }
        val controller = WorkbenchController(
            this, repository,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "install") },
            persistence = persistence,
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
            supportsMessageBatches = false,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        fun userRows() = (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().filter { it.isUser }
        try {
            runCurrent()
            assertNull(controller.state.value.activeThreadId)
            controller.editDraft("synthetic first prompt")
            controller.sendDraft()
            val key = userRows().single().key
            runCurrent()
            assertEquals(listOf(key), userRows().map { it.key })
            assertTrue(posted.isEmpty())

            created.complete(Conversation(ConversationId("created-chat"), "created", 0))
            runCurrent()
            assertTrue("The durable send record must be pending before any remote message request", writesStarted.isCompleted)
            assertTrue(posted.isEmpty())
            assertEquals("Creating the real conversation must keep the same already-visible pending bubble", listOf(key), userRows().map { it.key })
        } finally {
            releaseWrite.complete(Unit)
            controller.close()
        }
    }
}
