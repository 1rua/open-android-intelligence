package com.openandroidintelligence.mobile.plugins

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Timer identity is authored locally and its parameters remain in encrypted, account-scoped storage. */
object PluginJobScheduler {
    private fun documents(context: Context) = EncryptedDocuments(context,"plugin-jobs-v1")
    fun schedule(context: Context,call: KernelCallContext,args: JsonValue.JObject): Map<String,Any?> {
        maintain(context)
        val app = context.applicationContext as OpenAndroidIntelligenceApplication
        val grant = app.pairingGrants.currentKernelGrant(call.pairingId) ?: throw CapabilityDenied("kernel.background.run")
        check(grant.backgroundSync && identityGrantKey(call.identity,"kernel.background.run") in grant.granted) { "BACKGROUND_GRANT_REQUIRED" }
        val runAt = Instant.parse(JsonFields.string(args,"runAt") ?: error("SCHEMA_INVALID")).toEpochMilli()
        val delay = runAt - System.currentTimeMillis()
        val minimum=app.pluginHost.minimumBackgroundInterval(call.identity.pluginId)*1000L
        require(delay in minimum..30L*24*60*60*1000) { "SCHEDULE_TIME_INVALID" }
        val query = JsonFields.obj(JsonFields.field(args,"query")) ?: error("SCHEMA_INVALID")
        val jobId = "job_${UUID.randomUUID()}"
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val osId = (UUID.randomUUID().hashCode() and Int.MAX_VALUE)
        check(scheduler.getPendingJob(osId) == null) { "SCHEDULER_ID_CONFLICT" }
        val record = mapOf("jobId" to jobId,"osId" to osId,"pluginId" to call.identity.pluginId,"author" to call.identity.authorKeyFingerprint,
            "version" to call.identity.version,"accountId" to call.accountId,"pairingId" to call.pairingId,"grantRevision" to grant.revision,
            "runAt" to runAt,"expiresAt" to runAt+900_000L,"query" to query,"state" to "scheduled")
        documents(context).write(jobId,Json.canonical(Json.of(record)).toByteArray())
        val extras = PersistableBundle().apply { putString("jobId",jobId) }
        val info = JobInfo.Builder(osId,ComponentName(context,PluginTimerService::class.java)).setMinimumLatency(delay)
            .setPersisted(true).setExtras(extras).build()
        if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS) {
            documents(context).delete(jobId); error("SCHEDULER_UNAVAILABLE")
        }
        return mapOf("jobId" to jobId,"runAt" to Instant.ofEpochMilli(runAt).toString(),"state" to "scheduled")
    }
    fun read(context: Context,call: KernelCallContext,args: JsonValue.JObject): Map<String,Any?> {
        maintain(context)
        val jobId = JsonFields.string(args,"jobId") ?: error("SCHEMA_INVALID")
        val bytes = documents(context).read(jobId) ?: error("JOB_NOT_FOUND")
        val doc = JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("JOB_INVALID")
        check(JsonFields.string(doc,"pluginId") == call.identity.pluginId && JsonFields.string(doc,"author") == call.identity.authorKeyFingerprint &&
            JsonFields.string(doc,"accountId") == call.accountId && JsonFields.string(doc,"pairingId") == call.pairingId) { "JOB_SCOPE_MISMATCH" }
        return mapOf("jobId" to jobId,"state" to JsonFields.string(doc,"state"),"result" to JsonFields.field(doc,"result"))
    }
    @Synchronized fun cancel(context: Context,call: KernelCallContext,args: JsonValue.JObject): Map<String,Any?> {
        read(context,call,args)
        val jobId = JsonFields.string(args,"jobId")!!
        val doc = JsonFields.obj(Json.parse(documents(context).read(jobId)!!.decodeToString()))!!
        if (JsonFields.string(doc,"state") != "scheduled") error("JOB_ALREADY_EXECUTING")
        context.getSystemService(JobScheduler::class.java).cancel(JsonFields.int(doc,"osId")!!)
        save(context,jobId,doc,"cancelled")
        return mapOf("jobId" to jobId,"state" to "cancelled")
    }
    @Synchronized fun run(context: Context,jobId: String) {
        val doc = JsonFields.obj(Json.parse(documents(context).read(jobId)?.decodeToString() ?: return)) ?: return
        if (JsonFields.string(doc,"state") != "scheduled") return
        val app = context.applicationContext as OpenAndroidIntelligenceApplication
        val account = JsonFields.string(doc,"accountId")!!
        val pairing = JsonFields.string(doc,"pairingId")!!
        val grant = app.pairingGrants.currentKernelGrant(pairing)
        if (app.gatewayRuntime.connectedAccountId != account || grant?.revision != JsonFields.long(doc,"grantRevision") || grant?.backgroundSync != true) {
            save(context,jobId,doc,"authorization_expired"); return
        }
        // Persist before invoking: an OS restart cannot repeat a query or a native side effect.
        save(context,jobId,doc,"executing")
        val identity = PluginIdentity(JsonFields.string(doc,"pluginId")!!,JsonFields.string(doc,"author")!!,JsonFields.string(doc,"version")!!)
        try {
            val output = app.pluginHost.invoke(identity,account,"${identity.pluginId}.query@1.0.0",
                JsonFields.field(doc,"query")!!,jobId,background = true)
            save(context,jobId,doc,"succeeded",Json.parse(output.decodeToString()))
        } catch (cancelled: CancellationException) { save(context,jobId,doc,"outcome_unknown"); throw cancelled }
        catch (_: Exception) { save(context,jobId,doc,"failed") }
    }
    private fun save(context: Context,id: String,doc: JsonValue.JObject,state: String,result: JsonValue? = null) {
        val fields = doc.fields.toMap().toMutableMap(); fields["state"] = JsonValue.JString(state)
        if (state != "scheduled" && state != "executing") { fields["expiresAt"] = Json.of(System.currentTimeMillis()+900_000L); fields.remove("query") }
        if (result != null) fields["result"] = result
        documents(context).write(id,Json.canonical(JsonValue.JObject(fields.toList())).toByteArray())
    }
    fun maintain(context:Context) {
        val docs=documents(context)
        docs.keys().forEach { id -> val o=JsonFields.obj(Json.parse(docs.read(id)?.decodeToString() ?: return@forEach)) ?: return@forEach
            if ((JsonFields.long(o,"expiresAt") ?: 0L)<=System.currentTimeMillis()) {
                context.getSystemService(JobScheduler::class.java).cancel(JsonFields.int(o,"osId") ?: return@forEach); docs.delete(id)
            }
        }
    }
    fun eraseAccount(context: Context,account: String,pairing:String) { eraseMatching(context) { JsonFields.string(it,"accountId") == account && JsonFields.string(it,"pairingId")==pairing } }
    fun erasePlugin(context: Context,pluginId: String) { eraseMatching(context) { JsonFields.string(it,"pluginId") == pluginId } }
    private fun eraseMatching(context: Context,predicate: (JsonValue.JObject) -> Boolean) {
        val documents = documents(context)
        documents.keys().forEach { id ->
            val doc = JsonFields.obj(Json.parse(documents.read(id)!!.decodeToString())) ?: error("JOB_INVALID")
            if (predicate(doc)) { context.getSystemService(JobScheduler::class.java).cancel(JsonFields.int(doc,"osId")!!); documents.delete(id) }
        }
    }
}

class PluginTimerService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int,Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("jobId") ?: return false
        jobs[params.jobId] = scope.launch { try {
            val runtime=(application as OpenAndroidIntelligenceApplication).gatewayRuntime
            withContext(Dispatchers.Main) { runtime.restoreSessionIfAvailable() }
            withTimeoutOrNull(30_000) { runtime.phase.first { it is com.openandroidintelligence.mobile.ConnectionPhase.Connected || it is com.openandroidintelligence.mobile.ConnectionPhase.Failed || it is com.openandroidintelligence.mobile.ConnectionPhase.OfflineMirror } }
            runInterruptible { PluginJobScheduler.run(this@PluginTimerService,id) }
        } finally { jobs.remove(params.jobId); jobFinished(params,false) } }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
