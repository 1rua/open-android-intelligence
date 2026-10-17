package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.core.model.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.notifications.*
import com.openandroidintelligence.policy.*
import com.openandroidintelligence.sms.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceReadPrimitivesTest {
    private val context = LocalGrantContext("verified.plugin", "account", "pairing", "request-id")

    @Test
    fun registered_sms_provider_reads_with_query_filters_and_returns_content() = runBlocking {
        var requested: SmsInboxQuery? = null
        val primitive = SmsReadPrimitive(SmsInboxReader { query ->
            requested = query
            listOf(SmsInboxRow(42, "thread", "bank", "balance", 1700, true, null))
        }) { true }
        KernelPrimitiveRegistry.register(primitive)
        try {
            val records = invoke(KernelPrimitiveRegistry.provider("kernel.sms.read")!!,
                """{"senders":["bank"],"query":"balance","receivedAfter":"1970-01-01T00:00:01Z","limit":1}""")
            assertEquals(setOf("bank"), requested!!.senders)
            assertEquals("balance", requested!!.textQuery)
            assertEquals(1000L, requested!!.history.fromEpochMs)
            assertEquals(1, requested!!.history.maxRecords)
            assertEquals("balance", JsonFields.string(records.single(), "text"))
        } finally { KernelPrimitiveRegistry.unregister(primitive) }
    }

    @Test
    fun sms_permission_denial_never_queries_device_and_revocation_never_returns_content() = runBlocking {
        var queried = false
        var allowed = false
        val primitive = SmsReadPrimitive(SmsInboxReader {
            queried = true
            allowed = false
            listOf(SmsInboxRow(42, null, "bank", "secret", 1700, false, null))
        }) { allowed }
        assertThrows(CapabilityDenied::class.java) { runBlocking { invoke(primitive) } }
        assertFalse(queried)
        allowed = true
        assertThrows(CapabilityDenied::class.java) { runBlocking { invoke(primitive) } }
        assertTrue(queried)
    }

    @Test
    fun notification_grant_revision_may_advance_without_changing_capture_policy_revision() = runBlocking {
        val (authority, collector) = notifications(granted = false)
        // The settings port revision is the authorization revision (2), while the
        // collector still captures under policy revision 1. Reads must use the latter.
        authority.localController().apply(authority.snapshot().policy, 2u, true)
        collector.onPosted(raw())
        val records = invoke(NotificationsReadPrimitive({ collector }, authority) { true })
        assertEquals("secret", JsonFields.string(records.single(), "text"))
    }

    @Test
    fun provider_level_sms_permission_denial_is_reported_as_denied() {
        val primitive = SmsReadPrimitive(SmsInboxReader { throw SecurityException("AppOps denied") }) { true }
        assertThrows(CapabilityDenied::class.java) { runBlocking { invoke(primitive) } }
    }

    @Test
    fun notification_os_permission_and_local_policy_are_independent_required_gates() = runBlocking {
        val (authority, collector) = notifications()
        collector.onPosted(raw())
        var allowed = false
        var queried = false
        val primitive = NotificationsReadPrimitive({ queried = true; collector }, authority) { allowed }
        assertThrows(CapabilityDenied::class.java) { runBlocking { invoke(primitive) } }
        assertFalse(queried)
        allowed = true
        assertEquals(1, invoke(primitive).size)
        authority.localController().revoke(2u)
        assertThrows(CapabilityDenied::class.java) { runBlocking { invoke(primitive) } }
        Unit
    }

    @Test
    fun notification_metadata_policy_redacts_content_and_missing_listener_is_denied() = runBlocking {
        val (authority, collector) = notifications(fieldAccess = NotificationFieldAccess.METADATA)
        collector.onPosted(raw())
        val records = invoke(NotificationsReadPrimitive({ collector }, authority) { true })
        assertEquals(JsonValue.JNull, JsonFields.field(records.single(), "text"))
        assertThrows(CapabilityDenied::class.java) {
            runBlocking { invoke(NotificationsReadPrimitive({ null }, authority) { true }) }
        }
        Unit
    }

    @Test
    fun notification_os_revocation_during_capture_does_not_return_cached_content() = runBlocking {
        val (authority, collector) = notifications()
        collector.onPosted(raw())
        var allowed = true
        val revokingCollector = object : NotificationCollector by collector {
            override suspend fun captureOnDemand(request: OnDemandNotificationRead): NotificationCaptureResult {
                return collector.captureOnDemand(request).also { allowed = false }
            }
        }
        val primitive = NotificationsReadPrimitive({ revokingCollector }, authority) { allowed }
        assertThrows(CapabilityDenied::class.java) { runBlocking { invoke(primitive) } }
        Unit
    }

    private fun notifications(granted: Boolean = true, fieldAccess: NotificationFieldAccess = NotificationFieldAccess.CONTENT): Pair<PersistentNotificationPolicyAuthority, AndroidNotificationCollector> {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val policy = NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("com.mail"), fieldAccess, 1u)
        authority.localController().apply(policy, 1u, granted)
        val collector = AndroidNotificationCollector(authority)
        collector.applyPolicyBlocking(policy)
        return authority to collector
    }

    private fun raw() = RawNotification("com.mail", "mail", "Mail", "subject", "secret", "channel", 1700)

    private suspend fun invoke(primitive: KernelPrimitiveProvider, input: String = "{}"): List<JsonValue.JObject> =
        JsonFields.objects(JsonFields.obj(Json.parse(primitive.invoke(context, input.encodeToByteArray()).decodeToString())), "records")
}
