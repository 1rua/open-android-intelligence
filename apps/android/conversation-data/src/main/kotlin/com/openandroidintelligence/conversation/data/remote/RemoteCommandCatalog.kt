package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.gateway.schema.JsonValue

/** 将真实协议目录归一为输入补全项。版本与参数提示可以缺省。 */
internal object RemoteCommandCatalog {
    private val unversioned = CatalogVersion("unversioned")
    val empty = AgentCommandCatalog(unversioned, emptyList())

    fun decode(value: JsonValue): AgentCommandCatalog {
        val entries = (value as? JsonValue.JArray)?.items
            ?: value.array("items") ?: value.array("commands") ?: value.array("availableCommands")
            ?: throw RemoteSessionException("REMOTE_INVALID_COMMAND_CATALOG")
        val available = entries.filterNot { it.text("availability") == "unavailable" }
        val commands = available.mapNotNull(::command).distinctBy { it.command.lowercase() }
        if (available.isNotEmpty() && commands.isEmpty()) throw RemoteSessionException("REMOTE_INVALID_COMMAND_CATALOG")
        val rawVersion = value.text("catalogVersion") ?: value.text("version")
        val version = rawVersion?.let { runCatching { CatalogVersion(it) }.getOrNull() } ?: unversioned
        return AgentCommandCatalog(version, commands)
    }

    private fun command(value: JsonValue): AgentCommand? {
        val name = (value.text("invocation") ?: value.text("command") ?: value.text("name"))
            ?.trim()?.removePrefix("/") ?: return null
        if (name.isEmpty() || name.any { it.isWhitespace() || it.isISOControl() || it == '/' }) return null
        val hint = value.text("argumentHint") ?: value.at("input")?.text("hint")
        return AgentCommand("/$name", value.text("description").orEmpty(), hint?.takeIf { it.isNotBlank() })
    }
}
