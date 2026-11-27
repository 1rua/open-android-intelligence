package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 磁盘上的官方 ID 副本永远不参与加载——**包括 APK 资产加载失败的时候**。
 *
 * 如果过滤条件只看“本次成功加载的内建能力”，资产缺失或校验失败时，旧版本留在私有目录里的
 * 实现会重新顶上执行：内核运行一份过时实现，而能力页报告加载失败、插件管理又不显示它，
 * 用户既看不到也无法处理。
 *
 * 这里把副本放进宿主真正扫描的安装根目录（`filesDir/plugins`），用完清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegacyOfficialCopyIsNeverLoadedTest {

    private lateinit var store: PluginInstallStore

    @Before fun setUp() {
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val app = ApplicationProvider.getApplicationContext<Application>()
        store = PluginInstallStore(app)
        store.installRoot.deleteRecursively()
    }

    @After fun tearDown() {
        if (::store.isInitialized) runCatching { store.installRoot.deleteRecursively() }
    }

    private fun hostWith(reader: BuiltInPackageReader): ProductionPluginHost {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val audit = AndroidAuditStore()
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        return ProductionPluginHost(context, grants, trust, NativePluginLoader(trust), "legacy-copy", reader).also { host ->
            host.attach(PluginKernel(
                HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities), host.runtimes,
                audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation,
            ))
        }
    }

    private fun installStaleOfficialSmsCopy() {
        // 用真实签名包构造，否则它本来就会被校验拒绝，测不到过滤逻辑。
        store.install(TestAlpPackages.compiledSmsPackage(pluginId = BundledPlugin.SMS.pluginId))
        assertTrue("前提：磁盘上确实有这份旧副本", store.listInstalled().any { it.pluginId == BundledPlugin.SMS.pluginId })
    }

    @Test
    fun aStaleOfficialCopyStaysOutOfTheKernelEvenWhenTheBundledAssetIsMissing() {
        installStaleOfficialSmsCopy()

        // 本次启动读不到任何内置资产：所有包都报告缺失。
        val host = hostWith(BuiltInPackageReader { plugin -> throw BundledPluginUnavailable(plugin) })

        assertEquals("BUILT_IN_PACKAGE_MISSING", host.builtInFailures[BundledPlugin.SMS.pluginId])
        assertTrue("加载失败时不得注册该能力", host.entries(BundledPlugin.SMS.pluginId).isEmpty())
        assertFalse("更不得让旧副本顶上来执行", host.isEnabled(BundledPlugin.SMS.pluginId))
        // 旧副本也不能被当成普通第三方插件出现在插件管理里。
        assertTrue(host.isBuiltIn(BundledPlugin.SMS.pluginId))
    }

    @Test
    fun aStaleOfficialCopyIsIgnoredWhenTheBundledAssetLoadsCorrectly() {
        installStaleOfficialSmsCopy()

        val host = hostWith(BuiltInPackageReader { plugin -> TestAlpPackages.compiledBundledPackage(plugin) })

        val fromApk = host.entries(BundledPlugin.SMS.pluginId)
        assertTrue("正常启动时使用 APK 内的版本", fromApk.isNotEmpty())
        // 磁盘副本仍保留（用户数据不删），但它不参与注册：注册的能力数只等于 APK 版本。
        assertEquals(fromApk.size, host.verifiedCapabilities().count { it.identity.pluginId == BundledPlugin.SMS.pluginId })
        assertTrue(File(store.installRoot, BundledPlugin.SMS.pluginId).isDirectory)
    }
}
