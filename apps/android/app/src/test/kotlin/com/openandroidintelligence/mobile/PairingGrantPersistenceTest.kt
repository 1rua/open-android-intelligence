package com.openandroidintelligence.mobile

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PairingGrantPersistenceTest {
    private val binding = PairingGrantBinding("https://gateway.example", "account-a", "install-a")
    private fun context() = ApplicationProvider.getApplicationContext<Application>()
    private fun preferences() = context().getSharedPreferences("open_android_intelligence_pairing_grants", Application.MODE_PRIVATE)

    @Test fun globalPolicySurvivesCoreAccountAndProcessChanges() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        assertEquals(CapabilityAuthorizationMode.MANUAL, holder.capabilityPolicy("sms").mode)
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
        val other = binding.copy(gatewayId = "https://other.example", accountId = "account-b")
        holder.bind(other)
        assertEquals(CapabilityAuthorizationMode.READ_ONLY, holder.capabilityPolicy("sms").mode)
        val restarted = SharedPreferencesPairingGrantStore.from(context()).load(other)
        assertEquals(CapabilityAuthorizationMode.READ_ONLY, restarted.capabilityPolicies["sms"]!!.mode)
        assertFalse(restarted.migrationRequired)
        assertFalse(restarted.permits("kernel.background.run"))
    }

    @Test fun migrationIsAtomicIdempotentAndNeverImportsOldAutomaticOrBackgroundGrants() {
        val old = "pairing_grant_v1.${binding.storageKey}"
        val other = "pairing_grant_v1.${binding.copy(accountId = "b").storageKey}"
        val author = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        preferences().edit().putInt("android_capability_authorization_model", 2)
            .putLong("$old.revision", 7).putStringSet("$old.granted", setOf("kernel.sms.read", "kernel.background.run"))
            .putStringSet("$other.disabled", setOf(identityGrantKey(author, "kernel.sms.read")))
            .putStringSet("android_capability_legacy_scopes", setOf(binding.storageKey))
            .putBoolean("android_capability_unknown_legacy_scopes", true)
            .putString("unrelated_login_reference", "preserve-me").commit()
        val store = SharedPreferencesPairingGrantStore.from(context())
        val first = store.load(binding)
        assertEquals(8L, first.revision)
        assertFalse(first.migrationRequired)
        assertTrue(first.granted.isEmpty())
        assertTrue(first.capabilityPolicies.isEmpty())
        assertFalse(first.permits("kernel.background.run"))
        assertFalse(first.permits(identityGrantKey(author.copy(version = "2.0.0"), "kernel.sms.read")))
        assertFalse(preferences().all.keys.any { it.startsWith("pairing_grant_v1.") })
        assertFalse(preferences().contains("android_capability_legacy_scopes"))
        assertFalse(preferences().contains("android_capability_unknown_legacy_scopes"))
        assertEquals("preserve-me", preferences().getString("unrelated_login_reference", null))
        val second = SharedPreferencesPairingGrantStore.from(context()).load(binding)
        assertEquals(first, second)
    }

    @Test fun migrationCommitFailureLeavesOldStateAndCanBeRetriedWithoutPartialPolicy() {
        val actual = preferences()
        val old = "pairing_grant_v1.${binding.storageKey}"
        actual.edit().putLong("$old.revision", 4).commit()
        val failing = object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return java.lang.reflect.Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { proxy, method, args ->
                    if (method.name == "commit") false
                    else method.invoke(editor, *(args ?: emptyArray())).let { if (it === editor) proxy else it }
                } as SharedPreferences.Editor
            }
        }
        try { SharedPreferencesPairingGrantStore(failing); fail("failed commit must stop migration") }
        catch (expected: IllegalStateException) { assertEquals("INSTALLATION_CAPABILITY_MIGRATION_FAILED", expected.message) }
        assertFalse(actual.contains("installation_capability_policy_v3.revision"))
        assertTrue(actual.contains("$old.revision"))
        assertEquals(5L, SharedPreferencesPairingGrantStore(actual).load(binding).revision)
    }

    @Test fun removingConnectionsAndOfflineChangesPreserveTheInstallationPolicy() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        holder.updateCapabilityPolicy("calls", CapabilityPolicy(enabled = false))
        holder.clearCurrent()
        assertNull(holder.currentBinding())
        assertNotNull(holder.state.value)
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        holder.bind(binding.copy(accountId = "another"))
        assertFalse(holder.capabilityPolicy("calls").enabled)
        assertEquals(CapabilityAuthorizationMode.AUTOMATIC, holder.capabilityPolicy("sms").mode)
    }

    @Test fun unknownGlobalModelOrIncompleteCurrentStateFailsClosed() {
        preferences().edit().putInt("android_capability_authorization_model", 99).commit()
        try { SharedPreferencesPairingGrantStore.from(context()); fail("future models must be rejected") }
        catch (expected: IllegalStateException) { assertEquals("LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED", expected.message) }
        preferences().edit().putInt("android_capability_authorization_model", 3).commit()
        try { SharedPreferencesPairingGrantStore.from(context()).load(binding); fail("incomplete policy must be rejected") }
        catch (expected: IllegalStateException) { assertEquals("LOCAL_AUTHORIZATION_STATE_INCOMPLETE", expected.message) }
    }

    @Test fun staleOldBinaryWritesCannotChangeCurrentPolicyOrDemandAnotherMigration() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(enabled = false))
        val current = store.load(binding)
        val old = "pairing_grant_v1.${binding.storageKey}"
        preferences().edit().putLong("$old.revision", 999).putStringSet("$old.granted", setOf("kernel.sms.read")).commit()
        assertEquals(current, SharedPreferencesPairingGrantStore.from(context()).load(binding))
        assertFalse(preferences().all.keys.any { it.startsWith("pairing_grant_v1.") })
    }
}
