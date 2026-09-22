package com.openandroidintelligence.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.DeveloperTrustMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 组合根装配事实（条目 2-5 / 2-12）：
 * - 信任模式开关必须接了真实的持久化后端（开启后能读回 SharedPreferences）；
 * - 渠道策略从 BuildConfig 进入设置环境，插件运行时装配事实如实为 false。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
class ApplicationCompositionTest {

    private val app: OpenAndroidIntelligenceApplication
        get() = ApplicationProvider.getApplicationContext()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun enablingTrustModePersistsItThroughTheWiredBackend() {
        assertTrue(
            app.trustMode.enable(DeveloperTrustMode.Acknowledgement(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)),
        )
        val preferences = context.getSharedPreferences("open_android_intelligence_platform", Context.MODE_PRIVATE)
        assertTrue("组合根必须装配了持久化后端，否则开启动作不会落盘", preferences.contains("developer_trust_mode_enabled"))
        assertEquals(true, preferences.getBoolean("developer_trust_mode_enabled", false))
    }

    @Test
    fun theChannelPolicyAndRuntimeWiringFactsFlowIntoTheSettingsEnvironment() {
        val environment = app.platformSettingsEnvironment()

        assertEquals(BuildConfig.ALLOW_RUNTIME_PLUGINS, environment.allowRuntimePlugins)
        assertEquals(BuildConfig.ALLOW_DEVELOPER_TRUST_MODE, environment.allowDeveloperTrustMode)
        // 生产装配没有任何插件运行时：事实必须如实为 false，供插件管理区域降级。
        assertFalse(app.pluginRuntimesWired)
        assertFalse(environment.pluginRuntimesWired)
    }
}
