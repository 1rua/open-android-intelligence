package com.openandroidintelligence.device.primitives

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.sms.*
import com.openandroidintelligence.calls.*
import com.openandroidintelligence.capability.*
import com.openandroidintelligence.notifications.*
import com.openandroidintelligence.notification.host.NotificationHostInstaller
import com.openandroidintelligence.core.model.NotificationFieldAccess
import java.time.Instant

/** Collector implementation stays outside the App's compile-time surface. */
class PrimitiveBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        if (android.app.Application.getProcessName() != app.packageName) return true
        register(app, "kernel.sms.read") { request, _ ->
            permission(app, android.Manifest.permission.READ_SMS)
            val senders = JsonFields.strings(request, "senders").toSet()
            val query = JsonFields.string(request, "query").orEmpty()
            AndroidSmsInboxReader(app.contentResolver).query(SmsInboxQuery(SmsHistoryPolicy(since(request), limit(request))))
                .filter { (senders.isEmpty() || it.address in senders) && (query.isEmpty() || it.body.orEmpty().contains(query, true)) }
                .map { mapOf("id" to it.providerId.toString(), "sender" to it.address, "text" to it.body,
                    "timestamp" to it.messageAtEpochMs, "read" to it.read) }
        }
        register(app, "kernel.call-log.read") { request, _ ->
            permission(app, android.Manifest.permission.READ_CALL_LOG)
            val directions = JsonFields.strings(request, "directions").map { CallDirection.valueOf(it.uppercase()) }.toSet()
                .ifEmpty { setOf(CallDirection.INCOMING, CallDirection.OUTGOING, CallDirection.MISSED) }
            val query = JsonFields.string(request, "query").orEmpty()
            AndroidCallLogReader(app.contentResolver).query(CallLogQuery(CallHistoryPolicy(since(request), limit(request)), directions))
                .filter { query.isEmpty() || it.number.orEmpty().contains(query, true) }
                .map { mapOf("id" to it.providerId.toString(), "direction" to it.direction.name.lowercase(),
                    "timestamp" to it.startedAtEpochMs, "durationSeconds" to it.durationSeconds, "number" to it.number) }
        }
        register(app, "kernel.notifications.read") { request, context ->
            val collector = NotificationRuntimeFactoryRegistry.activeCollector ?: throw CapabilityDenied("NOTIFICATION_LISTENER_UNAVAILABLE")
            val composition = NotificationHostInstaller.install(app)
            val policy = composition.policyPort.snapshot()
            val result = NotificationAgentQueryGateway(collector, composition.authority).query(NotificationAgentQueryRequest(
                operationId = context.correlationId, policyRevision = policy.revision, limit = limit(request),
                filter = NotificationQueryFilter(JsonFields.strings(request, "packageIds").distinct().sorted(), policy.fieldAccess),
            ))
            if (result.failureReason != null) throw CapabilityDenied(result.failureReason!!)
            val query = JsonFields.string(request,"query").orEmpty()
            val receivedAfter = since(request)
            result.records.filter { receivedAfter == null || (it.metadata?.postedAtEpochMs ?: Long.MIN_VALUE) >= receivedAfter }
                .filter { query.isEmpty() || it.content?.title.orEmpty().contains(query,true) || it.content?.body.orEmpty().contains(query,true) }
                .map { mapOf("id" to it.occurrenceId, "packageName" to it.metadata?.packageName,
                    "title" to it.content?.title, "text" to it.content?.body, "timestamp" to it.metadata?.postedAtEpochMs) }
        }
        return true
    }

    private fun register(context: Context, id: String, read: suspend (JsonValue.JObject, LocalGrantContext) -> List<Map<String,Any?>>) {
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = id
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                val request = JsonFields.obj(Json.parse(input.toString(Charsets.UTF_8))) ?: error("SCHEMA_INVALID")
                return Json.canonical(Json.of(mapOf("records" to read(request,context)))).toByteArray(Charsets.UTF_8)
            }
        })
    }
    private fun permission(context: Context, name: String) { if (context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED) throw CapabilityDenied(name) }
    private fun limit(request: JsonValue.JObject): Int = (JsonFields.int(request,"limit") ?: 50).also { require(it in 1..100) { "SCHEMA_INVALID" } }
    private fun since(request: JsonValue.JObject): Long? = JsonFields.string(request,"receivedAfter")?.let { Instant.parse(it).toEpochMilli() }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
