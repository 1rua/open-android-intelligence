package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.*
import org.junit.Test

/** Regression at the production local-state -> kernel seam, without a second grant action. */
class ClientCapabilityDefaultsTest {
    @Test
    fun enabledVerifiedPluginUsesClientDefaultsWithoutASeparatePairingGrant() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        holder.bind(binding)
        val identity = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val capability = "org.example.reader.query@1.0.0"
        val primitive = "kernel.sms.read"
        val declared = setOf(capability, primitive)
        val trust = DeveloperTrustMode()
        val runtime = object : PluginRuntime {
            override fun invoke(identity: PluginIdentity, budget: ResourceBudget, input: ByteArray) = input
        }
        val kernel = PluginKernel(HostEnvelope(declared), PhoneLimits(declared),
            mapOf(PluginKernel.RUNTIME_PROTECTED_WASM to runtime), AndroidAuditStore(), trust,
            NativePluginLoader(trust), CapabilityProviderSelector(mapOf(capability to identity)),
            holder::currentKernelGrant)
        kernel.register(PluginRegistration(identity, PluginKernel.RUNTIME_PROTECTED_WASM, declared,
            ResourceBudget(1000, 1048576, 65536, 1, 1024), identityScopedGrants = true))
        kernel.enable(identity.pluginId)

        val result = kernel.invoke(identity, binding.accountId, binding.pairingId, capability,
            "read".toByteArray(), SessionConstraints(declared, false, "defaults-regression"))

