package com.openandroidintelligence.mobile

import android.content.Context
import android.os.Build
import com.openandroidintelligence.conversation.data.GatewayAttachmentDraftCoordinator
import com.openandroidintelligence.conversation.data.GatewayCommandCatalogRepository
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.HttpAttachmentTransport
import com.openandroidintelligence.gateway.auth.AndroidKeystoreGatewayCredentialStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.GatewayCredentialStore
import com.openandroidintelligence.gateway.auth.SessionCredentials
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.device.DeviceRequestClient
import com.openandroidintelligence.gateway.device.HttpDeviceRequestTransport
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.SpkiPinning
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import com.openandroidintelligence.gateway.negotiation.NegotiatedLimits
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
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

    data class Connected(
        val gatewayUrl: String,
        val username: String,
        val limits: NegotiatedLimits?,
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
) {
    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _controller = MutableStateFlow<WorkbenchController?>(null)
    val controller: StateFlow<WorkbenchController?> = _controller.asStateFlow()

    private val _operationNotice = MutableStateFlow<String?>(null)
    val operationNotice: StateFlow<String?> = _operationNotice.asStateFlow()
    fun dismissOperationNotice() { _operationNotice.value = null }

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

    private val activeThread = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The login form's authoritative action; the UI only reflects the phase. */
    fun login(gatewayUrl: String, username: String, password: CharArray) {
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
            val negotiation = runCatching { authClientFor(normalized).negotiate("neg_" + newToken()) }
            val negotiated = negotiation.getOrElse { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }
            val tlsPin = negotiatedPin(endpoint, negotiated.tlsSpkiSha256)
            if (endpoint.isTls && tlsPin == null) {
                _phase.value = ConnectionPhase.Failed("NEGOTIATION_FAILED:missing-tls-identity")
                password.fill('\u0000')
                return@launch
            }

            _phase.value = ConnectionPhase.Authenticating
            val credentials = runCatching {
                val publicKey = deviceKeys.publicKeyBase64Url(profileId)
                authClientFor(normalized, setOfNotNull(tlsPin)).loginWithPassword(
                    negotiationId = negotiated.negotiationId,
                    username = username,
                    password = password,
                    displayName = Build.MODEL ?: "Android",
                    devicePublicKeyBase64Url = publicKey,
                )
            }
            credentials.fold(
                onSuccess = { session ->
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
                        limits = negotiated.limits,
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
            if (revokeRefresh) {
                pairingGrants.clearCurrent()
            }
            teardown()
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
                val tlsPin = negotiatedPin(endpoint, negotiated.tlsSpkiSha256)
                if (endpoint.isTls && tlsPin == null) {
                    _operationNotice.value =
                        "刷新网关凭据失败：Gateway 没有返回可核验的 TLS 身份，已按安全要求中止。"
                    return@launch
                }
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
                    limits = negotiated.limits,
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
            } catch (cause: Exception) {
                _operationNotice.value = refreshFailureNotice(cause)
            } finally {
                refreshBytes.fill(0)
                _isRefreshingSession.value = false
            }
        }
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
            val auth = authClientFor(endpoint.baseUrl)
            val negotiated = runCatching { auth.negotiate("neg_" + newToken()) }.getOrElse { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }
            val tlsPin = negotiatedPin(endpoint, negotiated.tlsSpkiSha256)
            if (endpoint.isTls && tlsPin == null) {
                _phase.value = ConnectionPhase.Failed("NEGOTIATION_FAILED:missing-tls-identity")
                return@launch
            }

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
                limits = negotiated.limits,
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
        limits: NegotiatedLimits?,
        tlsSpkiSha256: String?,
        conversationUi: List<String>,
        deviceRequests: String?,
        generationCancelCapability: GenerationCancelCapability = GenerationCancelCapability.NotNegotiated,
    ) {
        val pins = setOfNotNull(tlsSpkiSha256)
        val profile = GatewayProfile(
            accountId = session.accountId,
            deviceId = session.deviceId,
            sessionId = session.sessionId,
            gatewayBaseUrl = endpoint.baseUrl,
            pinnedSpkiSha256 = pins,
            accessToken = session.accessToken,
        )
        pairingGrants.bind(
            PairingGrantBinding(
                gatewayId = endpoint.baseUrl,
                accountId = session.accountId,
                installationId = installationId(),
            ),
        )
        val transport = GatewayTransport(profile)
        val eventStreamStatus = com.openandroidintelligence.gateway.events.EventStreamStatusSink()
        val http = GatewayHttpClient(
            profile = profile,
            transport = transport,
            signer = { preimage -> deviceKeys.sign(profileId, preimage) },
            cursorStore = InMemoryEventCursorStore(),
            statusSink = eventStreamStatus,
        )
        val conversationClient = ConversationClient(http)
        // Contract §7.2: approval cards are only wired when the Gateway said it
        // serves them. Without the endpoint there is nothing to press, so the
        // workbench says so instead of drawing a card that cannot be answered.
        val approvalClient = if ("agent-approval-cards-v1" in conversationUi) {
            com.openandroidintelligence.gateway.approvals.ApprovalClient(http)
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

        sessionJob?.cancel()
        val ownedJob = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = ownedJob
        val sessionScope = CoroutineScope(ownedJob + Dispatchers.Main.immediate)
        val attachmentTransport = HttpAttachmentTransport(http)
        val uploader = AttachmentUploader(attachmentTransport)
        val gate = com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate(
            onSubmit = { error("ATTACHMENT_SUBMISSION_REQUIRES_CONVERSATION_CONTROLLER") },
        )
        val attachmentCoordinator = GatewayAttachmentDraftCoordinator(uploader, gate, sessionScope)

        val conversationScope = ConversationScope(
            profileId = profileId,
            gatewayId = endpoint.baseUrl,
            accountId = session.accountId,
            installId = installationId(),
        )

        _controller.value = WorkbenchController(
            scope = sessionScope,
            repository = workbenchRepository,
            catalogRepository = catalogRepository,
            scopeFactory = { conversationScope },
            attachmentCoordinator = attachmentCoordinator,
            supportsMessageBatches = "message-batches-v1" in conversationUi,
            // Whether this Gateway serves the `/new` command entry. Without it
            // the workbench refuses to create a thread at all rather than
            // building one only the phone knows about.
            supportsAgentCommandNew = "agent-command-new-v1" in conversationUi,
            supportsApprovalCards = approvalClient != null,
            onActiveThreadChanged = { threadId -> activeThread.set(threadId) },
            streamHealthSource = workbenchRepository,
        )
        _phase.value = ConnectionPhase.Connected(
            gatewayUrl = endpoint.baseUrl,
            username = username,
            limits = limits,
            pairingSummary = session.pairingSummary,
            tlsSpkiSha256 = tlsSpkiSha256,
            transportSecurity = endpoint.securityFor(pins),
            // 协商交集：只保留客户端真的声明过并实现的能力。网关回了一个
            // 客户端没有的键，客户端无法兑现，不能让它以「已同意」的面目出现。
            conversationUi = CLIENT_CONVERSATION_UI_OFFER intersect conversationUi.toSet(),
            requestedConversationUi = CLIENT_CONVERSATION_UI_OFFER,
            deviceRequests = deviceRequests,
        )
    }

    private fun teardown() {
        sessionJob?.cancel()
        sessionJob = null
        _controller.value = null
        accessTokenHolder = null
        deviceRequestClient = null
        activeThread.set(null)
        pairingGrants.unbind()
        _phase.value = ConnectionPhase.Disconnected
    }

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
    private fun negotiatedPin(endpoint: GatewayEndpoint, negotiated: String?): String? =
        if (endpoint.isTls) negotiated?.takeIf(SpkiPinning::isProtocolPin) else null

    private companion object {
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
