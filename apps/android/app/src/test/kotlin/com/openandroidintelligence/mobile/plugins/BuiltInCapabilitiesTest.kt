package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.plugin.pkg.PackageRejected
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内建能力从 APK 资源直接装配。
 *
 * 这组断言锁的是用户感受不到的那部分行为：不需要安装、不写磁盘、升级自愈、
 * 单项失败不牵连其他项，以及失败绝不伪装成可用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BuiltInCapabilitiesTest {

    private lateinit var staging: File

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        staging = File(app.cacheDir, "built-in-tests/${UUID.randomUUID()}").apply { mkdirs() }
    }

    private fun readerFor(packages: Map<BundledPlugin, ByteArray>) = BuiltInPackageReader { plugin ->
        packages[plugin] ?: throw BundledPluginUnavailable(plugin)
    }

    private fun load(packages: Map<BundledPlugin, ByteArray>) =
        loadBuiltInCapabilities(
            plugins = BundledPlugin.entries,
            readPackage = readerFor(packages),
            stagingRoot = staging,
        )

    @Test
    fun everyBundledCapabilityLoadsFromResourcesWithoutAnyInstalledCopy() {
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }
        val results = load(packages)

        assertEquals(BundledPlugin.entries.size, results.size)
        assertTrue(results.all { it is BuiltInLoadResult.Loaded })
        val loaded = results.filterIsInstance<BuiltInLoadResult.Loaded>().map { it.capability }
        assertEquals(BundledPlugin.entries.map { it.pluginId }.toSet(), loaded.map { it.pluginId }.toSet())
        loaded.forEach { capability ->
            assertTrue("必须带 WASM 载荷", capability.payload.isNotEmpty())
            assertTrue("必须声明至少一项能力", capability.capabilities.isNotEmpty())
            assertEquals("protected-wasm", capability.runtimeType)
        }
    }

    @Test
    fun loadingWritesNothingIntoThePluginInstallRoot() {
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }
        load(packages)

        // 暂存目录只服务本次校验；装配结束后不留任何可被扫描的副本。
        val leftovers = staging.walkTopDown().filter { it.isFile }.toList()
        assertTrue("内建能力不得在磁盘留下副本：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun repeatedLoadsProduceTheSameResultWithoutSideEffects() {
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }
        val first = load(packages)
        val second = load(packages)

        assertEquals(
            first.map { (it as BuiltInLoadResult.Loaded).capability.identity },
            second.map { (it as BuiltInLoadResult.Loaded).capability.identity },
        )
        assertTrue(staging.walkTopDown().none { it.isFile })
    }

    @Test
    fun aMissingPackageFailsOnlyItsOwnCapability() {
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }
            .filterKeys { it != BundledPlugin.SCREEN_CAPTURE }
        val results = load(packages)

        val failed = results.filterIsInstance<BuiltInLoadResult.Failed>()
        assertEquals(listOf(BundledPlugin.SCREEN_CAPTURE.pluginId), failed.map { it.pluginId })
        assertEquals("BUILT_IN_PACKAGE_MISSING", failed.single().code)
        // 其余五项照常加载：一个包缺失不能拖垮整个能力页。
        assertEquals(BundledPlugin.entries.size - 1, results.filterIsInstance<BuiltInLoadResult.Loaded>().size)
    }

    @Test
    fun aTamperedPackageIsRejectedWithADiagnosableCode() {
        val original = TestAlpPackages.compiledBundledPackage(BundledPlugin.SMS)
        // 破坏签名：校验必须拒绝，且不得注册这项能力。
        val tampered = original.copyOf().also { bytes ->
            val marker = "signature.ed25519".toByteArray()
            val index = bytes.indexOfSequence(marker)
            if (index >= 0) bytes[index + marker.size + 8] = (bytes[index + marker.size + 8].toInt() xor 0x01).toByte()
        }
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) } +
            (BundledPlugin.SMS to tampered)
        val results = load(packages)

        val sms = results.single { it.pluginId == BundledPlugin.SMS.pluginId }
        assertTrue(sms is BuiltInLoadResult.Failed)
        assertEquals("BUILT_IN_PACKAGE_INVALID", (sms as BuiltInLoadResult.Failed).code)
        assertTrue(results.filterIsInstance<BuiltInLoadResult.Loaded>().any { it.pluginId == BundledPlugin.CALL_LOG.pluginId })
    }

    @Test
    fun aPackageImpersonatingAnotherPluginIdIsRefused() {
        // 屏幕截图资产被换成属于别的 ID 的合法包：只能拒绝，不能把它当成截图能力注册。
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) } +
            (BundledPlugin.SCREEN_CAPTURE to TestAlpPackages.compiledBundledPackage(BundledPlugin.SCREEN_READER))
        val results = load(packages)

        val capture = results.single { it.pluginId == BundledPlugin.SCREEN_CAPTURE.pluginId }
        assertEquals("BUILT_IN_PLUGIN_ID_MISMATCH", (capture as BuiltInLoadResult.Failed).code)
    }

    @Test
    fun anUnreadableAssetIsReportedHonestly() {
        val results = loadBuiltInCapabilities(
            plugins = listOf(BundledPlugin.SMS),
            readPackage = BuiltInPackageReader { throw IllegalStateException("asset io") },
            stagingRoot = staging,
        )

        assertEquals("BUILT_IN_PACKAGE_UNREADABLE", (results.single() as BuiltInLoadResult.Failed).code)
    }

    @Test
    fun everyCapabilityKeepsItsOwnKernelPrimitives() {
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }
        val loaded = load(packages).filterIsInstance<BuiltInLoadResult.Loaded>().associateBy { it.pluginId }.mapValues { it.value.capability }

        assertEquals(setOf("kernel.screen.read"), loaded.getValue(BundledPlugin.SCREEN_READER.pluginId).capabilities.single().primitives)
        assertEquals(setOf("kernel.screen.capture"), loaded.getValue(BundledPlugin.SCREEN_CAPTURE.pluginId).capabilities.single().primitives)
        assertEquals(setOf("kernel.screen.control"), loaded.getValue(BundledPlugin.SCREEN_CONTROL.pluginId).capabilities.single().primitives)
        val sms = loaded.getValue(BundledPlugin.SMS.pluginId).capabilities.associateBy { it.id }
        // 每个能力只拿到自己那条原语：查询拿不到定时任务的调度权，反之亦然。
        assertEquals(setOf("kernel.sms.read", "kernel.background.run"),
            sms.getValue("org.openandroidintelligence.sms.query").primitives)
        assertEquals(setOf("kernel.scheduler.set", "kernel.background.run"),
            sms.getValue("org.openandroidintelligence.sms.schedule").primitives)
        assertEquals(setOf("kernel.scheduler.read"),
            sms.getValue("org.openandroidintelligence.sms.job-status").primitives)
        assertEquals(setOf("kernel.scheduler.cancel"),
            sms.getValue("org.openandroidintelligence.sms.cancel").primitives)
    }

    @Test
    fun aFreshInstallExposesEveryBuiltInCapabilityWithoutAnyInstallStep() {
        // 只有装配了插件运行时的渠道才带内置能力包；Play 变体明确不支持，见下一条用例。
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        // 这条是核心验收：全新安装、不点任何安装或启用按钮，六项官方能力就能被读到并执行。
        val host = freshHost()
            val declared = host.verifiedCapabilities()
            assertEquals(BundledPlugin.entries.size, declared.map { it.identity.pluginId }.distinct().size)
            BundledPlugin.entries.forEach { plugin ->
                assertTrue("${plugin.displayName} 必须自带能力", host.entries(plugin.pluginId).isNotEmpty())
                assertTrue("${plugin.displayName} 必须默认可用", host.isEnabled(plugin.pluginId))
            }
            // 插件管理不再列出它们：没有任何可卸载的副本。
            assertTrue(host.installStore.listInstalled().isEmpty())
    }

    @Test
    fun localCapabilityDefaultsSurviveAndAreNotOpenedByBuiltInLoading() {
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        // 内建能力“随应用就绪”不等于“能力被默认授权”：默认仍是每次手动，开关仍可单独关闭。
        val host = freshHost()
            val entry = host.entries(BundledPlugin.SCREEN_CAPTURE.pluginId).single()
            val policy = host.capabilityPolicy(BundledPlugin.SCREEN_CAPTURE.pluginId, entry.key)
            assertTrue("开关默认开启是既有语义", policy.enabled)
            assertEquals(com.openandroidintelligence.kernel.CapabilityAuthorizationMode.MANUAL, policy.mode)

            host.setCapabilityPolicy(BundledPlugin.SCREEN_CAPTURE.pluginId, entry.key, policy.copy(enabled = false))
            host.reload()

            val after = host.capabilityPolicy(BundledPlugin.SCREEN_CAPTURE.pluginId, entry.key)
            assertFalse("重启或重载不能把用户关闭的能力重新打开", after.enabled)
            assertEquals(com.openandroidintelligence.kernel.CapabilityAuthorizationMode.MANUAL, after.mode)
    }

    @Test
    fun anUnreadableBuiltInPackageIsReportedAsFailedAndNeverRegistered() {
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val packages = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }
            .filterKeys { it != BundledPlugin.SCREEN_CONTROL }
        val host = freshHost(packages)
            assertEquals("BUILT_IN_PACKAGE_MISSING", host.builtInFailures[BundledPlugin.SCREEN_CONTROL.pluginId])
            assertTrue("失败项不得注册", host.entries(BundledPlugin.SCREEN_CONTROL.pluginId).isEmpty())
            assertFalse(host.isEnabled(BundledPlugin.SCREEN_CONTROL.pluginId))
            // 一个包缺失不牵连其他五项。
            val others = BundledPlugin.entries.filter { it != BundledPlugin.SCREEN_CONTROL }
            others.forEach { assertTrue(host.entries(it.pluginId).isNotEmpty()) }
    }

    @Test
    fun builtInCapabilitiesAreNotRemovableLikeThirdPartyPlugins() {
        assumeTrue(com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val host = freshHost()
            val failure = runCatching { host.uninstall(BundledPlugin.NOTIFICATIONS.pluginId) }.exceptionOrNull()
            assertEquals("BUILT_IN_CAPABILITY_NOT_REMOVABLE", failure?.message)
            assertTrue("拒绝卸载后能力仍然在", host.entries(BundledPlugin.NOTIFICATIONS.pluginId).isNotEmpty())
    }

    @Test
    fun aChannelWithoutPluginRuntimesReportsEveryCapabilityAsUnavailableWithAReason() {
        // Play 变体不装配插件运行时、也不带内置包。能力页当时只显示“找不到提供者”，
        // 用户看不出这是渠道限制。现在每项都带一个可执行的原因码。
        assumeTrue(!com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val host = freshHost()

        assertEquals(
            BundledPlugin.entries.map { it.pluginId }.toSet(),
            host.builtInFailures.keys,
        )
        host.builtInFailures.values.forEach { assertEquals("BUILT_IN_CHANNEL_UNSUPPORTED", it) }
        BundledPlugin.entries.forEach { assertTrue(host.entries(it.pluginId).isEmpty()) }
    }

    private fun freshHost(packages: Map<BundledPlugin, ByteArray> = BundledPlugin.entries.associateWith { TestAlpPackages.compiledBundledPackage(it) }): ProductionPluginHost {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val audit = com.openandroidintelligence.kernel.AndroidAuditStore()
        val grants = com.openandroidintelligence.kernel.PairingGrantStateHolder(
            com.openandroidintelligence.kernel.InMemoryPairingGrantStore(), audit,
        )
        val trust = com.openandroidintelligence.kernel.DeveloperTrustMode()
        val host = ProductionPluginHost(context, grants, trust, com.openandroidintelligence.kernel.NativePluginLoader(trust), "built-in-tests", readerFor(packages))
        host.attach(
            com.openandroidintelligence.kernel.PluginKernel(
                com.openandroidintelligence.kernel.HostEnvelope(host.hostCapabilities),
                com.openandroidintelligence.kernel.PhoneLimits(host.phoneCapabilities),
                host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
                mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation,
            ),
        )
        return host
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) if (this[start + offset] != needle[offset]) continue@outer
            return start
        }
        return -1
    }
}
