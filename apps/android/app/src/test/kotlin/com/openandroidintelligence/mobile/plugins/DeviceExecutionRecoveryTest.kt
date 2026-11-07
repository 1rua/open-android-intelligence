package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.auth.GatewayAuthException
import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.SharedPreferencesPairingGrantStore
import com.openandroidintelligence.mobile.TestDocumentKeys
import com.openandroidintelligence.mobile.recoverPlatformSnapshot
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class DeviceExecutionRecoveryTest {
    @Test fun aBlockedLookupDoesNotBlockAnotherDurableNotificationOrItsCancellation() = runBlocking {
        fixture("enqueue_during_lookup") { driver, transport, journal, scope, failures, _ ->
            val lookupStarted = CompletableDeferred<Unit>()
            transport.onLookup = { id ->
                if (id == "req_blocked") {
                    lookupStarted.complete(Unit)
                    awaitCancellation()
                }
                null
            }
            withTimeout(1_000) { driver.enqueue("req_blocked") }
            withTimeout(1_000) { lookupStarted.await() }
            assertNotNull("enqueue must persist before it returns", journal.read("req_blocked"))
            withTimeout(1_000) { driver.enqueue("req_ready") }
            driver.cancel("req_blocked")
            awaitJobs(scope)

            assertEquals(listOf("req_ready"), transport.claims.map { it.target.substringAfter("/device-requests/").substringBefore('/') })
            assertEquals(1, transport.invocations.get())
            assertNotNull("Cancellation must survive a restart", journal.read("req_blocked"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun anEnqueuedNotificationSurvivesRestartBeforeAnyLookupStarts() = runBlocking {
        fixture("notified_restart") { driver, transport, journal, scope, failures, host ->
            val context = ApplicationProvider.getApplicationContext<Application>()
            val stopped = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val previous = DeviceExecutionDriver(context, stopped, http, host, "acct", "dev", 1,
                "notified_restart", { 7 }, { true }, TestDocumentKeys)
            previous.enqueue("req_restart_notification")
            assertTrue(transport.lookups.isEmpty())
            assertNotNull(journal.read("req_restart_notification"))

            driver.recoverPending()
            awaitJobs(scope)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aCancelledNotificationCannotInvokeAPluginAfterRestartOrEventReplay() = runBlocking {
        fixture("cancelled_notification_restart") { driver, transport, _, scope, failures, host ->
            val context = ApplicationProvider.getApplicationContext<Application>()
            val stopped = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val previous = DeviceExecutionDriver(context, stopped, http, host, "acct", "dev", 1,
                "cancelled_notification_restart", { 7 }, { true }, TestDocumentKeys)
            previous.enqueue("req_cancelled_notification")
            previous.cancel("req_cancelled_notification")

            driver.recoverPending()
            awaitJobs(scope)
            driver.enqueue("req_cancelled_notification")
            awaitJobs(scope)
            assertEquals(0, transport.invocations.get())
            assertTrue(transport.results.all { it.body.decodeToString().contains("cancelled") })
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun concurrentEventAndRecoveryReplaysShareTheBlockedLookup() = runBlocking {
        fixture("deduplicated_lookup") { driver, transport, _, scope, failures, _ ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            transport.onLookup = { id ->
                if (id == "req_duplicate" && transport.lookups.count { it == id } == 1) {
                    started.complete(Unit)
                    release.await()
                }
                null
            }
            driver.enqueue("req_duplicate")
            withTimeout(1_000) { started.await() }
            coroutineScope {
                (1..16).map { launch(Dispatchers.Default) { driver.enqueue("req_duplicate") } }.joinAll()
            }
            driver.recoverPending()
            assertEquals("Replays must not start another lookup while the first owns the request", 1, transport.lookups.size)
            release.complete(Unit)
            awaitJobs(scope)
            assertEquals(1, transport.claims.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun eventReplayDuringCancelledExecutionCleanupCannotOpenAnotherExecution() = runBlocking {
        fixture("cancelled_execution_cleanup") { driver, transport, _, scope, failures, _ ->
            val reporting = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            transport.afterAuthorization = {
                reporting.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
            driver.enqueue("req_cleanup")
            withTimeout(1_000) { reporting.await() }
            driver.cancel("req_cleanup")
            withTimeout(1_000) { cleaning.await() }
            val lookups = transport.lookups.size
            repeat(8) { driver.enqueue("req_cleanup") }
            driver.recoverPending()
            assertEquals(lookups, transport.lookups.size)
            assertEquals(1, transport.claims.size)
            assertEquals(0, transport.invocations.get())
            release.complete(Unit)
            awaitJobs(scope)
            driver.enqueue("req_cleanup")
            awaitJobs(scope)
            assertEquals(1, transport.claims.size)
            assertEquals(0, transport.invocations.get())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aFencedLookupCancelsTheExistingConfirmationAndNeverSubmitsItsResult() = runBlocking {
        fixture("confirmation_fence") { driver, transport, journal, scope, failures, host ->
            val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
            host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.MANUAL))
            for (status in listOf(403, 404, 409)) {
                val id = "req_fenced_$status"
                driver.enqueue(id)
                withTimeout(3_000) { driver.confirmation.filterNotNull().first { it.requestId == id } }
                transport.onLookup = { requested ->
                    if (requested == id) WireResponse(status, emptyList(),
                        """{"protocol":"2.1","error":{"code":"REQUEST_FENCED"}}""".toByteArray()) else null
                }
                driver.enqueue(id)
                awaitJobs(scope)
                assertNull(driver.confirmation.value)
                driver.decide(id, true)
                driver.enqueue(id)
                driver.recoverPending()
                awaitJobs(scope)
                assertEquals(0, transport.invocations.get())
                assertTrue(transport.results.isEmpty())
                assertNotNull("The remote fence must survive event replay", journal.read(id))
                assertTrue(failures.isEmpty())
            }
        }
    }

    @Test fun anExpiredRequestCannotBeClaimedOrExecutedByReplay() = runBlocking {
        fixture("expired_notification") { driver, transport, _, scope, failures, _ ->
            transport.expiresAt = Instant.now().minusSeconds(1).toString()
            driver.enqueue("req_expired")
            awaitJobs(scope)
            driver.enqueue("req_expired")
            driver.recoverPending()
            awaitJobs(scope)
            assertEquals(1, transport.lookups.size)
            assertTrue(transport.claims.isEmpty())
            assertTrue(transport.results.isEmpty())
            assertEquals(0, transport.invocations.get())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aFailedJournalWriteCannotAcknowledgeTheNotificationOrStartLookup() = runBlocking {
        fixture("journal_write_failure") { _, transport, _, scope, failures, host ->
            val context = ApplicationProvider.getApplicationContext<Application>()
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val keyReads = AtomicInteger()
            val unavailableKeys = object : com.openandroidintelligence.encrypted.store.AesGcmKeyProvider {
                override fun getOrCreate(): javax.crypto.SecretKey =
                    if (keyReads.incrementAndGet() == 1) TestDocumentKeys.getOrCreate() else throw IOException("JOURNAL_UNAVAILABLE")
                override fun delete() = error("UNEXPECTED_KEY_DELETION")
            }
            val driver = DeviceExecutionDriver(context, scope, http, host, "acct", "dev", 1,
                "journal_write_failure", { 7 }, { true }, unavailableKeys)
            assertEquals("JOURNAL_UNAVAILABLE", runCatching { driver.enqueue("req_unstored") }.exceptionOrNull()?.message)
            assertTrue(transport.lookups.isEmpty())
            assertTrue(transport.claims.isEmpty())
            assertEquals(0, transport.invocations.get())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aNotificationFromAnOldLocalRevisionCannotAcquireExecutionAuthorityAfterRestart() = runBlocking {
        fixture("notification_revision_fence") { _, transport, journal, scope, failures, host ->
            val context = ApplicationProvider.getApplicationContext<Application>()
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val stopped = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
            val previous = DeviceExecutionDriver(context, stopped, http, host, "acct", "dev", 1,
                "notification_revision_fence", { 7 }, { true }, TestDocumentKeys, localAuthorizationRevision = { 1L })
            previous.enqueue("req_previous_revision")
            val current = DeviceExecutionDriver(context, scope, http, host, "acct", "dev", 1,
                "notification_revision_fence", { 7 }, { true }, TestDocumentKeys, localAuthorizationRevision = { 2L })
            current.recoverPending()
            awaitJobs(scope)
            current.enqueue("req_previous_revision")
            awaitJobs(scope)
            assertTrue(transport.claims.isEmpty())
            assertEquals(0, transport.invocations.get())
            assertNotNull(journal.read("req_previous_revision"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun snapshotJournalsAllNotificationsBeforeReturningItsBaselineEvenWhenLookupsAreBlocked() = runBlocking {
        fixture("snapshot_notification_journal") { driver, transport, journal, scope, failures, _ ->
            val ids = listOf("req_snapshot_blocked", "req_snapshot_next")
            transport.snapshotIds = ids + ids.first()
            transport.onLookup = { awaitCancellation() }
            val contextHttp = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val baseline = withTimeout(1_000) {
                recoverPlatformSnapshot(contextHttp, {}, driver::enqueue, driver::recoverPending)
            }
            assertEquals("evt_durable_baseline", baseline)
            assertEquals(ids.toSet(), journal.keys())
            assertTrue(transport.claims.isEmpty())
            assertEquals(0, transport.invocations.get())
            ids.forEach(driver::cancel)
            awaitJobs(scope)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aFailedNotificationLookupIsRecoveredWithoutReplayingThePlatformEvent() = runBlocking {
        fixture("notification_lookup_recovery") { driver, transport, journal, scope, failures, _ ->
            transport.offlineId = "req_lookup_offline"
            driver.enqueue("req_lookup_offline")
            awaitJobs(scope)
            assertNotNull(journal.read("req_lookup_offline"))
            assertTrue(transport.claims.isEmpty())
            transport.offlineId = null
            driver.recoverPending()
            awaitJobs(scope)
            assertEquals(1, transport.claims.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun cancellationDuringResultRetryCannotReplayTheSensitiveCompletedPayload() = runBlocking {
        fixture("cancelled_result_retry") { driver, transport, journal, scope, failures, _ ->
            val submitted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            transport.resultFailures = 1
            transport.afterResult = {
                if (transport.results.size == 1) {
                    submitted.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            driver.enqueue("req_cancelled_result")
            withTimeout(3_000) { submitted.await() }
            driver.cancel("req_cancelled_result")
            val lookups = transport.lookups.size
            driver.enqueue("req_cancelled_result")
            driver.recoverPending()
            assertEquals(lookups, transport.lookups.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.claims.size)
            release.complete(Unit)
            awaitJobs(scope)
            driver.enqueue("req_cancelled_result")
            driver.recoverPending()
            awaitJobs(scope)
            assertEquals(2, transport.results.size)
            assertTrue(transport.results.first().body.decodeToString().contains("succeeded"))
            assertTrue(transport.results.last().body.decodeToString().contains("cancelled"))
            assertFalse(transport.results.last().body.decodeToString().contains("recovery-fixture"))
            assertFalse(transport.results.last().body.decodeToString().contains("synthetic-sensitive-recovery-body"))
            assertEquals(1, transport.invocations.get())
            val fenced = JsonFields.obj(Json.parse(journal.read("req_cancelled_result")!!.decodeToString()))!!
            assertEquals("fenced", JsonFields.string(fenced, "stage"))
            assertEquals("REQUEST_CANCELLED", JsonFields.string(fenced, "fenceReason"))
            assertNull(JsonFields.field(fenced, "result"))
            assertTrue(failures.isEmpty())
        }
    }

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

    @Test fun authorizationRecheckWaitsThroughThreeTransientFailuresWithoutRepeatingExecution() = runBlocking {
        fixture("authorization_recheck_jitter") { driver, transport, journal, scope, failures, host ->
            val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
            host.setCapabilityPolicy(entry.identity.pluginId, entry.key,
                CapabilityPolicy(mode = CapabilityAuthorizationMode.MANUAL))
            val id = "req_authorization_recheck_jitter"
            transport.onLookup = { requested ->
                if (requested == id) {
                    when (transport.lookups.count { it == id }) {
                        1 -> null
                        2 -> throw IOException("批准后核验暂时断网")
                        3, 4 -> WireResponse(503, emptyList(),
                            """{"protocol":"2.1","error":{"code":"GATEWAY_UNAVAILABLE"}}""".toByteArray())
                        else -> null
                    }
                } else null
            }

            driver.enqueue(id)
            withTimeout(3_000) { driver.confirmation.first { it?.requestId == id } }
            driver.respond(id, allowed = true, durationMinutes = 0)
            awaitJobs(scope)

            // Initial lookup, four pre-invocation reads and delivery authorization.
            assertEquals(6, transport.lookups.count { it == id })
            assertEquals(1, transport.claims.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertTrue("Unexpected terminal result: " + transport.results.single().body.decodeToString(),
                transport.results.single().body.decodeToString().contains("succeeded"))
            assertEquals(1, transport.authorizationStates.count { it.second == "awaiting_confirmation" })
            assertNull(driver.confirmation.value)
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun authorizationProgressTransientFailuresAreAdvisoryBut401StillStopsBeforeInvocation() = runBlocking {
        fixture("authorization_progress_advisory") { driver, transport, journal, scope, failures, host ->
            val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
            host.setCapabilityPolicy(entry.identity.pluginId, entry.key,
                CapabilityPolicy(mode = CapabilityAuthorizationMode.MANUAL))
            val advisoryId = "req_authorization_progress_advisory"
            val deniedId = "req_authorization_progress_401"
            transport.onAuthorization = { id, state ->
                when {
                    id == deniedId && state == "executing" -> WireResponse(401, emptyList(), ByteArray(0))
                    id == advisoryId && state == "executing" ->
                        throw GatewayAuthException("REFRESH_FAILED", "CORE_UNAVAILABLE", 503)
                    id == advisoryId || id == deniedId -> WireResponse(503, emptyList(), ByteArray(0))
                    else -> null
                }
            }

            driver.enqueue(advisoryId)
            withTimeout(3_000) { driver.confirmation.first { it?.requestId == advisoryId } }
            driver.respond(advisoryId, allowed = true, durationMinutes = 0)
            awaitJobs(scope)

            assertEquals(3, transport.lookups.count { it == advisoryId })
            assertEquals(1, transport.claims.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertEquals("succeeded", submittedResultOutcome(transport.results.single()))
            assertTrue(journal.keys().isEmpty())

            driver.enqueue(deniedId)
            withTimeout(3_000) { driver.confirmation.first { it?.requestId == deniedId } }
            driver.respond(deniedId, allowed = true, durationMinutes = 0)
            awaitJobs(scope)

            assertEquals(2, transport.lookups.count { it == deniedId })
            assertEquals(2, transport.claims.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(2, transport.results.size)
            assertTerminalResult(transport.results[1], "denied", "CAPABILITY_DENIED",
                "DEVICE_REQUEST_BINDING_CHANGED")
            assertEquals(listOf(
                advisoryId to "awaiting_confirmation", advisoryId to "executing",
                deniedId to "awaiting_confirmation", deniedId to "executing",
            ), transport.authorizationStates.toList())
            assertNull(driver.confirmation.value)
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun progressTlsIdentityFailureStopsBeforePluginInvocation() = runBlocking {
        fixture("authorization_progress_tls") { driver, transport, journal, scope, failures, _ ->
            val id = "req_progress_tls"
            transport.onAuthorization = { requested, state ->
                if (requested == id && state == "executing")
                    throw SSLHandshakeException("synthetic progress identity failure")
                null
            }
            driver.accept(id)
            awaitJobs(scope)
            assertEquals(1, transport.claims.size)
            assertEquals(0, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertTerminalResult(transport.results.single(), "failed", "PLUGIN_EXECUTION_FAILED")
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun temporaryCredentialRenewalFailureDuringRevalidationCanRecoverWithoutRepeatingClaim() = runBlocking {
        val name = "authorization_recheck_refresh"
        fixture(name) { _, transport, journal, scope, failures, host ->
            val id = "req_recheck_refresh"
            val profile = GatewayProfile("acct", "dev", "sess", "https://gateway.example")
            var refreshes = 0
            val renewal = GatewaySessionRenewal(profile, scope) {
                if (refreshes++ == 0) throw GatewayAuthException("REFRESH_FAILED", "CORE_UNAVAILABLE", 503)
                it.copy(sessionId = "renewed", accessToken = "renewed-token")
            }
            val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal)
            val driver = DeviceExecutionDriver(ApplicationProvider.getApplicationContext<Application>(), scope,
                http, host, "acct", "dev", 1, name, { 7 }, { true }, TestDocumentKeys)
            transport.onLookup = { requested ->
                if (requested == id && transport.lookups.count { it == id } in 2..3)
                    WireResponse(401, emptyList(), ByteArray(0)) else null
            }
            driver.accept(id)
            try {
                withTimeout(4_000) { while (transport.results.isEmpty()) delay(10) }
            } finally { renewal.close() }
            awaitJobs(scope)
            assertEquals(2, refreshes)
            assertEquals(1, transport.claims.size)
            assertEquals(1, transport.invocations.get())
            assertEquals(1, transport.results.size)
            assertEquals("succeeded", submittedResultOutcome(transport.results.single()))
            assertTrue(journal.keys().isEmpty())
            assertTrue(failures.isEmpty())
            renewal.close()
        }
    }

    @Test fun permanentAuthorizationRecheckFailureAndTlsStopWithoutInvocation() = runBlocking {
        fixture("authorization_recheck_permanent_failure") { driver, transport, journal, scope, failures, _ ->
            val ids = listOf("req_recheck_401", "req_recheck_403", "req_recheck_tls")
            transport.onLookup = { requested ->
                if (transport.lookups.count { it == requested } == 1) null
                else when (requested) {
                    "req_recheck_401" -> WireResponse(401, emptyList(), ByteArray(0))
                    "req_recheck_403" -> WireResponse(403, emptyList(), ByteArray(0))
                    "req_recheck_tls" -> throw SSLHandshakeException("synthetic revalidation TLS failure")
                    else -> null
                }
            }

            ids.forEachIndexed { index, id ->
                driver.accept(id)
                awaitJobs(scope)

                assertEquals("Initial lookup and one authorization recheck only for $id",
                    2, transport.lookups.count { it == id })
                assertEquals(index + 1, transport.claims.size)
                assertEquals(index + 1, transport.results.size)
                assertTerminalResult(
                    transport.results[index],
                    outcome = if (id == "req_recheck_tls") "failed" else "denied",
                    code = if (id == "req_recheck_tls") "DEVICE_AUTHORIZATION_CHECK_FAILED" else "CAPABILITY_DENIED",
                    reason = if (id == "req_recheck_tls") null else "GATEWAY_AUTHORIZATION_REVOKED",
                )
                assertNull(journal.read(id))
                assertEquals(0, transport.invocations.get())
            }
            assertTrue(transport.authorizationStates.isEmpty())
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun cancellingDuringAuthorizationRecheckBackoffStopsWithoutInvocation() = runBlocking {
        fixture("authorization_recheck_cancel") { driver, transport, journal, scope, failures, _ ->
            val id = "req_recheck_cancel"
            val recheckFailed = CompletableDeferred<Unit>()
            transport.onLookup = { requested ->
                if (requested == id && transport.lookups.count { it == id } >= 2) {
                    if (transport.lookups.count { it == id } == 2) recheckFailed.complete(Unit)
                    throw IOException("执行前核验暂时断网")
                }
                null
            }

            driver.enqueue(id)
            withTimeout(3_000) { recheckFailed.await() }
            driver.cancel(id)
            awaitJobs(scope)

            assertEquals(1, transport.claims.size)
            assertEquals(2, transport.lookups.count { it == id })
            assertEquals(1, transport.results.size)
            assertTerminalResult(transport.results.single(), "cancelled", "DEVICE_REQUEST_CANCELLED")
            assertEquals(0, transport.invocations.get())
            assertTrue(transport.authorizationStates.isEmpty())
            val saved = JsonFields.obj(Json.parse(journal.read(id)!!.decodeToString()))!!
            assertEquals("fenced", JsonFields.string(saved, "stage"))
            assertEquals("REQUEST_CANCELLED", JsonFields.string(saved, "fenceReason"))
            assertTrue(JsonFields.bool(saved, "cancelRequested") == true)
            assertNull("A cancelled request must retain no sensitive result body", JsonFields.field(saved, "result"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun authorizationRecheckStopsAtOriginalExpiryWithoutInvocation() = runBlocking {
        fixture("authorization_recheck_expiry") { driver, transport, journal, scope, failures, _ ->
            val id = "req_recheck_expiry"
            transport.onLookup = { requested ->
                if (requested == id && transport.lookups.count { it == id } == 1) {
                    transport.expiresAt = Instant.now().plusSeconds(2).toString()
                    null
                } else if (requested == id) {
                    WireResponse(503, emptyList(), ByteArray(0))
                } else null
            }

            driver.accept(id)
            awaitJobs(scope, timeoutMillis = 8_000)

            assertEquals(1, transport.claims.size)
            assertTrue("Expiry needs at least the initial lookup and one transient recheck",
                transport.lookups.count { it == id } >= 2)
            assertEquals(0, transport.invocations.get())
            assertTrue(transport.authorizationStates.isEmpty())
            assertTrue("Expiry must not produce more than its terminal failure result", transport.results.size <= 1)
            transport.results.singleOrNull()?.let { assertEquals("failed", submittedResultOutcome(it)) }
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun malformedOrReboundAuthorizationRecheckStopsWithoutInvocation() = runBlocking {
        fixture("authorization_recheck_invalid") { driver, transport, journal, scope, failures, _ ->
            val malformed = "req_recheck_malformed"
            val rebound = "req_recheck_generation"
            val foreign = "req_recheck_foreign_id"
            transport.onLookup = { requested ->
                if (transport.lookups.count { it == requested } == 1) null
                else when (requested) {
                    malformed -> WireResponse(200, emptyList(),
                        """{"protocol":"2.1","data":{"request":""".toByteArray())
                    rebound, foreign -> WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf(
                        "protocol" to "2.1",
                        "data" to mapOf("request" to mapOf(
                            "requestId" to if (requested == foreign) "another-request" else rebound,
                            "deviceId" to "dev", "pairingGeneration" to if (requested == foreign) 1 else 2,
                            "grantRevision" to 7, "state" to "claimed", "expiresAt" to transport.expiresAt,
                        )),
                    ))).toByteArray())
                    else -> null
                }
            }

            listOf(malformed, rebound, foreign).forEachIndexed { index, id ->
                driver.accept(id)
                awaitJobs(scope)

                assertEquals("Initial lookup and one authorization recheck only for $id",
                    2, transport.lookups.count { it == id })
                assertEquals(index + 1, transport.claims.size)
                assertEquals(index + 1, transport.results.size)
                assertTerminalResult(
                    transport.results[index],
                    outcome = if (id == malformed) "failed" else "denied",
                    code = if (id == malformed) "DEVICE_AUTHORIZATION_CHECK_FAILED" else "CAPABILITY_DENIED",
                    reason = if (id == malformed) null else "GATEWAY_AUTHORIZATION_REVOKED",
                )
                assertNull(journal.read(id))
                assertEquals(0, transport.invocations.get())
            }
            assertTrue(transport.authorizationStates.isEmpty())
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

    @Test fun anExpiredUndeliveredSensitiveResultIsScrubbedWithoutLosingItsFenceAfterRestart() = runBlocking {
        val name = "expired_sensitive_result_fence"
        fixture(name) { driver, transport, journal, scope, failures, host ->
            val id = "req_expired_sensitive_result"
            val completed = retainUndeliveredSensitiveResult(driver, transport, journal, scope, id)
            transport.expiresAt = Instant.EPOCH.toString()
            val expired = JsonFields.obj(Json.of(completed.fields.associate { it.first to it.second } +
                ("expiresAt" to transport.expiresAt)))!!
            journal.write(id, Json.canonical(expired).toByteArray())

            driver.recoverPending()
            awaitJobs(scope)
            assertScrubbedFence(journal, id, expired, "REQUEST_EXPIRED")
            assertFenceSurvivesRestart(name, id, transport, journal, scope, host)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun aRevokedUndeliveredSensitiveResultIsScrubbedWithoutLosingItsFenceAfterRestart() = runBlocking {
        val name = "revoked_sensitive_result_fence"
        fixture(name) { driver, transport, journal, scope, failures, host ->
            val id = "req_revoked_sensitive_result"
            val completed = retainUndeliveredSensitiveResult(driver, transport, journal, scope, id)
            val context = ApplicationProvider.getApplicationContext<Application>()
            val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore())
            val revoked = DeviceExecutionDriver(context, scope, http, host, "acct", "dev", 1,
                name, { 7 }, { true }, TestDocumentKeys, localAuthorizationRevision = { 1L })

            revoked.recoverPending()
            awaitJobs(scope)
            assertScrubbedFence(journal, id, completed, "LOCAL_AUTHORIZATION_CHANGED")
            // Even restoring the old revision cannot make this request executable again.
            assertFenceSurvivesRestart(name, id, transport, journal, scope, host)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun legacyFencedSensitiveResultsAreScrubbedByRecoveryAndEventReplayWithoutReexecution() = runBlocking {
        val name = "legacy_sensitive_fence"
        // 同一应用只安装一次已签名插件；各场景使用独立请求，重启仍重新校验已安装包。
        fixture(name) { driver, transport, journal, scope, failures, host ->
            for (recoverFirst in listOf(true, false)) {
                for (expired in listOf(true, false)) {
                    val id = "req_legacy_sensitive_fence_${recoverFirst}_$expired"
                    val reason = if (expired) "REQUEST_EXPIRED" else "LOCAL_AUTHORIZATION_CHANGED"
                    val expiration = if (expired) Instant.EPOCH.toString() else transport.expiresAt
                    val legacy = JsonFields.obj(Json.of(mapOf(
                        "stage" to "fenced", "cancelRequested" to true, "fenceReason" to reason,
                        "expiresAt" to expiration, "localAuthorizationRevision" to 0L,
                        "hostAuthorizationDigest" to executionHostAuthorizationDigest(host),
                        "localApproval" to mapOf("requestId" to id, "operation" to "READ",
                            "parametersSha256" to "original-parameter-digest"),
                        "result" to mapOf("outcome" to "succeeded",
                            "data" to mapOf("private" to "synthetic-sensitive-legacy-body")),
                    )))!!
                    journal.write(id, Json.canonical(legacy).toByteArray())

                    if (recoverFirst) driver.recoverPending() else driver.enqueue(id)
                    awaitJobs(scope)
                    assertScrubbedFence(journal, id, legacy, reason)
                    assertFenceSurvivesRestart(name, id, transport, journal, scope, host)
                    assertEquals(0, transport.invocations.get())
                    assertTrue(transport.lookups.isEmpty())
                    assertTrue(transport.claims.isEmpty())
                    assertTrue(transport.results.isEmpty())
                    assertTrue(failures.isEmpty())
                }
            }
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

    private suspend fun retainUndeliveredSensitiveResult(driver: DeviceExecutionDriver, transport: Transport,
        journal: EncryptedDocuments, scope: CoroutineScope, id: String): JsonValue.JObject {
        transport.resultFailures = 4
        driver.accept(id)
        awaitJobs(scope)
        val saved = JsonFields.obj(Json.parse(journal.read(id)!!.decodeToString()))!!
        assertEquals("completed", JsonFields.string(saved, "stage"))
        assertEquals("succeeded", JsonFields.string(JsonFields.obj(JsonFields.field(saved, "result")), "outcome"))
        assertTrue("An undelivered result must remain available while it is still authorized",
            Json.canonical(saved).contains("synthetic-sensitive-recovery-body"))
        assertEquals(1, transport.invocations.get())
        assertEquals(4, transport.results.size)
        return saved
    }

    private fun assertScrubbedFence(journal: EncryptedDocuments, id: String, previous: JsonValue.JObject, reason: String) {
        val saved = JsonFields.obj(Json.parse(journal.read(id)!!.decodeToString()))!!
        assertEquals("fenced", JsonFields.string(saved, "stage"))
        assertEquals(true, JsonFields.bool(saved, "cancelRequested"))
        assertEquals(reason, JsonFields.string(saved, "fenceReason"))
        assertNull("A fenced request must retain no deliverable result", JsonFields.field(saved, "result"))
        assertFalse(Json.canonical(saved).contains("recovery-fixture"))
        assertFalse(Json.canonical(saved).contains("synthetic-sensitive-recovery-body"))
        assertFalse(Json.canonical(saved).contains("synthetic-sensitive-legacy-body"))
        for (field in listOf("expiresAt", "localAuthorizationRevision", "hostAuthorizationDigest", "localApproval")) {
            assertEquals("Fence must preserve $field",
                JsonFields.field(previous, field)?.let(Json::canonical),
                JsonFields.field(saved, field)?.let(Json::canonical))
        }
    }

    private suspend fun assertFenceSurvivesRestart(name: String, id: String, transport: Transport,
        journal: EncryptedDocuments, scope: CoroutineScope, host: ProductionPluginHost) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val restartedHost = createHost(context, install = false)
        assertEquals(executionHostAuthorizationDigest(host), executionHostAuthorizationDigest(restartedHost))
        val http = GatewayHttpClient(GatewayProfile("acct", "dev", "sess", "https://gateway.example"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore())
        val restarted = DeviceExecutionDriver(context, scope, http, restartedHost, "acct", "dev", 1,
            name, { 7 }, { true }, TestDocumentKeys)
        val saved = journal.read(id)!!.decodeToString()
        val calls = listOf(transport.lookups.size, transport.claims.size, transport.results.size, transport.invocations.get())
        repeat(2) {
            restarted.recoverPending()
            restarted.accept(id)
            awaitJobs(scope)
        }
        assertEquals(saved, journal.read(id)!!.decodeToString())
        assertEquals("A retained fence must block lookup, claim, result upload and plugin invocation after restart",
            calls, listOf(transport.lookups.size, transport.claims.size, transport.results.size, transport.invocations.get()))
    }

    private fun submittedResultData(request: WireRequest, field: String): String? {
        val envelope = JsonFields.obj(Json.parse(request.body.decodeToString())) ?: return null
        val result = JsonFields.obj(JsonFields.field(envelope, "result")) ?: return null
        val data = JsonFields.obj(JsonFields.field(result, "data")) ?: return null
        return JsonFields.string(data, field)
    }

    private fun submittedResultOutcome(request: WireRequest): String? {
        val envelope = JsonFields.obj(Json.parse(request.body.decodeToString())) ?: return null
        val result = JsonFields.obj(JsonFields.field(envelope, "result")) ?: return null
        return JsonFields.string(result, "outcome")
    }

    private fun assertTerminalResult(request: WireRequest, outcome: String, code: String, reason: String? = null) {
        val envelope = JsonFields.obj(Json.parse(request.body.decodeToString())) ?: error("RESULT_ENVELOPE_INVALID")
        val result = JsonFields.obj(JsonFields.field(envelope, "result")) ?: error("RESULT_PAYLOAD_INVALID")
        assertEquals(outcome, JsonFields.string(result, "outcome"))
        assertEquals(code, submittedResultData(request, "code"))
        if (reason != null) assertEquals(reason, submittedResultData(request, "reason"))
    }

    private suspend fun awaitJobs(scope: CoroutineScope, timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) {
            while (true) {
                val jobs = scope.coroutineContext[Job]!!.children.toList()
                if (jobs.isEmpty()) break
                jobs.forEach { it.join() }
            }
        }
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
                return """{"records":[{"id":"recovery-fixture","text":"synthetic-sensitive-recovery-body"}]}""".toByteArray()
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
        val lookups = CopyOnWriteArrayList<String>()
        var onLookup: suspend (String) -> WireResponse? = { null }
        var snapshotIds: List<String>? = null
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
        var onAuthorization: suspend (String, String) -> WireResponse? = { _, _ -> null }
        var afterResult: suspend () -> Unit = {}

        /** A saved Android journal corresponds to an already-issued, still-current Core receipt. */
        fun seedClaimed(vararg ids: String) {
            ids.forEach { id -> receipt(id); states[id] = "claimed" }
        }

        private fun receipt(id: String): Map<String, Any?> = receipts.getOrPut(id) {
            mapOf("claimId" to "claim_$id", "requestId" to id, "accountId" to receiptAccount,
                "deviceId" to "dev", "pairingGeneration" to 1, "grantRevision" to 7)
        }

        override suspend fun execute(request: WireRequest): WireResponse {
            if (request.target.endsWith("/sync/snapshot")) return WireResponse(200, emptyList(),
                Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to mapOf("baselineCursor" to "evt_durable_baseline",
                    "pendingDeviceRequests" to (snapshotIds ?: error("UNEXPECTED_SNAPSHOT")))))).toByteArray())
            val id = request.target.substringAfter("/device-requests/").substringBefore('/')
            if (request.method == "GET") {
                lookups += id
                onLookup(id)?.let { return it }
            }
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
                    onAuthorization(id, state)?.let { return it }
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
                    afterResult()
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
