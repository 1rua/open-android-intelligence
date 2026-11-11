package com.openandroidintelligence.mobile

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Application、Activity 销毁和进程前后台通知，不手工调用生命周期恢复方法。 */
@RunWith(AndroidJUnit4::class)
class ApplicationConnectionLifecycleInstrumentedTest {
    @Test fun destroyingTheActivityKeepsBackgroundDeliveryAndRelaunchRepairsAHalfOpenConnection(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val core = isolatedUrl(requireNotNull(args.getString("oaiCoreUrl")))
        val controller = isolatedUrl(requireNotNull(args.getString("oaiControlUrl")))
        val backgroundDuration = args.getString("oaiBackgroundDurationMs")?.toLong() ?: 10_000L
        require(backgroundDuration in 10_000L..21_600_000L)
        assertEquals("isolated-background-connection-v1", JsonFields.string(control(controller, "status", "GET"), "fixture"))
        val app = ApplicationProvider.getApplicationContext<OpenAndroidIntelligenceApplication>()
        val runtime = app.gatewayRuntime
        // 本方法只运行于任务专用模拟器，隔离Core地址每次唯一，不清除任何既有本地历史。
        runtime.login(core, "connection-e2e", "connection-fixture-only".toCharArray())
        await("Application 登录没有完成") { runtime.phase.value is ConnectionPhase.Connected }
        await("Application 平台事件流没有就绪") { runtime.connectionStatus.value == EventStreamStatus.LIVE }
        val profiles = AndroidAccountProfileStore(app)
        val profile = requireNotNull(profiles.list().singleOrNull { it.gatewayBaseUrl == core && it.username == "connection-e2e" })
        val account = requireNotNull(profiles.binding(profile.localProfileId)).accountId
        val cursor = AndroidEventCursorStore(app, "${profile.localProfileId}:platform")
        val rebuildEvidence = java.util.concurrent.CopyOnWriteArrayList<String>()
        val originalLogSink = GatewayLog.sink
        GatewayLog.sink = { tag, message ->
            originalLogSink?.invoke(tag, message)
            if (tag == "OaiConnection") {
                if (message.startsWith("stage=PLATFORM_START ") && message.contains("reason=NETWORK_CHANGED"))
                    rebuildEvidence += "network_changed"
                else if (message.contains("\"type\":\"connection.reconnect_started\"")) rebuildEvidence += "transport_retry"
            }
        }
        var activity: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("真实 Activity 没有触发进程前台") { processIsStarted() }
            assertEquals(EventStreamStatus.LIVE, runtime.connectionStatus.value)
            val subscriptions = requireNotNull(JsonFields.int(control(controller, "status", "GET"), "subscriptionCount"))
            var previousSubscriptions = subscriptions
            var previousEvidence = rebuildEvidence.size
            requireNotNull(activity).close()
            activity = null
            await("销毁唯一 Activity 后没有进入真实后台") { !processIsStarted() }
            assertSame("销毁Activity不能替换Application连接管理器", runtime, app.gatewayRuntime)
            val prepared = control(controller, "prepare")
            val initialEvent = requireNotNull(JsonFields.string(prepared, "lastEventId"))
            await("后台没有通过真实事件通道提交游标") { cursor.load(account) == initialEvent }
            val backgroundStarted = SystemClock.elapsedRealtime()
            Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.background_started",
                "backgroundDurationMs" to backgroundDuration, "sameApplicationRuntime" to true))))
            val segmentMillis = minOf(60_000L, backgroundDuration / 3)
            var checkpoint = 0
            var backgroundEvent = initialEvent
            while (SystemClock.elapsedRealtime() - backgroundStarted < backgroundDuration) {
                delay(minOf(segmentMillis, backgroundDuration - (SystemClock.elapsedRealtime() - backgroundStarted)).coerceAtLeast(1))
                val advanced = control(controller, "checkpoint")
                backgroundEvent = requireNotNull(JsonFields.string(advanced, "lastEventId"))
                await("息屏后台分段事件未持久提交") { cursor.load(account) == backgroundEvent }
                checkpoint++
                assertEquals(EventStreamStatus.LIVE, runtime.connectionStatus.value)
                val currentSubscriptions = requireNotNull(JsonFields.int(advanced, "subscriptionCount"))
                val causes = rebuildEvidence.drop(previousEvidence)
                if (backgroundDuration == 10_000L) assertEquals("短时UI销毁不能重建健康订阅", subscriptions, currentSubscriptions)
                else if (currentSubscriptions > previousSubscriptions) {
                    assertTrue("后台新增订阅必须有真实路由或传输重试证据",
                        causes.size >= currentSubscriptions - previousSubscriptions)
                }
                assertEquals("后台不能重复登录", 1, JsonFields.int(advanced, "loginCount"))
                assertEquals("后台只能有一个活正式订阅", 1, JsonFields.int(advanced, "liveEventConnections"))
                Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.background_checkpoint",
                    "checkpoint" to checkpoint, "elapsedMs" to SystemClock.elapsedRealtime() - backgroundStarted,
                    "cursorCommitted" to true, "subscriptionCount" to currentSubscriptions, "rebuildReasons" to causes))))
                previousSubscriptions = currentSubscriptions
                previousEvidence = rebuildEvidence.size
            }
            assertEquals("后台不能因 Activity 销毁关闭连接", EventStreamStatus.LIVE, runtime.connectionStatus.value)
            val beforeFault = requireNotNull(JsonFields.int(control(controller, "status", "GET"), "subscriptionCount"))
            assertEquals(previousSubscriptions, beforeFault)
            val frozen = control(controller, "half_open")
            assertEquals(1, JsonFields.int(frozen, "frozenOpen"))
            assertEquals(0, JsonFields.int(frozen, "closedByFixture"))
            val replayEvent = requireNotNull(JsonFields.string(frozen, "lastEventId"))
            assertTrue("半开故障不能提前提交尚未交付的游标", backgroundEvent == cursor.load(account))
            Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.foreground_ready",
                "backgroundMillis" to SystemClock.elapsedRealtime() - backgroundStarted))))
            if (args.getString("oaiWaitForScreenWake") == "true") {
                await("等待外部ADB结束息屏验收", timeout = 30_000) {
                    app.getSystemService(android.os.PowerManager::class.java).isInteractive
                }
            }
            val started = SystemClock.elapsedRealtime()
            activity = ActivityScenario.launch(MainActivity::class.java)
            await("返回前台后没有自动补发并持久保存原事件", timeout = 10_000) { cursor.load(account) == replayEvent }
            val recoveryMillis = SystemClock.elapsedRealtime() - started
            assertTrue("从启动前台Activity到原游标恢复的总耗时不能超过十秒，实际${recoveryMillis}ms", recoveryMillis <= 10_000)
            assertSame(runtime, app.gatewayRuntime)
            assertEquals(EventStreamStatus.LIVE, runtime.connectionStatus.value)
            val finished = control(controller, "finish")
            val finalEvent = requireNotNull(JsonFields.string(finished, "lastEventId"))
            await("恢复后新事件未继续传输") { cursor.load(account) == finalEvent }
            val completed = control(controller, "status", "GET")
            assertEquals("恢复只应替换一个真实订阅", beforeFault + 1, JsonFields.int(completed, "subscriptionCount"))
            assertEquals("全过程没有Core重启", 0, JsonFields.int(completed, "restartCount"))
            assertEquals(1, JsonFields.int(completed, "liveEventConnections"))
            Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.result",
                "case" to "application_activity", "result" to "passed", "recoveryMillis" to recoveryMillis,
                "backgroundMillis" to SystemClock.elapsedRealtime() - backgroundStarted,
                "activityDestroyed" to true, "sameApplicationRuntime" to true,
                "backgroundCursorCommitted" to true, "subscriptionCount" to beforeFault + 1))))
        } finally {
            activity?.close()
            GatewayLog.sink = originalLogSink
        }
    }

    private fun processIsStarted(): Boolean {
        var started = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            started = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        return started
    }

    private suspend fun await(message: String, timeout: Long = 15_000, condition: () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) delay(25)
        }
        assertTrue(message, condition())
    }

    private fun isolatedUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) { "必须指定 adb reverse 的隔离回环地址" }
        return value.trimEnd('/')
    }

    private suspend fun control(base: String, operation: String, method: String = "POST"): JsonValue.JObject = withContext(Dispatchers.IO) {
        val connection = URL("$base/$operation").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            assertEquals("隔离夹具控制请求失败", 200, connection.responseCode)
            requireNotNull(JsonFields.obj(Json.parse(connection.inputStream.bufferedReader().use { it.readText() })))
        } finally { connection.disconnect() }
    }
}
