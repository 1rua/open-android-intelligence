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
        if (!connected) return@synchronized null
        val snapshot = try { activeNotifications() } catch (_: SecurityException) { null }
        if (snapshot == null) {
            collector.clearActive()
            return@synchronized null
        }
        collector.reconcileActive(snapshot)
        collector
    }

    fun posted(notification: RawNotification) = synchronized(lock) {
        if (connected) collector.onPosted(notification)
    }

    fun removed(key: String) = synchronized(lock) {
        if (connected) collector.onRemoved(key)
    }
}
