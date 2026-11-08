package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityAuthorization
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityRequestScope
import com.openandroidintelligence.kernel.DeviceOperation
import com.openandroidintelligence.kernel.CapabilityPolicy
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
import com.openandroidintelligence.kernel.identityGrantKey
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
 *    「当前版本暂不支持插件运行，这里的设置无法操作」；
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
        compose.onNodeWithText("当前版本暂不支持运行插件", substring = true).performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("当前版本暂不支持插件运行，这里的设置无法操作", substring = true).assertExists()

        // 点击不静默：如实回显无处授权
        compose.onNodeWithText("合并同类通知").performClick()
        compose.onNodeWithText("当前版本暂不支持插件运行，这里的设置无法操作", substring = true).assertExists()
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun multipleThirdPartyPluginsKeepEnableAndInstallActionsButNoRollback() {
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        store.install(TestAlpPackages.signedPackage(id = "org.example.alpha", name = "示例插件甲"))
        store.install(TestAlpPackages.signedPackage(id = "org.example.beta", name = "示例插件乙"))
        app.pluginHost.reload()
        val installed = store.listInstalled().map { it.pluginId }
        assertEquals(listOf("org.example.alpha", "org.example.beta"), installed)

        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { _, _ -> }) }
        }

        compose.onNodeWithText("恢复上一版本").assertDoesNotExist()
        compose.onAllNodesWithText("启用").assertCountEquals(2)
        compose.onAllNodesWithText("卸载").assertCountEquals(2)
        compose.onNodeWithTag(PLUGIN_INSTALL_ENTRY_TAG).assertIsEnabled()
        compose.onNodeWithText("打开通知访问设置").assertDoesNotExist()

        compose.onAllNodesWithText("启用").onFirst().performScrollTo().performClick()
        assertTrue(app.pluginHost.isEnabled("org.example.alpha"))
        assertFalse(app.pluginHost.isEnabled("org.example.beta"))
        compose.onNodeWithText("停用").assertExists()
        compose.onNodeWithText("启用").assertExists()
        assertEquals(installed, store.listInstalled().map { it.pluginId })
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun normalUninstallRequiresConfirmationAndCancellationKeepsThePlugin() {
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        store.install(TestAlpPackages.signedPackage(id = "org.example.removable"))
        app.pluginHost.reload()
        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }

        compose.onNodeWithTag("plugin-uninstall:org.example.removable").performScrollTo().performClick()
        compose.onNodeWithText("确认卸载插件").assertIsDisplayed()
        assertTrue(store.listInstalled().any { it.pluginId == "org.example.removable" })
        compose.onNodeWithText("取消").performClick()
        assertTrue(store.listInstalled().any { it.pluginId == "org.example.removable" })
        compose.onNodeWithTag("plugin-uninstall:org.example.removable").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-uninstall-confirm").performClick()
        compose.waitForIdle()
        assertFalse(store.listInstalled().any { it.pluginId == "org.example.removable" })
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

        compose.onNodeWithText("插件设置无法显示", substring = true).assertExists()
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

        compose.onNodeWithText("当前版本不允许安装插件", substring = true).assertExists()
        val node = compose.onNodeWithTag(PLUGIN_INSTALL_ENTRY_TAG).fetchSemanticsNode()
        assertFalse("渠道禁止时安装入口不得可点", node.config.getOrNull(SemanticsActions.OnClick) != null)
    }

    /**
     * 官方能力不再出现在插件管理里。
     *
     * 它们由 APK 提供、启动即就绪，因此既没有安装入口，也没有卸载入口；
     * 用户对它们唯一的控制就是「设备能力与授权」里的能力开关。
     */
    @Test fun theSixBuiltInCapabilitiesNeverAppearInPluginManagement() {
        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }

        for (plugin in BundledPlugin.entries) {
            compose.onNodeWithTag(plugin.installTag).assertDoesNotExist()
            compose.onNodeWithTag("plugin-uninstall:${plugin.pluginId}").assertDoesNotExist()
        }
        compose.onAllNodesWithText("内置插件").assertCountEquals(0)
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun thirdPartyPluginsKeepTheirOwnInstallEnableAndUninstallFlow() {
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val view = store.install(TestAlpPackages.signedPackage())
        // 一个不带官方 ID 的第三方插件：它照常有安装、启用与卸载。
        app.pluginHost.reload()
        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }

        compose.onNodeWithTag("plugin-uninstall:${view.pluginId}").performScrollTo().assertExists()
        compose.onNodeWithTag(PLUGIN_INSTALL_ENTRY_TAG).assertIsEnabled()
    }

    /** 即使磁盘上残留官方 ID 的旧副本，插件管理也不把它当成可管理插件。 */
    @Test fun aLeftoverOfficialCopyIsNotOfferedForUninstall() {
        val leftover = File(store.installRoot, SMS_PLUGIN_ID).apply { mkdirs() }
        File(leftover, "manifest.json").writeText("{}")
        compose.setContent { MaterialTheme { PluginManagementScreen(true, false, {}, store = store) } }

        compose.onNodeWithTag("plugin-uninstall:$SMS_PLUGIN_ID").assertDoesNotExist()
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
        // 官方 ID 的副本不再加载，因此这里用第三方 ID 复现同一场景。
        val thirdParty = "org.example.notify"
        store.install(TestAlpPackages.compiledNotificationsPackage(pluginId = thirdParty))
        host.reload();host.enable(thirdParty,true);host.grant(thirdParty,true)
        val authorized = app.pairingGrants.state.value!!.granted
        val legitimate = File(store.installRoot, thirdParty)
        val disguised = File(store.installRoot, "org.example.disguised")
        legitimate.copyRecursively(disguised)
        host.reload()
        assertEquals("PLUGIN_MANIFEST_INVALID",host.loadFailures[disguised.name])
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { _, _ -> }) }
        }
        // Only the legitimate row can expose normal actions or its declarative UI.
        assertEquals(1,compose.onAllNodesWithText("系统权限与后台任务").fetchSemanticsNodes().size)
        compose.onNodeWithTag("damaged-plugin-delete:org.example.disguised").performScrollTo().performClick()
        assertTrue(disguised.exists())
        compose.onNodeWithTag("damaged-plugin-delete-confirm").performClick()
        compose.waitForIdle()
        assertFalse(disguised.exists())
        assertTrue(legitimate.isDirectory)
        assertTrue(host.isEnabled(thirdParty))
        assertEquals(authorized,app.pairingGrants.state.value!!.granted)
        assertTrue(host.loadFailures.isEmpty())
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun anInstalledCapabilityOpensTheSingleEditorWithoutChangingPolicyOrTemporaryApprovals() {
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val thirdParty = "org.example.notify"
        store.install(TestAlpPackages.compiledNotificationsPackage(pluginId = thirdParty))
        app.pluginHost.reload()
        val entry = app.pluginHost.entries(thirdParty).first()
        app.pluginHost.setCapabilityPolicy(thirdParty, entry.key, CapabilityPolicy())
        val approvalScope = CapabilityRequestScope(entry.key, DeviceOperation.READ, "a".repeat(64))
        val policyRevision = requireNotNull(app.pairingGrants.state.value).revision
        app.pairingGrants.authorization.approve("ui-test-approval", approvalScope, 5, policyRevision, 300_000L)
        var opened: Pair<String, String>? = null
        var openedAuthorizations = 0
        compose.setContent {
            MaterialTheme {
                PluginManagementScreen(true, true, {}, store = store,
                    onOpenCapability = { id, capability -> opened = id to capability },
                    onOpenAuthorizations = { openedAuthorizations++ })
            }
        }
        compose.onNodeWithTag("revoke-temporary-approvals").performScrollTo().performClick()
        assertEquals(1, openedAuthorizations)
        assertTrue(app.pairingGrants.authorization.evaluate("ui-test-approval", approvalScope, CapabilityPolicy(), policyRevision)
            is CapabilityAuthorization.Allowed)
        compose.onNodeWithTag("capability-enabled:$thirdParty:${entry.key}").assertDoesNotExist()
        compose.onNodeWithTag("plugin-open-capability:$thirdParty:${entry.key}")
            .performScrollTo().performClick()
        assertEquals(thirdParty to entry.key, opened)
        assertEquals(CapabilityPolicy(), app.pluginHost.capabilityPolicy(thirdParty, entry.key))
        assertFalse(app.pluginHost.isEnabled(thirdParty))
    }

    @Test
    @Config(application = OpenAndroidIntelligenceApplication::class)
    fun explicitBackgroundSwitchDoesNotEnableNormalCapabilityOrAndroidPermission() {
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val context = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.READ_SMS)
        app.pairingGrants.bind(
            PairingGrantBinding(
                "https://gateway.example",
                "test_background_${java.util.UUID.randomUUID()}",
                "test_install",
            ),
        )
        val thirdPartySms = "org.example.sms"
        store.install(TestAlpPackages.compiledSmsPackage(pluginId = thirdPartySms))
        app.pluginHost.reload()
        app.pluginHost.enable(thirdPartySms, true)
        app.pluginHost.setCapabilityEnabled(thirdPartySms, false)
        assertTrue(app.pluginHost.supportsBackground(thirdPartySms))
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.READ_SMS),
        )

        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store) }
        }
        compose.onNodeWithText("允许后台任务").performScrollTo().assertExists()

        compose.onNodeWithTag("plugin-background-toggle:$thirdPartySms").performScrollTo().performClick()
        compose.waitForIdle()

        val localState = requireNotNull(app.pairingGrants.state.value)
        assertTrue(app.pluginHost.isBackgroundEnabled(thirdPartySms))
        assertFalse(app.pluginHost.isCapabilityEnabled(thirdPartySms))
        val smsReadIdentity = app.pluginHost.entries(thirdPartySms).first().identity
        assertFalse(localState.permits(identityGrantKey(smsReadIdentity, "kernel.sms.read")))
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.READ_SMS),
        )
    }

    @Test
    fun localAuthorizationStorageErrorsRemainVisibleInCapabilityDiagnostics() {
        for (code in listOf(
            "LOCAL_AUTHORIZATION_STATE_INCOMPLETE",
            "INSTALLATION_CAPABILITY_MIGRATION_FAILED",
            "LOCAL_AUTHORIZATION_MODEL_UNSUPPORTED",
        )) {
            val label = capabilityStatusLabel("LOCAL_GRANT_REQUIRED", code)
            assertTrue(label.contains("无法读取本机授权设置，请检查存储或升级应用后重试"))
            assertTrue(label.contains(code))
            assertFalse(label.contains("复核"))
        }
    }

    @Test
    fun capabilityStatusLabelsExplainLegacyServerAndLocalReasonsWithoutInventingWireStates() {
        assertEquals(
            "服务端尚不支持 Android 统一授权，请升级 OAI Core；当前设备能力不可用",
            capabilityStatusLabel("AUTHORIZATION_MODEL_UNSUPPORTED"),
        )
        assertEquals("服务端仍使用旧账号能力策略，请升级 OAI Core", capabilityStatusLabel("GATEWAY_DISABLED"))
        assertEquals("本机能力尚未开启或需要重新同步", capabilityStatusLabel("LOCAL_GRANT_REQUIRED"))
        assertTrue(capabilityStatusLabel("SYNC_FAILED", "GRANT_STALE").contains("GRANT_STALE"))
        assertTrue(capabilityStatusLabel("SYNC_RETRYING", "NETWORK_ERROR").contains("正在重试同步"))
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

        compose.onNodeWithText("设备能力与授权").performScrollTo().performClick()
        // 插件管理位于设备能力分类，仍可进入原真实安装流程。
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
