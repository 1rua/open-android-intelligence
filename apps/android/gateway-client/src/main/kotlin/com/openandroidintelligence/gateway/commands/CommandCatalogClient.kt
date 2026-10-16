package com.openandroidintelligence.gateway.commands

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.JsonFields
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class CommandCatalogEntry(
    val id: String,
    val invocation: String,
    val title: String,
    val description: String,
    val acceptsArguments: Boolean,
    val availability: String,
    val argumentHint: String? = null,
)

data class CommandCatalog(
    val format: String,
    val catalogVersion: String,
    val languageCode: String,
    val commands: List<CommandCatalogEntry>,
) {
    companion object {
        const val FORMAT_V1 = "agent-command-catalog-1.0"
    }
}

/**
 * The Gateway-level Agent command catalog.
 *
 * The catalog only drives discovery and display. Unknown or unavailable entries
 * are still sent verbatim, because interpreting a slash command is the Agent's
 * job, not the phone's.
 */
class CommandCatalogClient(private val http: GatewayHttpClient) {

    suspend fun get(languageCode: String): CommandCatalog {
        val encodedLanguageCode = URLEncoder.encode(languageCode, StandardCharsets.UTF_8.name())
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
        val response = http.execute(
            SignedGatewayRequest(
                method = "GET",
                target = "/open-android-intelligence/v2/commands?languageCode=$encodedLanguageCode",
                headers = listOf(RawHeader("Accept", "application/json")),
            ),
        )
        if (response.status != 200) {
            throw IllegalStateException("COMMAND_CATALOG_FAILED:${response.status}")
        }
        val body = response.requireData("COMMAND_CATALOG_FAILED")

        val commandArray = JsonFields.array(JsonFields.field(body, "commands"))
            ?: throw IllegalStateException("COMMAND_CATALOG_FAILED:invalid-commands")
        val wireCommands = commandArray.items.mapNotNull(JsonFields::obj)
        val commands = wireCommands.mapNotNull { entry ->
            val rawInvocation = JsonFields.string(entry, "invocation") ?: return@mapNotNull null
            val trimmedInvocation = rawInvocation.trim()
            if (trimmedInvocation.isBlank() || trimmedInvocation.any { it.isWhitespace() }) {
                return@mapNotNull null
            }
            val invocation = if (trimmedInvocation.startsWith("/")) {
                trimmedInvocation
            } else {
                "/$trimmedInvocation"
            }
            if (invocation == "/" || invocation.startsWith("//")) return@mapNotNull null
            CommandCatalogEntry(
                id = JsonFields.string(entry, "id")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: invocation.removePrefix("/"),
                invocation = invocation,
                title = JsonFields.string(entry, "title")?.trim()?.takeIf { it.isNotEmpty() } ?: invocation,
                description = JsonFields.string(entry, "description")?.trim().orEmpty(),
                acceptsArguments = JsonFields.bool(entry, "acceptsArguments") ?: false,
                availability = JsonFields.string(entry, "availability") ?: "available",
                argumentHint = JsonFields.string(entry, "argumentHint")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        if (commandArray.items.isNotEmpty() && commands.isEmpty()) {
            throw IllegalStateException("COMMAND_CATALOG_FAILED:no-valid-commands")
        }

        return CommandCatalog(
            format = JsonFields.string(body, "format") ?: CommandCatalog.FORMAT_V1,
            catalogVersion = JsonFields.string(body, "catalogVersion")
                ?: throw IllegalStateException("COMMAND_CATALOG_FAILED:missing-version"),
            languageCode = JsonFields.string(body, "languageCode") ?: languageCode,
            commands = commands,
        )
    }
}
