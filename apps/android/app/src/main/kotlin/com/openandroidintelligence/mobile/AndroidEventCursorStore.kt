package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.gateway.events.EventCursorStore
import java.security.MessageDigest

/** Opaque cursors are scoped to this Gateway/profile, then to the server account. */
class AndroidEventCursorStore(context: Context, profileId: String) : EventCursorStore {
    private val prefs = context.getSharedPreferences("gateway-event-cursors", Context.MODE_PRIVATE)
    private val prefix = digest(profileId) + "."
    override fun load(accountId: String): String? = prefs.getString(prefix + digest(accountId), null)
    override fun save(accountId: String, cursor: String) {
        check(prefs.edit().putString(prefix + digest(accountId), cursor).commit()) { "CURSOR_PERSISTENCE_FAILED" }
    }
    override fun clear(accountId: String) {
        check(prefs.edit().remove(prefix + digest(accountId)).commit()) { "CURSOR_PERSISTENCE_FAILED" }
    }
    /** Also supports cleanup of a profile whose remote binding was never saved. */
    fun clearProfile() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(edit::remove)
        check(edit.commit()) { "CURSOR_PERSISTENCE_FAILED" }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
