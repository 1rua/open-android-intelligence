package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.plugin.pkg.PackageRejected
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 插件管理区域的真实数据源：已安装列表来自对 `filesDir/plugins/` 的真实扫描，
 * 安装走真实的 AlpVerifier 校验 + PluginInstaller 落盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginInstallStoreTest {

    private lateinit var store: PluginInstallStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        store = PluginInstallStore(context)
        store.installRoot.deleteRecursively()
    }

    @Test
    fun anEmptyInstallRootListsNothing() {
        assertEquals(emptyList<InstalledPluginView>(), store.listInstalled())
    }

    @Test
    fun installAWellFormedSignedPackagePersistsItUnderTheInstallRoot() {
        val view = store.install(TestAlpPackages.signedPackage())

        assertEquals("org.example.notifications", view.pluginId)
        assertEquals("1.0.0", view.version)
        assertEquals("示例通知插件", view.displayName)
        assertEquals("protected-wasm", view.runtimeType)
        assertTrue(
            "安装必须真实落盘 manifest.json",
            File(store.installRoot, "org.example.notifications/manifest.json").isFile,
        )
        assertTrue(
            "声明式设置文件必须随包落盘",
            File(store.installRoot, "org.example.notifications/ui/settings-main.json").isFile,
        )
        assertEquals(listOf(view.pluginId), store.listInstalled().map { it.pluginId })
    }

    @Test
    fun aTamperedPackageIsRefusedWithTheVerifierReason() {
        // 签名与 manifest 内容不匹配：校验器必须以 SIGNATURE_INVALID 拒绝。
        val failure = runCatching { store.install(TestAlpPackages.tamperedManifestPackage()) }.exceptionOrNull()

        assertTrue("被篡改的包必须被拒绝：$failure", failure is PackageRejected)
        assertEquals("SIGNATURE_INVALID", failure!!.message)
    }

    @Test
    fun installingTheSamePluginIdTwiceIsRefusedInsteadOfSilentlyUpdating() {
        store.install(TestAlpPackages.signedPackage())

        val failure = runCatching { store.install(TestAlpPackages.signedPackage()) }.exceptionOrNull()

        assertTrue("重复安装必须有明确拒绝：$failure", failure is PluginInstallRefused)
        assertTrue(
            "拒绝码必须指明同 ID 已存在：${failure!!.message}",
            failure.message!!.contains("ALREADY_INSTALLED"),
        )
    }

    @Test
    fun unreadableManifestsAreShownAsUnknownInsteadOfInventedMetadata() {
        val dir = File(store.installRoot, "broken.directory")
        dir.mkdirs()

        val views = store.listInstalled()

        assertEquals(1, views.size)
        assertEquals(null, views[0].pluginId)
        assertEquals("broken.directory", views[0].directory.name)
    }
}
