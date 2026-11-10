package com.openandroidintelligence.mobile

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 安装包的真实通知链路：受控 Hermes → 生产 Core → 主工作台 → Android 系统通知。
 *
 * 必须由专用脚本在隔离 Android 用户或独立模拟器中显式运行。唯一被控制的是
 * Agent 的确定性任务何时完成；不手工构造通知、不调用内部通知桥接器。
 */
@RunWith(AndroidJUnit4::class)
class AgentReplyNotificationE2eTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun completedReplyUsesTheExistingSystemConversationNotification(): Unit = runBlocking {
        assumeTrue("仅由隔离设备验收命令运行", arguments.getString("oaiReplyNotificationE2e") == "isolated")
        val coreUrl = loopbackUrl("oaiCoreUrl")
        val controlUrl = loopbackUrl("oaiControlUrl")
        val scenario = arguments.getString("oaiNotificationScenario") ?: "screenOff"
        require(scenario in setOf("background", "screenOff", "otherConversation", "reconnectDuringCompletion"))
        val application = context.applicationContext as OpenAndroidIntelligenceApplication
        val runtime = application.gatewayRuntime
        assertTrue("只能使用空 Android 用户或本次专用验收包", runtime.savedProfiles.value.isEmpty() ||
            context.packageName == "com.openandroidintelligence.mobile.replyvalidation")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("须先经真实系统授予通知权限", manager.areNotificationsEnabled())
        // 专用验收包重跑时只撤回其旧测试通知，保留所有测试账号与会话数据。
        manager.cancelAll()
        val metadata = control(controlUrl, "/state")
        val conversationId = requireNotNull(JsonFields.string(metadata, "conversationId"))
        val otherConversationId = requireNotNull(JsonFields.string(metadata, "otherConversationId"))

        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            withTimeout(15_000) { runtime.localSessionState.first { it !is LocalSessionState.LoadingLocal } }
            onMain {
                runtime.replyNotifications.setEnabled(true)
                runtime.replyNotifications.refreshPermission()
                runtime.login(coreUrl, "alice", "test-only-password".toCharArray())
            }
            withTimeout(30_000) { runtime.phase.first { it is ConnectionPhase.Connected } }
            val workbench = withTimeout(30_000) {
                runtime.controller.filterNotNull().flatMapLatest { controller ->
                    controller.state.map { controller to it }
                }.first { (_, state) -> state.isOnline &&
                    (state.threads as? Loadable.Ready)?.value?.any { it.id.value == conversationId } == true }.first
            }
            onMain { workbench.openThread(conversationId) }
            withTimeout(15_000) { workbench.state.first { it.activeThreadId == conversationId && it.historyHydrated } }
            onMain { workbench.editDraft("disconnect"); workbench.sendDraft() }
            withTimeout(20_000) {
                workbench.state.first { state -> (state.timeline as? Loadable.Ready)?.value?.any {
                    !it.isUser && it.isStreaming && it.text == "开始"
                } == true }
            }
            val running = withTimeout(10_000) { runtime.nativeConversationNotifications.sessions.first { sessions ->
                sessions.any { it.target.agentId == "hermes" && it.target.conversationId == conversationId && it.isRunning }
            } }.single { it.target.agentId == "hermes" && it.target.conversationId == conversationId }
            val generationId = requireNotNull(running.generationId)
            assertEquals("通知必须关联宿主实际受理的轮次", generationId,
                JsonFields.string(control(controlUrl, "/state"), "generationId"))
            assertTrue("普通流式输出不能提前进入完成态", replyNotifications(manager).none {
                it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0
            })

