package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentProvider
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.core.model.*
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.ed25519WirePublicKey
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.TestDocumentKeys
import com.openandroidintelligence.notification.control.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.shadow.api.Shadow

/** Signed ALP -> Android grants/NLS -> publisher -> real signed Hermes HTTP -> native tool -> kernel/WASM -> notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class NotificationCapabilityHttpTest {
    @Test fun notificationReadingRequiresEveryGateAndResynchronizesAfterRevocationAndReconnect() = runBlocking {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it,"gateway-contract").isDirectory }
        val plugin = File(System.getenv("HERMES_PLUGIN_ROOT") ?: File(root,".hermes-gateway-plugin").path)
        val log = File.createTempFile("capability-http-", ".log")
        val process = ProcessBuilder(System.getenv("OAI_HERMES_PYTHON") ?: "python3",File(plugin,"tests/device_capability_fixture.py").path,
            "--contract-root",System.getenv("OPEN_ANDROID_GATEWAY_CONTRACT_ROOT") ?: File(root,"gateway-contract").path)
            .directory(plugin).redirectError(log).start()
        val input = process.inputStream.bufferedReader()
        val output = process.outputStream.bufferedWriter()
        fun control(operation: String, extra: Map<String,Any?> = emptyMap()): JsonValue.JObject {
            output.write(Json.canonical(Json.of(mapOf("operation" to operation)+extra)));output.newLine();output.flush()
            val line = CompletableFuture.supplyAsync { input.readLine() }.get(15,TimeUnit.SECONDS)
            return JsonFields.obj(Json.parse(line ?: error(log.readText())))!!
        }
        val executionScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var listener: NotificationListenerService? = null
        val listenerClass = Class.forName(LISTENER).asSubclass(NotificationListenerService::class.java)
        try {
            val baseUrl = CompletableFuture.supplyAsync { input.readLine() }.get(20,TimeUnit.SECONDS) ?: error(log.readText())
            check(baseUrl.startsWith("http://127.0.0.1:")) { log.readText() }
            val factory = GatewayConnectionFactory(::androidConnection)
            val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val auth = GatewayAuthClient(GatewayTransport(GatewayProfile("pre","pre","pre",baseUrl),factory),"notification_install","test",34)
            val negotiation = auth.negotiate("notification_negotiation")
            val session = auth.loginWithPassword(negotiation.negotiationId,"alice","device-fixture-only".toCharArray(),
                "Notification regression",ed25519WirePublicKey(keys.public))
            val profile = GatewayProfile(session.accountId,session.deviceId,session.sessionId,baseUrl,accessToken=session.accessToken)
            var loseClaimReply = true
            var loseResultReply = true
            val wire = GatewayTransport(profile,factory)
            val interruptedWire = object : GatewayByteTransport by wire {
                override suspend fun execute(request: WireRequest): WireResponse {
                    val response = wire.execute(request)
                    // 服务器已接受后丢掉回包，检验真实 HTTP 收据与结果重试的幂等性。
                    if (response.status == 200 && request.target.endsWith("/claim") && loseClaimReply) {
                        loseClaimReply = false
                        throw IOException("领取回包丢失夹具")
                    }
                    if (response.status == 200 && request.target.endsWith("/result") && loseResultReply) {
                        loseResultReply = false
                        throw IOException("结果回包丢失夹具")
                    }
                    return response
                }
            }
            val http = GatewayHttpClient(profile,interruptedWire,{ bytes ->
                Signature.getInstance("Ed25519").run { initSign(keys.private);update(bytes);sign() }
            },InMemoryEventCursorStore(),webSocketTransport=null)
            val binding = PairingGrantBinding(baseUrl,session.accountId,"notification_install")
            resetNotificationComposition()
            installRuntimeProvider("com.openandroidintelligence.notification.host.NotificationControlBootstrapProvider")
            installRuntimeProvider("com.openandroidintelligence.device.primitives.PrimitiveBootstrapProvider")
            assertNotNull(KernelPrimitiveRegistry.provider("kernel.notifications.read"))
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(),audit).apply { bind(binding) }
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"notification_install")
            host.installStore.installRoot.deleteRecursively()
            val kernel = PluginKernel(HostEnvelope(host.hostCapabilities),PhoneLimits(host.phoneCapabilities),host.runtimes,
                audit,trust,host.nativeLoader,host.selector,{ grants.currentKernelGrant(it) },host::mediate)
            host.attach(kernel)
            var publisher = CapabilityPublisher(context,http,binding.storageKey,session.grantRevision,
                { grants.state.value!!.revision },host,{ error("Unexpected foreign grant") },TestDocumentKeys)
            suspend fun currentStatus(): String {
                val data = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/pairings/current")).requireData("LOOKUP")
                return JsonFields.string(JsonFields.objects(data,"capabilities").first { JsonFields.string(it,"capabilityId") == ID },"androidStatus")!!
            }
            assertEquals(true,JsonFields.bool(control("switch",mapOf("capabilityId" to ID,"enabled" to true)),"ok"))
            publisher.sync();assertEquals("PLUGIN_NOT_INSTALLED",currentStatus())
            // Exercise the shipped APK package when supplied; ordinary source-only builds use the same signed installer.
            val notificationsPackage = try { readBundledPluginPackage(context, BundledPlugin.NOTIFICATIONS) }
                catch (_: BundledPluginUnavailable) {
                    check(System.getenv("OAI_BUNDLED_PLUGINS_DIR").isNullOrBlank()) { "Configured notification bundle missing from APK assets" }
                    TestAlpPackages.compiledNotificationsPackage()
                }
            host.installStore.installBundledPlugin(BundledPlugin.NOTIFICATIONS, notificationsPackage);host.reload()
            publisher.sync();assertEquals("PLUGIN_DISABLED",currentStatus())
            assertFalse(host.isEnabled(PLUGIN))
            assertTrue(host.authorizedBindings().isEmpty())
            val entry = host.entries(PLUGIN).single()
            host.enable(PLUGIN,true)
            publisher.sync();assertEquals("LOCAL_GRANT_REQUIRED",currentStatus())
            host.grant(PLUGIN,true)

            // The same runtime-only composition used by the production APK.
            val manager = context.getSystemService(NotificationManager::class.java)
            val component = ComponentName(context,listenerClass)
            shadowOf(manager).setNotificationListenerAccessGranted(component,false)
            publisher.sync();assertEquals("ANDROID_PERMISSION_REQUIRED",currentStatus())
            shadowOf(manager).setNotificationListenerAccessGranted(component,true)
            publisher.sync();assertEquals("LOCAL_GRANT_REQUIRED",currentStatus())
            val policyPort = NotificationControlRegistry.policyPort()
            val authorized = policyPort.update(NotificationPolicyUpdate(policyPort.snapshot().revision,
                granted=true,packageIds=listOf("com.mail"),fieldAccess=NotificationFieldAccess.CONTENT,mode=NotificationDeliveryMode.ON_DEMAND))
            assertTrue(authorized is NotificationPolicyUpdateOutcome.Accepted)
            publisher.sync();assertEquals("PROVIDER_UNAVAILABLE",currentStatus())
            listener = Robolectric.buildService(listenerClass).create().get()
            val shadow = Shadow.extract<ShadowNotificationListenerService>(listener)
            shadow.addActiveNotification("com.mail",1,Notification.Builder(context,"mail").setContentTitle("Mail").setContentText("permitted message").build())
            shadow.addActiveNotification("com.bank",2,Notification.Builder(context,"bank").setContentText("excluded secret").build())
            listener.onListenerConnected()
            publisher.sync();assertEquals("READY",currentStatus())

            var foreground = false
            val driver = DeviceExecutionDriver(context,executionScope,http,host,session.accountId,session.deviceId,session.pairingGeneration,
                binding.storageKey,{ publisher.grantRevision },{ foreground },TestDocumentKeys)
            suspend fun invoke(): JsonValue.JObject {
                control("start",mapOf("capabilityId" to ID,"parameters" to mapOf("limit" to 10)))
                var requestId: String? = null
                withTimeout(5_000) { while(requestId == null) { requestId=JsonFields.string(control("pending"),"requestId");if(requestId == null) delay(20) } }
                driver.accept(requestId!!)
                val result = withContext(Dispatchers.IO) { control("result") }
                withTimeout(5_000) { executionScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
                return result
            }
            val result = invoke()
            assertEquals("succeeded",JsonFields.string(result,"outcome"))
            assertTrue(Json.canonical(result).contains("permitted message"))
            assertFalse(Json.canonical(result).contains("excluded secret"))
            assertFalse(loseClaimReply)
            assertFalse(loseResultReply)
            foreground = true
            // An installed but damaged package cannot be diagnosed as absent, or retain a callable binding.
            val payload = File(host.installStore.installRoot, "$PLUGIN/payload/notifications.wasm")
            val verifiedPayload = payload.readBytes()
            payload.writeBytes(verifiedPayload.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            host.reload()
            assertEquals("PLUGIN_PACKAGE_INVALID", host.loadFailures[PLUGIN])
            publisher.sync();assertEquals("ANDROID_STATE_UNKNOWN",currentStatus())
            assertTrue(host.authorizedBindings().isEmpty())
            payload.writeBytes(verifiedPayload);host.reload()
            publisher.sync();assertEquals("PLUGIN_DISABLED",currentStatus())
            host.enable(PLUGIN,true)
            publisher.sync();assertEquals("READY",currentStatus())
            assertEquals("succeeded",JsonFields.string(invoke(),"outcome"))
            assertEquals(true,JsonFields.bool(control("switch",mapOf("capabilityId" to ID,"enabled" to false)),"ok"))
            control("start",mapOf("capabilityId" to ID))
            assertEquals("GATEWAY_DISABLED",JsonFields.string(control("result"),"reason"))
            control("switch",mapOf("capabilityId" to ID,"enabled" to true))
            // OS revocation removes bindings; reconnect and publisher recreation restore them only with the existing local grant.
            shadowOf(manager).setNotificationListenerAccessGranted(component,false)
            // Even before the next sync, an already-attested request must hit the real Android permission gate.
            val revoked = invoke()
            assertEquals("denied",JsonFields.string(revoked,"outcome"))
            assertTrue(Json.canonical(revoked).contains("NOTIFICATION_ACCESS_REQUIRED"))
            publisher.sync();assertEquals("ANDROID_PERMISSION_REQUIRED",currentStatus())
            shadowOf(manager).setNotificationListenerAccessGranted(component,true)
            listener.onListenerDisconnected()
            publisher.sync();assertEquals("PROVIDER_UNAVAILABLE",currentStatus())
            listener.onListenerConnected()
            publisher = CapabilityPublisher(context,http,binding.storageKey,publisher.grantRevision,
                { grants.state.value!!.revision },host,{ error("Unexpected foreign grant") },TestDocumentKeys)
            publisher.sync();assertEquals("READY",currentStatus())
            assertEquals("succeeded",JsonFields.string(invoke(),"outcome"))
            host.enable(PLUGIN,false);publisher.sync();assertEquals("PLUGIN_DISABLED",currentStatus())
            host.uninstall(PLUGIN);publisher.sync();assertEquals("PLUGIN_NOT_INSTALLED",currentStatus())
            assertTrue(host.authorizedBindings().isEmpty())
            assertNotNull(entry)
        } finally {
            listener?.onDestroy()
            executionScope.cancel()
            process.destroy();process.waitFor(10,TimeUnit.SECONDS)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
            resetNotificationComposition()
        }
    }

    // Only the Manifest-declared runtime implementations are loaded by name.
    // The App and this test keep their compile-time dependency on public control ports.
    private fun installRuntimeProvider(name: String) {
        Robolectric.buildContentProvider(Class.forName(name).asSubclass(ContentProvider::class.java)).create()
    }
    private fun resetNotificationComposition() {
        listOf("com.openandroidintelligence.notifications.NotificationRuntimeFactoryRegistry" to "reset",
            "com.openandroidintelligence.notification.host.NotificationHostInstaller" to "resetForTest").forEach { (name,method) ->
            val type=Class.forName(name)
            type.getMethod(method).invoke(type.getField("INSTANCE").get(null))
        }
        NotificationControlRegistry.reset()
    }

    private fun androidConnection(url: URL): HttpURLConnection {
        val clientClass = Class.forName("com.android.okhttp.OkHttpClient")
        val client = clientClass.getConstructor().newInstance()
        val ready = clientClass.getDeclaredMethod("copyWithDefaults").apply { isAccessible=true }.invoke(client)
        return Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl").getConstructor(URL::class.java,clientClass)
            .newInstance(url,ready) as HttpURLConnection
    }
    companion object {
        const val PLUGIN="org.openandroidintelligence.notifications"
        const val ID="$PLUGIN.query"
        const val LISTENER="com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService"
    }
}
