package com.openandroidintelligence.mobile

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Keeps the existing process-owned event stream active only while user-started work is running. */
internal class AgentSessionKeepAlive(context: Context) {
    private val appContext = context.applicationContext
    private var startRequested = false

    @Synchronized
    fun update(hasWork: Boolean, appVisible: Boolean) {
        if (!hasWork) {
            stop()
        } else if (appVisible && !startRequested) {
            requestStart()
        }
        // An existing foreground service can continue after the app becomes
        // invisible. A background update must never start or restart one.
    }

    /** Reassert the request after a system stop/timeout, only on a visible lifecycle transition. */
    @Synchronized
    fun onForegrounded(hasWork: Boolean) {
        if (hasWork) requestStart() else stop()
    }

    @Synchronized
    fun stop() {
        if (!startRequested) return
        startRequested = false
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
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent())
            startRequested = true
        } catch (_: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException. Visibility
            // may have changed before the system processes this request.
            startRequested = wasRequested
        } catch (_: SecurityException) {
            startRequested = wasRequested
        }
    }

    private fun serviceIntent() = Intent(appContext, AgentSessionForegroundService::class.java)
}
