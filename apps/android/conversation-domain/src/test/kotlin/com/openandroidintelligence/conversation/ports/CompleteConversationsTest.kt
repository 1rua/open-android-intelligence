package com.openandroidintelligence.conversation.ports

import com.openandroidintelligence.conversation.model.ConversationId
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompleteConversationsTest {
    private val scope=ConversationScope("profile","gateway","account","install")
    private fun summary(id:String)=ConversationSummary(ConversationId(id),id,1)

    @Test fun drainsAllPagesWithoutLosingFirstPageAndDeduplicatesBoundaries()=runTest {
        val requested=mutableListOf<String?>()
        val repository=repository { page ->
            requested+=page.cursor
            when(page.cursor) {
                null -> ConversationPage(listOf(summary("first"),summary("boundary")),"second",4)
                "second" -> ConversationPage(listOf(summary("boundary"),summary("last")),null,4)
                else -> error("UNEXPECTED_CURSOR")
            }
        }
        val page=repository.completeConversations(scope)
        assertEquals(listOf(null,"second"),requested)
        assertEquals(listOf("first","boundary","last"),page.conversations.map { it.id.value })
        assertEquals(4L,page.snapshotRevision)
    }

    @Test fun snapshotChangeRestartsFromFirstPageWithoutMixingOldHistory()=runTest {
        var starts=0
        val requested=mutableListOf<String?>()
        val repository=repository { page ->
            requested+=page.cursor
            if (page.cursor == null) {
                starts++
                ConversationPage(listOf(summary(if(starts==1) "obsolete" else "fresh_first")),"tail",starts.toLong())
            } else ConversationPage(listOf(summary("fresh_tail")),null,2)
        }
        assertEquals(listOf("fresh_first","fresh_tail"),repository.completeConversations(scope).conversations.map { it.id.value })
        assertEquals(listOf(null,"tail",null,"tail"),requested)
    }

    @Test fun expiredCursorRestartsButCursorCyclesFailWithoutPublishingPartialHistory()=runTest {
        var starts=0
        val reset=repository { page ->
            if(page.cursor==null) { starts++; ConversationPage(listOf(summary("head_$starts")),"tail",starts.toLong()) }
            else if(starts==1) error("CONVERSATIONS_FAILED:CURSOR_EXPIRED")
            else ConversationPage(listOf(summary("tail")),null,2)
        }
        assertEquals(listOf("head_2","tail"),reset.completeConversations(scope).conversations.map { it.id.value })
        val cycle=repository { ConversationPage(listOf(summary("partial")),"again",1) }
        assertEquals("CONVERSATIONS_CURSOR_CYCLE",runCatching { cycle.completeConversations(scope) }.exceptionOrNull()?.message)
    }

    @Test fun cachedPageProvenanceSurvivesTheCompleteHistoryBoundary()=runTest {
        val cached=repository { ConversationPage(listOf(summary("cached")),null,authoritative=false) }
        assertFalse(cached.completeConversations(scope).authoritative)
    }

    private fun repository(list:suspend (PageRequest)->ConversationPage)=object:ConversationRepository {
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=list(page)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override fun observeEvents(scope:ConversationScope)=emptyFlow<VerifiedConversationEvent>()
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("UNUSED")
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("UNUSED")
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("UNUSED")
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
