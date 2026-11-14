package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.gateway.remote.facade.ControlRequestParser
import com.openandroidintelligence.gateway.remote.facade.PhoneControlBus
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实签名短信插件正常返回结果，经生产结果映射、Bus 与加密账本形成可查询终态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsSendOutcomeHostBusTest {
    @Test fun normalUnknownSmsResultKeepsUnknownReceiptAndNeverResendsOnReplayOrRestart() = verify("outcome_unknown")

    @Test fun sentSmsResultKeepsCompletedReceiptAndOneSystemSend() = verify("sent")

    private fun verify(providerStatus: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val oldProvider = KernelPrimitiveRegistry.provider("kernel.sms.send")
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        var systemSends = 0
        var confirmations = 0
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.send"
            override fun confirmationBinding(input: ByteArray) = "sim:2:card-outcome-test"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                requireNotNull(context.beforeSystemEffect).invoke(input)
                systemSends++
                return Json.canonical(Json.of(mapOf("status" to providerStatus, "segmentCount" to 2,
                    "sentSegments" to 1, "failedSegments" to 0, "failureCode" to "OUTCOME_UNKNOWN"))).encodeToByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
            val trust = DeveloperTrustMode()
            val host = thirdPartyOnlyHost(app, grants, trust, NativePluginLoader(trust), "sms-outcome-$providerStatus")
            host.installStore.install(TestAlpPackages.compiledSmsPackage())
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
            host.enable(SMS_PLUGIN_ID, true)
            val entry = host.entries(SMS_PLUGIN_ID).single { it.id.endsWith(".send") }
            val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://sms-outcome.example"),
                "sms-account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
            val remote = RemoteCapabilityBinding(access) { true }
            val registration = grants.attachRemoteConnection(remote)
            try {
                val params = Json.parse("""{"subscriptionId":2,"recipient":"+15550001","text":"短信私密正文"}""")
                val persistence = InMemoryOutboxPersistence()
                fun ledger() = ControlMetadataStore(AesGcmEncryptedBlobStore(persistence, SecretKeySpec(ByteArray(32) { 23 }, "AES")))
                val firstLedger = ledger()
                val executor = object : PhoneControlExecutor {
                    override fun requireCurrentAccess(access: ControlAccessBinding) = Unit
                    override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
                    override fun cancel(scope: ControlRequestScope) = Unit
                    override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                        val id = context.scope.requestId
                        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                            host.requestRemoteAuthorization(id, entry, params, remote, context.parameterDigest))
                        confirmations++
                        host.approveRemoteRequest(id, entry, params, remote, context.parameterDigest)
                        val output = host.invokeRemote(entry, params, id, remote, context.parameterDigest,
                            beforeSystemEffect = context.consumeSmsSendEffect)
                        return phonePluginResult(entry, Json.parse(output.decodeToString()))
                    }
                }
                val bus = PhoneControlBus(this@runBlocking, firstLedger, executor)
                val task = bus.create(access)
                val request = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "sms-outcome-request-01",
                    "op" to "plugin.invoke", "args" to mapOf("pluginId" to SMS_PLUGIN_ID, "capabilityId" to entry.key,
                        "method" to "send", "params" to params)))))
                bus.submit(access, task, request)
                val running = coroutineContext[Job]!!.children.toList()
                withTimeout(5_000) { running.forEach { it.join() } }
                val unknown = providerStatus == "outcome_unknown"
                val row = firstLedger.transaction { it.values.single { saved -> saved.requestId == request.requestId } }
                assertTrue(row.executionFence)
                assertEquals(if (unknown) ControlRequestState.OUTCOME_UNKNOWN else ControlRequestState.COMPLETED, row.state)
                assertEquals(if (unknown) ControlExecutionStatus.UNKNOWN else ControlExecutionStatus.SUCCEEDED, row.execution)
                fun assertStatus(targetBus: PhoneControlBus, requestId: String, resultAvailable: Boolean) {
                    val query = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to requestId,
                        "op" to "request.status", "args" to mapOf("targetRequestId" to request.requestId, "targetTaskSessionId" to task)))))
                    val answer = JsonFields.obj(targetBus.submit(access, "control-bus", query))!!
                    val metadata = JsonFields.obj(JsonFields.field(answer, "result"))!!
                    assertEquals(if (unknown) "outcome_unknown" else "completed", JsonFields.string(metadata, "state"))
                    assertEquals(if (unknown) "unknown" else "succeeded",
                        JsonFields.string(JsonFields.obj(JsonFields.field(metadata, "execution")), "status"))
                    assertEquals(resultAvailable, JsonFields.bool(metadata, "resultAvailable"))
                    assertFalse(Json.canonical(metadata).contains("短信私密正文"))
                }
                assertStatus(bus, "sms-status-request-01", !unknown)
                val receipt = JsonFields.obj(bus.receipt(access, task, request.requestId))!!
                assertEquals(!unknown, JsonFields.bool(receipt, "resultAvailable"))
                if (unknown) assertNull(JsonFields.field(receipt, "result"))
                bus.submit(access, task, request)
                val replay = coroutineContext[Job]!!.children.toList()
                withTimeout(5_000) { replay.forEach { it.join() } }
                assertEquals(1, systemSends); assertEquals(1, confirmations)
                val restoredLedger = ledger()
                val restarted = PhoneControlBus(this@runBlocking, restoredLedger, executor)
                assertStatus(restarted, "sms-status-restart-01", false)
                assertThrows(ControlRequestException::class.java) { restarted.create(access, task) }
                assertEquals(row.state, restoredLedger.transaction { it.getValue(row.key).state })
                assertEquals(1, systemSends)

                // 同样的 JSON 不能更改其他插件或读取能力的控制终态。
                val rawUnknown = Json.parse("""{"status":"outcome_unknown"}""")
                assertEquals(PhoneControlCompletion.SUCCEEDED,
                    phonePluginResult(entry.copy(id = "org.openandroidintelligence.sms.query", primitives = setOf("kernel.sms.read")), rawUnknown).completion)
                assertEquals(PhoneControlCompletion.SUCCEEDED,
                    phonePluginResult(entry.copy(identity = entry.identity.copy(pluginId = "org.example.other")), rawUnknown).completion)
            } finally { registration.close() }
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (oldProvider != null) KernelPrimitiveRegistry.register(oldProvider)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        }
    }
}
