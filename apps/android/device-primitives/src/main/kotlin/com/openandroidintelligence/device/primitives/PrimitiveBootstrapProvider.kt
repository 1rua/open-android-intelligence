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
import java.time.Instant

/** Collector implementation stays outside the App's compile-time surface. */
class PrimitiveBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        if (android.app.Application.getProcessName() != app.packageName) return true
        KernelPrimitiveRegistry.register(SmsReadPrimitive(AndroidSmsInboxReader(app.contentResolver)) {
            app.checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        })
        register("kernel.call-log.read", availability = {
            if (app.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) null else "READ_CALL_LOG_REQUIRED"
        }) { request, _ ->
            permission(app, android.Manifest.permission.READ_CALL_LOG)
            val directions = JsonFields.strings(request, "directions").map { CallDirection.valueOf(it.uppercase()) }.toSet()
                .ifEmpty { setOf(CallDirection.INCOMING, CallDirection.OUTGOING, CallDirection.MISSED) }
            val query = JsonFields.string(request, "query").orEmpty()
            AndroidCallLogReader(app.contentResolver).query(CallLogQuery(CallHistoryPolicy(since(request), limit(request)), directions))
                .filter { query.isEmpty() || it.number.orEmpty().contains(query, true) }
                .map { mapOf("id" to it.providerId.toString(), "direction" to it.direction.name.lowercase(),
                    "timestamp" to it.startedAtEpochMs, "durationSeconds" to it.durationSeconds, "number" to it.number) }
        }
        val notifications = NotificationHostInstaller.install(app)
        KernelPrimitiveRegistry.register(NotificationsReadPrimitive(
            collector = { NotificationRuntimeFactoryRegistry.connectedCollector() },
            authority = notifications.authority,
            permissionGranted = { notifications.bindingPort.isListenerBound() },
            listenerConnected = { NotificationRuntimeFactoryRegistry.isListenerConnected() },
        ))
        ScreenPrimitiveInstaller.install(app)
        return true
    }

    private fun register(id: String, availability: () -> String?, read: suspend (JsonValue.JObject, LocalGrantContext) -> List<Map<String,Any?>>) {
        KernelPrimitiveRegistry.register(object : KernelPrimitiveProvider {
            override val primitiveId = id
            override fun availability(): String? = availability()
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
