package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.conversation.data.remote.*
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.network.RemoteHostDevice
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountRuntime
import com.openandroidintelligence.gateway.remote.facade.PhoneRemoteRpcHostHandler
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.assistant.*
import com.openandroidintelligence.mobile.conversations.*
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.RemotePhoneControlComposition
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RemoteWorkspaceState(
    val account: RemoteActiveAccount? = null,
    val hosts: List<RemoteHostDevice> = emptyList(),
    val endpoints: List<RemoteWorkspace> = emptyList(),
    val selected: EndpointKey? = null,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val models: RemoteModelCatalog? = null,
    val modelBusy: Boolean = false,
)

/** App 的 Remote 装配面：连接、工作台和本机控制分别拥有各自生命周期。 */
class RemoteRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pluginHost: ProductionPluginHost,
    private val grants: PairingGrantStateHolder,
    private val confirmLocally: suspend (String, String, String) -> Boolean,
) {
    private val mutableState = MutableStateFlow(RemoteWorkspaceState())
    val state = mutableState.asStateFlow()
    private val mutableController = MutableStateFlow<WorkbenchController?>(null)
    val controller = mutableController.asStateFlow()
    private val selection = Mutex()
    private val preferences = context.getSharedPreferences("remote-workspace-selection-v1", Context.MODE_PRIVATE)
    private val phone = RemotePhoneControlComposition(context, scope, pluginHost, grants, confirmLocally)
    private data class Registered(val access: ControlAccessBinding, val registration: AutoCloseable)
    private val registered = ConcurrentHashMap<String, Registered>()
    private val handler = PhoneRemoteRpcHostHandler(scope, phone.router, ::access)
    val accountRuntime = RemoteAccountRuntime(context, scope, handler)
    private var channel: RemoteRpcChannel? = null
    private var repository: RemoteConversationRepository? = null
    private var ownerJob: Job? = null
    private var ownerAttachments: RemoteAttachmentCoordinator? = null
    private var ownerMirror: EncryptedConversationMirror? = null
    private var ownerMedia: EncryptedHistoryMediaCache? = null
    private var ownerConversationScope: ConversationScope? = null
    private val assistantControllers = ConcurrentHashMap<String, WorkbenchController>()
    private val assistantPresence = ConcurrentHashMap<String, Pair<String?, Boolean>>()
    private val handoffs = ConcurrentHashMap<String, AssistantHandoffTarget>()
    private val mutableAssistantVersion = MutableStateFlow(0L)
    val assistantWorkspaceVersion = mutableAssistantVersion.asStateFlow()
    val replyNotifications = ReplyNotificationSettings(context)
    private val publisher = AndroidReplyNotificationPublisher(context, replyNotifications) { target ->
        assistantControllers.any { (token, controller) -> assistantPresence.values.any { it.first == token && it.second } &&
            controller.state.value.activeThreadId == target.conversationId && target.agentId == state.value.selected?.let(::endpointId) }
    }
    internal val nativeConversationNotifications = NativeConversationNotifications(context, scope, publisher::update,
        cancel = publisher::cancel, forget = publisher::forget)
    val connectedAccountId: String? get() = state.value.account?.accountId
    val connectedProfileId: String? get() = state.value.account?.let { profileId(it.origin) }
    val connectedAgentName: String? get() = state.value.endpoints.firstOrNull { it.endpoint.key == state.value.selected }?.endpoint?.displayName

    private fun access(connection: RemoteRpcConnectionScope): ControlAccessBinding {
        registered[connection.connectionId]?.let { return it.access }
        val peer = accountRuntime.authenticatedPeer(connection.connectionId)
        check(peer.scope == connection && peer.role == RemoteRole.HOST) { "REMOTE_HOST_BINDING_MISMATCH" }
        val active = accountRuntime.account.value ?: error("ACCOUNT_AUTH_REQUIRED")
        val access = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical(connection.origin), connection.accountId,
            connection.clientDeviceId, connection.hostDeviceId, peer.peerFingerprint, peer.localFingerprint), active.epoch,
            connection.connectionId, connection.generation)
        val registration = phone.register(access) {
            accountRuntime.account.value == active && runCatching { accountRuntime.authenticatedPeer(connection.connectionId) == peer }.getOrDefault(false)
        }
        val previous = registered.putIfAbsent(connection.connectionId, Registered(access, registration))
        if (previous != null) { registration.close(); return previous.access }
        return access
    }

    fun login(origin: String, email: String, password: String) = scope.launch {
        mutableState.update { it.copy(busy = true, errorCode = null) }
        try {
            retire()
            accountRuntime.login(origin, email, password)
            mutableState.value = RemoteWorkspaceState(account = accountRuntime.account.value, busy = true)
            refreshHostsInternal()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { fail(failure) }
        finally { mutableState.update { it.copy(busy = false) } }
    }
    fun restoreSessionIfAvailable() = scope.launch {
        if (state.value.account != null || state.value.busy) return@launch
        mutableState.update { it.copy(busy = true) }
        try {
            val account = accountRuntime.restore() ?: return@launch
            mutableState.update { it.copy(account = account) }
            restoreOffline(account)
            refreshHostsInternal()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { fail(failure) }
        finally { mutableState.update { it.copy(busy = false) } }
    }
    fun refreshHosts() = scope.launch { try { refreshHostsInternal() } catch (failure: Exception) { fail(failure) } }
    private suspend fun refreshHostsInternal() { mutableState.update { it.copy(hosts = accountRuntime.listHosts(), errorCode = null) } }

    fun discover(host: RemoteHostDevice) = scope.launch {
        selection.withLock {
            mutableState.update { it.copy(busy = true, errorCode = null) }
            var discovery: RemoteRpcChannel? = null
            try {
                retire()
                discovery = accountRuntime.connect(host.deviceId)
                val carriers = carriers(discovery)
                val endpoints = carriers.flatMap { it.workspaces() }.map { workspace ->
                    workspace.copy(endpoint = workspace.endpoint.copy(displayName = "${host.name} · ${workspace.endpoint.key.backendId.value} · ${workspace.endpoint.displayName}"))
                }
                mutableState.update { old -> old.copy(endpoints = old.endpoints.filter { it.endpoint.key.hostId != host.deviceId } + endpoints) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
            finally {
                discovery?.close()
                if (controller.value == null) state.value.account?.let { restoreOffline(it) }
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
    fun select(endpoint: RemoteWorkspace) = scope.launch { selectInternal(endpoint) }
    private suspend fun selectInternal(endpoint: RemoteWorkspace) = selection.withLock {
        mutableState.update { it.copy(busy = true, errorCode = null, models = null) }
        try {
            retire()
            val next = accountRuntime.connect(endpoint.endpoint.key.hostId)
            channel = next
            val carrier = carriers(next).firstOrNull { it.backend == endpoint.endpoint.key.backendId.value }
                ?: throw RemoteSessionException("REMOTE_BACKEND_UNAVAILABLE")
            val confirmed = carrier.workspaces().firstOrNull { candidate -> candidate.endpoint.key == endpoint.endpoint.key }
                ?: throw RemoteSessionException("REMOTE_WORKSPACE_NOT_FOUND")
            val owner = SupervisorJob(scope.coroutineContext[Job])
            ownerJob = owner
            val ownedScope = CoroutineScope(scope.coroutineContext + owner)
            val local = conversationScope(endpoint.endpoint.key)
            val mirror = withContext(Dispatchers.IO) { EncryptedConversationMirror(context, local) }
            val media = EncryptedHistoryMediaCache(context, local)
            val stagingKey = endpointId(endpoint.endpoint.key)
            val staging = EncryptedAttachmentStagingStore(File(context.noBackupFilesDir, "remote-attachment-staging/$stagingKey"),
                AndroidKeystoreOutboxKeyProvider("remote_attachment_$stagingKey").getOrCreate(), stagingKey, persistent = true)
            val attachments = RemoteAttachmentCoordinator(carrier, ownedScope, staging, EncryptedAttachmentRecovery(context, local))
            val active = AtomicReference<String?>(null)
            val repo = RemoteConversationRepository(carrier, confirmed, ownedScope, active::get, attachments::promptContent) { key, event ->
                mirror.commitNativeEvents(listOf(event))
                nativeNotificationFrame(local.accountId, endpointId(key), event)?.let {
                    nativeConversationNotifications.accept(local.profileId, local.accountId, it)
                }
            }
            repository = repo; ownerAttachments = attachments; ownerMirror = mirror; ownerMedia = media; ownerConversationScope = local
            nativeConversationNotifications.bind(local.profileId, local.accountId)
            val controller = createController(ownedScope, repo, mirror, media, attachments, local, active)
            mutableController.value = controller
            mutableState.update { it.copy(selected = endpoint.endpoint.key) }
            saveSelection(endpoint)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            fail(failure)
            state.value.account?.let { restoreOffline(it) }
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    private fun createController(ownedScope: CoroutineScope, repo: ConversationRepository, mirror: EncryptedConversationMirror,
        media: EncryptedHistoryMediaCache, attachments: RemoteAttachmentCoordinator?, local: ConversationScope,
        active: AtomicReference<String?>, restore: Boolean = true): WorkbenchController {
        val supported = (repo as? RemoteConversationRepository)?.carrier?.capabilities.orEmpty()
        return WorkbenchController(ownedScope, repo, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("remote"), emptyList())
        }, { local }, attachmentCoordinator = attachments,
            restoreLastThread = restore, supportsApprovalCards = SessionCapability.APPROVAL in supported,
            onActiveThreadChanged = { id -> active.set(id); (repo as? RemoteConversationRepository)?.activeConversationChanged(); refreshModels(id) },
            streamHealthSource = repo as? StreamHealthSource, persistence = mirror, media = media,
            downloads = AndroidAttachmentDownloads(context, media, local), allowSending = repo is RemoteConversationRepository,
            supportsConversationRename = SessionCapability.RENAME in supported,
            supportsConversationArchive = SessionCapability.ARCHIVE in supported,
            supportsConversationUnarchive = SessionCapability.UNARCHIVE in supported,
            authoritativeMessageIds = true)
    }
    fun refreshModels(id: String? = controller.value?.state?.value?.activeThreadId) = scope.launch {
        val repo = repository ?: return@launch
        if (id == null) { mutableState.update { it.copy(models = null) }; return@launch }
        mutableState.update { it.copy(modelBusy = true) }
        try {
            val models = repo.modelCatalog(id)
            if (repository === repo && controller.value?.state?.value?.activeThreadId == id) mutableState.update { it.copy(models = models) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (repository === repo) fail(failure) }
        finally { if (repository === repo) mutableState.update { it.copy(modelBusy = false) } }
    }
    fun selectModel(selection: RemoteModelSelection) = scope.launch {
        val repo = repository ?: return@launch
        val id = controller.value?.state?.value?.activeThreadId ?: return@launch
        mutableState.update { it.copy(modelBusy = true) }
        try {
            val confirmed = repo.selectModel(id, selection)
            if (repository === repo) mutableState.update { it.copy(models = it.models?.copy(selected = confirmed)) }
        } catch (failure: Exception) { fail(failure) }
        finally { mutableState.update { it.copy(modelBusy = false) } }
    }
    fun logout() = scope.launch {
        accountRuntime.logout()
        mutableState.update { it.copy(account = null) }
        selection.withLock {
        retire(); registered.values.forEach { it.registration.close() }; registered.clear()
        nativeConversationNotifications.leave(); mutableState.value = RemoteWorkspaceState()
    } }
    private suspend fun retire() {
        mutableController.value?.closeAndAwait(); mutableController.value = null
        assistantControllers.values.forEach { it.closeAndAwait() }; assistantControllers.clear()
        repository?.close(); repository = null
        ownerJob?.cancelAndJoin(); ownerJob = null
        channel?.close(); channel = null
        ownerAttachments = null; ownerMirror = null; ownerMedia = null; ownerConversationScope = null
        mutableAssistantVersion.value++
    }
    private suspend fun restoreOffline(account: RemoteActiveAccount) {
        val saved = preferences.getString("selection:${profileId(account.origin)}:${account.accountId}", null) ?: return
        val fields = Json.parse(saved) as? JsonValue.JArray ?: return
        val values = fields.items.map { (it as? JsonValue.JString)?.value ?: return }
        if (values.size != 5) return
        val key = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(account.origin), account.accountId, values[0], BackendId(values[1]), values[2])
        val local = conversationScope(key)
        val mirror = withContext(Dispatchers.IO) { EncryptedConversationMirror(context, local) }
        val media = EncryptedHistoryMediaCache(context, local)
        val owner = SupervisorJob(scope.coroutineContext[Job]); ownerJob = owner
        ownerMirror = mirror; ownerMedia = media; ownerConversationScope = local
        mutableController.value = withContext(Dispatchers.IO) { createController(CoroutineScope(scope.coroutineContext + owner), OfflineMirrorRepository(mirror), mirror, media, null, local, AtomicReference()) }
        mutableState.update { it.copy(selected = key, endpoints = listOf(RemoteWorkspace(AgentEndpoint(key, values[3], EndpointAvailability.UNKNOWN), values[4]))) }
    }
    private fun saveSelection(workspace: RemoteWorkspace) {
        val key = workspace.endpoint.key
        preferences.edit().putString("selection:${profileId(key.origin.value)}:${key.accountId}", Json.canonical(Json.of(listOf(
            key.hostId, key.backendId.value, key.workspaceId, workspace.endpoint.displayName, workspace.path)))).commit()
    }
    private fun fail(failure: Exception) { mutableState.update { it.copy(errorCode = (failure as? RemoteRpcException)?.code ?: (failure as? RemoteSessionException)?.code ?: "REMOTE_CONNECTION_FAILED") } }
    private fun carriers(channel: RemoteRpcChannel): List<RemoteSessionCarrier> = buildList {
        if ("harness.remote.v3" in channel.capabilities) add(HarnessSessionCarrier(channel))
        if ("codex.appserver.v1" in channel.capabilities) add(CodexSessionCarrier(channel))
        if ("agent.acp.v1" in channel.capabilities) channel.capabilities.mapNotNull { Regex("agent\\.acp\\.([a-z][a-z0-9_-]*)\\.v1").matchEntire(it)?.groupValues?.get(1) }
            .forEach { add(AcpSessionCarrier(channel, it)) }
    }
    private fun conversationScope(key: EndpointKey) = ConversationScope(profileId(key.origin.value), endpointId(key), key.accountId, installationId())
    private fun installationId(): String = preferences.getString("installation-id", null) ?: java.util.UUID.randomUUID().toString().also {
        check(preferences.edit().putString("installation-id", it).commit()) { "LOCAL_SAVE_FAILED" }
    }
    private fun profileId(origin: String) = "remote_" + digest(origin)
    private fun endpointId(key: EndpointKey) = digest(Json.canonical(Json.of(listOf(key.origin.value, key.accountId, key.hostId, key.backendId.value, key.workspaceId))))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun ensureAssistantWorkspace(): Boolean = repository != null && controller.value != null
    internal fun createAssistantSessionController(token: String): WorkbenchController? {
        assistantControllers[token]?.let { return it }
        val repo = repository ?: return null
        val owner = ownerJob ?: return null
        val mirror = ownerMirror ?: return null
        val media = ownerMedia ?: return null
        val local = ownerConversationScope ?: return null
        val active = AtomicReference<String?>(null)
        return createController(CoroutineScope(scope.coroutineContext + owner), repo, mirror, media, ownerAttachments, local, active,
            restore = false).also { controller -> assistantControllers.putIfAbsent(token, controller)?.let { controller.close() } }
            .let { assistantControllers[token] ?: it }
    }
    internal fun reattachAssistantSession(token: String) = assistantControllers[token]
    internal fun releaseAssistantSession(token: String) {
        val current = assistantControllers[token] ?: return
        if (current.state.value.generation in setOf(GenerationState.RUNNING, GenerationState.QUEUED, GenerationState.OUTCOME_UNKNOWN)) return
        assistantControllers.remove(token)?.close()
    }
    internal fun setAssistantPresence(clientToken: String, sessionToken: String?, visible: Boolean) {
        assistantPresence[clientToken] = sessionToken to visible
    }
    internal fun releaseAssistantClient(token: String) { assistantPresence.remove(token) }
    internal suspend fun prepareAssistantHandoff(token: String): AssistantHandoffResult {
        val sender = assistantControllers[token] ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.notConnected(context))
        val receiver = controller.value ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.workbenchNotReady(context))
        val profile = connectedProfileId ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.notConnected(context))
        val account = connectedAccountId ?: return AssistantHandoffResult.Rejected(AssistantHandoffReasons.accountMismatch(context))
        if (!sender.awaitHandoffAttachments(30_000)) return AssistantHandoffResult.Rejected(AssistantHandoffReasons.attachmentsNotReady(context))
        val draft = sender.assistantDraftForHandoff()
        val conversation = sender.state.value.activeThreadId
        conversation?.let(receiver::openThread)
        val skipped = receiver.adoptAssistantDraft(draft.text, draft.attachments)
        val target = AssistantHandoffTarget(profile, account, state.value.selected?.let(::endpointId), conversation)
        handoffs[token] = target
        return AssistantHandoffResult.Accepted(target, skipped)
    }
    internal fun consumeAssistantHandoff(token: String): Boolean = handoffs.remove(token)?.let { target ->
        if (!isBubbleTargetCurrent(ReplyNotificationTarget(target.profileId, target.accountId, target.conversationId ?: "", target.agentId))) return false
        target.conversationId?.let { controller.value?.openThread(it) }; true
    } ?: false
    internal fun isBubbleTargetCurrent(target: ReplyNotificationTarget): Boolean = target.profileId == connectedProfileId &&
        target.accountId == connectedAccountId && target.agentId == state.value.selected?.let(::endpointId)
    internal fun createBubbleConversation(target: ReplyNotificationTarget): WorkbenchController? {
        if (!isBubbleTargetCurrent(target)) return null
        return createAssistantSessionController("bubble:${target.identityKey()}")?.also { it.openThread(target.conversationId) }
    }
    internal fun releaseBubbleConversation(controller: WorkbenchController) {
        assistantControllers.entries.firstOrNull { it.value === controller }?.let { releaseAssistantSession(it.key) }
    }
    internal suspend fun createBubbleThread(target: ReplyNotificationTarget): ReplyNotificationTarget {
        check(isBubbleTargetCurrent(target)) { "REMOTE_ENDPOINT_CHANGED" }
        val repo = repository ?: error("REMOTE_NOT_CONNECTED")
        val local = ownerConversationScope ?: error("REMOTE_NOT_CONNECTED")
        val created = repo.createConversation(local, "conversation_${java.util.UUID.randomUUID()}")
        return target.copy(conversationId = created.id.value)
    }
    internal fun openReplyNotification(target: ReplyNotificationTarget): Boolean {
        if (!isBubbleTargetCurrent(target)) return false
        controller.value?.openThread(target.conversationId) ?: return false
        return true
    }
    fun onAppForegrounded() { replyNotifications.refreshPermission(); nativeConversationNotifications.refreshActive() }
    fun onAppBackgrounded() = Unit
    fun onNetworkAvailable(routeChanged: Boolean = false) {
        val selected = state.value.selected ?: return
        if (controller.value?.state?.value?.isOnline == false) state.value.endpoints.firstOrNull { it.endpoint.key == selected }?.let(::select)
    }
    fun onNetworkLost() { controller.value?.setRemoteConversationOnline(false) }
    fun onBackgroundSessionRestarted() { restoreSessionIfAvailable() }
    fun onBackgroundSessionStopped() { onNetworkLost() }
}
