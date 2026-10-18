package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.gateway.auth.SessionRecoveryPausedException
import java.security.MessageDigest

/** Non-secret write-ahead identity of a refresh whose response may be lost. */
internal class GatewayRefreshJournal(context: Context) {
    private val preferences = context.getSharedPreferences("gateway_refresh_operations", Context.MODE_PRIVATE)

    data class Operation(val id: String, val replayable: Boolean)

    @Synchronized
    fun prepare(profileId: String, credential: ByteArray, replaySupported: Boolean): Operation {
        val prefix = key(profileId)
        val digest = digest(credential)
        if (preferences.getString("$prefix.digest", null) == digest) {
            // The previous process may have died after the server committed.
            // A legacy Gateway offers no safe way to submit that secret again.
            if (!preferences.getBoolean("$prefix.replay", false) || !replaySupported) {
                throw RefreshOutcomeUnknownException()
            }
            return Operation(checkNotNull(preferences.getString("$prefix.id", null)), true)
        }
        val id = "refresh_" + java.util.UUID.randomUUID().toString().replace("-", "")
        check(preferences.edit().putString("$prefix.digest", digest).putString("$prefix.id", id)
            .putBoolean("$prefix.replay", replaySupported).commit()) { "REFRESH_JOURNAL_UNAVAILABLE" }
        return Operation(id, replaySupported)
    }

    @Synchronized
    fun clear(profileId: String) {
        val prefix = key(profileId)
        check(preferences.edit().remove("$prefix.digest").remove("$prefix.id").remove("$prefix.replay").commit()) {
            "REFRESH_JOURNAL_UNAVAILABLE"
        }
    }

    private fun key(profileId: String) = digest(profileId.toByteArray(Charsets.UTF_8))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

internal class RefreshOutcomeUnknownException : SessionRecoveryPausedException("REFRESH_OUTCOME_UNKNOWN")