        assertArrayEquals("read".toByteArray(), result.output)
    }

    @Test
    fun explicitAuthorDecisionSurvivesRebindAndDoesNotDenyAnotherAuthor() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val store = InMemoryPairingGrantStore()
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        val first = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val second = PluginIdentity("org.example.other", "b".repeat(64), "1.0.0")
        holder.updatePrimitive(identityGrantKey(first, "kernel.sms.read"), false)
        val restored = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        val grant = restored.currentKernelGrant(binding.pairingId)!!
        assertFalse(grant.permits(identityGrantKey(first, "kernel.sms.read")))
        assertTrue(grant.permits(identityGrantKey(second, "kernel.sms.read")))
        assertTrue(grant.permits("kernel.sms.read"))
        assertEquals(1L, grant.revision)
    }

    @Test
    fun aPluginOffDecisionSurvivesNewDeclarationsAndReopeningOnlyChangesThatAuthorsForegroundDecision() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val store = InMemoryPairingGrantStore()
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        val original = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val updated = original.copy(version = "2.0.0")
        val other = PluginIdentity("org.example.other", "b".repeat(64), "1.0.0")
        val oldCapability = "org.example.reader.previous@1.0.0"
        val newCapability = "org.example.reader.query@2.0.0"
        val background = "kernel.background.run"
        holder.updatePrimitives(setOf(background, identityGrantKey(original, background)), true)
        holder.updatePrimitive(identityGrantKey(other, "kernel.notifications.read"), false)
        holder.updatePrimitive("kernel.network.request", false)

        holder.updatePluginCapabilities(original, setOf(oldCapability), false)
        holder.bind(binding)
        val denied = holder.currentKernelGrant(binding.pairingId)!!
        assertFalse(denied.permits(identityGrantKey(updated, newCapability)))
        assertFalse(denied.permits(identityGrantKey(updated, "kernel.sms.read")))
        assertTrue(denied.permits(identityGrantKey(original, background)))
        assertFalse(denied.permits(identityGrantKey(other, background)))

        val reopened = holder.updatePluginCapabilities(updated, setOf(newCapability, "kernel.sms.read", background), true)
        assertTrue(reopened.permits(identityGrantKey(updated, newCapability)))
        assertTrue(reopened.permits(identityGrantKey(updated, "kernel.sms.read")))
        assertTrue(reopened.permits(identityGrantKey(updated, background)))
        assertFalse(reopened.permits(identityGrantKey(other, "kernel.notifications.read")))
        assertFalse(reopened.permits("kernel.network.request"))
        assertEquals(setOf(background, identityGrantKey(original, background)), reopened.granted)
        assertEquals(reopened, PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }.state.value)
    }

    @Test
    fun aV2PerDeclarationDenyStillClosesThePluginAfterTheOldDeclarationDisappears() {
        val original = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val updated = original.copy(version = "2.0.0")
        val otherAuthor = PluginIdentity(original.pluginId, "b".repeat(64), "2.0.0")
        val state = PairingGrantState("pairing-a", explicitDisabled = setOf(
            identityGrantKey(original, "org.example.reader.previous@1.0.0"),
        ))

        assertFalse(state.migrationRequired)
        assertFalse(state.permits(identityGrantKey(updated, "org.example.reader.query@2.0.0")))
        assertFalse(state.permits(identityGrantKey(updated, "kernel.sms.read")))
        assertTrue(state.permits(identityGrantKey(otherAuthor, "kernel.sms.read")))
        assertFalse(state.permits(identityGrantKey(otherAuthor, "kernel.background.run")))
    }

    @Test
    fun legacyAmbiguityRequiresOneLocalReviewAndExplicitDenialsSurviveIt() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val store = InMemoryPairingGrantStore().apply { save(binding, PairingGrantState.legacy(binding.pairingId)) }
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        assertTrue(holder.state.value!!.migrationRequired)
        assertFalse(holder.currentKernelGrant(binding.pairingId)!!.permits("kernel.sms.read"))
        holder.updatePrimitive("plugin:org.example.reader:author:kernel.sms.read", false)
        val migrated = holder.completeCapabilityMigration()
        assertFalse(migrated.migrationRequired)
        assertTrue(migrated.permits("kernel.notifications.read"))
        assertFalse(migrated.permits("plugin:org.example.reader:author:kernel.sms.read"))
        assertFalse(migrated.permits("kernel.background.run"))
        assertFalse(migrated.screenSelectionEnabled)
        assertEquals(2L, migrated.revision)
        assertEquals(migrated, PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }.state.value)
    }

    @Test
    fun clearingDoesNotTurnAnExplicitRevocationIntoFreshDefaults() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val store = InMemoryPairingGrantStore()
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        holder.updatePrimitive("kernel.sms.read", false)
        holder.clearCurrent()
        holder.bind(binding)
        assertTrue(holder.state.value!!.migrationRequired)
        assertFalse(holder.currentKernelGrant(binding.pairingId)!!.permits("kernel.sms.read"))
        assertFalse(holder.currentKernelGrant(binding.pairingId)!!.permits("kernel.notifications.read"))
        holder.completeCapabilityMigration()
        assertFalse(holder.currentKernelGrant(binding.pairingId)!!.permits("kernel.sms.read"))
        assertTrue(holder.currentKernelGrant(binding.pairingId)!!.permits("kernel.notifications.read"))
    }

    @Test
    fun unreadableStoreNeverFallsBackToFreshPermission() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val store = object : PairingGrantStore {
            override fun load(binding: PairingGrantBinding): PairingGrantState = error("corrupt")
            override fun save(binding: PairingGrantBinding, state: PairingGrantState) = Unit
            override fun clear(binding: PairingGrantBinding) = Unit
        }
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        assertTrue(holder.state.value!!.migrationRequired)
        assertFalse(holder.currentKernelGrant(binding.pairingId)!!.permits("kernel.sms.read"))
    }

    @Test
    fun backgroundNeverInheritsTheDefaultForegroundPermission() {
        val grant = PairingGrantState("pairing-a").asKernelGrant()
        val declared = setOf("kernel.sms.read", "kernel.background.run")
        val inputs = CapabilityInputs(declared, declared, declared, true, grant,
            SessionConstraints(declared, true, "background-check"))
        val effective = EffectiveCapabilities.compute(inputs)
        assertTrue("kernel.sms.read" in effective.primitives)
        assertFalse("kernel.background.run" in effective.primitives)
        assertFalse(effective.backgroundAllowed)
        assertFalse(grant.permits("plugin:org.example.reader:author:kernel.background.run"))
    }

    @Test
    fun revocationDuringAPrimitiveCallWithholdsItsSensitiveOutputFromThePlugin() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore()).apply { bind(binding) }
        val identity = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val capability = "org.example.reader.query@1.0.0"
        val primitive = "kernel.sms.read"
        val declared = setOf(capability, primitive)
        var pluginReceivedSensitiveOutput = false
        var primitiveCalls = 0
        lateinit var kernel: PluginKernel
        val runtime = object : PluginRuntime {
            override fun invoke(identity: PluginIdentity, budget: ResourceBudget, input: ByteArray): ByteArray {
                val result = kernel.call(identity, primitive, input)
                pluginReceivedSensitiveOutput = true
                return result
            }
        }
        val trust = DeveloperTrustMode()
        kernel = PluginKernel(HostEnvelope(declared), PhoneLimits(declared),
            mapOf(PluginKernel.RUNTIME_PROTECTED_WASM to runtime), AndroidAuditStore(), trust,
            NativePluginLoader(trust), CapabilityProviderSelector(mapOf(capability to identity)), holder::currentKernelGrant,
            mediate = { _, _, _ ->
                primitiveCalls++
                holder.updatePrimitive(identityGrantKey(identity, primitive), false)
                "sensitive-output".toByteArray()
            })
        kernel.register(PluginRegistration(identity, PluginKernel.RUNTIME_PROTECTED_WASM, declared,
            ResourceBudget(1000, 1048576, 65536, 1, 1024), identityScopedGrants = true))
        kernel.enable(identity.pluginId)
        assertTrue(runCatching { kernel.invoke(identity, binding.accountId, binding.pairingId, capability,
            byteArrayOf(), SessionConstraints(declared, false, "output-fence")) }.exceptionOrNull() is CapabilityDenied)
        assertEquals(1, primitiveCalls)
        assertFalse(pluginReceivedSensitiveOutput)
    }

    @Test
    fun anotherAuthorsBackgroundConsentCannotBeInheritedThroughTheSharedPrimitive() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore()).apply { bind(binding) }
        val identity = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val other = PluginIdentity("org.example.other", "b".repeat(64), "1.0.0")
        holder.updatePrimitives(setOf("kernel.background.run", identityGrantKey(other, "kernel.background.run")), true)
        val capability = "org.example.reader.query@1.0.0"
        val declared = setOf(capability, "kernel.sms.read", "kernel.background.run")
        val trust = DeveloperTrustMode()
        var calls = 0
        val runtime = object : PluginRuntime {
            override fun invoke(identity: PluginIdentity, budget: ResourceBudget, input: ByteArray): ByteArray {
                calls++
                return input
            }
        }
        val kernel = PluginKernel(HostEnvelope(declared), PhoneLimits(declared),
            mapOf(PluginKernel.RUNTIME_PROTECTED_WASM to runtime), AndroidAuditStore(), trust,
            NativePluginLoader(trust), CapabilityProviderSelector(mapOf(capability to identity)), holder::currentKernelGrant)
        kernel.register(PluginRegistration(identity, PluginKernel.RUNTIME_PROTECTED_WASM, declared,
            ResourceBudget(1000, 1048576, 65536, 1, 1024), identityScopedGrants = true))
        kernel.enable(identity.pluginId)
        assertTrue(runCatching { kernel.invoke(identity, binding.accountId, binding.pairingId, capability,
            byteArrayOf(), SessionConstraints(declared, true, "background-other-author")) }.exceptionOrNull() is CapabilityDenied)
        assertEquals(0, calls)
    }

    @Test
    fun anOldPositiveBackgroundGrantWithAnExplicitDenyDoesNotKeepTheAggregatePermissionAlive() {
        val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
        val first = PluginIdentity("org.example.first", "a".repeat(64), "1.0.0")
        val second = PluginIdentity("org.example.second", "b".repeat(64), "1.0.0")
        val background = "kernel.background.run"
        val denied = identityGrantKey(second, background)
        val store = InMemoryPairingGrantStore().apply {
            save(binding, PairingGrantState(binding.pairingId, granted = setOf(background, denied), explicitDisabled = setOf(denied)))
        }
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }

        holder.updatePluginBackground(first, true)
        assertTrue(holder.currentKernelGrant(binding.pairingId)!!.backgroundSync)
        assertTrue(holder.state.value!!.permits(identityGrantKey(first, background)))
        assertFalse(holder.state.value!!.permits(denied))
        val stopped = holder.updatePluginBackground(first, false)

        assertFalse(stopped.asKernelGrant().backgroundSync)
        assertFalse(background in stopped.granted)
        assertFalse(background in stopped.explicitDisabled)
        assertTrue(denied in stopped.granted)
        assertFalse(stopped.permits(denied))
        assertEquals(2L, stopped.revision)
        assertEquals(stopped, PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }.state.value)
    }
}
