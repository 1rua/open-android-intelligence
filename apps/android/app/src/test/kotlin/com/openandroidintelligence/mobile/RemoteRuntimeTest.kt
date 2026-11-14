package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.data.remote.RemoteModelSelection
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.network.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.assistant.AssistantHandoffResult
import com.openandroidintelligence.mobile.plugins.thirdPartyOnlyHost
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 经过实际 RemoteRuntime、Workbench 与加密镜像的回归；网络只替换认证后的 RPC 边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.Unconfined)
    private val connection = Account()
    private lateinit var runtime: RemoteRuntime
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 17 }, "AES")
        override fun delete() = Unit
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "remote-tests")
        runtime = RemoteRuntime(context, scope, host, grants, { _, _, _ -> false }, { connection }, key)
    }
    @After fun close() = runBlocking {
        runtime.logout().join()
        root.cancelAndJoin()
        Dispatchers.resetMain()
    }
    private suspend fun ready(workspace: String) {
        if (runtime.state.value.account == null) runtime.restoreSessionIfAvailable().join()
        runtime.select(endpoint(workspace)).join()
        eventually { runtime.controller.value?.state?.value?.activeThreadId == RemoteLocalIds.encode("same:原生/session") }
    }
    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }

    @Test fun `同名原生会话切工作区保留各自草稿并关闭旧流`() = runBlocking {
        ready("one")
        val first = runtime.controller.value!!
        first.editDraft("第一个工作区草稿")
        ready("two")
        assertFalse(first.state.value.isOnline)
        assertTrue(connection.channels.first().closed)
        assertEquals("", runtime.controller.value!!.state.value.draft)
        runtime.controller.value!!.editDraft("第二个工作区草稿")
        ready("one")
        assertEquals("第一个工作区草稿", runtime.controller.value!!.state.value.draft)
        assertEquals("one", runtime.state.value.selected!!.workspaceId)
    }

    @Test fun `旧工作区晚到模型目录不能覆盖当前模型`() = runBlocking {
        ready("one")
        val delayed = CompletableDeferred<JsonValue>()
        connection.channels.last().models = { delayed.await() }
        val oldRead = runtime.refreshModels()
        eventually { connection.channels.first().modelReads > 0 }
        ready("two")
        runtime.refreshModels().join()
        delayed.complete(catalog("旧模型"))
        oldRead.join()
        assertEquals("宿主模型", runtime.state.value.models!!.models.single().displayName)
        assertEquals("two", runtime.state.value.selected!!.workspaceId)
    }

    @Test fun `助手草稿独立且交接后主窗口接收原草稿`() = runBlocking {
        ready("one")
        val main = runtime.controller.value!!
        main.editDraft("主窗口已有草稿")
        val assistant = runtime.createAssistantSessionController("assistant-token")!!
        assistant.editDraft("助手草稿")
        assertEquals("主窗口已有草稿", main.state.value.draft)
        assertTrue(runtime.prepareAssistantHandoff("assistant-token") is AssistantHandoffResult.Accepted)
        assertEquals("助手草稿", main.state.value.draft)
        assertTrue(runtime.consumeAssistantHandoff("assistant-token"))
        runtime.releaseAssistantSession("assistant-token")
        assertEquals("助手草稿", main.state.value.draft)
    }

    @Test fun `通知点击切回准确端点并保留同名会话隔离`() = runBlocking {
        ready("one")
        val firstScope = runtime.controller.value!!.localConversationScope
        val target = ReplyNotificationTarget(firstScope.profileId, firstScope.accountId,
            RemoteLocalIds.encode("same:原生/session"), firstScope.gatewayId)
        ready("two")
        assertTrue(runtime.openReplyNotification(target))
        assertEquals("one", runtime.state.value.selected!!.workspaceId)
        eventually { runtime.controller.value!!.state.value.activeThreadId == target.conversationId }
        assertEquals(firstScope, runtime.controller.value!!.localConversationScope)
    }

    @Test fun `同名会话的本机文件夹按工作区隔离并重建后恢复`() = runBlocking {
        ready("one")
        val first = runtime.controller.value!!
        first.createFolder("工作区一")
        eventually { first.state.value.organization.folders.any { it.name == "工作区一" } }
        val one = first.localConversationScope
        val saved = runtime.readLocalOrganization(one)
        ready("two")
        assertTrue(runtime.controller.value!!.state.value.organization.folders.isEmpty())
        runtime.controller.value!!.createFolder("工作区二")
        eventually { runtime.controller.value!!.state.value.organization.folders.any { it.name == "工作区二" } }
        ready("one")
        eventually { runtime.controller.value!!.state.value.organization.folders.any { it.name == "工作区一" } }
        assertEquals(saved, com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(context, one, key).read(one.gatewayId))
    }

    @Test fun `本机组织功能不冒充远端改名归档和删除能力`() = runBlocking {
        ready("one")
        val state = runtime.controller.value!!.state.value
        assertTrue(state.conversationManagementSupported)
        assertTrue(state.organizationStoredLocally)
        assertTrue(state.conversationRenameSupported)
        assertFalse(state.conversationArchiveSupported)
        assertFalse(state.conversationDeleteSupported)
        assertFalse(state.conversationUnarchiveSupported)
    }

    @Test fun `未选择Remote端点时不枚举旧Core组织分区`() = runBlocking {
        context.getSharedPreferences("open_android_intelligence_runtime", 0).edit().putString("installation_id", "old-install").commit()
        val old = com.openandroidintelligence.conversation.ports.ConversationScope("core-profile", "https://core.example", "account", "old-install")
        val store = com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore(context, old, key)
        store.update("old-agent", 0, com.openandroidintelligence.conversation.ports.ConversationOrganization(
            folders = listOf(com.openandroidintelligence.conversation.ports.ConversationFolder("old-folder", "旧服务文件夹"))))
        assertTrue(runtime.localOrganizationScopes().isEmpty())
    }

    @Test fun `Remote设置备份导出再恢复保持端点组织与登录地址`() = runBlocking {
        ready("one")
        val local = runtime.controller.value!!.localConversationScope
        val original = com.openandroidintelligence.conversation.ports.ConversationOrganization(
            folders = listOf(com.openandroidintelligence.conversation.ports.ConversationFolder("folder", "备份文件夹")))
        runtime.updateLocalOrganization(local, original)
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val backup = com.openandroidintelligence.mobile.settings.backup.SettingsBackupCoordinator(
            context, AppearancePreferences(context), AndroidAccountProfileStore(context), grants, null,
            runtime.replyNotifications, null, null,
            com.openandroidintelligence.mobile.updates.AppUpdatePreferences(context, com.openandroidintelligence.mobile.updates.AppUpdateChannel.PREVIEW),
            com.openandroidintelligence.notification.control.NotificationControlRegistry.policyPort(), keyProvider = key, remoteRuntime = runtime)
        val exported = backup.export()
        runtime.updateLocalOrganization(local, com.openandroidintelligence.conversation.ports.ConversationOrganization())
        assertTrue(backup.import(exported.json).isSuccess)
        assertEquals(original.folders, runtime.readLocalOrganization(local).folders)
        assertEquals("https://remote.example", runtime.loginOrigin())
    }

    private fun endpoint(id: String): com.openandroidintelligence.conversation.data.remote.RemoteWorkspace =
        com.openandroidintelligence.conversation.data.remote.RemoteWorkspace(AgentEndpoint(EndpointKey(
            RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"), "account", "host", BackendId("harness"), id), id), "/$id")

    private inner class Account : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        val channels = mutableListOf<Rpc>()
        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult = error("本测试只恢复已认证账号")
        override suspend fun restore() = RemoteActiveAccount("https://remote.example", "account", 1).also { account.value = it }
        override suspend fun listHosts() = listOf(RemoteHostDevice("host", "电脑", "linux", "membership", true))
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel = Rpc(channels.size + 1).also { channels += it }
        override fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer = error("本测试不允许受控入站")
        override suspend fun logout() { account.value = null }
    }
    private inner class Rpc(generation: Int) : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "connection-$generation", generation.toLong())
        override val capabilities = setOf("harness.remote.v3")
        override val events = MutableSharedFlow<RemoteRpcEvent>()
        var closed = false
        var modelReads = 0
        var models: suspend () -> JsonValue = { catalog("宿主模型") }
        override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
            if (closed) throw RemoteRpcException("TRANSPORT_CLOSED", RemoteDeliveryOutcome.NOT_SENT)
            if (method == "harness.remote.stream.open") {
                if (JsonFields.string(params, "endpoint") == "workspace/follow") events.emit(RemoteRpcEvent("harness.remote.frame", Json.of(mapOf(
                    "streamId" to JsonFields.string(params, "streamId"), "value" to mapOf("type" to "baseline", "value" to mapOf("items" to listOf(
                        mapOf("workspaceId" to "one", "path" to "/one", "sessionIds" to listOf("same:原生/session")),
                        mapOf("workspaceId" to "two", "path" to "/two", "sessionIds" to listOf("same:原生/session")))))))))
                return Json.of(emptyMap<String, Any>())
            }
            if (method == "harness.remote.stream.close") return Json.of(emptyMap<String, Any>())
            val value = when (JsonFields.string(params, "endpoint")) {
                "session/list" -> Json.of(mapOf("items" to listOf(mapOf("sessionId" to "same:原生/session", "title" to "会话", "updatedAt" to 1))))
                "session/page" -> Json.of(mapOf("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false))
                "session/models" -> { modelReads++; models() }
                "commands/list" -> Json.of(mapOf("items" to emptyList<Any>()))
                else -> Json.of(emptyMap<String, Any>())
            }
            return Json.of(mapOf("ok" to true, "value" to value))
        }
        override suspend fun close() { closed = true }
    }
    private fun catalog(name: String): JsonValue = Json.of(mapOf("groups" to listOf(mapOf("id" to "provider", "models" to listOf(mapOf("id" to "model", "name" to name)))),
        "current" to mapOf("provider" to "provider", "model" to "model")))
}
