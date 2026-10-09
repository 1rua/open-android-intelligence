package com.openandroidintelligence.mobile

import android.content.Context
import android.os.Build
import com.openandroidintelligence.conversation.data.GatewayAttachmentDraftCoordinator
import com.openandroidintelligence.conversation.data.GatewayCommandCatalogRepository
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.completeTimeline
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.LocalAttachmentStagingStore
import com.openandroidintelligence.conversation.ports.StagedAttachmentContent
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.HttpAttachmentTransport
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.auth.AndroidKeystoreGatewayCredentialStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.GatewayCredentialStore
import com.openandroidintelligence.gateway.auth.SessionCredentials
import com.openandroidintelligence.gateway.auth.PairingRevocationClient
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.device.DeviceRequestClient
import com.openandroidintelligence.gateway.device.HttpDeviceRequestTransport
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.openandroidintelligence.gateway.http.EventSessionRejectedException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The connection lifecycle of the app: disconnected → negotiating →
 * authenticating → connected, with the workbench wiring built only after the
 * Gateway really issued a session.
 *
 * Nothing here fabricates a session. A failed login keeps the phase at Failed
 * with the Gateway's error code, and the login screen stays the honest state.
 *
 * The address the user typed decides the transport security: an `https://`
 * Gateway must return a TLS identity that the phone pins, while a plaintext
 * `http://` Gateway is accepted as an explicitly reported degraded connection
 * (ADR 0047) and never reported as a verified identity.
 */
/**
 * 本客户端在 `features.conversationUi` 里声明过的能力全集。
 *
 * 这份列表必须与 [com.openandroidintelligence.gateway.negotiation.NegotiationClient]
 * 发出的 offer 保持一致（那边是模块私有常量，无法直接引用）；漂移的代价是
 * 设置页会把一个从未请求过的能力标成「本网关不支持」。列表里有而 NegotiationClient
 * 没有的键永远不会被网关同意，也就永远不会离开「未声明」态。
 */
val CLIENT_CONVERSATION_UI_OFFER: Set<String> = setOf(
    "agent-command-catalog-v1",
    "agent-command-new-v1",
    "agent-approval-cards-v1",
    "message-batches-v1",
    "generation-cancel-v1",
)

sealed interface ConnectionPhase {
    data object Disconnected : ConnectionPhase

    data object Negotiating : ConnectionPhase

    data object Authenticating : ConnectionPhase

    data class OfflineMirror(val gatewayUrl:String,val username:String):ConnectionPhase

    data class Connected(
        val gatewayUrl: String,
        val username: String,
        val pairingSummary: String?,
        /** The pinned TLS identity, or null on a plaintext connection. */
        val tlsSpkiSha256: String?,
        val transportSecurity: TransportSecurity,
        /**
         * 协商交集：网关同意且客户端声明过并实现的对话界面能力。
         * 只放真正兑现得了的键——网关单方面同意一个客户端没有的键，照样无法兑现。
         */
        val conversationUi: Set<String> = emptySet(),
        /** 客户端 offer：本端声明过的能力全集（协议层请求原文）。 */
        val requestedConversationUi: Set<String> = CLIENT_CONVERSATION_UI_OFFER,
        /** 网关声明的设备请求能力档（契约 §10）；null 表示网关未提供。 */
        val deviceRequests: String? = null,
    ) : ConnectionPhase

    data class Failed(val code: String) : ConnectionPhase
}

class GatewayRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairingGrants: PairingGrantStateHolder,
    /**
     * 审计落点（条目 2-9）：提供时，被 Gateway 接受的审批卡决策（用户确认）
     * 会经 [ApprovalAuditBridge] 写入平台审计链；null 时行为与从前一致。
     */
    private val auditStore: com.openandroidintelligence.kernel.AndroidAuditStore? = null,
    /**
     * 刷新凭据的保管面。生产实现走 Android Keystore；单测注入内存实现，
     * 因为 Keystore 只在设备上存在，而「刷新凭据」这条路径必须可被验证。
     */
    private val credentialStore: GatewayCredentialStore = AndroidKeystoreGatewayCredentialStore(
        File(context.filesDir, "keystore-credentials").also { it.mkdirs() },
    ),
    /** 设备密钥签名面，理由同 [credentialStore]。 */
    private val deviceKeys: DeviceKeySource = KeystoreDeviceKeySource(
        File(context.filesDir, "gateway-credentials"),
    ),
    private val identityTrust: GatewayIdentityTrustStore = GatewayIdentityTrustStore(context),
    private val accountProfiles: AndroidAccountProfileStore = AndroidAccountProfileStore(context),
    private val pluginHost: com.openandroidintelligence.mobile.plugins.ProductionPluginHost? = null,
    private val localDocumentKeyProvider: com.openandroidintelligence.encrypted.store.AesGcmKeyProvider? = null,
) {
    private var executionDriver: com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver? = null
    private var capabilityPublisher: com.openandroidintelligence.mobile.plugins.CapabilityPublisher? = null
    val deviceConfirmation = MutableStateFlow<com.openandroidintelligence.mobile.plugins.DeviceConfirmation?>(null)
    val connectedAccountId: String? get() = lastAccountId.takeIf { activeHttpClient != null }
    fun decideDeviceRequest(id: String, approved: Boolean) { executionDriver?.decide(id,approved) }

    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()
    private val _savedProfiles = MutableStateFlow(accountProfiles.list())
    val savedProfiles: StateFlow<List<AccountProfile>> = _savedProfiles.asStateFlow()
    private val _isManagingProfiles = MutableStateFlow(false)
    val isManagingProfiles: StateFlow<Boolean> = _isManagingProfiles.asStateFlow()

    private val _controller = MutableStateFlow<WorkbenchController?>(null)
    val controller: StateFlow<WorkbenchController?> = _controller.asStateFlow()

    private val _operationNotice = MutableStateFlow<String?>(null)
    val operationNotice: StateFlow<String?> = _operationNotice.asStateFlow()
    fun dismissOperationNotice() { _operationNotice.value = null }

    init {
        val stagingRoot = File(context.noBackupFilesDir, "attachment-staging")
        scope.launch(Dispatchers.IO) {
            runCatching { EncryptedAttachmentStagingStore.cleanupExpiredRoot(stagingRoot) }
                .onFailure { _operationNotice.value = "ATTACHMENT_CLEANUP_FAILED:ATTACHMENT_STORAGE_UNAVAILABLE" }
        }
    }

    /** 是否正在向 Gateway 轮换凭据；界面据此展示进行中并阻止重复点击。 */
    private val _isRefreshingSession = MutableStateFlow(false)
    val isRefreshingSession: StateFlow<Boolean> = _isRefreshingSession.asStateFlow()

    /**
     * The app is visible again after having been in the background.
     *
     * Only a session that really exists has a channel to re-synchronize, so a
     * missing workbench is a no-op rather than something to invent.
     */
    fun onAppForegrounded() {
        _controller.value?.onForegrounded()
    }

    private var connectionJob: Job? = null
    private var sessionJob: Job? = null
    private var gatewayGrantRevision = -1L
    var activeMediaCache: com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache? = null
        private set
    private var activeAttachmentStaging: LocalAttachmentStagingStore? = null

    private val activeThread = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The login form's authoritative action; the UI only reflects the phase. */
    fun login(gatewayUrl: String, username: String, password: CharArray, invitation: Boolean = false, deviceKey: Boolean = false, expectedIdentityFingerprint: String? = null) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) {
            password.fill('\u0000')
            return
        }
        val endpoint = GatewayEndpoint.parse(gatewayUrl)
        if (endpoint == null) {
            password.fill('\u0000')
            _phase.value = ConnectionPhase.Failed("AUTH_INVALID:url-scheme-required")
            return
        }
        val normalized = endpoint.baseUrl
        val profileId = profileIdFor(normalized, username)
        _phase.value = ConnectionPhase.Negotiating
        connectionJob = scope.launch {
            try {
            val retainedPins = identityTrust.pinsBeforeConnect(endpoint, username)
            val negotiation = runCatching { authClientFor(normalized, retainedPins).negotiate("neg_" + newToken()) }
            val negotiated = negotiation.getOrElse { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }
            val tlsPin = identityTrust.verifyNegotiation(endpoint, username, negotiated)
            if (expectedIdentityFingerprint != null) {
                val identity=com.openandroidintelligence.gateway.schema.Json.of(mapOf("deploymentId" to negotiated.deploymentId,"tlsSpkiSha256" to negotiated.tlsSpkiSha256))
                check(com.openandroidintelligence.gateway.schema.Json.sha256(identity)==expectedIdentityFingerprint) { "GATEWAY_IDENTITY_MISMATCH" }
            }

            _phase.value = ConnectionPhase.Authenticating
            val credentials = runCatching {
                val publicKey = deviceKeys.publicKeyBase64Url(profileId)
                if (deviceKey) {
                    val binding=accountProfiles.binding(profileId) ?: error("DEVICE_NOT_PAIRED")
                    authClientFor(normalized,setOfNotNull(tlsPin)).loginWithDeviceKey(negotiated.negotiationId,binding.accountId,binding.deviceId) { deviceKeys.sign(profileId,it) }
                } else if (invitation) authClientFor(normalized,setOfNotNull(tlsPin)).loginWithInvite(negotiated.negotiationId,username,String(password).trim().uppercase(),Build.MODEL ?: "Android",publicKey) { deviceKeys.sign(profileId,it) }
                else authClientFor(normalized, setOfNotNull(tlsPin)).loginWithPassword(
                    negotiationId = negotiated.negotiationId,
                    username = username,
                    password = password,
                    displayName = Build.MODEL ?: "Android",
                    devicePublicKeyBase64Url = publicKey,
                )
            }
            credentials.fold(
                onSuccess = { session ->
                    identityTrust.remember(endpoint, username, negotiated)
                    val refreshCred = session.refreshCredential
                    if (refreshCred.isNotEmpty()) {
                        runCatching {
                            credentialStore.saveRefresh(profileId, refreshCred)
                            saveLastProfile(normalized, username, profileId, session)
                        }.onFailure { _operationNotice.value = "自动登录凭据未能保存，下次启动需要重新登录。" }
                    }
                    establish(
                        endpoint = endpoint,
                        username = username,
                        profileId = profileId,
                        session = session,
                        tlsSpkiSha256 = tlsPin,
                        conversationUi = negotiated.conversationUi,
                        deviceRequests = negotiated.deviceRequests,
                        generationCancelCapability =
                            GenerationCancelCapability.fromNegotiation(negotiated),
                    )
                },
                onFailure = { cause -> _phase.value = ConnectionPhase.Failed(errorCode(cause)) },
            )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Clears a failed attempt so the login form can be re-entered cleanly. */
    fun resetFailure() {
        if (_phase.value is ConnectionPhase.Failed) {
            _phase.value = ConnectionPhase.Disconnected
        }
    }

    fun logout(revokeRefresh: Boolean) {
        if (connectionJob?.isActive == true) return
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val accountId = lastAccountId ?: return
        val deviceId = lastDeviceId ?: return
        val sessionId = lastSessionId ?: return
        val accessToken = accessTokenHolder ?: return
        connectionJob = scope.launch {
            runCatching {
                authClientFor(current.gatewayUrl, setOfNotNull(current.tlsSpkiSha256)).logout(
                    accessToken = accessToken,
                    accountId = accountId,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    revokeRefresh = revokeRefresh,
                )
            }.onFailure { cause ->
                _operationNotice.value = "登出未获 Gateway 确认，请检查连接后重试。"
                return@launch
            }
            runCatching {
                credentialStore.clearRefresh(profileId)
                clearLastProfile()
            }.onFailure { _operationNotice.value = "Gateway 已登出，但本机凭据清理失败，请检查设备存储。" }
            teardown()
        }
    }

    /** Leave the active workbench without revoking another saved account's credentials. */
    fun reconnectOfflineMirror() {
        if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.OfflineMirror) return
        teardown()
        restoreSessionIfAvailable()
    }

    fun chooseAnotherAccount() {
        if (connectionJob?.isActive == true) return
        teardown()
        clearLastProfile()
    }

    fun selectSavedAccount(profileId: String) {
        if (connectionJob?.isActive == true) return
        val profile = accountProfiles.find(profileId) ?: return
        val binding = accountProfiles.binding(profileId) ?: return
        teardown()
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_GATEWAY, profile.gatewayBaseUrl).putString(KEY_LAST_USER, profile.username)
            .putString(KEY_LAST_PROFILE, profileId).putString(KEY_LAST_ACCOUNT, binding.accountId)
            .putString(KEY_LAST_DEVICE, binding.deviceId).putString(KEY_LAST_SESSION, binding.sessionId)
            .putInt(KEY_DEVICE_KEY_ENCODING, binding.keyEncoding)
        if (!edit.commit()) {
            _operationNotice.value = "账号选择未能保存，请检查本机存储后重试。"
            return
        }
        restoreSessionIfAvailable()
    }

    /** Called only by the login form's explicit local identity confirmation. */
    fun reconfirmGatewayIdentity(gatewayUrl: String, username: String) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) return
        val endpoint = GatewayEndpoint.parse(gatewayUrl) ?: return
        if (!endpoint.isTls) return
        runCatching {
            credentialStore.clearRefresh(profileIdFor(endpoint.baseUrl, username))
            identityTrust.forget(endpoint, username)
            _phase.value = ConnectionPhase.Disconnected
            _operationNotice.value = "旧网关身份和自动登录凭据已清除，请核对新身份后使用密码重新登录。"
        }.onFailure { _operationNotice.value = "网关身份清理失败，请检查本机存储。" }
    }

    fun removeLocalAccount(profileId: String) {
        if (connectionJob?.isActive == true || _phase.value is ConnectionPhase.Connected) return
        val profile = accountProfiles.find(profileId) ?: return
        val binding = accountProfiles.binding(profileId)
        val accountId = binding?.accountId ?: accountProfiles.accountId(profileId)
        _isManagingProfiles.value = true
        _operationNotice.value = "正在退出并移除本机账号…"
        connectionJob = scope.launch {
            var refresh: ByteArray? = null
            try {
                refresh = credentialStore.loadRefresh(profileId)
                if (refresh != null && refresh.isNotEmpty() && binding != null) {
                    val endpoint = GatewayEndpoint.parse(profile.gatewayBaseUrl) ?: error("PROFILE_INVALID")
                    val pins = identityTrust.pinsBeforeConnect(endpoint, profile.username, restoring = true)
                    val auth = authClientFor(endpoint.baseUrl, pins)
                    val negotiated = auth.negotiate("neg_" + newToken())
                    identityTrust.verifyNegotiation(endpoint, profile.username, negotiated)
                    val session = auth.refresh(binding.accountId, binding.deviceId, negotiated.negotiationId, refresh)
                    // Preserve the rotated credential if logout cannot be confirmed.
                    credentialStore.saveRefresh(profileId, session.refreshCredential)
                    auth.logout(session.accessToken, session.accountId, session.deviceId, session.sessionId, revokeRefresh = true)
                }
                withContext(Dispatchers.IO) {
                    credentialStore.clearRefresh(profileId)
                    credentialStore.clearDeviceKey(profileId)
                    deviceKeys.delete(profileId)
                    if (accountId != null) {
                        pairingGrants.clearFor(PairingGrantBinding(profile.gatewayBaseUrl, accountId, installationId()))
                        attachmentStagingStore(profile.gatewayBaseUrl, accountId, installationId()).cleanup()
                        com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),localDocumentKeyProvider).wipe()
                        com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),localDocumentKeyProvider).wipe()
                        com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,ConversationScope(profileId,profile.gatewayBaseUrl,accountId,installationId()),keyProvider=localDocumentKeyProvider).clearMedia()
                        pluginHost?.eraseAccount(accountId,PairingGrantBinding(profile.gatewayBaseUrl,accountId,installationId()).pairingId)
                    }
                    AndroidEventCursorStore(context, profileId).clearProfile()
                    AndroidEventCursorStore(context, platformCursorProfile(profileId)).clearProfile()
                    identityTrust.forget(GatewayEndpoint.parse(profile.gatewayBaseUrl)!!, profile.username)
                    accountProfiles.delete(profileId)
                }
                if (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_PROFILE, null) == profileId) clearLastProfile()
                _savedProfiles.value = accountProfiles.list()
                _operationNotice.value = "本机账号已移除；Gateway 账号和其他设备配对保持有效。"
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (cause: Exception) { _operationNotice.value = "本机账号移除未完成，资料已保留，请检查连接或存储后重试。" }
            finally { refresh?.fill(0); _isManagingProfiles.value = false }
        }
    }

    fun unpair() {
        if (connectionJob?.isActive == true) return
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val http = activeHttpClient ?: return
        val deviceId = lastDeviceId ?: return
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val accountId = lastAccountId ?: return
        val staging = activeAttachmentStaging
        val binding = PairingGrantBinding(current.gatewayUrl, accountId, installationId())
        connectionJob = scope.launch {
            try {
                PairingRevocationClient(http).revoke(deviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _operationNotice.value = "解除配对未获 Gateway 确认，本机凭据已保留，请检查连接后重试。"
                return@launch
            }
            teardown()
            val cleanup = listOf<() -> Unit>(
                { pairingGrants.clearFor(binding) },
                { credentialStore.clearRefresh(profileId) },
                { credentialStore.clearDeviceKey(profileId) },
                { deviceKeys.delete(profileId) },
                { staging?.cleanup(); Unit },
                { com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),localDocumentKeyProvider).wipe() },
                { com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),localDocumentKeyProvider).wipe() },
                { com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,ConversationScope(profileId,current.gatewayUrl,accountId,installationId()),keyProvider=localDocumentKeyProvider).clearMedia() },
                { pluginHost?.eraseAccount(accountId,binding.pairingId); Unit },
                { AndroidEventCursorStore(context, profileId).clear(accountId) },
                { AndroidEventCursorStore(context, platformCursorProfile(profileId)).clear(accountId) },
                { clearLastProfile() },
            ).map { action -> runCatching(action) }
            if (cleanup.any { it.isFailure }) {
                _operationNotice.value = "Gateway 已解除配对，但本机数据清理失败，请重试本机账户清理。"
            }
        }
    }

    /**
     * 设置页「刷新网关凭据」条目的动作：在**已连接**的会话上执行
     * `POST /sessions/refresh`，把短期访问令牌与刷新凭据一起轮换。
     *
     * 与冷启动恢复是两条不同的路径：`restoreSessionIfAvailable` 只在
     * Disconnected 成立，因此它无法承担在线续期（在已连接时它必然直接返回，
     * 这正是此前「点击无反应」的原因）。
     *
     * 续期成功后用新凭据重建工作台：只把新令牌写进存储而继续用旧令牌发请求，
     * 不叫续期。若本机没有可用的刷新凭据，则如实说明并保持当前连接不变，
     * 而不是假装成功。
     */
    fun refreshSession() {
        if (connectionJob?.isActive == true || _isRefreshingSession.value) return
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val endpoint = GatewayEndpoint.parse(current.gatewayUrl)
        if (endpoint == null) {
            _operationNotice.value = "刷新网关凭据失败：当前 Gateway 地址无法解析，请重新登录。"
            return
        }
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val accountId = lastAccountId
        val deviceId = lastDeviceId
        if (accountId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            _operationNotice.value = "刷新网关凭据失败：本机没有当前会话的账号/设备标识，请重新登录后重试。"
            return
        }
        val refreshBytes = runCatching { credentialStore.loadRefresh(profileId) }.getOrNull()
        if (refreshBytes == null || refreshBytes.isEmpty()) {
            _operationNotice.value =
                "本机没有为当前账号保存刷新凭据，无法向 Gateway 续期；请重新登录以获取新的自动登录凭据。"
            return
        }

        _isRefreshingSession.value = true
        connectionJob = scope.launch {
            try {
                val negotiated = runCatching {
                    authClientFor(current.gatewayUrl, setOfNotNull(current.tlsSpkiSha256))
                        .negotiate("neg_" + newToken())
                }.getOrElse { cause ->
                    _operationNotice.value = refreshFailureNotice(cause)
                    return@launch
                }
                val tlsPin = identityTrust.verifyNegotiation(endpoint, current.username, negotiated)
                val session = runCatching {
                    authClientFor(current.gatewayUrl, setOfNotNull(tlsPin)).refresh(
                        accountId = accountId,
                        deviceId = deviceId,
                        negotiationId = negotiated.negotiationId,
                        refreshCredential = refreshBytes,
                    )
                }.getOrElse { cause ->
                    // 只有 Gateway 明确拒绝才销毁本机凭据：404/5xx/网络中断
                    // 说明不了凭据是否还有效，清掉会把一次可恢复的中断变成永久登出。
                    if (com.openandroidintelligence.gateway.auth.GatewayAuthException.credentialRefused(cause)) {
                        runCatching { credentialStore.clearRefresh(profileId) }
                        _operationNotice.value =
                            "Gateway 拒绝续期：刷新凭据已失效，本机自动登录凭据已清除，请重新登录。"
                    } else {
                        _operationNotice.value = refreshFailureNotice(cause)
                    }
                    return@launch
                }

                val rotated = session.refreshCredential
                val saveFailure = if (rotated.isNotEmpty()) {
                    runCatching {
                        credentialStore.saveRefresh(profileId, rotated)
                        saveLastProfile(endpoint.baseUrl, current.username, profileId, session)
                    }.exceptionOrNull()
                } else {
                    null
                }

                establish(
                    endpoint = endpoint,
                    username = current.username,
                    profileId = profileId,
                    session = session,
                    tlsSpkiSha256 = tlsPin,
                    conversationUi = negotiated.conversationUi,
                    deviceRequests = negotiated.deviceRequests,
                    generationCancelCapability =
                        GenerationCancelCapability.fromNegotiation(negotiated),
                )
                _operationNotice.value = if (saveFailure == null) {
                    "已向 Gateway 续期会话凭据，新的访问令牌已生效。"
                } else {
                    "已向 Gateway 续期，但新的自动登录凭据未能保存，下次启动可能需要重新登录。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: PostLoginInitializationException) {
                val code = errorCode(cause)
                _phase.value = ConnectionPhase.Failed(code)
                _operationNotice.value = com.openandroidintelligence.conversation.components.readableFailure(
                    code, com.openandroidintelligence.conversation.components.CONNECTION_FAILURE_FALLBACK,
                )
            } catch (cause: Exception) {
                _operationNotice.value = refreshFailureNotice(cause)
            } finally {
                refreshBytes.fill(0)
                _isRefreshingSession.value = false
            }
        }
    }

    fun updateDebounceSettings(gateway:String?,delayMillis:Int,extend:Boolean) {
        val preferences=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context)
        preferences.write(gateway,delayMillis,extend)
        val current=(_phase.value as? ConnectionPhase.Connected)?.gatewayUrl
        _controller.value?.updateDebouncePolicy(preferences.read(current))
    }
    fun useGlobalDebounceSettings(gateway:String) {
        val preferences=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context)
        preferences.useGlobal(gateway)
        _controller.value?.updateDebouncePolicy(preferences.read(gateway))
    }

    private fun showOfflineMirror(endpoint:GatewayEndpoint,username:String,profileId:String,accountId:String,cause:Throwable) {
        if (cause !is java.io.IOException || cause is javax.net.ssl.SSLException) return
        val mirrorScope=ConversationScope(profileId,endpoint.baseUrl,accountId,installationId())
        val mirror=com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,mirrorScope,localDocumentKeyProvider)
        mirror.finishBaselineRecovery()
        if (mirror.load()==null) return
        val owned=SupervisorJob(scope.coroutineContext[Job]); sessionJob=owned
        _controller.value=WorkbenchController(CoroutineScope(owned+Dispatchers.Main.immediate),
            com.openandroidintelligence.mobile.conversations.OfflineMirrorRepository(mirror),
            object:com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository {
                override suspend fun get(gatewayId:String,languageCode:String):com.openandroidintelligence.conversation.ports.AgentCommandCatalog=throw java.io.IOException("OFFLINE_MIRROR")
            },{ mirrorScope },persistence=mirror,media=com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,mirrorScope,keyProvider=localDocumentKeyProvider),allowSending=false)
        _phase.value=ConnectionPhase.OfflineMirror(endpoint.baseUrl,username)
        _operationNotice.value="离线镜像：可阅读历史并编辑草稿，重新连接后可发送。"
    }

    /** Attempts silent session recovery on cold start if valid credentials exist. */
    fun restoreSessionIfAvailable() {
        if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.Disconnected) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUrl = prefs.getString(KEY_LAST_GATEWAY, null) ?: return
        val lastUser = prefs.getString(KEY_LAST_USER, null) ?: return
        val lastProfileId = prefs.getString(KEY_LAST_PROFILE, null) ?: return
        val storedAccountId = prefs.getString(KEY_LAST_ACCOUNT, null) ?: return
        val storedDeviceId = prefs.getString(KEY_LAST_DEVICE, null) ?: return
        val storedSessionId = prefs.getString(KEY_LAST_SESSION, null)
        // Older builds registered the 44-byte SPKI container instead of the 32-byte wire key.
        // Refresh cannot replace that server-side device key; password login registers it again.
        if (prefs.getInt(KEY_DEVICE_KEY_ENCODING, 0) != DEVICE_KEY_ENCODING_VERSION) {
            _phase.value = ConnectionPhase.Failed("DEVICE_KEY_REGISTRATION_UPGRADE_REQUIRED")
            return
        }
        lastAccountId = storedAccountId
        lastDeviceId = storedDeviceId
        lastSessionId = storedSessionId

        val endpoint = GatewayEndpoint.parse(lastUrl)
        if (endpoint == null) {
            _phase.value = ConnectionPhase.Failed("AUTH_INVALID:url-scheme-required")
            return
        }

        val refreshBytes = runCatching { credentialStore.loadRefresh(lastProfileId) }.getOrNull() ?: return
        if (refreshBytes.isEmpty()) return

        _phase.value = ConnectionPhase.Negotiating
        connectionJob = scope.launch {
            try {
            val retainedPins = identityTrust.pinsBeforeConnect(endpoint, lastUser, restoring = true)
            val auth = authClientFor(endpoint.baseUrl, retainedPins)
            val negotiated = runCatching { auth.negotiate("neg_" + newToken()) }.getOrElse { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                showOfflineMirror(endpoint,lastUser,lastProfileId,storedAccountId,cause)
                return@launch
            }
            val tlsPin = identityTrust.verifyNegotiation(endpoint, lastUser, negotiated)

            _phase.value = ConnectionPhase.Authenticating
            val session = runCatching {
                authClientFor(endpoint.baseUrl, setOfNotNull(tlsPin)).refresh(
                    accountId = storedAccountId,
                    deviceId = storedDeviceId,
                    negotiationId = negotiated.negotiationId,
                    refreshCredential = refreshBytes,
                )
            }.getOrElse { cause ->
                // Only an explicit Gateway refusal may destroy the local
                // credential. A 404 from an older Gateway build, a 5xx or a
                // network drop says nothing about whether the stored refresh
                // credential is still valid: wiping it here would turn a
                // recoverable outage into a permanent logout.
                if (com.openandroidintelligence.gateway.auth.GatewayAuthException.credentialRefused(cause)) {
                    credentialStore.clearRefresh(lastProfileId)
                    clearLastProfile()
                }
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                showOfflineMirror(endpoint,lastUser,lastProfileId,storedAccountId,cause)
                return@launch
            }

            val newRefresh = session.refreshCredential
            if (newRefresh.isNotEmpty()) {
                runCatching {
                    credentialStore.saveRefresh(lastProfileId, newRefresh)
                    saveLastProfile(endpoint.baseUrl, lastUser, lastProfileId, session)
                }.onFailure { _operationNotice.value = "轮换后的自动登录凭据未能保存，下次启动可能需要重新登录。" }
            }
            establish(
                endpoint = endpoint,
                username = lastUser,
                profileId = lastProfileId,
                session = session,
                tlsSpkiSha256 = tlsPin,
                conversationUi = negotiated.conversationUi,
                deviceRequests = negotiated.deviceRequests,
                generationCancelCapability =
                    GenerationCancelCapability.fromNegotiation(negotiated),
            )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                showOfflineMirror(endpoint,lastUser,lastProfileId,storedAccountId,cause)
            } finally {
                refreshBytes.fill(0)
            }
        }
    }

    private fun establish(
        endpoint: GatewayEndpoint,
        username: String,
        profileId: String,
        session: SessionCredentials,
        tlsSpkiSha256: String?,
        conversationUi: List<String>,
        deviceRequests: String?,
        generationCancelCapability: GenerationCancelCapability = GenerationCancelCapability.NotNegotiated,
    ): Unit = initializePostLogin(InitializationStage.SESSION_SETUP) {
        val pins = setOfNotNull(tlsSpkiSha256)
        val profile = GatewayProfile(
            accountId = session.accountId,
            deviceId = session.deviceId,
            sessionId = session.sessionId,
            gatewayBaseUrl = endpoint.baseUrl,
            pinnedSpkiSha256 = pins,
            accessToken = session.accessToken,
        )
        val binding = PairingGrantBinding(
                gatewayId = endpoint.baseUrl,
                accountId = session.accountId,
                installationId = installationId(),
            )
        initializePostLogin(InitializationStage.PAIRING_STATE) { pairingGrants.bind(binding) }
        gatewayGrantRevision = session.grantRevision.toLong()
        val transport = GatewayTransport(profile)
        val eventStreamStatus = com.openandroidintelligence.gateway.events.EventStreamStatusSink()
        val http = GatewayHttpClient(
            profile = profile,
            transport = transport,
            signer = { preimage -> deviceKeys.sign(profileId, preimage) },
            cursorStore = AndroidEventCursorStore(context, profileId),
            statusSink = eventStreamStatus,
            // Platform lifecycle is consumed independently of the selected thread.
            handlePlatformEvent = { event -> event.event in PLATFORM_EVENTS },
        )
        activeHttpClient = http
        val conversationClient = ConversationClient(http)
        // Contract §7.2: approval cards are only wired when the Gateway said it
        // serves them. Without the endpoint there is nothing to press, so the
        // workbench says so instead of drawing a card that cannot be answered.
        val approvalClient = if ("agent-approval-cards-v1" in conversationUi) {
            val decisionAudit = auditStore?.let { store ->
                ApprovalAuditBridge(
                    audit = store,
                    accountId = { session.accountId },
                    pairingId = { pairingGrants.state.value?.pairingId.orEmpty() },
                )
            }
            com.openandroidintelligence.gateway.approvals.ApprovalClient(http, audit = decisionAudit)
        } else {
            null
        }
        val repository = GatewayConversationRepository(
            client = conversationClient,
            activeConversationId = { activeThread.get() },
            streamStatus = eventStreamStatus,
            approvals = approvalClient,
        )
        // generation-cancel-v1 门禁接线：工作台的「停止生成」经由这层仓储
        // 才会带上协商门禁转发给真实客户端；未协商时保持 fail-closed。
        val workbenchRepository = CapabilityGatedConversationRepository(
            upstream = repository,
            client = conversationClient,
            capability = generationCancelCapability,
            activeConversationId = { activeThread.get() },
        )
        val catalogRepository = GatewayCommandCatalogRepository(CommandCatalogClient(http))
        // 契约 §10 设备请求执行通路：网关协商通过了 deviceRequests 才装配。
        // 触发源是网关下发 device.request.* 事件（条目 3-6），本机不自发产生
        // 请求；通路在此接好，收到请求即按 claim → result 两步执行。
        deviceRequestClient = if (deviceRequests != null) {
            DeviceRequestClient(HttpDeviceRequestTransport(http))
        } else {
            null
        }
        accessTokenHolder = session.accessToken
        lastAccountId = session.accountId
        lastDeviceId = session.deviceId
        lastSessionId = session.sessionId

        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("session.ready", mapOf(
            "accountId" to session.accountId, "deviceId" to session.deviceId, "sessionId" to session.sessionId,
        ))

        sessionJob?.cancel()
        val ownedJob = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = ownedJob
        val sessionScope = CoroutineScope(ownedJob + Dispatchers.Main.immediate)
        if (pluginHost != null && deviceRequests != null) {
            initializePostLogin(InitializationStage.DEVICE_EXECUTION) {
                val publisher = com.openandroidintelligence.mobile.plugins.CapabilityPublisher(context,http,binding.storageKey,session.grantRevision,
                    localRevision = { pairingGrants.state.value?.revision ?: 0L },host = pluginHost,
                    foreignChange = { executionDriver?.cancelAll(); pairingGrants.clearCurrent(); pairingGrants.bind(binding) })
                capabilityPublisher = publisher
                val executor = com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver(context,sessionScope,http,pluginHost,
                    session.accountId,session.deviceId,session.pairingGeneration,binding.storageKey,{ publisher.grantRevision },
                    isForeground = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) })
                executionDriver = executor
                sessionScope.launch { executor.confirmation.collect { deviceConfirmation.value = it } }
                sessionScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try { executor.recoverPending(); com.openandroidintelligence.mobile.plugins.PluginJobScheduler.maintain(context) }
                        catch (cancelled:CancellationException) { throw cancelled } catch (_:Exception) { _operationNotice.value="设备结果交付重试中。" }
                        delay(30_000)
                    }
                }
                sessionScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.flow.combine(pairingGrants.state,pluginHost.revision) { _, _ -> Unit }.collectLatest {
                        while (isActive) {
                            try { publisher.sync(); break }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { _operationNotice.value = "设备能力授权同步失败，正在重试。"; delay(2_000) }
                        }
                    }
                }
            }
        }
        val attachmentCoordinator = initializePostLogin(InitializationStage.ATTACHMENT_RECOVERY) {
            val attachmentTransport = HttpAttachmentTransport(http)
            val uploader = AttachmentUploader(attachmentTransport)
            val gate = com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate(
                onSubmit = { error("ATTACHMENT_SUBMISSION_REQUIRES_CONVERSATION_CONTROLLER") },
            )
            val staging = attachmentStagingStore(endpoint.baseUrl, session.accountId, installationId())
            activeAttachmentStaging = staging
            GatewayAttachmentDraftCoordinator(
                uploader,
                gate,
                sessionScope,
                staging,
                recovery = com.openandroidintelligence.mobile.conversations.EncryptedAttachmentRecovery(context,ConversationScope(profileId,endpoint.baseUrl,session.accountId,installationId()),localDocumentKeyProvider),
            )
        }

        val conversationScope = ConversationScope(
            profileId = profileId,
            gatewayId = endpoint.baseUrl,
            accountId = session.accountId,
            installId = installationId(),
        )

        val mediaCache = initializePostLogin(InitializationStage.MEDIA_CACHE) {
            com.openandroidintelligence.mobile.conversations.EncryptedHistoryMediaCache(context,conversationScope,http,localDocumentKeyProvider)
        }
        activeMediaCache=mediaCache
        val mirror = initializePostLogin(InitializationStage.MIRROR_RECOVERY) {
            com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror(context,conversationScope,localDocumentKeyProvider)
                .also { it.finishBaselineRecovery() }
        }
        val mirroredRepository = com.openandroidintelligence.mobile.conversations.MirroredConversationRepository(workbenchRepository,mirror,mediaCache)
        http.setCursorRecovery {
            val response = http.execute(com.openandroidintelligence.gateway.http.SignedGatewayRequest("GET","/open-android-intelligence/v2/sync/snapshot"))
            check(response.status == 200) { "SNAPSHOT_FAILED" }
            val envelope = JsonFields.obj(Json.parse(response.body.decodeToString())) ?: error("SNAPSHOT_INVALID")
            val data = JsonFields.obj(JsonFields.field(envelope,"data")) ?: error("SNAPSHOT_INVALID")
            val baseline = JsonFields.string(data,"baselineCursor") ?: error("SNAPSHOT_INVALID")
            val threads = workbenchRepository.listConversations(conversationScope,com.openandroidintelligence.conversation.ports.PageRequest()).conversations
            val pages = linkedMapOf<String,com.openandroidintelligence.conversation.ports.TimelinePage>()
            threads.forEach { thread -> pages[thread.id.value] = workbenchRepository.completeTimeline(thread.id.value) }
            mirror.installBaseline(threads,pages,baseline)
            mediaCache.reconcile(pages.values.flatMap { it.messages }.flatMap { it.parts }.filterIsInstance<com.openandroidintelligence.conversation.model.MessagePart.Attachment>().map { it.draftId.value }.toSet())
            mirror.finishBaselineRecovery()
            _controller.value?.onMirrorRebuilt()
            baseline
        }
        _controller.value = initializePostLogin(InitializationStage.WORKBENCH) {
            WorkbenchController(
                scope = sessionScope,
                repository = mirroredRepository,
                catalogRepository = catalogRepository,
                scopeFactory = { conversationScope },
                attachmentCoordinator = attachmentCoordinator,
                supportsMessageBatches = setOf("message-batches-v1", "newline-v1").all { it in conversationUi },
                debouncePolicy=com.openandroidintelligence.mobile.conversations.DebouncePreferences(context).read(endpoint.baseUrl),
                // Whether this Gateway serves the `/new` command entry. Without it
                // the workbench refuses to create a thread at all rather than
                // building one only the phone knows about.
                supportsAgentCommandNew = "agent-command-new-v1" in conversationUi,
                supportsApprovalCards = approvalClient != null,
                onActiveThreadChanged = { threadId -> activeThread.set(threadId);repository.activeConversationChanged() },
                streamHealthSource = workbenchRepository,
                persistence = mirror,
                media = mediaCache,
            )
        }
        _phase.value = ConnectionPhase.Connected(
            gatewayUrl = endpoint.baseUrl,
            username = username,
            pairingSummary = session.pairingSummary,
            tlsSpkiSha256 = tlsSpkiSha256,
            transportSecurity = endpoint.securityFor(pins),
            // 协商交集：只保留客户端真的声明过并实现的能力。网关回了一个
            // 客户端没有的键，客户端无法兑现，不能让它以「已同意」的面目出现。
            conversationUi = CLIENT_CONVERSATION_UI_OFFER intersect conversationUi.toSet(),
            requestedConversationUi = CLIENT_CONVERSATION_UI_OFFER,
            deviceRequests = deviceRequests,
        )
        GatewayLog.d("OaiConnection", "stage=CONNECTED state=ready")
        val platformHttp = GatewayHttpClient(
            profile = profile,
            transport = transport,
            signer = { preimage -> deviceKeys.sign(profileId, preimage) },
            cursorStore = AndroidEventCursorStore(context, platformCursorProfile(profileId)),
            webSocketTransport = null,
            handlePlatformEvent = { event -> handlePlatformEvent(event, session.sessionId, session.deviceId, binding) },
        )
        platformHttp.setCursorRecovery {
            recoverPlatformSnapshot(platformHttp,
                syncCapabilities = { capabilityPublisher?.sync() },
                acceptRequest = { executionDriver?.accept(it) },
                recoverLocalRequests = { executionDriver?.recoverPending() })
        }
        sessionScope.launch {
            GatewayLog.d("OaiConnection", "stage=EVENT_STREAM state=starting")
            // This cursor/collector never consumes the conversation cursor:
            // business events keep their apply-before-commit delivery path.
            while (isActive) {
                try {
                    platformHttp.events().collect { }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (rejected: EventSessionRejectedException) {
                    logConnectionFailure("EVENT_STREAM", "SESSION_REJECTED", rejected)
                    teardown()
                    _phase.value = ConnectionPhase.Failed("SESSION_REJECTED")
                    _operationNotice.value = "网关会话已失效，本机授权已冻结，请重新登录。"
                    return@launch
                } catch (cause: Exception) {
                    logConnectionFailure("EVENT_STREAM", "EVENT_STREAM_RETRY", cause)
                    delay(1_000)
                }
            }
        }
    }

    /** 本机初始化的失败只清理本次运行态，账号资料、密钥、刷新凭据和历史文件全部保留。 */
    private inline fun <T> initializePostLogin(stage: InitializationStage, operation: () -> T): T {
        GatewayLog.d("OaiConnection", "stage=${stage.name} state=starting")
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (diagnosed: PostLoginInitializationException) {
            throw diagnosed
        } catch (cause: Exception) {
            val code = "POST_LOGIN_INITIALIZATION_FAILED:${stage.name}"
            logConnectionFailure(stage.name, code, cause)
            try {
                teardown()
            } catch (cleanup: Exception) {
                logConnectionFailure(stage.name, "INITIALIZATION_CLEANUP_FAILED", cleanup)
            }
            // 不把原始异常正文、路径或密码材料带入界面状态。
            throw PostLoginInitializationException(code)
        }
    }

    private fun logConnectionFailure(stage: String, code: String, cause: Exception) {
        GatewayLog.w("OaiConnection", "stage=$stage code=$code errorType=${cause.javaClass.simpleName}")
    }

    private enum class InitializationStage {
        SESSION_SETUP,
        PAIRING_STATE,
        DEVICE_EXECUTION,
        ATTACHMENT_RECOVERY,
        MEDIA_CACHE,
        MIRROR_RECOVERY,
        WORKBENCH,
    }

    private class PostLoginInitializationException(code: String) : IllegalStateException(code)

    private fun teardown() {
        _controller.value?.close()
        activeMediaCache = null
        executionDriver?.cancelAll()
        executionDriver = null
        capabilityPublisher = null
        deviceConfirmation.value = null
        sessionJob?.cancel()
        sessionJob = null
        _controller.value = null
        accessTokenHolder = null
        activeHttpClient = null
        activeAttachmentStaging = null
        deviceRequestClient = null
        activeThread.set(null)
        pairingGrants.unbind()
        _phase.value = ConnectionPhase.Disconnected
    }

    private suspend fun handlePlatformEvent(event: GatewayEvent, sessionId: String, deviceId: String, binding: PairingGrantBinding): Boolean {
        if (event.event !in PLATFORM_EVENTS) return false
        val body = JsonFields.obj(Json.parse(event.data)) ?: error("PLATFORM_EVENT_INVALID")
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: error("PLATFORM_EVENT_INVALID")
        when (event.event) {
            "device.requested" -> executionDriver?.accept(JsonFields.string(payload,"requestId") ?: error("PLATFORM_EVENT_INVALID"))
            "device.request.cancel.requested" -> executionDriver?.cancel(JsonFields.string(payload,"requestId") ?: error("PLATFORM_EVENT_INVALID"))
            "session.revoked" -> {
                val revoked = JsonFields.string(payload, "sessionId") ?: error("PLATFORM_EVENT_INVALID")
                if (revoked == sessionId) {
                    GatewayLog.w("OaiConnection", "stage=EVENT_STREAM code=SESSION_REVOKED")
                    teardown()
                    _phase.value = ConnectionPhase.Failed("SESSION_REVOKED")
                }
            }
            "pairing.grant.changed" -> {
                val revision = JsonFields.long(payload, "grantRevision") ?: error("PLATFORM_EVENT_INVALID")
                check(revision >= 0) { "PLATFORM_EVENT_INVALID" }
                // Persisted pre-upgrade notifications have no target. They are
                // advisory only and cannot clear another device's local grant.
                val target = JsonFields.string(payload, "deviceId") ?: return true
                if (target != deviceId || revision <= gatewayGrantRevision) return true
                if (capabilityPublisher?.observeChange(revision.toInt(),JsonFields.string(payload,"grantDigest")) == true) {
                    gatewayGrantRevision = revision
                    return true
                }
                executionDriver?.cancelAll()
                pairingGrants.clearCurrent()
                pairingGrants.bind(binding)
                gatewayGrantRevision = revision
                _operationNotice.value = "网关授权已变更，请重新确认本机授权。"
            }
        }
        return true
    }

    private fun platformCursorProfile(profileId: String) = "$profileId:platform"

    private fun authClientFor(
        gatewayUrl: String,
        pinnedSpkiSha256: Set<String> = emptySet(),
    ): GatewayAuthClient = GatewayAuthClient(
        transport = GatewayTransport(
            GatewayProfile(
                accountId = "pre-auth",
                deviceId = "pre-auth",
                sessionId = "pre-auth",
                gatewayBaseUrl = gatewayUrl,
                pinnedSpkiSha256 = pinnedSpkiSha256,
            ),
        ),
        installationId = installationId(),
        appVersion = BuildConfig.VERSION_NAME.ifBlank { "unversioned" },
        platformApi = Build.VERSION.SDK_INT,
    )

    private var accessTokenHolder: String? = null
    private var activeHttpClient: GatewayHttpClient? = null

    /**
     * 当前会话的设备请求执行通路（契约 §10 claim/result）。
     *
     * 仅当网关协商通过了 `deviceRequests` 才存在；触发由网关下发
     * `device.request.*` 事件（条目 3-6），本机不会也没有理由自发产生请求。
     */
    var deviceRequestClient: com.openandroidintelligence.gateway.device.DeviceRequestClient? = null
        private set

    /** The session identity of the last successful login, for refresh/logout. */
    private var lastAccountId: String? = null
    private var lastDeviceId: String? = null
    private var lastSessionId: String? = null

    @Synchronized
    private fun installationId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL, null)?.let { return it }
        val created = "install_" + newToken()
        check(prefs.edit().putString(KEY_INSTALL, created).commit()) { "INSTALLATION_ID_PERSISTENCE_FAILED" }
        return created
    }

    private fun saveLastProfile(gatewayUrl: String, username: String, profileId: String, session: SessionCredentials) {
        val endpoint = checkNotNull(GatewayEndpoint.parse(gatewayUrl))
        val retained = if (endpoint.isTls) checkNotNull(identityTrust.retained(endpoint, username)) else null
        val trustId = if (endpoint.isTls) (retained?.spki ?: "ca:${retained?.deploymentId}") else ""
        accountProfiles.save(AccountProfile(profileId, gatewayUrl, username, trustId),
            AndroidAccountProfileStore.Binding(session.accountId, session.deviceId, session.sessionId, DEVICE_KEY_ENCODING_VERSION))
        _savedProfiles.value = accountProfiles.list()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GATEWAY, gatewayUrl)
            .putString(KEY_LAST_USER, username)
            .putString(KEY_LAST_PROFILE, profileId)
            .putString(KEY_LAST_ACCOUNT, session.accountId)
            .putString(KEY_LAST_DEVICE, session.deviceId)
            .putString(KEY_LAST_SESSION, session.sessionId)
            .putInt(KEY_DEVICE_KEY_ENCODING, DEVICE_KEY_ENCODING_VERSION)
            .apply()
        lastAccountId = session.accountId
        lastDeviceId = session.deviceId
        lastSessionId = session.sessionId
    }

    private fun clearLastProfile() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_GATEWAY)
            .remove(KEY_LAST_USER)
            .remove(KEY_LAST_PROFILE)
            .remove(KEY_LAST_ACCOUNT)
            .remove(KEY_LAST_DEVICE)
            .remove(KEY_LAST_SESSION)
            .remove(KEY_DEVICE_KEY_ENCODING)
            .apply()
        lastAccountId = null
        lastDeviceId = null
        lastSessionId = null
    }

    private fun profileIdFor(gatewayUrl: String, username: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$gatewayUrl|$username".toByteArray(Charsets.UTF_8))

    private fun newToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    private fun errorCode(cause: Throwable): String =
        cause.message?.takeIf { it.isNotBlank() } ?: cause::class.java.simpleName

    /**
     * The TLS identity this connection may pin.
     *
     * Only HTTPS can prove one: a plaintext address pins nothing, and a digest
     * returned over plaintext is unverifiable, so it is dropped rather than
     * trusted. `null` together with an HTTPS endpoint means the Gateway did not
     * return a usable identity, which the caller fails instead of silently
     * downgrading the account to unverified system trust.
     */

    private fun attachmentStagingStore(gatewayId: String, accountId: String, installId: String): LocalAttachmentStagingStore {
        val scopeId = "$gatewayId\n$accountId\n$installId"
        val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.noBackupFilesDir, "attachment-staging/$scopeHash")
        val provider = AndroidKeystoreOutboxKeyProvider("oai_attachment_${scopeHash.take(32)}")
        val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            EncryptedAttachmentStagingStore(directory, provider.getOrCreate(), scopeId)
        }
        return object : LocalAttachmentStagingStore {
            override fun stage(
                selection: LocalAttachmentSelection,
                onBytesStaged: (Long) -> Unit,
                isCancelled: () -> Boolean,
            ): StagedAttachmentContent = delegate.stage(selection, onBytesStaged, isCancelled)

            override fun openStream(stagedId: String): InputStream = delegate.openStream(stagedId)

            override fun delete(stagedId: String) = delegate.delete(stagedId)

            override fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long) =
                delegate.cleanupExpired(nowMillis, maxAgeMillis)

            override fun cleanup() {
                if (directory.exists()) {
                    val files = directory.listFiles() ?: error("ATTACHMENT_STORAGE_UNAVAILABLE")
                    files.filter { it.extension == "part" || it.extension == "stage" }.forEach {
                        check(it.delete()) { "ATTACHMENT_STORAGE_UNAVAILABLE" }
                    }
                }
            }
        }
    }

    private companion object {
        val PLATFORM_EVENTS = setOf("session.revoked", "pairing.grant.changed", "device.requested", "device.request.cancel.requested")
        const val PREFS_NAME = "open_android_intelligence_runtime"
        const val KEY_INSTALL = "installation_id"
        const val KEY_LAST_GATEWAY = "last_gateway_url"
        const val KEY_LAST_USER = "last_username"
        const val KEY_LAST_PROFILE = "last_profile_id"
        const val KEY_LAST_ACCOUNT = "last_account_id"
        const val KEY_LAST_DEVICE = "last_device_id"
        const val KEY_LAST_SESSION = "last_session_id"
        const val KEY_DEVICE_KEY_ENCODING = "device_key_encoding_version"
        const val DEVICE_KEY_ENCODING_VERSION = 1
    }
}

