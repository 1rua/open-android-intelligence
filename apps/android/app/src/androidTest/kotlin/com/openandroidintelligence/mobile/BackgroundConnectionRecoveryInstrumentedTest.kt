package com.openandroidintelligence.mobile

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.gateway.auth.Ed25519DeviceKeyStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Android socket 与隔离 Core 的传输验收，不依赖会话打开或界面操作。 */
@RunWith(AndroidJUnit4::class)
class BackgroundConnectionRecoveryInstrumentedTest {
    @Test fun aCoreRestartAutomaticallyReplaysEventsWithoutOpeningAConversation(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val core = isolatedUrl(requireNotNull(args.getString("oaiCoreUrl")))
        val controller = isolatedUrl(requireNotNull(args.getString("oaiControlUrl")))
        val transportKind = args.getString("oaiEventTransport") ?: "ws"
        require(transportKind in setOf("ws", "sse"))
        val status = control(controller, "status", "GET")
        assertEquals("必须使用本任务隔离夹具", FIXTURE, JsonFields.string(status, "fixture"))
        val (client, profile, sink, cursors) = connect(core, transportKind)
        val observed = mutableListOf<String>()
        val events = Channel<String>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            client.events().collect { event ->
                val envelope = JsonFields.obj(Json.parse(event.data))
                val payload = JsonFields.obj(JsonFields.field(envelope, "payload"))
                JsonFields.string(payload, "noticeCode")?.let { events.send(it) }
            }
        }
        try {
            withTimeout(10_000) { sink.status.first { it == EventStreamStatus.LIVE } }
            control(controller, "prepare")
            observed += withTimeout(10_000) { events.receive() }
            assertEquals("before_outage", observed.single())
            // 等待生产游标提交，避免以尚未提交的消费者通知触发停机。
            withTimeout(2_000) {
                while (cursors.load(profile.accountId) == null) kotlinx.coroutines.yield()
            }
            val started = SystemClock.elapsedRealtime()
            control(controller, "outage")
            observed += withTimeout(10_000) { events.receive() }
            val recoveryMillis = SystemClock.elapsedRealtime() - started
            assertEquals("during_outage", observed.last())
            assertTrue("前台正常网络下 Core 恢复验收目标为十秒", recoveryMillis <= 10_000)
            control(controller, "finish")
            observed += withTimeout(5_000) { events.receive() }
            assertEquals("离线事件应按原顺序各交付一次",
                listOf("before_outage", "during_outage", "after_recovery"), observed)
            val completed = control(controller, "status", "GET")
            assertEquals("恢复不能重新登录或注册设备", 1, JsonFields.int(completed, "loginCount"))
            assertEquals(1, JsonFields.int(completed, "restartCount"))
            assertTrue("必须观察到真实订阅重新建立", requireNotNull(JsonFields.int(completed, "subscriptionCount")) >= 2)
            Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.result",
                "transport" to transportKind, "recoveryMillis" to recoveryMillis, "orderedEvents" to observed.size,
                "duplicateEvents" to 0, "loginCount" to 1, "result" to "passed"))))
        } finally {
            collector.cancelAndJoin()
            events.close()
            // 测试密钥保留在独立安装标识目录，不清理或覆盖任何已有账号。
        }
    }

    @Test fun aHalfOpenConnectionRecoversWithoutChangingTheEventOwner(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val core = isolatedUrl(requireNotNull(args.getString("oaiCoreUrl")))
        val controller = isolatedUrl(requireNotNull(args.getString("oaiControlUrl")))
        assertEquals(FIXTURE, JsonFields.string(control(controller, "status", "GET"), "fixture"))
        val transportKind = args.getString("oaiEventTransport") ?: "sse"
        require(transportKind in setOf("ws", "sse"))
        val (client, profile, sink, cursors) = connect(core, transportKind)
        client.setAppForeground(true)
        val observed = mutableListOf<String>()
        val events = Channel<String>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            client.events().collect { event ->
                val payload = JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(event.data)), "payload"))
                JsonFields.string(payload, "noticeCode")?.let { events.send(it) }
            }
        }
        try {
            withTimeout(10_000) { sink.status.first { it == EventStreamStatus.LIVE } }
            control(controller, "prepare")
            observed += withTimeout(5_000) { events.receive() }
            withTimeout(2_000) {
                while (cursors.load(profile.accountId) == null) kotlinx.coroutines.yield()
            }
            val generation = client.currentEventGeneration()
            val started = SystemClock.elapsedRealtime()
            val frozen = control(controller, "half_open")
            assertEquals("故障必须发生在已经建立的单一事件 socket", 1, JsonFields.int(frozen, "frozenOpen"))
            assertEquals("测试不能主动关闭旧 socket 伪装半开", 0, JsonFields.int(frozen, "closedByFixture"))
            assertEquals("半开注入时底层仍表面在线", EventStreamStatus.LIVE, sink.status.value)
            // 负面对照可显式省略恢复信号，验证此夹具确实会使原 socket 一直静默。
            if (args.getString("oaiSkipHealthCheck") != "true") client.requestEventHealthCheck("foreground")
            observed += withTimeout(10_000) { events.receive() }
            val recoveryMillis = SystemClock.elapsedRealtime() - started
            control(controller, "finish")
            observed += withTimeout(5_000) { events.receive() }
            assertTrue("半开连接恢复不能超过十秒", recoveryMillis <= 10_000)
            assertEquals(listOf("before_outage", "during_outage", "after_recovery"), observed)
            assertEquals("只替换传输，不创建新的事件流所有者", generation, client.currentEventGeneration())
            val completed = control(controller, "status", "GET")
            assertEquals("半开场景不能重启 Core", 0, JsonFields.int(completed, "restartCount"))
            assertEquals(1, JsonFields.int(completed, "loginCount"))
            assertEquals("必须只增加一个替换订阅", 2, JsonFields.int(completed, "subscriptionCount"))
            assertEquals("旧连接退出后只保留一个正式订阅", 1, JsonFields.int(completed, "liveEventConnections"))
            assertTrue("必须确实丢弃过旧通道字节", requireNotNull(JsonFields.long(completed, "droppedBytes")) > 0)
            Log.i("OaiConnectionE2E", Json.canonical(Json.of(mapOf("event" to "connection.e2e.result",
                "case" to "half_open", "transport" to transportKind, "recoveryMillis" to recoveryMillis,
                "healthSignal" to if (args.getString("oaiSkipHealthCheck") == "true") "periodic" else "foreground",
                "orderedEvents" to observed.size, "duplicateEvents" to 0, "loginCount" to 1,
                "subscriptionCount" to 2, "result" to "passed"))))
        } finally {
            collector.cancelAndJoin()
            events.close()
        }
    }

    private data class TestConnection(val client: GatewayHttpClient, val profile: GatewayProfile,
        val sink: EventStreamStatusSink, val cursors: InMemoryEventCursorStore)

    private suspend fun connect(core: String, transportKind: String): TestConnection {
        val installation = "connection_e2e_" + UUID.randomUUID().toString().replace("-", "")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keys = Ed25519DeviceKeyStore(File(context.filesDir, "connection-e2e/$installation"))
        val anonymous = GatewayTransport(GatewayProfile("fixture", "fixture", "fixture", core))
        val auth = GatewayAuthClient(anonymous, installation, "0.0.0-e2e", Build.VERSION.SDK_INT)
        val negotiation = "neg_" + UUID.randomUUID().toString().replace("-", "")
        auth.negotiate(negotiation)
        val credentials = auth.loginWithPassword(negotiation, "connection-e2e", "connection-fixture-only".toCharArray(),
            "连接验收设备", keys.publicKeyBase64Url(installation))
        val profile = GatewayProfile(credentials.accountId, credentials.deviceId, credentials.sessionId, core,
            accessToken = credentials.accessToken)
        val sink = EventStreamStatusSink()
        val cursors = InMemoryEventCursorStore()
        val transport = GatewayTransport(profile)
        val signer: (ByteArray) -> ByteArray = { keys.sign(installation, it) }
        val client = if (transportKind == "sse") GatewayHttpClient(profile, transport, signer, cursors,
            webSocketTransport = null, statusSink = sink)
        else GatewayHttpClient(profile, transport, signer, cursors, statusSink = sink)
        return TestConnection(client, profile, sink, cursors)
    }

    private fun isolatedUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) { "必须指定 adb reverse 的隔离回环地址" }
        return value.trimEnd('/')
    }

    private suspend fun control(base: String, operation: String, method: String = "POST") =
        isolatedConnectionFixtureControl(base, operation, method)

    private companion object { const val FIXTURE = "isolated-background-connection-v1" }
}

/** 测试控制面只允许明确的回环地址，不携带任何产品认证。 */
internal suspend fun isolatedConnectionFixtureControl(base: String, operation: String, method: String = "POST") =
    withContext(Dispatchers.IO) {
        val uri = URI(base)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in setOf("", "/"))
        require(operation.matches(Regex("[a-z_]+")) && method in setOf("GET", "POST"))
        val connection = URL("$base/$operation").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            assertEquals("隔离夹具控制请求失败", 200, connection.responseCode)
            requireNotNull(JsonFields.obj(Json.parse(connection.inputStream.bufferedReader().use { it.readText() })))
        } finally { connection.disconnect() }
    }
