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
import com.openandroidintelligence.gateway.auth.Ed25519DeviceKeyStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.SessionCredentials
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.SpkiPinning
import com.openandroidintelligence.gateway.http.TransportSecurity
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
    ) : ConnectionPhase

    data class Failed(val code: String) : ConnectionPhase
}

class GatewayRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairingGrants: PairingGrantStateHolder,
) {
    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _controller = MutableStateFlow<WorkbenchController?>(null)
    val controller: StateFlow<WorkbenchController?> = _controller.asStateFlow()

    private val _operationNotice = MutableStateFlow<String?>(null)
    val operationNotice: StateFlow<String?> = _operationNotice.asStateFlow()
    fun dismissOperationNotice() { _operationNotice.value = null }

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
                            keystoreCredentials.saveRefresh(profileId, refreshCred)
                            saveLastProfile(normalized, username, profileId, session)
                        }.onFailure { _operationNotice.value = "自动登录凭据未能保存，下次启动需要重新登录。" }
                    }
                    establish(endpoint, username, profileId, session, negotiated.limits, tlsPin, negotiated.conversationUi)
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
                keystoreCredentials.clearRefresh(profileId)
                clearLastProfile()
            }.onFailure { _operationNotice.value = "Gateway 已登出，但本机凭据清理失败，请检查设备存储。" }
            if (revokeRefresh) {
                pairingGrants.clearCurrent()
            }
            teardown()
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

        val refreshBytes = runCatching { keystoreCredentials.loadRefresh(lastProfileId) }.getOrNull() ?: return
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
                if (credentialRevoked(cause)) {
                    keystoreCredentials.clearRefresh(lastProfileId)
                    clearLastProfile()
                }
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }

            val newRefresh = session.refreshCredential
            if (newRefresh.isNotEmpty()) {
                runCatching {
                    keystoreCredentials.saveRefresh(lastProfileId, newRefresh)
                    saveLastProfile(endpoint.baseUrl, lastUser, lastProfileId, session)
                }.onFailure { _operationNotice.value = "轮换后的自动登录凭据未能保存，下次启动可能需要重新登录。" }
            }
            establish(endpoint, lastUser, lastProfileId, session, negotiated.limits, tlsPin, negotiated.conversationUi)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
            } finally {
                refreshBytes.fill(0)
            }
        }
    }

    /**
     * Whether the Gateway explicitly refused the credential.
     *
     * `GatewayAuthClient` surfaces `AUTHENTICATION_FAILED:<code-or-status>`, so
     * the decisive part is what follows the last colon.
     */
    private fun credentialRevoked(cause: Throwable): Boolean {
        val text = cause.message ?: return false
        if (text.contains("REFRESH_REUSED")) return true
        return text.substringAfterLast(':').trim().toIntOrNull() in setOf(401, 403)
    }

    private fun establish(
        endpoint: GatewayEndpoint,
        username: String,
        profileId: String,
        session: SessionCredentials,
        limits: NegotiatedLimits?,
        tlsSpkiSha256: String?,
        conversationUi: List<String>,
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
        val catalogRepository = GatewayCommandCatalogRepository(CommandCatalogClient(http))
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
            repository = repository,
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
            streamHealthSource = repository,
        )
        _phase.value = ConnectionPhase.Connected(
            gatewayUrl = endpoint.baseUrl,
            username = username,
            limits = limits,
            pairingSummary = session.pairingSummary,
            tlsSpkiSha256 = tlsSpkiSha256,
            transportSecurity = endpoint.securityFor(pins),
        )
    }

    private fun teardown() {
        sessionJob?.cancel()
        sessionJob = null
        _controller.value = null
        accessTokenHolder = null
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

    private val deviceKeys: Ed25519DeviceKeyStore by lazy {
        Ed25519DeviceKeyStore(File(context.filesDir, "gateway-credentials"))
    }

    private val keystoreCredentials: AndroidKeystoreGatewayCredentialStore by lazy {
        AndroidKeystoreGatewayCredentialStore(File(context.filesDir, "keystore-credentials").also { it.mkdirs() })
    }

    private var accessTokenHolder: String? = null

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
