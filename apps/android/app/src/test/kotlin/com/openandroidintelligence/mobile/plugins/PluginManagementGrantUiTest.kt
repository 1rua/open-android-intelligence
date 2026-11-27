package com.openandroidintelligence.mobile.plugins

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.mobile.OpenAndroidIntelligenceApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 插件页只提供能力详情快捷入口，不另行修改策略、提供者或系统授权。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenAndroidIntelligenceApplication::class)
class PluginManagementGrantUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var store: PluginInstallStore
    private lateinit var app: OpenAndroidIntelligenceApplication
    private val pluginId = "org.example.device"
    private val first = "org.example.device.read"
    private val second = "org.example.device.control"

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = PluginInstallStore(app)
        store.installRoot.deleteRecursively()
    }

    @After fun tearDown() { store.installRoot.deleteRecursively() }

    private fun install() {
        val capabilities = Json.of(mapOf(
            "provides" to listOf(first, second).map { id ->
                mapOf("id" to id, "version" to "1.0.0", "schema" to "schemas/$id.json")
            },
            "depends" to emptyList<Any>(),
            "kernelPrimitives" to listOf(
                mapOf("id" to "kernel.screen.read", "version" to "1.0.0", "purpose" to "测试读取"),
                mapOf("id" to "kernel.screen.control", "version" to "1.0.0", "purpose" to "测试控制"),
            ),
        )) as JsonValue.JObject
        val contribution = """{"id":"settings-main","title":"插件设置","root":{"type":"section","id":"root","children":[{"type":"permission-request","id":"request","label":"请求读取授权","capability":"$first","action":"request-grant"},{"type":"capability-picker","id":"provider","label":"选择控制提供者","capability":"$second","action":"select-provider"},{"type":"permission-request","id":"primitive","label":"请求系统原语","capability":"kernel.screen.read","action":"request-grant"},{"type":"button","id":"choose","label":"选择插件能力","action":"request-grant"},{"type":"permission-request","id":"unknown","label":"未知能力","capability":"org.example.missing","action":"request-grant"}]}}"""
        store.install(TestAlpPackages.signedPackage(id = pluginId, capabilities = capabilities, settingsContributionJson = contribution))
        app.pluginHost.reload()
    }

    @Test fun declarativeGrantAndProviderActionsOpenExactlyTheDeclaredCapability() {
        install()
        val entries = app.pluginHost.entries(pluginId)
        assertEquals(setOf(first, second), entries.map { it.id }.toSet())
        val policies = entries.associate { it.key to app.pluginHost.capabilityPolicy(pluginId, it.key) }
        val opened = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { id, key -> opened += id to key }) }
        }
        compose.onNodeWithText("请求读取授权").performScrollTo().performClick()
        assertEquals(pluginId to entries.single { it.id == first }.key, opened.last())
        compose.onNodeWithText("选择控制提供者").performScrollTo().performClick()
        assertEquals(pluginId to entries.single { it.id == second }.key, opened.last())
        assertEquals(2, opened.size)
        entries.forEach { assertEquals(policies[it.key], app.pluginHost.capabilityPolicy(pluginId, it.key)) }
        compose.onNodeWithTag("plugin-grant:accessibility").assertDoesNotExist()
    }

    @Test fun genericActionLetsUserChooseAmongAllDeclaredCapabilities() {
        install()
        val entries = app.pluginHost.entries(pluginId)
        var opened: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { id, key -> opened = id to key }) }
        }
        compose.onNodeWithText("选择插件能力").performScrollTo().performClick()
        compose.onNodeWithText("权限与能力设置").assertIsDisplayed()
        val target = entries.single { it.id == second }
        compose.onNodeWithTag("plugin-capability-choice:$pluginId:${target.key}").performScrollTo().performClick()
        assertEquals(pluginId to target.key, opened)
    }

    @Test fun primitiveGrantActionLetsUserChooseBetweenMatchingDeclaredCapabilities() {
        install()
        val entries = app.pluginHost.entries(pluginId)
        var opened: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { id, key -> opened = id to key }) }
        }
        compose.onNodeWithText("请求系统原语").performScrollTo().performClick()
        compose.onNodeWithText("权限与能力设置").assertIsDisplayed()
        val target = entries.single { it.id == first }
        compose.onNodeWithTag("plugin-capability-choice:$pluginId:${target.key}").performScrollTo().performClick()
        assertEquals(pluginId to target.key, opened)
    }

    @Test fun unknownDeclaredActionDoesNotOpenOrChangeAnUnrelatedCapability() {
        install()
        val policies = app.pluginHost.entries(pluginId).associate { it.key to app.pluginHost.capabilityPolicy(pluginId, it.key) }
        var opened = 0
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { _, _ -> opened++ }) }
        }
        compose.onNodeWithText("未知能力").performScrollTo().performClick()
        compose.onNodeWithText("插件请求的能力未声明，无法打开能力设置。").assertExists()
        assertEquals(0, opened)
        assertTrue(app.pluginHost.entries(pluginId).all { app.pluginHost.capabilityPolicy(pluginId, it.key) == policies[it.key] })
    }

    @Test fun directEntryRoutesToTheSameCapabilityEditor() {
        install()
        val target = app.pluginHost.entries(pluginId).single { it.id == first }
        var opened: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme { PluginManagementScreen(true, true, {}, store = store, onOpenCapability = { id, key -> opened = id to key }) }
        }
        compose.onNodeWithTag("plugin-open-capability:$pluginId:${target.key}").performScrollTo().performClick()
        assertEquals(pluginId to target.key, opened)
    }
}
