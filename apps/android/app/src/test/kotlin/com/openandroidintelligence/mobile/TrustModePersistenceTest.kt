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
 * 开发者信任模式的持久化（条目 2-5）：
 * - 开关状态跨实例恢复；
 * - 损坏数据一律按「未开启」处理；
 * - 持久化不影响确认文本校验（每次开启仍需当次真实确认）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrustModePersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences
        get() = context.getSharedPreferences("open_android_intelligence_platform", Context.MODE_PRIVATE)

    @Test
    fun anEnabledStateSurvivesAReconstruction() {
        val first = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        assertTrue(first.enable(DeveloperTrustMode.Acknowledgement(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT)))

        val second = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        assertTrue("已开启状态必须跨实例恢复", second.isEnabled())
    }

    @Test
    fun aDisabledStateAlsoSurvives() {
        val first = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        first.enable(DeveloperTrustMode.Acknowledgement(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT))
        first.disable()

        val second = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        assertFalse(second.isEnabled())
    }

    @Test
    fun aCorruptedRecordIsTreatedAsDisabled() {
        preferences.edit().putString("developer_trust_mode_enabled", "maybe").commit()

        val restored = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        assertFalse("损坏的记录绝不能把开关恢复成已开启", restored.isEnabled())
    }

    @Test
    fun persistenceDoesNotWaiveTheAcknowledgementCheck() {
        // 上次进程的状态是「已开启」：恢复的只是状态，不是确认。
        preferences.edit().putBoolean("developer_trust_mode_enabled", true).commit()
        val restored = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        assertTrue(restored.isEnabled())

        // 未开启路径：错误的确认文本仍必须被拦下，且不会落盘为已开启。
        preferences.edit().clear().commit()
        val fresh = DeveloperTrustMode(SharedPreferencesTrustModePersistence(preferences))
        assertEquals(false, fresh.enable(DeveloperTrustMode.Acknowledgement("我确认")))
        assertFalse(fresh.isEnabled())
        assertFalse(preferences.contains("developer_trust_mode_enabled"))
    }
}
