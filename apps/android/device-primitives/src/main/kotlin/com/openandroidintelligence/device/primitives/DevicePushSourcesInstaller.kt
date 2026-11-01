package com.openandroidintelligence.device.primitives

import android.content.Context
import com.openandroidintelligence.capability.*
import com.openandroidintelligence.notifications.NotificationCollectionAccess
import com.openandroidintelligence.notifications.NotificationPushObserver
import com.openandroidintelligence.notifications.NotificationPushSource
import com.openandroidintelligence.notifications.RawNotification
import com.openandroidintelligence.sms.SmsCursor
import com.openandroidintelligence.sms.SmsPushCaptureRequest
import com.openandroidintelligence.sms.SmsPushSource

internal object DevicePushSourcesInstaller {
    fun install(context: Context) {
        DevicePushSourcesRegistry.current = object : DevicePushSources {
            private val observers = java.util.concurrent.ConcurrentHashMap<DevicePushNotificationObserver, NotificationPushObserver>()
            override fun installNotificationObserver(observer: DevicePushNotificationObserver) {
                val adapter = object : NotificationPushObserver {
                    override fun accepts(packageName: String) = observer.accepts(packageName)
                    override fun includesContent() = observer.includesContent()
                    override fun onPosted(notification: RawNotification) = observer.onPosted(DevicePushNotification(
                        notification.notificationKey, notification.packageName, notification.postedAtEpochMs,
                        notification.title, notification.body, notification.appLabel, notification.channelId))
                }
                observers[observer] = adapter
                NotificationPushSource.install(adapter)
            }
            override fun clearNotificationObserver(observer: DevicePushNotificationObserver) {
                observers.remove(observer)?.let(NotificationPushSource::clear)
            }
            override fun setNotificationCollectionAccess(allowed: () -> Boolean) = NotificationCollectionAccess.install(allowed)
            override fun captureActiveNotifications() = NotificationPushSource.captureActive()
            override fun captureSms(query: DevicePushSmsQuery, isCurrent: () -> Boolean,
                enqueueDurably: (DevicePushSms) -> Boolean): Boolean {
                val afterId = query.afterId
                val afterTime = query.afterEpochMs
                val result = SmsPushSource(context.contentResolver).capture(SmsPushCaptureRequest(query.fromEpochMs,
                    if (afterId != null && afterTime != null) SmsCursor(afterId, afterTime) else null,
                    query.senders, query.allSenders, query.includeContent, query.maxRecords), isCurrent) { row ->
                    enqueueDurably(DevicePushSms(row.providerId, row.messageAtEpochMs, row.address,
                        row.body, row.threadId, row.subscriptionId))
                }
                return result.morePossible && !result.paused
            }
        }
    }
}
