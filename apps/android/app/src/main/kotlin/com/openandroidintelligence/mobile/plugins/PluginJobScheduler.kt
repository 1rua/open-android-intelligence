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

/** 参数只进入本机加密存储；系统调度只保存不可读正文的 jobId。 */
object PluginJobScheduler {
    private const val RETENTION_MILLIS = 900_000L
    private val pendingStates = setOf("scheduled", "authorization_unavailable")
    private fun documents(context: Context) = EncryptedDocuments(context,"plugin-jobs-v1")

    @Synchronized
    fun schedule(context: Context, call: KernelCallContext, args: JsonValue.JObject): Map<String,Any?> {
        maintain(context)
        val app = context.applicationContext as OpenAndroidIntelligenceApplication
        val local = app.pairingGrants.state.value ?: throw CapabilityDenied("BACKGROUND_GRANT_REQUIRED")
        val grant = local.asKernelGrant()
        check(grant.backgroundSync && grant.permits("kernel.background.run") &&
            grant.permits(identityGrantKey(call.identity,"kernel.background.run"))) { "BACKGROUND_GRANT_REQUIRED" }
        val subject = app.pluginHost.remoteBackgroundSubject(call)
        val authority = ProductionRemoteBackgroundAuthority(context)
        requireReady(authority.revalidate(subject))
        val runAt = Instant.parse(JsonFields.string(args,"runAt") ?: error("SCHEMA_INVALID")).toEpochMilli()
        val delay = runAt - System.currentTimeMillis()
        val minimum = app.pluginHost.minimumBackgroundInterval(call.identity.pluginId)*1000L
        require(delay in minimum..30L*24*60*60*1000) { "SCHEDULE_TIME_INVALID" }
        val query = JsonFields.obj(JsonFields.field(args,"query")) ?: error("SCHEMA_INVALID")
        val target = app.pluginHost.scheduledTarget(call,args)
        val jobId = "job_${UUID.randomUUID()}"
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val osId = UUID.randomUUID().hashCode() and Int.MAX_VALUE
        check(scheduler.getPendingJob(osId) == null) { "SCHEDULER_ID_CONFLICT" }
        val record = mapOf("jobId" to jobId,"osId" to osId,"pluginId" to call.identity.pluginId,
            "author" to call.identity.authorKeyFingerprint,"version" to call.identity.version,
            "accountId" to call.accountId,"pairingId" to call.pairingId,"grantRevision" to local.revision,
            "remoteSubject" to subject.encode(), "runAt" to runAt,"expiresAt" to runAt+RETENTION_MILLIS,
            "query" to query,"state" to "scheduled", "capabilityId" to target.id,"capabilityVersion" to target.version,
            "schemaSha256" to Json.sha256(target.schema))
        // 授权或账号在准备期间变化时，不能创建一个已经失效的任务。
        check(app.pairingGrants.state.value?.revision == local.revision) { "LOCAL_AUTHORIZATION_CHANGED" }
        requireReady(authority.revalidate(subject))
        documents(context).write(jobId,Json.canonical(Json.of(record)).toByteArray())
        val extras = PersistableBundle().apply { putString("jobId",jobId) }
        val info = JobInfo.Builder(osId,ComponentName(context,PluginTimerService::class.java)).setMinimumLatency(delay)
            .setPersisted(true).setExtras(extras).build()
        if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS) {
            documents(context).delete(jobId); error("SCHEDULER_UNAVAILABLE")
        }
        return mapOf("jobId" to jobId,"runAt" to Instant.ofEpochMilli(runAt).toString(),"state" to "scheduled")
    }

    @Synchronized
    fun read(context: Context,call: KernelCallContext,args: JsonValue.JObject): Map<String,Any?> {
        maintain(context)
        val jobId = JsonFields.string(args,"jobId") ?: error("SCHEMA_INVALID")
        val doc = load(documents(context), jobId) ?: error("JOB_NOT_FOUND")
        val app = context.applicationContext as OpenAndroidIntelligenceApplication
        val current = app.pluginHost.remoteBackgroundSubject(call)
        val stored = RemoteBackgroundSubject.decode(JsonFields.field(doc,"remoteSubject"))
        check(JsonFields.string(doc,"pluginId") == call.identity.pluginId && JsonFields.string(doc,"author") == call.identity.authorKeyFingerprint &&
            stored.principal == current.principal && stored.accountEpoch == current.accountEpoch) { "JOB_SCOPE_MISMATCH" }
        return mapOf("jobId" to jobId,"state" to JsonFields.string(doc,"state"),"result" to JsonFields.field(doc,"result"))
    }

    @Synchronized
    fun cancel(context: Context,call: KernelCallContext,args: JsonValue.JObject): Map<String,Any?> {
        read(context,call,args)
        val jobId = JsonFields.string(args,"jobId")!!
        val docs = documents(context)
        val doc = load(docs,jobId) ?: error("JOB_NOT_FOUND")
        if (JsonFields.string(doc,"state") !in pendingStates) error("JOB_ALREADY_EXECUTING")
        context.getSystemService(JobScheduler::class.java).cancel(JsonFields.int(doc,"osId")!!)
        save(docs,jobId,doc,"cancelled")
        return mapOf("jobId" to jobId,"state" to "cancelled")
    }

    /** 返回 true 只表示密钥暂时不可读，应交回系统调度退避，不连接或切换任何聊天。 */
    @Synchronized
    fun run(context: Context,jobId: String): Boolean {
        val app = context.applicationContext as OpenAndroidIntelligenceApplication
        return runStoredJob(app.pluginHost, app.pairingGrants, ProductionRemoteBackgroundAuthority(context), documents(context), jobId)
    }

    @Synchronized
    internal fun runStoredJob(host: ProductionPluginHost, grants: PairingGrantStateHolder, authority: RemoteBackgroundAuthority,
        docs: EncryptedDocuments, jobId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val doc = load(docs,jobId) ?: return false
        // executing 已经越过持久栅栏；进程重启后只能报告未知，绝不能再次调用插件。
        if (JsonFields.string(doc,"state") == "executing") { save(docs,jobId,doc,"outcome_unknown"); return false }
        if (JsonFields.string(doc,"state") !in pendingStates) return false
        if (invocationExpired(doc,nowMillis)) { save(docs,jobId,doc,"expired"); return false }
        val subject = runCatching { RemoteBackgroundSubject.decode(JsonFields.field(doc,"remoteSubject")) }.getOrNull()
        val local = grants.state.value
        if (subject == null || subject.principal.accountId != JsonFields.string(doc,"accountId") ||
            subject.localGrantRevision != JsonFields.long(doc,"grantRevision") || local == null || local.revision != subject.localGrantRevision ||
            !local.asKernelGrant().backgroundSync) {
            save(docs,jobId,doc,"authorization_expired"); return false
        }
        when (val admission = authority.revalidate(subject)) {
            is RemoteBackgroundAdmission.Waiting -> {
                save(docs,jobId,doc,"authorization_unavailable",Json.of(mapOf("code" to admission.code)))
                return true
            }
            is RemoteBackgroundAdmission.Denied -> { save(docs,jobId,doc,"authorization_expired"); return false }
            RemoteBackgroundAdmission.Ready -> Unit
        }
        val binding = RemoteBackgroundBinding(subject.principal,subject.accountEpoch,subject.localGrantRevision) {
            grants.state.value?.revision == subject.localGrantRevision && authority.revalidate(subject) == RemoteBackgroundAdmission.Ready
        }
        var retry = false
        executeStoredInvocation(host,doc,jobId,binding,nowMillis) { state,result ->
            val admission = if (state == "authorization_expired") authority.revalidate(subject) else null
            if (admission is RemoteBackgroundAdmission.Waiting) {
                retry = true
                save(docs,jobId,doc,"authorization_unavailable",Json.of(mapOf("code" to admission.code)))
            } else save(docs,jobId,doc,state,result)
        }
        return retry
    }

    internal fun executeStoredInvocation(host: ProductionPluginHost,doc: JsonValue.JObject,jobId: String,
        binding: RemoteBackgroundBinding, nowMillis: Long = System.currentTimeMillis(), checkpoint: (String,JsonValue?) -> Unit) {
        if (invocationExpired(doc,nowMillis)) { checkpoint("expired",null); return }
        val identity = PluginIdentity(JsonFields.string(doc,"pluginId")!!,JsonFields.string(doc,"author")!!,JsonFields.string(doc,"version")!!)
        val target = host.entries(identity.pluginId).firstOrNull {
            it.identity == identity && it.id == JsonFields.string(doc,"capabilityId") &&
                it.version == JsonFields.string(doc,"capabilityVersion") && Json.sha256(it.schema) == JsonFields.string(doc,"schemaSha256")
        }
        if (target == null) { checkpoint("target_unavailable",null); return }
        if (binding.principal.accountId != JsonFields.string(doc,"accountId") ||
            !host.backgroundInvocationAllowed(target,JsonFields.field(doc,"query")!!,binding)) {
            checkpoint("authorization_expired",null); return
        }
        // 原子加密检查点先于真实插件调用；系统重入或进程崩溃都不得重放。
        checkpoint("executing",null)
        try {
            val output = host.invokeBackground(target,JsonFields.field(doc,"query")!!,jobId,binding)
            if (!binding.isCurrent()) checkpoint("outcome_unknown",null)
            else checkpoint("succeeded",Json.parse(output.decodeToString()))
        } catch (cancelled: CancellationException) { checkpoint("outcome_unknown",null); throw cancelled }
        catch (_: InterruptedException) { checkpoint("outcome_unknown",null); Thread.currentThread().interrupt() }
        catch (_: Exception) { checkpoint("outcome_unknown",null) }
    }

    private fun requireReady(admission: RemoteBackgroundAdmission) {
        when (admission) {
            RemoteBackgroundAdmission.Ready -> Unit
            is RemoteBackgroundAdmission.Waiting -> throw CapabilityDenied(admission.code)
            is RemoteBackgroundAdmission.Denied -> throw CapabilityDenied(admission.code)
        }
    }
    private fun invocationExpired(doc: JsonValue.JObject,nowMillis: Long): Boolean = (JsonFields.long(doc,"expiresAt") ?: 0L) <= nowMillis
    private fun load(docs: EncryptedDocuments,id: String): JsonValue.JObject? =
        docs.read(id)?.let { JsonFields.obj(Json.parse(it.decodeToString())) ?: error("JOB_INVALID") }
    private fun save(docs: EncryptedDocuments,id: String,doc: JsonValue.JObject,state: String,result: JsonValue? = null) {
        val fields = doc.fields.toMap().toMutableMap(); fields["state"] = JsonValue.JString(state)
        fields.remove("result")
        if (state !in pendingStates && state != "executing") {
            fields["expiresAt"] = Json.of(System.currentTimeMillis()+RETENTION_MILLIS); fields.remove("query")
        }
        if (result != null) fields["result"] = result
        docs.write(id,Json.canonical(JsonValue.JObject(fields.toList())).toByteArray())
    }
    @Synchronized
    fun maintain(context: Context) {
        val docs=documents(context)
        docs.keys().forEach { id ->
            val doc=load(docs,id) ?: return@forEach
            if (JsonFields.string(doc,"state") == "executing") save(docs,id,doc,"outcome_unknown")
            else if (invocationExpired(doc,System.currentTimeMillis())) {
                context.getSystemService(JobScheduler::class.java).cancel(JsonFields.int(doc,"osId") ?: return@forEach); docs.delete(id)
            }
        }
    }
    fun eraseAccount(context: Context,account: String,pairing: String) = eraseMatching(context) {
        JsonFields.string(it,"accountId") == account && JsonFields.string(it,"pairingId") == pairing
    }
    fun erasePlugin(context: Context,pluginId: String) = eraseMatching(context) { JsonFields.string(it,"pluginId") == pluginId }
    @Synchronized
    private fun eraseMatching(context: Context,predicate: (JsonValue.JObject) -> Boolean) {
        val docs=documents(context)
        docs.keys().forEach { id -> val doc=load(docs,id) ?: return@forEach
            if (predicate(doc)) { context.getSystemService(JobScheduler::class.java).cancel(JsonFields.int(doc,"osId")!!); docs.delete(id) }
        }
    }
}

class PluginTimerService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int,Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("jobId") ?: return false
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try { retry = runInterruptible { PluginJobScheduler.run(this@PluginTimerService,id) } }
            finally { jobs.remove(params.jobId); jobFinished(params,retry) }
        }
        if (jobs.putIfAbsent(params.jobId,task) != null) { task.cancel(); return false }
        task.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
