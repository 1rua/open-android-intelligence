package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
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
                audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant, host::mediate))
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
            assertTrue(host.invoke(entry.identity, binding.accountId, entry.key,
                Json.parse("""{"limit":1}"""), "explicitly-reopened").decodeToString().contains("updated-provider"))
            assertEquals(1, calls)
            assertFalse(host.isBackgroundEnabled(id))
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previousProvider != null) KernelPrimitiveRegistry.register(previousProvider)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun withSmsHost(legacy: Boolean = false, action: (ProductionPluginHost, PairingGrantStateHolder, () -> Int, (String?) -> Unit) -> Unit) {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previous = KernelPrimitiveRegistry.provider("kernel.sms.read")
        var calls = 0
        var unavailable: String? = null
        val provider = object : KernelPrimitiveProvider {
            override val primitiveId = "kernel.sms.read"
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
            if (legacy) store.save(binding, PairingGrantState.legacy(binding.pairingId))
            val grants = PairingGrantStateHolder(store, audit).apply { bind(binding) }
            val trust = DeveloperTrustMode()
            val host = ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), binding.installationId)
            host.installStore.install(TestAlpPackages.compiledSmsPackage())
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant, host::mediate))
            host.enable("org.openandroidintelligence.sms", true)
            action(host, grants, { calls }, { unavailable = it })
        } finally {
            KernelPrimitiveRegistry.unregister(provider)
            if (previous != null) KernelPrimitiveRegistry.register(previous)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        }
    }

    @Test
    fun enabledInstalledPluginIsCallableWithoutAnAdditionalGrantAndLocalOffStillWins() = withSmsHost { host, grants, calls, _ ->
        val id = "org.openandroidintelligence.sms"
        val entry = host.entries(id).first { it.id.endsWith(".query") }
        assertTrue(host.isCapabilityEnabled(id))
        assertFalse(host.capabilityMigrationRequired())
        assertTrue(host.authorizedBindings().any { it["capabilityId"] == entry.id })
        assertTrue(host.invoke(entry.identity, binding.accountId, entry.key, Json.parse("""{"limit":1}"""), "default-call")
            .decodeToString().contains("local-authority"))
        assertEquals(1, calls())
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)

        host.setCapabilityEnabled(id, false)
        assertFalse(host.isCapabilityEnabled(id))
        assertEquals("LOCAL_CAPABILITY_DISABLED", host.capabilitySnapshot().inventory.first { it["capabilityId"] == entry.id }["reason"])
        assertTrue(host.authorizedBindings().isEmpty())
        assertTrue(runCatching { host.invoke(entry.identity, binding.accountId, entry.key, Json.parse("""{"limit":1}"""), "denied-call") }
            .exceptionOrNull() is CapabilityDenied)
        assertEquals(1, calls())
        host.reload()
        assertTrue(host.isEnabled(id))
        assertFalse(host.isCapabilityEnabled(id))
        host.setCapabilityEnabled(id, true)
        assertTrue(host.isCapabilityEnabled(id))
        assertTrue(host.authorizedBindings().any { it["capabilityId"] == entry.id })
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)
    }

    @Test
    fun oneLocalMigrationReviewEnablesDefaultsButNeverGrantsBackground() = withSmsHost(legacy = true) { host, grants, _, _ ->
        val id = "org.openandroidintelligence.sms"
        assertTrue(host.capabilityMigrationRequired())
        assertFalse(host.isCapabilityEnabled(id))
        assertTrue(host.authorizedBindings().isEmpty())
        assertTrue(host.capabilitySnapshot().inventory.all { it["reason"] == "LOCAL_AUTHORIZATION_MIGRATION_REQUIRED" })
        host.completeCapabilityMigration()
        assertFalse(host.capabilityMigrationRequired())
        assertTrue(host.isCapabilityEnabled(id))
        assertTrue(host.authorizedBindings().isNotEmpty())
        assertFalse(grants.currentKernelGrant(binding.pairingId)!!.backgroundSync)
    }

    @Test
    fun defaultCapabilityDoesNotOverrideCurrentSystemPermissionAndRevocation() = withSmsHost { host, _, calls, permission ->
        val entry = host.entries("org.openandroidintelligence.sms").first { it.id.endsWith(".query") }
        permission("READ_SMS_REQUIRED")
        assertTrue(host.isCapabilityEnabled(entry.identity.pluginId))
        assertTrue(host.authorizedBindings().isEmpty())
        assertTrue(host.capabilitySnapshot().inventory.all { it["status"] == "ANDROID_PERMISSION_REQUIRED" })
        permission(null)
        assertTrue(host.authorizedBindings().isNotEmpty())
        permission("READ_SMS_REQUIRED")
        assertNotNull(runCatching { host.invoke(entry.identity, binding.accountId, entry.key, Json.parse("""{"limit":1}"""), "permission-revoked") }.exceptionOrNull())
        assertEquals(0, calls())
    }

    @Test
    fun schedulerRequiresSeparateLocalBackgroundConsentWhileForegroundQueryDefaultsRemainEnabled() = withSmsHost { host, _, calls, _ ->
        val id = "org.openandroidintelligence.sms"
        val entry = host.entries(id).first { it.id.endsWith(".query") }
        assertTrue(host.supportsBackground(id))
        assertTrue(host.isCapabilityEnabled(id))
        assertFalse(host.isBackgroundEnabled(id))
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
                audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant, host::mediate))
            host.enable(firstId, true)
            host.enable(secondId, true)
            host.setBackgroundEnabled(firstId, true)
            host.setBackgroundEnabled(secondId, true)
            val first = host.entries(firstId).first { it.id == "$firstId.query" }
            val second = host.entries(secondId).first { it.id == "$secondId.query" }
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
