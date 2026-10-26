package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.TestDocumentKeys
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Signed package -> installed bytes -> production host -> kernel -> compiled Rust WASM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36], application = Application::class)
class ProductionPluginHostTest {
    @Test
    @Config(sdk = [34])
    fun uninstallingADamagedPluginRevokesItsAuthorsWithoutRemovingSharedPrimitiveGrants() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val audit = AndroidAuditStore()
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(),audit).apply {
            bind(PairingGrantBinding("https://host.example","acct_damaged","install_damaged"))
        }
        val trust = DeveloperTrustMode()
        val host = ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"install_damaged")
        val bytes = TestAlpPackages.compiledNotificationsPackage()
        val otherScoped = "plugin:org.example.other:other-author:kernel.notifications.read"
        val preferences = context.getSharedPreferences("open_android_intelligence_pairing_grants",Application.MODE_PRIVATE)
        try {
            host.installStore.installRoot.deleteRecursively()
            host.installStore.install(bytes)
            val kernel = PluginKernel(HostEnvelope(host.hostCapabilities),PhoneLimits(host.phoneCapabilities),host.runtimes,
                audit,trust,host.nativeLoader,host.selector,{ grants.currentKernelGrant(it) },host::mediate)
            host.attach(kernel);host.enable(NOTIFICATION_PLUGIN_ID,true);host.grant(NOTIFICATION_PLUGIN_ID,true)
            grants.updatePrimitive(otherScoped,true)
            val ownScoped = grants.state.value!!.granted.filter { it.startsWith("plugin:$NOTIFICATION_PLUGIN_ID:") }.toSet()
            assertTrue(ownScoped.isNotEmpty())
            preferences.edit().putStringSet("saved.granted",ownScoped + otherScoped).commit()
            File(host.installStore.installRoot,"$NOTIFICATION_PLUGIN_ID/manifest.json").writeText("not-json")
            host.reload()
            assertTrue(host.loadFailures.containsKey(NOTIFICATION_PLUGIN_ID))
            host.uninstall(NOTIFICATION_PLUGIN_ID)
            assertTrue(grants.state.value!!.granted.none { it.startsWith("plugin:$NOTIFICATION_PLUGIN_ID:") })
            assertTrue(otherScoped in grants.state.value!!.granted)
            assertTrue("kernel.notifications.read" in grants.state.value!!.granted)
            assertEquals(setOf(otherScoped),preferences.getStringSet("saved.granted",emptySet()))
            host.installStore.install(bytes);host.reload();host.enable(NOTIFICATION_PLUGIN_ID,true)
            assertEquals("LOCAL_GRANT_REQUIRED",host.capabilitySnapshot().inventory.single()["status"])
            assertTrue(host.authorizedBindings().isEmpty())
        } finally {
            host.installStore.installRoot.deleteRecursively()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    @Config(sdk = [34])
    fun rejectedInstalledBytesRemoveBindingsAndMakeTheInventoryIncompleteUntilRepaired() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.notifications.read")
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.notifications.read"
            override suspend fun invoke(context: LocalGrantContext,input: ByteArray): ByteArray = "{}".encodeToByteArray()
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(),audit).apply {
                bind(PairingGrantBinding("https://host.example","acct_integrity","install_integrity"))
            }
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"install_integrity")
            host.installStore.installRoot.deleteRecursively()
            host.installStore.install(TestAlpPackages.compiledNotificationsPackage())
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities),PhoneLimits(host.phoneCapabilities),host.runtimes,
                audit,trust,host.nativeLoader,host.selector,{ grants.currentKernelGrant(it) },host::mediate))
            val id = "org.openandroidintelligence.notifications"
            host.enable(id,true); host.grant(id,true)
            assertEquals(1,host.capabilitySnapshot().bindings.size)
            assertTrue(host.capabilitySnapshot().inventoryComplete)

            // A different directory must not borrow the valid provider's ID
            // and cause reload() to verify that other package in its place.
            val disguised = File(host.installStore.installRoot,"org.example.disguised")
            disguised.mkdirs()
            File(disguised,"manifest.json").writeBytes(File(host.installStore.installRoot,"$id/manifest.json").readBytes())
            host.reload()
            assertEquals("PLUGIN_MANIFEST_INVALID",host.loadFailures[disguised.name])
            assertFalse(host.capabilitySnapshot().inventoryComplete)
            assertEquals(1,host.capabilitySnapshot().bindings.size)
            disguised.deleteRecursively(); host.reload()
            assertTrue(host.capabilitySnapshot().inventoryComplete)

            val payload = File(host.installStore.installRoot,"$id/payload/notifications.wasm")
            val original = payload.readBytes()
            payload.writeBytes(original + byteArrayOf(0))
            host.reload()
            assertEquals("PLUGIN_PACKAGE_INVALID",host.loadFailures[id])
            assertTrue(host.entries(id).isEmpty())
            assertTrue(host.capabilitySnapshot().bindings.isEmpty())
            assertFalse(host.capabilitySnapshot().inventoryComplete)

            payload.writeBytes(original)
            host.reload()
            assertTrue(host.loadFailures.isEmpty())
            assertTrue(host.capabilitySnapshot().inventoryComplete)
            assertFalse(host.isEnabled(id))
            assertEquals("PLUGIN_DISABLED",host.capabilitySnapshot().inventory.single()["status"])
            host.enable(id,true)
            assertEquals(1,host.capabilitySnapshot().bindings.size)
            host.uninstall(id)
            assertTrue(host.capabilitySnapshot().inventoryComplete)
            assertTrue(host.capabilitySnapshot().inventory.isEmpty())
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test fun anEnabledSignedUpgradeAndRollbackKeepTheSelectedProviderAndAuthorScopedGrant() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context=ApplicationProvider.getApplicationContext<Application>()
        val originalHandler=Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider=KernelPrimitiveRegistry.provider("kernel.sms.read")
        var calls=0
        val provider=object: KernelPrimitiveProvider {
            override val primitiveId="kernel.sms.read"
            override suspend fun invoke(context: LocalGrantContext,input: ByteArray): ByteArray {
                calls++;return """{"records":["upgraded-provider"]}""".toByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit=AndroidAuditStore()
            val grants=PairingGrantStateHolder(InMemoryPairingGrantStore(),audit)
            grants.bind(PairingGrantBinding("https://host.example","acct_upgrade","install_upgrade"))
            val trust=DeveloperTrustMode()
            val host=ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"install_upgrade")
            val author=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            host.installStore.installRoot.deleteRecursively()
            host.installStore.install(TestAlpPackages.compiledSmsPackage(author=author,version="1.0.0"))
            val kernel=PluginKernel(HostEnvelope(host.hostCapabilities),PhoneLimits(host.phoneCapabilities),host.runtimes,
                audit,trust,host.nativeLoader,host.selector,{grants.currentKernelGrant(it)},host::mediate)
            host.attach(kernel)
            val original=host.entries("org.openandroidintelligence.sms").first { it.id.endsWith(".query") }
            host.enable(original.identity.pluginId,true);host.grant(original.identity.pluginId,true)
            val before=grants.state.value!!
            val selection=host.selector.select(original.key,before.pairingId)
            for (version in listOf("2.0.0","1.0.0")) {
                if (version == "2.0.0") host.installStore.install(TestAlpPackages.compiledSmsPackage(author=author,version=version))
                else host.installStore.rollback(original.identity.pluginId)
                host.reload()
                val current=host.entries(original.identity.pluginId).first { it.key == original.key }
                assertEquals(version,current.identity.version)
                assertTrue(host.isEnabled(current.identity.pluginId))
                assertEquals(before,grants.state.value)
                assertEquals(selection.grantRevision,host.selector.select(current.key,before.pairingId).grantRevision)
                assertEquals(current.identity,host.selector.select(current.key,before.pairingId).identity)
                assertTrue(host.authorizedBindings().any { it["capabilityId"] == current.id })
                assertTrue(host.invoke(current.identity,"acct_upgrade",current.key,Json.parse("""{"limit":1}"""),"cor_upgrade").decodeToString().contains("upgraded-provider"))
            }
            assertEquals(2,calls)
            // A new author must not inherit the old override, enablement or grants.
            host.installStore.uninstall(original.identity.pluginId)
            host.installStore.install(TestAlpPackages.compiledSmsPackage(version="3.0.0"))
            host.reload()
            assertFalse(host.isEnabled(original.identity.pluginId))
            assertTrue(host.authorizedBindings().isEmpty())
            assertEquals(original.identity.authorKeyFingerprint,host.selector.select(original.key,before.pairingId).identity.authorKeyFingerprint)
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if(previousProvider!=null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
    }

    @Test fun signedSmsExecutionKeepsItsGrantFenceAndRetriesOnlyDeliveryAfterALostReply() = runBlocking {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.sms.read")
        var calls = 0
        val records = """{"records":[{"id":"sms_actual","body":"provider record"}]}""".toByteArray()
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.read"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                assertEquals("acct_host", context.accountId)
                assertTrue(input.decodeToString().contains("\"limit\":1"))
                calls++
                return records
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
            grants.bind(PairingGrantBinding("https://host.example", "acct_host", "install_host"))
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), "install_host")
            host.installStore.installRoot.deleteRecursively()
            host.installStore.install(TestAlpPackages.compiledSmsPackage())
            val kernel = PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector,
                { grants.currentKernelGrant(it) }, host::mediate)
            host.attach(kernel)
            val entry = host.entries("org.openandroidintelligence.sms").first { it.id.endsWith(".query") }
            host.enable(entry.identity.pluginId, true)
            host.grant(entry.identity.pluginId, true)
            assertTrue(host.authorizedBindings().any { it["capabilityId"] == entry.id })
            assertArrayEquals(records, host.invoke(entry.identity, "acct_host", entry.key, Json.parse("{\"limit\":1}"), "cor_host"))
            assertEquals(1, calls)
            val invalid = runCatching { host.invoke(entry.identity, "acct_host", entry.key, Json.parse("{\"limit\":101}"), "cor_bad") }.exceptionOrNull()
            assertEquals("SCHEMA_INVALID", invalid?.message)
            host.grant(entry.identity.pluginId, false)
            assertTrue(host.authorizedBindings().isEmpty())
            val denied = runCatching { host.invoke(entry.identity, "acct_host", entry.key, Json.parse("{\"limit\":1}"), "cor_denied") }.exceptionOrNull()
            assertTrue(denied is CapabilityDenied)
            assertEquals(1, calls)
            host.grant(entry.identity.pluginId, true)
            val submitted = CompletableDeferred<Unit>()
            val results = mutableListOf<WireRequest>()
            var terminal = false
            var gatewayRevoked = false
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse {
                    val activeId = request.target.substringAfter("/device-requests/").substringBefore('/')
                    if (request.target.endsWith("/req_disabled") && gatewayRevoked) {
                        return WireResponse(403,emptyList(),"""{"protocol":"2.1","error":{"code":"CAPABILITY_DENIED"}}""".toByteArray())
                    }
                    val data = when {
                        request.target.endsWith("/claim") -> mapOf("claimId" to "claim_$activeId", "requestId" to activeId,
                            "accountId" to "acct_host", "deviceId" to "dev_host", "pairingGeneration" to 1, "grantRevision" to 7)
                        request.target.endsWith("/result") -> {
                            results.add(request)
                            terminal = true
                            if (results.size == 1) throw IOException("Lost result ACK after Gateway adoption")
                            submitted.complete(Unit)
                            emptyMap<String, Any?>()
                        }
                        else -> mapOf("request" to mapOf("requestId" to activeId, "deviceId" to "dev_host",
                            "pairingGeneration" to 1, "grantRevision" to 7, "state" to if (terminal && activeId == "req_host") "result_succeeded" else "pending",
                            "expiresAt" to Instant.now().plusSeconds(60).toString(), "risk" to "read",
                            "provider" to mapOf("pluginId" to entry.identity.pluginId, "authorKeyId" to "sha256:${entry.identity.authorKeyFingerprint}"),
                            "capability" to mapOf("id" to entry.id, "version" to entry.version), "parameters" to mapOf("limit" to 1)))
                    }
                    return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to data))).toByteArray())
                }
                override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
            }
            val http = GatewayHttpClient(GatewayProfile("acct_host", "dev_host", "sess_host", "https://host.example", accessToken="test"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                var foreground = true
                val driver = DeviceExecutionDriver(context, executionScope, http, host, "acct_host", "dev_host", 1,
                    "test_host_execution", { 7 }, { foreground }, TestDocumentKeys)
                driver.accept("req_host")
                driver.accept("req_host") // Snapshot/event replay must not create another invocation.
                withTimeout(5000) { driver.confirmation.filterNotNull().first() }
                driver.decide("req_host",true) // The local verified risk wins over the mock's stale "read" risk.
                withTimeout(5000) { submitted.await(); executionScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
                assertEquals(2, calls) // Initial direct invocation plus one device invocation.
                assertEquals(2, results.size)
                assertArrayEquals(results[0].body, results[1].body)
                assertEquals(results[0].headers.first { it.name == "Idempotency-Key" }.value,
                    results[1].headers.first { it.name == "Idempotency-Key" }.value)
                assertTrue(results[0].body.decodeToString().contains("sms_actual"))
                driver.accept("req_host")
                assertEquals(2, calls)
                foreground = false
                driver.accept("req_background")
                withTimeout(5000) { executionScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
                assertEquals(2, calls)
                assertTrue(results.last().body.decodeToString().contains("LOCAL_CONFIRMATION_REQUIRED"))
                foreground = true
                // Revoke at the Gateway while the Android confirmation is open;
                // a missed cancellation event must not permit another invocation.
                driver.accept("req_disabled")
                val disabled = withTimeout(5000) { driver.confirmation.filterNotNull().first() }
                assertEquals("req_disabled",disabled.requestId)
                gatewayRevoked = true
                driver.decide("req_disabled",true)
                withTimeout(5000) { executionScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
                assertEquals(2,calls)
                assertTrue(results.last().body.decodeToString().contains("GATEWAY_AUTHORIZATION_REVOKED"))
            } finally {
                executionScope.cancel()
            }
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
    }

    @Test fun storedTimerInvokesTheVerifiedNonQueryNameAndVersionAndFencesLegacyOrChangedTargets() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context=ApplicationProvider.getApplicationContext<Application>()
        val originalHandler=Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider=KernelPrimitiveRegistry.provider("kernel.sms.read")
        val checkpoints=mutableListOf<String>();var calls=0
        val provider=object: KernelPrimitiveProvider {
            override val primitiveId="kernel.sms.read"
            override suspend fun invoke(context: LocalGrantContext,input: ByteArray): ByteArray {
                assertEquals("executing",checkpoints.last())
                assertTrue(input.decodeToString().contains("\"limit\":1"));calls++
                return """{"records":["timer-result"]}""".toByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit=AndroidAuditStore()
            val grants=PairingGrantStateHolder(InMemoryPairingGrantStore(),audit)
            grants.bind(PairingGrantBinding("https://host.example","acct_host","install_host"))
            val trust=DeveloperTrustMode()
            val host=ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"install_host")
            host.installStore.installRoot.deleteRecursively()
            host.installStore.install(TestAlpPackages.compiledSmsPackage("org.openandroidintelligence.sms.fetch","2.0.0"))
            val kernel=PluginKernel(HostEnvelope(host.hostCapabilities),PhoneLimits(host.phoneCapabilities),
                host.runtimes,audit,trust,host.nativeLoader,host.selector,{grants.currentKernelGrant(it)},host::mediate)
            host.attach(kernel)
            val entry=host.entries("org.openandroidintelligence.sms").first { it.id.endsWith(".fetch") }
            host.enable(entry.identity.pluginId,true);host.grant(entry.identity.pluginId,true)
            val call=KernelCallContext(entry.identity,"acct_host",grants.state.value!!.pairingId,
                SessionConstraints(entry.primitives+entry.key,false,"cor_schedule"),kernel.registrationFor(entry.identity.pluginId)!!.budget)
            val args=com.openandroidintelligence.gateway.schema.JsonFields.obj(Json.of(mapOf(
                "capabilityId" to entry.id,"capabilityVersion" to entry.version,"query" to mapOf("limit" to 1))))!!
            assertEquals(entry,host.scheduledTarget(call,args))
            val metadata=mapOf("pluginId" to entry.identity.pluginId,"author" to entry.identity.authorKeyFingerprint,
                "version" to entry.identity.version,"accountId" to "acct_host","capabilityId" to entry.id,
                "capabilityVersion" to entry.version,"schemaSha256" to Json.sha256(entry.schema),"query" to mapOf("limit" to 1),
                "expiresAt" to 1_000_000L)
            fun run(record: Map<String,Any?>) {
                checkpoints.clear()
                // Exercise the same serialization and target resolution used after an OS restart.
                val restored=com.openandroidintelligence.gateway.schema.JsonFields.obj(Json.parse(Json.canonical(Json.of(record))))!!
                PluginJobScheduler.executeStoredInvocation(host,restored,"job_test",nowMillis = 999_999L) { state,result ->
                    checkpoints+=state
                    if(state=="succeeded") assertTrue(Json.canonical(result!!).contains("timer-result"))
                }
            }
            run(metadata);assertEquals(listOf("executing","succeeded"),checkpoints);assertEquals(1,calls)
            // Delayed OS delivery must fence writes without an earlier maintenance pass.
            run(metadata+("expiresAt" to 999_999L));assertEquals(listOf("expired"),checkpoints)
            run(metadata+("expiresAt" to 1L));assertEquals(listOf("expired"),checkpoints)
            run(metadata-"expiresAt");assertEquals(listOf("expired"),checkpoints)
            assertEquals(1,calls)
            run(metadata-"capabilityId");assertEquals(listOf("target_unavailable"),checkpoints)
            run(metadata+("capabilityVersion" to "1.0.0"));assertEquals(listOf("target_unavailable"),checkpoints)
            run(metadata+("schemaSha256" to "sha256:"+"0".repeat(64)));assertEquals(listOf("target_unavailable"),checkpoints)
            assertEquals(1,calls)
            host.grant(entry.identity.pluginId,false)
            assertNotNull(runCatching { host.scheduledTarget(call,args) }.exceptionOrNull())
            run(metadata);assertEquals(listOf("executing","failed"),checkpoints);assertEquals(1,calls)
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if(previousProvider!=null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
    }
}
