package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.flow.emptyFlow

/** Explicit read-only mode for a persisted mirror when the host is unreachable. */
class OfflineMirrorRepository(private val mirror:EncryptedConversationMirror) : ConversationRepository {
    private fun unavailable():Nothing=throw java.io.IOException("OFFLINE_MIRROR")
    override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(mirror.threads().orEmpty(),null)
    override suspend fun timeline(conversationId:String,page:PageRequest)=mirror.timeline(conversationId) ?: TimelinePage(emptyList(),null)
    override fun observeEvents(scope:ConversationScope)=emptyFlow<VerifiedConversationEvent>()
    override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=unavailable()
    override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=unavailable()
    override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=unavailable()
    override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED,"OFFLINE_MIRROR")
}
