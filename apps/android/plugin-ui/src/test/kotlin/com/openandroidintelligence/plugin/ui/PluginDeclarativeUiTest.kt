package com.openandroidintelligence.plugin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 声明式 UI 宿主渲染器的契约测试。
 *
 * 安全边界（契约 §8:245-247 与 :156）：
 * - 只渲染八类白名单组件；未知组件/动作在解析层拒绝，渲染层对绕过解析器
 *   直接构造的树重新断言动作-组件组合白名单（fail closed，绝不 fallback）；
 * - 深度/数量超限拒绝；
 * - SET_SETTING 等动作只经宿主注入的 [PluginUiActionSink] 出口提交，
 *   渲染器自身不得有存储或网络副作用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginDeclarativeUiTest {

    @get:Rule
    val compose = createComposeRule()

    /** 记录 sink 收到的每一次动作。 */
    private class RecordingSink : PluginUiActionSink {
        val received = mutableListOf<Triple<String, UiActionId, PluginUiActionValue>>()
        override fun onAction(componentId: String, action: UiActionId, value: PluginUiActionValue) {
            received += Triple(componentId, action, value)
        }
    }

    private fun renderContribution(
        contribution: UiContribution,
        sink: RecordingSink = RecordingSink(),
    ): RecordingSink {
        compose.setContent {
            MaterialTheme {
                PluginDeclarativeUi(
                    contribution = contribution,
                    onAction = sink,
                )
            }
        }
        return sink
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() {
        assertExists()
    }

    // ------------------------------------------------------------------
    // 八类组件逐类渲染
    // ------------------------------------------------------------------

    @Test
    fun sectionRendersItsLabelAndChildren() {
        val contribution = UiContribution(
            id = "settings",
            title = "插件设置",
            root = UiComponent.Section(
                id = "root",
                label = "常规",
                children = listOf(
                    UiComponent.Text(id = "t", label = "说明", value = "本地策略查询", action = null),
                ),
            ),
        )
        renderContribution(contribution)
        compose.onNodeWithText("插件设置").assertExists()
        compose.onNodeWithText("常规").assertExists()
        compose.onNodeWithText("本地策略查询").assertExists()
    }

    @Test
    fun textComponentRendersItsValue() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Text(id = "t", label = "版本", value = "1.0.0", action = null),
        )
        renderContribution(contribution)
        compose.onNodeWithText("版本").assertExists()
        compose.onNodeWithText("1.0.0").assertExists()
    }

    @Test
    fun statusComponentRendersItsValue() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Status(
                id = "s",
                label = "同步状态",
                value = "已连接",
                severity = UiSeverity.INFO,
                action = null,
            ),
        )
        renderContribution(contribution)
        compose.onNodeWithText("已连接").assertExists()
    }

    @Test
    fun toggleSendsSetSettingThroughInjectedSink() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Toggle(id = "g", label = "启用通知", value = false, action = UiActionId.SET_SETTING),
        )
        val sink = renderContribution(contribution)
        compose.onNodeWithText("启用通知").performClick()
        assertEquals(1, sink.received.size)
        assertEquals("g", sink.received[0].first)
        assertEquals(UiActionId.SET_SETTING, sink.received[0].second)
        assertEquals(PluginUiActionValue.ToggleValue(true), sink.received[0].third)
    }

    @Test
    fun selectSendsChosenOptionThroughInjectedSink() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Select(
                id = "sel",
                label = "数据源",
                value = "a",
                options = listOf(
                    UiSelectOption(id = "a", label = "选项甲"),
                    UiSelectOption(id = "b", label = "选项乙"),
                ),
                action = UiActionId.SET_SETTING,
            ),
        )
        val sink = renderContribution(contribution)
        compose.onNodeWithText("选项乙").performClick()
        assertEquals(1, sink.received.size)
        assertEquals("sel", sink.received[0].first)
        assertEquals(UiActionId.SET_SETTING, sink.received[0].second)
        assertEquals(PluginUiActionValue.ChoiceValue("b"), sink.received[0].third)
    }

    @Test
    fun buttonSendsItsActionThroughInjectedSink() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Button(id = "b", label = "立即同步", action = UiActionId.INVOKE_CAPABILITY),
        )
        val sink = renderContribution(contribution)
        compose.onNodeWithText("立即同步").performClick()
        assertEquals(1, sink.received.size)
        assertEquals("b", sink.received[0].first)
        assertEquals(UiActionId.INVOKE_CAPABILITY, sink.received[0].second)
    }

    @Test
    fun permissionRequestSendsRequestGrantThroughInjectedSink() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.PermissionRequest(
                id = "p",
                label = "读取短信",
                capability = "kernel.sms.read",
                action = UiActionId.REQUEST_GRANT,
            ),
        )
        val sink = renderContribution(contribution)
        compose.onNodeWithText("读取短信").performClick()
        assertEquals(1, sink.received.size)
        assertEquals("p", sink.received[0].first)
        assertEquals(UiActionId.REQUEST_GRANT, sink.received[0].second)
        assertEquals(PluginUiActionValue.CapabilityValue("kernel.sms.read"), sink.received[0].third)
    }

    @Test
    fun capabilityPickerSendsSelectProviderThroughInjectedSink() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.CapabilityPicker(
                id = "cp",
                label = "选择提供者",
                capability = "org.openandroidintelligence.sms.query",
                action = UiActionId.SELECT_PROVIDER,
            ),
        )
        val sink = renderContribution(contribution)
        compose.onNodeWithText("选择提供者").performClick()
        assertEquals(1, sink.received.size)
        assertEquals("cp", sink.received[0].first)
        assertEquals(UiActionId.SELECT_PROVIDER, sink.received[0].second)
        assertEquals(
            PluginUiActionValue.CapabilityValue("org.openandroidintelligence.sms.query"),
            sink.received[0].third,
        )
    }

    // ------------------------------------------------------------------
    // fail closed：渲染层对绕过解析器的树重新断言安全边界
    // ------------------------------------------------------------------

    @Test
    fun renderPolicyRejectsComponentCarryingActionItsKindMayNotCarry() {
        // UiComponent 是公开数据类，宿主或运行时可以绕过 DeclarativeUiSchema.parse
        // 直接构造树。渲染器必须拒绝动作与组件种类不匹配的树，而不是静默渲染。
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Toggle(
                id = "g",
                label = "越权开关",
                value = true,
                action = UiActionId.INVOKE_CAPABILITY, // toggle 只允许 set-setting
            ),
        )
        try {
            PluginDeclarativeUiPolicy.validateForRendering(contribution)
            fail("expected UiRejected for a toggle carrying invoke-capability")
        } catch (cause: UiRejected) {
            assertTrue(cause.message!!.contains("UI_ACTION_NOT_ALLOWED"))
        }
    }

    @Test
    fun renderPolicyRejectsTreesNestedPastTheDepthLimit() {
        var node: UiComponent = UiComponent.Text(id = "leaf", label = null, value = "hi", action = null)
        repeat(12) { index ->
            node = UiComponent.Section(id = "s$index", label = null, children = listOf(node))
        }
        try {
            PluginDeclarativeUiPolicy.validateForRendering(UiContribution(id = "c", title = null, root = node))
            fail("expected UiRejected for a tree nested past the depth limit")
        } catch (cause: UiRejected) {
            assertTrue(cause.message!!.contains("UI_TOO_DEEP"))
        }
    }

    @Test
    fun renderPolicyRejectsTreesWithMoreThanTheComponentLimit() {
        val children = (0 until 300).map {
            UiComponent.Text(id = "t$it", label = null, value = "v", action = null)
        }
        try {
            PluginDeclarativeUiPolicy.validateForRendering(
                UiContribution(
                    id = "c",
                    title = null,
                    root = UiComponent.Section(id = "root", label = null, children = children),
                ),
            )
            fail("expected UiRejected for a tree with more than the component limit")
        } catch (cause: UiRejected) {
            assertTrue(cause.message!!.contains("UI_TOO_MANY_COMPONENTS"))
        }
    }

    @Test
    fun renderPolicyRejectsContentThatSmugglesMarkup() {
        val contribution = UiContribution(
            id = "c",
            title = null,
            root = UiComponent.Text(id = "t", label = null, value = "<script>alert(1)</script>", action = null),
        )
        try {
            PluginDeclarativeUiPolicy.validateForRendering(contribution)
            fail("expected UiRejected for markup smuggled through a directly built tree")
        } catch (cause: UiRejected) {
            assertTrue(cause.message!!.contains("UI_FORBIDDEN_CONTENT"))
        }
    }

    @Test
    fun parseStillRejectsUnknownComponentKindBeforeRendering() {
        // 守护：未知组件种类在解析层即拒绝（§8:247），永远不会进入渲染。
        try {
            DeclarativeUiSchema.parse(
                """{"id":"x","root":{"type":"webview","id":"w"}}""",
            )
            fail("expected UiRejected for an unknown component kind")
        } catch (cause: UiRejected) {
            assertTrue(cause.message!!.contains("UI_UNKNOWN_COMPONENT:webview"))
        }
    }
}
