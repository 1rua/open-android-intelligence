package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Publications survive lost acknowledgements, but never outlive the current local authorization. */
class CapabilityPublisher(context: Context,private val http: GatewayHttpClient,scopeKey: String,
    private val initialRevision: Int,private val localRevision: () -> Long,private val host: ProductionPluginHost,
    private val foreignChange: () -> Unit,
    keyProvider: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider? = null,
    private val authorizationCurrent: () -> Boolean = { true },
    private val bindingCurrent: () -> Boolean = { true },
    private val ownerCurrent: () -> Boolean = { true }) {
    // Never replay the positive pairing-grant journal across the authorization-model change.
    private val documents = if (keyProvider == null) EncryptedDocuments(context,"capability-publication:android-v1:$scopeKey")
        else EncryptedDocuments(context,"capability-publication:android-v1:$scopeKey",keyProvider)
    private val lock = Mutex()
    @Volatile var grantRevision = initialRevision; private set
    @Volatile private var pendingDigest: String? = documents.read("pending")?.let { digest(JsonFields.obj(Json.parse(it.decodeToString()))!!) }
    @Volatile private var lastDigest: String? = documents.read("acknowledged")?.let { JsonFields.string(JsonFields.obj(Json.parse(it.decodeToString())),"grantDigest") }
    private val _state = MutableStateFlow(DeviceCapabilitySyncState())
    val state = _state.asStateFlow()

    suspend fun sync() = lock.withLock {
        val local = localRevision()
        val hostRevision = host.revision.value
        val job = currentCoroutineContext()[Job]
        fun requireCurrent() {
            job?.ensureActive()
            check(ownerCurrent()) { "DEVICE_SESSION_CHANGED" }
            check(bindingCurrent()) { "PAIRING_BINDING_CHANGED" }
            check(authorizationCurrent()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
            check(localRevision() == local && host.revision.value == hostRevision) { "LOCAL_AUTHORIZATION_CHANGED" }
        }
        suspend fun pairing(): JsonValue.JObject {
            val value = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/pairings/current",
                beforeSend = ::requireCurrent)).requireData("PAIRING_LOOKUP_FAILED")
            requireCurrent()
            return value
        }
        try {
            requireCurrent()
            val previous = _state.value.forLocalState(local, hostRevision)
            if (previous.status != "SYNCED") {
                _state.value = DeviceCapabilitySyncState(
                    status = if (previous.errorCode == null) "SYNCING" else "SYNC_RETRYING",
                    localRevision = local, hostRevision = hostRevision, errorCode = previous.errorCode,
                )
            }
            val current = pairing()
            val remoteRevision = JsonFields.int(current,"grantRevision") ?: error("PAIRING_INVALID")
            val remoteDigest = JsonFields.string(current,"grantDigest")
            val pending = documents.read("pending")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
            val acknowledged = documents.read("acknowledged")?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
            lastDigest = JsonFields.string(acknowledged,"grantDigest")
            pendingDigest = pending?.let { digest(it) }
            if (remoteRevision > (JsonFields.int(acknowledged,"grantRevision") ?: initialRevision) &&
                remoteDigest !in setOfNotNull(lastDigest,pendingDigest)) {
                recordForeignRevision(remoteRevision,remoteDigest)
                foreignChange()
            }
            grantRevision = remoteRevision
            val snapshot = host.capabilitySnapshot()
            requireCurrent()
            val body = Json.of(mapOf("bindings" to snapshot.bindings,"localGrantRevision" to local,"expectedGrantRevision" to remoteRevision) +
                // An unreadable package cannot prove absence; retain compatible unknown-state diagnostics.
                if ((JsonFields.int(current,"capabilityPublicationVersion") ?: 1) >= 2 && snapshot.inventoryComplete)
                    mapOf("inventory" to snapshot.inventory) else emptyMap()) as JsonValue.JObject
            val expectedDigest = digest(body)
            pendingDigest = expectedDigest
            val confirmed = if (remoteDigest == expectedDigest) {
                current
            } else {
                _state.value = DeviceCapabilitySyncState("SYNCING",local,hostRevision,errorCode = previous.errorCode)
                documents.write("pending",Json.canonical(body).toByteArray())
                val response = http.execute(SignedGatewayRequest("POST","/open-android-intelligence/v2/pairings/current/capabilities",
                    body = Json.canonical(body).toByteArray(),headers = listOf(RawHeader("Content-Type","application/json")),
                    requestId = "bindings_" + digestName(Json.canonical(body)),beforeSend = ::requireCurrent))
                    .requireData("CAPABILITY_BINDING_FAILED")
                requireCurrent()
                check(JsonFields.string(response,"grantDigest") == expectedDigest) { "CAPABILITY_DIGEST_MISMATCH" }
                val acceptedRevision = JsonFields.int(response,"grantRevision") ?: error("CAPABILITY_BINDING_INVALID")
                check(acceptedRevision >= remoteRevision) { "CAPABILITY_BINDING_INVALID" }
                grantRevision = acceptedRevision
                // Preserve the delivery receipt; a failed readback is recovered by GET, never duplicate POST.
                lastDigest = expectedDigest
                documents.write("acknowledged",Json.canonical(response).toByteArray())
                documents.delete("pending")
                pairing().also {
                    check(JsonFields.int(it,"grantRevision") == acceptedRevision &&
                        JsonFields.string(it,"grantDigest") == expectedDigest) { "CAPABILITY_PUBLICATION_STALE" }
                }
            }
            requireCurrent()
            val confirmedRevision = JsonFields.int(confirmed,"grantRevision") ?: error("PAIRING_INVALID")
            if (remoteDigest == expectedDigest) {
                if (JsonFields.int(acknowledged,"grantRevision") != confirmedRevision ||
                    JsonFields.string(acknowledged,"grantDigest") != expectedDigest) {
                    documents.write("acknowledged",Json.canonical(Json.of(mapOf(
                        "grantRevision" to confirmedRevision,"grantDigest" to expectedDigest))).toByteArray())
                }
                if (pending != null) documents.delete("pending")
            }
            lastDigest = expectedDigest
            grantRevision = confirmedRevision
            requireCurrent()
            _state.value = DeviceCapabilitySyncState("SYNCED",local,hostRevision,confirmedRevision,expectedDigest,
                JsonFields.objects(confirmed,"capabilities"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            if (ownerCurrent()) {
                val code = capabilitySyncErrorCode(cause)
                _state.value = DeviceCapabilitySyncState(
                    status = if (code == "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED") "AUTHORIZATION_MODEL_UNSUPPORTED" else "SYNC_FAILED",
                    localRevision = local, hostRevision = hostRevision, errorCode = code,
                )
            }
            throw cause
        }
    }

    suspend fun observeChange(revision: Int,digest: String?): Boolean = lock.withLock {
        if (revision <= grantRevision) return@withLock true
        if (digest != null && digest in setOfNotNull(pendingDigest,lastDigest)) {
            grantRevision = revision
            _state.value = DeviceCapabilitySyncState(grantRevision = revision, grantDigest = digest)
            return@withLock true
        }
        grantRevision = revision
        recordForeignRevision(revision,digest)
        false
    }
    private fun recordForeignRevision(revision: Int,digest: String?) {
        documents.delete("pending"); pendingDigest = null; lastDigest = digest
        documents.write("acknowledged",Json.canonical(Json.of(mapOf("grantRevision" to revision,"grantDigest" to digest))).toByteArray())
        _state.value = DeviceCapabilitySyncState(grantRevision = revision, grantDigest = digest)
    }
    private fun digest(body: JsonValue.JObject): String = Json.sha256(Json.of(mapOf("bindings" to JsonFields.field(body,"bindings"),
        "localGrantRevision" to JsonFields.field(body,"localGrantRevision")) +
        if (JsonFields.field(body,"inventory") != null) mapOf("inventory" to JsonFields.field(body,"inventory")) else emptyMap()))
}
