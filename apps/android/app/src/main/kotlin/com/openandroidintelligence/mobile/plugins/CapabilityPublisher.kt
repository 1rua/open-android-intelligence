package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Frozen publications survive a lost response, including a process restart. */
class CapabilityPublisher(context: Context,private val http: GatewayHttpClient,scopeKey: String,
    private val initialRevision: Int,private val localRevision: () -> Long,private val host: ProductionPluginHost,
    private val foreignChange: () -> Unit) {
    private val documents = EncryptedDocuments(context,"capability-publication:$scopeKey")
    private val lock = Mutex()
    @Volatile var grantRevision = initialRevision; private set
    @Volatile private var pendingDigest: String? = null
    @Volatile private var lastDigest: String? = null
    suspend fun sync() = lock.withLock {
        val current = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/pairings/current")).requireData("PAIRING_LOOKUP_FAILED")
        val remoteRevision = JsonFields.int(current,"grantRevision") ?: error("PAIRING_INVALID")
        val remoteDigest = JsonFields.string(current,"grantDigest")
        val pending = documents.read("pending")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        val acknowledged = documents.read("acknowledged")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        lastDigest = JsonFields.string(acknowledged,"grantDigest")
        pendingDigest = pending?.let { digest(it) }
        if (remoteRevision > (JsonFields.int(acknowledged,"grantRevision") ?: initialRevision) && remoteDigest !in setOfNotNull(lastDigest,pendingDigest)) {
            documents.delete("pending"); foreignChange()
        }
        grantRevision = remoteRevision
        val body = pending?.takeIf { digest(it) == remoteDigest || JsonFields.int(it,"expectedGrantRevision") == remoteRevision }
            ?: Json.of(mapOf("bindings" to host.authorizedBindings(),"localGrantRevision" to localRevision(),"expectedGrantRevision" to remoteRevision)) as JsonValue.JObject
        pendingDigest = digest(body)
        if (lastDigest == pendingDigest && remoteDigest == pendingDigest) { documents.delete("pending"); return@withLock }
        documents.write("pending",Json.canonical(body).toByteArray())
        val id = "bindings_" + digestName(Json.canonical(body))
        val response = http.execute(SignedGatewayRequest("POST","/open-android-intelligence/v2/pairings/current/capabilities",
            body = Json.canonical(body).toByteArray(),headers = listOf(RawHeader("Content-Type","application/json")),requestId = id)).requireData("CAPABILITY_BINDING_FAILED")
        check(JsonFields.string(response,"grantDigest") == pendingDigest) { "CAPABILITY_DIGEST_MISMATCH" }
        grantRevision = JsonFields.int(response,"grantRevision") ?: error("CAPABILITY_BINDING_INVALID")
        lastDigest = pendingDigest
        documents.write("acknowledged",Json.canonical(response).toByteArray()); documents.delete("pending")
    }
    fun observeChange(revision: Int,digest: String?): Boolean {
        if (revision <= grantRevision) return true
        if (digest != null && digest in setOfNotNull(pendingDigest,lastDigest)) { grantRevision = revision; return true }
        grantRevision = revision; return false
    }
    private fun digest(body: JsonValue.JObject): String = Json.sha256(Json.of(mapOf("bindings" to JsonFields.field(body,"bindings"),
        "localGrantRevision" to JsonFields.field(body,"localGrantRevision"))))
}
