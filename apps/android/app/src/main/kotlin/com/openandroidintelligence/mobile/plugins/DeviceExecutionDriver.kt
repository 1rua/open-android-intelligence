package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.gateway.device.*
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.CapabilityDenied
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceConfirmation(val requestId: String,val pluginId: String,val capability: String,val parameters: String)

/** The event is only a wakeup. Identity, lease and parameters come from an authenticated request lookup. */
class DeviceExecutionDriver(context: Context,private val scope: CoroutineScope,private val http: GatewayHttpClient,
    private val host: ProductionPluginHost,private val accountId: String,private val deviceId: String,
    private val generation: Int,scopeKey: String,private val currentGrantRevision: () -> Int,
    private val isForeground: () -> Boolean,
    keyProvider: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider? = null) {
    private val documents = if (keyProvider == null) EncryptedDocuments(context,"device-execution:$scopeKey")
        else EncryptedDocuments(context,"device-execution:$scopeKey",keyProvider)
    private val client = DeviceRequestClient(HttpDeviceRequestTransport(http))
    private val running = ConcurrentHashMap<String,Job>()
    private val confirmations = ConcurrentHashMap<String,CompletableDeferred<Boolean>>()
    private val confirmationLock = Mutex()
    val confirmation = MutableStateFlow<DeviceConfirmation?>(null)

    suspend fun accept(requestId: String) {
        require(requestId.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
        val response = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/device-requests/$requestId"))
        if (response.status == 403 || response.status == 404 || response.status == 409) { documents.delete(requestId); return }
        val request = JsonFields.obj(JsonFields.field(response.requireData("DEVICE_LOOKUP_FAILED"),"request")) ?: error("DEVICE_REQUEST_INVALID")
        if (JsonFields.string(request,"deviceId") != deviceId || JsonFields.int(request,"pairingGeneration") != generation) return
        if (JsonFields.int(request,"grantRevision") != currentGrantRevision()) { documents.delete(requestId); return }
        if (JsonFields.string(request,"state") !in setOf("pending","claimed","cancel_requested")) { documents.delete(requestId); return }
        if (Instant.parse(JsonFields.string(request,"expiresAt") ?: error("DEVICE_REQUEST_INVALID")).toEpochMilli() <= System.currentTimeMillis()) {
            documents.delete(requestId); return
        }
        if (documents.read(requestId) == null) documents.write(requestId,Json.canonical(Json.of(mapOf("stage" to "received",
            "expiresAt" to JsonFields.string(request,"expiresAt")))).toByteArray())
        synchronized(running) {
            if (running[requestId]?.isActive == true) return
            running[requestId] = scope.launch(Dispatchers.IO) { try { execute(requestId,request) } finally { running.remove(requestId) } }
        }
    }
    fun decide(requestId: String, approved: Boolean) { confirmations[requestId]?.complete(approved) }
    fun cancel(requestId: String) { confirmations[requestId]?.complete(false); running[requestId]?.cancel() }
    fun cancelAll() { running.values.forEach { it.cancel() }; confirmations.values.forEach { it.complete(false) } }
    suspend fun recoverPending() {
        for (id in documents.keys()) {
            val doc = JsonFields.obj(Json.parse(documents.read(id)!!.decodeToString())) ?: error("EXECUTION_JOURNAL_INVALID")
            val expiration = JsonFields.string(doc,"expiresAt")
            if (expiration == null || Instant.parse(expiration).toEpochMilli() <= System.currentTimeMillis()) documents.delete(id)
            else accept(id)
        }
    }
    private suspend fun execute(id: String,request: JsonValue.JObject) {
        val grantRevision = JsonFields.int(request,"grantRevision") ?: error("DEVICE_REQUEST_INVALID")
        if (grantRevision != currentGrantRevision()) { documents.delete(id); return } // a fenced request must never execute under another revision
        val old = JsonFields.obj(Json.parse(documents.read(id)!!.decodeToString())) ?: error("EXECUTION_JOURNAL_INVALID")
        val claim = client.claim(id,grantRevision)
        check(claim.accountId == accountId && claim.deviceId == deviceId && claim.pairingGeneration == generation && claim.grantRevision == grantRevision) { "RECEIPT_BINDING_MISMATCH" }
        var invocationStarted=false
        var invocationRisk=JsonFields.string(request,"risk")
        var result: DeviceRequestResult = DeviceRequestResult.OutcomeUnknown
        try {
            val saved = JsonFields.obj(JsonFields.field(old,"result"))
            if (saved != null) result = decodeResult(saved)
            else if (JsonFields.string(old,"stage") == "received" && JsonFields.string(request,"state") == "pending") {
                checkpoint(id,request,"claimed")
                val provider = JsonFields.obj(JsonFields.field(request,"provider")) ?: error("DEVICE_REQUEST_INVALID")
                val capability = JsonFields.obj(JsonFields.field(request,"capability")) ?: error("DEVICE_REQUEST_INVALID")
                val pluginId = JsonFields.string(provider,"pluginId") ?: error("DEVICE_REQUEST_INVALID")
                val key = "${JsonFields.string(capability,"id")}@${JsonFields.string(capability,"version")}"
                val entry = host.entries(pluginId).firstOrNull { it.key == key && "sha256:${it.identity.authorKeyFingerprint}" == JsonFields.string(provider,"authorKeyId") }
                    ?: throw CapabilityDenied(key)
                val parameters = JsonFields.field(request,"parameters") ?: error("DEVICE_REQUEST_INVALID")
                if (entry.risk != "read") invocationRisk=entry.risk
                val requiresConfirmation = JsonFields.bool(request,"requiresForegroundConfirmation") == true ||
                    JsonFields.string(request,"risk") in setOf("write","high-privilege-ephemeral") || entry.risk in setOf("write","high-privilege-ephemeral")
                if (requiresConfirmation && !confirm(id,entry.identity,key,parameters,request)) result = DeviceRequestResult.Denied(mapOf("code" to "LOCAL_CONFIRMATION_REQUIRED"))
                else {
                    currentCoroutineContext().ensureActive()
                    check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() && grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                    invocationStarted=true
                    val output = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,key,parameters,id) }
                    result = DeviceRequestResult.Succeeded(mapOf("result" to Json.parse(output.decodeToString())))
                }
            }
        } catch (denied: CapabilityDenied) { result = DeviceRequestResult.Denied(mapOf("code" to "CAPABILITY_DENIED")) }
        catch (cancelled: CancellationException) { result = DeviceRequestResult.OutcomeUnknown }
        catch (_: Exception) { result = if (invocationStarted && invocationRisk != "read") DeviceRequestResult.OutcomeUnknown else DeviceRequestResult.Failed(mapOf("code" to "PLUGIN_EXECUTION_FAILED")) }
        // Persist the outcome before attempting delivery. A lost HTTP reply cannot repeat execution.
        checkpoint(id,request,"completed",encodeResult(result))
        withContext(NonCancellable) {
            repeat(4) { attempt ->
                try { client.submitResult(claim,result); documents.delete(id); return@withContext }
                catch (_: Exception) { if (attempt < 3) delay(500L * (attempt+1)) }
            }
        }
    }
    private suspend fun confirm(id: String,identity: PluginIdentity,key: String,parameters: JsonValue,request: JsonValue.JObject): Boolean = confirmationLock.withLock {
        if (!isForeground()) return@withLock false
        val wait = CompletableDeferred<Boolean>(); confirmations[id] = wait
        confirmation.value = DeviceConfirmation(id,identity.pluginId,key,Json.canonical(parameters).take(4096))
        try {
            val remaining = (Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() - System.currentTimeMillis()).coerceIn(1,30_000)
            withTimeoutOrNull(remaining) { wait.await() } == true && isForeground()
        } finally { confirmations.remove(id); confirmation.value = null }
    }
    private fun checkpoint(id: String,request: JsonValue.JObject,stage: String,result: Map<String,Any?>? = null) {
        documents.write(id,Json.canonical(Json.of(mapOf("stage" to stage,"expiresAt" to JsonFields.string(request,"expiresAt"),"result" to result))).toByteArray())
    }
    private fun encodeResult(result: DeviceRequestResult): Map<String,Any?> = when(result) {
        is DeviceRequestResult.Succeeded -> mapOf("outcome" to "succeeded","data" to result.payload)
        is DeviceRequestResult.Failed -> mapOf("outcome" to "failed","data" to result.payload)
        is DeviceRequestResult.Denied -> mapOf("outcome" to "denied","data" to result.payload)
        is DeviceRequestResult.Cancelled -> mapOf("outcome" to "cancelled","data" to result.payload)
        DeviceRequestResult.OutcomeUnknown -> mapOf("outcome" to "outcome_unknown")
    }
    private fun decodeResult(result: JsonValue.JObject): DeviceRequestResult {
        val data = JsonFields.obj(JsonFields.field(result,"data"))?.fields.orEmpty().associate { it.first to it.second }
        return when(JsonFields.string(result,"outcome")) {
            "succeeded" -> DeviceRequestResult.Succeeded(data); "failed" -> DeviceRequestResult.Failed(data)
            "denied" -> DeviceRequestResult.Denied(data); "cancelled" -> DeviceRequestResult.Cancelled(data)
            else -> DeviceRequestResult.OutcomeUnknown
        }
    }
}
