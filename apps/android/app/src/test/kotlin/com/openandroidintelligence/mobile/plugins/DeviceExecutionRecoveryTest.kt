package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.SharedPreferencesPairingGrantStore
import com.openandroidintelligence.mobile.TestDocumentKeys
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class DeviceExecutionRecoveryTest {
    @Test fun restartWithTheSameVerifiedStateRecoversAResultDespiteADifferentInMemoryHostRevision() = runBlocking {
        fixture("restart_same_authority") { _, transport, journal, scope, failures, originalHost ->
            originalHost.revision.value = 9L
            journal.write("req_restart", journalEntry(originalHost, transport.expiresAt, "completed",
                mapOf("outcome" to "succeeded", "data" to mapOf("count" to 1))))
            val context = ApplicationProvider.getApplicationContext<Application>()
            transport.seedClaimed("req_restart")
            val restartedHost = createHost(context, install = false)
            assertEquals(executionHostAuthorizationDigest(originalHost), executionHostAuthorizationDigest(restartedHost))
            assertNotEquals(originalHost.revision.value, restartedHost.revision.value)
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val restarted = DeviceExecutionDriver(context, scope, http, restartedHost, "acct", "dev", 1,
                "restart_same_authority", { 7 }, { true }, TestDocumentKeys)
            restarted.recoverPending()
            awaitJobs(scope)
            assertEquals(1, transport.results.size)
            assertTrue("Unexpected terminal result: " + transport.results.single().body.decodeToString(),
                transport.results.single().body.decodeToString().contains("succeeded"))
            assertEquals(0, transport.invocations.get())
            assertTrue(transport.authorizationStates.isEmpty())
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }
    @Test fun lostClaimReplyIsRetriedWithTheSameIdempotencyKeyAndProducesATerminalResult() = runBlocking {
        fixture("claim_retry") { driver, transport, _, scope, failures, _ ->
            transport.claimFailures = 1
            driver.accept("req_retry")
            awaitJobs(scope)
            assertEquals(2, transport.claims.size)
            assertEquals(transport.claims[0].headers.first { it.name == "Idempotency-Key" }.value,
                transport.claims[1].headers.first { it.name == "Idempotency-Key" }.value)
            assertEquals(1, transport.results.size)
            assertTrue("Unexpected terminal result: " + transport.results.single().body.decodeToString(),
                transport.results.single().body.decodeToString().contains("succeeded"))
            assertEquals(1, transport.invocations.get())
            assertEquals(listOf("req_retry" to "executing"), transport.authorizationStates.toList())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun claimDenialCannotInventAReceiptOrEscapeAsAnUncaughtCoroutineFailure() = runBlocking {
        fixture("claim_denied") { driver, transport, journal, scope, failures, host ->
            transport.claimDenied = true
            driver.accept("req_denied")
            awaitJobs(scope)
            assertEquals(1, transport.claims.size)
            assertTrue(transport.results.isEmpty())
            assertNotNull(journal.read("req_denied"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun oneOfflineJournalEntryDoesNotPreventAnotherCompletedResultFromRecovering() = runBlocking {
        fixture("recovery_isolation") { driver, transport, journal, scope, failures, host ->
            listOf("req_offline", "req_ready").forEach { id ->
                journal.write(id, journalEntry(host, transport.expiresAt, "completed",
                    mapOf("outcome" to "succeeded", "data" to mapOf("count" to 1))))
            }
            transport.seedClaimed("req_offline", "req_ready")
            transport.offlineId = journal.keys().first()
            driver.recoverPending()
            awaitJobs(scope)
            assertEquals(1, transport.results.size)
            assertTrue("Unexpected terminal result: " + transport.results.single().body.decodeToString(),
                transport.results.single().body.decodeToString().contains("succeeded"))
            assertEquals(setOf(transport.offlineId), journal.keys())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aHungClaimStopsAtTheRequestDeadlineWithoutForgingAResult() = runBlocking {
        fixture("claim_timeout") { driver, transport, journal, scope, failures, host ->
            transport.expiresAt = Instant.now().plusMillis(200).toString()
            transport.hangClaim = true
            driver.accept("req_hung")
            withTimeout(2_000) { awaitJobs(scope) }
            assertTrue(transport.results.isEmpty())
            assertNotNull(journal.read("req_hung"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun exhaustedDeliveryRetainsTheExactResultForIdempotentRecovery() = runBlocking {
        fixture("delivery_retry") { driver, transport, journal, scope, failures, host ->
            journal.write("req_result", journalEntry(host, transport.expiresAt, "completed",
                mapOf("outcome" to "succeeded", "data" to mapOf("count" to 1))))
            transport.seedClaimed("req_result")
            transport.resultFailures = 4
            driver.recoverPending(); awaitJobs(scope)
            assertEquals(4, transport.results.size)
            assertNotNull(journal.read("req_result"))
            driver.recoverPending(); awaitJobs(scope)
            assertEquals(5, transport.results.size)
            assertEquals(0, transport.invocations.get())
            assertTrue(transport.results.all { it.body.contentEquals(transport.results.first().body) })
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun cancellingBeforeInvocationPersistsCancelledInsteadOfAnUnknownOutcome() = runBlocking {
        fixture("cancel_before_invocation") { driver, transport, journal, scope, failures, host ->
            val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
            host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.MANUAL))
            val reporting = CompletableDeferred<Unit>()
            transport.afterAuthorization = { state ->
                assertEquals("awaiting_confirmation", state)
                reporting.complete(Unit)
                awaitCancellation()
            }
            // Retain the completed journal if the current delivery gate attempts a terminal upload.
            // This test establishes the execution outcome, independently of terminal-delivery policy.
            transport.resultFailures = 4
            driver.accept("req_cancel_before_invocation")
            withTimeout(5_000L) { reporting.await() }
            driver.cancel("req_cancel_before_invocation")
            awaitJobs(scope)

            val document = JsonFields.obj(Json.parse(journal.read("req_cancel_before_invocation")!!.decodeToString()))!!
            val result = JsonFields.obj(JsonFields.field(document, "result"))!!
            assertEquals("completed", JsonFields.string(document, "stage"))
            assertEquals("cancelled", JsonFields.string(result, "outcome"))
            assertEquals("DEVICE_REQUEST_CANCELLED", JsonFields.string(JsonFields.obj(JsonFields.field(result, "data")), "code"))
            assertEquals(0, transport.invocations.get())
            assertEquals(listOf("req_cancel_before_invocation" to "awaiting_confirmation"), transport.authorizationStates.toList())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun recoveringAnAlreadyClaimedRequestCannotRepeatPluginExecution() = runBlocking {
        fixture("claimed_recovery") { driver, transport, journal, scope, failures, host ->
            journal.write("req_claimed", journalEntry(host, transport.expiresAt, "claimed"))
            transport.seedClaimed("req_claimed")
            driver.recoverPending(); awaitJobs(scope)
            assertEquals(1, transport.results.size)
            assertTrue(transport.results.single().body.decodeToString().contains("outcome_unknown"))
            assertFalse(transport.results.single().body.decodeToString().contains("CAPABILITY_DENIED"))
            assertEquals(0, transport.invocations.get())
            assertTrue(transport.authorizationStates.isEmpty())
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aForeignReceiptIsNeverUsedToUploadAResult() = runBlocking {
        fixture("foreign_receipt") { driver, transport, journal, scope, failures, host ->
            transport.receiptAccount = "other_account"
            driver.accept("req_foreign"); awaitJobs(scope)
            assertTrue(transport.results.isEmpty())
            assertNotNull(journal.read("req_foreign"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aHungResultUploadHasABoundedLifetimeAndKeepsTheCompletedJournal() = runBlocking {
        fixture("result_timeout") { driver, transport, journal, scope, failures, host ->
            transport.hangResult = true
            driver.accept("req_hung_result")
            withTimeout(12_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(1, transport.results.size)
            val saved = journal.read("req_hung_result")!!.decodeToString()
            assertTrue(saved.contains("completed"))
            val savedResult = JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(saved)), "result"))
            assertEquals("Persisted execution result must survive the hung upload", "succeeded", JsonFields.string(savedResult, "outcome"))
            assertEquals(1, transport.invocations.get())
            assertTrue(failures.isEmpty())
        }
    }

    private suspend fun awaitJobs(scope: CoroutineScope) {
        withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    }

    private suspend fun fixture(name: String, block: suspend (DeviceExecutionDriver, Transport, EncryptedDocuments,
        CoroutineScope, List<Throwable>, ProductionPluginHost) -> Unit) {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.notifications.read")
        val journal = EncryptedDocuments(context, "device-execution:$name", TestDocumentKeys)
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error })
        val invocations = AtomicInteger()
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.notifications.read"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                invocations.incrementAndGet()
                return """{"records":[{"id":"recovery-fixture"}]}""".toByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val host = createHost(context, install = true)
            val transport = Transport(host.entries(NOTIFICATION_PLUGIN_ID).single(), invocations)
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val driver = DeviceExecutionDriver(context, scope, http, host, "acct", "dev", 1, name, { 7 }, { true }, TestDocumentKeys)
            block(driver, transport, journal, scope, failures, host)
        } finally {
            scope.cancel()
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun createHost(context: Context, install: Boolean): ProductionPluginHost {
        val audit = AndroidAuditStore()
        val grants = PairingGrantStateHolder(SharedPreferencesPairingGrantStore.from(context), audit).apply {
            bind(PairingGrantBinding("https://gateway.example", "acct", "install"))
        }
        val trust = DeveloperTrustMode()
        val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), "install")
        if (install) host.installStore.install(TestAlpPackages.compiledNotificationsPackage())
        host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities), host.runtimes,
            audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant, mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
        if (install) host.enable(NOTIFICATION_PLUGIN_ID, true)
        else assertTrue("Restart must restore the verified plugin's persisted enablement", host.isEnabled(NOTIFICATION_PLUGIN_ID))
        val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
        if (install) host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        assertEquals(CapabilityAuthorizationMode.AUTOMATIC, host.capabilityPolicy(entry.identity.pluginId, entry.key).mode)
        return host
    }

    private fun journalEntry(host: ProductionPluginHost, expiresAt: String, stage: String,
        result: Map<String,Any?>? = null): ByteArray = Json.canonical(Json.of(mapOf(
        "stage" to stage, "expiresAt" to expiresAt, "result" to result,
        "localAuthorizationRevision" to 0, "hostAuthorizationDigest" to executionHostAuthorizationDigest(host),
    ))).toByteArray()

    private class Transport(private val entry: VerifiedCapability, val invocations: AtomicInteger) : GatewayByteTransport {
        val claims = CopyOnWriteArrayList<WireRequest>()
        val results = CopyOnWriteArrayList<WireRequest>()
        val authorizationStates = CopyOnWriteArrayList<Pair<String, String>>()
        private val states = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val receipts = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
        private val adoptedResults = java.util.concurrent.ConcurrentHashMap<String, String>()
        var claimFailures = 0
        var resultFailures = 0
        var claimDenied = false
        var hangClaim = false
        var hangResult = false
        var receiptAccount = "acct"
        var offlineId: String? = null
        var expiresAt = Instant.now().plusSeconds(60).toString()
        var afterAuthorization: suspend (String) -> Unit = {}

        /** A saved Android journal corresponds to an already-issued, still-current Core receipt. */
        fun seedClaimed(vararg ids: String) {
            ids.forEach { id -> receipt(id); states[id] = "claimed" }
        }

        private fun receipt(id: String): Map<String, Any?> = receipts.getOrPut(id) {
            mapOf("claimId" to "claim_$id", "requestId" to id, "accountId" to receiptAccount,
                "deviceId" to "dev", "pairingGeneration" to 1, "grantRevision" to 7)
        }

        override suspend fun execute(request: WireRequest): WireResponse {
            val id = request.target.substringAfter("/device-requests/").substringBefore('/')
            if (id == offlineId) throw IOException("离线夹具")
            val data = when {
                request.target.endsWith("/claim") -> {
                    claims += request
                    if (hangClaim) awaitCancellation()
                    if (claimDenied) return WireResponse(403, emptyList(), """{"protocol":"2.1","error":{"code":"CAPABILITY_DENIED"}}""".toByteArray())
                    assertEquals("POST", request.method)
                    val body = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                    assertEquals(7, JsonFields.int(body, "grantRevision"))
                    val claimed = receipt(id)
                    states.putIfAbsent(id, "claimed")
                    if (claimFailures-- > 0) throw IOException("领取已成功但回包丢失")
                    claimed
                }
                request.target.endsWith("/authorization") -> {
                    assertEquals("POST", request.method)
                    val body = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                    val state = JsonFields.string(body, "state")!!
                    assertTrue(state in setOf("awaiting_confirmation", "executing"))
                    assertEquals("claimed", states[id])
                    authorizationStates += id to state
                    afterAuthorization(state)
                    mapOf("requestId" to id, "localAuthorizationState" to state)
                }
                request.target.endsWith("/result") -> {
                    assertEquals("POST", request.method)
                    val encoded = request.body.decodeToString()
                    val body = JsonFields.obj(Json.parse(encoded))!!
                    val issued = receipts[id] ?: error("RESULT_WITHOUT_CLAIM")
                    assertEquals(issued["claimId"], JsonFields.string(body, "claimId"))
                    assertEquals(issued["grantRevision"], JsonFields.int(body, "grantRevision"))
                    val result = JsonFields.obj(JsonFields.field(body, "result"))!!
                    val outcome = JsonFields.string(result, "outcome")!!
                    assertTrue(outcome in setOf("succeeded", "failed", "denied", "cancelled", "outcome_unknown"))
                    results += request
                    if (hangResult) awaitCancellation()
                    if (resultFailures-- > 0) throw IOException("结果尚未被确认")
                    val previous = adoptedResults.putIfAbsent(id, encoded)
                    assertTrue("Result retries must retain the adopted result", previous == null || previous == encoded)
                    states[id] = outcome
                    mapOf("requestId" to id, "state" to outcome)
                }
                else -> mapOf("request" to mapOf("requestId" to id, "deviceId" to "dev", "pairingGeneration" to 1,
                    "grantRevision" to 7, "state" to (states[id] ?: "pending"), "expiresAt" to expiresAt, "risk" to entry.risk,
                    "caller" to mapOf("agentName" to "恢复测试 Agent", "purpose" to "验证领取重试与结果恢复"),
                    "provider" to mapOf("pluginId" to entry.identity.pluginId, "authorKeyId" to "sha256:${entry.identity.authorKeyFingerprint}"),
                    "capability" to mapOf("id" to entry.id, "version" to entry.version), "parameters" to mapOf("limit" to 1)))
            }
            return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to data))).toByteArray())
        }
        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }
}
