package com.openandroidintelligence.notifications

/** Independent of NotificationCollectionPolicy/Task-6 passive read authorization. */
interface NotificationPushObserver {
    /** Must check current login, push enablement, endpoint selection, and package scope. */
    fun accepts(packageName: String): Boolean
    fun includesContent(): Boolean
    /** Must durably enqueue or surface backpressure; no unbounded fire-and-forget queue. */
    fun onPosted(notification: RawNotification)
}

enum class NotificationPushSourceIssue { CAPTURE_FAILED }

/**
 * App composition installs a real observer here. Without one, the OS push branch reads nothing.
 * The lazy raw factory runs only after the independent push gate admits the package.
 */
object NotificationPushSource {
    @Volatile private var observer: NotificationPushObserver? = null
    private val sourceLock = Any()
    private var activeSource: Pair<Any, () -> Unit>? = null
    @Volatile var lastIssue: NotificationPushSourceIssue? = null
        private set

    fun install(value: NotificationPushObserver) { observer = value }
    fun clear(value: NotificationPushObserver) { if (observer === value) observer = null }

    internal fun connected(owner: Any, capture: () -> Unit) = synchronized(sourceLock) { activeSource = owner to capture }
    internal fun disconnected(owner: Any) = synchronized(sourceLock) {
        if (activeSource?.first === owner) activeSource = null
    }

    /** Reconcile currently active OS notifications after login/configuration/process recovery. */
    fun captureActive() {
        val capture = synchronized(sourceLock) { activeSource?.second } ?: return
        try { NotificationCollectionAccess.capture(capture) } catch (_: Exception) { lastIssue = NotificationPushSourceIssue.CAPTURE_FAILED }
    }

    internal fun posted(packageName: String, raw: (includeContent: Boolean) -> RawNotification) {
        val target = observer ?: return
        try {
            if (!target.accepts(packageName)) return
            val notification = raw(target.includesContent())
            // Extraction can race logout, revocation, or observer replacement.
            if (observer === target && target.accepts(packageName)) target.onPosted(notification)
        } catch (_: Exception) { lastIssue = NotificationPushSourceIssue.CAPTURE_FAILED }
    }
}
