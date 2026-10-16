package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchCatalogRefreshTest {
    @Test fun openingSlashMenuRefreshesCommandsAndUsesTheConfiguredLocale() = runTest {
        val requests=mutableListOf<String>()
        val controller=controller(object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String):AgentCommandCatalog {
                requests+=languageCode
                return AgentCommandCatalog(CatalogVersion("v${requests.size}"),listOf(AgentCommand("/live${requests.size}","说明")))
            }
        })
        advanceUntilIdle()
        controller.editDraft("/")
        advanceUntilIdle()
        assertEquals(listOf("ja-JP","ja-JP"),requests)
        assertEquals("/live2",(controller.state.value.catalog as Loadable.Ready).value.commands.single().command)
        controller.editDraft("/l")
        controller.editDraft("/li")
        advanceUntilIdle()
        assertEquals(2,requests.size)
        controller.editDraft("")
        controller.editDraft("/")
        advanceUntilIdle()
        assertEquals(3,requests.size)
    }

    @Test fun olderCatalogResponseCannotOverwriteANewerRefresh() = runTest {
        val old=CompletableDeferred<AgentCommandCatalog>()
        var calls=0
        val controller=controller(object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String):AgentCommandCatalog =
                if (++calls==1) old.await() else AgentCommandCatalog(CatalogVersion("fresh"),listOf(AgentCommand("/fresh","说明")))
        })
        runCurrent()
        controller.editDraft("/")
        runCurrent()
        assertEquals("fresh",(controller.state.value.catalog as Loadable.Ready).value.version.value)
        old.complete(AgentCommandCatalog(CatalogVersion("stale"),listOf(AgentCommand("/stale","说明"))))
        advanceUntilIdle()
        assertEquals("fresh",(controller.state.value.catalog as Loadable.Ready).value.version.value)
    }

    private fun TestScope.controller(catalog:AgentCommandCatalogRepository)=WorkbenchController(
        workbenchTestScope(),Repository(),catalog,{ ConversationScope("p","g","a","i") },languageCode="ja-JP",
        replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
    private class Repository : ConversationRepository {
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(emptyList(),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String)=error("unused")
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage)=error("unused")
        override suspend fun submitBatch(batch:MessageBatch)=error("unused")
        override fun observeEvents(scope:ConversationScope)=emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId:String,requestId:String)=error("unused")
    }
}
