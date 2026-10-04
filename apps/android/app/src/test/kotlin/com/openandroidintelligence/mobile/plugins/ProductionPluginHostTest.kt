package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.TestDocumentKeys
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
@Config(sdk = [34], application = Application::class)
class ProductionPluginHostTest {
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
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse {
                    val data = when {
                        request.target.endsWith("/claim") -> mapOf("claimId" to "claim_host", "requestId" to "req_host",
                            "accountId" to "acct_host", "deviceId" to "dev_host", "pairingGeneration" to 1, "grantRevision" to 7)
                        request.target.endsWith("/result") -> {
                            results.add(request)
                            terminal = true
                            if (results.size == 1) throw IOException("Lost result ACK after Gateway adoption")
                            submitted.complete(Unit)
                            emptyMap<String, Any?>()
                        }
                        else -> mapOf("request" to mapOf("requestId" to "req_host", "deviceId" to "dev_host",
                            "pairingGeneration" to 1, "grantRevision" to 7, "state" to if (terminal) "result_succeeded" else "pending",
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
                val driver = DeviceExecutionDriver(context, executionScope, http, host, "acct_host", "dev_host", 1,
                    "test_host_execution", { 7 }, { true }, TestDocumentKeys)
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
                "capabilityVersion" to entry.version,"schemaSha256" to Json.sha256(entry.schema),"query" to mapOf("limit" to 1))
            fun run(record: Map<String,Any?>) {
                checkpoints.clear()
                // Exercise the same serialization and target resolution used after an OS restart.
                val restored=com.openandroidintelligence.gateway.schema.JsonFields.obj(Json.parse(Json.canonical(Json.of(record))))!!
                PluginJobScheduler.executeStoredInvocation(host,restored,"job_test") { state,result ->
                    checkpoints+=state
                    if(state=="succeeded") assertTrue(Json.canonical(result!!).contains("timer-result"))
                }
            }
            run(metadata);assertEquals(listOf("executing","succeeded"),checkpoints);assertEquals(1,calls)
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
