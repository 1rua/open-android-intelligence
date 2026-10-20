package com.openandroidintelligence.mobile

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.capability.MediaProjectionCaptureService
import com.openandroidintelligence.mobile.assistant.parcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36], application = Application::class)
class AndroidApiCompatibilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun host_manifest_supports_android_12_without_changing_compile_or_target_contract() {
        assertEquals(31, context.applicationInfo.minSdkVersion)
        assertEquals(35, context.applicationInfo.targetSdkVersion)
    }

    @Test
    fun foreground_and_projection_services_keep_their_declared_types() {
        val packageManager = context.packageManager
        val sessionService = packageManager.getServiceInfo(
            ComponentName(context, AgentSessionForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )
        val projectionService = packageManager.getServiceInfo(
            ComponentName(context, MediaProjectionCaptureService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, sessionService.foregroundServiceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, projectionService.foregroundServiceType)
    }

    @Test
    fun assistant_parcelable_reader_is_available_on_every_supported_api() {
        assertNull(Bundle().apply { putParcelable("content", null) }.parcelFileDescriptor("content"))
    }
}
