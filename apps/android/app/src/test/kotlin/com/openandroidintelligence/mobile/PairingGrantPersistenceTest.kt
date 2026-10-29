package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.account.AccountProfile
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

    @Test
    fun freshInstallationAndLaterNewScopeUseDefaultsWithoutASecondGrant() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        assertFalse(store.load(binding).migrationRequired)
        assertTrue(store.load(binding).permits("kernel.sms.read"))
        context().getSharedPreferences("open_android_intelligence_runtime", Application.MODE_PRIVATE)
            .edit().putString("installation_id", "install-a").commit()
        val newScope = binding.copy(accountId = "account-new")
        assertFalse(SharedPreferencesPairingGrantStore.from(context()).load(newScope).migrationRequired)
    }

    @Test
    fun oldEmptyOrPartialScopesStayLegacyAndMigrationKeepsExplicitOff() {
        val prefix = "pairing_grant_v1.${binding.storageKey}"
        preferences().edit().putStringSet("$prefix.granted", emptySet()).commit()
        val store = SharedPreferencesPairingGrantStore.from(context())
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        assertTrue(holder.state.value!!.migrationRequired)
        assertFalse(holder.state.value!!.permits("kernel.sms.read"))
        holder.updatePrimitive("plugin:org.example.reader:author:kernel.sms.read", false)
        holder.completeCapabilityMigration()
        val restored = SharedPreferencesPairingGrantStore.from(context()).load(binding)
        assertFalse(restored.migrationRequired)
        assertTrue(restored.permits("kernel.sms.read"))
        assertFalse(restored.permits("plugin:org.example.reader:author:kernel.sms.read"))
        assertFalse(restored.permits("kernel.background.run"))
    }

    @Test
    fun existingV2DeclarationDenialsRemainClosedAcrossReplacementAndReopenOnlyTheirAuthor() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        val author = PluginIdentity("org.example.reader", "a".repeat(64), "1.0.0")
        val updated = author.copy(version = "2.0.0")
        val other = PluginIdentity("org.example.other", "b".repeat(64), "1.0.0")
        store.save(binding, PairingGrantState(binding.pairingId, revision = 7L))
        // Simulate the shipped v2 representation before stable plugin decisions were introduced.
        val prefix = "pairing_grant_v1.${binding.storageKey}"
        preferences().edit().putStringSet("$prefix.disabled", setOf(
            identityGrantKey(author, "org.example.reader.previous@1.0.0"),
            identityGrantKey(author, "kernel.background.run"),
            identityGrantKey(other, "kernel.notifications.read"),
        )).commit()

        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        assertFalse(holder.state.value!!.migrationRequired)
        assertFalse(holder.state.value!!.permits(identityGrantKey(updated, "kernel.sms.read")))
        assertFalse(holder.state.value!!.permits(identityGrantKey(updated, "org.example.reader.query@2.0.0")))
        holder.updatePluginCapabilities(updated, setOf("kernel.sms.read", "org.example.reader.query@2.0.0"), false)
        val restarted = SharedPreferencesPairingGrantStore.from(context()).load(binding)
        assertFalse(restarted.migrationRequired)
        assertFalse(restarted.permits(identityGrantKey(updated, "org.example.reader.query@2.0.0")))

        holder.bind(binding)
        holder.updatePluginCapabilities(updated, setOf("kernel.sms.read", "org.example.reader.query@2.0.0"), true)
        val reopened = SharedPreferencesPairingGrantStore.from(context()).load(binding)
        assertTrue(reopened.permits(identityGrantKey(updated, "org.example.reader.query@2.0.0")))
        assertFalse(reopened.permits(identityGrantKey(updated, "kernel.background.run")))
        assertFalse(reopened.permits(identityGrantKey(other, "kernel.notifications.read")))
        assertTrue(reopened.granted.isEmpty())
        assertEquals(9L, reopened.revision)
    }

    @Test
    fun oldMissingKeysAreDetectedFromExistingAccountBindingsAndScopesMigrateSeparately() {
        context().getSharedPreferences("open_android_intelligence_runtime", Application.MODE_PRIVATE)
            .edit().putString("installation_id", "install-a").commit()
        val profiles = AndroidAccountProfileStore(context())
        profiles.save(AccountProfile("profile-a", binding.gatewayId, "alice", TLS_TRUST_ID),
            AndroidAccountProfileStore.Binding(binding.accountId, "device-a", null, 1))
        val other = binding.copy(accountId = "account-b")
        profiles.save(AccountProfile("profile-b", binding.gatewayId, "bob", TLS_TRUST_ID),
            AndroidAccountProfileStore.Binding(other.accountId, "device-b", null, 1))
        val store = SharedPreferencesPairingGrantStore.from(context())
        assertTrue(store.load(binding).migrationRequired)
        assertTrue(store.load(other).migrationRequired)
        PairingGrantStateHolder(store, AndroidAuditStore()).apply {
            bind(binding); completeCapabilityMigration()
        }
        val restarted = SharedPreferencesPairingGrantStore.from(context())
        assertFalse(restarted.load(binding).migrationRequired)
        assertTrue(restarted.load(other).migrationRequired)
        // A genuinely new account after the frozen upgrade snapshot is not another grant workflow.
        assertFalse(restarted.load(binding.copy(accountId = "account-new")).migrationRequired)
    }

    private companion object {
        val TLS_TRUST_ID = "sha256:${"a".repeat(64)}"
    }

    @Test
    fun clearPersistsARevocationTombstoneAndNeverReusesDefaultsOnRebind() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        holder.updatePrimitive("kernel.sms.read", false)
        store.clear(binding)
        val restored = store.load(binding)
        assertTrue(restored.migrationRequired)
        assertEquals(2L, restored.revision)
        assertFalse(restored.permits("kernel.sms.read"))
        assertFalse(restored.permits("kernel.notifications.read"))
    }

    @Test
    fun corruptOrUnknownLocalFormatFailsClosedInsteadOfApplyingFreshDefaults() {
        val prefix = "pairing_grant_v1.${binding.storageKey}"
        preferences().edit().putInt("$prefix.authorization_model", 99).commit()
        val holder = PairingGrantStateHolder(SharedPreferencesPairingGrantStore.from(context()), AndroidAuditStore())
        holder.bind(binding)
        assertTrue(holder.state.value!!.migrationRequired)
        assertFalse(holder.state.value!!.permits("kernel.sms.read"))
    }

    @Test
    fun oldBinaryWritesAndIncompleteCurrentStateCannotRestoreDefaultPermission() {
        val store = SharedPreferencesPairingGrantStore.from(context())
        val holder = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        holder.updateScreenSelection(true)
        val prefix = "pairing_grant_v1.${binding.storageKey}"
        // Simulate the previous binary updating only its three known v1 fields.
        preferences().edit().putLong("$prefix.revision", 2L).putStringSet("$prefix.granted", emptySet())
            .putBoolean("$prefix.screen_selection", false).commit()
        assertTrue(store.load(binding).migrationRequired)
        assertFalse(store.load(binding).permits("kernel.sms.read"))
        holder.bind(binding)
        holder.completeCapabilityMigration()
        preferences().edit().remove("$prefix.disabled").commit()
        holder.bind(binding)
        assertTrue(holder.state.value!!.migrationRequired)
        assertFalse(holder.state.value!!.permits("kernel.sms.read"))
    }
}
