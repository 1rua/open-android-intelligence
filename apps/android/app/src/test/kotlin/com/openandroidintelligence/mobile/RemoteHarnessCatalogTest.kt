package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.AuthenticatedRemotePeer
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 官方 Host 的类型清单只细化 Codex/ACP；Harness 使用独立协商能力。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteHarnessCatalogTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.Unconfined)
    private val account = Account()
    private lateinit var runtime: RemoteRuntime
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 23 }, "AES")
        override fun delete() = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "harness-catalog-tests")
        host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
            host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
            mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
        runtime = RemoteRuntime(context, scope, host, grants, { _, _, _ -> false }, { account }, key)
    }

    @After fun close() = runBlocking {
        runtime.logout().join()
        root.cancelAndJoin()
        Dispatchers.resetMain()
    }

    @Test fun `类型清单仅列Codex时仍发现并进入已协商的Harness工作区`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        runtime.discover(account.listHosts().single()).join()

        assertEquals("官方混合后端目录应同时保留 DSH 与 Codex", setOf("harness", "codex"),
            runtime.state.value.endpoints.map { it.endpoint.key.backendId.value }.toSet())
        assertTrue("DSH 工作区必须经过真实发现调用", account.harnessWorkspaceReads > 0)
        val harness = runtime.state.value.endpoints.single { it.endpoint.key.backendId.value == "harness" }
        assertEquals("harness-workspace", harness.endpoint.key.workspaceId)
        runtime.select(harness).join()
        assertNull(runtime.state.value.errorCode)
        assertEquals(harness.endpoint.key, runtime.state.value.selected)
        val loaded = withTimeout(5_000) {
            runtime.controller.value!!.state.first { current ->
                current.activeThreadId == RemoteLocalIds.encode("harness-session") && current.historyHydrated &&
                    (current.timeline as? Loadable.Ready)?.value?.any { it.text == "DSH已保存的回复" } == true
            }
        }
        assertTrue("进入 DSH 工作区后仍使用在线会话", loaded.isOnline)
        assertTrue("进入会话实际读取 Session V3 历史", account.harnessHistoryReads > 0)
        assertTrue("进入会话实际建立 Session V3 实时流", account.harnessSessionFollows > 0)
    }

    @Test fun `通过工作台获取当前远程会话的实际命令并在输入斜杠时刷新`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        runtime.discover(account.listHosts().single()).join()
        runtime.select(runtime.state.value.endpoints.single { it.endpoint.key.backendId.value == "harness" }).join()
        val controller = requireNotNull(runtime.controller.value)
        suspend fun awaitCommand(command: String) = withTimeout(5_000) {
            controller.state.first { (it.catalog as? Loadable.Ready)?.value?.commands?.singleOrNull()?.command == command }
        }
        awaitCommand("/live-first")
        account.commandName = "live-second"
        controller.editDraft("/")
        val updated = awaitCommand("/live-second")
        val catalog = updated.catalog as Loadable.Ready
        assertEquals("<文件>", catalog.value.commands.single().argumentHint)
    }

    @Test fun `类型清单列Codex但未协商Harness能力时不创建DSH工作区`() = runBlocking {
        account.harnessAvailable = false
        runtime.restoreSessionIfAvailable().join()
        runtime.discover(account.listHosts().single()).join()

        assertEquals(setOf("codex"), runtime.state.value.endpoints.map { it.endpoint.key.backendId.value }.toSet())
        assertEquals("缺少 Harness 能力不能探测或构造 DSH 端点", 0, account.harnessWorkspaceReads)
        assertNull(runtime.state.value.errorCode)
    }

    private class Account : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        var harnessAvailable = true
        var harnessWorkspaceReads = 0
        var harnessHistoryReads = 0
        var harnessSessionFollows = 0
        @Volatile var commandName = "live-first"
        private var generation = 0L
        private val peers = mutableMapOf<String, AuthenticatedRemotePeer>()

        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult = error("本测试只恢复已认证账号")
        override suspend fun restore() = RemoteActiveAccount("https://remote.example", "catalog-account", 1).also { account.value = it }
        override suspend fun listHosts() = listOf(RemoteHostDevice("catalog-host", "电脑", "linux", "membership", true))
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
            val current = ++generation
            val binding = RemoteRpcConnectionScope("https://remote.example", "catalog-account", hostDeviceId,
                "catalog-client", "catalog-connection-$current", current)
            peers[binding.connectionId] = AuthenticatedRemotePeer(binding, "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64), RemoteRole.CLIENT)
            return object : RemoteRpcChannel {
                override val scope = binding
                override val capabilities = setOf("codex.appserver.v1") + if (harnessAvailable) setOf("harness.remote.v3") else emptySet()
                override val workspaceTypes = listOf(value(mapOf("id" to "codex", "name" to "CodeX",
                    "capability" to "codex.appserver.v1", "available" to true)) as JsonValue.JObject)
                override val events = MutableSharedFlow<RemoteRpcEvent>(extraBufferCapacity = 64)
                override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
                    if (method == "harness.remote.stream.open") {
                        if (JsonFields.string(params, "endpoint") == "session/follow") harnessSessionFollows++
                        if (JsonFields.string(params, "endpoint") == "workspace/follow") {
                            harnessWorkspaceReads++
                            events.emit(RemoteRpcEvent("harness.remote.frame", value(mapOf(
                                "streamId" to JsonFields.string(params, "streamId"), "value" to mapOf(
                                    "type" to "baseline", "value" to mapOf("items" to listOf(mapOf(
                                        "workspaceId" to "harness-workspace", "path" to "/harness-workspace",
                                        "sessionIds" to listOf("harness-session")))))))))
                        }
                        return value(emptyMap<String, Any>())
                    }
                    if (method == "harness.remote.stream.close") return value(emptyMap<String, Any>())
                    if (method == "codex.app.call") {
                        check(JsonFields.string(params, "method") == "project/list") { "本测试只枚举 Codex 工作区" }
                        return value(mapOf("data" to listOf(mapOf("id" to "codex-workspace", "name" to "Codex 工作区",
                            "roots" to listOf(mapOf("path" to "/codex-workspace"))))))
                    }
                    check(method == "harness.remote.call") { "本测试不应调用其他载体" }
                    val result = when (JsonFields.string(params, "endpoint")) {
                        "session/list" -> mapOf("items" to listOf(mapOf("sessionId" to "harness-session", "title" to "DSH 会话",
                            "updatedAt" to 1, "running" to false, "blank" to false)))
                        "session/page" -> {
                            harnessHistoryReads++
                            mapOf("cursor" to 1, "records" to listOf(mapOf("type" to "event", "event" to mapOf(
                                "seq" to 1, "type" to "assistant/message", "data" to mapOf("message" to mapOf(
                                    "id" to "historical-reply", "content" to listOf(mapOf("type" to "text", "text" to "DSH已保存的回复"))))))), "hasMore" to false)
                        }
                        "session/models" -> mapOf("groups" to emptyList<Any>())
                        "commands/list" -> {
                            val payload = JsonFields.obj(JsonFields.field(params, "payload"))
                            val args = JsonFields.obj(JsonFields.field(payload, "args"))
                            assertEquals("harness-session", JsonFields.string(args, "agentId"))
                            listOf(mapOf("name" to commandName, "description" to "宿主命令", "input" to mapOf("hint" to "<文件>")))
                        }
                        else -> error("本测试不应调用其他 Harness 接口")
                    }
                    return value(mapOf("ok" to true, "value" to result))
                }
                override suspend fun close() { peers.remove(binding.connectionId) }
            }
        }
        override fun authenticatedPeer(connectionId: String) = peers[connectionId]
            ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
        override suspend fun logout() { account.value = null }
        private fun value(input: Any?): JsonValue = Json.of(input)
    }
}
