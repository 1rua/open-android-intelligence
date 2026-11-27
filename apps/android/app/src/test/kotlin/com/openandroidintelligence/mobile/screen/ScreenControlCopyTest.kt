package com.openandroidintelligence.mobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地确认弹层的文案翻译。
 *
 * 改造前的弹窗把 `pluginId` / 能力 ID / 原始 JSON 直接打在用户面前。这里的断言
 * 锁住三件事：
 * 1. 每种屏幕动作都有用户看得懂的说明；
 * 2. 敏感内容（nodeId 原文、输入正文）不出现在确认文案里；
 * 3. 非屏幕能力不能被说成屏幕操作。
 */
class ScreenControlCopyTest {

    private val screenCapability = "org.openandroidintelligence.screen-control.control"

    private fun summary(parameters: String, capability: String = screenCapability) =
        summarizeDeviceControl(capability, parameters)

    @Test
    fun clickByNodeIsDescribedWithoutLeakingNodeReference() {
        val result = summary("""{"action":"click","nodeId":"node-abc-123","expectedRevision":"r1"}""")

        assertTrue(result.isScreen)
        assertEquals("点击界面上的控件", result.action)
        // nodeId 是无意义的内部引用，绝不能出现在用户文案里。
        assertFalse(result.action.contains("node-abc-123"))
    }

    @Test
    fun coordinateClickIsDistinguishedFromSemanticClick() {
        val byCoordinate = summary("""{"action":"click","x":100,"y":200,"expectedRevision":"r1"}""")
        assertEquals("在屏幕坐标处点击一下", byCoordinate.action)
    }

    @Test
    fun allDocumentedActionsHaveDistinctReadableLabels() {
        val actions = listOf(
            """{"action":"click","nodeId":"n"}""",
            """{"action":"longPress","nodeId":"n"}""",
            """{"action":"setText","nodeId":"n","text":"secret"}""",
            """{"action":"scroll","nodeId":"n","direction":"forward"}""",
            """{"action":"swipe","x":1,"y":2,"endX":3,"endY":4}""",
            """{"action":"drag","x":1,"y":2,"endX":3,"endY":4}""",
            """{"action":"global","globalAction":"back"}""",
            """{"action":"launchApp","packageName":"com.example.app"}""",
        ).map { summary(it).action }

        // 每种动作的说法互不相同，用户才能判断这次要发生什么。
        assertEquals(actions.size, actions.toSet().size)
        actions.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun typedTextIsNeverEchoedBackInConfirmation() {
        val result = summary("""{"action":"setText","nodeId":"n","text":"我的银行卡密码是123456"}""")

        assertEquals("在输入框中填写文字", result.action)
        // 用户输入内容不重复展示：确认弹窗不是展示正文的地方。
        assertFalse(result.action.contains("银行卡"))
        assertFalse(result.action.contains("123456"))
    }

    @Test
    fun globalActionsAreTranslatedOneByOne() {
        val mapping = mapOf(
            "back" to "执行返回操作",
            "home" to "回到主屏幕",
            "recents" to "打开最近任务",
            "notifications" to "下拉打开通知栏",
            "quickSettings" to "打开快捷设置面板",
        )
        mapping.forEach { (action, expected) ->
            assertEquals(expected, summary("""{"action":"global","globalAction":"$action"}""").action)
        }
    }

    @Test
    fun launchAppShowsTargetPackageAsTheSubject() {
        val result = summary("""{"action":"launchApp","packageName":"com.example.target"}""")
        assertTrue(result.action.contains("com.example.target"))
        assertTrue(result.action.startsWith("打开应用"))
    }

    @Test
    fun screenActionsAreFlaggedAsScreenSoTheDialogCanWarnProperly() {
        assertTrue(summary("""{"action":"click","nodeId":"n"}""").isScreen)
    }

    @Test
    fun nonScreenCapabilitiesAreNotDescribedAsScreenOperations() {
        val sms = summarizeDeviceControl(
            "org.openandroidintelligence.sms-send.send",
            """{"recipient":"10086","body":"hi"}""",
        )
        assertFalse(sms.isScreen)
        assertEquals("发送短信", sms.action)
        // 短信正文同样不得回显。
        assertFalse(sms.action.contains("hi"))
    }

    @Test
    fun unknownCapabilityFallsBackToNeutralWording() {
        val result = summarizeDeviceControl("org.example.unknown.thing", "{}")
        assertFalse(result.isScreen)
        assertEquals("执行设备操作", result.action)
    }

    @Test
    fun malformedParametersDoNotThrowAndDoNotLeakRawJson() {
        val result = summary("""{这个不是合法 JSON""")
        assertTrue(result.action.isNotBlank())
        assertFalse(result.action.contains("{"))
    }

    @Test
    fun emptyParametersAreTreatedAsReadInsteadOfFabricatingAnAction() {
        val result = summary("")
        // 没有 action 字段时不能编造具体动作；读取是唯一安全的默认描述。
        assertEquals("读取屏幕内容", result.action)
        assertNull(result.target)
    }

    @Test
    fun screenCapabilityPrefixIsRecognisedEvenWithoutPluginId() {
        val byKernelPrimitive = summarizeDeviceControl("kernel.screen.read", """{"operation":"snapshot"}""")
        assertTrue(byKernelPrimitive.isScreen)
    }
}
