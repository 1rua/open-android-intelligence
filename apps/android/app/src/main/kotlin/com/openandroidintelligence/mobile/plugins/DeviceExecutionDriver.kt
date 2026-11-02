package com.openandroidintelligence.mobile.plugins

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import com.openandroidintelligence.kernel.CapabilityAuthorization
import com.openandroidintelligence.kernel.CapabilityRequestScope
import com.openandroidintelligence.kernel.DeviceOperation
import com.openandroidintelligence.kernel.LocalCapabilityApproval
import com.openandroidintelligence.gateway.device.*
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.CapabilityDenied
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceConfirmation(val requestId: String,val pluginId: String,val capability: String,val parameters: String,
    val agentName: String = "", val purpose: String = "", val operation: String = "", val expiresAtMillis: Long = 0L,
    val decisionToken: String = "")

/** 事件仅负责唤醒；身份、租约和参数均来自带认证的请求查询。 */
class DeviceExecutionDriver(private val context: Context,private val scope: CoroutineScope,private val http: GatewayHttpClient,
    private val host: ProductionPluginHost,private val accountId: String,private val deviceId: String,
    private val generation: Int,scopeKey: String,private val currentGrantRevision: () -> Int,
    private val isForeground: () -> Boolean,
    keyProvider: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider? = null,
    private val authorizationCurrent: () -> Boolean = { true },
    private val localAuthorizationRevision: () -> Long = { 0L },
    private val authenticatedBinding: com.openandroidintelligence.kernel.PairingGrantBinding? = null,
    private val screenBinding: com.openandroidintelligence.control.ScreenAuthorizationBinding? = null) {
    private val decisionOwner = java.util.UUID.randomUUID().toString()
    private val documents = if (keyProvider == null) EncryptedDocuments(context,"device-execution:$scopeKey")
        else EncryptedDocuments(context,"device-execution:$scopeKey",keyProvider)
    private val client = DeviceRequestClient(object : DeviceRequestTransport {
        override suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt =
            HttpDeviceRequestTransport(http) { id -> checkTransportAuthorization(id) }.claim(requestId, grantRevision)

        override suspend fun submitResult(requestId: String, body: Map<String, Any?>) {
            HttpDeviceRequestTransport(http) { id -> checkTransportAuthorization(id, body["result"]) }
                .submitResult(requestId, body)
        }
    })
    private val stateLock = Any()
    private val lookups = ConcurrentHashMap<String,Job>()
    private val running = ConcurrentHashMap<String,Job>()
    private val confirmations = ConcurrentHashMap<String,CompletableDeferred<Int>>()
    private val confirmationLock = Mutex()
    private val recoveryLock = Mutex()
    val confirmation = MutableStateFlow<DeviceConfirmation?>(null)

    /** A durable wake-up signal, never a Claim or permission to invoke a plugin. */
    suspend fun enqueue(requestId: String) = withContext(Dispatchers.IO) {
        require(requestId.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
        synchronized(stateLock) {
            if (readJournal(requestId) == null) writeJournal(requestId, notification())
            scheduleLookup(requestId)
        }
    }

    /** Compatibility entry point; platform events and snapshots must use enqueue. */
    suspend fun accept(requestId: String) {
        enqueue(requestId)
        synchronized(stateLock) { lookups[requestId] }?.join()
    }

    private fun checkTransportAuthorization(id: String, expectedResult: Any? = null) = synchronized(stateLock) {
        check(authorizationCurrent()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
        val saved = readJournal(id)
        check(saved != null && journalDeliveryCurrent(saved)) { "LOCAL_AUTHORIZATION_CHANGED" }
        check(JsonFields.string(saved, "expiresAt")?.let(Instant::parse)?.toEpochMilli()?.let { it > System.currentTimeMillis() } == true) {
            "DEVICE_REQUEST_EXPIRED"
        }
        if (expectedResult != null) {
            val persisted = JsonFields.field(saved, "result") ?: error("EXECUTION_RESULT_MISSING")
            check(Json.canonical(persisted) == Json.canonical(Json.of(expectedResult))) { "EXECUTION_RESULT_CHANGED" }
        }
    }

    private fun notification(): Map<String, Any?> = mapOf("stage" to "notified",
        "localAuthorizationRevision" to localAuthorizationRevision(),
        "hostAuthorizationDigest" to executionHostAuthorizationDigest(host))

    private fun readJournal(id: String): JsonValue.JObject? = documents.read(id)?.let {
        JsonFields.obj(Json.parse(it.decodeToString())) ?: error("EXECUTION_JOURNAL_INVALID")
    }

    private fun writeJournal(id: String, fields: Map<String, Any?>) =
        documents.write(id, Json.canonical(Json.of(fields)).toByteArray())

    private fun journalFields(document: JsonValue.JObject): MutableMap<String, Any?> =
        document.fields.associateTo(mutableMapOf<String, Any?>()) { it.first to it.second }

    /** Called under stateLock; the entry remains owned until the Job has fully completed. */
    private fun launchTracked(jobs: ConcurrentHashMap<String, Job>, id: String, stage: String,
        action: suspend () -> Unit) {
        if (jobs[id]?.isCompleted == false) return
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                diagnostic(id, stage, "EXECUTION_RECOVERY_REQUIRED_" + failure.javaClass.simpleName.take(64))
            }
        }
        jobs[id] = job
        // A cancelled Job can still be persisting/delivering its final outcome. Remove only
        // after that finalization, comparing the owner so an old Job cannot remove a new one.
        job.invokeOnCompletion { synchronized(stateLock) { jobs.remove(id, job) } }
        job.start()
    }

    private fun scheduleLookup(id: String) {
        val saved = readJournal(id) ?: return
        if (JsonFields.string(saved, "stage") == "fenced" || !authorizationCurrent()) return
        val executing = running[id]
        if (executing != null && executing.isCancelled && !executing.isCompleted) return
        launchTracked(lookups, id, "lookup") { lookup(id) }
    }

    private suspend fun lookup(requestId: String) {
        if (!authorizationCurrent()) return
        diagnostic(requestId,"lookup","STARTED")
        val response = try {
            withTimeout(LOOKUP_TIMEOUT_MILLIS) {
                http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/device-requests/$requestId",
                    beforeSend = { check(authorizationCurrent()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" } }))
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            diagnostic(requestId,"lookup","DEVICE_LOOKUP_TIMEOUT")
            throw IOException("DEVICE_LOOKUP_TIMEOUT",timeout)
        } catch (failure: IOException) {
            diagnostic(requestId,"lookup","DEVICE_LOOKUP_FAILED")
            throw failure
        }
        currentCoroutineContext().ensureActive()
        if (response.status in setOf(403,404,409)) {
            fence(requestId, "REQUEST_FENCED")
            return
        }
        val request = JsonFields.obj(JsonFields.field(response.requireData("DEVICE_LOOKUP_FAILED"),"request")) ?: error("DEVICE_REQUEST_INVALID")
        if (JsonFields.string(request,"deviceId") != deviceId || JsonFields.int(request,"pairingGeneration") != generation) {
            fence(requestId, "REQUEST_BINDING_CHANGED")
            return
        }
        if (JsonFields.int(request,"grantRevision") != currentGrantRevision()) {
            fence(requestId, "GRANT_STALE")
            return
        }
        val remoteState = JsonFields.string(request,"state")
        if (remoteState !in setOf("pending","claimed","cancel_requested")) {
            fence(requestId, "REQUEST_TERMINAL")
            return
        }
        if (Instant.parse(JsonFields.string(request,"expiresAt") ?: error("DEVICE_REQUEST_INVALID")).toEpochMilli() <= System.currentTimeMillis()) {
            fence(requestId, "REQUEST_EXPIRED")
            return
        }
        if (remoteState == "cancel_requested") recordCancellation(requestId, cancelLookup = false)
        synchronized(stateLock) {
            val saved = readJournal(requestId) ?: return
            if (JsonFields.string(saved, "stage") == "fenced") return
            if (JsonFields.string(saved, "stage") == "notified") {
                writeJournal(requestId, journalFields(saved).apply {
                    this["stage"] = "received"
                    this["expiresAt"] = JsonFields.string(request, "expiresAt")
                })
            }
            launchTracked(running, requestId, "journal") { execute(requestId,request) }
        }
    }
    fun respond(requestId: String, allowed: Boolean, durationMinutes: Int = 0) {
        require(durationMinutes in setOf(0, 5, 30, 60))
        confirmations[requestId]?.complete(if (allowed) durationMinutes else -1)
    }
    fun decide(requestId: String, approved: Boolean, durationMinutes: Int = 0) = respond(requestId, approved, durationMinutes)
    fun cancel(requestId: String) = recordCancellation(requestId)

    private fun signalCancellation(requestId: String, cancelLookup: Boolean) {
        if (screenBinding != null) ScreenHostRegistry.current?.cancelRequest(requestId, screenBinding)
        host.cancelRequestAuthorization(requestId, authenticatedBinding)
        confirmations[requestId]?.complete(-1)
        running[requestId]?.cancel()
        if (cancelLookup) lookups[requestId]?.cancel()
    }

    private fun recordCancellation(requestId: String, cancelLookup: Boolean = true) {
        require(requestId.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
        try {
            synchronized(stateLock) {
                val saved = readJournal(requestId)
                val fields = saved?.let(::journalFields) ?: notification().toMutableMap()
                fields["cancelRequested"] = true
                val result = JsonFields.obj(JsonFields.field(saved, "result"))
                if (JsonFields.string(result, "outcome") == "succeeded")
                    fields["result"] = encodeResult(cancelledResult(saved))
                writeJournal(requestId, fields)
            }
        } finally { signalCancellation(requestId, cancelLookup) }
    }

    private fun fence(requestId: String, reason: String) {
        try {
            synchronized(stateLock) {
                val saved = readJournal(requestId)
                val fields = saved?.let(::journalFields) ?: notification().toMutableMap()
                fields["stage"] = "fenced"
                fields["cancelRequested"] = true
                fields["fenceReason"] = reason
                writeJournal(requestId, fields)
            }
            diagnostic(requestId,"lookup",reason)
        } finally { signalCancellation(requestId, cancelLookup = true) }
    }

    fun cancelAll() {
        // Closing a connection cancels its in-flight work. A completed delivery
        // window has no live owner: preserve that result for authenticated
        // recovery rather than rewriting execution history as cancellation.
        val ids = synchronized(stateLock) {
            running.filterValues { !it.isCompleted }.keys + lookups.filterValues { !it.isCompleted }.keys + confirmations.keys
        }
        ids.forEach(::cancel)
    }

    suspend fun recoverPending() = recoveryLock.withLock {
        withContext(Dispatchers.IO) {
            for (id in documents.keys()) {
                try {
                    synchronized(stateLock) {
                        val doc = readJournal(id) ?: return@synchronized
                        if (JsonFields.string(doc,"stage") == "fenced") return@synchronized
                        val expiration = JsonFields.string(doc,"expiresAt")
                        if (expiration != null && Instant.parse(expiration).toEpochMilli() <= System.currentTimeMillis()) {
                            fence(id,"REQUEST_EXPIRED")
                        } else scheduleLookup(id)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { diagnostic(id,"recovery","EXECUTION_RECOVERY_REQUIRED") }
            }
        }
    }
    private suspend fun execute(id: String,request: JsonValue.JObject) {
        var claim: ClaimReceipt? = null
        var invocationStarted = false
        var invocationRisk = JsonFields.string(request,"risk")
        var screenInvocation = false
        var stage = "claim"
        var result: DeviceRequestResult = DeviceRequestResult.OutcomeUnknown
        var persistedResult: DeviceRequestResult? = null
        try {
            if (!authorizationCurrent()) return
            val grantRevision = JsonFields.int(request,"grantRevision") ?: error("DEVICE_REQUEST_INVALID")
            if (grantRevision != currentGrantRevision()) {
                fence(id, "GRANT_STALE")
                return
            }
            var old = synchronized(stateLock) { readJournal(id) } ?: return
            if (JsonFields.string(old, "stage") !in setOf("received", "claimed", "completed")) return
            if (JsonFields.bool(old, "cancelRequested") == true && JsonFields.obj(JsonFields.field(old, "result")) == null) {
                val cancellation = if (JsonFields.string(old, "stage") == "received" || invocationRisk == "read")
                    DeviceRequestResult.Cancelled(mapOf("code" to "DEVICE_REQUEST_CANCELLED")) else DeviceRequestResult.OutcomeUnknown
                if (!checkpoint(id, request, "completed", encodeResult(cancellation))) return
                old = synchronized(stateLock) { readJournal(id) } ?: return
            }
            if (!journalDeliveryCurrent(old)) {
                fence(id, "LOCAL_AUTHORIZATION_CHANGED")
                return
            }
            persistedResult = JsonFields.obj(JsonFields.field(old,"result"))?.let(::decodeResult)
            val remaining = (Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() - System.currentTimeMillis())
                .coerceIn(1,EXECUTION_TIMEOUT_MILLIS)
            withTimeout(remaining) {
                val receipt = claimWithRetry(id,grantRevision)
                check(receipt.requestId == id && receipt.accountId == accountId && receipt.deviceId == deviceId &&
                    receipt.pairingGeneration == generation && receipt.grantRevision == grantRevision) { "RECEIPT_BINDING_MISMATCH" }
                claim = receipt
                diagnostic(id,"claim","SUCCEEDED")
                if (persistedResult != null) result = persistedResult!!
                else if (JsonFields.string(old,"stage") == "received" && JsonFields.bool(old, "cancelRequested") != true &&
                    JsonFields.string(request,"state") == "pending") {
                    // 执行前落盘：重启或重放只能补报未知结果，不能再次调用插件。
                    if (!checkpoint(id,request,"claimed")) return@withTimeout
                    stage = "authorization"
                    val provider = JsonFields.obj(JsonFields.field(request,"provider")) ?: error("DEVICE_REQUEST_INVALID")
                    val capability = JsonFields.obj(JsonFields.field(request,"capability")) ?: error("DEVICE_REQUEST_INVALID")
                    val pluginId = JsonFields.string(provider,"pluginId") ?: error("DEVICE_REQUEST_INVALID")
                    val key = "${JsonFields.string(capability,"id")}@${JsonFields.string(capability,"version")}"
                    val entry = host.entries(pluginId).firstOrNull {
                        it.key == key && "sha256:${it.identity.authorKeyFingerprint}" == JsonFields.string(provider,"authorKeyId")
                    } ?: throw CapabilityDenied(key)
                    val parameters = JsonFields.field(request,"parameters") ?: error("DEVICE_REQUEST_INVALID")
                    screenInvocation = entry.primitives.any { it.startsWith("kernel.screen.") }
                    if (entry.risk != "read") invocationRisk = entry.risk
                    val caller = JsonFields.obj(JsonFields.field(request, "caller"))
                        ?: throw CapabilityDenied("CALLER_DECLARATION_REQUIRED")
                    val agentName = JsonFields.string(caller, "agentName").orEmpty()
                    val purpose = JsonFields.string(caller, "purpose").orEmpty()
                    if (agentName.isBlank() || purpose.isBlank()) throw CapabilityDenied("CALLER_DECLARATION_REQUIRED")
                    var approval: LocalCapabilityApproval? = null
                    when (val decision = host.requestAuthorization(id, entry, parameters, authenticatedBinding)) {
                        is CapabilityAuthorization.Denied -> throw CapabilityDenied(decision.code)
                        is CapabilityAuthorization.Allowed -> approval = decision.approval
                        CapabilityAuthorization.AwaitingConfirmation -> {
                            val duration = confirm(id, entry.identity, key, parameters, request, agentName, purpose,
                                host.authorizationScope(entry, parameters).operation.name)
                            if (duration < 0 && duration != ALREADY_AUTHORIZED) {
                                host.auditRequestDecision(id, accountId, authenticatedBinding, false,
                                    if (duration == -2) "capability.request.confirmation.expired" else "capability.request.rejected")
                                throw CapabilityDenied(if (duration == -2) "LOCAL_CONFIRMATION_TIMEOUT" else "LOCAL_USER_REJECTED")
                            }
                            if (duration != ALREADY_AUTHORIZED)
                                host.auditRequestDecision(id, accountId, authenticatedBinding, true)
                            val remainingApproval = Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() - System.currentTimeMillis()
                            approval = if (duration == ALREADY_AUTHORIZED)
                                (host.requestAuthorization(id, entry, parameters, authenticatedBinding) as? CapabilityAuthorization.Allowed)?.approval
                            else host.approveRequest(id, entry, parameters, duration, remainingApproval, authenticatedBinding)
                        }
                    }
                    saveAuthorization(id, host.authorizationScope(entry, parameters), approval)
                    run {
                        currentCoroutineContext().ensureActive()
                        check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() &&
                            grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                        // 确认弹窗可能跨越撤销或协议变化；查询服务端的身份与请求绑定，
                        // 本机能力许可仍由插件内核独立裁决。
                        if (!authorizationCurrent()) throw CapabilityDenied("DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED")
                        val current = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/device-requests/$id",
                            beforeSend = {
                                check(authorizationCurrent()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
                                val saved = synchronized(stateLock) { readJournal(id) }
                                check(saved != null && journalAuthorizationCurrent(saved)) { "LOCAL_AUTHORIZATION_CHANGED" }
                            }))
                        if (current.status in setOf(401,403,404,409)) throw CapabilityDenied("GATEWAY_AUTHORIZATION_REVOKED")
                        val live = JsonFields.obj(JsonFields.field(current.requireData("DEVICE_LOOKUP_FAILED"),"request")) ?: error("DEVICE_REQUEST_INVALID")
                        if (JsonFields.string(live,"state") !in setOf("pending","claimed") ||
                            JsonFields.int(live,"grantRevision") != grantRevision || JsonFields.int(live,"pairingGeneration") != generation ||
                            JsonFields.string(live,"deviceId") != deviceId) {
                            host.cancelRequestAuthorization(id, authenticatedBinding)
                            throw CapabilityDenied("GATEWAY_AUTHORIZATION_REVOKED")
                        }
                        currentCoroutineContext().ensureActive()
                        check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() &&
                            grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                        stage = "invoke"
                        if (screenInvocation) {
                            val permissions = entry.primitives.mapNotNull {
                                when (it) {
                                    "kernel.screen.read" -> com.openandroidintelligence.control.ScreenPermission.READ
                                    "kernel.screen.capture" -> com.openandroidintelligence.control.ScreenPermission.CAPTURE
                                    "kernel.screen.control" -> com.openandroidintelligence.control.ScreenPermission.CONTROL
                                    else -> null
                                }
                            }.toSet()
                            val expiresAt = Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli()
                            val screen = ScreenHostRegistry.current ?: throw CapabilityDenied("SCREEN_PROVIDER_UNAVAILABLE")
                            val revalidate = {
                                if (!authorizationCurrent() || !host.authorizationIsCurrent(id, entry, parameters, authenticatedBinding))
                                    throw com.openandroidintelligence.control.ScreenAuthorizationException("LOCAL_AUTHORIZATION_CHANGED")
                            }
                            val owner = screenBinding ?: throw CapabilityDenied("SCREEN_CONNECTION_REQUIRED")
                            screen.installRequestAuthorization(id, permissions, expiresAt, Json.sha256(parameters), owner, revalidate)
                        }
                        if (!authorizationCurrent()) throw CapabilityDenied("DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED")
                        val saved = synchronized(stateLock) { readJournal(id) }
                        if (saved == null || !journalAuthorizationCurrent(saved) || !host.authorizationIsCurrent(id, entry, parameters, authenticatedBinding))
                            throw CapabilityDenied("LOCAL_AUTHORIZATION_CHANGED")
                        reportAuthorization(id, "executing")
                        currentCoroutineContext().ensureActive()
                        val authorized = synchronized(stateLock) { readJournal(id) }
                        if (!authorizationCurrent() || authorized == null || !journalAuthorizationCurrent(authorized) ||
                            grantRevision != currentGrantRevision() ||
                            Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() <= System.currentTimeMillis() ||
                            !host.authorizationIsCurrent(id, entry, parameters, authenticatedBinding))
                            throw CapabilityDenied("LOCAL_AUTHORIZATION_CHANGED")
                        invocationStarted = true
                        diagnostic(id,stage,"STARTED")
                        val output = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,key,parameters,id,authenticatedBinding = authenticatedBinding) }
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
            result = if (!invocationStarted || invocationRisk == "read")
                DeviceRequestResult.Cancelled(mapOf("code" to "DEVICE_REQUEST_CANCELLED")) else DeviceRequestResult.OutcomeUnknown
            diagnostic(id,stage,"EXECUTION_CANCELLED")
        } catch (failure: Exception) {
            val code = when(stage) {
                "claim" -> "DEVICE_CLAIM_FAILED"
                "authorization" -> "DEVICE_AUTHORIZATION_CHECK_FAILED"
                else -> "PLUGIN_EXECUTION_FAILED"
            }
            result = if (screenInvocation) ScreenExecutionErrors.result(failure)
                ?: if (invocationStarted && invocationRisk != "read") DeviceRequestResult.OutcomeUnknown
                    else DeviceRequestResult.Failed(mapOf("code" to code))
                else if (invocationStarted && invocationRisk != "read") DeviceRequestResult.OutcomeUnknown
                    else DeviceRequestResult.Failed(mapOf("code" to code))
            diagnostic(id,stage,failure.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,128}")) } ?: code)
        } finally {
            if (screenInvocation && screenBinding != null) ScreenHostRegistry.current?.finishRequest(id, screenBinding)
        }
        // 未获得并核对服务器收据时，保留日志等待恢复；不得自行构造收据或上传结果。
        val receipt = claim ?: return
        // 已落盘的真实结果不能因恢复时的网络异常或超时而被改写。
        persistedResult?.let { result = it }
        if (!checkpoint(id,request,"completed",encodeResult(result))) return
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
        if (!authorizationCurrent()) return
        try {
            withTimeout(DELIVERY_TIMEOUT_MILLIS) {
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        val saved = synchronized(stateLock) { readJournal(id) }
                        if (!authorizationCurrent() || saved == null || !journalDeliveryCurrent(saved)) return@withTimeout
                        var deliveredResult = JsonFields.obj(JsonFields.field(saved, "result"))?.let(::decodeResult) ?: result
                        if (deliveredResult is DeviceRequestResult.Succeeded && !remoteAllowsSensitiveDelivery(id, claim)) {
                            host.cancelRequestAuthorization(id, authenticatedBinding)
                            deliveredResult = cancelledResult(saved)
                            val request = JsonFields.obj(Json.of(mapOf("expiresAt" to JsonFields.string(saved, "expiresAt"))))!!
                            if (!checkpoint(id, request, "completed", encodeResult(deliveredResult))) return@withTimeout
                        }
                        client.submitResult(claim,deliveredResult)
                        synchronized(stateLock) {
                            val current = readJournal(id)
                            if (current != null && JsonFields.bool(current, "cancelRequested") == true) {
                                writeJournal(id, journalFields(current).apply {
                                    this["stage"] = "fenced"
                                    this["fenceReason"] = "REQUEST_CANCELLED"
                                })
                            } else documents.delete(id)
                        }
                        host.finishRequestAuthorization(id, authenticatedBinding)
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
    private suspend fun remoteAllowsSensitiveDelivery(id: String, claim: ClaimReceipt): Boolean {
        val response = http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/device-requests/$id",
            beforeSend = {
                check(authorizationCurrent()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
                val saved = documents.read(id)?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
                check(saved != null && journalDeliveryCurrent(saved)) { "LOCAL_AUTHORIZATION_CHANGED" }
            }))
        if (response.status in setOf(401, 403, 404, 409)) return false
        val request = JsonFields.obj(JsonFields.field(response.requireData("DEVICE_LOOKUP_FAILED"), "request"))
            ?: error("DEVICE_REQUEST_INVALID")
        return JsonFields.string(request, "deviceId") == deviceId &&
            JsonFields.int(request, "pairingGeneration") == generation &&
            JsonFields.int(request, "grantRevision") == claim.grantRevision &&
            JsonFields.string(request, "state") in setOf("claimed", "succeeded") &&
            (JsonFields.string(request, "expiresAt")?.let(Instant::parse)?.toEpochMilli() ?: 0L) > System.currentTimeMillis()
    }

    private fun diagnostic(id: String,stage: String,code: String) {
        GatewayLog.d("OaiDeviceRequest","requestId=$id stage=$stage code=$code")
    }
    private suspend fun confirm(id: String, identity: PluginIdentity, key: String, parameters: JsonValue,
        request: JsonValue.JObject, agentName: String, purpose: String, operation: String): Int {
        val submittedAt = JsonFields.string(request, "createdAt")?.let(Instant::parse)?.toEpochMilli()
            ?: System.currentTimeMillis()
        val expiresAt = minOf(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli(),
            submittedAt + CONFIRMATION_TIMEOUT_MILLIS)
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0) return -2
        reportAuthorization(id, "awaiting_confirmation")
        return withTimeoutOrNull((expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)) {
            confirmationLock.withLock {
                val entry = host.entries(identity.pluginId).firstOrNull { it.key == key && it.identity == identity }
                    ?: return@withLock -1
                if (host.requestAuthorization(id, entry, parameters, authenticatedBinding) is CapabilityAuthorization.Allowed)
                    return@withLock ALREADY_AUTHORIZED
                val wait = CompletableDeferred<Int>(); confirmations[id] = wait
                val sharedApproval = scope.launch {
                    host.authorizationChanges.collect {
                        when (runCatching { host.requestAuthorization(id, entry, parameters, authenticatedBinding) }
                            .getOrElse { CapabilityAuthorization.Denied("AUTHENTICATED_DEVICE_BINDING_REQUIRED") }) {
                            is CapabilityAuthorization.Allowed -> wait.complete(ALREADY_AUTHORIZED)
                            is CapabilityAuthorization.Denied -> wait.complete(-1)
                            CapabilityAuthorization.AwaitingConfirmation -> Unit
                        }
                    }
                }
                confirmation.value = DeviceConfirmation(id, identity.pluginId, key, Json.canonical(parameters).take(4096),
                    agentName, purpose, operation, expiresAt, digestName("$decisionOwner:$id"))
                showConfirmationNotification(id, agentName, purpose)
                try { wait.await() }
                finally {
                    sharedApproval.cancel()
                    confirmations.remove(id); confirmation.value = null
                    context.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
                }
            }
        } ?: -2
    }

    private suspend fun reportAuthorization(id: String, state: String) {
        try {
            withTimeout(5_000L) {
                val response = http.execute(SignedGatewayRequest("POST",
                    "/open-android-intelligence/v2/device-requests/$id/authorization",
                    body = Json.canonical(Json.of(mapOf("state" to state))).toByteArray(),
                    headers = listOf(RawHeader("Content-Type", "application/json")),
                    beforeSend = {
                        check(authorizationCurrent()) { "DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED" }
                        val document = documents.read(id)?.let { JsonFields.obj(Json.parse(it.decodeToString())) }
                        check(document != null && journalAuthorizationCurrent(document)) { "LOCAL_AUTHORIZATION_CHANGED" }
                    }))
                if (response.status in setOf(401, 403, 404, 409)) throw CapabilityDenied("DEVICE_REQUEST_BINDING_CHANGED")
                response.requireData("DEVICE_AUTHORIZATION_PROGRESS_FAILED")
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            diagnostic(id, "authorization", "AUTHORIZATION_PROGRESS_DELIVERY_PENDING")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: CapabilityDenied) { throw denied }
        catch (_: IOException) { diagnostic(id, "authorization", "AUTHORIZATION_PROGRESS_DELIVERY_PENDING") }
    }

    private fun showConfirmationNotification(id: String, agentName: String, purpose: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CONFIRMATION_CHANNEL, "设备操作确认", NotificationManager.IMPORTANCE_HIGH))
            val intent = Intent(context, com.openandroidintelligence.mobile.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("device_request_id", id)
            val pending = PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(id.hashCode(), Notification.Builder(context, CONFIRMATION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("设备操作需要确认")
                .setContentText("${agentName.take(80)}：${purpose.take(160)}")
                .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(CONFIRMATION_TIMEOUT_MILLIS).build())
        }.onFailure { diagnostic(id, "confirmation", "CONFIRMATION_NOTIFICATION_UNAVAILABLE") }
    }

    private fun saveAuthorization(id: String, scope: CapabilityRequestScope, approval: LocalCapabilityApproval?) = synchronized(stateLock) {
        val existing = readJournal(id) ?: error("EXECUTION_JOURNAL_INVALID")
        if (!journalAuthorizationCurrent(existing)) throw CapabilityDenied("LOCAL_AUTHORIZATION_CHANGED")
        val fields = journalFields(existing)
        fields["localApproval"] = mapOf("requestId" to id, "capability" to scope.capability,
            "operation" to scope.operation.name, "parametersSha256" to scope.parametersSha256, "approvalId" to approval?.id)
        writeJournal(id, fields)
    }
    private fun checkpoint(id: String,request: JsonValue.JObject,stage: String,result: Map<String,Any?>? = null): Boolean = synchronized(stateLock) {
        val existing = readJournal(id) ?: return@synchronized false
        if (JsonFields.string(existing, "stage") == "fenced") return@synchronized false
        if (stage == "claimed" && (JsonFields.string(existing, "stage") != "received" || !journalAuthorizationCurrent(existing)))
            return@synchronized false
        val fields = journalFields(existing)
        fields["stage"] = stage
        fields["expiresAt"] = JsonFields.string(request,"expiresAt")
        if (result != null) fields["result"] = if (result["outcome"] == "succeeded" && JsonFields.bool(existing, "cancelRequested") == true)
            encodeResult(cancelledResult(existing)) else result
        writeJournal(id, fields)
        true
    }

    private fun cancelledResult(document: JsonValue.JObject?): DeviceRequestResult =
        if (JsonFields.string(JsonFields.obj(JsonFields.field(document, "localApproval")), "operation") == "READ")
            DeviceRequestResult.Cancelled(mapOf("code" to "DEVICE_REQUEST_CANCELLED")) else DeviceRequestResult.OutcomeUnknown

    private fun journalDeliveryCurrent(document: JsonValue.JObject): Boolean {
        if (JsonFields.string(document, "stage") == "fenced") return false
        val outcome = JsonFields.string(JsonFields.obj(JsonFields.field(document, "result")), "outcome")
        // A metadata-only refusal/cancellation cannot disclose a sensitive output and must reach Core
        // even if the corresponding manual approval was cancelled. Binding/revision fences still apply.
        if (outcome in setOf("denied", "failed", "cancelled", "outcome_unknown")) return (JsonFields.long(document, "localAuthorizationRevision") ?: 0L) == localAuthorizationRevision() &&
                JsonFields.string(document, "hostAuthorizationDigest") == executionHostAuthorizationDigest(host)
        return journalAuthorizationCurrent(document)
    }

    private fun journalAuthorizationCurrent(document: JsonValue.JObject): Boolean =
        JsonFields.string(document, "stage") !in setOf("notified", "fenced") && JsonFields.bool(document, "cancelRequested") != true &&
        (JsonFields.long(document,"localAuthorizationRevision") ?: 0L) == localAuthorizationRevision() &&
            JsonFields.string(document,"hostAuthorizationDigest") == executionHostAuthorizationDigest(host) && approvalCurrent(document)

    private fun approvalCurrent(document: JsonValue.JObject): Boolean {
        val approval = JsonFields.obj(JsonFields.field(document, "localApproval")) ?: return true
        return runCatching {
            val requestId = JsonFields.string(approval,"requestId") ?: return false
            val scope = CapabilityRequestScope(JsonFields.string(approval,"capability") ?: return false,
                DeviceOperation.valueOf(JsonFields.string(approval,"operation") ?: return false),
                JsonFields.string(approval,"parametersSha256") ?: return false)
            host.authorizationScopeIsCurrent(requestId, scope, JsonFields.string(approval,"approvalId"), authenticatedBinding)
        }.getOrDefault(false)
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
        const val EXECUTION_TIMEOUT_MILLIS = 330_000L
        const val CONFIRMATION_TIMEOUT_MILLIS = 300_000L
        const val CONFIRMATION_CHANNEL = "oai-device-confirmation"
        const val ALREADY_AUTHORIZED = -3
        const val DELIVERY_TIMEOUT_MILLIS = 10_000L
        const val RETRY_DELAY_MILLIS = 500L
        const val MAX_ATTEMPTS = 4
    }
}

/** Stable across a normal process restart; unlike the in-memory host revision. */
internal fun executionHostAuthorizationDigest(host: ProductionPluginHost): String {
    val snapshot = host.capabilitySnapshot()
    return Json.sha256(Json.of(mapOf("bindings" to snapshot.bindings, "inventory" to snapshot.inventory,
        "inventoryComplete" to snapshot.inventoryComplete)))
}
