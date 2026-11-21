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
    private lateinit var runtimeFactory: (suspend (Long) -> Unit) -> RemoteRuntime
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
        runtimeFactory = { delayFn ->
            RemoteRuntime(context, scope, host, grants, { _, _, _ -> false }, { handler -> phoneHandler = handler; connection }, key, delayFn = delayFn)
        }
        runtime = runtimeFactory { delay(it) }
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
        assertFalse("同 Agent 切工作区必须复用底层通道而不关闭", connection.channels.first().closed)
        assertEquals("同 Agent 切工作区不应建立新连接", 1, connection.channels.size)
        assertEquals("", runtime.controller.value!!.state.value.draft)
        runtime.controller.value!!.editDraft("第二个工作区草稿")
        ready("one")
        assertEquals("第一个工作区草稿", runtime.controller.value!!.state.value.draft)
        assertEquals("one", runtime.state.value.selected!!.workspaceId)
    }

    @Test fun `重新发现宿主后离线恢复不能覆盖完整工作区目录`() = runBlocking {
        ready("one")
        val activeController = runtime.controller.value!!
        val activeChannel = connection.channels.single()
        activeController.editDraft("发现目录时保留草稿")
        runtime.discover(connection.listHosts().single()).join()

        assertSame("发现当前宿主不能替换正在使用的工作台", activeController, runtime.controller.value)
        assertFalse("发现当前宿主不能关闭活动通道", activeChannel.closed)
        assertEquals("同宿主发现应复用已认证通道", 1, connection.channels.size)
        assertTrue(runtime.controller.value!!.state.value.isOnline)
        assertEquals("发现目录时保留草稿", runtime.controller.value!!.state.value.draft)
        assertEquals("离线恢复仍保留用户上次选择", "one", runtime.state.value.selected!!.workspaceId)
        assertEquals("设置页必须能选择宿主实际发现的所有工作区", setOf("one", "two"),
            runtime.state.value.endpoints.map { it.endpoint.key.workspaceId }.toSet())
        assertTrue("刚发现的工作区事实不能被替换成未知状态",
            runtime.state.value.endpoints.all { it.endpoint.availability == EndpointAvailability.AVAILABLE })
    }

    @Test fun `发现其他宿主后恢复原工作区在线与草稿`() = runBlocking {
        ready("one")
        runtime.controller.value!!.editDraft("原工作区草稿")
        val selected = runtime.state.value.selected

        runtime.discover(RemoteHostDevice("other-host", "另一台电脑", "linux", "membership", true)).join()

        eventually { runtime.controller.value?.state?.value?.isOnline == true }
        assertEquals(selected, runtime.state.value.selected)
        assertEquals("原工作区草稿", runtime.controller.value!!.state.value.draft)
        assertEquals(listOf("host", "other-host", "host"), connection.channels.map { it.scope.hostDeviceId })
        assertTrue("临时发现连接必须关闭", connection.channels[1].closed)
        assertFalse("恢复后的工作区连接必须保留", connection.channels.last().closed)
        assertTrue(runtime.state.value.endpoints.any { it.endpoint.key.hostId == "other-host" })
    }

    @Test fun `同宿主通道已关闭时重新发现会重连并恢复在线工作区`() = runBlocking {
        ready("one")
        runtime.controller.value!!.editDraft("断线后保留草稿")
        connection.channels.single().close()

        runtime.discover(connection.listHosts().single()).join()

        assertTrue("重新发现必须替换已关闭通道", connection.channels.size > 1)
        eventually { runtime.controller.value?.state?.value?.isOnline == true }
        assertEquals("断线后保留草稿", runtime.controller.value!!.state.value.draft)
        assertEquals(setOf("one", "two"), runtime.state.value.endpoints.map { it.endpoint.key.workspaceId }.toSet())
        assertNull(runtime.state.value.errorCode)
        assertFalse(connection.channels.last().closed)
    }

    @Test fun `发现其他宿主失败仍恢复原工作区并保留错误`() = runBlocking {
        ready("one")
        val selected = runtime.state.value.selected
        connection.failedHost = "other-host"

        runtime.discover(RemoteHostDevice("other-host", "另一台电脑", "linux", "membership", true)).join()

        eventually { runtime.controller.value?.state?.value?.isOnline == true }
        assertEquals(selected, runtime.state.value.selected)
        assertEquals("HOST_UNAVAILABLE", runtime.state.value.errorCode)
        assertFalse(runtime.state.value.busy)
        assertFalse(connection.channels.last().closed)
    }

    @Test fun `旧工作区晚到模型目录不能覆盖当前模型`() = runBlocking {
        ready("one")
        val delayed = CompletableDeferred<JsonValue>()
        val targetChannel = connection.channels.last()
        val readsBeforeOld = targetChannel.modelReads
        var interceptFirst = true
        targetChannel.models = {
            if (interceptFirst) {
                interceptFirst = false
                delayed.await()
            } else {
                catalog("宿主模型")
            }
        }
        val oldRead = runtime.refreshModels()
        eventually { targetChannel.modelReads > readsBeforeOld }
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

    @Test fun `跨generation别名完成及断网快照对账都能退出LiveUpdate并停止后台保活`() = runBlocking {
        ready("one")
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val local = runtime.controller.value!!.localConversationScope
        val endpoint = local.gatewayId
        val convId = com.openandroidintelligence.conversation.model.ConversationId("same.runtime.conv")
        val clientId = com.openandroidintelligence.conversation.model.ClientMessageId("client-runtime")
        var clock = System.currentTimeMillis()
        fun inputOf(fact: VerifiedConversationEvent, resolvedGen: String? = null): NativeNotificationInput =
            requireNotNull(com.openandroidintelligence.mobile.conversations.nativeNotificationInput(
                local.profileId, local.accountId, endpoint, fact)).copy(resolvedGenerationId = resolvedGen)

        runtime.onAppForegrounded()
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.MessageStatus(
            "ev-delivered", ++clock, convId, "u-runtime", clientId, AgentMessageStatus.DELIVERED, 1, null, "req-runtime",
        )))
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.TimelineUpsert(
            "ev-delta", ++clock, 1, TimelineMessage("reply-runtime", "assistant",
                listOf(com.openandroidintelligence.conversation.model.MessagePart.Text("跨别名流式内容")),
                clock, "STREAMING", convId, generationId = "turn-runtime"),
        ), resolvedGen = "req-runtime"))
        eventually { AgentSessionRecoveryState(context).hasPendingWork }
        runtime.onAppBackgrounded()
        assertTrue(AgentSessionRecoveryState(context).hasPendingWork)

        // completed + status(COMPLETED) 使用 turn-runtime 收尾，必须清除保活并退出进行态
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.TimelineUpsert(
            "ev-completed", ++clock, 2, TimelineMessage("reply-runtime", "assistant",
                listOf(com.openandroidintelligence.conversation.model.MessagePart.Text("跨别名最终答案")),
                clock, "COMPLETED", convId, generationId = "turn-runtime"),
        ), resolvedGen = "req-runtime"))
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.MessageStatus(
            "ev-completed-status", ++clock, convId, "u-runtime-srv", clientId, AgentMessageStatus.COMPLETED, 3, null, "turn-runtime",
        )))
        eventually { !AgentSessionRecoveryState(context).hasPendingWork }
        assertFalse(runtime.nativeConversationNotifications.sessions.value.single().isRunning)
        assertEquals(NativeConversationStatus.COMPLETED, runtime.nativeConversationNotifications.sessions.value.single().status)

        // 第 2 轮断网后挂起在 DISCONNECTED：保活需立即停止且切回网络时自动通过第 1 页快照对账收敛至 ENDED
        val liveConvId = com.openandroidintelligence.conversation.model.ConversationId(
            requireNotNull(runtime.controller.value!!.state.value.activeThreadId))
        runtime.onAppForegrounded()
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.MessageStatus(
            "ev-r2", ++clock, liveConvId, "u-r2", com.openandroidintelligence.conversation.model.ClientMessageId("client-r2"),
            AgentMessageStatus.QUEUED, 10, null, "gen-r2",
        )))
        eventually { AgentSessionRecoveryState(context).hasPendingWork }
        runtime.onNetworkLost()
        val discSession = runtime.nativeConversationNotifications.sessions.value.first { it.target.conversationId == liveConvId.value }
        assertEquals(NativeConversationStatus.DISCONNECTED, discSession.status)
        eventually { !AgentSessionRecoveryState(context).hasPendingWork }

        // 网络恢复后重新连接并通过包含 turn/end 的真实 Harness 分页快照将断联卡住会话收敛至 ENDED
        connection.defaultSessionPage = {
            mapOf(
                "cursor" to 2,
                "records" to listOf(
                    mapOf("type" to "event", "event" to mapOf("seq" to 1, "type" to "turn/start", "data" to mapOf("turn" to 1, "turnId" to "gen-r2"))),
                    mapOf("type" to "event", "event" to mapOf("seq" to 2, "type" to "turn/end", "data" to mapOf("turn" to 1, "turnId" to "gen-r2", "reason" to mapOf("kind" to "done")))),
                ),
                "hasMore" to false,
            )
        }
        runtime.onNetworkAvailable()
        eventually {
            runtime.nativeConversationNotifications.sessions.value
                .firstOrNull { it.target.conversationId == liveConvId.value }?.status == NativeConversationStatus.ENDED
        }
        assertFalse(AgentSessionRecoveryState(context).hasPendingWork)

        // UI 控制器直接发起任务后（controller.hasActiveWork == true）断网，仍必须立刻停止前台保活且离线期暂停轮询对账
        val controller = runtime.controller.value!!
        controller.submitComposerDraft("测试UI断网停保活", controller.state.value.draftEpoch,
            com.openandroidintelligence.conversation.ports.MessageDeliveryMode.NORMAL)
        eventually { controller.hasActiveWork.value && AgentSessionRecoveryState(context).hasPendingWork }
        val activeChannel = connection.channels.last()
        runtime.onNetworkLost()
        eventually { !AgentSessionRecoveryState(context).hasPendingWork }
        val offlinePageReads = activeChannel.pageReads
        runtime.onAppForegrounded()
        delay(50)
        assertEquals("断网期间不得继续轮询远端历史接口", offlinePageReads, activeChannel.pageReads)
    }

    @Test fun `Harness省略activeTurnId且存在大量跨端点待对账会话时仍立即核对选中端点且不误杀进行中通知`() = runBlocking {
        runtime.restoreSessionIfAvailable().join()
        runtime.discover(accountHost()).join()
        val first = runtime.state.value.endpoints.first { it.endpoint.key.workspaceId == "one" }
        runtime.select(first).join()
        eventually { runtime.controller.value?.state?.value?.activeThreadId != null }
        val convId = com.openandroidintelligence.conversation.model.ConversationId(
            requireNotNull(runtime.controller.value!!.state.value.activeThreadId))
        val local = runtime.controller.value!!.localConversationScope
        var clock = System.currentTimeMillis()
        fun inputOf(fact: VerifiedConversationEvent, agentOverride: String = local.gatewayId): NativeNotificationInput =
            requireNotNull(com.openandroidintelligence.mobile.conversations.nativeNotificationInput(
                local.profileId, local.accountId, agentOverride, fact))

        // 先在其他端点制造 10 个待对账会话，验证不会挤占选中端点的 8 条对账配额
        for (i in 1..10) {
            runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.MessageStatus(
                "other-ev-$i", ++clock, com.openandroidintelligence.conversation.model.ConversationId("other-conv-$i"),
                "u-$i", com.openandroidintelligence.conversation.model.ClientMessageId("c-$i"),
                AgentMessageStatus.DELIVERED, 1, null, "gen-other-$i",
            ), agentOverride = "other-endpoint-$i"))
        }
        val selectedClientId = com.openandroidintelligence.conversation.model.ClientMessageId("cid-no-active-turn")
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.MessageStatus(
            "sel-ev-1", ++clock, convId, "u-sel-1", selectedClientId, AgentMessageStatus.DELIVERED, 1, null, "turn-omitted",
        )))

        // 模拟真实 Harness session/page：省略 activeTurnId 且暂未包含 turn/start 或 turn/end
        val activeChannel = connection.channels.last()
        val beforeReads = activeChannel.pageReads
        activeChannel.sessionPage = {
            mapOf(
                "cursor" to 5,
                "records" to listOf(mapOf("type" to "event", "event" to mapOf(
                    "seq" to 5,
                    "type" to "assistant/message",
                    "data" to mapOf("message" to mapOf(
                        "id" to "a-partial",
                        "content" to listOf(mapOf("type" to "text", "text" to "中间步骤消息")),
                    )),
                ))),
                "hasMore" to false,
            )
        }
        runtime.nativeConversationNotifications.requestReconciliation()
        runtime.onAppForegrounded()

        eventually { activeChannel.pageReads > beforeReads }
        val selectedSession = runtime.nativeConversationNotifications.sessions.value
            .first { it.target.conversationId == convId.value && it.target.agentId == local.gatewayId }
        assertTrue("缺失 activeTurnId 的 Harness 分页绝不能把进行中 Live Update 标为 ENDED", selectedSession.isRunning)
        assertEquals(NativeConversationStatus.RUNNING, selectedSession.status)

        // 随后到达的最终回复与 COMPLETED 状态仍能正常完成（未被误写入终态围栏阻断）
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.TimelineUpsert(
            "sel-ev-2", ++clock, 2, TimelineMessage("reply-omitted", "assistant",
                listOf(com.openandroidintelligence.conversation.model.MessagePart.Text("真实Harness最终答复")),
                clock, "COMPLETED", convId, generationId = "turn-omitted"),
        )))
        runtime.nativeConversationNotifications.accept(inputOf(VerifiedConversationEvent.MessageStatus(
            "sel-ev-3", ++clock, convId, "u-sel-1", selectedClientId, AgentMessageStatus.COMPLETED, 3, null, "turn-omitted",
        )))
        val finished = runtime.nativeConversationNotifications.sessions.value
            .first { it.target.conversationId == convId.value && it.target.agentId == local.gatewayId }
        assertFalse(finished.isRunning)
        assertEquals(NativeConversationStatus.COMPLETED, finished.status)
        assertEquals("真实Harness最终答复", finished.latestReply)
    }

    @Test fun `断网处理排队期间网络已恢复时必须补一次重连而不是空等宽限期`() = runBlocking {
        // 宽限期永不结束：若断网处理释放锁后不立刻补发重连，用例必然超时失败，不依赖宿主机快慢。
        runtime.logout().join()
        runtime = runtimeFactory { awaitCancellation() }
        ready("one")
        val gate = CompletableDeferred<Unit>()
        connection.restoreGate = gate
        runtime.reconnectInPlace() // 占住 selection 锁直至 gate 放行
        runtime.onNetworkLost()    // 断网清理在 selection 锁外排队
        runtime.onNetworkAvailable() // 清理尚未完成前网络已恢复
        connection.restoreGate = null
        gate.complete(Unit)
        eventually {
            connection.channels.size >= 2 && !connection.channels.last().closed &&
                runtime.controller.value?.state?.value?.isOnline == true &&
                runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                !runtime.agentConnection.value.offlineConfirmed
        }
    }

    @Test fun `断网排队期间连续闪断闪连或切工作区不会落入假在线僵尸态`() = runBlocking {
        // 场景 A：断网清理在锁外排队期间用户切换工作区，旧断网协程不得拆毁新工作区健康连接。
        ready("one")
        val gateA = CompletableDeferred<Unit>()
        connection.restoreGate = gateA
        runtime.reconnectInPlace() // 占住 selection 锁
        runtime.onNetworkLost()    // L1 排队
        val switchJob = runtime.select(endpoint("two"))
        connection.restoreGate = null
        gateA.complete(Unit)
        switchJob.join()
        eventually {
            runtime.state.value.selected?.workspaceId == "two" &&
                runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                runtime.controller.value?.state?.value?.isOnline == true &&
                !connection.channels.last().closed
        }

        // 场景 B（FIFO Mutex ABA）+ 建链中途断网不覆写 networkConnected：
        // 锁被占期间依次触发 Lost #1 -> Available #1 -> Lost #2，最终必须停在断联态而非假 CONNECTED 僵尸态。
        runtime.logout().join()
        runtime = runtimeFactory { awaitCancellation() }
        ready("one")
        val gateB = CompletableDeferred<Unit>()
        connection.restoreGate = gateB
        runtime.reconnectInPlace()   // 占住 selection 锁
        runtime.onNetworkLost()      // L1 入队（epoch 1）
        runtime.onNetworkAvailable() // 网络短暂恢复
        runtime.onNetworkLost()      // L2 入队（epoch 2，网络最终保持断开）
        connection.restoreGate = null
        gateB.complete(Unit)
        eventually {
            runtime.agentConnection.value.phase == AgentConnectionPhase.RECONNECTING &&
                runtime.controller.value?.state?.value?.isOnline == false
        }
        // 验证网络仍断开时未误触发补连，直到显式 onNetworkAvailable 才恢复 CONNECTED。
        val channelsWhileOffline = connection.channels.size
        runtime.onNetworkAvailable()
        eventually {
            connection.channels.size > channelsWhileOffline &&
                runtime.agentConnection.value.phase == AgentConnectionPhase.CONNECTED &&
                runtime.controller.value?.state?.value?.isOnline == true
        }

        // 场景 C：DISCONNECTED 态首次 select 中途断网，不能跳过断网代际或把 networkConnected 覆写回 true 导致僵尸在线态。
        runtime.logout().join()
        runtime = runtimeFactory { awaitCancellation() }
        runtime.restoreSessionIfAvailable().join()
        val gateC = CompletableDeferred<Unit>()
        connection.connectGate = gateC
        val initialSelect = runtime.select(endpoint("one"))
        runtime.onNetworkLost()
        connection.connectGate = null
        gateC.complete(Unit)
        initialSelect.join()
        eventually {
            runtime.agentConnection.value.phase == AgentConnectionPhase.RECONNECTING &&
                runtime.controller.value?.state?.value?.isOnline == false
        }
    }

    @Test fun `非空时间线对账围栏在主动对账或新事件推进后必须拒绝旧快照且不吞配额`() = runBlocking {
        ready("one")
        val convId = com.openandroidintelligence.conversation.model.ConversationId(
            requireNotNull(runtime.controller.value!!.state.value.activeThreadId))
        val local = runtime.controller.value!!.localConversationScope
        val target = ReplyNotificationTarget(local.profileId, local.accountId, convId.value, local.gatewayId)
        runtime.nativeConversationNotifications.accept(requireNotNull(
            com.openandroidintelligence.mobile.conversations.nativeNotificationInput(
                local.profileId,
                local.accountId,
                local.gatewayId,
                VerifiedConversationEvent.MessageStatus(
                    "fence-ev-1",
                    System.currentTimeMillis(),
                    convId,
                    "u-fence-1",
                    com.openandroidintelligence.conversation.model.ClientMessageId("cid-fence-1"),
                    AgentMessageStatus.DELIVERED,
                    1,
                    null,
                    "turn-fence-1",
                ),
            )
        ))
        // 先触发一次主动对账使会话处于可捕获非空围栏状态。
        runtime.nativeConversationNotifications.requestReconciliation()
        val capturedFence = requireNotNull(runtime.nativeConversationNotifications.captureTimelineReconciliationFence(target))

        // 模拟远端历史读取在途期间发生新的主动对账请求，推进 requestEpoch。
        runtime.nativeConversationNotifications.requestReconciliation()
        val staleApplied = runtime.nativeConversationNotifications.reconcileWithFence(
            capturedFence,
            currentGeneration = null,
            messages = emptyList(),
            activityKnown = true,
        )
        assertFalse("旧非空围栏在主动对账代际推进后必须被拒绝", staleApplied)
        assertTrue("拒绝旧非空围栏时绝不能吞掉后续主动对账配额", runtime.nativeConversationNotifications.hasPendingReconciliation())
        val stillRunning = runtime.nativeConversationNotifications.sessions.value.first { it.target == target }
        assertTrue("被拒绝的旧快照不得把进行中通知结束为 ENDED", stillRunning.isRunning)
        assertEquals(NativeConversationStatus.RUNNING, stillRunning.status)
    }

    @Test fun `在途旧时间线快照晚于主动对账请求返回时不得吞掉前台对账配额`() = runBlocking {
        val firstPageEntered = CompletableDeferred<Unit>()
        val releaseStalePage = CompletableDeferred<Unit>()
        val interceptedInitialLoad = java.util.concurrent.atomic.AtomicBoolean(false)
        connection.defaultSessionPage = {
            if (interceptedInitialLoad.compareAndSet(false, true)) {
                firstPageEntered.complete(Unit)
                releaseStalePage.await()
            }
            mapOf("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false)
        }
        runtime.restoreSessionIfAvailable().join()
        runtime.discover(accountHost()).join()
        val first = runtime.state.value.endpoints.first { it.endpoint.key.workspaceId == "one" }
        runtime.select(first).join()
        eventually { runtime.controller.value?.state?.value?.activeThreadId != null }
        withTimeout(5_000) { firstPageEntered.await() }

        val convId = com.openandroidintelligence.conversation.model.ConversationId(
            requireNotNull(runtime.controller.value!!.state.value.activeThreadId))
        val local = runtime.controller.value!!.localConversationScope
        val activeChannel = connection.channels.last()
        val beforeReads = activeChannel.pageReads
        runtime.nativeConversationNotifications.accept(requireNotNull(
            com.openandroidintelligence.mobile.conversations.nativeNotificationInput(
                local.profileId,
                local.accountId,
                local.gatewayId,
                VerifiedConversationEvent.MessageStatus(
                    "stale-race-ev-1",
                    System.currentTimeMillis(),
                    convId,
                    "u-stale-1",
                    com.openandroidintelligence.conversation.model.ClientMessageId("cid-stale-1"),
                    AgentMessageStatus.DELIVERED,
                    1,
                    null,
                    "turn-stale-1",
                ),
            )
        ))
        activeChannel.sessionPage = {
            mapOf(
                "cursor" to 5,
                "records" to listOf(mapOf("type" to "event", "event" to mapOf(
                    "seq" to 5,
                    "type" to "assistant/message",
                    "data" to mapOf("message" to mapOf(
                        "id" to "a-fresh",
                        "content" to listOf(mapOf("type" to "text", "text" to "前台主动对账快照")),
                    )),
                ))),
                "hasMore" to false,
            )
        }
        runtime.nativeConversationNotifications.requestReconciliation()
        // 放行在 requestReconciliation 之前已发出的旧时间线读取，并确定性等待其落地到时间线状态后再触发前台对账。
        releaseStalePage.complete(Unit)
        eventually { runtime.controller.value?.state?.value?.timeline !is com.openandroidintelligence.conversation.state.Loadable.Loading }
        assertTrue("旧在途时间线快照不得消耗后续主动对账请求", runtime.nativeConversationNotifications.hasPendingReconciliation())

        runtime.onAppForegrounded()
        eventually { activeChannel.pageReads > beforeReads }
        val selected = runtime.nativeConversationNotifications.sessions.value
            .first { it.target.conversationId == convId.value && it.target.agentId == local.gatewayId }
        assertTrue(selected.isRunning)
        assertEquals(NativeConversationStatus.RUNNING, selected.status)
    }

    private suspend fun accountHost() = connection.listHosts().single()

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
        val channels = java.util.concurrent.CopyOnWriteArrayList<Rpc>()
        @Volatile var defaultSessionPage: suspend () -> Map<String, Any?> = { mapOf("cursor" to 0, "records" to emptyList<Any>(), "hasMore" to false) }
        @Volatile var failedHost: String? = null
        @Volatile var restoreGate: CompletableDeferred<Unit>? = null
        @Volatile var connectGate: CompletableDeferred<Unit>? = null
        private val peers = java.util.concurrent.ConcurrentHashMap<String, AuthenticatedRemotePeer>()
        override suspend fun login(origin: String, email: String, password: String): RemoteLoginResult = error("本测试只恢复已认证账号")
        override suspend fun restore(): RemoteActiveAccount {
            restoreGate?.await()
            return RemoteActiveAccount("https://remote.example", "account", 1).also { account.value = it }
        }
        override suspend fun listHosts() = listOf(RemoteHostDevice("host", "电脑", "linux", "membership", true))
        override suspend fun connect(hostDeviceId: String): RemoteRpcChannel {
            connectGate?.await()
            if (hostDeviceId == failedHost) throw RemoteRpcException("HOST_UNAVAILABLE")
            return Rpc(channels.size + 1, hostDeviceId).also {
                channels += it
                // 主动聊天使用本机客户端角色；夹具仍保留完整认证绑定和正式指纹格式。
                peers[it.scope.connectionId] = AuthenticatedRemotePeer(
                    it.scope, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64), RemoteRole.CLIENT)
            }
        }
        private val peerReadCount = java.util.concurrent.atomic.AtomicInteger(0)
        var peerReads: Int
            get() = peerReadCount.get()
            set(value) = peerReadCount.set(value)
        override fun authenticatedPeer(connectionId: String): AuthenticatedRemotePeer {
            peerReadCount.incrementAndGet()
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
    private inner class Rpc(generation: Int, hostDeviceId: String) : RemoteRpcChannel {
        override val scope = RemoteRpcConnectionScope("https://remote.example", "account", hostDeviceId, "client", "connection-$generation", generation.toLong())
        override val capabilities = setOf("harness.remote.v3")
        // 与生产 RemoteRpcSession(Channel(128)) 保持一致的缓冲广播语义，避免零缓冲 SharedFlow 在并发开流/取消下死锁。
        override val events = MutableSharedFlow<RemoteRpcEvent>(extraBufferCapacity = 64)
        @Volatile var closed = false
        private val modelReadCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val pageReadCount = java.util.concurrent.atomic.AtomicInteger(0)
        val modelReads: Int get() = modelReadCount.get()
        val pageReads: Int get() = pageReadCount.get()
        @Volatile var models: suspend () -> JsonValue = { catalog("宿主模型") }
        @Volatile var sessionPage: suspend () -> Map<String, Any?> = connection.defaultSessionPage
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
                "session/page" -> { pageReadCount.incrementAndGet(); Json.of(sessionPage()) }
                "session/models" -> { modelReadCount.incrementAndGet(); models() }
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
