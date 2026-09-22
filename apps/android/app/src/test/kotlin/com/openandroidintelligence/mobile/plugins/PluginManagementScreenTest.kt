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
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.PlatformSettingsEnvironment
import com.openandroidintelligence.mobile.SettingsScreen
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        compose.onNodeWithText("未安装任何插件").assertIsDisplayed()
        compose.onNodeWithText("宿主未装配插件运行时", substring = true).assertIsDisplayed()
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

        compose.onNodeWithText("示例通知插件").assertIsDisplayed()
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
