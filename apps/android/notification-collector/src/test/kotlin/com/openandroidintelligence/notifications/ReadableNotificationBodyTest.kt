package com.openandroidintelligence.notifications

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadableNotificationBodyTest {
    @Test fun expandedBodyUsesTheFullTextSuppliedByAndroid() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notification = Notification.Builder(app, "test").setContentText("short")
            .setStyle(Notification.BigTextStyle().bigText("complete permitted sample body")).build()
        assertEquals("complete permitted sample body", readableNotificationBody(notification, true))
        assertNull(readableNotificationBody(notification, false))
    }
    @Test fun plainAndMissingBodiesAreKeptAsProvided() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("plain", readableNotificationBody(Notification.Builder(app, "test").setContentText("plain").build(), true))
        assertNull(readableNotificationBody(Notification.Builder(app, "test").build(), true))
    }
}
