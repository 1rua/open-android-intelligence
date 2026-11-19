package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test fun `输入斜杠期间首次拉取失败后下一次编辑可以重试`() = runTest {
        var calls = 0
        val controller = controller(object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog {
                if (++calls <= 2) throw java.io.IOException("CATALOG_TEMPORARILY_UNAVAILABLE")
                return AgentCommandCatalog(CatalogVersion("v1"), listOf(AgentCommand("/inspect", "检查")))
            }
        })
        advanceUntilIdle()
        controller.editDraft("/")
        advanceUntilIdle()
        assertTrue(controller.state.value.catalog is Loadable.Failed)
        controller.editDraft("/i")
        advanceUntilIdle()
        val catalog = controller.state.value.catalog
        assertTrue("拉取恢复后应展示当前目录，实际为 $catalog", catalog is Loadable.Ready)
        assertEquals("/inspect", (catalog as Loadable.Ready).value.commands.single().command)
    }

    @Test fun `控制器连接时发布首个目录结果`() = runTest {
        val controller = controller(object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = commands("first")
        })
        advanceUntilIdle()
        assertEquals(commands("first"), (controller.state.value.catalog as Loadable.Ready).value)
    }

    @Test fun `切换端点和会话后丢弃迟到目录并接收当前会话实时更新`() = runTest {
        val old = CompletableDeferred<AgentCommandCatalog>()
        val firstUpdates = MutableSharedFlow<AgentCommandCatalog>()
        val secondUpdates = MutableSharedFlow<AgentCommandCatalog>()
        val targets = mutableListOf<Pair<String, String?>>()
        var context = ConversationScope("p", "workspace-1", "a", "i")
        val controller = controller(object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = error("需要作用域")
            override suspend fun get(gatewayId: String, languageCode: String, conversationId: String?): AgentCommandCatalog {
                targets += gatewayId to conversationId
                if (conversationId == "first") return withContext(NonCancellable) { old.await() }
                return commands()
            }

            override fun observe(gatewayId: String, conversationId: String?) = when (conversationId) {
                "first" -> firstUpdates
                "second" -> secondUpdates
                else -> emptyFlow()
            }
        }, scopeFactory = { context })
        runCurrent()
        controller.openThread("first")
        runCurrent()
        firstUpdates.emit(commands("only-first"))
        runCurrent()
        assertEquals(commands("only-first"), (controller.state.value.catalog as Loadable.Ready).value)
        context = context.copy(gatewayId = "workspace-2")
        controller.openThread("second")
        assertFalse(controller.state.value.catalog is Loadable.Ready)
        runCurrent()
        assertEquals(Loadable.Empty, controller.state.value.catalog)
        secondUpdates.emit(commands("only-second"))
        old.complete(commands("stale"))
        firstUpdates.emit(commands("alien"))
        runCurrent()
        assertEquals(listOf("workspace-1" to null, "workspace-1" to "first", "workspace-2" to "second"), targets)
        assertEquals(commands("only-second"), (controller.state.value.catalog as Loadable.Ready).value)
        secondUpdates.emit(commands())
        runCurrent()
        assertEquals(Loadable.Empty, controller.state.value.catalog)
    }

    @Test fun `空目录时手输命令仍按原文提交`() = runTest {
        val repository = Repository()
        val controller = controller(object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = commands()
        }, repository = repository)
        controller.openThread("thread")
        advanceUntilIdle()
        val line = "/unknown  参数"
        controller.editDraft(line)
        advanceUntilIdle()
        assertEquals(Loadable.Empty, controller.state.value.catalog)
        controller.sendDraft()
        advanceUntilIdle()
        val sent = repository.sent.single()
        assertEquals(line, sent.text)
    }

    private fun commands(vararg names: String) = AgentCommandCatalog(
        CatalogVersion("test"), names.map { AgentCommand("/$it", "说明") },
    )

    private fun TestScope.controller(
        catalog: AgentCommandCatalogRepository,
        repository: Repository = Repository(),
        scopeFactory: () -> ConversationScope = { ConversationScope("p", "g", "a", "i") },
    ) = WorkbenchController(
        workbenchTestScope(), repository, catalog, scopeFactory, languageCode = "ja-JP",
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private class Repository : ConversationRepository {
        val sent = mutableListOf<OutgoingMessage>()
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(emptyList(),null)
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String)=error("unused")
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            sent += message
            return MessageAcceptance("accepted", message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch:MessageBatch)=error("unused")
        override fun observeEvents(scope:ConversationScope)=emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId:String,requestId:String)=error("unused")
    }
}
