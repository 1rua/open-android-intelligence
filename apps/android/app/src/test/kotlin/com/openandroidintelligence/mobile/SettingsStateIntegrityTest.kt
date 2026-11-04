package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.mobile.settings.SettingsCategory
import com.openandroidintelligence.mobile.settings.settingsOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsStateIntegrityTest {
    @Test fun categoryOrderAndLegacyDetailOwnershipAreExplicit() {
        assertEquals(listOf("外观与交互", "连接与账号", "通知与提醒", "设备能力与授权", "安全与隐私", "高级设置与关于"), SettingsCategory.entries.map { it.label })
        assertEquals(SettingsRoutes.PAIRING, settingsOwner(SettingsRoutes.NOTIFICATIONS))
        assertEquals(SettingsRoutes.PAIRING, settingsOwner(SettingsRoutes.PLUGINS))
        assertEquals(SettingsRoutes.PAIRING, settingsOwner(SettingsRoutes.DEVICE_PUSH))
        assertEquals(SettingsRoutes.ADVANCED, settingsOwner(SettingsRoutes.APP_UPDATES))
        assertEquals(SettingsRoutes.SECURITY, settingsOwner(SettingsRoutes.AUDIT_LOG))
    }

    @Test fun aSystemNotificationDenialDoesNotRewriteTheSavedPreference() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences("settings-denial-test", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("enabled", true).commit()
        val settings = ReplyNotificationSettings(context, preferences)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.refreshPermission()
        assertFalse(settings.allowed.value)
        assertTrue(settings.enabled.value)
        assertTrue(preferences.getBoolean("enabled", false))
    }

    @Test fun failedPreferenceCommitIsReportedAndDoesNotPublishAnUnsavedToggle() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val real = context.getSharedPreferences("settings-commit-failure", Context.MODE_PRIVATE)
        real.edit().putBoolean("enabled", true).commit()
        val preferences = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val delegate = real.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                    override fun commit(): Boolean = false
                }
            }
        }
        val settings = ReplyNotificationSettings(context, preferences)
        assertFalse(settings.setEnabled(false))
        assertTrue(settings.enabled.value)
        assertTrue(real.getBoolean("enabled", false))
    }

    @Test fun coreEventsAndLoginRemainIndependentFacts() {
        val state = SettingsUiState(
            connectionPhase = ConnectionPhase.Connected("http://core", "user", null, null, com.openandroidintelligence.gateway.http.TransportSecurity.PLAINTEXT),
            loginStatus = GatewayLoginStatus.SIGNED_IN,
            coreHttpHealth = CoreHttpHealth(CoreHttpReachability.REACHABLE),
            eventStreamStatus = EventStreamStatus.RECONNECTING,
            replyNotificationsEnabled = true,
            replyNotificationsAllowed = false,
        )
        assertTrue(state.isGatewayConnected)
        assertEquals(EventStreamStatus.RECONNECTING, state.eventStreamStatus)
        assertEquals(GatewayLoginStatus.SIGNED_IN, state.loginStatus)
        assertEquals(CoreHttpReachability.REACHABLE, state.coreHttpHealth.reachability)
        assertTrue(state.replyNotificationsEnabled)
        assertFalse(state.replyNotificationsAllowed)
    }
}
