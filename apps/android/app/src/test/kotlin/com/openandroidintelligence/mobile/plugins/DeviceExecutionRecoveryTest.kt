package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.TestDocumentKeys
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class DeviceExecutionRecoveryTest {
    @Test fun lostClaimReplyIsRetriedWithTheSameIdempotencyKeyAndProducesATerminalResult() = runBlocking {
        fixture("claim_retry") { driver, transport, _, scope, failures ->
            transport.claimFailures = 1
            driver.accept("req_retry")
            awaitJobs(scope)
            assertEquals(2, transport.claims.size)
            assertEquals(transport.claims[0].headers.first { it.name == "Idempotency-Key" }.value,
                transport.claims[1].headers.first { it.name == "Idempotency-Key" }.value)
            assertEquals(1, transport.results.size)
            assertTrue(transport.results.single().body.decodeToString().contains("denied"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun claimDenialCannotInventAReceiptOrEscapeAsAnUncaughtCoroutineFailure() = runBlocking {
        fixture("claim_denied") { driver, transport, journal, scope, failures ->
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
        fixture("recovery_isolation") { driver, transport, journal, scope, failures ->
            listOf("req_offline", "req_ready").forEach { id ->
                journal.write(id, Json.canonical(Json.of(mapOf("stage" to "completed",
                    "expiresAt" to transport.expiresAt, "result" to mapOf("outcome" to "succeeded",
                        "data" to mapOf("count" to 1))))).toByteArray())
            }
            transport.offlineId = journal.keys().first()
            driver.recoverPending()
            awaitJobs(scope)
            assertEquals(1, transport.results.size)
            assertTrue(transport.results.single().body.decodeToString().contains("succeeded"))
            assertEquals(setOf(transport.offlineId), journal.keys())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aHungClaimStopsAtTheRequestDeadlineWithoutForgingAResult() = runBlocking {
        fixture("claim_timeout") { driver, transport, journal, scope, failures ->
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
        fixture("delivery_retry") { driver, transport, journal, scope, failures ->
            journal.write("req_result", Json.canonical(Json.of(mapOf("stage" to "completed",
                "expiresAt" to transport.expiresAt, "result" to mapOf("outcome" to "succeeded",
                    "data" to mapOf("count" to 1))))).toByteArray())
            transport.resultFailures = 4
            driver.recoverPending(); awaitJobs(scope)
            assertEquals(4, transport.results.size)
            assertNotNull(journal.read("req_result"))
            driver.recoverPending(); awaitJobs(scope)
            assertEquals(5, transport.results.size)
            assertTrue(transport.results.all { it.body.contentEquals(transport.results.first().body) })
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun recoveringAnAlreadyClaimedRequestCannotRepeatPluginExecution() = runBlocking {
        fixture("claimed_recovery") { driver, transport, journal, scope, failures ->
            journal.write("req_claimed", Json.canonical(Json.of(mapOf("stage" to "claimed",
                "expiresAt" to transport.expiresAt))).toByteArray())
            driver.recoverPending(); awaitJobs(scope)
            assertEquals(1, transport.results.size)
            assertTrue(transport.results.single().body.decodeToString().contains("outcome_unknown"))
            assertFalse(transport.results.single().body.decodeToString().contains("CAPABILITY_DENIED"))
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aForeignReceiptIsNeverUsedToUploadAResult() = runBlocking {
        fixture("foreign_receipt") { driver, transport, journal, scope, failures ->
            transport.receiptAccount = "other_account"
            driver.accept("req_foreign"); awaitJobs(scope)
            assertTrue(transport.results.isEmpty())
            assertNotNull(journal.read("req_foreign"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aHungResultUploadHasABoundedLifetimeAndKeepsTheCompletedJournal() = runBlocking {
        fixture("result_timeout") { driver, transport, journal, scope, failures ->
            transport.hangResult = true
            driver.accept("req_hung_result")
            withTimeout(12_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(1, transport.results.size)
            val saved = journal.read("req_hung_result")!!.decodeToString()
            assertTrue(saved.contains("completed"))
            assertTrue(saved.contains("denied"))
            assertTrue(failures.isEmpty())
        }
    }

    private suspend fun awaitJobs(scope: CoroutineScope) {
        withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    }

    private suspend fun fixture(name: String, block: suspend (DeviceExecutionDriver, Transport, EncryptedDocuments,
        CoroutineScope, List<Throwable>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val journal = EncryptedDocuments(context, "device-execution:$name", TestDocumentKeys)
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error })
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val trust = DeveloperTrustMode()
        val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), "install")
        val transport = Transport()
        val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore())
        val driver = DeviceExecutionDriver(context, scope, http, host, "acct", "dev", 1, name, { 7 }, { true }, TestDocumentKeys)
        try { block(driver, transport, journal, scope, failures) } finally { scope.cancel() }
    }

    private class Transport : GatewayByteTransport {
        val claims = CopyOnWriteArrayList<WireRequest>()
        val results = CopyOnWriteArrayList<WireRequest>()
        var claimFailures = 0
        var resultFailures = 0
        var claimDenied = false
        var hangClaim = false
        var hangResult = false
        var receiptAccount = "acct"
        var offlineId: String? = null
        var expiresAt = Instant.now().plusSeconds(60).toString()
        override suspend fun execute(request: WireRequest): WireResponse {
            val id = request.target.substringAfter("/device-requests/").substringBefore('/')
            if (id == offlineId) throw IOException("离线夹具")
            val data = when {
                request.target.endsWith("/claim") -> {
                    claims += request
                    if (hangClaim) awaitCancellation()
                    if (claimDenied) return WireResponse(403, emptyList(), """{"protocol":"2.1","error":{"code":"CAPABILITY_DENIED"}}""".toByteArray())
                    if (claimFailures-- > 0) throw IOException("领取已成功但回包丢失")
                    mapOf("claimId" to "claim_$id", "requestId" to id, "accountId" to receiptAccount,
                        "deviceId" to "dev", "pairingGeneration" to 1, "grantRevision" to 7)
                }
                request.target.endsWith("/result") -> {
                    results += request
                    if (hangResult) awaitCancellation()
                    if (resultFailures-- > 0) throw IOException("结果回包丢失")
                    emptyMap<String, Any?>()
                }
                else -> mapOf("request" to mapOf("deviceId" to "dev", "pairingGeneration" to 1,
                    "grantRevision" to 7, "state" to "pending", "expiresAt" to expiresAt, "risk" to "read",
                    "provider" to mapOf("pluginId" to "missing", "authorKeyId" to "sha256:missing"),
                    "capability" to mapOf("id" to "notifications.read", "version" to "1.0.0"), "parameters" to emptyMap<String, Any?>()))
            }
            return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to data))).toByteArray())
        }
        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }
}
