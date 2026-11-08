package com.openandroidintelligence.mobile.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 已保存的应用选择必须在再次打开时立刻有名字。
 *
 * 缺陷是：每次进入详情都清空已读到的应用目录，枚举完成前界面只能先说
 * 「应用信息暂不可用」，几百毫秒后才变成真名。缓存让第二次打开直接有名字可读。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InstalledNotificationAppCacheTest {

    @Before fun setUp() = InstalledNotificationAppCache.clear()

    @After fun tearDown() = InstalledNotificationAppCache.clear()

    @Test
    fun aSuccessfulReadBecomesImmediatelyAvailableToTheNextScreen() {
        val apps = listOf(InstalledNotificationApp("com.example.chat", "聊天"))

        val first = cachedInstalledNotificationApps(
            ApplicationProvider.getApplicationContext<Application>().packageManager,
            loader = { apps },
        )
        assertEquals(apps, first)
        // 第二次打开时不需要等待枚举：缓存里已经有名字。
        assertEquals(apps, InstalledNotificationAppCache.current())
    }

    @Test
    fun aFailedRefreshKeepsTheLastKnownNamesInsteadOfReportingUnavailable() {
        val known = listOf(InstalledNotificationApp("com.example.chat", "聊天"))
        cachedInstalledNotificationApps(
            ApplicationProvider.getApplicationContext<Application>().packageManager,
            loader = { known },
        )

        // 刷新失败（例如包管理器暂时不可用）不得把已知名字抹掉。
        val failure = runCatching { cachedInstalledNotificationApps(
            ApplicationProvider.getApplicationContext<Application>().packageManager,
            loader = { error("package manager unavailable") },
        ) }
        assertTrue(failure.isFailure)
        assertEquals("读取失败不能清空已保存的名字", known, InstalledNotificationAppCache.current())
    }

    @Test
    fun anEmptyCatalogueIsStillARealAnswerAndReplacesStaleEntries() {
        val dead = listOf(InstalledNotificationApp("com.example.gone", "已卸载"))
        cachedInstalledNotificationApps(
            ApplicationProvider.getApplicationContext<Application>().packageManager,
            loader = { dead },
        )

        cachedInstalledNotificationApps(
            ApplicationProvider.getApplicationContext<Application>().packageManager,
            loader = { emptyList() },
        )
        // 空列表是有效事实：不能因为“没有应用”就退回上一次的陈旧名单。
        assertEquals(emptyList<InstalledNotificationApp>(), InstalledNotificationAppCache.current())
    }

    @Test
    fun beforeAnyReadTheCacheReportsUnknownRatherThanEmpty() {
        // null 与空列表必须可区分：前者是“还没读到”，后者是“确实没有应用”。
        assertNull(InstalledNotificationAppCache.current())
    }
}
