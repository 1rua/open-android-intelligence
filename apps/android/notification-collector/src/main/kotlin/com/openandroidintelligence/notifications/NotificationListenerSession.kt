package com.openandroidintelligence.notifications

/** Serializes OS snapshots and callbacks so reconnects and late local grants see current notifications. */
internal class NotificationListenerSession(
    private val collector: AndroidNotificationCollector,
    private val activeNotifications: () -> List<RawNotification>?,
) {
    private val lock = Any()
    private var connected = false

    fun connect() = synchronized(lock) { connected = true }

    fun isConnected(): Boolean = synchronized(lock) { connected }

    fun disconnect() = synchronized(lock) {
        connected = false
        collector.clearActive()
    }

    fun refresh(): AndroidNotificationCollector? = synchronized(lock) {
        if (!canCollect()) return@synchronized null
        val snapshot = try { NotificationCollectionAccess.capture(activeNotifications) } catch (_: SecurityException) { null }
        if (snapshot == null || !canCollect()) {
            collector.clearActive()
            return@synchronized null
        }
        collector.reconcileActive(snapshot)
        if (!canCollect()) return@synchronized null
        collector
    }

    /** The service passes a lazy factory so logout prevents even title/body extraction. */
    fun posted(notification: () -> RawNotification): Boolean = synchronized(lock) {
        if (!canCollect()) return@synchronized false
        val raw = NotificationCollectionAccess.capture(notification) ?: run {
            collector.clearActive()
            return@synchronized false
        }
        if (!canCollect()) return@synchronized false
        collector.onPosted(raw)
    }

    /** Already-normalized test/provider records still cross the same lifecycle gate. */
    fun posted(notification: RawNotification): Boolean = posted { notification }

    fun removed(key: String): Boolean = synchronized(lock) {
        if (!canCollect()) return@synchronized false
        collector.onRemoved(key)
    }

    private fun canCollect(): Boolean {
        if (connected && NotificationCollectionAccess.isAllowed()) return true
        collector.clearActive()
        return false
    }
}
