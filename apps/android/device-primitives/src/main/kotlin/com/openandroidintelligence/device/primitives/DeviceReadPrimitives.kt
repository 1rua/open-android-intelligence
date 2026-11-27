package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.capability.SmsHistoryPolicy
import com.openandroidintelligence.core.model.NotificationCollector
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.notifications.*
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import com.openandroidintelligence.sms.*
import java.time.Instant
import com.openandroidintelligence.capability.SmsSendRequest

/** The registered production providers, with Android adapters injected at the composition root. */
internal class SmsReadPrimitive(
    private val reader: SmsInboxReader,
    private val permissionGranted: () -> Boolean,
) : RecordReadPrimitive("kernel.sms.read") {
    override fun availability(): String? = if (permissionGranted()) null else "READ_SMS_REQUIRED"
    override suspend fun read(request: JsonValue.JObject, context: LocalGrantContext): List<Map<String, Any?>> {
        checkPermission()
        val includeContent = JsonFields.bool(request, "includeContent") != false
        val textQuery = JsonFields.string(request, "query").orEmpty()
        if (!includeContent && textQuery.isNotEmpty()) throw CapabilityDenied("LOCAL_DATA_SCOPE_DENIED")
        val scopeMode = JsonFields.string(request, "smsScopeMode")?.let(SmsScopeMode::valueOf) ?: SmsScopeMode.ALL
        val phoneNumbers = JsonFields.strings(request, "smsPhoneNumbers").toSet()
        val keywords = JsonFields.strings(request, "smsKeywords").toSet()
        val senders = JsonFields.strings(request, "senders").toSet()
        if (!SmsScopeMatcher.hasConditions(scopeMode, phoneNumbers, keywords)) return emptyList()
        val rows = try {
            reader.query(SmsInboxQuery(
                history = SmsHistoryPolicy(receivedAfter(request), readLimit(request)),
                senders = senders,
                textQuery = textQuery,
                includeBody = includeContent,
                allSenders = scopeMode == SmsScopeMode.ALL,
                phoneNumbers = phoneNumbers.takeIf { scopeMode == SmsScopeMode.PHONE_NUMBERS }.orEmpty(),
                keywords = keywords.takeIf { scopeMode == SmsScopeMode.KEYWORDS }.orEmpty(),
            ))
        } catch (_: SecurityException) { throw CapabilityDenied("READ_SMS_REQUIRED") }
        catch (_: SmsQueryScanLimitExceeded) { throw CapabilityDenied("SMS_QUERY_SCAN_LIMIT_EXCEEDED") }
        // Recheck before disclosure even if an injected/provider implementation ignored the query.
        val records = rows.filter {
            it.scopeMatched && (senders.isEmpty() || it.address in senders) &&
                SmsScopeMatcher.matches(scopeMode, phoneNumbers, keywords, it.address, it.body, it.matchedKeywords) &&
                (textQuery.isEmpty() || it.body.orEmpty().contains(textQuery, ignoreCase = true))
        }.take(readLimit(request)).map { mapOf("id" to it.providerId.toString(), "sender" to it.address,
            "text" to it.body.takeIf { includeContent }, "timestamp" to it.messageAtEpochMs, "read" to it.read) }
        checkPermission()
        return records
    }

    private fun checkPermission() {
        if (!permissionGranted()) throw CapabilityDenied("READ_SMS_REQUIRED")
    }
}

/** 原生短信发送原语；发送正文只在此调用栈内解析，不写入审计或控制历史。 */
internal class SmsSendPrimitive(
    private val sender: AndroidSmsSendProvider,
    private val beforeSystemEffect: (LocalGrantContext, SmsSendRequest) -> Unit,
    private val permissionGranted: () -> Boolean = { true },
    private val subscriptionBinding: (Int) -> String? = { it.toString() },
) : KernelPrimitiveProvider {
    override val primitiveId: String = "kernel.sms.send"

    override fun availability(): String? = if (permissionGranted()) null else "SEND_SMS_REQUIRED"

    override fun confirmationBinding(input: ByteArray): String {
        val request = JsonFields.obj(Json.parse(input.decodeToString())) ?: error("SCHEMA_INVALID")
        val id = JsonFields.int(request, "subscriptionId") ?: error("SCHEMA_INVALID")
        if (id < 0) throw CapabilityDenied("SMS_SUBSCRIPTION_REQUIRED")
        return subscriptionBinding(id) ?: throw CapabilityDenied("SMS_SUBSCRIPTION_UNAVAILABLE")
    }

    override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
        if (!permissionGranted()) throw CapabilityDenied("SEND_SMS_REQUIRED")
        val request = JsonFields.obj(Json.parse(input.decodeToString())) ?: error("SCHEMA_INVALID")
        val sms = SmsSendRequest(
            subscriptionId = JsonFields.int(request, "subscriptionId") ?: error("SCHEMA_INVALID"),
            recipient = JsonFields.string(request, "recipient") ?: error("SCHEMA_INVALID"),
            text = JsonFields.string(request, "text") ?: error("SCHEMA_INVALID"),
        )
        val binding = confirmationBinding(input)
        val result = sender.send(sms, SmsSendBeforeSystemEffect {
            if (!permissionGranted()) throw CapabilityDenied("SEND_SMS_REQUIRED")
            if (binding != confirmationBinding(input)) throw CapabilityDenied("SMS_SUBSCRIPTION_CHANGED")
            beforeSystemEffect(context, sms)
        })
        return Json.canonical(Json.of(mapOf(
            "status" to result.status.name.lowercase(),
            "segmentCount" to result.segmentCount,
            "sentSegments" to result.sentSegments,
            "failedSegments" to result.failedSegments,
            "failureCode" to result.failureCode,
            "deliveryConfirmed" to false,
            "automaticRetryAllowed" to false,
        ))).encodeToByteArray()
    }
}

internal class NotificationsReadPrimitive(
    private val collector: () -> NotificationCollector?,
    private val authority: PersistentNotificationPolicyAuthority,
    private val listenerConnected: () -> Boolean = { true },
    private val permissionGranted: () -> Boolean,
) : RecordReadPrimitive("kernel.notifications.read") {
    override fun availability(): String? {
        if (!permissionGranted()) return "NOTIFICATION_ACCESS_REQUIRED"
        val snapshot = authority.snapshot()
        if (snapshot.corrupted) return "LOCAL_POLICY_CORRUPTED"
        if (!snapshot.granted) return "LOCAL_GRANT_REQUIRED"
        if (!listenerConnected()) return "NOTIFICATION_LISTENER_UNAVAILABLE"
        return null
    }
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
