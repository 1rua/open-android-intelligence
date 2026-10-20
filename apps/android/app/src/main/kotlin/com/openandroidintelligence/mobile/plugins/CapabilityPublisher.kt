package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Publications survive lost acknowledgements, but never outlive the current local authorization. */
class CapabilityPublisher(context: Context,private val http: GatewayHttpClient,scopeKey: String,
    private val initialRevision: Int,private val localRevision: () -> Long,private val host: ProductionPluginHost,
    private val foreignChange: () -> Unit,
    keyProvider: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider? = null) {
    private val documents = if (keyProvider == null) EncryptedDocuments(context,"capability-publication:$scopeKey")
        else EncryptedDocuments(context,"capability-publication:$scopeKey",keyProvider)
    private val lock = Mutex()
    @Volatile var grantRevision = initialRevision; private set
    @Volatile private var pendingDigest: String? = documents.read("pending")?.let { digest(JsonFields.obj(Json.parse(it.decodeToString()))!!) }
    @Volatile private var lastDigest: String? = documents.read("acknowledged")?.let { JsonFields.string(JsonFields.obj(Json.parse(it.decodeToString())),"grantDigest") }
    val gatewayPolicy = kotlinx.coroutines.flow.MutableStateFlow<JsonValue.JObject?>(null)
    val gatewayCapabilities = kotlinx.coroutines.flow.MutableStateFlow<List<JsonValue.JObject>>(emptyList())
    suspend fun sync() = lock.withLock {
        val current = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/pairings/current")).requireData("PAIRING_LOOKUP_FAILED")
        val remoteRevision = JsonFields.int(current,"grantRevision") ?: error("PAIRING_INVALID")
        val remoteDigest = JsonFields.string(current,"grantDigest")
        gatewayPolicy.value = JsonFields.obj(JsonFields.field(current,"capabilityPolicy"))
        gatewayCapabilities.value = JsonFields.objects(current,"capabilities")
        val pending = documents.read("pending")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        val acknowledged = documents.read("acknowledged")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
        lastDigest = JsonFields.string(acknowledged,"grantDigest")
        pendingDigest = pending?.let { digest(it) }
        if (remoteRevision > (JsonFields.int(acknowledged,"grantRevision") ?: initialRevision) && remoteDigest !in setOfNotNull(lastDigest,pendingDigest)) {
            recordForeignRevision(remoteRevision,remoteDigest)
            foreignChange()
        }
        grantRevision = remoteRevision
        val local = localRevision()
        val hostRevision = host.revision.value
        val snapshot = host.capabilitySnapshot()
        check(localRevision() == local && host.revision.value == hostRevision) { "LOCAL_AUTHORIZATION_CHANGED" }
        val body = Json.of(mapOf("bindings" to snapshot.bindings,"localGrantRevision" to local,"expectedGrantRevision" to remoteRevision) +
            if ((JsonFields.int(current,"capabilityPublicationVersion") ?: 1) >= 2) mapOf("inventory" to snapshot.inventory) else emptyMap()) as JsonValue.JObject
        pendingDigest = digest(body)
        // GET is authoritative after a lost POST response. Reposting the old
        // expectedGrantRevision can loop forever, or republish a revoked grant.
        if (remoteDigest == pendingDigest) {
            lastDigest = pendingDigest
            if (JsonFields.int(acknowledged,"grantRevision") != remoteRevision || JsonFields.string(acknowledged,"grantDigest") != remoteDigest) {
                documents.write("acknowledged",Json.canonical(Json.of(mapOf("grantRevision" to remoteRevision,"grantDigest" to remoteDigest))).toByteArray())
            }
            if (pending != null) documents.delete("pending")
            return@withLock
        }
        documents.write("pending",Json.canonical(body).toByteArray())
        val id = "bindings_" + digestName(Json.canonical(body))
        val response = http.execute(SignedGatewayRequest("POST","/open-android-intelligence/v2/pairings/current/capabilities",
            body = Json.canonical(body).toByteArray(),headers = listOf(RawHeader("Content-Type","application/json")),requestId = id)).requireData("CAPABILITY_BINDING_FAILED")
        check(JsonFields.string(response,"grantDigest") == pendingDigest) { "CAPABILITY_DIGEST_MISMATCH" }
        grantRevision = JsonFields.int(response,"grantRevision") ?: error("CAPABILITY_BINDING_INVALID")
        lastDigest = pendingDigest
        documents.write("acknowledged",Json.canonical(response).toByteArray()); documents.delete("pending")
    }
    suspend fun observeChange(revision: Int,digest: String?): Boolean = lock.withLock {
        if (revision <= grantRevision) return@withLock true
        if (digest != null && digest in setOfNotNull(pendingDigest,lastDigest)) { grantRevision = revision; return@withLock true }
        grantRevision = revision
        recordForeignRevision(revision,digest)
        false
    }
    private fun recordForeignRevision(revision: Int,digest: String?) {
        documents.delete("pending"); pendingDigest = null; lastDigest = digest
        documents.write("acknowledged",Json.canonical(Json.of(mapOf("grantRevision" to revision,"grantDigest" to digest))).toByteArray())
    }
    private fun digest(body: JsonValue.JObject): String = Json.sha256(Json.of(mapOf("bindings" to JsonFields.field(body,"bindings"),
        "localGrantRevision" to JsonFields.field(body,"localGrantRevision")) +
        if (JsonFields.field(body,"inventory") != null) mapOf("inventory" to JsonFields.field(body,"inventory")) else emptyMap()))
}
