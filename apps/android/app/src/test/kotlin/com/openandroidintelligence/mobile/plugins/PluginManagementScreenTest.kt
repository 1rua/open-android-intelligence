package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsScreen
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置内插件管理区域（条目 1-1）的界面语义：
 * 1. 无插件时如实空态，宿主未装配运行时必须明说；
 * 2. 已安装列表来自真实存储，声明式 UI 用 PluginDeclarativeUi 渲染并标注
 *    「内核未装配，设置项不可交互」；
 * 3. 渠道策略真实控制安装入口（full 允许 / play 禁止）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginManagementScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var store: PluginInstallStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        store = PluginInstallStore(context)
        store.installRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        store.installRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 诚实降级（条目 1-1 核心）
    // ------------------------------------------------------------------

    @Test
    fun anEmptyInstallRootShowsTheHonestEmptyState() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    PluginManagementScreen(
                        allowRuntimePlugins = true,
                        pluginRuntimesWired = false,
                        onBack = {},
                        store = store,
                    )
                }
            }
        }

        compose.onNodeWithText("未安装任何插件").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("宿主未装配插件运行时", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anInstalledPluginIsListedFromTheRealStore() {
        store.install(TestAlpPackages.signedPackage())

        compose.setContent {
            MaterialTheme {
                Surface {
                    PluginManagementScreen(
                        allowRuntimePlugins = true,
                        pluginRuntimesWired = false,
                        onBack = {},
                        store = store,
                    )
                }
            }
        }

        compose.onNodeWithText("示例通知插件").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("org.example.notifications", substring = true).assertExists()
        compose.onNodeWithText("未启用", substring = true).assertExists()
    }

    // ------------------------------------------------------------------
    // 声明式 UI：已安装插件提供 ui/settings 声明时用 PluginDeclarativeUi 渲染
    // ------------------------------------------------------------------

    @Test
    fun declarativeSettingsRenderWithKernelAbsenceLabelledAndActionsHonestlyRefused() {
        store.install(TestAlpPackages.signedPackage())

        compose.setContent {
            MaterialTheme {
                Surface {
                    PluginManagementScreen(
                        allowRuntimePlugins = true,
                        pluginRuntimesWired = false,
                        onBack = {},
                        store = store,
                    )
                }
            }
        }

        // 声明被渲染（位于长列表下方，用存在性断言避免滚动差异）
        compose.onNodeWithText("合并同类通知").assertExists()
        // 内核未装配必须明说
        compose.onNodeWithText("内核未装配，设置项不可交互", substring = true).assertExists()

        // 点击不静默：如实回显无处授权
        compose.onNodeWithText("合并同类通知").performClick()
        compose.onNodeWithText("内核未装配，设置项不可交互", substring = true).assertExists()
    }

    @Test
    fun aBrokenDeclarationIsReportedInsteadOfRendered() {
        store.install(TestAlpPackages.signedPackage())
        // 移除有效声明，只留下一个无法通过校验的声明文件。
        File(store.installRoot, "org.example.notifications/ui/settings-main.json").delete()
        val bad = File(store.installRoot, "org.example.notifications/ui/broken.json")
        bad.parentFile?.mkdirs()
        bad.writeText("""{"id":"x","root":{"type":"section"}}""")

        compose.setContent {
            MaterialTheme {
                Surface {
                    PluginManagementScreen(
                        allowRuntimePlugins = true,
                        pluginRuntimesWired = false,
                        onBack = {},
                        store = store,
                    )
                }
            }
        }

        compose.onNodeWithText("声明式设置被拒绝", substring = true).assertExists()
        compose.onNodeWithText("合并同类通知").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // 渠道策略真实生效（条目 2-12）：full 允许 / play 禁止
    // ------------------------------------------------------------------

    @Test
    fun theInstallEntryIsClickableWhenTheChannelAllowsRuntimePlugins() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    PluginManagementScreen(
                        allowRuntimePlugins = true,
                        pluginRuntimesWired = false,
                        onBack = {},
                        store = store,
                    )
                }
            }
        }

        val node = compose.onNodeWithTag(PLUGIN_INSTALL_ENTRY_TAG).fetchSemanticsNode()
        assertTrue("渠道允许时安装入口必须可点", node.config.getOrNull(SemanticsActions.OnClick) != null)
    }

    @Test
    fun theInstallEntryIsDisabledAndExplainedWhenTheChannelForbidsRuntimePlugins() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    PluginManagementScreen(
                        allowRuntimePlugins = false,
                        pluginRuntimesWired = false,
                        onBack = {},
                        store = store,
                    )
                }
            }
        }

        compose.onNodeWithText("当前分发渠道禁止安装插件", substring = true).assertExists()
        val node = compose.onNodeWithTag(PLUGIN_INSTALL_ENTRY_TAG).fetchSemanticsNode()
        assertFalse("渠道禁止时安装入口不得可点", node.config.getOrNull(SemanticsActions.OnClick) != null)
    }

    @Test fun theBundledNotificationButtonInstallsThroughTheVerifiedStore() {
        val packageBytes = TestAlpPackages.signedPackage(id = NOTIFICATION_PLUGIN_ID, name = "通知读取插件")
        compose.setContent {
            MaterialTheme {
                PluginManagementScreen(true, false, {}, store = store, bundledNotificationPackage = { packageBytes })
            }
        }
        compose.onNodeWithTag(BUNDLED_NOTIFICATION_INSTALL_TAG).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("通知读取插件已安装").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(NOTIFICATION_PLUGIN_ID, store.listInstalled().single().pluginId)
        val node = compose.onNodeWithTag(BUNDLED_NOTIFICATION_INSTALL_TAG).fetchSemanticsNode()
        assertFalse(node.config.getOrNull(SemanticsActions.OnClick) != null)
        compose.onNodeWithText("已安装，未启用", substring = true).assertExists()
    }

    @Test fun theBundledNotificationButtonExplainsMissingAssetsAndKeepsFileImportAvailable() {
        compose.setContent {
            MaterialTheme {
                PluginManagementScreen(true, false, {}, store = store,
                    bundledNotificationPackage = { throw BundledNotificationUnavailable() })
            }
        }
        compose.onNodeWithTag(BUNDLED_NOTIFICATION_INSTALL_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("当前安装包未附带通知读取插件，请从文件导入已签名的 .alp 插件包。").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(store.listInstalled().isEmpty())
        assertTrue(compose.onNodeWithTag(PLUGIN_INSTALL_ENTRY_TAG).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) != null)
    }

    @Test fun theBundledNotificationButtonIsBlockedByTheDistributionPolicy() {
        var reads = 0
        compose.setContent {
            MaterialTheme {
                PluginManagementScreen(false, false, {}, store = store,
                    bundledNotificationPackage = { reads++; TestAlpPackages.signedPackage(id = NOTIFICATION_PLUGIN_ID) })
            }
        }
        val node = compose.onNodeWithTag(BUNDLED_NOTIFICATION_INSTALL_TAG).fetchSemanticsNode()
        assertFalse(node.config.getOrNull(SemanticsActions.OnClick) != null)
        assertEquals(0, reads)
        assertTrue(store.listInstalled().isEmpty())
    }

    @Test fun anUnreadableManifestCanBeRemovedAfterExplicitConfirmation() {
        val damaged = File(store.installRoot, "org.example.damaged").apply { mkdirs() }
        File(damaged, "manifest.json").writeText("not-json")
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, false, {}, store = store) }
        }
        compose.onNodeWithTag("damaged-plugin-delete:org.example.damaged").performScrollTo().performClick()
        assertTrue("打开确认窗口不能删除文件", damaged.exists())
        compose.onNodeWithTag("damaged-plugin-delete-confirm").performClick()
        compose.waitForIdle()
        assertFalse(damaged.exists())
        compose.onNodeWithText("未安装任何插件").assertExists()
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun aDisguisedDirectoryCannotControlOrRemoveItsLegitimateNamedPlugin() {
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val host = app.pluginHost
        app.pairingGrants.bind(PairingGrantBinding("https://gateway.example", "test_ui", "test_install"))
        store.install(TestAlpPackages.compiledNotificationsPackage())
        host.reload();host.enable(NOTIFICATION_PLUGIN_ID,true);host.grant(NOTIFICATION_PLUGIN_ID,true)
        val authorized = app.pairingGrants.state.value!!.granted
        val legitimate = File(store.installRoot, NOTIFICATION_PLUGIN_ID)
        val disguised = File(store.installRoot, "org.example.disguised")
        legitimate.copyRecursively(disguised)
        host.reload()
        assertEquals("PLUGIN_MANIFEST_INVALID",host.loadFailures[disguised.name])
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store) }
        }
        // Only the legitimate row can expose normal actions or its declarative UI.
        assertEquals(1,compose.onAllNodesWithText("配对授权").fetchSemanticsNodes().size)
        compose.onNodeWithTag("damaged-plugin-delete:org.example.disguised").performScrollTo().performClick()
        assertTrue(disguised.exists())
        compose.onNodeWithTag("damaged-plugin-delete-confirm").performClick()
        compose.waitForIdle()
        assertFalse(disguised.exists())
        assertTrue(legitimate.isDirectory)
        assertTrue(host.isEnabled(NOTIFICATION_PLUGIN_ID))
        assertEquals(authorized,app.pairingGrants.state.value!!.granted)
        assertTrue(host.loadFailures.isEmpty())
    }

    // ------------------------------------------------------------------
    // 路由可达：设置概览 → 插件管理
    // ------------------------------------------------------------------

    @Test
    fun thePluginManagementRouteIsReachableFromSettingsOverview() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsScreen(environment = settingsEnvironment(), runtime = null, onBack = {})
                }
            }
        }

        // 条目位于长列表下方：滚动可见后再点击（越界节点点击不生效）。
        compose.onNodeWithText("插件管理").performScrollTo()
        compose.onNodeWithText("插件管理").performClick()

        compose.onNodeWithText("未安装任何插件").assertExists()
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    private fun settingsEnvironment(): PlatformSettingsEnvironment {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val trustMode = DeveloperTrustMode()
        val auditStore = AndroidAuditStore(InMemoryAuditSink())
        val pairingGrants = PairingGrantStateHolder(
            store = InMemoryPairingGrantStore(),
            audit = auditStore,
        )
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(primitives = emptySet()),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = emptyMap(),
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap()),
            grants = { null },
        )
        return PlatformSettingsEnvironment(
            trustMode = trustMode,
            audit = auditStore,
            auditSink = InMemoryAuditSink(),
            allowDeveloperTrustMode = true,
            kernel = kernel,
            pairingGrants = pairingGrants,
            appearance = AppearancePreferences(context),
        )
    }
}
