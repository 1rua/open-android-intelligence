package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.remote.facade.PhoneControlBus
import com.openandroidintelligence.gateway.remote.facade.ControlRequestParser
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.kernel.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实签名包、Rust WASM、PluginKernel、宿主最终栅栏到替身系统端口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsSendPluginHostTest {
    @Test fun oneLocalApprovalCannotLetSignedWasmSendTwiceAndRestartKeepsTheConsumedFact() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.sms.send")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        var systemSends = 0
        var primitiveCalls = 0
        var confirmations = 0
        var secondDenial: String? = null
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.send"
            override fun confirmationBinding(input: ByteArray) = "sim:2:card-for-repeat-test"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                primitiveCalls++
                try { requireNotNull(context.beforeSystemEffect).invoke(input) }
                catch (failure: CapabilityDenied) { secondDenial = failure.primitive; throw failure }
                val sent = CompletableDeferred<Int>()
                // 替身系统立即同步返回发送成功；第二次调用也能立即成功，故只能由授权边界阻止。
                systemSends++; sent.complete(android.app.Activity.RESULT_OK)
                assertEquals(android.app.Activity.RESULT_OK, sent.await())
                return """{"status":"sent","segmentCount":1,"sentSegments":1,"failedSegments":0}""".encodeToByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
            val trust = DeveloperTrustMode()
            val host = thirdPartyOnlyHost(app, grants, trust, NativePluginLoader(trust), "sms-repeat-test")
            host.installStore.install(TestAlpPackages.compiledSmsPackage(payloadOverride = RepeatedSmsSendWasm.bytes()))
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
            host.enable(SMS_PLUGIN_ID, true)
            val entry = host.entries(SMS_PLUGIN_ID).single { it.id.endsWith(".send") }
            val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://repeat-sms.example"),
                "sms-repeat-account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
            val remote = RemoteCapabilityBinding(access) { true }
            val connection = grants.attachRemoteConnection(remote)
            try {
                val parameters = Json.parse("""{"subscriptionId":2,"recipient":"+15550009","text":"不得重复发送的正文"}""")
                val persistence = InMemoryOutboxPersistence()
                fun restoreLedger() = ControlMetadataStore(AesGcmEncryptedBlobStore(persistence, SecretKeySpec(ByteArray(32) { 17 }, "AES")))
                val ledger = restoreLedger()
                val executor = object : PhoneControlExecutor {
                    override fun requireCurrentAccess(access: ControlAccessBinding) = Unit
                    override fun capabilities(access: ControlAccessBinding) = ControlValue.Object(emptyMap())
                    override fun cancel(scope: ControlRequestScope) = Unit
                    override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
                        val id = context.scope.requestId
                        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                            host.requestRemoteAuthorization(id, entry, parameters, remote, context.parameterDigest))
                        confirmations++
                        host.approveRemoteRequest(id, entry, parameters, remote, context.parameterDigest)
                        host.invokeRemote(entry, parameters, id, remote, context.parameterDigest,
                            beforeSystemEffect = context.consumeSmsSendEffect)
                        error("恶意 WASM 的第二次系统调用必须被拒绝")
                    }
                }
                val bus = PhoneControlBus(this@runBlocking, ledger, executor)
                val task = bus.create(access)
                val request = ControlRequestParser.parse(Json.canonical(Json.of(mapOf("request_id" to "sms-repeat-system-0001",
                    "op" to "plugin.invoke", "args" to mapOf("pluginId" to SMS_PLUGIN_ID, "capabilityId" to entry.key,
                        "method" to "send", "params" to parameters)))))
                bus.submit(access, task, request)
                val active = coroutineContext[Job]!!.children.toList()
                withTimeout(5_000) { active.forEach { it.join() } }
                assertEquals(1, confirmations)
                assertEquals(2, primitiveCalls)
                assertEquals(1, systemSends)
                assertEquals("SMS_EFFECT_ALREADY_CONSUMED", secondDenial)
                val row = ledger.transaction { it.values.single { item -> item.requestId == request.requestId } }
                assertTrue(row.executionFence)
                assertEquals(ControlRequestState.OUTCOME_UNKNOWN, row.state)
                assertEquals(ControlExecutionStatus.UNKNOWN, row.execution)
                val restored = restoreLedger().transaction { it.getValue(row.key) }
                assertTrue(restored.executionFence)
                assertEquals(ControlRequestState.OUTCOME_UNKNOWN, restored.state)
                assertFalse(row.toString().contains("不得重复发送的正文"))
                bus.submit(access, task, request)
                val replay = coroutineContext[Job]!!.children.toList()
                withTimeout(5_000) { replay.forEach { it.join() } }
                assertEquals(1, confirmations); assertEquals(1, systemSends)
            } finally { connection.close() }
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test fun sendBindsExactParametersSubscriptionAndFinalLocalFence() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val old = KernelPrimitiveRegistry.provider("kernel.sms.send")
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        var subscription = "sim:2:card-a"
        var sends = 0
        var beforeFinal: () -> Unit = {}
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.send"
            override fun confirmationBinding(input: ByteArray) = subscription
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                beforeFinal()
                requireNotNull(context.beforeSystemEffect).invoke(input)
                sends++
                return """{"status":"sent","segmentCount":1,"sentSegments":1,"failedSegments":0}""".encodeToByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
            val trust = DeveloperTrustMode()
            val host = thirdPartyOnlyHost(app, grants, trust, NativePluginLoader(trust), "sms-send-test")
            host.installStore.install(TestAlpPackages.compiledSmsPackage())
            val kernel = PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation)
            host.attach(kernel)
            val entry = host.entries(SMS_PLUGIN_ID).single { it.id.endsWith(".send") }
            host.enable(SMS_PLUGIN_ID, true)
            val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://sms.example"),
                "sms-account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
            val remote = RemoteCapabilityBinding(access) { true }
            val registration = grants.attachRemoteConnection(remote)
            try {
                val params = Json.parse("""{"subscriptionId":2,"recipient":"+15550001","text":"敏感正文"}""")
                host.setCapabilityPolicy(SMS_PLUGIN_ID, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
                assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestRemoteAuthorization("sms-request-a", entry, params, remote))
                host.approveRemoteRequest("sms-request-a", entry, params, remote)
                var fences = 0
                assertTrue(host.invokeRemote(entry, params, "sms-request-a", remote,
                    beforeSystemEffect = { fences++ }).decodeToString().contains("sent"))
                assertEquals(1, sends); assertEquals(1, fences)
                val changed = Json.parse("""{"subscriptionId":2,"recipient":"+15550002","text":"敏感正文"}""")
                assertTrue(runCatching { host.invokeRemote(entry, changed, "sms-request-a", remote, beforeSystemEffect = {}) }.isFailure)
                assertEquals(1, sends)
                assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestRemoteAuthorization("sms-request-b", entry, params, remote))
                subscription = "sim:2:card-b"
                assertTrue(runCatching { host.approveRemoteRequest("sms-request-b", entry, params, remote) }.isFailure)
                subscription = "sim:2:card-a"
                host.requestRemoteAuthorization("sms-request-c", entry, params, remote)
                host.approveRemoteRequest("sms-request-c", entry, params, remote)
                beforeFinal = { kernel.emergencyStop("sms-stop") }
                assertTrue(runCatching { host.invokeRemote(entry, params, "sms-request-c", remote, beforeSystemEffect = {}) }.isFailure)
                assertEquals(1, sends)
            } finally { registration.close() }
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (old != null) KernelPrimitiveRegistry.register(old)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
