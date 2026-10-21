package com.openandroidintelligence.mobile.notifications

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.BuildConfig
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class InstalledNotificationAppsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val packageManager: PackageManager = context.packageManager

    private fun install(packageId: String, label: String) {
        shadowOf(packageManager).installPackage(
            PackageInfo().apply {
                packageName = packageId
                applicationInfo = ApplicationInfo().apply {
                    packageName = packageId
                    nonLocalizedLabel = label
                    flags = ApplicationInfo.FLAG_INSTALLED
                    enabled = true
                }
            },
        )
        // Robolectric 未替新包登记系统默认图标；补齐真实平台会提供的默认资源。
        shadowOf(packageManager).setApplicationIcon(packageId, packageManager.defaultActivityIcon)
    }

    @Test
    fun reads_installed_package_label_and_icon_from_package_manager() {
        val packageId = "com.example.notificationpicker.mail"
        val icon = ColorDrawable(Color.BLUE)
        install(packageId, "邮件")
        shadowOf(packageManager).setApplicationIcon(packageId, icon)

        val application = loadInstalledNotificationApps(packageManager).single { it.packageId == packageId }

        assertEquals("邮件", application.label)
        assertSame(icon, application.icon)
    }

    @Test
    fun includes_installed_application_without_a_launcher_activity() {
        val packageId = "com.example.notificationpicker.service"
        install(packageId, "通知服务")
        assertNull(packageManager.getLaunchIntentForPackage(packageId))

        val application = loadInstalledNotificationApps(packageManager).single { it.packageId == packageId }

        assertEquals("通知服务", application.label)
        assertNotNull(application.icon)
    }

    @Test
    fun keeps_packages_with_the_same_label_and_orders_them_by_package_id() {
        val first = "com.example.notificationpicker.a"
        val second = "com.example.notificationpicker.b"
        install(second, "同名应用")
        install(first, "同名应用")

        val applications = loadInstalledNotificationApps(packageManager).filter { it.packageId in setOf(first, second) }

        assertEquals(listOf(first, second), applications.map { it.packageId })
        assertEquals(listOf("同名应用", "同名应用"), applications.map { it.label })
    }

    @Test
    fun orders_labels_with_the_current_language_collation() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRENCH)
            val accented = "com.example.notificationpicker.accented"
            val last = "com.example.notificationpicker.last"
            install(last, "Zulu")
            install(accented, "Éclair")

            val applications = loadInstalledNotificationApps(packageManager)
                .filter { it.packageId in setOf(accented, last) }

            assertEquals(listOf(accented, last), applications.map { it.packageId })
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun removed_package_disappears_on_the_next_query() {
        val packageId = "com.example.notificationpicker.removed"
        install(packageId, "待卸载应用")
        assertTrue(loadInstalledNotificationApps(packageManager).any { it.packageId == packageId })

        shadowOf(packageManager).removePackage(packageId)

        assertFalse(loadInstalledNotificationApps(packageManager).any { it.packageId == packageId })
    }

    @Test
    @Config(shadows = [DuplicateApplicationsShadow::class])
    fun duplicate_package_results_are_returned_once() {
        val packageId = "com.example.notificationpicker.duplicate"
        install(packageId, "重复记录")
        assertEquals(2, packageManager.getInstalledApplications(0).count { it.packageName == packageId })

        val applications = loadInstalledNotificationApps(packageManager)

        assertEquals(1, applications.count { it.packageId == packageId })
    }

    @Test
    @Config(shadows = [UninstallDuringQueryShadow::class])
    fun uninstall_after_enumeration_does_not_fail_the_remaining_application_list() {
        val remaining = "com.example.notificationpicker.remaining"
        install(UNINSTALLED_DURING_QUERY, "查询时卸载")
        install(remaining, "保留应用")

        val applications = loadInstalledNotificationApps(packageManager)

        assertFalse(applications.any { it.packageId == UNINSTALLED_DURING_QUERY })
        assertTrue(applications.any { it.packageId == remaining })
    }

    @Test
    fun only_full_distribution_requests_access_to_all_installed_packages() {
        val requestedPermissions = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()

        assertEquals(
            BuildConfig.FLAVOR == "full",
            Manifest.permission.QUERY_ALL_PACKAGES in requestedPermissions,
        )
    }

    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class DuplicateApplicationsShadow : ShadowApplicationPackageManager() {
        @Implementation
        override fun getInstalledApplications(flags: Int): List<ApplicationInfo> =
            super.getInstalledApplications(flags).flatMap { listOf(it, it) }
    }

    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class UninstallDuringQueryShadow : ShadowApplicationPackageManager() {
        @Implementation
        override fun getInstalledApplications(flags: Int): List<ApplicationInfo> {
            val applications = super.getInstalledApplications(flags)
            removePackage(UNINSTALLED_DURING_QUERY)
            return applications
        }
    }

    companion object {
        private const val UNINSTALLED_DURING_QUERY = "com.example.notificationpicker.uninstallrace"
    }
}
