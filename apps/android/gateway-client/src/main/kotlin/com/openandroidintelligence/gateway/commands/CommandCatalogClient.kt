package com.openandroidintelligence.gateway.commands

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.JsonFields

data class CommandCatalogEntry(
    val id: String,
    val invocation: String,
    val title: String,
    val description: String,
    val acceptsArguments: Boolean,
    val availability: String,
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
        val response = http.execute(
            SignedGatewayRequest(
                method = "GET",
                target = "/open-android-intelligence/v2/commands?languageCode=$languageCode",
                headers = listOf(RawHeader("Accept", "application/json")),
            ),
        )
        if (response.status != 200) {
            throw IllegalStateException("COMMAND_CATALOG_FAILED:${response.status}")
        }
        val body = response.requireData("COMMAND_CATALOG_FAILED")

        return CommandCatalog(
            format = JsonFields.string(body, "format") ?: CommandCatalog.FORMAT_V1,
            catalogVersion = JsonFields.string(body, "catalogVersion")
                ?: throw IllegalStateException("COMMAND_CATALOG_FAILED:missing-version"),
            languageCode = JsonFields.string(body, "languageCode") ?: languageCode,
            commands = JsonFields.objects(body, "commands").mapNotNull { entry ->
                val invocation = JsonFields.string(entry, "invocation") ?: return@mapNotNull null
                CommandCatalogEntry(
                    id = JsonFields.string(entry, "id") ?: invocation.removePrefix("/"),
                    invocation = invocation,
                    title = JsonFields.string(entry, "title") ?: invocation,
                    description = JsonFields.string(entry, "description").orEmpty(),
                    acceptsArguments = JsonFields.bool(entry, "acceptsArguments") ?: false,
                    availability = JsonFields.string(entry, "availability") ?: "available",
                )
            },
        )
    }
}
