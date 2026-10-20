package com.openandroidintelligence.mobile

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 系统数字助理声明的真机契约。
 *
 * 用户在「默认数字助理」里能不能看到本应用，取决于系统能否解析
 * `VoiceInteractionService` 的 `android.voice_interaction` 元数据：
 * - `sessionService` 必须是**带包名的扁平组件名**（pkg/cls）。写成短类名时
 *   `ComponentName.unflattenFromString` 返回 null，系统判成 "No sessionService
 *   specified"，整个应用被 RoleManager 标记为 unqualified（日志：
 *   `has an unqualified voice interaction service`），助理选择列表里看不到本应用；
 * - 会话服务必须 `exported`，否则系统（不同 UID）无法绑定它来创建会话；
 * - `supportsAssist` 必须为 true，否则 Assist 手势不会路由到本应用。
 *
 * 这条测试跑在设备上、读真实的已合并资源与服务表，所以能抓到"改坏了 XML"或
 * "applicationId 变了但包名没跟着变"这类只在真机才暴露的问题。
 */
@RunWith(AndroidJUnit4::class)
class AssistantRoleQualificationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun voiceInteractionMetadataPointsAtAnExportedSessionService() {
        val context = instrumentation.targetContext
        val parser = context.resources.getXml(R.xml.voice_interaction_service)

        var sessionService: String? = null
        var supportsAssist = false
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (parser.name != "voice-interaction-service") continue
            val namespace = "http://schemas.android.com/apk/res/android"
            sessionService = parser.getAttributeValue(namespace, "sessionService")
            supportsAssist = parser.getAttributeBooleanValue(namespace, "supportsAssist", false)
            break
        }
        parser.close()

        assertNotNull("voice_interaction_service.xml 必须声明 sessionService", sessionService)
        val component = ComponentName.unflattenFromString(sessionService!!)
        assertNotNull(
            "sessionService 必须是 pkg/cls 形式的扁平组件名，实际为 $sessionService",
            component,
        )
        assertEquals("sessionService 必须属于本应用", context.packageName, component!!.packageName)

        val serviceInfo = context.packageManager.getServiceInfo(component, 0)
        assertTrue("系统助理会话服务必须 exported，否则系统无法绑定", serviceInfo.exported)
        assertTrue("supportsAssist 必须为 true，否则 Assist 手势不会路由到本应用", supportsAssist)
    }
}