/**
 * 把续期失败翻译成用户能照做的说明，且不回显原始异常文本。
 *
 * 原始 message 可能带服务端正文或网络地址，对用户没有可执行意义；界面需要的
 * 是「现在该怎么办」，而不是再一次把内部字符串摊给用户。分类只认关键字，
 * 认不出来时给最保守的一条，而不是编造一个更具体的理由。
 */
internal fun refreshFailureNotice(cause: Throwable): String {
    val text = (cause.message ?: cause::class.java.simpleName).uppercase()
    return when {
        text.contains("REFRESH_REUSED") ->
            "Gateway 拒绝续期：刷新凭据已被使用过，请重新登录以取得新的会话凭据。"
        text.contains("GATEWAY_IDENTITY_CHANGED") ->
            "刷新网关凭据失败：网关部署身份或证书已变更，请核对后重新登录以建立新信任。"
        text.contains("PROTOCOL_INCOMPATIBLE") || text.contains(":406") ->
            "Gateway 拒绝续期：App 与 Gateway 的契约版本不一致，请把两端升级到同一版本后重试。"
        text.contains("401") || text.contains("403") ||
            text.contains("REVOKED") || text.contains("UNAUTHORIZED") ->
            "Gateway 拒绝续期：当前凭据已失效或被撤销，请重新登录。"
        text.contains("TIMEOUT") || text.contains("TIMED OUT") ->
            "刷新网关凭据失败：连接超时，当前连接仍然有效，请检查网络后重试。"
        text.contains("CONNECT") || text.contains("UNKNOWNHOST") || text.contains("IOEXCEPTION") ->
            "刷新网关凭据失败：无法连接 Gateway，当前连接仍然有效，请检查网络后重试。"
        else -> "刷新网关凭据失败，当前连接仍然有效，请稍后重试。"
    }
}
