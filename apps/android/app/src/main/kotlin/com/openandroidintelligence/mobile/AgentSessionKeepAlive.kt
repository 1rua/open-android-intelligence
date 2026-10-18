package com.openandroidintelligence.mobile

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Keeps the existing process-owned event stream active only while user-started work is running. */
internal class AgentSessionKeepAlive(context: Context) {
    private val appContext = context.applicationContext
    private val recovery = AgentSessionRecoveryState(appContext)
    private var startRequested = false

    @Synchronized
    fun update(hasWork: Boolean, appVisible: Boolean) {
        if (!hasWork) {
            // A freshly recreated process has not loaded its task projection yet.
            // Explicit logout and teardown still call stop() without this grace.
            if (!recovery.hasRecoveryLease) stop()
        } else {
            // A delayed main-thread timeout cannot grant an already expired
            // background recovery a fresh lifetime. Visible work may request a
            // new start under the normal foreground launch rules.
            if (!recovery.confirmRunningWork()) stop()
            if (appVisible && !startRequested) requestStart()
        }
        // An existing foreground service can continue after the app becomes
        // invisible. A background update must never start or restart one.
    }

    /** Reassert the request after a system stop/timeout, only on a visible lifecycle transition. */
    @Synchronized
    fun onForegrounded(hasWork: Boolean) {
        if (hasWork) {
            if (!recovery.confirmRunningWork()) stop()
            requestStart()
        } else if (!recovery.hasRecoveryLease) stop()
    }

    @Synchronized
    fun stop() {
        val shouldStop = startRequested || recovery.hasPendingWork
        recovery.clear()
        startRequested = false
        if (!shouldStop) return
        try {
            appContext.stopService(serviceIntent())
        } catch (_: IllegalStateException) {
            // Losing permission or a lifecycle race must not crash logout or settings changes.
        } catch (_: SecurityException) {
            // The next visible, authorized task can retry independently.
        }
    }

    private fun requestStart() {
        val wasRequested = startRequested
        // Persist the authorization before handing control to Android. It never
        // contains credentials, account identity, or queued commands.
        if (!recovery.markPendingWork()) return
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent())
            startRequested = true
        } catch (_: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException. Visibility
            // may have changed before the system processes this request.
            startRequested = wasRequested
            recovery.clear()
        } catch (_: SecurityException) {
            startRequested = wasRequested
            recovery.clear()
        }
    }

    private fun serviceIntent() = Intent(appContext, AgentSessionForegroundService::class.java)
}

/** Durable eligibility for system recreation of an already user-started service. */
internal class AgentSessionRecoveryState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("agent-session-recovery", Context.MODE_PRIVATE)

    val hasPendingWork: Boolean get() = synchronized(lock) { preferences.getBoolean("pending", false) }
    val isStartEligible: Boolean get() = synchronized(lock) {
        val deadline = preferences.getLong("recovery-deadline", 0L)
        preferences.getBoolean("pending", false) &&
            (deadline == 0L || deadline - SystemClock.elapsedRealtime() in 1..RECOVERY_LEASE_MILLIS)
    }
    val isRecovering: Boolean get() = synchronized(lock) { preferences.getLong("recovery-deadline", 0L) != 0L }
    val hasRecoveryLease: Boolean get() = synchronized(lock) {
        val remaining = preferences.getLong("recovery-deadline", 0L) - SystemClock.elapsedRealtime()
        preferences.getBoolean("pending", false) && remaining in 1..RECOVERY_LEASE_MILLIS
    }

    fun markPendingWork(): Boolean = synchronized(lock) {
        preferences.getBoolean("pending", false) || preferences.edit().putBoolean("pending", true).commit()
    }

    fun confirmRunningWork(): Boolean = synchronized(lock) {
        val deadline = preferences.getLong("recovery-deadline", 0L)
        if (deadline != 0L && deadline - SystemClock.elapsedRealtime() !in 1..RECOVERY_LEASE_MILLIS) {
            return@synchronized false
        }
        if (preferences.contains("recovery-deadline")) preferences.edit().remove("recovery-deadline").commit()
        true
    }

    /** Repeated process death cannot extend the original recovery deadline. */
    fun beginRecovery(): Long? = synchronized(lock) {
        if (!preferences.getBoolean("pending", false)) return@synchronized null
        val now = SystemClock.elapsedRealtime()
        val previous = preferences.getLong("recovery-deadline", 0L)
        val remaining = if (previous == 0L) RECOVERY_LEASE_MILLIS else previous - now
        if (remaining !in 1..RECOVERY_LEASE_MILLIS) {
            clear()
            return@synchronized null
        }
        if (!preferences.edit().putLong("recovery-deadline", now + remaining).commit()) {
            clear()
            return@synchronized null
        }
        remaining
    }

    fun clear() = synchronized(lock) {
        if (preferences.contains("pending") || preferences.contains("recovery-deadline")) preferences.edit().clear().commit()
        Unit
    }

    companion object {
        internal const val RECOVERY_LEASE_MILLIS = 60_000L
        private val lock = Any()
    }
}
