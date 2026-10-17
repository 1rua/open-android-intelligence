package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.capability.SmsHistoryPolicy
import com.openandroidintelligence.core.model.NotificationCollector
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.notifications.*
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import com.openandroidintelligence.sms.*
import java.time.Instant

/** The registered production providers, with Android adapters injected at the composition root. */
internal class SmsReadPrimitive(
    private val reader: SmsInboxReader,
    private val permissionGranted: () -> Boolean,
) : RecordReadPrimitive("kernel.sms.read") {
    override suspend fun read(request: JsonValue.JObject, context: LocalGrantContext): List<Map<String, Any?>> {
        checkPermission()
        val rows = try {
            reader.query(SmsInboxQuery(
                history = SmsHistoryPolicy(receivedAfter(request), readLimit(request)),
                senders = JsonFields.strings(request, "senders").toSet(),
                textQuery = JsonFields.string(request, "query").orEmpty(),
            ))
        } catch (_: SecurityException) { throw CapabilityDenied("READ_SMS_REQUIRED") }
        val records = rows.map { mapOf("id" to it.providerId.toString(), "sender" to it.address, "text" to it.body,
            "timestamp" to it.messageAtEpochMs, "read" to it.read) }
        checkPermission()
        return records
    }

    private fun checkPermission() {
        if (!permissionGranted()) throw CapabilityDenied("READ_SMS_REQUIRED")
    }
}

internal class NotificationsReadPrimitive(
    private val collector: () -> NotificationCollector?,
    private val authority: PersistentNotificationPolicyAuthority,
    private val permissionGranted: () -> Boolean,
) : RecordReadPrimitive("kernel.notifications.read") {
    override suspend fun read(request: JsonValue.JObject, context: LocalGrantContext): List<Map<String, Any?>> {
        checkPermission()
        val source = collector() ?: throw CapabilityDenied("NOTIFICATION_LISTENER_UNAVAILABLE")
        val policy = authority.snapshot().policy
        val result = NotificationAgentQueryGateway(source, authority).query(NotificationAgentQueryRequest(
            operationId = context.correlationId, policyRevision = policy.policyRevision, limit = readLimit(request),
            filter = NotificationQueryFilter(
                packageIds = JsonFields.strings(request, "packageIds").distinct().sorted(),
                fieldAccess = policy.fieldAccess,
                receivedAfterEpochMs = receivedAfter(request),
                textQuery = JsonFields.string(request, "query").orEmpty(),
            ),
        ))
        if (result.failureReason != null) throw CapabilityDenied(result.failureReason!!)
        checkPermission()
        return result.records.map { mapOf("id" to it.occurrenceId, "packageName" to it.metadata?.packageName,
            "title" to it.content?.title, "text" to it.content?.body, "timestamp" to it.metadata?.postedAtEpochMs) }
    }

    private fun checkPermission() {
        if (!permissionGranted()) throw CapabilityDenied("NOTIFICATION_ACCESS_REQUIRED")
    }
}

internal abstract class RecordReadPrimitive(final override val primitiveId: String) : KernelPrimitiveProvider {
    final override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
        val request = JsonFields.obj(Json.parse(input.decodeToString())) ?: error("SCHEMA_INVALID")
        return Json.canonical(Json.of(mapOf("records" to read(request, context)))).encodeToByteArray()
    }

    protected abstract suspend fun read(request: JsonValue.JObject, context: LocalGrantContext): List<Map<String, Any?>>

    protected fun readLimit(request: JsonValue.JObject): Int = (JsonFields.int(request, "limit") ?: 50)
        .also { require(it in 1..100) { "SCHEMA_INVALID" } }

    protected fun receivedAfter(request: JsonValue.JObject): Long? = JsonFields.string(request, "receivedAfter")
        ?.let { Instant.parse(it).toEpochMilli() }
}
