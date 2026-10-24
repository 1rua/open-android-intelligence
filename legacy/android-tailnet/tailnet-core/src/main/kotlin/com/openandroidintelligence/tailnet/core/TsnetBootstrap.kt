package com.openandroidintelligence.tailnet.core

/**
 * Process-level bootstrap for the pinned userspace core. Android processes
 * have no usable $HOME, so the app (which owns a Context) supplies its
 * app-private writable varRoot here BEFORE any node is started; the native
 * tsnet userspace then uses that directory instead of os.UserConfigDir().
 *
 * Must be called before the first [TailscaleUserspaceCore.start]; idempotent.
 */
object TsnetBootstrap {
    @Volatile
    private var configured = false

    fun configureUserspaceVarRoot(varRoot: String) {
        require(varRoot.isNotBlank()) { "userspace varRoot must not be blank" }
        if (configured) return
        synchronized(this) {
            if (configured) return
            tsnetbridge.Tsnetbridge.setUserspaceVarRoot(varRoot)
            configured = true
        }
    }
}
