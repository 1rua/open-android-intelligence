package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.AgentSessionException
import com.openandroidintelligence.gateway.agents.CoreAgent

/** A command belongs to the selected host; another Agent's catalog cannot interpret it. */
class CoreAgentCommandCatalogRepository(
    private val client: AgentSessionClient,
    private val currentAgent: () -> CoreAgent?,
    private val negotiated: Boolean,
) : AgentCommandCatalogRepository {
    override suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog {
        if (!negotiated) throw AgentSessionException("COMMAND_CATALOG_UNSUPPORTED")
        val agent = currentAgent() ?: throw AgentSessionException("NO_ACTIVE_AGENT_SELECTED")
        val catalog = client.commands(agent, languageCode)
        return AgentCommandCatalog(CatalogVersion(catalog.catalogVersion), catalog.commands.map {
            AgentCommand(it.invocation, it.description.ifBlank { it.title }, it.argumentHint)
        })
    }
}
