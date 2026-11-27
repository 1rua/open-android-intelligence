package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments

/** Organization is a phone preference; native session history remains owned by the Agent. */
interface NativeConversationOrganizationStore {
    fun read(agentId: String): ConversationOrganization
    fun update(agentId: String, expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization
    fun removeConversation(agentId: String, conversationId: String)
}

/** Explicitly ephemeral implementation for repository tests and callers without Android storage. */
class MemoryNativeConversationOrganizationStore : NativeConversationOrganizationStore {
    private val organizations = mutableMapOf<String, ConversationOrganization>()
    @Synchronized override fun read(agentId: String) = organizations[agentId]?.snapshot() ?: ConversationOrganization()
    @Synchronized override fun update(agentId: String, expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization {
        check(read(agentId).revision == expectedRevision) { "ORGANIZATION_CONFLICT" }
        return organization.committed(expectedRevision).also { organizations[agentId] = it }.snapshot()
    }
    @Synchronized override fun removeConversation(agentId: String, conversationId: String) {
        val current = read(agentId)
        val next = current.withoutConversation(conversationId)
        if (next != current) update(agentId, current.revision, next)
    }
}

/** Account/install/Agent scoped, atomic encrypted preferences shared by all native conversation surfaces. */
class EncryptedNativeConversationOrganizationStore(
    context: Context,
    scope: ConversationScope,
    keyProvider: AesGcmKeyProvider? = null,
) : NativeConversationOrganizationStore {
    private val documentScope = "native-conversation-organization-v1:" + Json.canonical(Json.of(
        listOf(scope.profileId, scope.gatewayId, scope.accountId, scope.installId)))
    private val documents = if (keyProvider == null) EncryptedDocuments(context, documentScope)
        else EncryptedDocuments(context, documentScope, keyProvider)

    override fun read(agentId: String): ConversationOrganization = synchronized(lock) {
        val bytes = documents.read(agentId) ?: return@synchronized ConversationOrganization()
        try {
            val value = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("ORGANIZATION_INVALID")
            fun strings(key: String) = JsonFields.array(JsonFields.field(value, key))?.items?.map {
                (it as? JsonValue.JString)?.value ?: error("ORGANIZATION_INVALID")
            } ?: error("ORGANIZATION_INVALID")
            val assignments = JsonFields.obj(JsonFields.field(value, "assignments")) ?: error("ORGANIZATION_INVALID")
            ConversationOrganization(
                revision = JsonFields.long(value, "revision") ?: error("ORGANIZATION_INVALID"),
                sortMode = ConversationSortMode.entries.firstOrNull { it.wireValue == JsonFields.string(value, "sortMode") }
                    ?: error("ORGANIZATION_INVALID"),
                customOrder = strings("customOrder"),
                folders = JsonFields.objects(value, "folders").map {
                    ConversationFolder(JsonFields.string(it, "folderId") ?: error("ORGANIZATION_INVALID"),
                        JsonFields.string(it, "name") ?: error("ORGANIZATION_INVALID"))
                },
                assignments = assignments.fields.associate { (id, folder) ->
                    id to ((folder as? JsonValue.JString)?.value ?: error("ORGANIZATION_INVALID"))
                },
            ).validated()
        } finally { bytes.fill(0) }
    }

    override fun update(agentId: String, expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization = synchronized(lock) {
        check(read(agentId).revision == expectedRevision) { "ORGANIZATION_CONFLICT" }
        val committed = organization.committed(expectedRevision)
        val bytes = Json.canonical(Json.of(mapOf("revision" to committed.revision, "sortMode" to committed.sortMode.wireValue,
            "customOrder" to committed.customOrder, "folders" to committed.folders.map { mapOf("folderId" to it.folderId, "name" to it.name) },
            "assignments" to committed.assignments))).toByteArray()
        try { documents.write(agentId, bytes) } finally { bytes.fill(0) }
        committed
    }

    override fun removeConversation(agentId: String, conversationId: String) = synchronized(lock) {
        val current = read(agentId)
        val next = current.withoutConversation(conversationId)
        if (next != current) update(agentId, current.revision, next)
        Unit
    }

    fun wipe() = synchronized(lock) { documents.erase() }

    // Every surface is in the application process. The lock must span store instances,
    // rather than only individual document reads/writes, to make revision CAS atomic.
    private companion object { val lock = Any() }
}

private fun ConversationOrganization.snapshot() = copy(customOrder = customOrder.toList(), folders = folders.toList(), assignments = assignments.toMap())
private fun ConversationOrganization.committed(expectedRevision: Long): ConversationOrganization {
    check(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE) { "ORGANIZATION_INVALID" }
    return copy(revision = expectedRevision + 1).snapshot().validated()
}
private fun ConversationOrganization.validated(): ConversationOrganization {
    val ids = folders.map { it.folderId }
    check(revision >= 0 && ids.size == ids.toSet().size && ids.none(String::isBlank) && folders.none { it.name.isBlank() } &&
        customOrder.size == customOrder.toSet().size && customOrder.none(String::isBlank) &&
        assignments.keys.none(String::isBlank) && assignments.values.all { it in ids }) { "ORGANIZATION_INVALID" }
    return this
}
private fun ConversationOrganization.withoutConversation(id: String) = copy(customOrder = customOrder.filterNot { it == id }, assignments = assignments - id)
