package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.control.remote.ControlAccessBinding
import com.openandroidintelligence.control.remote.ControlOrigin
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.control.remote.ParsedControlRequest
import com.openandroidintelligence.control.remote.RemoteControlPrincipal
import com.openandroidintelligence.gateway.remote.facade.ControlRequestParser
import com.openandroidintelligence.gateway.remote.facade.PhoneControlBus
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.TestDocumentKeys
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteNotificationHostBusTest {
    private val access = ControlAccessBinding(
        RemoteControlPrincipal(ControlOrigin.fromCanonical("https://notifications.example"), "notification-account",
            "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)),
        authorizationEpoch = 1, connectionId = "notification-connection", connectionGeneration = 1,
    )
    private val systemQueries = CopyOnWriteArrayList<Pair<String, JsonValue>>()
    private var confirmations = 0
    private var rejectConfirmation = false
    private var providerFailure: String? = null
    private var providerUnavailable: String? = null

    @Test
    fun approvedNotificationReturnsItsRecordOnceAndANewRequestStillNeedsConsent() = withDataHost { _, bus ->
        verifyReceipts(bus)
    }

    @Test
    fun automaticNotificationReadCompletesWithoutLocalConfirmation() = withDataHost { host, bus ->
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, PairingGrantCapabilities.NOTIFICATIONS,
            CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        val task = bus.create(access)
        val request = notificationQuery("notification-automatic-01")
        bus.submit(access, task, request)
        coroutineContext.job.children.toList().joinAll()

        val receipt = JsonFields.obj(bus.receipt(access, task, request.requestId))
        assertEquals("completed", JsonFields.string(receipt, "state"))
        assertEquals(true, JsonFields.bool(receipt, "resultAvailable"))
        assertEquals(0, confirmations)
        assertEquals(1, systemQueries.size)
    }

    @Test
    fun notificationResultsHideTheirBodyAfterPolicyOrSystemPermissionIsRevoked() = withDataHost { host, bus ->
        val task = bus.create(access)
        val policy = CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY)
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, PairingGrantCapabilities.NOTIFICATIONS, policy)

        suspend fun read(id: String) {
            bus.submit(access, task, notificationQuery(id))
            coroutineContext.job.children.toList().joinAll()
            assertEquals(true, JsonFields.bool(JsonFields.obj(bus.receipt(access, task, id)), "resultAvailable"))
        }

        fun assertOnlyReceipt(id: String) {
            val receipt = JsonFields.obj(bus.receipt(access, task, id))
            assertEquals("completed", JsonFields.string(receipt, "state"))
            assertEquals(false, JsonFields.bool(receipt, "resultAvailable"))
            assertNull(JsonFields.field(receipt, "result"))
            assertFalse(Json.canonical(bus.history(access, task)).contains("仅由通知提供者返回的标记"))
        }

        val first = "notification-policy-revoked"
        read(first)
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, PairingGrantCapabilities.NOTIFICATIONS, policy.copy(enabled = false))
        assertOnlyReceipt(first)

        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, PairingGrantCapabilities.NOTIFICATIONS, policy)
        val second = "notification-system-revoked"
        read(second)
        providerUnavailable = "NOTIFICATION_ACCESS_REQUIRED"
        assertOnlyReceipt(second)
        providerUnavailable = null
        assertOnlyReceipt(second)
        assertEquals(0, confirmations)
    }

    @Test
    fun samePrincipalAndEpochCanReadNotificationAfterReconnectUntilPermissionIsRevoked() = withPhoneHost { host, phone ->
        host.setCapabilityPolicy(NOTIFICATION_PLUGIN_ID, PairingGrantCapabilities.NOTIFICATIONS,
            CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        val bus = phone.bus
        val request = notificationQuery("notification-reconnected-01")
        val task = phone.register(access) { true }.use {
            bus.create(access).also { task ->
                assertEquals(true, JsonFields.bool(submitAndRead(bus, task, request), "resultAvailable"))
            }
        }
        val successor = access.copy(connectionId = "notification-successor", connectionGeneration = 2)
        val otherKey = successor.copy(principal = successor.principal.copy(controllerKeyFingerprint = "sha256:" + "c".repeat(64)))
        val otherEpoch = successor.copy(authorizationEpoch = access.authorizationEpoch + 1)
        for (other in listOf(otherKey, otherEpoch)) {
            phone.register(other) { true }.use {
                val failure = runCatching { bus.history(other, task) }.exceptionOrNull() as? ControlRequestException
                assertEquals("CONTROL_TASK_NOT_FOUND", failure?.code)
            }
        }

        phone.register(successor) { true }.use {
            val receipt = JsonFields.obj(bus.receipt(successor, task, request.requestId))
            assertEquals(true, JsonFields.bool(receipt, "resultAvailable"))
            val record = JsonFields.objects(JsonFields.obj(JsonFields.field(receipt, "result")), "records").single()
            assertEquals("仅由通知提供者返回的标记", JsonFields.string(record, "text"))

            providerUnavailable = "NOTIFICATION_ACCESS_REQUIRED"
            assertFalse(Json.canonical(bus.history(successor, task)).contains("仅由通知提供者返回的标记"))
            val minimalReceipt = JsonFields.obj(bus.receipt(successor, task, request.requestId))
            assertEquals("completed", JsonFields.string(minimalReceipt, "state"))
            assertEquals(false, JsonFields.bool(minimalReceipt, "resultAvailable"))
            assertNull(JsonFields.field(minimalReceipt, "result"))
        }
        assertEquals(1, systemQueries.size)
        assertEquals(0, confirmations)
    }

    @Test
    fun callLogReadOnlyAndAutomaticReadWithoutConfirmation() = withDataHost(BundledPlugin.CALL_LOG) { host, bus ->
        val task = bus.create(access)
        for ((index, mode) in listOf(CapabilityAuthorizationMode.READ_ONLY, CapabilityAuthorizationMode.AUTOMATIC).withIndex()) {
            host.setCapabilityPolicy(CALL_LOG_PLUGIN_ID, "$CALL_LOG_PLUGIN_ID.query@1.0.0", CapabilityPolicy(mode = mode))
            val request = dataRequest("call-log-mode-query-$index", CALL_LOG_PLUGIN_ID)
            assertEquals(true, JsonFields.bool(submitAndRead(bus, task, request), "resultAvailable"))
        }
        assertEquals(2, systemQueries.size)
        assertEquals(0, confirmations)
    }

    @Test
    fun automaticSmsReadKeepsScopeBackgroundAndWriteConfirmation() = withDataHost(BundledPlugin.SMS) { host, bus ->
        host.setBackgroundEnabled(SMS_PLUGIN_ID, false)
        assertFalse(host.isBackgroundEnabled(SMS_PLUGIN_ID))
        val policy = CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC,
            allowedSources = setOf("+15550001"), includeContent = false)
        host.setCapabilityPolicy(SMS_PLUGIN_ID, PairingGrantCapabilities.SMS, policy)
        val task = bus.create(access)
        val read = submitAndRead(bus, task, dataRequest("sms-automatic-query-01", SMS_PLUGIN_ID))
        assertEquals(true, JsonFields.bool(read, "resultAvailable"))
        val parameters = JsonFields.obj(systemQueries.single().second)
        assertEquals(listOf("+15550001"), JsonFields.strings(parameters, "senders"))
        assertEquals(false, JsonFields.bool(parameters, "includeContent"))

        val blocked = submitAndRead(bus, task, dataRequest("sms-outside-scope-01", SMS_PLUGIN_ID,
            params = """{"senders":["+15550002"]}"""))
        assertEquals(false, JsonFields.bool(blocked, "resultAvailable"))

        val app = ApplicationProvider.getApplicationContext<Application>()
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        try {
            val locked = submitAndRead(bus, task, dataRequest("sms-background-denied-01", SMS_PLUGIN_ID))
            assertEquals("BACKGROUND_PERMISSION_REQUIRED", JsonFields.string(JsonFields.obj(JsonFields.field(locked, "error")), "code"))
        } finally { keyguard.setKeyguardLocked(false) }
        assertEquals(1, systemQueries.size)
        assertEquals(0, confirmations)

        host.setCapabilityPolicy(SMS_PLUGIN_ID, "$SMS_PLUGIN_ID.cancel@1.0.0", policy)
        rejectConfirmation = true
        val write = submitAndRead(bus, task, dataRequest("sms-write-cancel-01", SMS_PLUGIN_ID, "cancel",
            """{"operation":"cancel","jobId":"fixture-job"}"""))
        assertEquals("LOCAL_USER_REJECTED", JsonFields.string(JsonFields.obj(JsonFields.field(write, "error")), "code"))
        assertEquals("not_started", JsonFields.string(JsonFields.obj(JsonFields.field(write, "execution")), "status"))
        assertEquals(1, confirmations)
    }

    private fun withDataHost(plugin: BundledPlugin = BundledPlugin.NOTIFICATIONS,
        verify: suspend CoroutineScope.(ProductionPluginHost, PhoneControlBus) -> Unit) = withPhoneHost(plugin) { host, phone ->
        phone.register(access) { true }.use { verify(host, phone.bus) }
    }

    private fun withPhoneHost(plugin: BundledPlugin = BundledPlugin.NOTIFICATIONS,
        verify: suspend CoroutineScope.(ProductionPluginHost, RemotePhoneControlComposition) -> Unit) = runBlocking {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<Application>()
        // 初始化进程生命周期并启动前台界面，为各插件建立相同的前台读取条件。
        requireNotNull(app.contentResolver.acquireContentProviderClient("${app.packageName}.androidx-startup")).close()
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use {
            val provider = object : KernelPrimitiveProvider {
                override val primitiveId = when (plugin) {
                    BundledPlugin.NOTIFICATIONS -> "kernel.notifications.read"
                    BundledPlugin.CALL_LOG -> "kernel.call-log.read"
                    BundledPlugin.SMS -> "kernel.sms.read"
                    else -> error("测试仅覆盖数据插件")
                }
                override fun availability(): String? = providerUnavailable
                override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                    providerFailure?.let { throw com.openandroidintelligence.kernel.CapabilityDenied(it) }
                    systemQueries.add(context.accountId to Json.parse(input.decodeToString()))
                    return """{"records":[{"id":"notification-42","text":"仅由通知提供者返回的标记"}]}""".encodeToByteArray()
                }
            }
            val previousProvider = KernelPrimitiveRegistry.provider(provider.primitiveId)
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            KernelPrimitiveRegistry.register(provider)
            try {
                val audit = AndroidAuditStore()
                val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
                val trust = DeveloperTrustMode()
                val host = thirdPartyOnlyHost(app, grants, trust, NativePluginLoader(trust), "notification-bus-test")
                host.installStore.install(TestAlpPackages.compiledBundledPackage(plugin))
                host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                    host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                    mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
                host.enable(plugin.pluginId, true)
                host.grant(plugin.pluginId, true)

                withTimeout(15_000) {
                    RemotePhoneControlComposition(app, this, host, grants, metadataKeyProvider = TestDocumentKeys,
                        confirmLocally = { _, _, _ ->
                            confirmations++
                            !rejectConfirmation && confirmations != 2
                        }).use { phone ->
                        verify(host, phone)
                    }
                }
            } finally {
                KernelPrimitiveRegistry.unregister(provider)
                previousProvider?.let(KernelPrimitiveRegistry::register)
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }
    }

    private fun notificationQuery(id: String) = dataRequest(id, NOTIFICATION_PLUGIN_ID)

    private fun dataRequest(id: String, pluginId: String, operation: String = "query", params: String = """{"limit":1}""") =
        ControlRequestParser.parse("""{
            "request_id":"$id","op":"plugin.invoke","args":{
                "pluginId":"$pluginId","capabilityId":"$pluginId.$operation@1.0.0",
                "method":"$operation","params":$params}
        }""")

    private suspend fun CoroutineScope.submitAndRead(bus: PhoneControlBus, task: String, request: ParsedControlRequest): JsonValue.JObject {
        bus.submit(access, task, request)
        coroutineContext.job.children.toList().joinAll()
        return requireNotNull(JsonFields.obj(bus.receipt(access, task, request.requestId)))
    }

    private suspend fun CoroutineScope.verifyReceipts(bus: PhoneControlBus) {
        val task = bus.create(access)
        suspend fun receiptAfterWorkersFinish(id: String): JsonValue.JObject {
            coroutineContext.job.children.toList().joinAll()
            return requireNotNull(JsonFields.obj(bus.receipt(access, task, id)))
        }

        val approved = notificationQuery("notification-approved-01")
        bus.submit(access, task, approved)
        val completed = receiptAfterWorkersFinish(approved.requestId)
        assertEquals(Json.canonical(completed), "completed", JsonFields.string(completed, "state"))
        assertEquals("succeeded", JsonFields.string(JsonFields.obj(JsonFields.field(completed, "execution")), "status"))
        assertEquals(true, JsonFields.bool(completed, "resultAvailable"))
        val result = JsonFields.obj(JsonFields.field(completed, "result"))
        val record = JsonFields.objects(result, "records").single()
        assertEquals("仅由通知提供者返回的标记", JsonFields.string(record, "text"))
        assertEquals(1, confirmations)
        assertEquals(listOf(access.principal.accountId to Json.parse("""{"limit":1}""")), systemQueries)

        assertEquals(completed, bus.submit(access, task, approved))
        assertEquals(completed, receiptAfterWorkersFinish(approved.requestId))
        assertEquals(1, confirmations)
        assertEquals(1, systemQueries.size)

        val rejected = notificationQuery("notification-rejected-02")
        bus.submit(access, task, rejected)
        val denied = receiptAfterWorkersFinish(rejected.requestId)
        assertEquals(Json.canonical(denied), "denied", JsonFields.string(denied, "state"))
        assertEquals("not_started", JsonFields.string(JsonFields.obj(JsonFields.field(denied, "execution")), "status"))
        assertEquals("LOCAL_USER_REJECTED", JsonFields.string(JsonFields.obj(JsonFields.field(denied, "error")), "code"))
        assertEquals(false, JsonFields.bool(denied, "resultAvailable"))
        assertNull(JsonFields.field(denied, "result"))
        assertEquals(2, confirmations)
        assertEquals(1, systemQueries.size)

        // 系统权限在授权后变化时，让桌面收到可诊断的固定错误，且不泄露未知异常的正文。
        for ((reason, code) in listOf("NOTIFICATION_LISTENER_UNAVAILABLE" to "NOTIFICATION_LISTENER_UNAVAILABLE",
            "私有通知标记" to "CAPABILITY_DENIED")) {
            providerFailure = reason
            val operation = notificationQuery("notification-failure-${confirmations}")
            bus.submit(access, task, operation)
            val failed = receiptAfterWorkersFinish(operation.requestId)
            assertEquals(code, JsonFields.string(JsonFields.obj(JsonFields.field(failed, "error")), "code"))
            assertFalse(Json.canonical(failed).contains("私有通知标记"))
        }
    }
}
