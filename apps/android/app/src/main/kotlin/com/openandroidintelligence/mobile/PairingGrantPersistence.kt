package com.openandroidintelligence.mobile

import android.accounts.AccountManager
import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantState
import com.openandroidintelligence.kernel.PairingGrantStore

/** SharedPreferences persistence for non-secret, Android-owned capability decisions. */
class SharedPreferencesPairingGrantStore(
    private val preferences: SharedPreferences,
) : PairingGrantStore {

    override fun load(binding: PairingGrantBinding): PairingGrantState {
        val prefix = prefix(binding)
        // A v1 scope can have no keys after an explicit revoke. Its installation
        // migration snapshot must therefore be consulted before applying defaults.
        val legacy = !preferences.contains("$prefix.authorization_model") && (
            preferences.all.keys.any { it.startsWith("$prefix.") } ||
                binding.storageKey in preferences.getStringSet(LEGACY_SCOPES, emptySet()).orEmpty() ||
                preferences.getBoolean(UNKNOWN_LEGACY_SCOPES, false))
        val savedModel = preferences.contains("$prefix.authorization_model")
        if (savedModel) {
            check(listOf("revision", "granted", "screen_selection", "disabled", "legacy_projection_digest")
                .all { preferences.contains("$prefix.$it") }) { "LOCAL_AUTHORIZATION_STATE_INCOMPLETE" }
        }
        val granted = preferences.getStringSet("$prefix.granted", emptySet()).orEmpty().toSet()
        val revision = preferences.getLong("$prefix.revision", 0L)
        val screenSelection = preferences.getBoolean("$prefix.screen_selection", false)
        val projectionChanged = savedModel && preferences.getString("$prefix.legacy_projection_digest", null) !=
            legacyProjectionDigest(revision, granted, screenSelection)
        // An old binary can still write the v1 projection. Recognise that
        // write instead of treating its empty grant as v2 default permission.
        val model = if (projectionChanged) 1 else preferences.getInt("$prefix.authorization_model",
            if (legacy) 1 else PairingGrantState.CURRENT_AUTHORIZATION_MODEL)
        return PairingGrantState(binding.pairingId, granted, revision, screenSelection, model,
            preferences.getStringSet("$prefix.disabled", emptySet()).orEmpty().toSet()).withStablePluginCapabilityDenials()
    }

    override fun save(binding: PairingGrantBinding, state: PairingGrantState) {
        require(state.pairingId == binding.pairingId) { "pairing grant binding mismatch" }
        val prefix = prefix(binding)
        val saved = state.withStablePluginCapabilityDenials()
        check(
            preferences.edit()
                .putLong("$prefix.revision", saved.revision)
                .putStringSet("$prefix.granted", saved.granted.toSet())
                .putBoolean("$prefix.screen_selection", saved.screenSelectionEnabled)
                .putInt("$prefix.authorization_model", saved.authorizationModelVersion)
                .putStringSet("$prefix.disabled", saved.explicitDisabled.toSet())
                .putString("$prefix.legacy_projection_digest", legacyProjectionDigest(saved.revision, saved.granted, saved.screenSelectionEnabled))
                .commit(),
        ) { "PAIRING_GRANT_PERSISTENCE_FAILED" }
    }

    override fun clear(binding: PairingGrantBinding) {
        val current = runCatching { load(binding) }.getOrElse { PairingGrantState.legacy(binding.pairingId) }
        // Keep an explicit revocation tombstone. Removing the keys would make a
        // future bind indistinguishable from a genuinely new local scope.
        save(binding, PairingGrantState.legacy(binding.pairingId, Math.addExact(current.revision, 1L))
            .copy(explicitDisabled = current.explicitDisabled))
    }

    private fun prefix(binding: PairingGrantBinding): String = "pairing_grant_v1.${binding.storageKey}"

    private fun legacyProjectionDigest(revision: Long, granted: Set<String>, screenSelection: Boolean): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("$revision\u0000$screenSelection\u0000" + granted.sorted().joinToString("\u0000")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val PREFERENCES_NAME = "open_android_intelligence_pairing_grants"
        private const val INSTALLATION_MODEL = "android_capability_authorization_model"
        private const val LEGACY_SCOPES = "android_capability_legacy_scopes"
        private const val UNKNOWN_LEGACY_SCOPES = "android_capability_unknown_legacy_scopes"

        fun from(context: Context): SharedPreferencesPairingGrantStore {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            synchronized(SharedPreferencesPairingGrantStore::class.java) {
                if (!preferences.contains(INSTALLATION_MODEL)) snapshotLegacyScopes(context, preferences)
                check(preferences.getInt(INSTALLATION_MODEL, 0) == PairingGrantState.CURRENT_AUTHORIZATION_MODEL) {
                    "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED"
                }
            }
            return SharedPreferencesPairingGrantStore(preferences)
        }

        private fun snapshotLegacyScopes(context: Context, preferences: SharedPreferences) {
            val legacyScopes = preferences.all.keys.filter { it.startsWith("pairing_grant_v1.") }
                .map { it.removePrefix("pairing_grant_v1.").substringBefore('.') }.toMutableSet()
            val installation = context.getSharedPreferences("open_android_intelligence_runtime", Context.MODE_PRIVATE)
                .getString("installation_id", null)
            var unknown = false
            runCatching {
                val accounts = AccountManager.get(context).getAccountsByType(AndroidAccountProfileStore.TYPE)
                val profiles = AndroidAccountProfileStore(context)
                val readable = profiles.list()
                if (readable.size != accounts.size) unknown = true
                for (profile in readable) {
                    val accountId = profiles.accountId(profile.localProfileId)
                    if (installation == null || accountId == null) unknown = true
                    else legacyScopes += PairingGrantBinding(profile.gatewayBaseUrl, accountId, installation).storageKey
                }
            }.onFailure { unknown = true }
            // The installation marker is written only after the complete old
            // scope snapshot. Completing one scope never releases another one.
            check(preferences.edit()
                .putStringSet(LEGACY_SCOPES, legacyScopes)
                .putBoolean(UNKNOWN_LEGACY_SCOPES, unknown)
                .putInt(INSTALLATION_MODEL, PairingGrantState.CURRENT_AUTHORIZATION_MODEL)
                .commit()) { "LOCAL_AUTHORIZATION_MIGRATION_SNAPSHOT_FAILED" }
        }
    }
}
