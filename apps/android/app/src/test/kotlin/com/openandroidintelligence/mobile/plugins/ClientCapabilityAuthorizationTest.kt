package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.schema.CapabilitySchemaValidator
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.SharedPreferencesPairingGrantStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClientCapabilityAuthorizationTest {
    private val binding = PairingGrantBinding("https://client.example", "account-client", "install-client")

    @Test
    fun aDisabledPluginCannotEnableItselfByReplacingEveryDeclarationInASignedUpdate() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.sms.read")
        var calls = 0
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.read"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                calls++
                return """{"records":["updated-provider"]}""".toByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val author = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val oldDeclarations = Json.of(mapOf(
                "provides" to listOf(mapOf("id" to "org.openandroidintelligence.sms.previous",
                    "version" to "1.0.0", "schema" to "schemas/sms.json")),
                "depends" to emptyList<Any>(), "kernelPrimitives" to emptyList<Any>(),
            )) as JsonValue.JObject
            val audit = AndroidAuditStore()
            val store = InMemoryPairingGrantStore()
            val grants = PairingGrantStateHolder(store, audit).apply { bind(binding) }
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), binding.installationId)
            host.installStore.install(TestAlpPackages.compiledSmsPackage(author = author, capabilityDeclaration = oldDeclarations))
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities), host.runtimes,
                audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant, mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
            val id = "org.openandroidintelligence.sms"
            host.enable(id, true)
            host.setCapabilityEnabled(id, false)

            // Accepting an update's new declared primitives does not reopen the separate local switch.
            host.installStore.install(TestAlpPackages.compiledSmsPackage(author = author, version = "2.0.0"), approvalGranted = true)
            host.reload()
            grants.bind(binding)
            val entry = host.entries(id).first { it.id.endsWith(".query") }
            assertTrue(host.isEnabled(id))
            assertFalse(host.isCapabilityEnabled(id))
            assertTrue(host.authorizedBindings().isEmpty())
            assertTrue(runCatching { host.invoke(entry.identity, binding.accountId, entry.key,
                Json.parse("""{"limit":1}"""), "disabled-after-update") }.exceptionOrNull() is CapabilityDenied)
            assertEquals(0, calls)
            assertFalse(host.isBackgroundEnabled(id))

            host.setCapabilityEnabled(id, true)
            assertTrue(host.isCapabilityEnabled(id))
            val parameters = Json.parse("""{"limit":1}""")
            assertEquals(CapabilityAuthorizationMode.MANUAL, host.capabilityPolicy(id, entry.key).mode)
            assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                host.requestAuthorization("explicitly-reopened", entry, parameters))
            host.approveRequest("explicitly-reopened", entry, parameters, durationMinutes = 0, remainingRequestMillis = 60_000L)
            assertTrue(host.invoke(entry.identity, binding.accountId, entry.key,
                parameters, "explicitly-reopened").decodeToString().contains("updated-provider"))
            assertEquals(1, calls)
            assertFalse(host.isBackgroundEnabled(id))
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun withCapabilityHost(
        readOnlyPackage: Boolean = false,
        beforePrimitive: (PluginKernel, KernelCallContext, String, ByteArray) -> Unit = { _, _, _, _ -> },
        action: (ProductionPluginHost, PairingGrantStateHolder, () -> Int, (String?) -> Unit) -> Unit,
    ) {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        val primitive = if (readOnlyPackage) "kernel.notifications.read" else "kernel.sms.read"
        val pluginId = if (readOnlyPackage) NOTIFICATION_PLUGIN_ID else SMS_PLUGIN_ID
        val previous = KernelPrimitiveRegistry.provider(primitive)
        var calls = 0
        var unavailable: String? = null
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = primitive
            override fun availability(): String? = unavailable
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray {
                if (unavailable != null) throw CapabilityDenied(primitiveId)
                calls++
                return """{"records":["local-authority"]}""".toByteArray()
            }
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val store = InMemoryPairingGrantStore()
            val grants = PairingGrantStateHolder(store, audit).apply { bind(binding) }
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), binding.installationId)
            host.installStore.install(if (readOnlyPackage) TestAlpPackages.compiledNotificationsPackage()
                else TestAlpPackages.compiledSmsPackage())
            lateinit var kernel: PluginKernel
            kernel = PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                mediate = { call, primitiveId, input ->
                    beforePrimitive(kernel, call, primitiveId, input)
                    host.mediate(call, primitiveId, input)
                }, authorizeInvocation = host::authorizeKernelInvocation)
            host.attach(kernel)
            host.enable(pluginId, true)
            action(host, grants, { calls }, { unavailable = it })
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previous != null) KernelPrimitiveRegistry.register(previous)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        }
    }

    @Test
    fun manualReadIsPublishedButRequiresLocalConfirmationAndLocalOffStillWins() = withCapabilityHost(readOnlyPackage = true) { host, grants, calls, _ ->
        val id = NOTIFICATION_PLUGIN_ID
        val entry = host.entries(id).single()
        val parameters = Json.parse("""{"limit":1}""")
        assertEquals(DeviceOperation.READ, host.authorizationScope(entry, parameters).operation)
        assertTrue(host.isCapabilityEnabled(id))
        assertEquals(CapabilityPolicy(), host.capabilityPolicy(id, entry.key))
        assertTrue(host.authorizedBindings().any { it["capabilityId"] == entry.id })
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestAuthorization("manual-read", entry, parameters))
        val unapproved = runCatching { host.invoke(entry.identity, binding.accountId, entry.key, parameters, "manual-read") }.exceptionOrNull()
        assertTrue(unapproved is CapabilityDenied)
        assertEquals("LOCAL_CONFIRMATION_REQUIRED", (unapproved as CapabilityDenied).primitive)
        assertEquals(0, calls())

        host.approveRequest("manual-read", entry, parameters, durationMinutes = 0, remainingRequestMillis = 60_000L)
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestAuthorization("other-request", entry, parameters))
        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
            host.requestAuthorization("manual-read", entry, Json.parse("""{"limit":2}""")))
        assertTrue(host.invoke(entry.identity, binding.accountId, entry.key, parameters, "manual-read")
            .decodeToString().contains("local-authority"))
        host.finishRequestAuthorization("manual-read")
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestAuthorization("manual-read", entry, parameters))
        assertEquals(1, calls())
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)

        host.setCapabilityEnabled(id, false)
        assertFalse(host.isCapabilityEnabled(id))
        assertEquals("LOCAL_CAPABILITY_DISABLED", host.capabilitySnapshot().inventory.single()["reason"])
        assertTrue(host.authorizedBindings().isEmpty())
        assertTrue(runCatching { host.invoke(entry.identity, binding.accountId, entry.key, parameters, "denied-call") }
            .exceptionOrNull() is CapabilityDenied)
        assertEquals(1, calls())
        host.reload()
        assertTrue(host.isEnabled(id))
        assertFalse(host.isCapabilityEnabled(id))
        host.setCapabilityEnabled(id, true)
        assertEquals(CapabilityPolicy(), host.capabilityPolicy(id, entry.key))
        assertTrue(host.authorizedBindings().any { it["capabilityId"] == entry.id })
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestAuthorization("reopened-read", entry, parameters))
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)
    }

    @Test
    fun changingCoreAndAccountPreservesTheGlobalModeAndLocalDeny() = withCapabilityHost { host, grants, _, _ ->
        val id = SMS_PLUGIN_ID
        val entry = host.entries(id).first { it.id.endsWith(".query") }
        val policy = CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY)
        host.setCapabilityPolicy(id, entry.key, policy)
        host.setCapabilityEnabled(id, false)
        val revision = grants.state.value!!.revision

        val otherBinding = PairingGrantBinding("https://other-core.example", "other-account", binding.installationId)
        grants.bind(otherBinding)
        assertEquals(otherBinding, grants.currentBinding())
        assertEquals(revision, grants.state.value!!.revision)
        assertEquals(policy.copy(enabled = false), host.capabilityPolicy(id, entry.key))
        assertTrue(host.authorizedBindings().isEmpty())
        assertFalse(grants.currentKernelGrant(otherBinding.pairingId)!!.backgroundSync)

        host.setCapabilityEnabled(id, true)
        assertEquals(policy, host.capabilityPolicy(id, entry.key))
        grants.bind(binding)
        assertEquals(policy, host.capabilityPolicy(id, entry.key))
        assertTrue(host.isCapabilityEnabled(id))
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)
    }

    @Test
    fun aSingleApprovalCannotBeSharedByTheSameRawRequestIdOnAnotherAuthenticatedCore() = withCapabilityHost(readOnlyPackage = true) { host, grants, calls, _ ->
        val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
        val parameters = Json.parse("""{"limit":1}""")
        val requestId = "same-raw-request-id"
        val otherBinding = PairingGrantBinding("https://other-core.example", binding.accountId, binding.installationId)
        grants.attachConnection(binding)
        grants.attachConnection(otherBinding)
        try {
            assertEquals(CapabilityAuthorizationMode.MANUAL, host.capabilityPolicy(entry.identity.pluginId, entry.key).mode)
            val approval = host.approveRequest(requestId, entry, parameters, durationMinutes = 0,
                remainingRequestMillis = 60_000L, binding = binding)
            val originalDecision = host.requestAuthorization(requestId, entry, parameters, binding = binding)
            assertTrue(originalDecision is CapabilityAuthorization.Allowed)
            assertEquals(approval.id, (originalDecision as CapabilityAuthorization.Allowed).approval?.id)
            assertTrue(host.authorizationIsCurrent(requestId, entry, parameters, binding = binding))
            assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                host.requestAuthorization(requestId, entry, parameters, binding = otherBinding))
            assertFalse(host.authorizationIsCurrent(requestId, entry, parameters, binding = otherBinding))

            val denied = runCatching { host.invoke(entry.identity, otherBinding.accountId, entry.key, parameters,
                requestId, authenticatedBinding = otherBinding) }.exceptionOrNull()
            assertTrue(denied is CapabilityDenied)
            assertEquals("LOCAL_CONFIRMATION_REQUIRED", (denied as CapabilityDenied).primitive)
            assertEquals(0, calls())
            assertTrue(host.invoke(entry.identity, binding.accountId, entry.key, parameters, requestId,
                authenticatedBinding = binding).decodeToString().contains("local-authority"))
            assertEquals(1, calls())
            host.finishRequestAuthorization(requestId, binding = binding)
            assertFalse(host.authorizationIsCurrent(requestId, entry, parameters, binding = binding))
            assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                host.requestAuthorization(requestId, entry, parameters, binding = otherBinding))
            assertEquals(binding, grants.currentBinding())
        } finally {
            grants.detachConnection(otherBinding)
        }
    }

    @Test
    fun aFiveMinuteApprovalCanBeSharedByTheSameRawRequestIdOnAnotherAuthenticatedCore() = withCapabilityHost(readOnlyPackage = true) { host, grants, calls, _ ->
        val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
        val parameters = Json.parse("""{"limit":1}""")
        val requestId = "same-raw-request-id"
        val otherBinding = PairingGrantBinding("https://other-core.example", binding.accountId, binding.installationId)
        grants.attachConnection(binding)
        grants.attachConnection(otherBinding)
        try {
            val approval = host.approveRequest(requestId, entry, parameters, durationMinutes = 5,
                remainingRequestMillis = 60_000L, binding = binding)
            listOf(binding, otherBinding).forEach { owner ->
                val decision = host.requestAuthorization(requestId, entry, parameters, binding = owner)
                assertTrue(decision is CapabilityAuthorization.Allowed)
                assertEquals(approval.id, (decision as CapabilityAuthorization.Allowed).approval?.id)
                assertTrue(host.authorizationIsCurrent(requestId, entry, parameters, binding = owner))
                assertTrue(host.invoke(entry.identity, owner.accountId, entry.key, parameters, requestId,
                    authenticatedBinding = owner).decodeToString().contains("local-authority"))
                host.finishRequestAuthorization(requestId, binding = owner)
            }
            assertEquals(2, calls())
            assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                host.requestAuthorization(requestId, entry, Json.parse("""{"limit":2}"""), binding = otherBinding))
            host.revokeTemporaryApprovals()
            assertFalse(host.authorizationIsCurrent(requestId, entry, parameters, binding = binding))
            assertFalse(host.authorizationIsCurrent(requestId, entry, parameters, binding = otherBinding))
            assertEquals(binding, grants.currentBinding())
        } finally {
            grants.detachConnection(otherBinding)
        }
    }

    @Test
    fun aTimedApprovalIsSharedAcrossCoreAccountsButKeepsItsParametersAndRevocation() = withCapabilityHost(readOnlyPackage = true) { host, grants, calls, _ ->
        val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
        val parameters = Json.parse("""{"limit":1}""")
        val approval = host.approveRequest("original-request", entry, parameters, durationMinutes = 5, remainingRequestMillis = 60_000L)
        val otherBinding = PairingGrantBinding("https://other-core.example", "other-account", binding.installationId)
        grants.bind(otherBinding)

        val shared = host.requestAuthorization("other-core-request", entry, parameters)
        assertTrue(shared is CapabilityAuthorization.Allowed)
        assertEquals(approval.id, (shared as CapabilityAuthorization.Allowed).approval?.id)
        assertTrue(host.invoke(entry.identity, otherBinding.accountId, entry.key, parameters, "other-core-request")
            .decodeToString().contains("local-authority"))
        assertEquals(1, calls())
        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
            host.requestAuthorization("out-of-scope", entry, Json.parse("""{"limit":2}""")))

        host.revokeTemporaryApprovals()
        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
            host.requestAuthorization("after-revocation", entry, parameters))
        assertEquals(1, calls())
    }

    @Test
    fun readOnlyModeAllowsReadWithoutGrantingBackground() = withCapabilityHost(readOnlyPackage = true) { host, grants, calls, _ ->
        val entry = host.entries(NOTIFICATION_PLUGIN_ID).single()
        val parameters = Json.parse("""{"limit":1}""")
        host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
        assertEquals(DeviceOperation.READ, host.authorizationScope(entry, parameters).operation)
        assertTrue(host.requestAuthorization("read-only", entry, parameters) is CapabilityAuthorization.Allowed)
        assertTrue(host.invoke(entry.identity, binding.accountId, entry.key, parameters, "read-only")
            .decodeToString().contains("local-authority"))
        assertEquals(1, calls())
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)
    }

    @Test
    fun readOnlySmsQueryRunsWithItsOwnSessionAndCannotCallSchedulerPrimitives() {
        val schedulerPrimitives = setOf("kernel.scheduler.set", "kernel.scheduler.cancel", "kernel.scheduler.read")
        val deniedAttempts = mutableListOf<String>()
        var querySession: SessionConstraints? = null
        var schedulerMediations = 0
        withCapabilityHost(beforePrimitive = { kernel, call, primitive, _ ->
            if (primitive in schedulerPrimitives) schedulerMediations++
            if (primitive == "kernel.sms.read") {
                querySession = call.session
                val declared = kernel.registrationFor(call.identity.pluginId)!!.declaredPrimitives
                assertTrue(declared.containsAll(schedulerPrimitives))
                // Exercise the real kernel while the real WASM query's call context is active.
                // The host mediator remains unchanged and must never receive these attempts.
                schedulerPrimitives.forEach { forbidden ->
                    val failure = runCatching { kernel.call(call.identity, forbidden, "{}".toByteArray()) }.exceptionOrNull()
                    assertTrue("Query must deny $forbidden at the kernel boundary", failure is CapabilityDenied)
                    assertEquals(forbidden, (failure as CapabilityDenied).primitive)
                    deniedAttempts += forbidden
                }
            }
        }) { host, grants, calls, _ ->
            val entry = host.entries(SMS_PLUGIN_ID).first { it.id.endsWith(".query") }
            val parameters = Json.parse("""{"limit":1}""")
            val allowedPrimitives = setOf("kernel.sms.read", "kernel.background.run", "kernel.store.read", "kernel.store.keys")
            assertTrue("Query must retain SMS read", "kernel.sms.read" in entry.primitives)
            assertTrue("Query must retain separate background eligibility", "kernel.background.run" in entry.primitives)
            assertTrue("Query must not borrow another operation's primitives", allowedPrimitives.containsAll(entry.primitives))
            assertEquals(DeviceOperation.READ, host.authorizationScope(entry, parameters).operation)
            host.entries(SMS_PLUGIN_ID).filter { it.key != entry.key }.forEach { other ->
                host.setCapabilityPolicy(SMS_PLUGIN_ID, other.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
            }
            host.setCapabilityPolicy(SMS_PLUGIN_ID, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
            schedulerPrimitives.forEach { primitive ->
                assertTrue(primitive in host.hostCapabilities)
                assertTrue(grants.currentKernelGrant(binding.pairingId)!!.permits(primitive))
            }
            assertTrue(host.requestAuthorization("sms-read-only", entry, parameters) is CapabilityAuthorization.Allowed)
            assertTrue(host.invoke(entry.identity, binding.accountId, entry.key, parameters, "sms-read-only")
                .decodeToString().contains("local-authority"))
            assertEquals(1, calls())
            assertEquals(entry.primitives + entry.key, querySession!!.primitives)
            assertTrue(querySession!!.primitives.intersect(schedulerPrimitives).isEmpty())
            assertEquals(schedulerPrimitives, deniedAttempts.toSet())
            assertEquals(schedulerPrimitives.size, deniedAttempts.size)
            assertEquals(0, schedulerMediations)
            assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)

            val injectedOperations = listOf(
                mapOf("operation" to "schedule", "runAt" to java.time.Instant.now().plusSeconds(120).toString(),
                    "query" to mapOf("limit" to 1)),
                mapOf("operation" to "cancel", "jobId" to "job_out_of_query_scope"),
                mapOf("operation" to "job-status", "jobId" to "job_out_of_query_scope"),
            )
            injectedOperations.forEachIndexed { index, injected ->
                val failure = runCatching { host.invoke(entry.identity, binding.accountId, entry.key,
                    Json.of(injected), "query-injected-operation-$index") }.exceptionOrNull()
                assertEquals("SCHEMA_INVALID", failure?.message)
            }
            assertEquals(1, calls())
            assertEquals(0, schedulerMediations)
        }
    }

    @Test
    fun readOnlyModeStillRequiresAnExactLocalApprovalForSchedulingAndCancellation() = withCapabilityHost { host, _, calls, _ ->
        val operations = listOf(
            ".schedule" to Json.of(mapOf("operation" to "schedule",
                "runAt" to java.time.Instant.now().plusSeconds(120).toString(), "query" to mapOf("limit" to 1))),
            ".cancel" to Json.of(mapOf("operation" to "cancel", "jobId" to "job_pending_confirmation")),
        )
        operations.forEachIndexed { index, (suffix, parameters) ->
            val entry = host.entries(SMS_PLUGIN_ID).first { it.id.endsWith(suffix) }
            val requestId = "write-request-$index"
            assertTrue("Use the real operation's accepted parameters", CapabilitySchemaValidator.accepts(entry.schema, parameters))
            host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
            assertEquals(DeviceOperation.WRITE, host.authorizationScope(entry, parameters).operation)
            assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestAuthorization(requestId, entry, parameters))
            val denied = runCatching { host.invoke(entry.identity, binding.accountId, entry.key, parameters, requestId) }.exceptionOrNull()
            assertTrue(denied is CapabilityDenied)
            assertEquals("LOCAL_CONFIRMATION_REQUIRED", (denied as CapabilityDenied).primitive)
            assertEquals(0, calls())

            val approval = host.approveRequest(requestId, entry, parameters, durationMinutes = 0, remainingRequestMillis = 60_000L)
            val approved = host.requestAuthorization(requestId, entry, parameters)
            assertTrue(approved is CapabilityAuthorization.Allowed)
            assertEquals(approval.id, (approved as CapabilityAuthorization.Allowed).approval?.id)
            assertEquals(CapabilityAuthorization.AwaitingConfirmation,
                host.requestAuthorization("another-write-$index", entry, parameters))
            host.finishRequestAuthorization(requestId)
            assertEquals(CapabilityAuthorization.AwaitingConfirmation, host.requestAuthorization(requestId, entry, parameters))
        }
        assertEquals(0, calls())
    }

    @Test
    fun automaticModeStillChecksCurrentSystemPermissionAndLocalRevocation() = withCapabilityHost { host, _, calls, permission ->
        val entry = host.entries(SMS_PLUGIN_ID).first { it.id.endsWith(".query") }
        val parameters = Json.parse("""{"limit":1}""")
        host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        permission("READ_SMS_REQUIRED")
        assertTrue(host.isCapabilityEnabled(entry.identity.pluginId))
        assertTrue(host.authorizedBindings().none { it["capabilityId"] == entry.id })
        val unavailableQuery = host.capabilitySnapshot().inventory.single { it["capabilityId"] == entry.id }
        assertEquals("ANDROID_PERMISSION_REQUIRED", unavailableQuery["status"])
        assertEquals("READ_SMS_REQUIRED", unavailableQuery["reason"])
        permission(null)
        assertTrue(host.authorizedBindings().any { it["capabilityId"] == entry.id })
        assertTrue(host.invoke(entry.identity, binding.accountId, entry.key, parameters, "automatic-call")
            .decodeToString().contains("local-authority"))
        assertEquals(1, calls())

        permission("READ_SMS_REQUIRED")
        assertTrue(runCatching { host.invoke(entry.identity, binding.accountId, entry.key, parameters, "permission-revoked") }
            .exceptionOrNull() is CapabilityDenied)
        assertEquals(1, calls())
        permission(null)
        host.setCapabilityPolicy(entry.identity.pluginId, entry.key,
            CapabilityPolicy(enabled = false, mode = CapabilityAuthorizationMode.AUTOMATIC))
        assertTrue(host.authorizedBindings().none { it["capabilityId"] == entry.id })
        val revoked = runCatching { host.invoke(entry.identity, binding.accountId, entry.key, parameters, "automatic-revoked") }.exceptionOrNull()
        assertTrue(revoked is CapabilityDenied)
        assertEquals("LOCAL_CAPABILITY_DISABLED", (revoked as CapabilityDenied).primitive)
        assertEquals(1, calls())
    }

    @Test
    fun schedulerRequiresSeparateLocalBackgroundConsentEvenInAutomaticMode() = withCapabilityHost { host, _, calls, _ ->
        val id = "org.openandroidintelligence.sms"
        val entry = host.entries(id).first { it.id.endsWith(".query") }
        assertTrue(host.supportsBackground(id))
        assertTrue(host.isCapabilityEnabled(id))
        assertFalse(host.isBackgroundEnabled(id))
        host.setCapabilityPolicy(id, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        val record = com.openandroidintelligence.gateway.schema.JsonFields.obj(Json.of(mapOf(
            "pluginId" to entry.identity.pluginId, "author" to entry.identity.authorKeyFingerprint,
            "version" to entry.identity.version, "accountId" to binding.accountId,
            "capabilityId" to entry.id, "capabilityVersion" to entry.version,
            "schemaSha256" to Json.sha256(entry.schema), "query" to mapOf("limit" to 1), "expiresAt" to 2_000L)))!!
        fun run(jobId: String): List<String> {
            val states = mutableListOf<String>()
            PluginJobScheduler.executeStoredInvocation(host, record, jobId, nowMillis = 1_000L) { state, _ -> states += state }
            return states
        }
        assertEquals(listOf("executing", "failed"), run("job-no-background"))
        assertEquals(0, calls())
        host.setBackgroundEnabled(id, true)
        assertTrue(host.isBackgroundEnabled(id))
        assertTrue(host.isCapabilityEnabled(id))
        assertEquals(listOf("executing", "succeeded"), run("job-local-background"))
        assertEquals(1, calls())
        host.setBackgroundEnabled(id, false)
        assertFalse(host.isBackgroundEnabled(id))
        assertTrue(host.isCapabilityEnabled(id))
        assertEquals(listOf("executing", "failed"), run("job-background-revoked"))
        assertEquals(1, calls())
    }

    @Test
    fun closingOnePluginsBackgroundConsentKeepsAnotherAllowedAuthorCallableAndClearsTheLastAggregateGrant() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.sms.read")
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.read"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray): ByteArray =
                """{"records":["${context.pluginId}"]}""".toByteArray()
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            val audit = AndroidAuditStore()
            val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit).apply { bind(binding) }
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), binding.installationId)
            val firstId = "org.example.background-first"
            val secondId = "org.example.background-second"
            host.installStore.install(TestAlpPackages.compiledSmsPackage(queryCapabilityId = "$firstId.query", pluginId = firstId))
            host.installStore.install(TestAlpPackages.compiledSmsPackage(queryCapabilityId = "$secondId.query", pluginId = secondId))
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities), host.runtimes,
                audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant, mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
            host.enable(firstId, true)
            host.enable(secondId, true)
            host.setBackgroundEnabled(firstId, true)
            host.setBackgroundEnabled(secondId, true)
            val first = host.entries(firstId).first { it.id == "$firstId.query" }
            val second = host.entries(secondId).first { it.id == "$secondId.query" }
            listOf(first, second).forEach { entry ->
                host.setCapabilityPolicy(entry.identity.pluginId, entry.key, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
            }
            fun invoke(entry: VerifiedCapability): String = host.invoke(entry.identity, binding.accountId, entry.key,
                Json.parse("""{"limit":1}"""), "background-${entry.identity.pluginId}", background = true).decodeToString()
            assertTrue(invoke(first).contains(firstId))
            assertTrue(invoke(second).contains(secondId))

            host.setBackgroundEnabled(firstId, false)
            grants.bind(binding)
            assertFalse(host.isBackgroundEnabled(firstId))
            assertTrue(host.isBackgroundEnabled(secondId))
            assertTrue(runCatching { invoke(first) }.exceptionOrNull() is CapabilityDenied)
            assertTrue(invoke(second).contains(secondId))
            assertFalse("kernel.background.run" in grants.state.value!!.explicitDisabled)
            assertTrue(identityGrantKey(first.identity, "kernel.background.run") in grants.state.value!!.explicitDisabled)

            host.setBackgroundEnabled(secondId, false)
            assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)
            assertFalse("kernel.background.run" in grants.state.value!!.granted)
            assertFalse("kernel.background.run" in grants.state.value!!.explicitDisabled)
            assertTrue(host.isCapabilityEnabled(firstId))
            assertTrue(host.isCapabilityEnabled(secondId))
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun anExplicitAuthorBackgroundDenyOverridesItsOldPositiveGrantBeforeAJobIsCreated() {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val host = app.pluginHost
        val previousProvider = KernelPrimitiveRegistry.provider("kernel.sms.read")
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.read"
            override suspend fun invoke(context: LocalGrantContext, input: ByteArray) = "{}".toByteArray()
        }
        KernelPrimitiveRegistry.register(provider)
        try {
            host.installStore.install(TestAlpPackages.compiledSmsPackage())
            host.reload()
            val id = "org.openandroidintelligence.sms"
            val entry = host.entries(id).first { it.id.endsWith(".query") }
            val background = "kernel.background.run"
            val scoped = identityGrantKey(entry.identity, background)
            SharedPreferencesPairingGrantStore.from(app).save(binding, PairingGrantState(binding.pairingId,
                granted = setOf(background, scoped), revision = 4L, explicitDisabled = setOf(scoped)))
            app.pairingGrants.bind(binding)
            host.enable(id, true)
            assertTrue(scoped in app.pairingGrants.state.value!!.granted)
            assertFalse(host.isBackgroundEnabled(id))
            val scheduler = app.getSystemService(JobScheduler::class.java)
            val before = scheduler.allPendingJobs.map { it.id }.toSet()
            val call = KernelCallContext(entry.identity, binding.accountId, binding.pairingId,
                SessionConstraints(entry.primitives + entry.key, false, "background-denied-schedule"),
                app.kernel.registrationFor(id)!!.budget, app.pairingGrants.state.value!!.revision)
            val args = Json.of(mapOf("runAt" to java.time.Instant.now().plusSeconds(120).toString(),
                "capabilityId" to entry.id, "capabilityVersion" to entry.version, "query" to mapOf("limit" to 1))) as JsonValue.JObject

            val failure = runCatching { PluginJobScheduler.schedule(app, call, args) }.exceptionOrNull()

            assertEquals("BACKGROUND_GRANT_REQUIRED", failure?.message)
            assertEquals(before, scheduler.allPendingJobs.map { it.id }.toSet())
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
        }
    }
}
