package com.openandroidintelligence.capability

/** Native event values contain only fields supplied by Android. */
data class DevicePushNotification(val notificationKey: String, val packageName: String, val postedAtEpochMs: Long,
    val title: String?, val body: String?, val appLabel: String?, val channelId: String?)
data class DevicePushSms(val providerId: Long, val receivedAtEpochMs: Long, val senderAddress: String?,
    val body: String?, val threadId: String?, val subscriptionId: Int?, val matchedKeywords: Set<String> = emptySet())
data class DevicePushSmsQuery(val fromEpochMs: Long, val afterId: Long?, val afterEpochMs: Long?,
    val senders: Set<String>, val allSenders: Boolean, val includeContent: Boolean, val maxRecords: Int = 128,
    val keywords: Set<String> = emptySet())

interface DevicePushNotificationObserver {
    fun accepts(packageName: String): Boolean
    fun includesContent(): Boolean
    fun onPosted(notification: DevicePushNotification)
}

/** Host-owned policy crosses this port; collector implementations stay outside the App dependency surface. */
interface DevicePushSources {
    fun installNotificationObserver(observer: DevicePushNotificationObserver)
    fun clearNotificationObserver(observer: DevicePushNotificationObserver)
    fun setNotificationCollectionAccess(allowed: () -> Boolean)
    fun captureActiveNotifications()
    /** Returns true when another bounded page may remain. Cursor persistence belongs to the App queue. */
    fun captureSms(query: DevicePushSmsQuery, isCurrent: () -> Boolean, enqueueDurably: (DevicePushSms) -> Boolean): Boolean =
        captureSms(query, isCurrent, { _, _ -> false }, enqueueDurably)
    fun captureSms(query: DevicePushSmsQuery, isCurrent: () -> Boolean,
        advanceSkippedCursor: (Long, Long) -> Boolean, enqueueDurably: (DevicePushSms) -> Boolean): Boolean
}

object DevicePushSourcesRegistry {
    @Volatile var current: DevicePushSources? = null
}
