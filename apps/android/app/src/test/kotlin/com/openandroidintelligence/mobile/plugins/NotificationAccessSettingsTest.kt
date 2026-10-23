package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationAccessSettingsTest {
    @Test fun notificationAccessUsesThePlatformSettingsAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var opened: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { opened = intent }
        }
        assertTrue(openNotificationAccessSettings(context))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, opened?.action)
        assertTrue(opened!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun aDeviceWithoutNotificationSettingsDoesNotReportSuccessfulNavigation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(openNotificationAccessSettings(context))
    }
}
