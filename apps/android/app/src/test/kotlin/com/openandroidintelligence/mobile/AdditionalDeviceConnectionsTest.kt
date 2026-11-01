package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.KernelPrimitiveProvider
import com.openandroidintelligence.kernel.KernelPrimitiveRegistry
import com.openandroidintelligence.kernel.LocalGrantContext
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.TestAlpPackages
import com.openandroidintelligence.mobile.plugins.VerifiedCapability
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real publisher + driver + encrypted documents + signed WASM host, with only Core HTTP replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdditionalDeviceConnectionsTest {
    @Test fun aReadRequestCompletesWhenNoScreenRuntimeIsRegistered() = runBlocking {
        val previousScreenHost = ScreenHostRegistry.current
        ScreenHostRegistry.current = null
        try {
            fixture(CapabilityAuthorizationMode.READ_ONLY) {
                val second = remote("second", "account-second")
                val connections = connections()
                connections.sync(listOf(second.profile))
                second.awaitResult()
                assertEquals(1, calls.size)
                assertEquals(second.accountId, calls.single().accountId)
                assertEquals(second.binding.pairingId, calls.single().pairingId)
                assertEquals(primary, holder.currentBinding())
                assertEquals(listOf("executing"), second.authorizationStates.map { it.second })
                assertNull(connections.confirmation.value)
                assertNull(ScreenHostRegistry.current)
                connections.closeProfiles()
                assertFalse(holder.isConnectionCurrent(second.binding))
                assertTrue(holder.isConnectionCurrent(primary))
                assertNull(ScreenHostRegistry.current)
            }
        } finally {
            ScreenHostRegistry.current = previousScreenHost
        }
    }

    @Test fun secondaryAccountsReadWithTheGlobalPolicyWithoutSwitchingTheConversationBinding() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val second = remote("second", "account-second")
            val third = remote("third", "account-third").apply { pending = emptyList() }
            val connections = connections()
            connections.sync(listOf(second.profile, third.profile))
            second.awaitResult()
            // Both connections remain attached; later requests retain the same installation policy.
            third.pending = listOf(third.requestId)
            connections.sync(listOf(second.profile, third.profile))
            third.awaitResult()

            assertEquals(primary, holder.currentBinding())
            assertEquals(setOf(second.accountId, third.accountId), calls.map { it.accountId }.toSet())
            assertEquals(setOf(second.binding.pairingId, third.binding.pairingId), calls.map { it.pairingId }.toSet())
            assertTrue(holder.isConnectionCurrent(primary))
            assertTrue(holder.isConnectionCurrent(second.binding))
            assertTrue(holder.isConnectionCurrent(third.binding))
            assertEquals(holder.state.value!!.revision, second.lastPublishedLocalRevision)
            assertEquals(holder.state.value!!.revision, third.lastPublishedLocalRevision)
            assertEquals(listOf("executing"), second.authorizationStates.map { it.second })
            listOf(second, third).forEach { remote ->
                assertTrue(remote.requests.any { it.target == "/open-android-intelligence/v2/device-requests/pending" })
                assertTrue(remote.requests.none { it.target.contains("/conversations") || it.target.endsWith("/sync/snapshot") })
            }
            assertNull(connections.confirmation.value)
        }
    }

    @Test fun localConfirmationIsAggregatedAndATimedApprovalIsSharedWithAnotherAuthenticatedCore() = runBlocking {
        fixture(CapabilityAuthorizationMode.MANUAL) {
            val second = remote("second", "account-second")
            val third = remote("third", "account-third").apply { pending = emptyList() }
            val connections = connections()
            connections.sync(listOf(second.profile, third.profile))
            val confirmation = withTimeout(5_000L) { connections.confirmation.filterNotNull().first() }
            assertEquals(second.requestId, confirmation.requestId)
            assertEquals("Additional connections test", confirmation.agentName)
            assertEquals("Read isolated test records", confirmation.purpose)
            assertTrue(calls.isEmpty())
            assertEquals(listOf("awaiting_confirmation"), second.authorizationStates.map { it.second })

            connections.respond(confirmation.requestId, true, 5, confirmation.decisionToken)
            second.awaitResult()
            third.pending = listOf(third.requestId)
            connections.sync(listOf(second.profile, third.profile))
            third.awaitResult()
            assertEquals(listOf("awaiting_confirmation", "executing"), second.authorizationStates.map { it.second })
            assertEquals(listOf("executing"), third.authorizationStates.map { it.second })
            assertEquals(2, calls.size)
            assertEquals(primary, holder.currentBinding())
        }
    }

    @Test fun anUnacknowledgedPublicationCannotClaimRequestsOrBlockAHealthySibling() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val broken = remote("broken", "account-broken").apply { failPublication = true }
            val healthy = remote("healthy", "account-healthy")
            val connections = connections()
            connections.sync(listOf(broken.profile, healthy.profile))
            healthy.awaitResult()
            assertTrue(broken.requests.none { it.target.contains("/device-requests/") })
            assertEquals(listOf(healthy.accountId), calls.map { it.accountId })

            broken.failPublication = false
            connections.sync(listOf(broken.profile, healthy.profile))
            broken.awaitResult()
            assertEquals(setOf(broken.accountId, healthy.accountId), calls.map { it.accountId }.toSet())
        }
    }

    @Test fun aHungProfileHasABoundedCycleAndCannotStarveAnotherConnection() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val hung = remote("hung", "account-hung").apply { hangPairing = true }
            val healthy = remote("healthy", "account-healthy")
            val connections = connections(cycleTimeoutMillis = 2_000L)
            withTimeout(5_000L) { connections.sync(listOf(hung.profile, healthy.profile)) }
            healthy.awaitResult()
            assertTrue(hung.requests.none { it.target.contains("/device-requests/") })
            assertEquals(listOf(healthy.accountId), calls.map { it.accountId })
        }
    }

    @Test fun aHungRequestLookupDoesNotSkipTheNextPendingRequestOnTheSameProfile() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val second = remote("second", "account-second").apply {
                pending = listOf("hung-request", requestId)
                hungRequestId = "hung-request"
            }
            val connections = connections(requestTimeoutMillis = 200L)
            withTimeout(5_000L) { connections.sync(listOf(second.profile)) }
            second.awaitResult()
            assertTrue(second.requests.any { it.target.endsWith("/device-requests/hung-request") })
            assertTrue(second.requests.none { it.target.endsWith("/hung-request/claim") })
            assertEquals(1, calls.size)
            assertEquals(listOf(second.accountId), calls.map { it.accountId })
        }
    }

    @Test fun removingAProfileClosesItsConfirmationAndPreservesThePrimaryAuthenticatedBinding() = runBlocking {
        fixture(CapabilityAuthorizationMode.MANUAL) {
            val second = remote("second", "account-second")
            val connections = connections()
            connections.sync(listOf(second.profile))
            withTimeout(5_000L) { connections.confirmation.filterNotNull().first() }
            connections.sync(emptyList())
            connections.respond(second.requestId, true, 60)
            assertNull(connections.confirmation.value)
            assertFalse(holder.isConnectionCurrent(second.binding))
            assertTrue(holder.isConnectionCurrent(primary))
            assertEquals(primary, holder.currentBinding())
            assertTrue(calls.isEmpty())
            assertTrue(second.results.isEmpty())
        }
    }

    @Test fun aPendingListForAnotherDeviceCannotClaimOrExecuteAnyRequest() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val second = remote("second", "account-second").apply { pendingDeviceId = "foreign-device" }
            val connections = connections()
            connections.sync(listOf(second.profile))
            assertTrue(second.requests.any { it.target == "/open-android-intelligence/v2/device-requests/pending" })
            assertTrue(second.requests.none { it.target.contains("/device-requests/") && !it.target.endsWith("/pending") })
            assertTrue(second.results.isEmpty())
            assertTrue(calls.isEmpty())
            assertNull(connections.confirmation.value)

            second.pendingDeviceId = second.profile.deviceId
            connections.sync(listOf(second.profile))
            second.awaitResult()
            assertEquals(1, calls.size)
            assertEquals(second.accountId, calls.single().accountId)
        }
    }

    @Test fun policyOrSessionRevocationWhileThePendingLookupIsInFlightFencesEveryClaim() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val changedPolicy = remote("policy", "account-policy")
            changedPolicy.afterPendingRequests = {
                host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(enabled = false))
            }
            val connections = connections()
            connections.sync(listOf(changedPolicy.profile))
            assertTrue(changedPolicy.requests.none { it.target.endsWith("/claim") })
            assertTrue(calls.isEmpty())

            host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
            val expiredSession = remote("session", "account-session")
            expiredSession.afterPendingRequests = { expiredSession.current.set(false) }
            connections.sync(listOf(expiredSession.profile))
            assertTrue(expiredSession.requests.none { it.target.endsWith("/claim") })
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun duplicateRequestIdsCannotReceiveAnAmbiguousLocalApproval() = runBlocking {
        fixture(CapabilityAuthorizationMode.MANUAL) {
            val second = remote("second", "account-second", "same-request")
            val third = remote("third", "account-third", "same-request")
            val connections = connections()
            connections.sync(listOf(second.profile, third.profile))
            waitFor { second.authorizationStates.isNotEmpty() && third.authorizationStates.isNotEmpty() }
            // Both drivers have reached their real confirmation state; refresh the aggregate once more.
            connections.sync(listOf(second.profile, third.profile))
            assertNull(connections.confirmation.value)
            connections.respond("same-request", true, 60)
            assertTrue(calls.isEmpty())
            assertTrue(second.results.isEmpty())
            assertTrue(third.results.isEmpty())
        }
    }

    @Test fun aDecisionTokenFromOneCoreCannotApproveAnotherCoreUsingTheSameRawRequestId() = runBlocking {
        fixture(CapabilityAuthorizationMode.MANUAL) {
            val second = remote("second", "account-second", "shared-request")
            val third = remote("third", "account-third", "shared-request").apply { pending = emptyList() }
            val connections = connections()
            connections.sync(listOf(second.profile, third.profile))
            val first = withTimeout(5_000L) { connections.confirmation.filterNotNull().first() }
            assertTrue(first.decisionToken.isNotBlank())
            third.pending = listOf(third.requestId)
            connections.sync(listOf(second.profile, third.profile))
            waitFor { second.authorizationStates.isNotEmpty() && third.authorizationStates.isNotEmpty() &&
                connections.confirmation.value == null }
            connections.respond(first.requestId, true, 0, first.decisionToken)
            assertTrue(calls.isEmpty())

            connections.sync(listOf(third.profile))
            val next = withTimeout(5_000L) { connections.confirmation.filterNotNull().first() }
            assertEquals(first.requestId, next.requestId)
            assertNotEquals(first.decisionToken, next.decisionToken)
            connections.respond(next.requestId, true, 0, first.decisionToken)
            assertEquals(next, connections.confirmation.value)
            assertTrue(calls.isEmpty())
            assertTrue(third.results.isEmpty())
            connections.respond(next.requestId, true, 0, next.decisionToken)
            third.awaitResult()
            assertEquals(listOf(third.accountId), calls.map { it.accountId })
            assertTrue(second.results.isEmpty())
        }
    }

    @Test fun failedDeliveryIsRecoveredFromItsProfileJournalWithoutExecutingAgain() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val second = remote("second", "account-second").apply { resultFailures.set(4); pending = emptyList() }
            val third = remote("third", "account-third")
            val original = connections()
            original.sync(listOf(second.profile, third.profile))
            third.awaitResult()
            val idleJobs = job.children.flatMap { it.children }.toSet()
            second.pending = listOf(second.requestId)
            original.sync(listOf(second.profile, third.profile))
            waitFor(timeoutMillis = 15_000L) { second.resultAttempts.size == 4 }
            // An attempted upload is not a finished delivery window. Join the actual request jobs
            // before closing profiles, otherwise cancelAll legitimately revokes an in-flight ticket.
            withTimeout(15_000L) {
                job.children.flatMap { it.children }.filter { it !in idleJobs }.toList().forEach { it.join() }
            }
            original.closeProfiles()
            assertEquals(2, calls.size)
            second.pending = emptyList()

            val restarted = connections()
            restarted.sync(listOf(second.profile, third.profile))
            try { second.awaitResult(timeoutMillis = 15_000L) }
            catch (failure: AssertionError) { throw AssertionError("Recovery diagnostics: $diagnostics", failure) }
            assertEquals(2, calls.size)
            assertEquals(5, second.resultAttempts.size)
            assertTrue(second.resultAttempts.all { it.body.contentEquals(second.resultAttempts.first().body) })
            assertEquals(listOf("executing"), second.authorizationStates.map { it.second })
            assertEquals(1, third.resultAttempts.size)
        }
    }

    @Test fun aLatePendingLookupAfterCloseCannotReviveADeviceConnection() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val second = remote("second", "account-second")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            second.afterPendingRequests = { entered.complete(Unit); release.await() }
            val connections = connections()
            val cycle = scope.launch { connections.sync(listOf(second.profile)) }
            withTimeout(5_000L) { entered.await() }
            connections.closeProfiles()
            release.complete(Unit)
            withTimeout(5_000L) { cycle.join() }
            assertFalse(holder.isConnectionCurrent(second.binding))
            assertTrue(second.requests.none { it.target.endsWith("/claim") })
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun invalidOwnershipAndDuplicateProfileIdsCannotStartOrRetainDeviceWork() = runBlocking {
        fixture(CapabilityAuthorizationMode.READ_ONLY) {
            val second = remote("second", "account-second").apply { current.set(false) }
            val connections = connections()
            connections.sync(listOf(second.profile))
            assertTrue(second.requests.isEmpty())
            assertFalse(holder.isConnectionCurrent(second.binding))
            second.current.set(true)
            val duplicate = second.profile.copy(accountId = "foreign-account")
            assertEquals("DUPLICATE_DEVICE_PROFILE",
                runCatching { connections.sync(listOf(second.profile, duplicate)) }.exceptionOrNull()?.message)
            assertTrue(second.requests.isEmpty())
            assertTrue(calls.isEmpty())
        }
    }

    private suspend fun fixture(mode: CapabilityAuthorizationMode, block: suspend Fixture.() -> Unit) {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val fixture = Fixture(mode)
        try { fixture.block() } finally { fixture.close() }
    }

    private class Fixture(mode: CapabilityAuthorizationMode) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val primary = PairingGrantBinding("https://primary.example", "account-primary", "installation")
        val audit = AndroidAuditStore()
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit).apply { bind(primary) }
        val failures = CopyOnWriteArrayList<Throwable>()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error })
        val calls = CopyOnWriteArrayList<LocalGrantContext>()
        private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        private val previousLogSink = com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink
        val diagnostics = CopyOnWriteArrayList<String>()
        private val previousProvider = KernelPrimitiveRegistry.provider("kernel.notifications.read")
        private val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.notifications.read"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                calls += context
                return Json.canonical(Json.of(mapOf("records" to listOf(mapOf("account" to context.accountId))))).toByteArray()
            }
        }
        private val managers = mutableListOf<AdditionalDeviceConnections>()
        val host: ProductionPluginHost
        val entry: VerifiedCapability

        init {
            com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { tag, message -> diagnostics += "$tag:$message" }
            KernelPrimitiveRegistry.register(provider)
            val trust = DeveloperTrustMode()
            host = ProductionPluginHost(context, holder, trust, NativePluginLoader(trust), "installation")
            host.installStore.install(TestAlpPackages.compiledNotificationsPackage())
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities), host.runtimes,
                audit, trust, host.nativeLoader, host.selector, holder::currentKernelGrant,
                mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
            host.enable("org.openandroidintelligence.notifications", true)
            entry = host.entries("org.openandroidintelligence.notifications").single()
            host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = mode))
        }

        fun connections(cycleTimeoutMillis: Long = 15_000L, requestTimeoutMillis: Long = 5_000L) = AdditionalDeviceConnections(
            context, scope, host, holder, "installation", keyProvider = TestDocumentKeys,
            cycleTimeoutMillis = cycleTimeoutMillis, requestTimeoutMillis = requestTimeoutMillis,
        ).also { managers += it }

        fun remote(id: String, account: String, requestId: String = "request-$id") = Remote(id, account, requestId, entry)

        suspend fun close() {
            managers.forEach { it.closeProfiles() }
            withTimeout(10_000L) { job.cancelAndJoin() }
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = previousLogSink
            assertTrue("Unexpected background failures: $failures", failures.isEmpty())
        }
    }

    private class Remote(val id: String, val accountId: String, val requestId: String, private val entry: VerifiedCapability) : GatewayByteTransport {
        val binding = PairingGrantBinding("https://$id.example", accountId, "installation")
        val current = AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<WireRequest>()
        val authorizationStates = CopyOnWriteArrayList<Pair<String, String>>()
        val resultAttempts = CopyOnWriteArrayList<WireRequest>()
        val results = ConcurrentHashMap<String, String>()
        val resultFailures = AtomicInteger()
        private val states = ConcurrentHashMap<String, String>()
        private val receipts = ConcurrentHashMap<String, Map<String, Any?>>()
        private val expiresAt = Instant.now().plusSeconds(120).toString()
        @Volatile var pending = listOf(requestId)
        @Volatile var pendingDeviceId = "device-$id"
        @Volatile var failPublication = false
        @Volatile var hangPairing = false
        @Volatile var hungRequestId: String? = null
        @Volatile var afterPendingRequests: suspend () -> Unit = {}
        @Volatile var revision = 1
        @Volatile private var digest: String? = null
        @Volatile var lastPublishedLocalRevision: Long? = null
        private val http = GatewayHttpClient(GatewayProfile(accountId, "device-$id", "session-$id", binding.gatewayId),
            this, { ByteArray(64) }, InMemoryEventCursorStore())
        val profile = DeviceConnectionProfile(id, binding.gatewayId, accountId, "device-$id", 1, http, current::get, 1)

        override suspend fun execute(request: WireRequest): WireResponse {
            requests += request
            val path = request.target
            val activeId = path.substringAfter("/device-requests/").substringBefore('/')
            val data: Map<String, Any?> = when {
                path.endsWith("/pairings/current") -> {
                    assertEquals("GET", request.method)
                    if (hangPairing) awaitCancellation()
                    mapOf("grantRevision" to revision, "grantDigest" to digest, "capabilityPublicationVersion" to 2)
                }
                path.endsWith("/pairings/current/capabilities") -> {
                    assertEquals("POST", request.method)
                    if (failPublication) throw IOException("Isolated publication failure")
                    val body = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                    assertEquals(revision, JsonFields.int(body, "expectedGrantRevision"))
                    val next = Json.sha256(JsonValue.JObject(body.fields.filter { it.first != "expectedGrantRevision" }))
                    if (digest != next) revision++
                    digest = next
                    lastPublishedLocalRevision = JsonFields.long(body, "localGrantRevision")
                    mapOf("grantRevision" to revision, "grantDigest" to digest)
                }
                path == "/open-android-intelligence/v2/device-requests/pending" -> {
                    assertEquals("GET", request.method)
                    assertTrue(request.headers.any {
                        it.name.equals("X-Open-Android-Intelligence-Signature", ignoreCase = true) && it.value.isNotBlank()
                    })
                    val value = mapOf("deviceId" to pendingDeviceId, "pairingGeneration" to 1, "grantRevision" to revision,
                        "pendingDeviceRequests" to pending.filter { states[it] != "succeeded" }.take(128))
                    afterPendingRequests()
                    value
                }
                path.endsWith("/claim") -> {
                    assertEquals("POST", request.method)
                    val body = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                    assertEquals(revision, JsonFields.int(body, "grantRevision"))
                    states.putIfAbsent(activeId, "claimed")
                    receipts.getOrPut(activeId) {
                        mapOf("claimId" to "claim-$activeId", "requestId" to activeId, "accountId" to accountId,
                            "deviceId" to profile.deviceId, "pairingGeneration" to 1, "grantRevision" to revision)
                    }
                }
                path.endsWith("/authorization") -> {
                    assertEquals("POST", request.method)
                    val state = JsonFields.string(JsonFields.obj(Json.parse(request.body.decodeToString())), "state")!!
                    assertTrue(state in setOf("awaiting_confirmation", "executing"))
                    authorizationStates += activeId to state
                    mapOf("requestId" to activeId, "localAuthorizationState" to state)
                }
                path.endsWith("/result") -> {
                    assertEquals("POST", request.method)
                    resultAttempts += request
                    val body = request.body.decodeToString()
                    val submitted = JsonFields.obj(Json.parse(body))!!
                    val receipt = receipts[activeId] ?: error("RESULT_REQUIRES_REAL_CLAIM")
                    assertEquals(receipt["claimId"], JsonFields.string(submitted, "claimId"))
                    assertEquals(receipt["grantRevision"], JsonFields.int(submitted, "grantRevision"))
                    assertTrue(states[activeId] in setOf("claimed", "succeeded"))
                    assertEquals("succeeded", JsonFields.string(JsonFields.obj(JsonFields.field(submitted, "result")), "outcome"))
                    if (resultFailures.getAndDecrement() > 0) throw IOException("Isolated result upload failure")
                    val previous = results.putIfAbsent(activeId, body)
                    assertTrue("Result retries must preserve the adopted body", previous == null || previous == body)
                    states[activeId] = "succeeded"
                    mapOf("requestId" to activeId, "state" to "succeeded")
                }
                path.contains("/device-requests/") -> {
                    assertEquals("GET", request.method)
                    if (activeId == hungRequestId) awaitCancellation()
                    mapOf("request" to mapOf("requestId" to activeId, "deviceId" to profile.deviceId,
                        "pairingGeneration" to 1, "grantRevision" to revision,
                        "state" to (states[activeId] ?: "pending"), "expiresAt" to expiresAt,
                        "risk" to entry.risk, "capability" to mapOf("id" to entry.id, "version" to entry.version),
                        "provider" to mapOf("pluginId" to entry.identity.pluginId, "authorKeyId" to "sha256:${entry.identity.authorKeyFingerprint}"),
                        "parameters" to mapOf("limit" to 1),
                        "caller" to mapOf("agentName" to "Additional connections test", "purpose" to "Read isolated test records")))
                }
                else -> error("UNEXPECTED_DEVICE_ENDPOINT")
            }
            return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to data))).toByteArray())
        }

        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        suspend fun awaitResult(timeoutMillis: Long = 8_000L) {
            try { waitFor(timeoutMillis) { results.containsKey(requestId) } }
            catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Missing result for $requestId; requests=" + requests.map { it.method + " " + it.target } +
                    "; states=" + states + "; uploads=" + resultAttempts.size + "; revision=" + revision, timeout)
            }
        }
    }

    private companion object {
        suspend fun waitFor(timeoutMillis: Long = 8_000L, predicate: () -> Boolean) {
            withTimeout(timeoutMillis) { while (!predicate()) delay(10L) }
        }
    }
}
