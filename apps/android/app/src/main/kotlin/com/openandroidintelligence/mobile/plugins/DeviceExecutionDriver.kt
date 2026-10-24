package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.gateway.device.*
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.CapabilityDenied
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceConfirmation(val requestId: String,val pluginId: String,val capability: String,val parameters: String)

/** 事件仅负责唤醒；身份、租约和参数均来自带认证的请求查询。 */
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
    private val recoveryLock = Mutex()
    val confirmation = MutableStateFlow<DeviceConfirmation?>(null)

    suspend fun accept(requestId: String) {
        require(requestId.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
        if (running[requestId]?.isActive == true) return
        diagnostic(requestId,"lookup","STARTED")
        val response = try {
            withTimeout(LOOKUP_TIMEOUT_MILLIS) {
                http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/device-requests/$requestId"))
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            diagnostic(requestId,"lookup","DEVICE_LOOKUP_TIMEOUT")
            throw IOException("DEVICE_LOOKUP_TIMEOUT",timeout)
        } catch (failure: IOException) {
            diagnostic(requestId,"lookup","DEVICE_LOOKUP_FAILED")
            throw failure
        }
        if (response.status == 403 || response.status == 404 || response.status == 409) {
            documents.delete(requestId)
            diagnostic(requestId,"lookup","REQUEST_FENCED")
            return
        }
        val request = JsonFields.obj(JsonFields.field(response.requireData("DEVICE_LOOKUP_FAILED"),"request")) ?: error("DEVICE_REQUEST_INVALID")
        if (JsonFields.string(request,"deviceId") != deviceId || JsonFields.int(request,"pairingGeneration") != generation) return
        if (JsonFields.int(request,"grantRevision") != currentGrantRevision()) {
            documents.delete(requestId); diagnostic(requestId,"lookup","GRANT_STALE"); return
        }
        if (JsonFields.string(request,"state") !in setOf("pending","claimed","cancel_requested")) {
            documents.delete(requestId); diagnostic(requestId,"lookup","REQUEST_TERMINAL"); return
        }
        if (Instant.parse(JsonFields.string(request,"expiresAt") ?: error("DEVICE_REQUEST_INVALID")).toEpochMilli() <= System.currentTimeMillis()) {
            documents.delete(requestId); diagnostic(requestId,"lookup","REQUEST_EXPIRED"); return
        }
        synchronized(running) {
            if (running[requestId]?.isActive == true) return
            if (documents.read(requestId) == null) documents.write(requestId,Json.canonical(Json.of(mapOf("stage" to "received",
                "expiresAt" to JsonFields.string(request,"expiresAt")))).toByteArray())
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try { execute(requestId,request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { diagnostic(requestId,"journal","EXECUTION_RECOVERY_REQUIRED") }
                finally { running.remove(requestId,currentCoroutineContext()[Job]) }
            }
            running[requestId] = job
            job.start()
        }
    }
    fun decide(requestId: String, approved: Boolean) { confirmations[requestId]?.complete(approved) }
    fun cancel(requestId: String) { confirmations[requestId]?.complete(false); running[requestId]?.cancel() }
    fun cancelAll() { running.values.forEach { it.cancel() }; confirmations.values.forEach { it.complete(false) } }
    suspend fun recoverPending() = recoveryLock.withLock {
        for (id in documents.keys()) {
            try {
                val bytes = documents.read(id) ?: continue
                val doc = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("EXECUTION_JOURNAL_INVALID")
                val expiration = JsonFields.string(doc,"expiresAt")
                if (expiration == null || Instant.parse(expiration).toEpochMilli() <= System.currentTimeMillis()) {
                    documents.delete(id)
                    diagnostic(id,"recovery","REQUEST_EXPIRED")
                } else accept(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { diagnostic(id,"recovery","EXECUTION_RECOVERY_REQUIRED") }
        }
    }
    private suspend fun execute(id: String,request: JsonValue.JObject) {
        var claim: ClaimReceipt? = null
        var invocationStarted = false
        var invocationRisk = JsonFields.string(request,"risk")
        var stage = "claim"
        var result: DeviceRequestResult = DeviceRequestResult.OutcomeUnknown
        var persistedResult: DeviceRequestResult? = null
        try {
            val grantRevision = JsonFields.int(request,"grantRevision") ?: error("DEVICE_REQUEST_INVALID")
            if (grantRevision != currentGrantRevision()) {
                documents.delete(id)
                diagnostic(id,"claim","GRANT_STALE")
                return
            }
            val old = JsonFields.obj(Json.parse(documents.read(id)!!.decodeToString())) ?: error("EXECUTION_JOURNAL_INVALID")
            persistedResult = JsonFields.obj(JsonFields.field(old,"result"))?.let(::decodeResult)
            val remaining = (Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() - System.currentTimeMillis())
                .coerceIn(1,EXECUTION_TIMEOUT_MILLIS)
            withTimeout(remaining) {
                val receipt = claimWithRetry(id,grantRevision)
                check(receipt.accountId == accountId && receipt.deviceId == deviceId &&
                    receipt.pairingGeneration == generation && receipt.grantRevision == grantRevision) { "RECEIPT_BINDING_MISMATCH" }
                claim = receipt
                diagnostic(id,"claim","SUCCEEDED")
                if (persistedResult != null) result = persistedResult!!
                else if (JsonFields.string(old,"stage") == "received" && JsonFields.string(request,"state") == "pending") {
                    // 执行前落盘：重启或重放只能补报未知结果，不能再次调用插件。
                    checkpoint(id,request,"claimed")
                    stage = "authorization"
                    val provider = JsonFields.obj(JsonFields.field(request,"provider")) ?: error("DEVICE_REQUEST_INVALID")
                    val capability = JsonFields.obj(JsonFields.field(request,"capability")) ?: error("DEVICE_REQUEST_INVALID")
                    val pluginId = JsonFields.string(provider,"pluginId") ?: error("DEVICE_REQUEST_INVALID")
                    val key = "${JsonFields.string(capability,"id")}@${JsonFields.string(capability,"version")}"
                    val entry = host.entries(pluginId).firstOrNull {
                        it.key == key && "sha256:${it.identity.authorKeyFingerprint}" == JsonFields.string(provider,"authorKeyId")
                    } ?: throw CapabilityDenied(key)
                    val parameters = JsonFields.field(request,"parameters") ?: error("DEVICE_REQUEST_INVALID")
                    if (entry.risk != "read") invocationRisk = entry.risk
                    val requiresConfirmation = JsonFields.bool(request,"requiresForegroundConfirmation") == true ||
                        JsonFields.string(request,"risk") in setOf("write","high-privilege-ephemeral") ||
                        entry.risk in setOf("write","high-privilege-ephemeral")
                    if (requiresConfirmation && !confirm(id,entry.identity,key,parameters,request)) {
                        result = DeviceRequestResult.Denied(mapOf("code" to "LOCAL_CONFIRMATION_REQUIRED"))
                    } else {
                        currentCoroutineContext().ensureActive()
                        check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() &&
                            grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                        // 确认弹窗可能跨越撤权；即使取消事件丢失，也必须重新检查 Gateway 授权。
                        val current = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/device-requests/$id"))
                        if (current.status in setOf(401,403,404,409)) throw CapabilityDenied("GATEWAY_AUTHORIZATION_REVOKED")
                        val live = JsonFields.obj(JsonFields.field(current.requireData("DEVICE_LOOKUP_FAILED"),"request")) ?: error("DEVICE_REQUEST_INVALID")
                        if (JsonFields.string(live,"state") !in setOf("pending","claimed") ||
                            JsonFields.int(live,"grantRevision") != grantRevision || JsonFields.int(live,"pairingGeneration") != generation ||
                            JsonFields.string(live,"deviceId") != deviceId) throw CapabilityDenied("GATEWAY_AUTHORIZATION_REVOKED")
                        currentCoroutineContext().ensureActive()
                        check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() &&
                            grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                        stage = "invoke"
                        invocationStarted = true
                        diagnostic(id,stage,"STARTED")
                        val output = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,key,parameters,id) }
                        result = DeviceRequestResult.Succeeded(mapOf("result" to Json.parse(output.decodeToString())))
                    }
                }
            }
        } catch (denied: CapabilityDenied) {
            val reason = denied.primitive.takeIf { it.matches(Regex("[A-Z0-9_]{1,128}")) } ?: "LOCAL_CAPABILITY_DENIED"
            result = DeviceRequestResult.Denied(mapOf("code" to "CAPABILITY_DENIED","reason" to reason))
            diagnostic(id,stage,reason)
        } catch (_: TimeoutCancellationException) {
            result = if (invocationStarted && invocationRisk != "read") DeviceRequestResult.OutcomeUnknown
                else DeviceRequestResult.Failed(mapOf("code" to "DEVICE_REQUEST_TIMEOUT"))
            diagnostic(id,stage,"DEVICE_REQUEST_TIMEOUT")
        } catch (_: CancellationException) {
            result = DeviceRequestResult.OutcomeUnknown
            diagnostic(id,stage,"EXECUTION_CANCELLED")
        } catch (_: Exception) {
            val code = when(stage) {
                "claim" -> "DEVICE_CLAIM_FAILED"
                "authorization" -> "DEVICE_AUTHORIZATION_CHECK_FAILED"
                else -> "PLUGIN_EXECUTION_FAILED"
            }
            result = if (invocationStarted && invocationRisk != "read") DeviceRequestResult.OutcomeUnknown
                else DeviceRequestResult.Failed(mapOf("code" to code))
            diagnostic(id,stage,code)
        }
        // 未获得并核对服务器收据时，保留日志等待恢复；不得自行构造收据或上传结果。
        val receipt = claim ?: return
        // 已落盘的真实结果不能因恢复时的网络异常或超时而被改写。
        persistedResult?.let { result = it }
        checkpoint(id,request,"completed",encodeResult(result))
        diagnostic(id,"completed",encodeResult(result)["outcome"].toString())
        withContext(NonCancellable) { deliver(id,receipt,result) }
    }
    private suspend fun claimWithRetry(id: String,grantRevision: Int): ClaimReceipt {
        repeat(MAX_ATTEMPTS) { attempt ->
            try { return client.claim(id,grantRevision) }
            catch (failure: IOException) {
                diagnostic(id,"claim","CLAIM_TRANSPORT_FAILED")
                if (attempt == MAX_ATTEMPTS - 1) throw failure
                delay(RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        error("DEVICE_CLAIM_FAILED")
    }
    private suspend fun deliver(id: String,claim: ClaimReceipt,result: DeviceRequestResult) {
        try {
            withTimeout(DELIVERY_TIMEOUT_MILLIS) {
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        client.submitResult(claim,result)
                        documents.delete(id)
                        diagnostic(id,"result","ACKNOWLEDGED")
                        return@withTimeout
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        diagnostic(id,"result","DEVICE_RESULT_RETRY")
                        if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS * (attempt + 1))
                    }
                }
                diagnostic(id,"result","DELIVERY_RECOVERY_REQUIRED")
            }
        } catch (_: TimeoutCancellationException) {
            diagnostic(id,"result","DELIVERY_TIMEOUT")
        }
    }
    private fun diagnostic(id: String,stage: String,code: String) {
        GatewayLog.d("OaiDeviceRequest","requestId=$id stage=$stage code=$code")
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
    private companion object {
        const val LOOKUP_TIMEOUT_MILLIS = 10_000L
        const val EXECUTION_TIMEOUT_MILLIS = 30_000L
        const val DELIVERY_TIMEOUT_MILLIS = 10_000L
        const val RETRY_DELAY_MILLIS = 500L
        const val MAX_ATTEMPTS = 4
    }
}
