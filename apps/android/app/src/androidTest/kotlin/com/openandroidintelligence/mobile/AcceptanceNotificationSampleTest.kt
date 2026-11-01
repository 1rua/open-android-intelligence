package com.openandroidintelligence.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit synthetic OS notification for listener/push acceptance, containing no personal data. */
@RunWith(AndroidJUnit4::class)
class AcceptanceNotificationSampleTest {
    @Test fun postDedicatedSyntheticNotificationThroughAndroid() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("oaiAcceptanceSample") == "post") { "EXPLICIT_ACCEPTANCE_SAMPLE_REQUIRED" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Android notification permission must be granted through the real system UI", manager.areNotificationsEnabled())
        manager.createNotificationChannel(NotificationChannel("oai-acceptance-samples", "OAI验收样本", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(1874591, Notification.Builder(context, "oai-acceptance-samples")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("OAI合成验收样本")
            .setContentText("OAI-SAMPLE-NOTIFICATION-20261101")
            .setStyle(Notification.BigTextStyle().bigText("OAI-SAMPLE-NOTIFICATION-20261101 complete synthetic body"))
            .setAutoCancel(false).build())
    }
}
