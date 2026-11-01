package com.openandroidintelligence.mobile

import android.content.Context
import android.content.SharedPreferences
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantState
import com.openandroidintelligence.kernel.PairingGrantStore
import java.util.Base64

/** The name remains for binary/source migration; no policy is stored by Core, account, or pairing. */
class SharedPreferencesPairingGrantStore(
    private val preferences: SharedPreferences,
) : PairingGrantStore {
    init { synchronized(SharedPreferencesPairingGrantStore::class.java) { migrateInstallationPolicy() } }

    override fun load(binding: PairingGrantBinding): PairingGrantState = synchronized(SharedPreferencesPairingGrantStore::class.java) {
        check(preferences.getInt(MODEL, 0) == PairingGrantState.CURRENT_AUTHORIZATION_MODEL) {
            "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED"
        }
        check(listOf("revision", "disabled", "screen_selection").all { preferences.contains("$GLOBAL.$it") }) {
            "LOCAL_AUTHORIZATION_STATE_INCOMPLETE"
        }
        val policies = preferences.all.filterKeys { it.startsWith("$POLICY.") }.map { (key, value) ->
            val decoded = String(Base64.getUrlDecoder().decode(key.removePrefix("$POLICY.")), Charsets.UTF_8)
            val json = org.json.JSONObject(value as? String ?: error("LOCAL_POLICY_CORRUPTED"))
            val sources = json.getJSONArray("allowedSources")
            val allowedSources = (0 until sources.length()).map { sources.getString(it) }.toSet()
            // Missing fields are the old format; partial/new unknown fields must fail closed.
            check(json.has("smsScopeMode") == json.has("smsKeywords")) { "LOCAL_POLICY_CORRUPTED" }
            val scopeMode = if (json.has("smsScopeMode")) SmsScopeMode.valueOf(json.getString("smsScopeMode"))
                else if (allowedSources.isEmpty()) SmsScopeMode.ALL else SmsScopeMode.PHONE_NUMBERS
            val keywords = if (json.has("smsKeywords")) json.getJSONArray("smsKeywords").let { values ->
                (0 until values.length()).map { values.getString(it) }.toSet()
            } else emptySet()
            decoded to CapabilityPolicy(json.getBoolean("enabled"), CapabilityAuthorizationMode.valueOf(json.getString("mode")),
                allowedSources, json.getBoolean("includeContent"), scopeMode, keywords)
        }.toMap()
        PairingGrantState(
            pairingId = binding.pairingId,
            revision = preferences.getLong("$GLOBAL.revision", 0),
            screenSelectionEnabled = preferences.getBoolean("$GLOBAL.screen_selection", false),
            explicitDisabled = preferences.getStringSet("$GLOBAL.disabled", emptySet()).orEmpty().toSet(),
            capabilityPolicies = policies,
            granted = preferences.getStringSet("$GLOBAL.local_primitives", emptySet()).orEmpty().toSet(),
            localError = preferences.getString("$GLOBAL.error", null),
        ).withStablePluginCapabilityDenials()
    }

    override fun save(binding: PairingGrantBinding, state: PairingGrantState) = synchronized(SharedPreferencesPairingGrantStore::class.java) {
        require(state.pairingId == binding.pairingId) { "pairing transport projection mismatch" }
        check(!state.migrationRequired) { "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED" }
        val saved = state.withStablePluginCapabilityDenials()
        val edit = preferences.edit()
            .putLong("$GLOBAL.revision", saved.revision)
            .putStringSet("$GLOBAL.disabled", saved.explicitDisabled.toSet())
            .putStringSet("$GLOBAL.local_primitives", saved.granted.toSet())
            .putBoolean("$GLOBAL.screen_selection", saved.screenSelectionEnabled)
            .apply {
                if (saved.localError == null) remove("$GLOBAL.error") else putString("$GLOBAL.error", saved.localError)
            }
        preferences.all.keys.filter { it.startsWith("$POLICY.") }.forEach(edit::remove)
        saved.capabilityPolicies.forEach { (key, policy) ->
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
            val json = org.json.JSONObject().put("enabled", policy.enabled).put("mode", policy.mode.name)
                .put("allowedSources", org.json.JSONArray(policy.allowedSources.sorted())).put("includeContent", policy.includeContent)
                .put("smsScopeMode", policy.smsScopeMode.name).put("smsKeywords", org.json.JSONArray(policy.smsKeywords.sorted()))
            edit.putString("$POLICY.$encoded", json.toString())
        }
        check(edit.commit()) { "INSTALLATION_CAPABILITY_PERSISTENCE_FAILED" }
    }

    private fun migrateInstallationPolicy() {
        val model = preferences.getInt(MODEL, 0)
        check(model in 0..PairingGrantState.CURRENT_AUTHORIZATION_MODEL) { "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED" }
        if (model == 3 || model == PairingGrantState.CURRENT_AUTHORIZATION_MODEL) {
            // v3 already owns installation-wide grants. Preserve every policy and revision;
            // v4 is a downgrade barrier because old binaries cannot understand keyword scopes.
            if (model == 3) {
                check(listOf("revision", "disabled", "screen_selection").all { preferences.contains("$GLOBAL.$it") }) {
                    "LOCAL_AUTHORIZATION_STATE_INCOMPLETE"
                }
                check(preferences.edit().putInt(MODEL, PairingGrantState.CURRENT_AUTHORIZATION_MODEL).commit()) {
                    "INSTALLATION_CAPABILITY_MIGRATION_FAILED"
                }
            }
            val obsolete = preferences.all.keys.filter { it.startsWith(LEGACY_PREFIX) ||
                it == "android_capability_legacy_scopes" || it == "android_capability_unknown_legacy_scopes" }
            if (obsolete.isNotEmpty()) {
                val edit = preferences.edit(); obsolete.forEach(edit::remove)
                check(edit.commit()) { "INSTALLATION_CAPABILITY_MIGRATION_FAILED" }
            }
            return
        }
        val old = preferences.all
        val scoped = old.keys.filter { it.startsWith(LEGACY_PREFIX) }
        val denied = scoped.filter { it.endsWith(".disabled") }.flatMap { key ->
            @Suppress("UNCHECKED_CAST")
            ((old[key] as? Set<*>)?.filterIsInstance<String>()).orEmpty()
        }.toSet()
        val revision = scoped.filter { it.endsWith(".revision") }.mapNotNull { old[it] as? Long }.maxOrNull() ?: 0L
        val selection = scoped.filter { it.endsWith(".screen_selection") }.mapNotNull { old[it] as? Boolean }
        // One atomic SharedPreferences transaction writes the complete global state and removes only
        // obsolete capability keys. A failed commit is retried on next process start, never reviewed in UI.
        val edit = preferences.edit()
            .putLong("$GLOBAL.revision", Math.addExact(revision, 1L))
            .putStringSet("$GLOBAL.disabled", denied)
            .putStringSet("$GLOBAL.local_primitives", emptySet())
            .putBoolean("$GLOBAL.screen_selection", selection.isNotEmpty() && selection.all { it })
            .putInt(MODEL, PairingGrantState.CURRENT_AUTHORIZATION_MODEL)
            .remove("android_capability_legacy_scopes")
            .remove("android_capability_unknown_legacy_scopes")
        scoped.forEach(edit::remove)
        check(edit.commit()) { "INSTALLATION_CAPABILITY_MIGRATION_FAILED" }
    }

    companion object {
        private const val PREFERENCES_NAME = "open_android_intelligence_pairing_grants"
        private const val MODEL = "android_capability_authorization_model"
        private const val GLOBAL = "installation_capability_policy_v3"
        private const val POLICY = "$GLOBAL.capability"
        private const val LEGACY_PREFIX = "pairing_grant_v1."

        fun from(context: Context): SharedPreferencesPairingGrantStore = SharedPreferencesPairingGrantStore(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
    }
}
