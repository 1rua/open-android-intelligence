package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.data.remote.RemoteModelSelection
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
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
    private lateinit var phoneHandler: RemoteRpcHostHandler
    private lateinit var grants: PairingGrantStateHolder
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 17 }, "AES")
        override fun delete() = Unit
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val audit = AndroidAuditStore(InMemoryAuditSink())
        grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val trust = DeveloperTrustMode()
        val host = thirdPartyOnlyHost(context, grants, trust, NativePluginLoader(trust), "remote-tests")
        host.attach(PluginKernel(HostEnvelope(host.hostCapabilities), PhoneLimits(host.phoneCapabilities),
            host.runtimes, audit, trust, host.nativeLoader, host.selector, grants::currentKernelGrant,
            mediate = host::mediate, authorizeInvocation = host::authorizeKernelInvocation))
        runtime = RemoteRuntime(context, scope, host, grants, { _, _, _ -> false }, { handler -> phoneHandler = handler; connection }, key)
    }
    @After fun close() = runBlocking {
        runtime.logout().join()
        root.cancelAndJoin()
        Dispatchers.resetMain()
    }
    private class PhoneWire : RemoteMessageTransport {
        var receive: ((ByteArray) -> Unit)? = null
        var terminal: ((String) -> Unit)? = null
        val writes = mutableListOf<String>()
        override suspend fun send(bytes: ByteArray) { writes += bytes.decodeToString() }
        override fun onMessage(handler: (ByteArray) -> Unit) = AutoCloseable { receive = null }.also { receive = handler }
        override fun onClose(handler: (String) -> Unit) = AutoCloseable { terminal = null }.also { terminal = handler }
        override suspend fun close() { terminal?.invoke("TRANSPORT_CLOSED") }
        fun openStream(id: String) {
            receive!!(Json.canonical(Json.of(mapOf("v" to 1, "id" to id, "timestamp" to 1, "type" to "rpc.request",
                "payload" to mapOf("method" to "harness.remote.stream.open", "params" to mapOf("streamId" to id,
                    "endpoint" to "session/control", "payload" to emptyMap<String, Any>()))))).encodeToByteArray())
        }
    }

    @Test fun `真实手机连接关闭释放授权登记观察任务且旧代际不能清新登记`() = runBlocking {
        connection.restore()
        val baselineJobs = root.children.count()
        var previous: RemoteRpcConnectionScope? = null
        val principal = com.openandroidintelligence.control.remote.RemoteControlPrincipal(
            com.openandroidintelligence.control.remote.ControlOrigin.fromCanonical("https://remote.example"),
            "account", "controller", "phone", "sha256:" + "2".repeat(64), "sha256:" + "1".repeat(64))
        val grantId = RemoteCapabilityBinding(com.openandroidintelligence.control.remote.ControlAccessBinding(principal, 1, "phone-connection", 1)) { true }.id
        repeat(12) { index ->
            val current = RemoteRpcConnectionScope("https://remote.example", "account", "phone", "controller", "phone-connection", index + 1L)
            connection.acceptPhone(current)
            phoneHandler.invoke(current, "harness.transport.describe", Json.of(emptyMap<String, Any>()) as JsonValue.JObject)
            val wire = PhoneWire()
            val session = RemoteRpcSession(current, emptySet(), wire, scope, phoneHandler) { connection.releasePhone(current) }
            wire.openStream("phone-observer-$index")
            eventually { wire.writes.any { it.contains("harness.remote.frame") } }
            assertNotNull(grants.currentKernelGrant(grantId))
            previous?.let(phoneHandler::connectionClosed)
            assertNotNull("迟到旧代际关闭不能撤销新连接登记", grants.currentKernelGrant(grantId))
            if (index % 2 == 0) wire.terminal!!("TRANSPORT_CLOSED") else session.close()
            eventually { root.children.count() == baselineJobs }
            connection.peerReads = 0
            assertNull(grants.currentKernelGrant(grantId))
            assertEquals("旧授权投影必须已摘除，查询不能再调用旧身份检查", 0, connection.peerReads)
            assertNull(wire.receive); assertNull(wire.terminal)
            session.close(); previous = current
        }
    }

    @Test fun `退出持久化失败保持退出未完成状态且仍退休工作区后可重试`() = runBlocking {
        ready("one")
        val account = runtime.state.value.account
        connection.logoutFailure = true
        runtime.logout().join()
        assertEquals(account, runtime.state.value.account)
        assertEquals("REMOTE_LOGOUT_INCOMPLETE", runtime.state.value.errorCode)
        assertNull(runtime.controller.value)
        assertTrue(connection.channels.all { it.closed })
        connection.logoutFailure = false
        runtime.logout().join()
        assertNull(runtime.state.value.account)
        assertNull(runtime.state.value.errorCode)
    }

    private suspend fun ready(workspace: String) {
        if (runtime.state.value.account == null) runtime.restoreSessionIfAvailable().join()
        runtime.select(endpoint(workspace)).join()
        val rpc = connection.channels.last()
        val peer = connection.authenticatedPeer(rpc.scope.connectionId)
        assertEquals("主动聊天连接保留完整连接作用域", rpc.scope, peer.scope)
        assertEquals("主动聊天连接的角色是本机客户端", RemoteRole.CLIENT, peer.role)
        assertSame("同一连接的认证身份保持稳定", peer, connection.authenticatedPeer(rpc.scope.connectionId))
        val fingerprint = Regex("sha256:[a-f0-9]{64}")
        assertTrue("本机和宿主指纹均使用真实传输格式", fingerprint.matches(peer.localFingerprint) && fingerprint.matches(peer.peerFingerprint))
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

    @Test fun `重新发现宿主后离线恢复不能覆盖完整工作区目录`() = runBlocking {
        ready("one")
        runtime.discover(connection.listHosts().single()).join()

        assertEquals("离线恢复仍保留用户上次选择", "one", runtime.state.value.selected!!.workspaceId)
        assertEquals("设置页必须能选择宿主实际发现的所有工作区", setOf("one", "two"),
            runtime.state.value.endpoints.map { it.endpoint.key.workspaceId }.toSet())
        assertTrue("刚发现的工作区事实不能被替换成未知状态",
            runtime.state.value.endpoints.all { it.endpoint.availability == EndpointAvailability.AVAILABLE })
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
        runtime.saveLoginOrigin("https://remote.example")
        val local = runtime.controller.value!!.localConversationScope
        val original = com.openandroidintelligence.conversation.ports.ConversationOrganization(
            folders = listOf(com.openandroidintelligence.conversation.ports.ConversationFolder("folder", "备份文件夹")))
        runtime.updateLocalOrganization(local, original)
        val audit = AndroidAuditStore(InMemoryAuditSink())
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), audit)
        val backup = com.openandroidintelligence.mobile.settings.backup.SettingsBackupCoordinator(
            context, AppearancePreferences(context), LocalProfileStore(context), grants, null,
            runtime.replyNotifications, null, null,
            com.openandroidintelligence.mobile.updates.AppUpdatePreferences(context, com.openandroidintelligence.mobile.updates.AppUpdateChannel.PREVIEW),
            com.openandroidintelligence.notification.control.NotificationControlRegistry.policyPort(), keyProvider = key, remoteRuntime = runtime)
        val exported = backup.export()
        runtime.updateLocalOrganization(local, com.openandroidintelligence.conversation.ports.ConversationOrganization())
        assertTrue(backup.import(exported.json).isSuccess)
        assertEquals(original.folders, runtime.readLocalOrganization(local).folders)
        assertEquals("https://remote.example", runtime.loginOrigin())
    }

    @Test fun `未登录导入地址后导出保持同一登录预填`() {
        val backup = remoteBackup()
        backup.import(connectionBackup("https://prefill-b.example")).getOrThrow()
        assertNull(runtime.state.value.account)
        assertEquals("https://prefill-b.example", runtime.loginOrigin())
        assertEquals("https://prefill-b.example", exportedOrigin(backup.export().json))
    }

    @Test fun `已登录A导入B只修改预填且导出B`() = runBlocking {
        ready("one")
        val active = runtime.state.value.account
        val backup = remoteBackup()
        backup.import(connectionBackup("https://prefill-b.example")).getOrThrow()
        assertEquals("https://prefill-b.example", exportedOrigin(backup.export().json))
        assertEquals(active, runtime.state.value.account)
        assertEquals("https://remote.example", runtime.state.value.account?.origin)
    }

    @Test fun `导入后续失败回滚原登录默认地址`() {
        runtime.saveLoginOrigin("https://original.example")
        val rejecting = object : com.openandroidintelligence.notification.control.NotificationPolicyPort {
            override fun snapshot() = com.openandroidintelligence.notification.control.NotificationPolicySnapshot(
                installed = true, granted = true, packageIds = emptyList(),
                fieldAccess = com.openandroidintelligence.core.model.NotificationFieldAccess.METADATA,
                mode = com.openandroidintelligence.core.model.NotificationDeliveryMode.ON_DEMAND, revision = 1uL)
            override fun observe() = flowOf(snapshot())
            override fun update(request: com.openandroidintelligence.notification.control.NotificationPolicyUpdate) =
                com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome.Rejected("TEST_REJECT")
        }
        val backup = remoteBackup(rejecting)
        val text = backupDocument(mapOf(
            "connection" to mapOf("gatewayUrl" to "https://prefill-b.example"),
            "notificationCollection" to mapOf("packageIds" to listOf("com.example.chat"), "fieldAccess" to "METADATA", "mode" to "ON_DEMAND")))
        val failure = backup.import(text).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains("TEST_REJECT"))
        assertEquals("https://original.example", runtime.loginOrigin())
        assertEquals("https://original.example", exportedOrigin(backup.export().json))
    }

    @Test fun `运行回复离开前台仍保留服务且完成后停止`() = runBlocking {
        ready("one")
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runtime.onAppForegrounded()
        postReply("running", false)
        eventually { AgentSessionRecoveryState(context).hasPendingWork }
        runtime.onAppBackgrounded()
        assertTrue(AgentSessionRecoveryState(context).hasPendingWork)
        assertTrue(runtime.nativeConversationNotifications.sessions.value.single().isRunning)
        postReply("completed", true)
        eventually { !AgentSessionRecoveryState(context).hasPendingWork }
        assertFalse(runtime.nativeConversationNotifications.sessions.value.single().isRunning)
    }

    @Test fun `退出关闭主助手流通知与服务且旧回调不能重新启动`() = runBlocking {
        ready("one")
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runtime.onAppForegrounded()
        val main = runtime.controller.value!!
        val assistant = runtime.createAssistantSessionController("logout-assistant")!!
        postReply("running", false)
        eventually { AgentSessionRecoveryState(context).hasPendingWork }
        val local = main.localConversationScope
        runtime.logout().join()
        assertNull(runtime.controller.value)
        assertNull(runtime.state.value.account)
        assertFalse(main.state.value.isOnline)
        assertFalse(assistant.state.value.isOnline)
        assertTrue(connection.channels.all { it.closed })
        assertTrue(runtime.nativeConversationNotifications.sessions.value.isEmpty())
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
        runtime.nativeConversationNotifications.accept(replyFrame(local, "late", false))
        runtime.onAppBackgrounded()
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
    }

    @Test fun `切换工作区停止旧服务且后台旧端点回调不能重新启动`() = runBlocking {
        ready("one")
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runtime.onAppForegrounded()
        val old = runtime.controller.value!!.localConversationScope
        postReply("running", false)
        eventually { AgentSessionRecoveryState(context).hasPendingWork }
        runtime.onAppBackgrounded()
        ready("two")
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
        runtime.nativeConversationNotifications.accept(replyFrame(old, "late", false))
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
    }

    @Test fun `后台新工作和通知权限拒绝都不能非法启动前台服务`() = runBlocking {
        ready("one")
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runtime.onAppForegrounded()
        postReply("denied", false)
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
        runtime.onAppBackgrounded()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runtime.replyNotifications.refreshPermission()
        postReply("background", false)
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)
    }

    private fun postReply(eventId: String, complete: Boolean) {
        val local = runtime.controller.value!!.localConversationScope
        runtime.nativeConversationNotifications.accept(replyFrame(local, eventId, complete))
    }
    private fun replyFrame(local: ConversationScope, eventId: String, complete: Boolean): NativeNotificationInput {
        val fact = if (complete) VerifiedConversationEvent.MessageStatus(eventId, System.currentTimeMillis(),
            com.openandroidintelligence.conversation.model.ConversationId("same.reply"), "user",
            com.openandroidintelligence.conversation.model.ClientMessageId("request"), AgentMessageStatus.COMPLETED, 2, null, "gen")
        else VerifiedConversationEvent.TimelineUpsert(eventId, System.currentTimeMillis(), 1,
            TimelineMessage("reply", "assistant", listOf(com.openandroidintelligence.conversation.model.MessagePart.Text("运行回复")),
                System.currentTimeMillis(), "STREAMING", com.openandroidintelligence.conversation.model.ConversationId("same.reply"), generationId = "gen"))
        val input = requireNotNull(com.openandroidintelligence.mobile.conversations.nativeNotificationInput(
            local.profileId, local.accountId, local.gatewayId, fact))
        assertSame("生产桥必须直接接纳并保留可信事实", fact, input.event)
        assertEquals(local.profileId, input.target.profileId)
        assertEquals(local.gatewayId, input.endpointId)
        return input
    }

    private fun remoteBackup(policy: com.openandroidintelligence.notification.control.NotificationPolicyPort =
        com.openandroidintelligence.notification.control.NotificationControlRegistry.policyPort()): com.openandroidintelligence.mobile.settings.backup.SettingsBackupCoordinator {
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore(InMemoryAuditSink()))
        return com.openandroidintelligence.mobile.settings.backup.SettingsBackupCoordinator(
            context, AppearancePreferences(context), LocalProfileStore(context), grants, null,
            runtime.replyNotifications, null, null,
            com.openandroidintelligence.mobile.updates.AppUpdatePreferences(context, com.openandroidintelligence.mobile.updates.AppUpdateChannel.PREVIEW),
            policy, keyProvider = key, remoteRuntime = runtime)
    }
    private fun connectionBackup(origin: String): String = backupDocument(mapOf("connection" to mapOf("gatewayUrl" to origin)))
    private fun backupDocument(sections: Map<String, Map<String, Any>>): String =
        com.openandroidintelligence.mobile.settings.backup.SettingsBackupCodec.encode(
            sections.mapValues { (_, fields) -> Json.of(fields) as JsonValue.JObject },
            appVersionName = "test", appVersionCode = 1, exportedAt = "2026-11-14T00:00:00Z")
    private fun exportedOrigin(text: String): String? {
        val root = JsonFields.obj(Json.parse(text)) ?: return null
        val settings = JsonFields.obj(JsonFields.field(root, "settings")) ?: return null
        val connection = JsonFields.obj(JsonFields.field(settings, "connection")) ?: return null
        return JsonFields.string(connection, "gatewayUrl")
    }

    private fun endpoint(id: String): com.openandroidintelligence.conversation.data.remote.RemoteWorkspace =
        com.openandroidintelligence.conversation.data.remote.RemoteWorkspace(AgentEndpoint(EndpointKey(
            RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"), "account", "host", BackendId("harness"), id), id), "/$id")

    private inner class Account : RemoteAccountConnection {
        override val account = MutableStateFlow<RemoteActiveAccount?>(null)
        override val state = MutableStateFlow(RemoteAccountConnectionState.READY)
        override val hostState = MutableStateFlow(RemoteHostConnectionState.ONLINE)
        val channels = mutableListOf<Rpc>()
        private val peers = mutableMapOf<String, AuthenticatedRemotePeer>()
        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult = error("本测试只恢复已认证账号")
        override suspend fun restore() = RemoteActiveAccount("https://remote.example", "account", 1).also { account.value = it }
        override suspend fun listHosts() = listOf(RemoteHostDevice("host", "电脑", "linux", "membership", true))
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel = Rpc(channels.size + 1).also {
            channels += it
            // 主动聊天使用本机客户端角色；夹具仍保留完整认证绑定和正式指纹格式。
            peers[it.scope.connectionId] = AuthenticatedRemotePeer(
                it.scope, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), RemoteRole.CLIENT)
        }
        var peerReads = 0
        override fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer {
            peerReads++
            return peers[connectionId] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
        }
        fun acceptPhone(scope: RemoteRpcConnectionScope) {
            peers[scope.connectionId] = AuthenticatedRemotePeer(scope, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), RemoteRole.HOST)
        }
        fun releasePhone(scope: RemoteRpcConnectionScope) {
            peers[scope.connectionId]?.takeIf { it.scope == scope }?.let { peers.remove(scope.connectionId, it) }
        }
        fun releasePeer(connectionId: String) { peers.remove(connectionId) }
        var logoutFailure = false
        override suspend fun logout() {
            account.value = null
            if (logoutFailure) throw RemoteRpcException("REMOTE_LOGOUT_INCOMPLETE")
        }
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
        override suspend fun close() {
            closed = true
            connection.releasePeer(scope.connectionId)
        }
    }
    private fun catalog(name: String): JsonValue = Json.of(mapOf("groups" to listOf(mapOf("id" to "provider", "models" to listOf(mapOf("id" to "model", "name" to name)))),
        "current" to mapOf("provider" to "provider", "model" to "model")))
}