            if (scenario == "otherConversation") {
                onMain { workbench.openThread(otherConversationId) }
                withTimeout(15_000) { workbench.state.first { it.activeThreadId == otherConversationId } }
            } else {
                shell("input keyevent KEYCODE_HOME")
                onMain { runtime.onAppBackgrounded() }
                if (scenario == "screenOff") {
                    shell("input keyevent KEYCODE_SLEEP")
                    assertFalse("完成前设备必须已息屏", context.getSystemService(PowerManager::class.java).isInteractive)
                }
            }
            if (scenario == "reconnectDuringCompletion") {
                control(controlUrl, "/stop-core", "POST")
                withTimeout(30_000) { workbench.state.first { it.streamHealth in setOf(StreamHealth.RECONNECTING, StreamHealth.FAILED) } }
            }
            val released = control(controlUrl, "/release", "POST")
            assertEquals(1L, JsonFields.long(released, "startedCount"))
            assertEquals(1L, JsonFields.long(released, "userCount"))
            assertEquals(0L, JsonFields.long(released, "interrupted"))
            if (scenario == "reconnectDuringCompletion") {
                control(controlUrl, "/start-core", "POST")
                onMain { runtime.onNetworkAvailable(routeChanged = true) }
            }
            val completed = withTimeout(20_000) { runtime.nativeConversationNotifications.sessions.first { sessions ->
                sessions.any { it.target == running.target && it.generationId == generationId &&
                    it.status == NativeConversationStatus.COMPLETED && it.latestReply == FINAL_REPLY }
            } }.single { it.target == running.target }
            assertEquals(generationId, completed.generationId)
            val notification = awaitNotification(manager) { item -> item.notification.channelId == REPLY_CHANNEL_ID &&
                item.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 &&
                (Notification.Builder.recoverBuilder(context, item.notification).style as? Notification.MessagingStyle)
                    ?.messages?.lastOrNull()?.text?.toString() == FINAL_REPLY }
            assertEquals("同轮最多保留一条会话通知", 1, replyNotifications(manager).size)
            assertEquals(Notification.CATEGORY_MESSAGE, notification.notification.category)
            assertNotNull("保留 Android 原生消息气泡元数据", notification.notification.bubbleMetadata)
            assertNotNull(notification.notification.contentIntent)
            val shortcut = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts
                .single { it.id == notification.notification.shortcutId }
            assertEquals(running.target, BubbleConversationActivity.targetFromIntent(requireNotNull(shortcut.intent)))
            assertEquals(notification.notification.shortcutId, notification.notification.bubbleMetadata!!.shortcutId)

            // 先离开原会话，再从系统通知栏点击，防止“本来就在目标会话”掩盖导航错误。
            if (workbench.state.value.activeThreadId == conversationId) {
                onMain { workbench.openThread(otherConversationId) }
                withTimeout(15_000) { workbench.state.first { it.activeThreadId == otherConversationId } }
            }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar expand-notifications")
            clickNotificationText(FINAL_REPLY)
            withTimeout(15_000) { workbench.state.first { it.activeThreadId == conversationId } }
            assertEquals("hermes", runtime.nativeAgents.value?.state?.value?.selectedAgentId)
            assertEquals("点击后必须回到实际完成的会话", conversationId, workbench.state.value.activeThreadId)

            // 重连触发真实历史/游标恢复，用户已处理的提醒不得重新出现。
            manager.cancel(notification.tag, notification.id)
            control(controlUrl, "/stop-core", "POST")
            withTimeout(30_000) { workbench.state.first { it.streamHealth in setOf(StreamHealth.RECONNECTING, StreamHealth.FAILED) } }
            control(controlUrl, "/start-core", "POST")
            onMain { runtime.onNetworkAvailable(routeChanged = true) }
            withTimeout(20_000) { workbench.state.first { state -> state.activeThreadId == conversationId &&
                state.streamHealth == StreamHealth.LIVE &&
                (state.timeline as? Loadable.Ready)?.value?.any { !it.isUser && !it.isStreaming && it.text == FINAL_REPLY } == true } }
            assertTrue("历史同步不能把已经处理的完成重新通知", replyNotifications(manager).isEmpty())
            println("OAI通知验收通过 scenario=$scenario agent=hermes conversation=$conversationId generation=$generationId")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            onMain { activity.finish() }
        }
    }

    private fun loopbackUrl(argument: String): String = requireNotNull(arguments.getString(argument)).also { address ->
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in setOf("", "/")) {
            "设备验收仅连接 adb reverse 提供的隔离回环地址"
        }
    }

    private suspend fun control(base: String, path: String, method: String = "GET"): JsonValue.JObject = withContext(Dispatchers.IO) {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            assertEquals("隔离宿主控制口调用失败", 200, connection.responseCode)
            JsonFields.obj(Json.parse(connection.inputStream.bufferedReader().use { it.readText() }))
                ?: error("测试控制口未返回对象")
        } finally { connection.disconnect() }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun replyNotifications(manager: NotificationManager) = manager.activeNotifications.filter {
        it.notification.channelId == REPLY_CHANNEL_ID &&
            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }

    private fun awaitNotification(manager: NotificationManager, predicate: (android.service.notification.StatusBarNotification) -> Boolean): android.service.notification.StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            manager.activeNotifications.firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("系统通知未在期限内出现")
    }

    private fun clickNotificationText(text: String) {
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val matches = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (match in matches) {
                var node: AccessibilityNodeInfo? = match
                while (node != null) {
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    node = node.parent
                }
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("系统通知栏未找到可点击的确定性测试回复")
    }

    private companion object {
        const val FINAL_REPLY = "开始：观察断开后完成"
    }
}
