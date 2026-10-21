package com.openandroidintelligence.mobile

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 系统数字助理声明的真机契约。
 *
 * 资格判定（`VoiceInteractionServiceInfo`）逐项检查 `<voice-interaction-service>`：
 * - `sessionService` / `recognitionService` 必须是**类名**：框架用
 *   `ComponentName(serviceInfo.packageName, <value>)` 组装组件，写成 pkg/cls
 *   会得到类名带 "/" 的非法组件；
 * - `recognitionService` 不能缺，缺了直接判 unqualified，应用不会出现在
 *   「默认数字助理」列表里；
 * - `supportsAssist` 必须为 true；
 * - 两个被引用的服务必须真实存在，会话服务还必须 exported（系统以不同 UID 绑定）。
 *
 * 这条测试读的是**已合并的运行时资源与服务表**，所以能抓到"改坏 XML"、
 * "applicationId 变了但类名没跟着变"这类只在真机才暴露的问题。
 */
@RunWith(AndroidJUnit4::class)
class AssistantRoleQualificationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun voiceInteractionMetadataDeclaresBindableSessionAndRecognitionServices() {
        val context = instrumentation.targetContext
        val parser = context.resources.getXml(R.xml.voice_interaction_service)

        var sessionService: String? = null
        var recognitionService: String? = null
        var supportsAssist = false
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (parser.name != "voice-interaction-service") continue
            sessionService = parser.getAttributeValue(androidNamespace, "sessionService")
            recognitionService = parser.getAttributeValue(androidNamespace, "recognitionService")
            supportsAssist = parser.getAttributeBooleanValue(androidNamespace, "supportsAssist", false)
            break
        }
        parser.close()

        assertTrue("supportsAssist 必须为 true，否则 Assist 手势不会路由到本应用", supportsAssist)
        assertQualifiedService(context, "sessionService", sessionService, mustBeExported = true)
        assertQualifiedService(context, "recognitionService", recognitionService, mustBeExported = true)
    }

    private fun assertQualifiedService(
        context: android.content.Context,
        label: String,
        value: String?,
        mustBeExported: Boolean,
    ) {
        assertNotNull("$label 不能缺：缺了系统会判 unqualified", value)
        assertFalse(
            "$label 必须是类名，不能是 pkg/cls 扁平名：$value",
            value!!.contains('/'),
        )
        assertTrue("$label 必须是完整类名：$value", value.contains('.'))
        val component = ComponentName(context.packageName, value)
        val serviceInfo = context.packageManager.getServiceInfo(component, 0)
        assertEquals("$label 必须指向本应用内的服务", context.packageName, component.packageName)
        if (mustBeExported) {
            assertTrue("$label 对应服务必须 exported，否则系统无法绑定", serviceInfo.exported)
        }
    }
}
