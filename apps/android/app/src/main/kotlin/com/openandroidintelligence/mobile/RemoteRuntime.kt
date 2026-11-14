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
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.mobile.assistant.*
import com.openandroidintelligence.mobile.conversations.*
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.plugins.RemotePhoneControlComposition
import com.openandroidintelligence.mobile.plugins.DeviceCapabilitySyncState
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
    val accountAvatarBase64: String? = null,
    val backendFailures: Map<String, String> = emptyMap(),
)

/** App 的 Remote 装配面：连接、工作台和本机控制分别拥有各自生命周期。 */
class RemoteRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pluginHost: ProductionPluginHost,
    private val grants: PairingGrantStateHolder,
    private val confirmLocally: (suspend (String, String, String) -> Boolean)? = null,
    accountFactory: (RemoteRpcHostHandler) -> RemoteAccountConnection = { ProductionRemoteAccountConnection(context, scope, it) },
    private val localDocumentKeyProvider: AesGcmKeyProvider? = null,
) {
    private val mutableState = MutableStateFlow(RemoteWorkspaceState())
    val state = mutableState.asStateFlow()
    private val mutableController = MutableStateFlow<WorkbenchController?>(null)
    val controller = mutableController.asStateFlow()
    val deviceCapabilityState = MutableStateFlow(DeviceCapabilitySyncState())
    private val selection = Mutex()
    private val preferences = context.getSharedPreferences("remote-workspace-selection-v1", Context.MODE_PRIVATE)
    private val phone by lazy {
        confirmLocally?.let { RemotePhoneControlComposition(context, scope, pluginHost, grants, it) }
            ?: RemotePhoneControlComposition(context, scope, pluginHost, grants)
    }
    private data class Registered(val access: ControlAccessBinding, val registration: AutoCloseable)
    private val registered = ConcurrentHashMap<String, Registered>()
    private val handler = object : RemoteRpcHostHandler {
        private val delegate by lazy { PhoneRemoteRpcHostHandler(scope, phone.router, ::access) }
        override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = delegate.invoke(scope, method, params)
        override fun events(scope: RemoteRpcConnectionScope) = flow { emitAll(delegate.events(scope)) }
    }
    val accountRuntime = accountFactory(handler)
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
    private val assistantWrappers = ConcurrentHashMap<String, AssistantAttachmentDraftCoordinator>()
    private val assistantRepositories = ConcurrentHashMap<String, RemoteConversationRepository>()
    private var assistantWorkspace: AssistantConversationWorkspace? = null
    private var surfaceRepository: ((AtomicReference<String?>) -> RemoteConversationRepository)? = null
    private val modelSequence = java.util.concurrent.atomic.AtomicLong()
    private val mutableAssistantVersion = MutableStateFlow(0L)
    val assistantWorkspaceVersion = mutableAssistantVersion.asStateFlow()
    val replyNotifications = ReplyNotificationSettings(context)
    private val publisher = AndroidReplyNotificationPublisher(context, replyNotifications) { target ->
        assistantControllers.any { (token, controller) -> assistantPresence.values.any { it.first == token && it.second } &&
            controller.state.value.activeThreadId == target.conversationId && target.agentId == state.value.selected?.let(::endpointId) }
    }
    internal val nativeConversationNotifications = NativeConversationNotifications(context, scope, publisher::update,
        cancel = publisher::cancel, forget = publisher::forget)
    val localSettings by lazy {
        LocalSettingsRuntime(replyNotifications, nativeConversationNotifications::refreshActive,
            ::reloadDebounceSettings, { controller.value?.refreshThreads() })
    }
    val connectedAccountId: String? get() = state.value.account?.accountId
    val connectedProfileId: String? get() = state.value.account?.let { profileId(it.origin) }
    val connectedAgentName: String? get() = state.value.endpoints.firstOrNull { it.endpoint.key == state.value.selected }?.endpoint?.displayName

    private fun access(connection: RemoteRpcConnectionScope): ControlAccessBinding {
        val peer = accountRuntime.authenticatedPeer(connection.connectionId)
        check(peer.scope == connection && peer.role == RemoteRole.HOST) { "REMOTE_HOST_BINDING_MISMATCH" }
        val active = accountRuntime.account.value ?: error("ACCOUNT_AUTH_REQUIRED")
        check(active.origin == connection.origin && active.accountId == connection.accountId) { "REMOTE_ACCOUNT_BINDING_MISMATCH" }
        registered[connection.connectionId]?.let {
            check(it.access.authorizationEpoch == active.epoch && it.access.connectionGeneration == connection.generation) { "REMOTE_AUTHORIZATION_CHANGED" }
            return it.access
        }
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
            mutableState.value = RemoteWorkspaceState(account = accountRuntime.account.value, busy = true,
                accountAvatarBase64 = accountRuntime.account.value?.let(::avatar))
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
            mutableState.update { it.copy(account = account, accountAvatarBase64 = avatar(account)) }
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
                val failures = linkedMapOf<String, String>()
                val endpoints = carriers.flatMap { carrier ->
                    try { carrier.workspaces() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failures[carrier.backend] = (failure as? RemoteRpcException)?.code ?: (failure as? RemoteSessionException)?.code ?: "REMOTE_BACKEND_UNAVAILABLE"
                        emptyList()
                    }
                }.map { workspace ->
                    workspace.copy(endpoint = workspace.endpoint.copy(displayName = "${host.name} · ${workspace.endpoint.key.backendId.value} · ${workspace.endpoint.displayName}"))
                }
                endpoints.forEach(::saveEndpoint)
                mutableState.update { old -> old.copy(endpoints = old.endpoints.filter { it.endpoint.key.hostId != host.deviceId } + endpoints, backendFailures = failures) }
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
            val mirror = withContext(Dispatchers.IO) { EncryptedConversationMirror(context, local, localDocumentKeyProvider) }
            val media = EncryptedHistoryMediaCache(context, local, keyProvider = localDocumentKeyProvider)
            val stagingKey = endpointId(endpoint.endpoint.key)
            val staging = EncryptedAttachmentStagingStore(File(context.noBackupFilesDir, "remote-attachment-staging/$stagingKey"),
                (localDocumentKeyProvider ?: AndroidKeystoreOutboxKeyProvider("remote_attachment_$stagingKey")).getOrCreate(), stagingKey, persistent = true)
            val attachments = RemoteAttachmentCoordinator(carrier, ownedScope, staging, EncryptedAttachmentRecovery(context, local, localDocumentKeyProvider))
            val active = AtomicReference<String?>(null)
            val repo = RemoteConversationRepository(carrier, confirmed, ownedScope, active::get, attachments::promptContent) { key, event ->
                mirror.commitNativeEvents(listOf(event))
                nativeNotificationFrame(local.accountId, endpointId(key), event)?.let {
                    nativeConversationNotifications.accept(local.profileId, local.accountId, it)
                }
            }
            repository = repo; ownerAttachments = attachments; ownerMirror = mirror; ownerMedia = media; ownerConversationScope = local
            surfaceRepository = { surfaceActive -> RemoteConversationRepository(carrier, confirmed, ownedScope, surfaceActive::get,
                attachments::promptContent) { key, event ->
                mirror.commitNativeEvents(listOf(event))
                nativeNotificationFrame(local.accountId, endpointId(key), event)?.let {
                    nativeConversationNotifications.accept(local.profileId, local.accountId, it)
                }
            } }
            nativeConversationNotifications.bind(local.profileId, local.accountId)
            val controller = createController(ownedScope, repo, mirror, media, attachments, local, active)
            mutableController.value = controller
            mutableState.update { it.copy(selected = endpoint.endpoint.key) }
            assistantWorkspace = AssistantConversationWorkspace(local.profileId, local.accountId, endpointId(endpoint.endpoint.key), ownedScope,
                create = ::createAssistantOwned, onSessionReleased = { token ->
                    assistantControllers.remove(token)
                    assistantWrappers.remove(token)?.close(scope)
                    assistantRepositories.remove(token)?.let { old -> scope.launch { old.close() } }
                })
            saveSelection(endpoint)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            fail(failure)
            state.value.account?.let { restoreOffline(it) }
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    private fun createController(ownedScope: CoroutineScope, repo: ConversationRepository, mirror: EncryptedConversationMirror,
        media: EncryptedHistoryMediaCache, attachments: AttachmentDraftCoordinator?, local: ConversationScope,
        active: AtomicReference<String?>, restore: Boolean = true): WorkbenchController {
        val supported = (repo as? RemoteConversationRepository)?.carrier?.capabilities.orEmpty()
        val source = if (repo is RemoteConversationRepository) MirroredConversationRepository(repo, mirror, media) else repo
        val organization = EncryptedNativeConversationOrganizationStore(context, local, localDocumentKeyProvider)
        val tracker = repo as? GenerationTracker ?: object : GenerationTracker {
            override val generationId = MutableStateFlow<String?>(null)
            override val generationState = MutableStateFlow(GenerationState.IDLE)
        }
        val history = object : ConversationRepository by source, GenerationTracker by tracker, MessageOutcomeQuery {
            override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? =
                (source as? MessageOutcomeQuery)?.queryMessage(conversationId, clientMessageId)
            override suspend fun readOrganization() = withContext(Dispatchers.IO) { organization.read(local.gatewayId) }
            override suspend fun updateOrganization(expectedRevision: Long, value: ConversationOrganization) =
                withContext(Dispatchers.IO) { organization.update(local.gatewayId, expectedRevision, value) }
        }
        return WorkbenchController(ownedScope, history, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                (repo as? RemoteConversationRepository)?.carrier?.commands(active.get()?.let(RemoteLocalIds::decode))
                    ?: throw RemoteSessionException("COMMANDS_UNAVAILABLE_OFFLINE")
        }, { local }, attachmentCoordinator = attachments,
            restoreLastThread = restore, supportsApprovalCards = SessionCapability.APPROVAL in supported,
            onActiveThreadChanged = { id -> active.set(id); (repo as? RemoteConversationRepository)?.activeConversationChanged(); if (restore) refreshModels(id) },
            streamHealthSource = repo as? StreamHealthSource, persistence = mirror.takeIf { restore }, media = media,
            downloads = AndroidAttachmentDownloads(context, media, local, localDocumentKeyProvider), allowSending = repo is RemoteConversationRepository,
            supportsConversationManagement = true, organizationStoredLocally = true,
            supportsConversationDelete = false,
            supportsConversationRename = SessionCapability.RENAME in supported,
            supportsConversationArchive = SessionCapability.ARCHIVE in supported,
            supportsConversationUnarchive = SessionCapability.UNARCHIVE in supported,
            authoritativeMessageIds = true)
    }
    fun refreshModels(id: String? = controller.value?.state?.value?.activeThreadId): Job {
        val sequence = modelSequence.incrementAndGet()
        return scope.launch {
        val repo = repository ?: return@launch
        if (id == null) { mutableState.update { it.copy(models = null) }; return@launch }
        mutableState.update { it.copy(modelBusy = true) }
        try {
            val models = repo.modelCatalog(id)
            if (repository === repo && modelSequence.get() == sequence && controller.value?.state?.value?.activeThreadId == id) mutableState.update { it.copy(models = models) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (repository === repo) fail(failure) }
        finally { if (repository === repo && modelSequence.get() == sequence) mutableState.update { it.copy(modelBusy = false) } }
    } }
    fun selectModel(selection: RemoteModelSelection): Job {
        val sequence = modelSequence.incrementAndGet()
        return scope.launch {
        val repo = repository ?: return@launch
        val id = controller.value?.state?.value?.activeThreadId ?: return@launch
        mutableState.update { it.copy(modelBusy = true) }
        try {
            val confirmed = repo.selectModel(id, selection)
            if (repository === repo && modelSequence.get() == sequence && controller.value?.state?.value?.activeThreadId == id)
                mutableState.update { it.copy(models = it.models?.copy(selected = confirmed)) }
        } catch (failure: Exception) { fail(failure) }
        finally { if (repository === repo && modelSequence.get() == sequence) mutableState.update { it.copy(modelBusy = false) } }
    } }
    fun logout() = scope.launch {
        accountRuntime.logout()
        mutableState.update { it.copy(account = null) }
        selection.withLock {
        retire(); registered.values.forEach { it.registration.close() }; registered.clear()
        nativeConversationNotifications.leave(); mutableState.value = RemoteWorkspaceState()
    } }
    private suspend fun retire() {
        mutableController.value?.closeAndAwait(); mutableController.value = null
        assistantWorkspace?.closeAndAwait(); assistantWorkspace = null
        assistantControllers.values.forEach { it.closeAndAwait() }; assistantControllers.clear()
        assistantRepositories.values.forEach { it.close() }; assistantRepositories.clear()
        assistantWrappers.values.forEach { it.close(scope).join() }; assistantWrappers.clear()
        repository?.close(); repository = null
        ownerJob?.cancelAndJoin(); ownerJob = null
        channel?.close(); channel = null
        ownerAttachments = null; ownerMirror = null; ownerMedia = null; ownerConversationScope = null
        surfaceRepository = null
        mutableAssistantVersion.value++
    }
    private suspend fun restoreOffline(account: RemoteActiveAccount) {
        val saved = preferences.getString("selection:${profileId(account.origin)}:${account.accountId}", null) ?: return
        val fields = Json.parse(saved) as? JsonValue.JArray ?: return
        val values = fields.items.map { (it as? JsonValue.JString)?.value ?: return }
        if (values.size != 5) return
        val key = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(account.origin), account.accountId, values[0], BackendId(values[1]), values[2])
        val local = conversationScope(key)
        val mirror = withContext(Dispatchers.IO) { EncryptedConversationMirror(context, local, localDocumentKeyProvider) }
        val media = EncryptedHistoryMediaCache(context, local, keyProvider = localDocumentKeyProvider)
        val owner = SupervisorJob(scope.coroutineContext[Job]); ownerJob = owner
        ownerMirror = mirror; ownerMedia = media; ownerConversationScope = local
        mutableController.value = withContext(Dispatchers.IO) { createController(CoroutineScope(scope.coroutineContext + owner), OfflineMirrorRepository(mirror), mirror, media, null, local, AtomicReference()) }
        mutableState.update { it.copy(selected = key, endpoints = listOf(RemoteWorkspace(AgentEndpoint(key, values[3], EndpointAvailability.UNKNOWN), values[4]))) }
    }
    private fun saveSelection(workspace: RemoteWorkspace) {
        val key = workspace.endpoint.key
        saveEndpoint(workspace)
        preferences.edit().putString("selection:${profileId(key.origin.value)}:${key.accountId}", Json.canonical(Json.of(listOf(
            key.hostId, key.backendId.value, key.workspaceId, workspace.endpoint.displayName, workspace.path)))).commit()
    }
    private fun saveEndpoint(workspace: RemoteWorkspace) {
        val key = workspace.endpoint.key
        val encoded = Json.canonical(Json.of(listOf(key.hostId, key.backendId.value, key.workspaceId, workspace.endpoint.displayName, workspace.path)))
        check(preferences.edit().putString("endpoint:${profileId(key.origin.value)}:${key.accountId}:${endpointId(key)}", encoded).commit()) { "LOCAL_SAVE_FAILED" }
    }
    private fun endpointForNotification(target: ReplyNotificationTarget): RemoteWorkspace? {
        val account = state.value.account ?: return null
        if (target.profileId != profileId(account.origin) || target.accountId != account.accountId || target.agentId == null) return null
        state.value.endpoints.firstOrNull { endpointId(it.endpoint.key) == target.agentId }?.let { return it }
        val encoded = preferences.getString("endpoint:${target.profileId}:${target.accountId}:${target.agentId}", null) ?: return null
        val values = (runCatching { Json.parse(encoded) }.getOrNull() as? JsonValue.JArray)?.items?.map {
            (it as? JsonValue.JString)?.value ?: return null
        } ?: return null
        if (values.size != 5) return null
        val key = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(account.origin), account.accountId, values[0], BackendId(values[1]), values[2])
        if (endpointId(key) != target.agentId) return null
        return RemoteWorkspace(AgentEndpoint(key, values[3], EndpointAvailability.UNKNOWN), values[4])
    }
    private fun fail(failure: Exception) { mutableState.update { it.copy(errorCode = (failure as? RemoteRpcException)?.code ?: (failure as? RemoteSessionException)?.code ?: "REMOTE_CONNECTION_FAILED") } }
    private fun carriers(channel: RemoteRpcChannel): List<RemoteSessionCarrier> = buildList {
        fun ready(backend: String, capability: String): Boolean {
            if (capability !in channel.capabilities) return false
            if (channel.workspaceTypes.isEmpty()) return true
            return channel.workspaceTypes.firstOrNull { JsonFields.string(it, "id") == backend }?.let {
                JsonFields.bool(it, "available") == true && JsonFields.string(it, "capability") in channel.capabilities
            } == true
        }
        if (ready("harness", "harness.remote.v3")) add(HarnessSessionCarrier(channel))
        if (ready("codex", "codex.appserver.v1")) add(CodexSessionCarrier(channel))
        if ("agent.acp.v1" in channel.capabilities) setOf("cursor", "antigravity", "pi", "opencode", "claude", "hermes")
            .filter { ready(it, "agent.acp.$it.v1") }.forEach { add(AcpSessionCarrier(channel, it)) }
    }
    private fun conversationScope(key: EndpointKey) = ConversationScope(profileId(key.origin.value), endpointId(key), key.accountId, installationId())
    private fun installationId(): String = preferences.getString("installation-id", null) ?: java.util.UUID.randomUUID().toString().also {
        check(preferences.edit().putString("installation-id", it).commit()) { "LOCAL_SAVE_FAILED" }
    }
    private fun profileId(origin: String) = "remote_" + digest(origin)
    private fun avatar(account: RemoteActiveAccount): String? = preferences.getString("avatar:${profileId(account.origin)}:${account.accountId}", null)
    fun setAccountAvatar(owner: String, bytes: ByteArray) {
        val account = state.value.account ?: return
        val expected = "${profileId(account.origin)}:${account.accountId}"
        check(owner == expected && bytes.isNotEmpty() && bytes.size <= ACCOUNT_AVATAR_MAX_BYTES) { "REMOTE_AVATAR_OWNER_CHANGED" }
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        check(preferences.edit().putString("avatar:$expected", encoded).commit()) { "LOCAL_SAVE_FAILED" }
        if (state.value.account == account) mutableState.update { it.copy(accountAvatarBase64 = encoded) }
    }
    private fun endpointId(key: EndpointKey) = digest(Json.canonical(Json.of(listOf(key.origin.value, key.accountId, key.hostId, key.backendId.value, key.workspaceId))))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun ensureAssistantWorkspace(): Boolean = repository != null && controller.value != null
    internal fun createAssistantSessionController(token: String): WorkbenchController? {
        return assistantWorkspace?.let { runCatching { it.open(token) }.getOrNull() }
    }
    private fun createAssistantOwned(token: String): WorkbenchController? {
        assistantControllers[token]?.let { return it }
        val owner = ownerJob ?: return null
        val mirror = ownerMirror ?: return null
        val media = ownerMedia ?: return null
        val local = ownerConversationScope ?: return null
        val active = AtomicReference<String?>(null)
        val repo = surfaceRepository?.invoke(active) ?: return null
        assistantRepositories[token] = repo
        val wrapper = assistantAttachmentWrapperFor(assistantWrappers, ownerAttachments, token)
        return createController(CoroutineScope(scope.coroutineContext + owner), repo, mirror, media, wrapper, local, active,
            restore = false).also { controller -> assistantControllers.putIfAbsent(token, controller)?.let { controller.close() } }
            .let { assistantControllers[token] ?: it }
    }
    internal fun reattachAssistantSession(token: String) = assistantControllers[token]
    internal fun releaseAssistantSession(token: String) {
        assistantWorkspace?.release(token)
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
        assistantWrappers[token]?.let { wrapper -> draft.attachments.forEach { wrapper.disown(it.id.value) } }
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
    internal suspend fun activateNotificationEndpoint(target: ReplyNotificationTarget): Boolean {
        if (isBubbleTargetCurrent(target) && repository != null) return true
        val endpoint = endpointForNotification(target) ?: return false
        selectInternal(endpoint)
        return isBubbleTargetCurrent(target) && repository != null
    }
    internal suspend fun openReplyNotification(target: ReplyNotificationTarget): Boolean {
        if (!activateNotificationEndpoint(target)) return false
        controller.value?.openThread(target.conversationId) ?: return false
        return true
    }
    fun onAppForegrounded() { foreground = true; replyNotifications.refreshPermission(); nativeConversationNotifications.refreshActive() }
    private var foreground = false
    fun onAppBackgrounded() { foreground = false }
    /** 仅枚举当前 Remote 账号已登记的完整端点，不读取旧 Core 档案。 */
    fun localOrganizationScopes(): List<ConversationScope> {
        val account = state.value.account ?: return emptyList()
        val prefix = "endpoint:${profileId(account.origin)}:${account.accountId}:"
        return preferences.all.keys.filter { it.startsWith(prefix) }.mapNotNull { key ->
            val id = key.removePrefix(prefix)
            endpointForNotification(ReplyNotificationTarget(profileId(account.origin), account.accountId, "", id))
                ?.endpoint?.key?.let(::conversationScope)
        }.distinct()
    }
    fun readLocalOrganization(local: ConversationScope): ConversationOrganization =
        EncryptedNativeConversationOrganizationStore(context, local, localDocumentKeyProvider).read(local.gatewayId)
    fun updateLocalOrganization(local: ConversationScope, value: ConversationOrganization): ConversationOrganization {
        check(local in localOrganizationScopes()) { "REMOTE_ORGANIZATION_SCOPE_UNAVAILABLE" }
        val store = EncryptedNativeConversationOrganizationStore(context, local, localDocumentKeyProvider)
        return store.update(local.gatewayId, store.read(local.gatewayId).revision, value)
    }
    fun loginOrigin(): String = preferences.getString("preferred-origin", null) ?: "https://dsh.r2049.cn"
    fun saveLoginOrigin(origin: String) {
        val canonical = RemoteServiceOrigin.fromCanonicalOrigin(origin).value
        check(preferences.edit().putString("preferred-origin", canonical).commit()) { "LOCAL_SAVE_FAILED" }
    }
    fun reloadDebounceSettings() {
        val policy = DebouncePreferences(context).read()
        controller.value?.updateDebouncePolicy(policy)
        assistantControllers.values.forEach { it.updateDebouncePolicy(policy) }
    }
    fun onNetworkAvailable(routeChanged: Boolean = false) {
        val selected = state.value.selected ?: return
        if (controller.value?.state?.value?.isOnline == false) state.value.endpoints.firstOrNull { it.endpoint.key == selected }?.let(::select)
    }
    fun onNetworkLost() { controller.value?.setRemoteConversationOnline(false) }
    fun onBackgroundSessionRestarted() { restoreSessionIfAvailable() }
    fun onBackgroundSessionStopped() { onNetworkLost() }
}
