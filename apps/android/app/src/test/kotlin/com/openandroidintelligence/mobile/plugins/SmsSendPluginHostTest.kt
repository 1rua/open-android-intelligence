package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.kernel.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实签名包、Rust WASM、PluginKernel、宿主最终栅栏到替身系统端口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsSendPluginHostTest {
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
