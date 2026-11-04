package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 问题二的 UI 回归：授权入口必须按插件实际声明的 kernel primitive 呈现。
 *
 * 修复前「检查 Android 系统权限」只处理 READ_SMS / READ_CALL_LOG，
 * 无障碍、通知读取、屏幕截图三类能力没有任何入口。这些测试钉住：
 * 1. 声明了什么能力，就出现对应的授权入口；
 * 2. 不同类型能力给出**不同**的动作文案（申请权限 / 打开无障碍设置 /
 *    打开通知使用权 / 发起系统授权），而不是笼统一句「检查系统权限」；
 * 3. 已授权时按钮禁用并显示「已授权」，不诱导重复申请。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
class PluginManagementGrantUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var store: PluginInstallStore
    private lateinit var context: OpenAndroidIntelligenceApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PluginInstallStore(context)
        store.installRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        store.installRoot.deleteRecursively()
    }

    /**
     * 渲染一个只声明给定 kernel primitive 的最小插件。
     *
     * 字段形状必须与契约校验一致：`provides` 只允许 id/version/schema；
     * `kernelPrimitives` 每项必须是 id/version/purpose。
     */
    private fun installPlugin(pluginId: String, primitive: String, capabilityId: String) {
        store.install(TestAlpPackages.signedPackage(
            id = pluginId,
            name = "示例设备能力插件",
            capabilities = capabilities(capabilityId, listOf(primitive)),
        ))
        // 授权入口来自宿主载入的能力声明，安装后必须让 host 重新扫描。
        context.pluginHost.reload()
    }

    /** 构造 capabilities 段；risk 属于能力绑定而非 manifest，不能出现在这里。 */
    private fun capabilities(capabilityId: String, primitives: List<String>): JsonValue.JObject = Json.of(
        mapOf(
            "provides" to listOf(
                mapOf(
                    "id" to capabilityId,
                    "version" to "1.0.0",
                    "schema" to "schemas/$capabilityId.json",
                ),
            ),
            "depends" to emptyList<Any>(),
            "kernelPrimitives" to primitives.map {
                mapOf("id" to it, "version" to "1.0.0", "purpose" to "测试声明")
            },
        ),
    ) as JsonValue.JObject

    private fun openGrantDialog(pluginId: String) {
        compose.onNodeWithText("系统权限与后台任务").performScrollTo().performClick()
        compose.onNodeWithText("权限与能力设置").assertIsDisplayed()
    }

    @Test
    fun smsPluginOffersARuntimePermissionEntryOnly() {
        installPlugin("org.example.sms", "kernel.sms.read", "org.example.sms.query")

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        openGrantDialog("org.example.sms")

        compose.onNodeWithTag("plugin-grant:sms").assertExists()
        // 未声明无障碍/通知/截图，就不该出现它们的入口。
        compose.onNodeWithTag("plugin-grant:accessibility").assertDoesNotExist()
        compose.onNodeWithTag("plugin-grant:notifications").assertDoesNotExist()
        compose.onNodeWithTag("plugin-grant:screen-capture").assertDoesNotExist()
        compose.onNodeWithText("短信读取").assertExists()
    }

    @Test
    fun accessibilityPluginPointsAtTheAccessibilitySettingsPage() {
        installPlugin("org.example.reader", "kernel.screen.read", "org.example.reader.read")

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        openGrantDialog("org.example.reader")

        compose.onNodeWithTag("plugin-grant:accessibility").assertExists()
        compose.onNodeWithText("无障碍服务").assertExists()
        // 动作文案必须指向无障碍设置，而不是「申请权限」。
        compose.onNodeWithText("打开无障碍设置").assertIsEnabled()
        compose.onNodeWithTag("plugin-grant:sms").assertDoesNotExist()
    }

    @Test
    fun notificationPluginPointsAtNotificationAccessSettings() {
        installPlugin("org.example.notify", "kernel.notifications.read", "org.example.notify.read")

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        openGrantDialog("org.example.notify")

        compose.onNodeWithTag("plugin-grant:notifications").assertExists()
        compose.onNodeWithText("通知使用权").assertExists()
        compose.onNodeWithText("打开通知使用权设置").assertIsEnabled()
    }

    @Test
    fun capturePluginLaunchesTheSystemDialogInsteadOfOpeningASettingsPage() {
        installPlugin("org.example.capture", "kernel.screen.capture", "org.example.capture.capture")

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        openGrantDialog("org.example.capture")

        compose.onNodeWithTag("plugin-grant:screen-capture").assertExists()
        compose.onNodeWithText("屏幕截图").assertExists()
        // 关键回归：投屏授权必须是「发起系统授权」，不能跳去无关设置页。
        compose.onNodeWithText("发起系统授权").assertIsEnabled()
        compose.onNodeWithText("打开无障碍设置").assertDoesNotExist()
    }

    @Test
    fun screenReadAndControlShareOneAccessibilityEntry() {
        store.install(TestAlpPackages.signedPackage(
            id = "org.example.control",
            name = "示例控制插件",
            capabilities = capabilities(
                "org.example.control.control",
                listOf("kernel.screen.read", "kernel.screen.control"),
            ),
        ))
        context.pluginHost.reload()

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        openGrantDialog("org.example.control")

        // 两个原语共用同一项无障碍授权，不能出现两条重复入口。
        compose.onAllNodesWithText("无障碍服务").onLast().assertExists()
        assertEquals(1, compose.onAllNodesWithText("打开无障碍设置").fetchSemanticsNodes().size)
    }

    @Test
    fun grantedEntryIsDisabledAndSaysSoInsteadOfAskingAgain() {
        installPlugin("org.example.sms2", "kernel.sms.read", "org.example.sms2.query")
        // 预先授予运行时权限：界面必须显示「已授权」而不是再弹一次申请。
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_SMS)

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        openGrantDialog("org.example.sms2")

        compose.onNodeWithTag("plugin-grant:sms").assertTextEquals("已授权").assertIsNotEnabled()
    }

    @Test
    fun pluginsWithoutDevicePrimitivesSaySoInsteadOfShowingEmptyEntries() {
        store.install(TestAlpPackages.signedPackage())
        context.pluginHost.reload()

        compose.setContent { MaterialTheme { PluginManagementScreen(true, true, {}, store = store) } }
        compose.onAllNodesWithText("系统权限与后台任务").onLast().performScrollTo().performClick()
        compose.onNodeWithText("权限与能力设置").assertIsDisplayed()

        // 没有任何设备能力时如实说明，不渲染一排点了没反应的按钮。
        compose.onNodeWithText("这个插件不需要额外的 Android 系统授权。").assertExists()
        assertTrue(
            compose.onAllNodesWithText("申请权限").fetchSemanticsNodes().isEmpty(),
        )
    }

}