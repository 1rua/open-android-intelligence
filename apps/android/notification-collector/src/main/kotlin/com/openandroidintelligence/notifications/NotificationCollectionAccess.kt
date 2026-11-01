package com.openandroidintelligence.notifications

/**
 * Installation lifecycle gate supplied by Application: a real logged-in account, an enabled
 * notification capability, and no emergency stop. This is NOT the passive-read data policy.
 * Uninstalled or failing predicates deny access before OS snapshots or bodies are read.
 */
object NotificationCollectionAccess {
    @Volatile private var access: () -> Boolean = { false }

    fun install(isLoggedIn: () -> Boolean) { access = isLoggedIn }

    fun isAllowed(): Boolean = allows(access)

    /** Lazy extraction and final handoff share the same installed lifecycle gate. */
    internal fun <T> capture(read: () -> T): T? {
        val current = access
        if (!allows(current)) return null
        val value = read()
        return value.takeIf { access === current && allows(current) }
    }

    private fun allows(current: () -> Boolean): Boolean = try { current() } catch (_: Exception) { false }
}
